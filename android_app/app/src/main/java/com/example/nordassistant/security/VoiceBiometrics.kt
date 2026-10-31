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
     * Writes raw 16-bit 16kHz mono PCM bytes to a valid .wav file with standard RIFF header.
     */
    fun writeWavFile(pcmBytes: ByteArray, outputFile: java.io.File, sampleRate: Int = SAMPLE_RATE) {
        val totalAudioLen = pcmBytes.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = (16 * sampleRate * channels / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        outputFile.outputStream().use { fos ->
            fos.write(header)
            fos.write(pcmBytes)
        }
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
            if (rms < 0.0025f) continue // Skip silent / unvoiced background noise frames

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

        // 4. Aggregate frame statistics into a 64-dimensional speaker biometric vector.
        // CRITICAL FIX: We EXCLUDE mfcc[0] (c0 is the overall frame energy/gain DC component).
        // If c0 is included, its massive negative value dominates 99% of the L2-normalized vector,
        // causing ANY speaker or AI voice to match with 95%+ false similarity.
        // We use c1..c12 (spectral timbre and vocal tract shape) + pitch F0 + spectral dynamics.
        val embedding = FloatArray(EMBEDDING_DIM)
        val validMfccCount = NUM_MFCC - 1 // 12 coefficients (c1 to c12)

        // Slots 0-11: MFCC Mean (c1..c12)
        for (i in 0 until validMfccCount) {
            val coefIdx = i + 1
            var sum = 0f
            for (mfcc in frameMfccs) sum += mfcc[coefIdx]
            embedding[i] = sum / frameMfccs.size
        }

        // Slots 12-23: MFCC Standard Deviation (c1..c12)
        for (i in 0 until validMfccCount) {
            val coefIdx = i + 1
            val mean = embedding[i]
            var sumSq = 0f
            for (mfcc in frameMfccs) {
                val diff = mfcc[coefIdx] - mean
                sumSq += diff * diff
            }
            embedding[12 + i] = sqrt(sumSq / frameMfccs.size)
        }

        // Slots 24-35: MFCC Delta Mean (c1..c12 trajectory dynamics)
        if (frameMfccs.size > 2) {
            for (i in 0 until validMfccCount) {
                val coefIdx = i + 1
                var deltaSum = 0f
                for (t in 1 until frameMfccs.size - 1) {
                    deltaSum += (frameMfccs[t + 1][coefIdx] - frameMfccs[t - 1][coefIdx]) / 2f
                }
                embedding[24 + i] = deltaSum / (frameMfccs.size - 2)
            }
        }

        // Slots 36-47: MFCC Delta Standard Deviation (c1..c12)
        if (frameMfccs.size > 2) {
            for (i in 0 until validMfccCount) {
                val coefIdx = i + 1
                val deltaMean = embedding[24 + i]
                var sumSq = 0f
                for (t in 1 until frameMfccs.size - 1) {
                    val d = (frameMfccs[t + 1][coefIdx] - frameMfccs[t - 1][coefIdx]) / 2f
                    val diff = d - deltaMean
                    sumSq += diff * diff
                }
                embedding[36 + i] = sqrt(sumSq / (frameMfccs.size - 2))
            }
        }

        // Slots 48-51: Pitch (F0 fundamental frequency) Mean, Std Dev, Min, Max
        if (framePitches.isNotEmpty()) {
            val pitchMean = framePitches.average().toFloat()
            var pitchVar = 0f
            for (p in framePitches) pitchVar += (p - pitchMean) * (p - pitchMean)
            embedding[48] = pitchMean / 200f
            embedding[49] = sqrt(pitchVar / framePitches.size) / 50f
            embedding[50] = (framePitches.minOrNull() ?: pitchMean) / 200f
            embedding[51] = (framePitches.maxOrNull() ?: pitchMean) / 200f
        }

        // Slots 52-55: Spectral Centroid & Spectral Rolloff
        if (frameCentroids.isNotEmpty()) {
            val centMean = frameCentroids.average().toFloat()
            var centVar = 0f
            for (c in frameCentroids) centVar += (c - centMean) * (c - centMean)
            embedding[52] = centMean / 3000f
            embedding[53] = sqrt(centVar / frameCentroids.size) / 1500f
        }

        if (frameRolloffs.isNotEmpty()) {
            val rollMean = frameRolloffs.average().toFloat()
            var rollVar = 0f
            for (r in frameRolloffs) rollVar += (r - rollMean) * (r - rollMean)
            embedding[54] = rollMean / 5000f
            embedding[55] = sqrt(rollVar / frameRolloffs.size) / 2500f
        }

        // Slots 56-57: Voicing ratio & Energy ratio
        embedding[56] = (frameMfccs.size.toFloat() / numFrames).coerceIn(0f, 1f)
        embedding[57] = (framePitches.size.toFloat() / max(1, frameMfccs.size)).coerceIn(0f, 1f)

        // 5. L2-Normalize the final 64-dimensional feature vector
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
    @Volatile
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
            audioRecord?.stop()
            recordingThread?.join(300)
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
