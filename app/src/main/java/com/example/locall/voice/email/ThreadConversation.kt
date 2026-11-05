package com.example.locall.voice.email

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor

private const val TAG = "ThreadConversation"

/**
 * Answers conversational follow-up questions about a thread the user just had summarised. It first tries
 * to answer from the compact stored [ThreadSummarizer.ThreadSummary] (cheap, no network); only when that
 * briefing lacks the needed detail does it pull the original messages on demand and answer from those.
 */
object ThreadConversation {

    /** Sentinel the model emits when the briefing can't answer — triggers the on-demand original fetch. */
    private const val NEED_ORIGINALS = "NEED_ORIGINALS"
    private const val ORIGINALS_CHAR_CAP = 4500 // keep input within n_ctx=2048 alongside system + answer

    private val FROM_SUMMARY_PROMPT = """
        You answer the user's question about an email thread using ONLY the briefing and extracted items
        provided. Answer in 1-3 spoken sentences, plain English, no preamble. If the briefing does not
        contain enough detail to answer the question, reply with EXACTLY this token and nothing else:
        $NEED_ORIGINALS
    """.trimIndent()

    private val FROM_ORIGINALS_PROMPT =
        "You answer the user's question using the original email thread messages provided. Answer in 1-3 " +
            "spoken sentences, plain English, no preamble. If the answer isn't in the messages, say you " +
            "couldn't find it in the thread."

    /**
     * Answer [question] about the thread. [fetchOriginals] is called lazily only if the summary is
     * insufficient, and should return the thread's original message bodies as plain text.
     */
    suspend fun answer(
        context: Context,
        summary: ThreadSummarizer.ThreadSummary,
        question: String,
        fetchOriginals: suspend () -> String,
    ): String {
        val briefing = buildBriefing(summary)
        val fromSummary = generate(
            context, FROM_SUMMARY_PROMPT,
            "Thread briefing:\n$briefing\n\nQuestion: $question\nAnswer:",
        )
        if (fromSummary.isNotBlank() && !fromSummary.contains(NEED_ORIGINALS, ignoreCase = true)) {
            return fromSummary
        }

        Log.i(TAG, "summary insufficient for \"$question\" — fetching originals")
        val originals = try {
            fetchOriginals()
        } catch (t: Throwable) {
            Log.w(TAG, "fetchOriginals failed", t); ""
        }
        if (originals.isBlank()) return "I don't have enough detail to answer that."

        val fromOriginals = generate(
            context, FROM_ORIGINALS_PROMPT,
            "Original thread messages:\n${originals.take(ORIGINALS_CHAR_CAP)}\n\nQuestion: $question\nAnswer:",
        )
        return fromOriginals.ifBlank { "I couldn't find that in the thread." }
    }

    private fun buildBriefing(s: ThreadSummarizer.ThreadSummary): String = buildString {
        append(s.summary)
        if (s.decisions.isNotEmpty()) append("\nDecisions: ").append(s.decisions.joinToString("; "))
        if (s.actions.isNotEmpty()) append("\nAction items: ").append(s.actions.joinToString("; "))
        if (s.deadlines.isNotEmpty()) append("\nDeadlines: ").append(s.deadlines.joinToString("; "))
    }

    private suspend fun generate(context: Context, system: String, user: String): String {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        return try {
            LlmBridge.generateText(
                systemPrompt = system, userPrompt = user,
                temperature = 0.4f, maxTokens = 160, decodeThreads = decodeThreads,
            ).trim()
        } catch (t: Throwable) {
            Log.w(TAG, "generate failed", t); ""
        }
    }
}
