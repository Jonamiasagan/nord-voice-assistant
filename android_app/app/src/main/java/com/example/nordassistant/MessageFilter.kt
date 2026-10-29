package com.example.nordassistant

import java.util.Locale

/**
 * Intelligent filter to detect commercial, promotional, spam, and system notifications.
 * Filters both SMS and WhatsApp messages so only genuine personal messages are read out loud.
 */
object MessageFilter {

    private val commercialKeywords = listOf(
        "offer", "discount", "cashback", "coupon", "promo", "voucher",
        "sale", "flat 50%", "flat 40%", "flat 30%", "flat 20%", "flat 70%",
        "hurry", "limited time", "deal of the day", "buy 1 get 1", "bogo",
        "loan", "pre-approved", "credit card", "apply now", "click here",
        "invest", "trading", "rummy", "win cash", "lottery", "prize",
        "recharge now", "exclusive deal", "subscribe now", "t&c apply",
        "opt-out", "unsubscribe", "reply stop", "claim now", "congratulations you won"
    )

    private val backupKeywords = listOf(
        "backup", "backing up", "chat backup", "google drive backup",
        "looking for backups", "finishing backup", "preparing backup",
        "uploading to google drive", "backup paused", "restoring chat",
        "restoring messages", "backup in progress", "waiting for wi-fi to backup",
        "google drive", "uploading"
    )

    private val systemMessages = listOf(
        "checking for new messages",
        "whatsapp web is currently active",
        "backup in progress",
        "incoming voice call",
        "incoming video call",
        "missed voice call",
        "missed video call"
    )

    /**
     * Identifies background WhatsApp backup, sync, and storage notifications
     * so they are completely silenced and never announced out loud.
     */
    fun isBackupNotification(
        sender: String,
        message: String,
        channelId: String? = null
    ): Boolean {
        if (channelId?.contains("backup", ignoreCase = true) == true) return true
        val lowerText = message.lowercase(Locale.getDefault())
        val lowerSender = sender.lowercase(Locale.getDefault())

        if (backupKeywords.any { lowerText.contains(it) || lowerSender.contains(it) }) return true
        if (lowerSender == "whatsapp" && (lowerText.contains("messages") || lowerText.contains("progress") ||
                    lowerText.contains("drive") || lowerText.contains("upload") || lowerText.contains("backup"))) {
            return true
        }
        return false
    }

    /**
     * Returns true if the message is deemed commercial, spam, or a background system message.
     */
    fun isCommercialOrSpam(sender: String, message: String): Boolean {
        if (isBackupNotification(sender, message)) return true

        val lowerText = message.lowercase(Locale.getDefault())
        val lowerSender = sender.lowercase(Locale.getDefault())

        // 1. WhatsApp / System background notifications
        for (sys in systemMessages) {
            if (lowerText.contains(sys)) return true
        }

        // 2. Generic "X new messages" placeholder
        if (lowerText.matches(Regex(".*\\d+\\s+new\\s+messages?.*"))) {
            return true
        }

        // 3. Indian telemarketing / commercial sender headers (e.g. AX-AIRTEL, VK-JIO, BZ-SWIGGY, JD-HDFC)
        val isCommercialSenderHeader = Regex("^[a-zA-Z]{2}-[a-zA-Z0-9]+$").matches(sender.trim())

        // Check for commercial keywords
        val containsCommercialWord = commercialKeywords.any { lowerText.contains(it) }

        if (isCommercialSenderHeader && containsCommercialWord) {
            return true
        }

        // 4. Commercial call-to-actions with links or promo codes
        if (containsCommercialWord && (lowerText.contains("http://") || lowerText.contains("https://") ||
                    lowerText.contains("bit.ly") || lowerText.contains("use code") ||
                    lowerText.contains("apply code") || lowerText.contains("valid till"))) {
            return true
        }

        return false
    }

