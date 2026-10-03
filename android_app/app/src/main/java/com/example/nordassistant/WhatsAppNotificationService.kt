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

        /**
         * Returns recent non-commercial WhatsApp messages, optionally filtered by sender name.
         */
        fun getRecentMessages(fromSender: String? = null): List<StoredMessage> {
            synchronized(messageHistory) {
                if (fromSender.isNullOrBlank()) {
                    return messageHistory.takeLast(5).reversed()
                }
                val cleanQuery = fromSender.trim().lowercase(Locale.getDefault())
                return messageHistory.filter {
                    it.sender.lowercase(Locale.getDefault()).contains(cleanQuery) ||
                            cleanQuery.contains(it.sender.lowercase(Locale.getDefault()))
                }.takeLast(5).reversed()
            }
        }

        fun hasMessages(): Boolean {
            synchronized(messageHistory) {
                return messageHistory.isNotEmpty()
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
        Log.i(TAG, "WhatsApp Notification Listener connected successfully")
    }
}
