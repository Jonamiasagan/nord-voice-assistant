package com.example.nordassistant.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nordassistant.MainActivity
import com.example.nordassistant.R
import com.example.nordassistant.protocol.DevilProtocolManager
import java.util.Locale

/**
 * High-efficiency, zero-cloud Sentry Mode Foreground Service.
 *
 * Runs hands-free perimeter audio monitoring for "Hey DEVIL", "Hey Jarvis", "Hey Friday", and "Hey Edith".
 * Displays the Android green mic indicator while armed, and shuts down to 0% CPU when revoked.
 */
class DevilSentryService : Service() {

    companion object {
        const val TAG = "DevilSentryService"
        const val ACTION_START_SENTRY = "com.example.nordassistant.ACTION_START_SENTRY"
        const val ACTION_STOP_SENTRY = "com.example.nordassistant.ACTION_STOP_SENTRY"

        private const val CHANNEL_ID = "devil_sentry_channel"
        private const val NOTIFICATION_ID = 9001
        
        var isServiceActive = false
            private set
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRunning = false
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val wakeWordRegex = Regex("(?i)\\b(hey\\s*devil|devil|hey\\s*jarvis|jarvis|hey\\s*friday|friday|hey\\s*edith|edith)\\b")
    private val revokeSentryRegex = Regex("(?i)\\b(revoke\\s*sentry(\\s*mode)?|sentry\\s*mode\\s*(off|revoke|cancel|stop)|turn\\s*off\\s*sentry|stop\\s*sentry|disengage\\s*sentry)\\b")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isServiceActive = true
        Log.i(TAG, "🛡️ DevilSentryService created and active")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SENTRY

        if (action == ACTION_STOP_SENTRY) {
            Log.i(TAG, "Received ACTION_STOP_SENTRY. Shutting down Sentry Mode.")
            stopSentryMode()
            return START_NOT_STICKY
        }

        // Start Foreground Notification
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        startSentryListening()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DEVIL Sentry Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hands-free perimeter audio sensors for DEVIL voice assistant"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val revokeIntent = Intent(this, DevilSentryService::class.java).apply {
            action = ACTION_STOP_SENTRY
        }
        val pendingRevoke = PendingIntent.getService(
            this,
            1,
            revokeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DEVIL // 🛡️ SENTRY MODE ACTIVE")
            .setContentText("Perimeter audio sensors online. Say 'Hey DEVIL' or 'Hey Jarvis'.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Revoke Sentry", pendingRevoke)
            .build()
    }

    private fun startSentryListening() {
        if (!isRunning) return

        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                    speechRecognizer?.setRecognitionListener(SentryRecognitionListener())
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                isListening = true
                speechRecognizer?.startListening(intent)
                Log.d(TAG, "Sentry speech recognizer started (Offline prefered)")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Sentry speech recognizer", e)
                scheduleRestart(1000L)
            }
        }
    }

    private fun scheduleRestart(delayMs: Long = 350L) {
        if (!isRunning) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isRunning && !isListening) {
                startSentryListening()
            }
        }, delayMs)
    }

    private fun handleDetectedSpeech(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        Log.i(TAG, "Sentry heard: '$text'")

        // 1. Check if Boss revoked Sentry Mode
        if (revokeSentryRegex.containsMatchIn(text)) {
            Log.i(TAG, "Sentry Mode revoke command detected: '$text'")
            val revokeIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("command", "revoke sentry mode")
            }
            startActivity(revokeIntent)
            stopSentryMode()
            return
        }

        // 2. Check if Wake-Word was spoken
        if (wakeWordRegex.containsMatchIn(text)) {
            Log.i(TAG, "⚡ SENTRY WAKE-WORD TRIGGERED: '$text'")
            isListening = false
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}

            // Extract any command spoken immediately after the wake-word
            val cleaned = text.replace(wakeWordRegex, "").trim()

            val wakeIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                if (cleaned.length >= 2) {
                    putExtra("command", cleaned)
                } else {
                    putExtra("EXTRA_WAKE_DEVIL", true)
                }
            }
            startActivity(wakeIntent)

            // Temporarily pause Sentry listener for 10 seconds while MainActivity converses
            scheduleRestart(10_000L)
        }
    }

    private fun stopSentryMode() {
        isRunning = false
        isListening = false
        isServiceActive = false
        mainHandler.removeCallbacksAndMessages(null)

        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying speech recognizer", e)
        }

        stopForeground(true)
        stopSelf()
        Log.i(TAG, "🛑 DevilSentryService stopped successfully. Mic released.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSentryMode()
    }

    private inner class SentryRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            isListening = false
        }

        override fun onError(error: Int) {
            isListening = false
            if (isRunning) {
                // Quickly restart on timeouts / no match to maintain perimeter watch
                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                    else -> 600L
                }
                scheduleRestart(delay)
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                handleDetectedSpeech(matches[0])
            }
            if (isRunning && !isListening) {
                scheduleRestart(350L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val candidate = matches[0]
                if (wakeWordRegex.containsMatchIn(candidate) || revokeSentryRegex.containsMatchIn(candidate)) {
                    handleDetectedSpeech(candidate)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
