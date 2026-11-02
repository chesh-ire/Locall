package com.example.locall.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LlmModelManager {
    private const val TAG = "LlmModelManager"
    private const val ASSET_PATH = "llm/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
    private const val MODEL_FILENAME = "Llama-3.2-1B-Instruct-Q4_K_M.gguf"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "llm"), MODEL_FILENAME)

    private fun doneMarker(context: Context): File =
        File(File(context.filesDir, "llm"), ".extracted")

    fun isExtracted(context: Context): Boolean =
        doneMarker(context).exists() && modelFile(context).exists()

    suspend fun ensureExtracted(context: Context, onStatus: (String) -> Unit = {}) {
        if (isExtracted(context)) return
        withContext(Dispatchers.IO) {
            onStatus("Preparing AI model (first run)…")
            Log.i(TAG, "extracting $ASSET_PATH to ${modelFile(context)}")
            val dest = modelFile(context)
            dest.parentFile?.mkdirs()
            doneMarker(context).delete()
            context.assets.open(ASSET_PATH).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            doneMarker(context).createNewFile()
            Log.i(TAG, "extraction complete")
            onStatus("AI model ready")
        }
    }
}
