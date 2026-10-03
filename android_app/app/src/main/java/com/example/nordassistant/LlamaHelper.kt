package com.example.nordassistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
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

            // Load via llama-kotlin-android
            model = LlamaModel.load(internalModel.absolutePath) {
                contextSize = 1024   // Keep small for speed on mobile
                threads = 4
                temperature = 0.7f
            }
            modelReady = true
            Log.i(TAG, "Model loaded successfully ✓")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
        }
    }

    /**
     * Generates a short answer for [prompt].
     * Returns a plain-text string suitable for TTS.
     */
    suspend fun generateAnswer(prompt: String): String = withContext(Dispatchers.IO) {
        if (!modelReady || model == null) {
            return@withContext "Model is still loading. Please wait a moment."
        }
        try {
            val result = model!!.generate(prompt)
            result.trim().ifEmpty { "I'm not sure how to answer that." }
        } catch (e: Exception) {
            Log.e(TAG, "Generation error", e)
            "Sorry, I couldn't generate an answer."
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