    /**
     * Checks if a notification/message is from a group chat.
     * Used to ensure DEVIL never speaks out group notifications or group messages.
     * Incorporates deterministic WhatsApp JID inspection (@g.us vs @s.whatsapp.net),
     * channel IDs, conversation titles, and message body formatting.
     */
    fun isGroupMessage(
        title: String,
        text: String,
        subText: String = "",
        isGroupConversation: Boolean = false,
        conversationTitle: String? = null,
        channelId: String? = null,
        shortcutId: String? = null,
        tag: String? = null,
        key: String? = null
    ): Boolean {
        // 1. WhatsApp JID / Channel Deterministic Checks
        // Groups in WhatsApp always have JID ending with @g.us
        if (channelId?.contains("@g.us", ignoreCase = true) == true) return true
        if (shortcutId?.contains("@g.us", ignoreCase = true) == true) return true
        if (tag?.contains("@g.us", ignoreCase = true) == true) return true
        if (key?.contains("@g.us", ignoreCase = true) == true) return true

        // WhatsApp group notification channel
        if (channelId?.contains("group_chat", ignoreCase = true) == true) return true

        // Explicit 1-on-1 individual chat detection
        val isExplicitIndividual = (channelId?.contains("@s.whatsapp.net", ignoreCase = true) == true) ||
                (channelId?.contains("individual_chat", ignoreCase = true) == true) ||
                (shortcutId?.contains("@s.whatsapp.net", ignoreCase = true) == true) ||
                (tag?.contains("@s.whatsapp.net", ignoreCase = true) == true) ||
                (key?.contains("@s.whatsapp.net", ignoreCase = true) == true)

        // 2. Android Notification conversation properties
        if (isGroupConversation) return true
        if (!conversationTitle.isNullOrBlank()) return true

        // If verified as an individual chat via channel/JID, avoid false group positives
        if (isExplicitIndividual) {
            return false
        }

        val lowerTitle = title.lowercase(Locale.getDefault()).trim()
        val lowerText = text.lowercase(Locale.getDefault()).trim()
        val lowerSubText = subText.lowercase(Locale.getDefault()).trim()

        // e.g. "T Group (2 messages)", "Family (5 messages)"
        if (lowerTitle.contains(Regex("\\(\\d+\\s*messages?\\)")) || lowerTitle.contains(Regex("\\(\\d+\\)"))) return true
        if (lowerSubText.contains(Regex("\\(\\d+\\s*messages?\\)")) || lowerSubText.contains(Regex("\\(\\d+\\)"))) return true

        // WhatsApp group sender format: "Group: Sender" or "Sender @ Group"
        if (title.contains(":") || title.contains(" @ ")) return true

        // WhatsApp group message body format: "<ParticipantName>: <Message>" (e.g., "Chakradhar: https://...")
        // In personal 1-on-1 chats, WhatsApp message bodies NEVER start with a name followed by a colon.
        if (text.trim().matches(Regex("^[^:\\n]{1,40}:\\s+.*", RegexOption.DOT_MATCHES_ALL))) return true

        // Group message content prefixed by WhatsApp participant name: "~ Sudheer: hello" or "~ Sudheer"
        if (text.trim().startsWith("~") || text.trim().contains("\n~") || text.trim().matches(Regex("^~\\s*[^:]+:?.*"))) return true

        // Title contains common group keywords
        val groupKeywords = listOf(
            "group", "team", "batch", "family", "club", "community",
            "society", "office", "gang", "squad", "class", "project",
            "colleagues", "associates", "committee", "dept", "department",
            "friends", "trip", "roommates"
        )
        if (groupKeywords.any { lowerTitle.contains(it) }) {
            return true
        }

        // Generic multiple message summary e.g. "3 messages" or "2 new messages"
        if (lowerText.matches(Regex("^\\d+\\s+messages?$")) || lowerText.matches(Regex("^\\d+\\s+new\\s+messages?$"))) return true

        return false
    }
}
