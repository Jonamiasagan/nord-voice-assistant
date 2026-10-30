package com.example.nordassistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        private const val DATABASE_VERSION = 2

        private const val TABLE_MEMORIES = "memories"
        private const val COL_ID = "id"
        private const val COL_KEY = "key_phrase"
        private const val COL_FACT = "fact_content"
        private const val COL_CATEGORY = "category"
        private const val COL_UPDATED_AT = "updated_at"
        private const val COL_ACCESS_COUNT = "access_count"

        private const val TABLE_JOURNAL = "conversation_journal"
        private const val COL_J_ID = "id"
        private const val COL_J_TIMESTAMP = "timestamp"
        private const val COL_J_DATE = "date_key"
        private const val COL_J_TIME = "time_str"
        private const val COL_J_USER = "user_query"
        private const val COL_J_DEVIL = "assistant_reply"

        private const val CREATE_JOURNAL_TABLE = """
            CREATE TABLE IF NOT EXISTS conversation_journal (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                date_key TEXT NOT NULL,
                time_str TEXT NOT NULL,
                user_query TEXT NOT NULL,
                assistant_reply TEXT NOT NULL
            )
        """

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
            CREATE TABLE IF NOT EXISTS $TABLE_MEMORIES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_KEY TEXT UNIQUE NOT NULL,
                $COL_FACT TEXT NOT NULL,
                $COL_CATEGORY TEXT NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL,
                $COL_ACCESS_COUNT INTEGER DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createQuery)
        db.execSQL(CREATE_JOURNAL_TABLE)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_journal_date ON $TABLE_JOURNAL($COL_J_DATE)")
        Log.i(TAG, "BossMemoryVault database initialized with journal table.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(CREATE_JOURNAL_TABLE)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_journal_date ON $TABLE_JOURNAL($COL_J_DATE)")
            Log.i(TAG, "BossMemoryVault upgraded to v2 with journal table.")
        }
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

            // 3. Pure-Kotlin Semantic & Fuzzy N-Gram Matcher
            val allMemories = getAllStoredMemories()
            if (allMemories.isNotEmpty()) {
                val stopWords = setOf("the", "my", "what", "is", "about", "did", "you", "remember", "where", "tell", "me", "know", "does")
                val queryTokens = clean.split(" ", "_", "-").map { it.trim() }.filter { it.length > 1 && it !in stopWords }.toSet()
                val expandedTokens = expandWithSynonyms(queryTokens)

                var bestMatch: MemoryItem? = null
                var bestScore = 0.0

                for (item in allMemories) {
                    val keyTokens = item.key.lowercase().split(" ", "_", "-").map { it.trim() }.toSet()
                    val factTokens = item.fact.lowercase().split(" ", "_", "-").map { it.trim() }.toSet()
                    val allItemTokens = keyTokens + factTokens

                    // A: Token & Synonym Overlap
                    val overlap = expandedTokens.intersect(allItemTokens).size
                    val tokenScore = if (expandedTokens.isNotEmpty()) overlap.toDouble() / expandedTokens.size else 0.0

                    // B: Key N-Gram Similarity
                    val ngramScore = computeNGramSimilarity(clean, item.key.lowercase())

                    // Combined Weighted Score
                    val combinedScore = (tokenScore * 0.65) + (ngramScore * 0.35)

                    if (combinedScore > bestScore && combinedScore >= 0.35) {
                        bestScore = combinedScore
                        bestMatch = item
                    }
                }

                if (bestMatch != null) {
                    Log.i(TAG, "🧠 Semantic memory hit: '$clean' -> '${bestMatch.key}' (score: $bestScore)")
                    incrementAccess(bestMatch.key)
                    return bestMatch
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recalling memory: $query", e)
        }
        return null
    }

    private val synonymGroups = listOf(
        setOf("car", "vehicle", "automobile", "bike", "scooter", "motorcycle", "ride"),
        setOf("park", "parking", "parked", "slot", "spot", "garage", "basement"),
        setOf("password", "pass", "pin", "credential", "code", "key", "secret", "login"),
        setOf("phone", "mobile", "number", "cell", "contact", "sim"),
        setOf("home", "house", "address", "flat", "apartment", "residence"),
        setOf("office", "work", "desk", "cabin", "building", "floor"),
        setOf("laptop", "computer", "pc", "mac", "macbook", "machine"),
        setOf("wifi", "network", "internet", "hotspot", "connection"),
        setOf("wife", "spouse", "partner"),
        setOf("mother", "mom", "amma"),
        setOf("father", "dad", "appa"),
        setOf("brother", "bro"),
        setOf("friend", "colleague", "buddy")
    )

    private fun expandWithSynonyms(tokens: Set<String>): Set<String> {
        val expanded = tokens.toMutableSet()
        for (token in tokens) {
            for (group in synonymGroups) {
                if (group.contains(token)) {
                    expanded.addAll(group)
                }
            }
        }
        return expanded
    }

    private fun computeNGramSimilarity(s1: String, s2: String, n: Int = 3): Double {
        if (s1.length < n || s2.length < n) {
            return if (s1.contains(s2) || s2.contains(s1)) 0.7 else 0.0
        }
        val ngrams1 = (0..s1.length - n).map { s1.substring(it, it + n) }.toSet()
        val ngrams2 = (0..s2.length - n).map { s2.substring(it, it + n) }.toSet()
        val intersection = ngrams1.intersect(ngrams2).size
        val union = ngrams1.union(ngrams2).size
        return if (union > 0) intersection.toDouble() / union.toDouble() else 0.0
    }

    fun getAllStoredMemories(): List<MemoryItem> {
        val list = mutableListOf<MemoryItem>()
        try {
            val cursor = readableDatabase.query(
                TABLE_MEMORIES,
                arrayOf(COL_KEY, COL_FACT, COL_CATEGORY, COL_UPDATED_AT),
                null, null, null, null, "$COL_ACCESS_COUNT DESC", "100"
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(MemoryItem(it.getString(0), it.getString(1), it.getString(2), it.getLong(3)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching stored memories", e)
        }
        return list
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

    // ── Daily Conversation Journal & RAM Cache ──

    data class JournalEntry(
        val id: Long,
        val timestamp: Long,
        val dateKey: String,
        val timeStr: String,
        val userQuery: String,
        val assistantReply: String
    )

    private val ramJournalCache = mutableListOf<JournalEntry>()
    private var isCacheLoaded = false

    private fun loadRamCacheIfNeeded() {
        if (isCacheLoaded) return
        synchronized(ramJournalCache) {
            if (isCacheLoaded) return
            try {
                val db = readableDatabase
                val cursor = db.query(
                    TABLE_JOURNAL,
                    arrayOf(COL_J_ID, COL_J_TIMESTAMP, COL_J_DATE, COL_J_TIME, COL_J_USER, COL_J_DEVIL),
                    null, null, null, null,
                    "$COL_J_TIMESTAMP DESC",
                    "200"
                )
                cursor.use {
                    while (it.moveToNext()) {
                        ramJournalCache.add(
                            0, // Keep in chronological order
                            JournalEntry(
                                id = it.getLong(0),
                                timestamp = it.getLong(1),
                                dateKey = it.getString(2),
                                timeStr = it.getString(3),
                                userQuery = it.getString(4),
                                assistantReply = it.getString(5)
                            )
                        )
                    }
                }
                isCacheLoaded = true
                Log.i(TAG, "RAM Journal cache loaded: ${ramJournalCache.size} entries")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading RAM journal cache", e)
            }
        }
    }

    /**
     * Persistently logs a dialogue turn into SQLite and keeps it indexed in RAM.
     */
    fun recordJournalTurn(userQuery: String, assistantReply: String): Boolean {
        val q = userQuery.trim()
        val a = assistantReply.trim()
        if (q.isEmpty() || a.isEmpty()) return false

        loadRamCacheIfNeeded()
        val now = System.currentTimeMillis()
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val timeStr = SimpleDateFormat("hh:mm a", Locale.US).format(Date(now))

        val entry = JournalEntry(0, now, dateKey, timeStr, q, a)
        synchronized(ramJournalCache) {
            ramJournalCache.add(entry)
            while (ramJournalCache.size > 200) {
                ramJournalCache.removeAt(0)
            }
        }

        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_J_TIMESTAMP, now)
                put(COL_J_DATE, dateKey)
                put(COL_J_TIME, timeStr)
                put(COL_J_USER, q)
                put(COL_J_DEVIL, a)
            }
            db.insert(TABLE_JOURNAL, null, values) != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error saving journal turn", e)
            false
        }
    }

    /**
     * Returns the very last conversation turn from today or earlier.
     */
    fun getLastConversationTurn(): JournalEntry? {
        loadRamCacheIfNeeded()
        synchronized(ramJournalCache) {
            return ramJournalCache.lastOrNull()
        }
    }

    /**
     * Returns all entries for a specific calendar date (yyyy-MM-dd).
     */
    fun getJournalEntriesForDate(dateKey: String): List<JournalEntry> {
        loadRamCacheIfNeeded()
        synchronized(ramJournalCache) {
            val fromRam = ramJournalCache.filter { it.dateKey == dateKey }
            if (fromRam.isNotEmpty()) return fromRam
        }
        val list = mutableListOf<JournalEntry>()
        try {
            val cursor = readableDatabase.query(
                TABLE_JOURNAL,
                arrayOf(COL_J_ID, COL_J_TIMESTAMP, COL_J_DATE, COL_J_TIME, COL_J_USER, COL_J_DEVIL),
                "$COL_J_DATE = ?",
                arrayOf(dateKey),
                null, null, "$COL_J_TIMESTAMP ASC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        JournalEntry(
                            id = it.getLong(0),
                            timestamp = it.getLong(1),
                            dateKey = it.getString(2),
                            timeStr = it.getString(3),
                            userQuery = it.getString(4),
                            assistantReply = it.getString(5)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching journal for date: $dateKey", e)
        }
        return list
    }

    /**
     * Returns an eloquent spoken summary of conversation turns for a given date.
     */
    fun getFormattedDaySummary(dateKey: String, dayLabel: String): String {
        val entries = getJournalEntriesForDate(dateKey)
        if (entries.isEmpty()) {
            return "I don't have any recorded conversations for $dayLabel, Boss."
        }
        val sb = StringBuilder()
        sb.append("Here is what we talked about $dayLabel, Boss: ")
        val distinctEntries = entries.distinctBy { it.userQuery.lowercase() }.takeLast(4)
        val highlights = distinctEntries.map { entry ->
            val cleanReply = entry.assistantReply.split(Regex("(?<=[.!?])\\s+")).firstOrNull() ?: entry.assistantReply.take(70)
            "at ${entry.timeStr}, you asked '${entry.userQuery}', and I said '$cleanReply'"
        }
        sb.append(highlights.joinToString(". "))
        return sb.toString()
    }

    fun getTodaySummary(): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return getFormattedDaySummary(today, "today")
    }

    fun getYesterdaySummary(): String {
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 86_400_000L))
        return getFormattedDaySummary(yesterday, "yesterday")
    }
}
