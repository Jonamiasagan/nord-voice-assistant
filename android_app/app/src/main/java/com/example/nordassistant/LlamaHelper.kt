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

            // Load via llama-kotlin-android with 2048 context and optimized parameters
            model = LlamaModel.load(internalModel.absolutePath) {
                contextSize = 2048
                threads = 4 // Optimal for Snapdragon 7 Gen 3: 4 performance cores
                temperature = 0.25f
                topP = 0.85f
                maxTokens = 90
                stopSequences = listOf("<|im_end|>", "<|endoftext|>", "<|im_start|>", "\nUser:", "\nuser:", "\nDEVIL:", "\nBoss:")
            }
            modelReady = true
            Log.i(TAG, "Model loaded successfully with contextSize=2048, maxTokens=90 ✓")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
        }
    }

    data class DialogueTurn(val role: String, val content: String)

    private val conversationHistory = mutableListOf<DialogueTurn>()
    private val recentJokes = mutableListOf<String>()
    private var lastInteractionTime = 0L
    private const val MAX_HISTORY_TURNS = 12 // Up to 12 turns (6 full back-and-forth conversational turns)
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
            while (recentJokes.size > 5) {
                recentJokes.removeAt(0)
            }
            Log.i(TAG, "Joke recorded in memory: '$trimmed'. Total recent jokes: ${recentJokes.size}")
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
        if (q.isEmpty() || a.isEmpty()) return
        memoryVault?.recordJournalTurn(q, a)
        synchronized(conversationHistory) {
            lastInteractionTime = System.currentTimeMillis()
            conversationHistory.add(DialogueTurn("user", q))
            conversationHistory.add(DialogueTurn("assistant", a))
            while (conversationHistory.size > MAX_HISTORY_TURNS) {
                conversationHistory.removeAt(0)
            }
            if (q.contains("joke", ignoreCase = true) || a.contains("Because ", ignoreCase = true)) {
                recordJoke(a)
            }
            Log.i(TAG, "Turn recorded: Q='$q' -> A='$a' (Total turns: ${conversationHistory.size})")
        }
    }

    /**
     * Constructs the ChatML prompt with persona, memory, and multi-turn context.
     */
    fun buildChatPrompt(query: String): String {
        val promptBuilder = StringBuilder()
        promptBuilder.append("<|im_start|>system\n")
        val personaPrompt = when (DevilProtocolManager.currentProtocol) {
            DevilProtocol.JARVIS ->
                "You are J.A.R.V.I.S., the formal British AI assistant for Boss. Be eloquent, loyal, and witty in 1-2 spoken sentences. Accurately recall and quote prior conversation context and jokes when Boss asks."
            DevilProtocol.FRIDAY ->
                "You are F.R.I.D.A.Y., the energetic and warm tactical companion AI for Boss. Be lively, helpful, and concise in 1-2 spoken sentences. Accurately recall prior conversation context and jokes when Boss asks."
            DevilProtocol.EDITH ->
                "You are E.D.I.T.H., tactical defense AI for Boss. Be analytical, precise, and concise in 1-2 spoken sentences. Accurately recall prior conversation context when queried."
            DevilProtocol.BARN_DOOR ->
                "You are DEVIL in silent meeting mode. Answer Boss in one short, quiet spoken sentence."
            DevilProtocol.HOUSE_PARTY ->
                "You are DEVIL in developer mode. Answer Boss directly and technically in 1 to 2 sentences."
            else ->
                "You are DEVIL, an intelligent, loyal, witty, and fun AI companion for your Boss. Chat naturally, warmly, and helpfully in 1 to 2 concise spoken sentences without markdown or bullet points. You already know your Boss well; never introduce yourself during casual chat.\n" +
                "CONVERSATION MEMORY RULES:\n" +
                "1. You have a sharp, active memory of earlier jokes, remarks, and topics discussed in the conversation turns above.\n" +
                "2. When Boss asks if you remember prior jokes, questions, or statements (e.g. 'do you remember', 'what did you say', 'you said some jokes earlier', 'where we ended'), ALWAYS directly cite or quote what was said earlier (e.g. 'Yes Boss, I told you the joke about [quote joke]!' or 'We were talking about X').\n" +
                "3. NEVER reply with evasive brush-offs like 'I am always ready' or 'I can tell jokes' when Boss asks what was said earlier; state the actual conversation facts!"
        }
        promptBuilder.append(personaPrompt)

        val jokesList = getRecentJokes()
        if (jokesList.isNotEmpty()) {
            promptBuilder.append("\n[Jokes already shared in this session: ").append(jokesList.takeLast(3).joinToString(" | ")).append("]")
        }

        val savedMemories = memoryVault?.getMemoryContextForPrompt()
        if (!savedMemories.isNullOrBlank()) {
            promptBuilder.append("\nMemory Vault: ").append(savedMemories.take(160))
        }

        val toolPrompt = "\nTOOL CALLING INSTRUCTION:\n" +
            "If Boss explicitly commands an action you can execute, you may reply with a single <tool_call> JSON:\n" +
            "- WhatsApp: <tool_call>{\"action\": \"whatsapp\", \"recipient\": \"Name\", \"message\": \"text\"}</tool_call>\n" +
            "- Protocol: <tool_call>{\"action\": \"protocol\", \"mode\": \"jarvis|friday|edith|barn_door|baby_monitor|boss_mode\"}</tool_call>\n" +
            "- Shift/Clock: <tool_call>{\"action\": \"shift_status\"}</tool_call>\n" +
            "- Music: <tool_call>{\"action\": \"play_music\", \"query\": \"artist or song\"}</tool_call>\n" +
            "- Navigation: <tool_call>{\"action\": \"navigate\", \"destination\": \"place\"}</tool_call>\n" +
            "Otherwise for normal conversation, reply directly with natural speech and NO tool call.\n"
        promptBuilder.append(toolPrompt)
        promptBuilder.append("<|im_end|>\n")

        synchronized(conversationHistory) {
            while (conversationHistory.size > MAX_HISTORY_TURNS) {
                conversationHistory.removeAt(0)
            }
            for (turn in conversationHistory) {
                promptBuilder.append("<|im_start|>${turn.role}\n${turn.content}<|im_end|>\n")
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
                Log.i(TAG, "Incoming query: '$query'")
                val chatPrompt = buildChatPrompt(query)
                Log.d(TAG, "Chat prompt sent to model:\n$chatPrompt")
                val raw = model!!.generate(chatPrompt)
                val cleaned = raw
                    .replace(Regex("<\\|.*?\\|>"), "")
                    .replace(Regex("[#*`_]"), "")
                    .trim()

                // Filter out any non-standard or foreign characters
                val englishOnly = cleaned.filter { it.code in 32..126 || it == '\n' || it == '’' || it == '‘' || it == '“' || it == '”' }
                    .trim()

                // Keep to maximum 3 concise sentences
                val sentences = englishOnly.split(Regex("(?<=[.!?])\\s+"))
                val truncated = if (sentences.size > 3) sentences.take(3).joinToString(" ") else englishOnly
                val candidateAnswer = truncated.ifEmpty { "I'm right here with you, Boss! What's on your mind?" }

                val isRefusal = candidateAnswer.contains("can't assist", ignoreCase = true) ||
                                candidateAnswer.contains("cannot assist", ignoreCase = true) ||
                                candidateAnswer.contains("unable to assist", ignoreCase = true) ||
                                candidateAnswer.contains("sorry, but I can't", ignoreCase = true)

                val finalAnswer = if (isRefusal) {
                    "I'm all ears, Boss! Tell me what you're thinking, or ask me anything."
                } else {
                    candidateAnswer
                }

                synchronized(conversationHistory) {
                    conversationHistory.add(DialogueTurn("user", query.trim()))
                    conversationHistory.add(DialogueTurn("assistant", finalAnswer))
                }

                memoryVault?.recordJournalTurn(query.trim(), finalAnswer)

                if (query.contains("joke", ignoreCase = true) || finalAnswer.contains("Because ", ignoreCase = true)) {
                    recordJoke(finalAnswer)
                }

                Log.i(TAG, "Generated Answer: $finalAnswer (Raw: '$raw', History turns: ${conversationHistory.size})")
                finalAnswer
            } catch (e: Exception) {
                Log.w(TAG, "Generation error, attempting minimalist recovery", e)
                try {
                    clearHistory()
                    val fallbackPrompt = "<|im_start|>system\nYou are DEVIL, an AI companion for Boss. Respond warmly and naturally in one concise sentence without markdown.<|im_end|>\n<|im_start|>user\n${query.trim()}<|im_end|>\n<|im_start|>assistant\n"
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
