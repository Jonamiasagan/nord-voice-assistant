package com.example.nordassistant.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BossSecurityManager handles Voice Biometric State & Guest Access Control for DEVIL.
 *
 * Rules:
 * 1. Default State: The Boss is the sole authorized user.
 * 2. If a stranger speaks without prior Boss introduction, DEVIL denies access or ignores them.
 * 3. Introduction Protocol: When the Boss says "DEVIL, meet [Name]" or introduces someone,
 *    a temporary Guest Session is opened for that person.
 * 4. Lockdown: The Boss can terminate guest sessions immediately with "DEVIL, lock down".
 */
class BossSecurityManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var activeGuest: String? = null
        private set

    var guestAccessExpiryMs: Long = 0L
        private set

    var isStrictBossMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_STRICT_MODE, value).apply()

    var isBossEnrolled: Boolean
        get() = prefs.getBoolean(KEY_ENROLLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENROLLED, value).apply()

    fun isGuestSessionActive(): Boolean {
        val guest = activeGuest ?: return false
        val active = System.currentTimeMillis() < guestAccessExpiryMs
        if (!active) {
            activeGuest = null
        }
        return active
    }

    /**
     * Authorizes a guest introduced by the Boss.
     */
    fun introduceGuest(guestName: String, durationMinutes: Int = 15): String {
        activeGuest = guestName.trim()
        guestAccessExpiryMs = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        return "Guest session activated for $activeGuest for $durationMinutes minutes."
    }

    /**
     * Immediately revokes any active guest session and locks down to Boss-only mode.
     */
    fun revokeGuest(): String {
        val prevGuest = activeGuest
        activeGuest = null
        guestAccessExpiryMs = 0L
        return if (prevGuest != null) {
            "Lockdown engaged. Guest privileges for $prevGuest have been revoked. Boss-only mode is active."
        } else {
            "Lockdown engaged. Boss-only mode is active."
        }
    }

    fun getSecurityStatus(): String {
        return when {
            isGuestSessionActive() -> "GUEST SESSION ACTIVE: ${activeGuest?.uppercase()}"
            isBossEnrolled -> "BOSS SECURED // DEVIL ONLINE"
            else -> "BOSS MODE ACTIVE // DEVIL ONLINE"
        }
    }

    /**
     * Cosine similarity between two acoustic embedding vectors.
     */
    fun computeCosineSimilarity(vectorA: FloatArray, vectorB: FloatArray): Float {
        if (vectorA.size != vectorB.size || vectorA.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in vectorA.indices) {
            dot += vectorA[i] * vectorB[i]
            normA += vectorA[i] * vectorA[i]
            normB += vectorB[i] * vectorB[i]
        }
        val denom = (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())).toFloat()
        return if (denom > 0f) dot / denom else 0f
    }

    fun saveBossVoiceprint(embedding: FloatArray) {
        val byteBuffer = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach { byteBuffer.putFloat(it) }
        val base64 = Base64.encodeToString(byteBuffer.array(), Base64.DEFAULT)
        prefs.edit().putString(KEY_VOICEPRINT, base64).putBoolean(KEY_ENROLLED, true).apply()
    }

    fun loadBossVoiceprint(): FloatArray? {
        val base64 = prefs.getString(KEY_VOICEPRINT, null) ?: return null
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = byteBuffer.float
        }
        return floats
    }

    companion object {
        private const val PREF_NAME = "devil_boss_security"
        private const val KEY_ENROLLED = "boss_enrolled"
        private const val KEY_VOICEPRINT = "boss_voiceprint"
        private const val KEY_STRICT_MODE = "strict_boss_mode"

        @Volatile
        private var instance: BossSecurityManager? = null

        fun getInstance(context: Context): BossSecurityManager {
            return instance ?: synchronized(this) {
                instance ?: BossSecurityManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
