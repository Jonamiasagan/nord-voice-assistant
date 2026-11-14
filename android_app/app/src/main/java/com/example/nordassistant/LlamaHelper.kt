package com.example.nordassistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import org.codeshipping.llamakotlin.LlamaModel
import com.example.nordassistant.data.BossMemoryVault
import com.example.nordassistant.protocol.DevilProtocol
import com.example.nordassistant.protocol.DevilProtocolManager
import java.io.File

/**
 * Manages the on-device LLM using llama-kotlin-android.
 *
 * The model GGUF file must be pushed to the device beforehand:
 *   adb push model.gguf /data/local/tmp/model.gguf
 *
 * On first launch the app copies it from /data/local/tmp/ to app-private
 * storage.  After that the device works fully offline — no internet,
 * no API keys, no backend needed.
 */
object LlamaHelper {

    private const val TAG = "LlamaHelper"
    private const val MODEL_FILENAME = "model.gguf"
    // ADB-pushable location (writable without root)
    private const val ADB_MODEL_PATH = "/data/local/tmp/model.gguf"

    private var model: LlamaModel? = null
    private var memoryVault: BossMemoryVault? = null
    var modelReady = false
        private set

    val isReady: Boolean
        get() = modelReady && model != null

    /**
     * Loads the model.  Tries the following locations in order:
     *  1. App-internal storage  (already copied in a previous run)
     *  2. /data/local/tmp/      (pushed via ADB)
     *  3. APK assets folder     (bundled in the APK — only for tiny models)
     */
    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        memoryVault = BossMemoryVault.getInstance(context)
        if (modelReady) return@withContext

