package com.example.nordassistant.security

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.*

/**
 * On-device Acoustic Feature Extractor and Voiceprint Verification Engine.
 *
 * Extracts Mel-Frequency Cepstral Coefficients (MFCC), Pitch (F0), Spectral Centroids,
 * and dynamic spectral trajectories from raw 16kHz PCM audio to produce a normalized
 * 64-dimensional biometric speaker embedding.
 */
object VoiceBiometrics {
    private const val TAG = "VoiceBiometrics"

    const val SAMPLE_RATE = 16000
    const val FRAME_SIZE = 512       // 32ms window
    const val HOP_SIZE = 256         // 16ms stride
    const val NUM_MEL_FILTERS = 26
    const val NUM_MFCC = 13
    const val EMBEDDING_DIM = 64

    private val hammingWindow = FloatArray(FRAME_SIZE) { i ->
        (0.54 - 0.46 * cos(2.0 * Math.PI * i / (FRAME_SIZE - 1))).toFloat()
    }

    private val melFilterBank: Array<FloatArray> by lazy {
        computeMelFilterbank(NUM_MEL_FILTERS, FRAME_SIZE, SAMPLE_RATE, 100f, 7500f)
    }

    /**
     * Extracts a normalized 64-dimensional speaker acoustic embedding from raw 16-bit PCM bytes.
     */
    fun extractEmbedding(pcmBytes: ByteArray): FloatArray {
        if (pcmBytes.size < FRAME_SIZE * 2) {
            Log.w(TAG, "Audio too short for embedding extraction (${pcmBytes.size} bytes)")
            return FloatArray(EMBEDDING_DIM)
        }

        // 1. Convert 16-bit PCM bytes to floats in [-1.0, 1.0]
        val numSamples = pcmBytes.size / 2
        val audio = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val byteL = pcmBytes[i * 2].toInt() and 0xFF
            val byteH = pcmBytes[i * 2 + 1].toInt()
            val sample = (byteH shl 8) or byteL
            audio[i] = sample / 32768.0f
        }

        // 2. Pre-emphasis filter: y(t) = x(t) - 0.97 * x(t-1)
        val filtered = FloatArray(audio.size)
        filtered[0] = audio[0]
        for (i in 1 until audio.size) {
            filtered[i] = audio[i] - 0.97f * audio[i - 1]
        }

        // 3. Slice into overlapping frames & extract frame features
        val numFrames = (filtered.size - FRAME_SIZE) / HOP_SIZE
        if (numFrames <= 0) return FloatArray(EMBEDDING_DIM)

        val frameMfccs = mutableListOf<FloatArray>()
        val framePitches = mutableListOf<Float>()
        val frameCentroids = mutableListOf<Float>()
        val frameRolloffs = mutableListOf<Float>()

        val real = FloatArray(FRAME_SIZE)
        val imag = FloatArray(FRAME_SIZE)
        val powerSpectrum = FloatArray(FRAME_SIZE / 2 + 1)

        for (f in 0 until numFrames) {
            val offset = f * HOP_SIZE

            // Check frame RMS energy (silence rejection)
            var sumSq = 0.0f
            for (i in 0 until FRAME_SIZE) {
                val s = filtered[offset + i]
                sumSq += s * s
            }
            val rms = sqrt(sumSq / FRAME_SIZE)
            if (rms < 0.012f) continue // Skip silent / unvoiced background noise frames

            // Apply Hamming window
            for (i in 0 until FRAME_SIZE) {
                real[i] = filtered[offset + i] * hammingWindow[i]
                imag[i] = 0.0f
            }

            // In-place Radix-2 FFT
            fft(real, imag)

            // Compute power spectrum (first N/2 + 1 bins)
            for (k in 0..FRAME_SIZE / 2) {
                val magSq = real[k] * real[k] + imag[k] * imag[k]
                powerSpectrum[k] = magSq / FRAME_SIZE
            }

            // Mel Filterbank energies
            val logMelEnergies = FloatArray(NUM_MEL_FILTERS)
            for (m in 0 until NUM_MEL_FILTERS) {
                var energy = 0.0f
                val filter = melFilterBank[m]
                for (k in 0..FRAME_SIZE / 2) {
                    energy += powerSpectrum[k] * filter[k]
                }
                logMelEnergies[m] = ln(max(energy, 1e-6f))
            }

            // DCT-II for 13 MFCCs
            val mfcc = FloatArray(NUM_MFCC)
            for (n in 0 until NUM_MFCC) {
                var sum = 0.0f
                for (m in 0 until NUM_MEL_FILTERS) {
                    sum += logMelEnergies[m] * cos((Math.PI * n * (m + 0.5) / NUM_MEL_FILTERS).toFloat())
                }
                mfcc[n] = sum
            }
            frameMfccs.add(mfcc)

            // Autocorrelation pitch (F0) estimation for voice fundamental frequency
            val pitch = estimatePitch(filtered, offset, FRAME_SIZE, SAMPLE_RATE)
            if (pitch > 50f && pitch < 450f) {
                framePitches.add(pitch)
            }

            // Spectral Centroid & Spectral Rolloff (85%)
            var totalPower = 0.0f
            var weightedSum = 0.0f
            for (k in 0..FRAME_SIZE / 2) {
                val p = powerSpectrum[k]
                totalPower += p
                weightedSum += p * (k * SAMPLE_RATE.toFloat() / FRAME_SIZE)
            }
            if (totalPower > 1e-6f) {
                frameCentroids.add(weightedSum / totalPower)
            }

            var cumPower = 0.0f
            val targetPower = totalPower * 0.85f
            var rolloffHz = 0.0f
            for (k in 0..FRAME_SIZE / 2) {
                cumPower += powerSpectrum[k]
                if (cumPower >= targetPower) {
                    rolloffHz = k * SAMPLE_RATE.toFloat() / FRAME_SIZE
                    break
                }
            }
            frameRolloffs.add(rolloffHz)
        }

