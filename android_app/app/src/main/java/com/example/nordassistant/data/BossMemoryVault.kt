package com.example.nordassistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * On-Device Persistent Semantic Memory Vault for DEVIL.
 *
 * Stores user preferences, facts, routines, and personal credentials locally
 * in a private SQLite database with zero cloud reliance.
 */
class BossMemoryVault private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "BossMemoryVault"
        private const val DATABASE_NAME = "devil_boss_memory.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_MEMORIES = "memories"
        private const val COL_ID = "id"
        private const val COL_KEY = "key_phrase"
        private const val COL_FACT = "fact_content"
        private const val COL_CATEGORY = "category"
        private const val COL_UPDATED_AT = "updated_at"
        private const val COL_ACCESS_COUNT = "access_count"

        @Volatile
        private var instance: BossMemoryVault? = null

        fun getInstance(context: Context): BossMemoryVault {
            return instance ?: synchronized(this) {
                instance ?: BossMemoryVault(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createQuery = """
            CREATE TABLE $TABLE_MEMORIES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_KEY TEXT UNIQUE NOT NULL,
                $COL_FACT TEXT NOT NULL,
                $COL_CATEGORY TEXT NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL,
                $COL_ACCESS_COUNT INTEGER DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createQuery)
        Log.i(TAG, "BossMemoryVault database initialized.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MEMORIES")
        onCreate(db)
    }

    data class MemoryItem(
        val key: String,
        val fact: String,
        val category: String,
        val updatedAt: Long
    )

    /**
     * Stores or updates a personal fact in memory.
     */
    fun remember(keyPhrase: String, fact: String, category: String = "general"): Boolean {
        val cleanKey = keyPhrase.trim().lowercase()
        val cleanFact = fact.trim()
        if (cleanKey.isEmpty() || cleanFact.isEmpty()) return false

        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_KEY, cleanKey)
                put(COL_FACT, cleanFact)
                put(COL_CATEGORY, category)
                put(COL_UPDATED_AT, System.currentTimeMillis())
            }

            val rows = db.insertWithOnConflict(
                TABLE_MEMORIES,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            Log.i(TAG, "🧠 Remembered: '$cleanKey' -> '$cleanFact'")
            rows != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error saving memory", e)
            false
        }
    }

    /**
     * Recalls a specific memory by key or keyword match.
     */
    fun recall(query: String): MemoryItem? {
        val clean = query.trim().lowercase()
        if (clean.isEmpty()) return null

        try {
            val db = readableDatabase
            // 1. Direct exact or substring key match
            val cursor = db.query(
                TABLE_MEMORIES,
                arrayOf(COL_KEY, COL_FACT, COL_CATEGORY, COL_UPDATED_AT),
                "$COL_KEY LIKE ? OR ? LIKE '%' || $COL_KEY || '%'",
                arrayOf("%$clean%", clean),
                null, null, "$COL_ACCESS_COUNT DESC, $COL_UPDATED_AT DESC", "1"
            )

            cursor.use {
                if (it.moveToFirst()) {
                    val key = it.getString(0)
                    val fact = it.getString(1)
                    val category = it.getString(2)
                    val updated = it.getLong(3)
                    incrementAccess(key)
                    return MemoryItem(key, fact, category, updated)
                }
            }

            // 2. Multi-token fallback search
            val tokens = clean.split(" ").filter { it.length > 2 && it !in listOf("the", "my", "what", "is", "about", "did", "you", "remember") }
            for (token in tokens) {
                val tokenCursor = db.query(
                    TABLE_MEMORIES,
                    arrayOf(COL_KEY, COL_FACT, COL_CATEGORY, COL_UPDATED_AT),
                    "$COL_KEY LIKE ? OR $COL_FACT LIKE ?",
                    arrayOf("%$token%", "%$token%"),
                    null, null, "$COL_ACCESS_COUNT DESC", "1"
                )
                tokenCursor.use {
                    if (it.moveToFirst()) {
                        val key = it.getString(0)
                        val fact = it.getString(1)
                        val category = it.getString(2)
                        val updated = it.getLong(3)
                        incrementAccess(key)
                        return MemoryItem(key, fact, category, updated)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recalling memory: $query", e)
        }
        return null
    }

    private fun incrementAccess(key: String) {
        try {
            writableDatabase.execSQL(
                "UPDATE $TABLE_MEMORIES SET $COL_ACCESS_COUNT = $COL_ACCESS_COUNT + 1 WHERE $COL_KEY = ?",
                arrayOf(key)
            )
        } catch (_: Exception) {}
    }

    /**
     * Deletes a specific fact from memory.
     */
    fun forget(keyPhrase: String): Boolean {
        val clean = keyPhrase.trim().lowercase()
        return try {
            val db = writableDatabase
            val count = db.delete(
                TABLE_MEMORIES,
                "$COL_KEY LIKE ? OR ? LIKE '%' || $COL_KEY || '%'",
                arrayOf("%$clean%", clean)
            )
            Log.i(TAG, "Purged memory for: '$clean' (deleted $count rows)")
            count > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error forgetting memory", e)
            false
        }
    }

    /**
     * Clears all long-term memories.
     */
    fun clearAll(): Boolean {
        return try {
            writableDatabase.delete(TABLE_MEMORIES, null, null)
            Log.i(TAG, "All memories wiped from BossMemoryVault.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing memories", e)
            false
        }
    }

    /**
     * Returns a formatted list of all saved memories.
     */
    fun listMemoriesSummary(limit: Int = 10): String {
        val items = mutableListOf<String>()
        try {
            val cursor = readableDatabase.query(
                TABLE_MEMORIES,
                arrayOf(COL_KEY, COL_FACT),
                null, null, null, null,
                "$COL_UPDATED_AT DESC",
                limit.toString()
            )
            cursor.use {
                while (it.moveToNext()) {
                    val k = it.getString(0).replaceFirstChar { char -> char.uppercase() }
                    val v = it.getString(1)
                    items.add("$k: $v")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing memories", e)
        }
        return if (items.isEmpty()) {
            "I don't have any saved memories yet, Boss. You can tell me 'DEVIL, remember that...' anytime."
        } else {
            "Here is what I have saved in your personal memory vault, Boss:\n" +
                    items.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        }
    }

    /**
     * Compact string representation of memories for injection into the Qwen LLM prompt.
     */
    fun getMemoryContextForPrompt(limit: Int = 8): String {
        val sb = StringBuilder()
        try {
            val cursor = readableDatabase.query(
                TABLE_MEMORIES,
                arrayOf(COL_KEY, COL_FACT),
                null, null, null, null,
                "$COL_ACCESS_COUNT DESC, $COL_UPDATED_AT DESC",
                limit.toString()
            )
            cursor.use {
                while (it.moveToNext()) {
                    val k = it.getString(0)
                    val v = it.getString(1)
                    sb.append("- $k: $v\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting prompt memory context", e)
        }
        return sb.toString().trim()
    }
}
