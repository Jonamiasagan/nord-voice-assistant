package com.example.nordassistant.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.nordassistant.audio.BluetoothAudioHelper
import java.util.Locale

/**
 * Universal voice announcer for DEVIL alerts (e.g. Darwinbox shift completion,
 * attendance confirmations, urgent alarms).
 * 
 * Crucial Audio Focus Contract:
 * - Requests transient audio focus (may duck) while speaking.
 * - ALWAYS abandons audio focus in onDone / onError to immediately restore
 *   full-volume background music (Spotify, YouTube Music, VLC, etc.).
 * - Resets audio mode back to MODE_NORMAL so Bluetooth returns to high-quality A2DP stereo.
 */
object DevilVoiceSpeaker : TextToSpeech.OnInitListener {
    private const val TAG = "DevilVoiceSpeaker"
    private var fallbackTts: TextToSpeech? = null
    private var isFallbackReady = false
    private val pendingPhrases = mutableListOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var activeFocusRequest: AudioFocusRequest? = null

    private val abandonFocusRunnable = Runnable {
        abandonAudioFocus()
    }

    fun speak(context: Context, phrase: String) {
        val appContext = context.applicationContext
        mainHandler.post {
            executeSpeak(appContext, phrase)
        }
    }

    private fun executeSpeak(context: Context, phrase: String) {
        Log.i(TAG, "🗣️ Spoken alert requested: \"$phrase\"")

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager = am

        // 1. Keep CPU awake while audio synthesizes and speaks
        acquireWakeLock(context, 15000L)

        // 2. Request transient audio focus with ducking while speaking
        requestAudioFocus(context)

        // 3. Fallback singleton TTS engine
        if (fallbackTts == null) {
            try {
                fallbackTts = TextToSpeech(context, this)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate fallback TextToSpeech", e)
            }
        }

        if (isFallbackReady && fallbackTts != null) {
            speakWithEngine(fallbackTts!!, phrase)
        } else {
            Log.i(TAG, "Fallback TTS initializing... phrase queued: \"$phrase\"")
            pendingPhrases.add(phrase)
        }

        // Safety fallback: auto-abandon audio focus after 8 seconds in case onDone is missed
        mainHandler.removeCallbacks(abandonFocusRunnable)
        mainHandler.postDelayed(abandonFocusRunnable, 8000L)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            fallbackTts?.let { engine ->
                engine.language = Locale.US
                engine.setSpeechRate(1.15f)
                engine.setPitch(1.20f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.i(TAG, "DevilVoiceSpeaker: speech started ($utteranceId)")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.i(TAG, "DevilVoiceSpeaker: speech completed ($utteranceId)")
                        mainHandler.post {
                            releaseWakeLock()
                            abandonAudioFocus()
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "DevilVoiceSpeaker: speech error ($utteranceId)")
                        mainHandler.post {
                            releaseWakeLock()
                            abandonAudioFocus()
                        }
                    }
                })
            }
            isFallbackReady = true
            Log.i(TAG, "DevilVoiceSpeaker: Fallback TTS ready. Flushing ${pendingPhrases.size} pending phrases")
            for (queued in pendingPhrases) {
                speakWithEngine(fallbackTts!!, queued)
            }
            pendingPhrases.clear()
        } else {
            Log.e(TAG, "DevilVoiceSpeaker: Fallback TTS initialization failed with status $status")
            isFallbackReady = false
            releaseWakeLock()
            abandonAudioFocus()
        }
    }

    private fun speakWithEngine(engine: TextToSpeech, phrase: String) {
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val utteranceId = "DEVIL_VOICE_ALERT_${System.currentTimeMillis()}"
        engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun requestAudioFocus(context: Context) {
        try {
            val am = audioManager ?: (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager) ?: return
            audioManager = am
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()
                activeFocusRequest = focusRequest
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
            Log.d(TAG, "Audio focus requested (TRANSIENT_MAY_DUCK)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not request audio focus", e)
        }
    }

    private fun abandonAudioFocus() {
        mainHandler.removeCallbacks(abandonFocusRunnable)
        try {
            val am = audioManager
            if (am != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activeFocusRequest?.let {
                        am.abandonAudioFocusRequest(it)
                    }
                    activeFocusRequest = null
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
                Log.i(TAG, "Audio focus abandoned - background music volume restored to 100%")
            }
            // Ensure Bluetooth routing is in normal mode
            BluetoothAudioHelper.resetToNormal()
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus", e)
        }
    }

    private fun acquireWakeLock(context: Context, timeoutMs: Long) {
        try {
            if (wakeLock == null || wakeLock?.isHeld == false) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "NordAssistant:DevilVoiceSpeaker"
                )
                wakeLock?.acquire(timeoutMs)
                Log.d(TAG, "WakeLock acquired for ${timeoutMs}ms")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (_: Exception) {}
    }
}
