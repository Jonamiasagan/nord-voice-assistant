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
import android.media.MediaRecorder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nordassistant.MainActivity
import com.example.nordassistant.R
import com.example.nordassistant.data.DarwinboxAlarmReceiver
import com.example.nordassistant.data.DarwinboxShiftTracker
import com.example.nordassistant.protocol.DevilProtocolManager
import java.util.Locale

/**
 * High-efficiency, zero-cloud Sentry Mode Foreground Service.
 *
 * Runs hands-free perimeter audio monitoring for "Hey DEVIL", "DEVIL", "Hey Jarvis", "Hey Friday", and "Hey Edith".
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

        private var instance: DevilSentryService? = null

        var isPausedForConversation: Boolean = false
            private set

        /**
         * Called by MainActivity when it begins TTS speech or an active conversation session.
         * Pauses Sentry Mode background audio recognition to prevent mic resource clashes
         * and audio feedback loops.
         */
        fun pauseForConversation() {
            isPausedForConversation = true
            instance?.mainHandler?.post {
                instance?.pauseSentryInternal()
            }
        }

        /**
         * Called by MainActivity when it transitions back to IDLE / "Standing by, Boss."
         * Resumes hands-free background listening cleanly.
         */
        fun resumeAfterConversation() {
            isPausedForConversation = false
            instance?.mainHandler?.post {
                instance?.resumeSentryInternal()
            }
        }

        /**
         * Static helper: arms the backup shift timer if the service is running.
         * Called from DarwinboxShiftTracker after check-in.
         */
        fun armBackupShiftTimerIfActive() {
            instance?.mainHandler?.post {
                instance?.armBackupShiftTimer()
            }
        }

        /**
         * Static helper: cancels the backup shift timer if the service is running.
         * Called from DarwinboxShiftTracker after check-out.
         */
        fun cancelBackupShiftTimerIfActive() {
            instance?.mainHandler?.post {
                instance?.cancelBackupShiftTimer()
            }
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRunning = false
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sentryVoiceRecorder = BossVoiceRecorder()
    private val sentryAudioBuffer = java.io.ByteArrayOutputStream()

    // ── Backup Shift Timer (OxygenOS-proof) ──
    // AlarmManager alarms are unreliable on OnePlus/OxygenOS.
    // This in-process Handler timer runs inside the foreground service
    // and is immune to battery optimization killing.
    private val shiftTimerHandler = Handler(Looper.getMainLooper())
    private var shiftTimerRunnable: Runnable? = null
    private var shiftCompleteRunnable: Runnable? = null

    private val wakeWordRegex = Regex(
        "(?i)\\b(?:" +
            "(?:hey|ok|hello|hi|hay|a|the|ha)?\\s*" +
            "(?:devil|deil|deveil|devin|david|davil|dabble|retailer|they\\s*will|day\\s*will|jarvis|friday|edith|table|level|bevel|double|travel|tell|heavy|terrible|debble|delhi|daily|dell|daniel|daryl|diver|driver|develop|dev|dave|abel|evil|able)" +
            "(?:\\s+sir)?|" +
            "(?:devil|deil|deveil|devin|david|davil|retailer|table|level|travel|they\\s*will|delhi|dell)" +
        ")\\b"
    )
    private val revokeSentryRegex = Regex("(?i)\\b((?:revoke|turn\\s*off|stop|disengage|cancel)\\s*(?:sentry|centri|centry|century|sentri|sentiment)(\\s*(?:mode|mde|mod))?|(?:sentry|centri|centry|century|sentri|sentiment)\\s*(?:mode|mde|mod)?\\s*(?:off|revoke|cancel|stop)|revoke\\s*(?:sentry|centri|centry|century|sentri|sentiment))\\b")

    private fun isDirectActionTrigger(text: String): Boolean {
        val lower = text.lowercase(Locale.getDefault()).trim()
        return lower.contains(Regex("(?i)\\b(check\\s*in|checkin|chicken\\s*in|chick\\s*in|cheque\\s*in|clock\\s*in|clockin|punch\\s*in|punchin|mark\\s*attendance|attendance\\s*in)\\b")) ||
               lower.contains(Regex("(?i)\\b(check\\s*out|checkout|chick\\s*out|cheque\\s*out|clock\\s*out|clockout|punch\\s*out|punchout|attendance\\s*out)\\b")) ||
               lower.contains(Regex("(?i)\\b(darwinbox|darwin\\s*box)\\b")) ||
               lower.contains(Regex("(?i)\\b(pause|resume|next\\s+song|previous\\s+song|skip\\s+song|stop\\s+music)\\b")) ||
               lower.contains(Regex("(?i)\\b(what\\s+time|what\\s+is\\s+the\\s+time|current\\s+time|what\\s+is\\s+the\\s+date|what\\s+date|today'?s?\\s+date)\\b")) ||
               lower.contains(Regex("(?i)\\b(how\\s+much\\s+time\\s+left|time\\s+to\\s+leave|shift\\s+time|remaining\\s+time)\\b")) ||
               lower.contains(Regex("(?i)\\b(holiday|holidays|upcoming\\s+holiday|leave\\s+section)\\b"))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        isServiceActive = true
        isPausedForConversation = false
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        isPausedForConversation = false
        startSentryListening()
        armBackupShiftTimer()

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

    private fun pauseSentryInternal() {
        Log.d(TAG, "⏸️ Sentry listener paused for active conversation")
        mainHandler.removeCallbacksAndMessages(null)
        isListening = false
        destroyRecognizer()
    }

    private fun resumeSentryInternal() {
        if (!isRunning || !isServiceActive) return
        Log.d(TAG, "▶️ Sentry listener resuming perimeter monitoring")
        isPausedForConversation = false
        scheduleRestart(400L)
    }

    private fun destroyRecognizer() {
        try {
            sentryVoiceRecorder.stopRecording()
        } catch (_: Exception) {}
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying speech recognizer", e)
        } finally {
            speechRecognizer = null
            isListening = false
        }
    }

    private var offlineSpeechSupported = true

    private fun startSentryListening() {
        if (!isRunning || isPausedForConversation) return

        mainHandler.post {
            if (!isRunning || isPausedForConversation) return@post
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                    speechRecognizer?.setRecognitionListener(SentryRecognitionListener())
                }

                val preferredLocale = if (Locale.getDefault().country.equals("IN", ignoreCase = true) || Locale.getDefault().language.equals("en", ignoreCase = true)) {
                    Locale("en", "IN")
                } else {
                    Locale.getDefault()
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferredLocale.toLanguageTag())
                    putExtra("android.speech.extra.GET_AUDIO", true)
                    if (offlineSpeechSupported) {
                        // Prefer offline recognition if local pack is installed
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    // Hardware multi-mic beamforming & noise/echo suppression
                    putExtra("android.speech.extra.AUDIO_SOURCE", MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    // Fast responsive endpointing so traffic noise doesn't hold the mic hostage
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 750L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                }

                isListening = true
                speechRecognizer?.startListening(intent)
                Log.d(TAG, "Sentry speech recognizer started listening (noise-suppression active)")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Sentry speech recognizer", e)
                destroyRecognizer()
                scheduleRestart(800L)
            }
        }
    }

    private fun scheduleRestart(delayMs: Long = 250L) {
        if (!isRunning || isPausedForConversation) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isRunning && !isListening && !isPausedForConversation) {
                startSentryListening()
            }
        }, delayMs)
    }

    private fun handleDetectedSpeech(rawText: String, pcmAudio: ByteArray = ByteArray(0)) {
        val text = rawText.trim()
        if (text.isEmpty() || isPausedForConversation) return

        Log.i(TAG, "Sentry heard: '$text'")

        // Voice biometrics gate removed — DEVIL responds to all voices freely.

        // 1. Check if Boss revoked Sentry Mode
        if (revokeSentryRegex.containsMatchIn(text) || DevilProtocolManager.isSentryModeRevokeTrigger(text)) {
            Log.i(TAG, "Sentry Mode revoke command detected: '$text'")
            val revokeIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("command", "revoke sentry mode")
            }
            startActivity(revokeIntent)
            stopSentryMode()
            return
        }

        // 2. Check if Boss commands a Protocol Switch while in Sentry Mode (e.g. "Boss Mode", "Switch to Jarvis", "Activate Friday", "Revoke all modes")
        if (DevilProtocolManager.isBossModeRevocation(text) ||
            DevilProtocolManager.isJarvisTrigger(text) ||
            DevilProtocolManager.isFridayTrigger(text) ||
            DevilProtocolManager.isEdithTrigger(text) ||
            DevilProtocolManager.isBarnDoorTrigger(text) ||
            DevilProtocolManager.isHousePartyTrigger(text) ||
            DevilProtocolManager.isBabyMonitorTrigger(text)) {
            Log.i(TAG, "⚡ SENTRY PROTOCOL SWITCH DETECTED: '$text'")
            isPausedForConversation = true
            mainHandler.removeCallbacksAndMessages(null)
            destroyRecognizer()

            val switchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("command", text)
            }
            startActivity(switchIntent)
            if (DevilProtocolManager.isBossModeRevocation(text)) {
                stopSentryMode()
            }
            return
        }

        // 2.5 Direct Action Command Execution (e.g. "do checkin in darwinbox", "do checkout in darwinbox", "what time is it")
        if (isDirectActionTrigger(text)) {
            Log.i(TAG, "⚡ SENTRY DIRECT ACTION TRIGGERED: '$text'")
            isPausedForConversation = true
            mainHandler.removeCallbacksAndMessages(null)
            destroyRecognizer()

            mainHandler.postDelayed({
                if (isRunning && isPausedForConversation) {
                    Log.i(TAG, "Watchdog: Auto-resuming Sentry after 45s timeout")
                    resumeAfterConversation()
                }
            }, 45_000L)

            val actionIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("command", text)
            }
            startActivity(actionIntent)
            return
        }

        // 3. Check if Wake-Word was spoken
        if (wakeWordRegex.containsMatchIn(text)) {
            Log.i(TAG, "⚡ SENTRY WAKE-WORD TRIGGERED: '$text'")
            
            // Immediately pause Sentry monitoring so mic is 100% free for MainActivity
            isPausedForConversation = true
            mainHandler.removeCallbacksAndMessages(null)
            destroyRecognizer()

            // Safety watchdog: In case MainActivity doesn't resume, auto-resume after 45s
            mainHandler.postDelayed({
                if (isRunning && isPausedForConversation) {
                    Log.i(TAG, "Watchdog: Auto-resuming Sentry after 45s timeout")
                    resumeAfterConversation()
                }
            }, 45_000L)

            // Extract any command spoken immediately after the wake-word
            val cleaned = text.replace(wakeWordRegex, "").trim()

            val wakeIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                if (cleaned.length >= 2 && !cleaned.equals("devil", ignoreCase = true) && !cleaned.equals("deil", ignoreCase = true)) {
                    putExtra("command", cleaned)
                } else {
                    putExtra("EXTRA_WAKE_DEVIL", true)
                }
            }
            startActivity(wakeIntent)
        }
    }

    private fun stopSentryMode() {
        isRunning = false
        isListening = false
        isServiceActive = false
        isPausedForConversation = false
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()

        stopForeground(true)
        stopSelf()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "🛑 DevilSentryService stopped successfully. Mic released.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSentryMode()
    }

    private inner class SentryRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
            Log.d(TAG, "Sentry mic armed and ready")
        }

        override fun onBeginningOfSpeech() {
            sentryAudioBuffer.reset()
            try {
                sentryVoiceRecorder.startRecording()
            } catch (e: Exception) {
                Log.w(TAG, "Sentry voice recorder start failed", e)
            }
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {
            if (buffer != null && buffer.isNotEmpty()) {
                synchronized(sentryAudioBuffer) {
                    sentryAudioBuffer.write(buffer)
                }
            }
        }
        override fun onEndOfSpeech() {
            isListening = false
        }

        override fun onError(error: Int) {
            isListening = false
            try {
                sentryVoiceRecorder.stopRecording()
            } catch (_: Exception) {}
            Log.w(TAG, "Sentry recognition error: $error")
            if (isRunning && !isPausedForConversation) {
                if (error == 13) {
                    Log.w(TAG, "Sentry error 13 (offline model missing). Falling back to dynamic speech recognition.")
                    offlineSpeechSupported = false
                    destroyRecognizer()
                } else if (error == 11 || 
                    error == SpeechRecognizer.ERROR_AUDIO || 
                    error == SpeechRecognizer.ERROR_CLIENT || 
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_SERVER) {
                    destroyRecognizer()
                }
                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 200L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 600L
                    else -> 400L
                }
                scheduleRestart(delay)
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val recordedBytes = try {
                sentryVoiceRecorder.stopRecording()
            } catch (_: Exception) {
                ByteArray(0)
            }
            val bufferBytes = synchronized(sentryAudioBuffer) { sentryAudioBuffer.toByteArray() }
            var pcmAudio = if (recordedBytes.isNotEmpty()) recordedBytes else bufferBytes

            if (pcmAudio.size < VoiceBiometrics.FRAME_SIZE * 4) {
                try {
                    val audioUri = results?.getParcelable<android.net.Uri>("android.speech.extra.AUDIO_STREAM")
                        ?: results?.getParcelable<android.net.Uri>("android.speech.extra.GET_AUDIO_STREAM")
                    if (audioUri != null) {
                        contentResolver.openInputStream(audioUri)?.use { stream ->
                            val rawBytes = stream.readBytes()
                            if (rawBytes.isNotEmpty()) {
                                pcmAudio = if (rawBytes.size > 44 && rawBytes[0] == 'R'.code.toByte() && rawBytes[1] == 'I'.code.toByte()) {
                                    rawBytes.copyOfRange(44, rawBytes.size)
                                } else {
                                    rawBytes
                                }
                                Log.d(TAG, "Retrieved ${pcmAudio.size} pcm bytes from speech audio URI")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read audio stream URI", e)
                }
            }

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.d(TAG, "Sentry onResults matches: $matches, audioBytes=${pcmAudio.size}")
                var triggered = false
                for (candidate in matches) {
                    if (wakeWordRegex.containsMatchIn(candidate) || 
                        isDirectActionTrigger(candidate) ||
                        revokeSentryRegex.containsMatchIn(candidate) ||
                        DevilProtocolManager.isSentryModeRevokeTrigger(candidate) ||
                        DevilProtocolManager.isBossModeRevocation(candidate) ||
                        DevilProtocolManager.isJarvisTrigger(candidate) ||
                        DevilProtocolManager.isFridayTrigger(candidate) ||
                        DevilProtocolManager.isEdithTrigger(candidate) ||
                        DevilProtocolManager.isBarnDoorTrigger(candidate) ||
                        DevilProtocolManager.isHousePartyTrigger(candidate) ||
                        DevilProtocolManager.isBabyMonitorTrigger(candidate)) {
                        handleDetectedSpeech(candidate, pcmAudio)
                        triggered = true
                        break
                    }
                }
                if (!triggered) {
                    Log.i(TAG, "Sentry heard: '${matches[0]}' (no wake-word match)")
                }
            }
            if (isRunning && !isListening && !isPausedForConversation) {
                scheduleRestart(200L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Do NOT trigger commands on partial results: audio recording is incomplete and cannot be biometric-verified!
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.v(TAG, "Sentry partial speech heard: ${matches[0]}")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Backup Shift Timer Implementation ──

    /**
     * Arms the backup in-process shift timer.
     * Checks DarwinboxShiftTracker for an active shift and schedules a
     * Handler callback at the overtime alert time. If the alarm time is
     * already in the past, or the user has checked out, does nothing.
     *
     * Called on service start and can be called externally after check-in.
     */
    fun armBackupShiftTimer() {
        // Cancel any previous timer first
        cancelBackupShiftTimer()

        val prefs = getSharedPreferences("devil_darwinbox_shift_prefs", MODE_PRIVATE)
        val shiftDate = prefs.getString("shift_date", null)
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date())
        if (shiftDate != todayStr) {
            Log.d(TAG, "Backup shift timer: No shift recorded for today. Standing down.")
            return
        }

        val isCheckedOut = prefs.getBoolean("is_checked_out", false)
        if (isCheckedOut) {
            Log.d(TAG, "Backup shift timer: Already checked out today. Standing down.")
            return
        }

        val now = System.currentTimeMillis()
        val targetMs = prefs.getLong("target_checkout_ms", 0L)
        val alertMs = prefs.getLong("alert_timestamp", 0L)

        // 1. Arm exact 9-hour shift complete backup
        if (targetMs > now) {
            val delayCompleteMs = targetMs - now
            val completeTimeStr = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(targetMs))
            Log.i(TAG, "🛡️⏰ Backup shift complete timer ARMED: will fire in ${delayCompleteMs / 60000}min at $completeTimeStr")

            shiftCompleteRunnable = Runnable {
                Log.i(TAG, "🛡️⏰ Backup shift complete timer FIRED! Checking if alert needed...")
                val currentPrefs = getSharedPreferences("devil_darwinbox_shift_prefs", MODE_PRIVATE)
                val stillCheckedIn = currentPrefs.getString("shift_date", null) == todayStr
                        && !currentPrefs.getBoolean("is_checked_out", false)

                if (stillCheckedIn) {
                    Log.i(TAG, "🛡️📢 User has NOT checked out! Firing 9-hour shift complete announcement.")
                    val completeIntent = Intent(this, DarwinboxAlarmReceiver::class.java).apply {
                        action = DarwinboxShiftTracker.ACTION_ALARM_SHIFT_COMPLETE
                    }
                    sendBroadcast(completeIntent)
                }
            }
            shiftTimerHandler.postDelayed(shiftCompleteRunnable!!, delayCompleteMs)
        }

        // 2. Arm 9h + 10m overdue backup
        if (alertMs > now) {
            val delayOverdueMs = alertMs - now
            val alertTimeStr = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(alertMs))
            Log.i(TAG, "🛡️⏰ Backup overdue timer ARMED: will fire in ${delayOverdueMs / 60000}min at $alertTimeStr")

            shiftTimerRunnable = Runnable {
                Log.i(TAG, "🛡️🚨 Backup overdue timer FIRED! Checking if alert needed...")
                val currentPrefs = getSharedPreferences("devil_darwinbox_shift_prefs", MODE_PRIVATE)
                val stillCheckedIn = currentPrefs.getString("shift_date", null) == todayStr
                        && !currentPrefs.getBoolean("is_checked_out", false)

                if (stillCheckedIn) {
                    Log.i(TAG, "🛡️📢 User has NOT checked out! Firing overtime alert from Sentry backup.")
                    val alarmIntent = Intent(this, DarwinboxAlarmReceiver::class.java).apply {
                        action = DarwinboxShiftTracker.ACTION_ALARM_OVERDUE
                    }
                    sendBroadcast(alarmIntent)
                } else {
                    Log.i(TAG, "🛡️✅ User already checked out. Backup timer suppressed.")
                }
            }
            shiftTimerHandler.postDelayed(shiftTimerRunnable!!, delayOverdueMs)
        }
    }

    /**
     * Cancels the backup shift timers (e.g. after user checks out).
     */
    fun cancelBackupShiftTimer() {
        shiftCompleteRunnable?.let {
            shiftTimerHandler.removeCallbacks(it)
        }
        shiftCompleteRunnable = null

        shiftTimerRunnable?.let {
            shiftTimerHandler.removeCallbacks(it)
            Log.d(TAG, "Backup shift timers canceled.")
        }
        shiftTimerRunnable = null
    }
}
