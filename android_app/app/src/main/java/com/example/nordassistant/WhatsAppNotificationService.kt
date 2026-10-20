package com.example.nordassistant

import android.app.Notification
import android.content.Context
import android.database.Cursor
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * High-performance background notification listener for Nord Assistant / DEVIL.
 *
 * 1. ZERO-LATENCY INCOMING CALL ANNOUNCER:
 *    Captures incoming call notifications (Google Dialer, OnePlus InCallUI,
 *    WhatsApp calls, Truecaller, Telecom, etc.) and immediately speaks:
 *      "Boss, incoming call from <Caller Name / Number>"
 *    Crucial for Vibrate Mode: Uses STREAM_MUSIC / USAGE_ASSISTANCE_ACCESSIBILITY
 *    and ensures audible volume so DEVIL speaks out loud even when the phone
 *    is set to vibrate or silent!
 *
 * 2. WHATSAPP MESSAGE READER & FILTER:
 *    Captures personal messages and filters spam/commercial texts.
 */
class WhatsAppNotificationService : NotificationListenerService(), TextToSpeech.OnInitListener {

    data class StoredMessage(
        val sender: String,
        val message: String,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "DEVIL_CallAnnouncer"
        private const val MAX_MESSAGES = 30
        private val messageHistory = mutableListOf<StoredMessage>()
        var instance: WhatsAppNotificationService? = null

        // Call announcement state
        private var callTts: TextToSpeech? = null
        private var isTtsReady = false
        private var activeCallKey: String? = null
        private var activeCallerName: String? = null
        private var isCallRinging = false
        private var callAnnounceCount = 0
        private var callAnnounceStartTime = 0L

        // Spoken messages cache to ensure DEVIL NEVER repeats any message once spoken
        private val spokenMessageKeys = mutableSetOf<String>()

        private fun messageKey(sender: String, message: String): String {
            return "${sender.trim().lowercase(Locale.getDefault())}:::${message.trim().lowercase(Locale.getDefault())}"
        }

        fun isAlreadySpoken(sender: String, message: String): Boolean {
            synchronized(spokenMessageKeys) {
                return spokenMessageKeys.contains(messageKey(sender, message))
            }
        }

        /**
         * Confirms a message as spoken and triggers "Mark as read" on WhatsApp
         * so it is never repeated even if seen as unread later.
         */
        fun markMessageAsSpokenAndRead(sender: String, message: String, sbnKey: String? = null) {
            val key = messageKey(sender, message)
            synchronized(spokenMessageKeys) {
                spokenMessageKeys.add(key)
                if (spokenMessageKeys.size > 200) {
                    val first = spokenMessageKeys.iterator().next()
                    spokenMessageKeys.remove(first)
                }
            }
            Log.i(TAG, "Message confirmed as spoken & read: [$sender] $message")

            // Fire WhatsApp "Mark as read" action
            instance?.triggerMarkAsRead(sender, message, sbnKey)
        }

        /**
         * Returns recent non-commercial WhatsApp messages, optionally filtered by sender name.
         * Automatically excludes messages that DEVIL has already spoken out.
         */
        fun getRecentMessages(fromSender: String? = null, includeSpoken: Boolean = false): List<StoredMessage> {
            val results = mutableListOf<StoredMessage>()

            try {
                instance?.activeNotifications?.forEach { sbn ->
                    val pkg = sbn.packageName ?: return@forEach
                    if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
                        val extras = sbn.notification.extras ?: return@forEach
                        val sender = extras.getString(Notification.EXTRA_TITLE) ?: return@forEach
                        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                        val content = (bigText ?: text).trim()
                        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
                        val isGroupConv = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
                        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

                        // 🛑 Ignore group messages (group messages are never spoken out)
                        if (MessageFilter.isGroupMessage(sender, content, subText, isGroupConv, convTitle)) {
                            return@forEach
                        }

                        if (content.isNotEmpty() && !MessageFilter.isCommercialOrSpam(sender, content)) {
                            results.add(StoredMessage(sender, content, sbn.postTime))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying activeNotifications", e)
            }

            synchronized(messageHistory) {
                for (msg in messageHistory) {
                    if (results.none { it.sender == msg.sender && it.message == msg.message }) {
                        results.add(msg)
                    }
                }
            }

            // Exclude messages DEVIL already spoke about unless explicitly requested
            if (!includeSpoken) {
                results.removeAll { isAlreadySpoken(it.sender, it.message) }
            }

            results.sortByDescending { it.timestamp }

            if (fromSender.isNullOrBlank()) {
                return results.take(5)
            }
            val cleanQuery = fromSender.trim().lowercase(Locale.getDefault())
            return results.filter {
                it.sender.lowercase(Locale.getDefault()).contains(cleanQuery) ||
                        cleanQuery.contains(it.sender.lowercase(Locale.getDefault()))
            }.take(5)
        }

        fun hasMessages(): Boolean {
            synchronized(messageHistory) {
                return messageHistory.isNotEmpty() || (instance?.activeNotifications?.any {
                    it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b"
                } == true)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        initTts()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        initTts()
        Log.i(TAG, "DEVIL Notification & Call Service connected and active")
    }

    private fun initTts() {
        if (callTts == null) {
            try {
                callTts = TextToSpeech(applicationContext, this)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating TextToSpeech", e)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            callTts?.let { engine ->
                engine.language = Locale.US
                engine.setSpeechRate(1.15f)
                engine.setPitch(1.20f)
                try {
                    // Use USAGE_ASSISTANCE_ACCESSIBILITY: Plays loud and clear even in Vibrate Mode!
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    engine.setAudioAttributes(audioAttributes)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioAttributes setting fallback", e)
                }
            }
            isTtsReady = true
            Log.i(TAG, "DEVIL Call Announcer TTS pre-warmed & ready with 0 latency (vibrate-bypass ready)")
        } else {
            Log.e(TAG, "TTS init failed with status: $status")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val category = notification.category ?: ""

        val titleStr = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val textStr = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val subTextStr = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

        val isVip = isVipContact(titleStr) || isVipContact(textStr)
        if (!com.example.nordassistant.protocol.DevilProtocolManager.shouldAllowNotification(pkg, isVip)) {
            Log.d(TAG, "Silencing notification from $pkg ($titleStr) under active protocol: ${com.example.nordassistant.protocol.DevilProtocolManager.currentProtocol.title}")
            return
        }

        // ═════════════════════════════════════════════════════════════════
        // ── 1. ZERO-LATENCY INCOMING CALL DETECTION & DEVIL ANNOUNCEMENT ──
        // ═════════════════════════════════════════════════════════════════
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isInCall = audioManager?.mode == AudioManager.MODE_IN_CALL ||
                audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION

        val isCallCategory = category == Notification.CATEGORY_CALL
        val callType = extras.getInt(Notification.EXTRA_CALL_TYPE, -1)
        val isCallStyle = callType != -1 ||
                extras.getString(Notification.EXTRA_TEMPLATE)?.contains("CallStyle") == true
        val isIncomingCallType = callType == 1 // Notification.CallStyle.CALL_TYPE_INCOMING
        val isOngoingCallType = callType == 2  // Notification.CallStyle.CALL_TYPE_ONGOING

        val hasAnswerAction = notification.actions?.any { action ->
            val titleAct = action.title?.toString()?.lowercase(Locale.getDefault()) ?: ""
            titleAct.contains("answer") || titleAct.contains("accept")
        } ?: false

        val hasReplyAction = notification.actions?.any { action ->
            val titleAct = action.title?.toString()?.lowercase(Locale.getDefault()) ?: ""
            titleAct.contains("reply")
        } ?: false

        val isMissed = category == Notification.CATEGORY_MISSED_CALL ||
                titleStr.contains("missed", ignoreCase = true) ||
                textStr.contains("missed", ignoreCase = true)

        val isOngoing = isOngoingCallType || (textStr + " " + titleStr + " " + subTextStr).matches(
            Regex("(?i).*(ongoing|in call|call in progress|active call|mute|hold|dialing|calling|hang up).*")
        )

        // 🛑 CRITICAL: If a call was ringing and now became ongoing, missed, answered, or in-call, STOP IMMEDIATELY!
        if (isCallRinging && (isInCall || isOngoing || isMissed || isOngoingCallType)) {
            Log.i(TAG, "Active call state transition detected (isInCall: $isInCall, isOngoing: $isOngoing, isMissed: $isMissed). Stopping announcements.")
            stopCallAnnouncements()
            return
        }

        // If the user is actively on a phone or VoIP call, do not speak out loud
        if (isInCall) {
            return
        }

        val isGroupConv = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val isGroup = MessageFilter.isGroupMessage(titleStr, textStr, subTextStr, isGroupConv, convTitle)

        // Incoming Call Detection:
        // Must have an explicit incoming indicator (Answer button, incoming text, or CALL_TYPE_INCOMING)
        // Group messages, message notifications with "Reply", ongoing/outgoing calls are NEVER treated as calls!
        val isPhoneApp = pkg == "com.google.android.dialer" || pkg.contains("dialer") || pkg.contains("incallui") ||
                pkg.contains("phone") || pkg.contains("telecom") || pkg.contains("truecaller") || pkg.contains("oplus")
        val isWhatsApp = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"

        val hasIncomingText = textStr.contains("incoming", ignoreCase = true) ||
                subTextStr.contains("incoming", ignoreCase = true) ||
                titleStr.contains("incoming", ignoreCase = true)

        val isIncomingCall = !isMissed && !isOngoing && !isGroup && !hasReplyAction && (
                (isPhoneApp && (isIncomingCallType || hasAnswerAction || hasIncomingText)) ||
                (isWhatsApp && (isIncomingCallType || hasIncomingText || (hasAnswerAction && !hasReplyAction)))
        )

        if (isIncomingCall) {
            val rawCaller = when {
                titleStr.isNotBlank() && !titleStr.contains("incoming", ignoreCase = true) && !titleStr.contains("call", ignoreCase = true) -> titleStr
                textStr.isNotBlank() && !textStr.contains("incoming", ignoreCase = true) && !textStr.contains("call", ignoreCase = true) -> textStr
                titleStr.isNotBlank() -> titleStr
                textStr.isNotBlank() -> textStr
                else -> ""
            }

            if (rawCaller.isNotBlank()) {
                activeCallKey = sbn.key
                announceIncomingCall(rawCaller)
                return // Handled as incoming call
            }
        }

        // ═════════════════════════════════════════════════════════════════
        // ── 2. WHATSAPP MESSAGE HANDLING ─────────────────────────────────
        // ═════════════════════════════════════════════════════════════════
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        // 🛑 USER RULE: FOR GROUP MESSAGES DO NOT SPEAK OUT!
        if (isGroup) {
            Log.d(TAG, "Muting WhatsApp group notification: $titleStr (group messages are silent)")
            return
        }

        val sender = extras.getString(Notification.EXTRA_TITLE) ?: return
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val messageContent = (bigText ?: textStr).trim()

        if (messageContent.isEmpty()) return

        if (MessageFilter.isCommercialOrSpam(sender, messageContent)) {
            Log.i(TAG, "Skipping commercial/spam/system WhatsApp message from: $sender")
            return
        }

        var isNewPersonalMessage = false
        synchronized(messageHistory) {
            val isDuplicate = messageHistory.lastOrNull()?.let {
                it.sender == sender && it.message == messageContent && (System.currentTimeMillis() - it.timestamp < 3000)
            } ?: false

            if (!isDuplicate) {
                messageHistory.add(StoredMessage(sender, messageContent, System.currentTimeMillis()))
                if (messageHistory.size > MAX_MESSAGES) {
                    messageHistory.removeAt(0)
                }
                isNewPersonalMessage = true
                Log.d(TAG, "Captured WhatsApp personal message from $sender: $messageContent")
            }
        }

        // Politely announce incoming personal message once (never loops, never for groups, never if already spoken)
        if (isNewPersonalMessage && !isCallRinging && !isAlreadySpoken(sender, messageContent)) {
            announcePersonalWhatsAppMessage(sender, messageContent, sbn.key)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val key = sbn?.key
        val cat = sbn?.notification?.category
        val pkg = sbn?.packageName ?: ""

        if (key == activeCallKey || cat == Notification.CATEGORY_CALL ||
            pkg == "com.google.android.dialer" || pkg.contains("dialer") ||
            pkg.contains("incallui") || pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            stopCallAnnouncements()
        }
    }

    private fun stopCallAnnouncements() {
        isCallRinging = false
        activeCallKey = null
        activeCallerName = null
        callAnnounceCount = 0
        callAnnounceStartTime = 0L
        repeatRunnable?.let { mainHandler.removeCallbacks(it) }
        repeatRunnable = null
        try {
            callTts?.stop()
        } catch (_: Exception) {}
        Log.i(TAG, "Call ended, answered, or timed out. Stopped announcement.")
    }

    /**
     * Politely announces a 1-on-1 personal WhatsApp message.
     * Group messages are NEVER passed to this method.
     */
    private fun announcePersonalWhatsAppMessage(sender: String, message: String, sbnKey: String? = null) {
        if (!isTtsReady || callTts == null) {
            initTts()
            return
        }

        // Mark as spoken and read right away so it is NEVER repeated
        markMessageAsSpokenAndRead(sender, message, sbnKey)

        val cleanSender = resolveContactName(sender) ?: sender
        val preview = if (message.length > 70) message.substring(0, 70) + "..." else message
        val speech = "Boss, WhatsApp message from $cleanSender: $preview"
        Log.i(TAG, "📢 DEVIL SPEAK (Personal WhatsApp): $speech")

        ensureAudibleVolume()
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        callTts?.speak(speech, TextToSpeech.QUEUE_ADD, params, "DEVIL_WA_MSG_${System.currentTimeMillis()}")
    }

    /**
     * Triggers the "Mark as read" PendingIntent on the relevant WhatsApp notification.
     */
    fun triggerMarkAsRead(sender: String, message: String, sbnKey: String? = null) {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                val pkg = sbn.packageName ?: continue
                if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
                    if (sbnKey != null && sbn.key == sbnKey) {
                        executeMarkAsReadAction(sbn)
                        return
                    }
                    val extras = sbn.notification?.extras ?: continue
                    val notifSender = extras.getString(Notification.EXTRA_TITLE) ?: ""
                    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                    val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
                    val notifContent = (bigText.ifBlank { text }).trim()

                    if (notifSender.equals(sender, ignoreCase = true) || notifContent.contains(message.take(20), ignoreCase = true)) {
                        executeMarkAsReadAction(sbn)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in triggerMarkAsRead", e)
        }
    }

    private fun executeMarkAsReadAction(sbn: StatusBarNotification) {
        try {
            val actions = sbn.notification?.actions ?: return
            for (action in actions) {
                val title = action.title?.toString()?.lowercase(Locale.getDefault()) ?: ""
                val isSemanticRead = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    action.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ
                } else false

                if (title.contains("read") || isSemanticRead) {
                    action.actionIntent?.send()
                    Log.i(TAG, "Fired 'Mark as read' PendingIntent for WhatsApp notification (${sbn.key})")
                    return
                }
            }
            // Fallback: dismiss notification from drawer
            cancelNotification(sbn.key)
            Log.i(TAG, "Dismissed notification from drawer (${sbn.key})")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing mark as read on notification", e)
        }
    }

    /**
     * Announces the incoming caller with ZERO LATENCY.
     * Works even when phone is in Vibrate or Silent mode by routing to STREAM_MUSIC
     * and ensuring an audible media volume.
     */
    private fun announceIncomingCall(rawCaller: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val currentlyInCall = am?.mode == AudioManager.MODE_IN_CALL ||
                am?.mode == AudioManager.MODE_IN_COMMUNICATION
        if (currentlyInCall) {
            Log.i(TAG, "Device is already actively in-call. Skipping incoming call announcement.")
            return
        }

        // If already ringing for this caller, don't restart the whole cycle
        if (isCallRinging && rawCaller.equals(activeCallerName, ignoreCase = true)) {
            return
        }

        isCallRinging = true
        activeCallerName = rawCaller
        callAnnounceCount = 0
        callAnnounceStartTime = System.currentTimeMillis()

        // Check if rawCaller is a pure phone number (+91 98765 43210 or 9876543210)
        val digitsOnly = rawCaller.replace(Regex("[^0-9]"), "")
        val isNumber = digitsOnly.length >= 6 && rawCaller.matches(Regex("^[+0-9\\s()\\-]+$"))

        val spokenName = if (isNumber) {
            val contactName = resolveContactName(rawCaller)
            contactName ?: digitsOnly.toList().joinToString(" ")
        } else {
            rawCaller
        }

        val isAmma = spokenName.matches(Regex("(?i).*(amma|mom|mother|mummy).*"))
        val isVip = isAmma || isVipContact(spokenName)

        // Under Barn Door Protocol (Meeting Mode), silence non-emergency & non-VIP calls
        if (com.example.nordassistant.protocol.DevilProtocolManager.currentProtocol == com.example.nordassistant.protocol.DevilProtocol.BARN_DOOR && !isVip) {
            Log.i(TAG, "Silencing incoming call from $spokenName under Barn Door Protocol (Meeting Mode)")
            return
        }

        val initialPhrase = if (isAmma) {
            "Boss, incoming call from $spokenName"
        } else {
            "Boss, incoming call from $spokenName. Would you like to answer the call, or should I send a WhatsApp message?"
        }

        val repeatPhrase = if (isAmma) {
            "Incoming call from $spokenName"
        } else {
            "Incoming call from $spokenName. Should I answer, or send a WhatsApp message?"
        }
        val repeatDelay = if (isAmma) 6000L else 7500L

        Log.i(TAG, "⚡ DEVIL ZERO-LATENCY CALL ANNOUNCE: $initialPhrase (vibrate-bypass)")

        // Ensure volume is loud enough on STREAM_MUSIC so DEVIL is heard in vibrate mode
        ensureAudibleVolume()

        // 1. Speak immediately (0ms delay)
        speakCallPhrase(initialPhrase)

        // 2. Schedule follow-up announcement (maximum 2 repeats / 20 seconds) if still ringing
        repeatRunnable?.let { mainHandler.removeCallbacks(it) }
        repeatRunnable = object : Runnable {
            override fun run() {
                val currentAudio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val inActiveCall = currentAudio?.mode == AudioManager.MODE_IN_CALL ||
                        currentAudio?.mode == AudioManager.MODE_IN_COMMUNICATION

                if (inActiveCall || !isCallRinging || callAnnounceCount >= 2 || (System.currentTimeMillis() - callAnnounceStartTime > 20000L)) {
                    Log.i(TAG, "Stopping repeated call announcement (inActiveCall=$inActiveCall, count=$callAnnounceCount)")
                    stopCallAnnouncements()
                    return
                }

                callAnnounceCount++
                ensureAudibleVolume()
                speakCallPhrase(repeatPhrase)
                mainHandler.postDelayed(this, repeatDelay)
            }
        }
        mainHandler.postDelayed(repeatRunnable!!, repeatDelay)
    }

    /**
     * Ensures STREAM_MUSIC has adequate volume to speak over vibration/silence.
     */
    private fun ensureAudibleVolume() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            // If volume is less than 60%, boost to 80% for the announcement
            if (currentVol < (maxVol * 0.6).toInt()) {
                val targetVol = (maxVol * 0.80).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                Log.i(TAG, "Boosted STREAM_MUSIC volume from $currentVol to $targetVol for caller announcement in vibrate mode")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not adjust audio volume", e)
        }
    }

    private fun speakCallPhrase(phrase: String) {
        if (!isTtsReady || callTts == null) {
            initTts()
            mainHandler.postDelayed({ speakCallPhrase(phrase) }, 200)
            return
        }

        // STREAM_MUSIC guarantees sound output even when ringer mode is VIBRATE
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        callTts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, params, "DEVIL_CALL_${System.currentTimeMillis()}")
    }

    private fun isVipContact(name: String): Boolean {
        val clean = name.lowercase(Locale.getDefault()).trim()
        val vipList = listOf("amma", "mom", "mother", "mummy", "uj", "baddu", "bethol", "uma", "barath", "bharath")
        return vipList.any { clean.contains(it) || it.contains(clean) }
    }

    private fun resolveContactName(phoneNumber: String): String? {
        var cursor: Cursor? = null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            cursor = contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact query exception for: $phoneNumber", e)
            null
        } finally {
            cursor?.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        stopCallAnnouncements()
        try {
            callTts?.shutdown()
            callTts = null
            isTtsReady = false
        } catch (_: Exception) {}
    }
}