        if (frameMfccs.isEmpty()) {
            Log.w(TAG, "No voiced speech frames detected in audio")
            return FloatArray(EMBEDDING_DIM)
        }

        // 4. Aggregate frame statistics into a fixed 64-dimensional feature vector
        val embedding = FloatArray(EMBEDDING_DIM)

        // Slots 0-12: MFCC Mean
        for (i in 0 until NUM_MFCC) {
            var sum = 0f
            for (mfcc in frameMfccs) sum += mfcc[i]
            embedding[i] = sum / frameMfccs.size
        }

        // Slots 13-25: MFCC Standard Deviation
        for (i in 0 until NUM_MFCC) {
            val mean = embedding[i]
            var sumSq = 0f
            for (mfcc in frameMfccs) {
                val diff = mfcc[i] - mean
                sumSq += diff * diff
            }
            embedding[13 + i] = sqrt(sumSq / frameMfccs.size)
        }

        // Slots 26-38: MFCC Delta (Trajectory dynamics)
        if (frameMfccs.size > 2) {
            for (i in 0 until NUM_MFCC) {
                var deltaSum = 0f
                for (t in 1 until frameMfccs.size - 1) {
                    deltaSum += (frameMfccs[t + 1][i] - frameMfccs[t - 1][i]) / 2f
                }
                embedding[26 + i] = deltaSum / (frameMfccs.size - 2)
            }
        }

        // Slots 39-51: Delta Standard Deviation
        if (frameMfccs.size > 2) {
            for (i in 0 until NUM_MFCC) {
                val deltaMean = embedding[26 + i]
                var sumSq = 0f
                for (t in 1 until frameMfccs.size - 1) {
                    val d = (frameMfccs[t + 1][i] - frameMfccs[t - 1][i]) / 2f
                    val diff = d - deltaMean
                    sumSq += diff * diff
                }
                embedding[39 + i] = sqrt(sumSq / (frameMfccs.size - 2))
            }
        }

        // Slots 52-53: Pitch (F0) Mean and Std Dev
        if (framePitches.isNotEmpty()) {
            val pitchMean = framePitches.average().toFloat()
            var pitchVar = 0f
            for (p in framePitches) pitchVar += (p - pitchMean) * (p - pitchMean)
            embedding[52] = pitchMean / 300f // normalized
            embedding[53] = sqrt(pitchVar / framePitches.size) / 100f
        }

        // Slots 54-55: Spectral Centroid Mean and Std Dev
        if (frameCentroids.isNotEmpty()) {
            val centMean = frameCentroids.average().toFloat()
            embedding[54] = centMean / 4000f
            var centVar = 0f
            for (c in frameCentroids) centVar += (c - centMean) * (c - centMean)
            embedding[55] = sqrt(centVar / frameCentroids.size) / 2000f
        }

        // Slots 56-57: Spectral Rolloff Mean and Std Dev
        if (frameRolloffs.isNotEmpty()) {
            val rollMean = frameRolloffs.average().toFloat()
            embedding[56] = rollMean / 6000f
            var rollVar = 0f
            for (r in frameRolloffs) rollVar += (r - rollMean) * (r - rollMean)
            embedding[57] = sqrt(rollVar / frameRolloffs.size) / 3000f
        }

        // Slots 58-63: Energy dynamics & frame stats
        embedding[58] = (frameMfccs.size.toFloat() / numFrames).coerceIn(0f, 1f) // Voicing ratio

