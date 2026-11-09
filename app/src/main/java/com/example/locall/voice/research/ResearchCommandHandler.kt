package com.example.locall.voice.research

import android.content.Context
import android.util.Log
import com.example.locall.voice.research.pipeline.ResearchAgent

private const val TAG = "ResearchCommandHandler"

/**
 * Voice entry point to the knowledge agent: runs the multi-step [ResearchAgent] over the question,
 * speaks a citation-free version of the grounded answer, and returns the full answer (with its
 * [source] tags) for on-screen display.
 */
class ResearchCommandHandler(appContext: Context) {

    private val app = appContext.applicationContext

    suspend fun handle(
        context: Context,
        transcript: String,
        onStatus: (String) -> Unit,
        speak: (String) -> Unit,
    ): String {
        return try {
            val answer = ResearchAgent.answer(context, transcript.trim(), onStatus)
            Log.i(TAG, "answer: ${answer.llmPasses} passes over ${answer.sourcesConsidered} source(s)")
            speak(forSpeech(answer.text))
            buildString {
                append("🧠 ").append(answer.text)
                if (answer.citations.isNotEmpty()) {
                    append("\n\nSources: ").append(answer.citations.joinToString(", "))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "research failed", t)
            "⚠️ research failed: ${t.javaClass.simpleName}"
        }
    }

    /** Drop the "[Source]" citation tags for the spoken version — they read awkwardly aloud. */
    private fun forSpeech(text: String): String =
        text.replace(Regex("\\[[^\\]]*\\]"), "").replace(Regex("[ \\t]{2,}"), " ").trim()
}
