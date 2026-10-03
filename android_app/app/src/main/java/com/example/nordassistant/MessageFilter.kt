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
     * Returns true if the message is deemed commercial, spam, or a background system message.
     */
    fun isCommercialOrSpam(sender: String, message: String): Boolean {
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
}