        try {
            val internalModel = File(context.filesDir, MODEL_FILENAME)

            // ── 1.  Already in internal storage? ────────────────────────
            if (internalModel.exists() && internalModel.length() > 0) {
                Log.i(TAG, "Model found in internal storage")
            }
            // ── 2.  Pushed via ADB to /data/local/tmp? ─────────────────
            else {
                val adbFile = File(ADB_MODEL_PATH)
                if (adbFile.exists() && adbFile.length() > 0) {
                    Log.i(TAG, "Copying model from ADB path …")
                    adbFile.inputStream().use { input ->
                        internalModel.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                        }
                    }
                    Log.i(TAG, "Copy complete (${internalModel.length() / 1_000_000} MB)")
                }
                // ── 3.  Bundled in assets? ─────────────────────────────
                else {
                    try {
                        context.assets.open(MODEL_FILENAME).use { input ->
                            internalModel.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                            }
                        }
                        Log.i(TAG, "Copied model from assets (${internalModel.length() / 1_000_000} MB)")
                    } catch (e: Exception) {
                        Log.e(TAG, "No model found anywhere!  " +
                                "Push it with:  adb push model.gguf /data/local/tmp/model.gguf", e)
                        return@withContext
                    }
                }
            }

            // Load via llama-kotlin-android: 1024 context, 512 batch (fits 110 token prompt perfectly), 4 big cores, maxTokens=20
            model = LlamaModel.load(internalModel.absolutePath) {
                contextSize = 1024
                batchSize = 512
                threads = 4 // 4 big performance cores
                threadsBatch = 4
                temperature = 0.1f // Low temperature for deterministic fast tool selection
                topP = 0.85f
                maxTokens = 20 // 20 tokens max ensures generation is done in < 2 seconds
                stopSequences = listOf("<|im_end|>", "<|endoftext|>")
            }
            modelReady = true
            Log.i(TAG, "Model loaded successfully: contextSize=1024, batchSize=512, maxTokens=20 ✓")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
        }
    }

    data class DialogueTurn(val role: String, val content: String)

    private val conversationHistory = mutableListOf<DialogueTurn>()
    private val recentJokes = mutableListOf<String>()
    private var lastInteractionTime = 0L
    private const val MAX_HISTORY_TURNS = 2 // Keep to last 2 turns (1 back-and-forth) so prompt stays < 200 tokens
    private const val SESSION_TIMEOUT_MS = 600_000L // 10 minutes session timeout

    private val generateMutex = Mutex()

    fun clearHistory() {
        synchronized(conversationHistory) {
            conversationHistory.clear()
            recentJokes.clear()
            Log.i(TAG, "Conversation history and jokes cleared")
        }
    }

    fun recordJoke(joke: String) {
        val trimmed = joke.trim()
        if (trimmed.isEmpty()) return
        synchronized(recentJokes) {
            if (recentJokes.lastOrNull() != trimmed) {
                recentJokes.add(trimmed)
            }
            while (recentJokes.size > 3) {
                recentJokes.removeAt(0)
            }
        }
    }

    fun getRecentJokes(): List<String> {
        synchronized(recentJokes) {
            return recentJokes.toList()
        }
    }

    fun getLastJokeTold(): String? {
        synchronized(recentJokes) {
            return recentJokes.lastOrNull()
        }
    }

    fun hasRecentJokes(): Boolean {
        synchronized(recentJokes) {
            return recentJokes.isNotEmpty()
        }
    }

    /**
     * Explicitly commits a dialogue turn into the ongoing conversation history.
     * Useful for recording canned jokes, calendar replies, and fast-paths so LLM remembers them.
     */
    fun recordTurn(userQuery: String, assistantReply: String) {
        val q = userQuery.trim()
        val a = assistantReply.trim()
        if (q.isEmpty() || a.isEmpty() || a.contains("<tool_call>") || a.contains("capability", ignoreCase = true)) return
        memoryVault?.recordJournalTurn(q, a)
        synchronized(conversationHistory) {
            lastInteractionTime = System.currentTimeMillis()
            conversationHistory.add(DialogueTurn("user", q))
            conversationHistory.add(DialogueTurn("assistant", a))
            while (conversationHistory.size > MAX_HISTORY_TURNS) {
                conversationHistory.removeAt(0)
            }
            Log.i(TAG, "Turn recorded: Q='$q' -> A='$a' (Total turns: ${conversationHistory.size})")
        }
    }

    /**
     * Constructs ultra-compact ChatML prompt for sub-2-second CPU inference on OnePlus.
     */
    fun buildChatPrompt(query: String): String {
        val promptBuilder = StringBuilder()
        promptBuilder.append("<|im_start|>system\n")
        promptBuilder.append("You are DEVIL, the loyal AI companion for your Boss, Bhaman, on OnePlus. You have direct control over OnePlus hardware.\n")
        promptBuilder.append("For phone actions or status, reply ONLY with the exact tool tag:\n")
        promptBuilder.append("- Battery / power / dying / juice / charge: TOOL_BATTERY\n")
        promptBuilder.append("- Flashlight / torch on / dark / can't see: TOOL_FLASHLIGHT_ON\n")
        promptBuilder.append("- Flashlight off / torch off: TOOL_FLASHLIGHT_OFF\n")
        promptBuilder.append("- Wi-Fi / internet / connection: TOOL_WIFI\n")
        promptBuilder.append("- Volume up / louder: TOOL_VOLUME_UP\n")
        promptBuilder.append("- Volume down / quieter / mute: TOOL_VOLUME_DOWN\n")
        promptBuilder.append("For casual chat, answer Boss Bhaman warmly in 1 short spoken sentence. Boss's name is Bhaman.\n")
        promptBuilder.append("<|im_end|>\n")

        // Include at most 2 most recent turns
        synchronized(conversationHistory) {
            val turnsToInclude = conversationHistory.takeLast(2)
            for (turn in turnsToInclude) {
                if (!turn.content.startsWith("TOOL_") && !turn.content.contains("<tool_call>") && !turn.content.contains("capability", ignoreCase = true)) {
                    promptBuilder.append("<|im_start|>${turn.role}\n${turn.content.take(80)}<|im_end|>\n")
                }
            }
        }

        promptBuilder.append("<|im_start|>user\n${query.trim()}<|im_end|>\n<|im_start|>assistant\n")
        return promptBuilder.toString()
    }

    /**
     * Generates an intelligent, context-aware answer using Qwen2.5 ChatML format
     * with multi-turn conversation history.
     */
    suspend fun generateAnswer(query: String): String = withContext(Dispatchers.IO) {
        if (!modelReady || model == null) {
            return@withContext "I'm right here with you, Boss! What shall we talk about?"
        }

        val now = System.currentTimeMillis()
        if (now - lastInteractionTime > SESSION_TIMEOUT_MS) {
            clearHistory()
        }
        lastInteractionTime = now

        generateMutex.withLock {
            try {
                val startTime = System.currentTimeMillis()
                Log.i(TAG, "Incoming query: '$query'")
                val chatPrompt = buildChatPrompt(query)
                Log.d(TAG, "Chat prompt sent to model:\n$chatPrompt")
                val raw = model!!.generate(chatPrompt) ?: ""
                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "⚡ Model generation completed in ${duration}ms! Raw len=${raw.length}, Raw: '$raw'")

                // 1. Direct tool trigger check from model output
                val trimmedRaw = raw.trim()
                if (trimmedRaw.contains("TOOL_BATTERY") ||
                    trimmedRaw.contains("TOOL_FLASHLIGHT_ON") ||
                    trimmedRaw.contains("TOOL_FLASHLIGHT_OFF") ||
                    trimmedRaw.contains("TOOL_WIFI") ||
                    trimmedRaw.contains("TOOL_VOLUME_UP") ||
                    trimmedRaw.contains("TOOL_VOLUME_DOWN") ||
                    trimmedRaw.contains("<tool_call>")) {
                    return@withContext trimmedRaw
                }

                val cleaned = raw
                    .replace(Regex("<\\|.*?\\|>"), "")
                    .replace(Regex("[#*`_]"), "")
                    .trim()

                // Filter out any non-standard or foreign characters
                val englishOnly = cleaned.filter { it.code in 32..126 || it == '\n' || it == '’' || it == '‘' || it == '“' || it == '”' }
                    .trim()

                // Keep to maximum 2 concise sentences
                val sentences = englishOnly.split(Regex("(?<=[.!?])\\s+"))
                val truncated = if (sentences.size > 2) sentences.take(2).joinToString(" ") else englishOnly
                val candidateAnswer = truncated.ifEmpty { "I'm right here with you, Boss!" }

                val isRefusal = candidateAnswer.contains("capability", ignoreCase = true) ||
                                candidateAnswer.contains("can't assist", ignoreCase = true) ||
                                candidateAnswer.contains("cannot assist", ignoreCase = true) ||
                                candidateAnswer.contains("unable to assist", ignoreCase = true) ||
                                candidateAnswer.contains("sorry, but I can't", ignoreCase = true) ||
                                candidateAnswer.contains("don't have the", ignoreCase = true)

                // Fail-safe tool conversion if model refused on hardware query
                val finalAnswer = if (isRefusal) {
                    val qLower = query.lowercase()
                    when {
                        qLower.contains(Regex("\\b(?:battery|power|die|dying|charge|juice)\\b")) -> "TOOL_BATTERY"
                        qLower.contains(Regex("\\b(?:torch|flashlight|light|dark)\\b")) -> "TOOL_FLASHLIGHT_ON"
                        qLower.contains(Regex("\\b(?:wi-?fi|internet|data|network|connection)\\b")) -> "TOOL_WIFI"
                        qLower.contains(Regex("\\b(?:volume|louder|sound|quiet|mute)\\b")) -> "TOOL_VOLUME_UP"
                        else -> "I'm right here with you, Boss! Tell me more."
                    }
                } else {
                    candidateAnswer
                }

                if (!finalAnswer.startsWith("TOOL_") && !finalAnswer.contains("<tool_call>")) {
                    synchronized(conversationHistory) {
                        conversationHistory.add(DialogueTurn("user", query.trim()))
                        conversationHistory.add(DialogueTurn("assistant", finalAnswer))
                        while (conversationHistory.size > MAX_HISTORY_TURNS) {
                            conversationHistory.removeAt(0)
                        }
                    }
                    memoryVault?.recordJournalTurn(query.trim(), finalAnswer)
                }

                Log.i(TAG, "Final Answer: $finalAnswer (History turns: ${conversationHistory.size})")
                finalAnswer
            } catch (e: Exception) {
                Log.w(TAG, "Generation error, attempting minimalist recovery", e)
                try {
                    clearHistory()
                    val fallbackPrompt = "<|im_start|>system\nYou are DEVIL, companion for Boss. Respond in 1 short spoken sentence.<|im_end|>\n<|im_start|>user\n${query.trim()}<|im_end|>\n<|im_start|>assistant\n"
                    val rawFallback = model?.generate(fallbackPrompt) ?: ""
                    val cleaned = rawFallback.replace(Regex("<\\|.*?\\|>"), "").replace(Regex("[#*`_]"), "").trim()
                    if (cleaned.isNotBlank()) {
                        return@withContext cleaned
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Minimalist recovery also failed", e2)
                }
                "I'm right here with you, Boss! What's on your mind?"
            }
        }
    }

    /**
     * Safe response generation avoiding native SIGSEGV in llama-kotlin-android nativeGenerateStream.
     * Generates via rock-solid nativeGenerate and emits chunks cleanly for TTS.
     */
    suspend fun generateAnswerStream(
        query: String,
        onSentenceChunk: suspend (chunk: String, isFirst: Boolean, isFinal: Boolean) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val answer = generateAnswer(query)
        val sentences = answer.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (sentences.isEmpty()) {
            onSentenceChunk(answer, true, true)
        } else {
            sentences.forEachIndexed { index, sentence ->
                val isFirst = index == 0
                val isFinal = index == sentences.size - 1
                onSentenceChunk(sentence, isFirst, isFinal)
            }
        }
        answer
    }

    /** Clean up native resources.  Call from onDestroy. */
    fun release() {
        try {
            model?.close()
            model = null
            modelReady = false
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing model", e)
        }
    }
}
