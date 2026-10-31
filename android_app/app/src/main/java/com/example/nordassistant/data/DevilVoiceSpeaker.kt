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
import com.example.nordassistant.WhatsAppNotificationService
import java.util.Locale

/**
 * Universal voice announcer for DEVIL alerts (e.g. Darwinbox shift completion,
 * overtime checkout reminders, urgent alarms). Guarantees spoken audio even when:
 * 1. Screen is OFF or phone is locked (acquires WakeLock).
 * 2. Phone is in Silent or Vibrate mode (routes via STREAM_MUSIC at boosted volume).
 * 3. App is running in the background (uses pre-warmed WhatsAppNotificationService or self-initialized TTS).
 */
object DevilVoiceSpeaker : TextToSpeech.OnInitListener {
    private const val TAG = "DevilVoiceSpeaker"
    private var fallbackTts: TextToSpeech? = null
    private var isFallbackReady = false
    private val pendingPhrases = mutableListOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    fun speak(context: Context, phrase: String) {
        val appContext = context.applicationContext
        mainHandler.post {
            executeSpeak(appContext, phrase)
        }
    }

    private fun executeSpeak(context: Context, phrase: String) {
        Log.i(TAG, "🗣️ Spoken alert requested: \"$phrase\"")

        // 1. Boost volume for STREAM_MUSIC so it is heard loud & clear even on Silent/Vibrate
        boostAudioVolume(context)

        // 2. Keep CPU awake while audio synthesizes and speaks
        acquireWakeLock(context, 30000L)

        // 3. Request audio focus
        requestAudioFocus(context)

        // 4. Prefer pre-warmed WhatsAppNotificationService TTS if available
        if (WhatsAppNotificationService.isReady()) {
            Log.i(TAG, "Using pre-warmed WhatsAppNotificationService TTS for instant speech")
            WhatsAppNotificationService.speakOutLoud(phrase)
            return
        }

        // 5. Fallback singleton TTS engine
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
                        releaseWakeLock()
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "DevilVoiceSpeaker: speech error ($utteranceId)")
                        releaseWakeLock()
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

    private fun boostAudioVolume(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current < (max * 0.75).toInt()) {
                val target = (max * 0.85).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                Log.i(TAG, "Boosted STREAM_MUSIC volume from $current to $target for DEVIL voice alert")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not adjust audio volume", e)
        }
    }

    private fun requestAudioFocus(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not request audio focus", e)
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

