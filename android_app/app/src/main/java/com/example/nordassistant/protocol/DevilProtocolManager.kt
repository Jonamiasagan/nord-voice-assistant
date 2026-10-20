package com.example.nordassistant.protocol

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.util.Log

enum class DevilProtocol(
    val id: String,
    val title: String,
    val description: String
) {
    BOSS_MODE(
        "boss_mode",
        "DEVIL // BOSS SECURED",
        "Default master unrestricted clearance with dynamic, cheerful voice."
    ),
    BARN_DOOR(
        "barn_door",
        "DEVIL // 🤫 BARN DOOR PROTOCOL",
        "Meeting Mode: All media, notifications and non-emergency calls silenced. Whispered formal assistant."
    ),
    HOUSE_PARTY(
        "house_party",
        "DEVIL // ⏳ HOUSE PARTY PROTOCOL",
        "Development Mode: Strict technical filters, auto-reply armed, high-efficiency brief assistant."
    ),
    JARVIS(
        "jarvis",
        "DEVIL // 👔 J.A.R.V.I.S. PROTOCOL",
        "Professional Mode: Formal British male accent, work prioritized, distraction apps shielded."
    ),
    FRIDAY(
        "friday",
        "DEVIL // 🎙️ F.R.I.D.A.Y. PROTOCOL",
        "Tactical Companion Mode: Fast-paced, witty, warm Irish/warm female companion."
    ),
    EDITH(
        "edith",
        "DEVIL // 🕶️ E.D.I.T.H. PROTOCOL",
        "Tactical Security & Defense Mode: Ultra-calm, analytical, crisp tactical synth female voice."
    ),
    BABY_MONITOR(
        "baby_monitor",
        "DEVIL // 🍹 BABY MONITOR PROTOCOL",
        "Leisure Mode: Warm informal female voice, work communication blocked, entertainment unlocked."
    )
}

object DevilProtocolManager {
    private const val TAG = "DevilProtocolManager"
    private const val PREFS_NAME = "devil_protocol_prefs"
    private const val KEY_PROTOCOL = "active_protocol"

    private var prefs: SharedPreferences? = null