        // 5. L2-Normalize the final embedding vector
        var normSq = 0f
        for (v in embedding) normSq += v * v
        val norm = sqrt(normSq)
        if (norm > 1e-6f) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }

        return embedding
    }

    /**
     * Estimates fundamental frequency (F0) using autocorrelation.
     */
    private fun estimatePitch(buffer: FloatArray, offset: Int, length: Int, sampleRate: Int): Float {
        val minLag = sampleRate / 400 // ~40 samples (400 Hz)
        val maxLag = sampleRate / 70  // ~228 samples (70 Hz)
        if (length <= maxLag) return 0f

        var maxCorr = -1.0f
        var bestLag = -1

        for (lag in minLag..maxLag) {
            var sum = 0.0f
            for (i in 0 until (length - lag)) {
                sum += buffer[offset + i] * buffer[offset + i + lag]
            }
            if (sum > maxCorr) {
                maxCorr = sum
                bestLag = lag
            }
        }

        return if (bestLag > 0) sampleRate.toFloat() / bestLag else 0f
    }

    /**
     * Generates a 26-band Mel filterbank.
     */
    private fun computeMelFilterbank(
        numFilters: Int,
        fftSize: Int,
        sampleRate: Int,
        lowFreq: Float,
        highFreq: Float
    ): Array<FloatArray> {
        fun hzToMel(hz: Float): Float = (2595.0 * log10(1.0 + hz / 700.0)).toFloat()
        fun melToHz(mel: Float): Float = (700.0 * (10.0.pow(mel / 2595.0) - 1.0)).toFloat()

        val lowMel = hzToMel(lowFreq)
        val highMel = hzToMel(highFreq)
        val melPoints = FloatArray(numFilters + 2)
        for (i in 0 until numFilters + 2) {
            melPoints[i] = lowMel + i * (highMel - lowMel) / (numFilters + 1)
        }

        val binPoints = IntArray(numFilters + 2)
        for (i in 0 until numFilters + 2) {
            val hz = melToHz(melPoints[i])
            binPoints[i] = floor((fftSize + 1) * hz / sampleRate).toInt().coerceIn(0, fftSize / 2)
        }

        val filterbank = Array(numFilters) { FloatArray(fftSize / 2 + 1) }
        for (m in 1..numFilters) {
            val left = binPoints[m - 1]
            val center = binPoints[m]
            val right = binPoints[m + 1]

            if (center > left) {
                for (k in left until center) {
                    filterbank[m - 1][k] = (k - left).toFloat() / (center - left)
                }
            }
            if (right > center) {
                for (k in center until right) {
                    filterbank[m - 1][k] = (right - k).toFloat() / (right - center)
                }
            }
        }
        return filterbank
    }

    /**
     * Radix-2 Cooley-Tukey FFT in-place (N = 512).
     */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * Math.PI / len
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (k in 0 until halfLen) {
                    val pos = i + k + halfLen
                    val tr = wR * real[pos] - wI * imag[pos]
                    val ti = wR * imag[pos] + wI * real[pos]

                    real[pos] = real[i + k] - tr
                    imag[pos] = imag[i + k] - ti
                    real[i + k] += tr
                    imag[i + k] += ti

                    val nextWR = wR * wStepR - wI * wStepI
                    wI = wR * wStepI + wI * wStepR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Dedicated high-speed PCM Audio Recorder for Voice Biometrics.
 */
class BossVoiceRecorder {
    private val TAG = "BossVoiceRecorder"

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val audioBuffer = ByteArrayOutputStream()
    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                VoiceBiometrics.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBufferSize, VoiceBiometrics.FRAME_SIZE * 4)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                VoiceBiometrics.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return
            }

            audioBuffer.reset()
            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread {
                val tempBuffer = ByteArray(1024)
                while (isRecording) {
                    val read = audioRecord?.read(tempBuffer, 0, tempBuffer.size) ?: 0
                    if (read > 0) {
                        synchronized(audioBuffer) {
                            audioBuffer.write(tempBuffer, 0, read)
                        }
                    }
                }
            }.apply { start() }
            Log.d(TAG, "Voice biometrics audio recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting voice recording", e)
            isRecording = false
        }
    }

    fun stopRecording(): ByteArray {
        if (!isRecording) return ByteArray(0)
        isRecording = false
        try {
            recordingThread?.join(200)
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioRecord: ${e.message}")
        }
        val data = synchronized(audioBuffer) { audioBuffer.toByteArray() }
        Log.d(TAG, "Voice biometrics captured: ${data.size} bytes (${data.size / (VoiceBiometrics.SAMPLE_RATE * 2f)}s)")
        return data
    }

    /**
     * Records audio for calibration duration (e.g. 3.5 seconds) on a background thread.
     */
    suspend fun recordCalibration(durationMs: Long = 3500L): ByteArray = withContext(Dispatchers.IO) {
        startRecording()
        kotlinx.coroutines.delay(durationMs)
        stopRecording()
    }
}
