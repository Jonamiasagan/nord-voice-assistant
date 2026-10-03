package com.example.nordassistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale

/**
 * Background listener service for WhatsApp notifications.
 * Automatically captures incoming personal messages and filters out commercial/spam notifications.
 */
class WhatsAppNotificationService : NotificationListenerService() {

    data class StoredMessage(
        val sender: String,
        val message: String,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "WhatsAppService"
        private const val MAX_MESSAGES = 30
        private val messageHistory = mutableListOf<StoredMessage>()
        var instance: WhatsAppNotificationService? = null

        /**
         * Returns recent non-commercial WhatsApp messages, optionally filtered by sender name.
         * Scans both live active notifications from the status bar AND memory history.
         */
        fun getRecentMessages(fromSender: String? = null): List<StoredMessage> {
            val results = mutableListOf<StoredMessage>()

            // 1. Pull directly from active notifications currently on the phone!
            try {
                instance?.activeNotifications?.forEach { sbn ->
                    val pkg = sbn.packageName ?: return@forEach
                    if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
                        val extras = sbn.notification.extras ?: return@forEach
                        val sender = extras.getString(Notification.EXTRA_TITLE) ?: return@forEach
                        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                        val content = (bigText ?: text)?.trim() ?: return@forEach

                        if (content.isNotEmpty() && !MessageFilter.isCommercialOrSpam(sender, content)) {
                            results.add(StoredMessage(sender, content, sbn.postTime))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying activeNotifications", e)
            }

            // 2. Combine with captured message history
            synchronized(messageHistory) {
                for (msg in messageHistory) {
                    if (results.none { it.sender == msg.sender && it.message == msg.message }) {
                        results.add(msg)
                    }
                }
            }

            // Sort by most recent
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val extras = sbn.notification.extras ?: return
        val sender = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val messageContent = (bigText ?: text)?.trim() ?: return

        if (messageContent.isEmpty()) return

        // Commercial / Spam / System Filter
        if (MessageFilter.isCommercialOrSpam(sender, messageContent)) {
            Log.i(TAG, "Skipping commercial/spam/system WhatsApp message from: $sender")
            return
        }

        synchronized(messageHistory) {
            // Avoid duplicate identical consecutive notifications
            val isDuplicate = messageHistory.lastOrNull()?.let {
                it.sender == sender && it.message == messageContent && (System.currentTimeMillis() - it.timestamp < 3000)
            } ?: false

            if (!isDuplicate) {
                messageHistory.add(StoredMessage(sender, messageContent, System.currentTimeMillis()))
                if (messageHistory.size > MAX_MESSAGES) {
                    messageHistory.removeAt(0)
                }
                Log.d(TAG, "Captured WhatsApp message from $sender: $messageContent")
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "WhatsApp Notification Listener connected successfully")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }
}