    var currentProtocol: DevilProtocol = DevilProtocol.BOSS_MODE
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs?.getString(KEY_PROTOCOL, DevilProtocol.BOSS_MODE.id)
        currentProtocol = DevilProtocol.values().find { it.id == saved } ?: DevilProtocol.BOSS_MODE
        Log.i(TAG, "DevilProtocolManager initialized. Active protocol: ${currentProtocol.name}")
    }

    fun setProtocol(protocol: DevilProtocol, context: Context): DevilProtocol {
        currentProtocol = protocol
        prefs?.edit()?.putString(KEY_PROTOCOL, protocol.id)?.apply()
        Log.i(TAG, "⚡ Active Protocol switched to: ${protocol.title}")
        applySystemSettings(protocol, context)
        return protocol
    }

    private fun applySystemSettings(protocol: DevilProtocol, context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            when (protocol) {
                DevilProtocol.BARN_DOOR -> {
                    // Mute media, notifications, and non-emergency ringers
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                }
                DevilProtocol.BOSS_MODE -> {
                    // Restore standard normal ringer & audible media
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    val maxMedia = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMedia * 0.75f).toInt(), 0)
                }
                else -> {
                    // Normal audio for other protocols
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio settings adjustment note: ${e.message}")
        }
    }

    /**
     * Checks if an app is restricted under J.A.R.V.I.S. Protocol during professional hours.
     */
    fun isAppRestricted(packageName: String): Boolean {
        if (currentProtocol != DevilProtocol.JARVIS) return false
        val restrictedPkgs = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.netflix.mediaclient",
            "com.facebook.katana",
            "com.zhiliaoapp.musically",
            "tv.twitch.android.app",
            "com.dts.freefireth",
            "com.tencent.ig",
            "com.pubg.imobile"
        )
        return restrictedPkgs.contains(packageName)
    }

    /**
     * Filters notifications according to the active protocol:
     * - BARN_DOOR: Only VIP callers/contacts allowed
     * - HOUSE_PARTY: Only technical alerts (GitHub, Termux, Jira) and VIPs allowed
     * - JARVIS: Restricts casual social feeds and gaming notifications
     * - BABY_MONITOR: Blocks work apps (Teams, Slack, Outlook, Darwinbox)
     * - BOSS_MODE: Unrestricted
     */
    fun shouldAllowNotification(packageName: String, isVip: Boolean): Boolean {
        return when (currentProtocol) {
            DevilProtocol.BARN_DOOR -> isVip
            DevilProtocol.HOUSE_PARTY -> {
                if (isVip) return true
                val techPkgs = setOf("com.github.android", "com.termux", "com.atlassian.android.jira.core")
                techPkgs.contains(packageName)
            }
            DevilProtocol.JARVIS -> {
                val leisurePkgs = setOf("com.instagram.android", "com.google.android.youtube", "com.netflix.mediaclient")
                !leisurePkgs.contains(packageName)
            }
            DevilProtocol.BABY_MONITOR -> {
                val workPkgs = setOf(
                    "com.microsoft.teams",
                    "com.slack",
                    "com.microsoft.office.outlook",
                    "com.darwinbox.darwinbox"
                )
                !workPkgs.contains(packageName)
            }
            DevilProtocol.FRIDAY -> true
            DevilProtocol.EDITH -> {
                val leisurePkgs = setOf("com.instagram.android", "com.google.android.youtube", "com.netflix.mediaclient")
                !leisurePkgs.contains(packageName)
            }
            DevilProtocol.BOSS_MODE -> true
        }
    }

    /**
     * Flexible phonetic matching for "Barn Door Protocol" accommodating
     * Indian English pronunciation variations (e.g. "baarn", "baan", "baron", "burn", "bahn").
     */
    fun isBarnDoorTrigger(input: String): Boolean {
        val lower = input.lowercase().trim()
        if (lower.contains("barn door") || lower.contains("barndoor") || lower.contains("barn-door")) return true
        if (lower.contains("meeting mode") || lower.contains("silent protocol")) return true

        // Regex for phonetic variations of "barn" followed optionally by "door" or "protocol"
        val pattern = Regex(
            "(?i)\\b(ba+r+n|ba+rn|ba+n|ba+h+n|ba+r+o+n|bu+r+n|bo+r+n|bo+r+ne|bha+r+n|bha+n|band|bound)\\s*(door|dor|dhor|protocol|protocal|mode)?\\b"
        )
        return pattern.containsMatchIn(lower) && (
            lower.contains("door") || lower.contains("dor") || lower.contains("protocol") ||
            lower.contains("mode") || lower.contains("start") || lower.contains("turn on") ||
            lower.contains("activate") || lower.contains("enter")
        )
    }

    /**
     * Checks if input matches House Party Protocol (Development / Busy mode).
     */
    fun isHousePartyTrigger(input: String): Boolean {
        val lower = input.lowercase().trim()
        return lower.contains(Regex("(?i)\\b(house\\s*party|development\\s*mode|dev\\s*mode|busy\\s*mode|coding\\s*mode|hackathon\\s*mode)\\b"))
    }

    /**
     * Checks if input matches J.A.R.V.I.S. Protocol (Professional mode).
     * Includes phonetic variations for Indian English STT (e.g., "gyaras", "gyras", "jervis").
     */
    fun isJarvisTrigger(input: String): Boolean {
        val lower = input.lowercase().trim()
        return lower.contains(Regex("(?i)\\b(jarvis|jarves|jervis|jarvys|javis|zarvis|gyaras|gyras|gyrus|gyros|j\\.a\\.r\\.v\\.i\\.s\\.|professional\\s*mode|business\\s*mode|office\\s*mode|work\\s*mode)\\b"))
    }

    /**
     * Checks if input matches Baby Monitor / Chill Protocol (Leisure mode).
     */
    fun isBabyMonitorTrigger(input: String): Boolean {
        val lower = input.lowercase().trim()
        return lower.contains(Regex("(?i)\\b(baby\\s*monitor|babymonitor|sunset\\s*protocol|chill\\s*(mode|protocol)?|leisure\\s*mode|relax\\s*mode|evening\\s*mode)\\b"))
    }

    /**
     * Checks if input matches F.R.I.D.A.Y. Protocol (Tactical companion mode).
     */
    fun isFridayTrigger(input: String): Boolean {
        val lower = input.lowercase().trim()
        return lower.contains(Regex("(?i)\\b(friday|fryday|f\\.r\\.i\\.d\\.a\\.y\\.|switch\\s+to\\s+friday|protocol\\s+friday|friday\\s+mode|companion\\s+mode)\\b"))
    }

    /**
     * Checks if input matches E.D.I.T.H. Protocol (Tactical defense mode).
     */
    fun isEdithTrigger(input: String): Boolean {
        val lower = input.lowercase().trim()
        return lower.contains(Regex("(?i)\\b(edith|e\\.d\\.i\\.t\\.h\\.|switch\\s+to\\s+edith|protocol\\s+edith|edith\\s+mode|tactical\\s+mode|defense\\s+mode)\\b"))
    }

    /**
     * Checks if input requests activation of Sentry Mode (Hands-Free Wake-Word listening).
     */
    fun isSentryModeTrigger(input: String): Boolean {
        val lower = input.lowercase().trim()
        return lower.contains(Regex("(?i)\\b((activate|engage|turn\\s*on|start|enter|enable)\\s+sentry(\\s*mode)?|sentry\\s*mode\\s*(on|active|engage|start)|sentry\\s*mode)\\b"))
    }

    /**
     * Checks if input requests revocation/disengagement of Sentry Mode.
     */
    fun isSentryModeRevokeTrigger(input: String): Boolean {
        val lower = input.lowercase().trim()
        return lower.contains(Regex("(?i)\\b((revoke|disengage|turn\\s*off|stop|disable|exit|cancel)\\s+sentry(\\s*mode)?|sentry\\s*mode\\s*(off|revoke|cancel|stop)|revoke\\s*sentry)\\b"))
    }

    var isSentryModeActive: Boolean = false
        private set

    fun activateSentryMode(context: Context) {
        isSentryModeActive = true
        try {
            val intent = android.content.Intent(context, com.example.nordassistant.security.DevilSentryService::class.java).apply {
                action = com.example.nordassistant.security.DevilSentryService.ACTION_START_SENTRY
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "🛡️ Sentry Mode ACTIVATED: Hands-free background listening online")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DevilSentryService", e)
        }
    }

    fun revokeSentryMode(context: Context) {
        isSentryModeActive = false
        try {
            val intent = android.content.Intent(context, com.example.nordassistant.security.DevilSentryService::class.java).apply {
                action = com.example.nordassistant.security.DevilSentryService.ACTION_STOP_SENTRY
            }
            context.stopService(intent)
            Log.i(TAG, "🛑 Sentry Mode REVOKED: Audio sensors powered down to battery-saver mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop DevilSentryService", e)
        }
    }

    /**
     * Checks if input revokes all protocols and returns to Boss Mode.
     */
    fun isBossModeRevocation(input: String): Boolean {
        val lower = input.lowercase().trim()
        return lower.contains(Regex("(?i)\\b(boss\\s*mode|devil\\s*mode|switch\\s+to\\s+devil|switch\\s+to\\s+boss|revoke(\\s*all|\\s*protocols?)?|back\\s*to\\s*boss\\s*mode|normal\\s*mode|reset\\s*protocols?|cancel\\s*protocols?)\\b"))
    }
}
