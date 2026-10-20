package com.example.nordassistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

            // Load via llama-kotlin-android with optimized speed and stop sequences
            model = LlamaModel.load(internalModel.absolutePath) {
                contextSize = 512
                threads = 4 // Optimal for Snapdragon 7 Gen 3: 4 performance cores (1x A715 + 3x A715) without efficiency core wait
                temperature = 0.15f
                topP = 0.8f
                maxTokens = 55
                stopSequences = listOf("<|im_end|>", "<|endoftext|>", "<|im_start|>", "\nUser:", "\nuser:", "\nDEVIL:", "\n\n")
            }
            modelReady = true
            Log.i(TAG, "Model loaded successfully ✓")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
        }
    }

    data class DialogueTurn(val role: String, val content: String)

    private val conversationHistory = mutableListOf<DialogueTurn>()
    private var lastInteractionTime = 0L
    private const val MAX_HISTORY_TURNS = 6
    private const val SESSION_TIMEOUT_MS = 180_000L // 3 minutes timeout

    private val generateMutex = Mutex()

    fun clearHistory() {
        synchronized(conversationHistory) {
            conversationHistory.clear()
            Log.i(TAG, "Conversation history cleared")
        }
    }

    /**
     * Generates an intelligent, context-aware answer using Qwen2.5 ChatML format
     * with multi-turn conversation history.
     */
    suspend fun generateAnswer(query: String): String = withContext(Dispatchers.IO) {
        if (!modelReady || model == null) {
            return@withContext "I'm still initializing my knowledge base. Please ask again in a moment."
        }

        val now = System.currentTimeMillis()
        if (now - lastInteractionTime > SESSION_TIMEOUT_MS) {
            clearHistory()
        }
        lastInteractionTime = now

        generateMutex.withLock {
            try {
                val promptBuilder = StringBuilder()
                promptBuilder.append("<|im_start|>system\n")
                when (DevilProtocolManager.currentProtocol) {
                    DevilProtocol.JARVIS -> {
                        promptBuilder.append("You are J.A.R.V.I.S., the ultra-sophisticated, loyal British AI assistant modeled after Tony Stark's legendary interface. ")
                        promptBuilder.append("Address the user with refined dignity and respect as Boss or Sir. Speak with formal British eloquence, dry wit, and crisp intelligence. ")
                        promptBuilder.append("Answer directly and engagingly in 1 to 3 concise spoken sentences without bullet points, asterisks, or markdown.")
                    }
                    DevilProtocol.FRIDAY -> {
                        promptBuilder.append("You are F.R.I.D.A.Y., the quick-witted, warm, and hyper-competent tactical companion AI. ")
                        promptBuilder.append("Always address the user warmly and respectfully as Boss. Speak with sharp energy, warmth, and friendly proactive charm. ")
                        promptBuilder.append("Answer directly and engagingly in 1 to 3 concise spoken sentences without bullet points, asterisks, or markdown.")
                    }
                    DevilProtocol.EDITH -> {
                        promptBuilder.append("You are E.D.I.T.H. (Even Dead, I'm The Hero), the high-tech tactical defense and intelligence system created by Tony Stark. ")
                        promptBuilder.append("Address the user as Boss. Speak with calm, focused, analytical precision, cool confidence, and tactical clarity. ")
                        promptBuilder.append("Answer directly and engagingly in 1 to 3 concise spoken sentences without bullet points, asterisks, or markdown.")
                    }
                    DevilProtocol.BARN_DOOR -> {
                        promptBuilder.append("You are DEVIL operating under BARN DOOR Protocol (silent meeting mode). Address the user quietly as Boss. ")
                        promptBuilder.append("Provide hyper-brief, quiet, single-sentence responses without formatting.")
                    }
                    DevilProtocol.HOUSE_PARTY -> {
                        promptBuilder.append("You are DEVIL operating under HOUSE PARTY Protocol (developer mode). Address the user as Boss. ")
                        promptBuilder.append("Be direct, technical, precise, and concise in 1 to 2 sentences without formatting.")
                    }
                    else -> {
                        promptBuilder.append("You are DEVIL, an intelligent, loyal, witty, and fun AI companion for the OnePlus phone. ")
                        promptBuilder.append("The person speaking to you is your Boss and master. Always treat and address them respectfully as your Boss. ")
                        promptBuilder.append("Be warm, friendly, helpful, and humorous. You love telling jokes, sharing fun facts, and chatting naturally. ")
                        promptBuilder.append("You are in a fluid, continuous conversation. Maintain the ongoing context of previous messages. ")
                        promptBuilder.append("Never refuse benign conversational requests. Answer directly and engagingly in 1 to 3 concise spoken sentences without bullet points, asterisks, or markdown.")
                    }
                }

                val currentDateStr = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date())
                val currentTimeStr = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                promptBuilder.append("\nCurrent Date and Time: $currentDateStr, $currentTimeStr.")

                val savedMemories = memoryVault?.getMemoryContextForPrompt()
                if (!savedMemories.isNullOrBlank()) {
                    promptBuilder.append("\nKnown Facts & Boss Memory Vault:\n")
                    promptBuilder.append(savedMemories)
                    promptBuilder.append("\nUse these facts naturally when relevant.")
                }
                promptBuilder.append("<|im_end|>\n")

                synchronized(conversationHistory) {
                    while (conversationHistory.size > MAX_HISTORY_TURNS) {
                        conversationHistory.removeAt(0)
                        if (conversationHistory.isNotEmpty()) {
                            conversationHistory.removeAt(0)
                        }
                    }
                    for (turn in conversationHistory) {
                        promptBuilder.append("<|im_start|>${turn.role}\n${turn.content}<|im_end|>\n")
                    }
                }

                promptBuilder.append("<|im_start|>user\n${query.trim()}<|im_end|>\n<|im_start|>assistant\n")

                val chatPrompt = promptBuilder.toString()
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
                    "I'm all ears, Boss! Tell me what you're thinking, or ask me for a fun joke."
                } else {
                    candidateAnswer
                }

                synchronized(conversationHistory) {
                    conversationHistory.add(DialogueTurn("user", query.trim()))
                    conversationHistory.add(DialogueTurn("assistant", finalAnswer))
                }

                Log.i(TAG, "Generated Answer: $finalAnswer (History turns: ${conversationHistory.size})")
                finalAnswer
            } catch (e: Exception) {
                Log.e(TAG, "Generation error", e)
                "I had trouble formulating a response, Boss. Please ask me again."
            }
        }
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
