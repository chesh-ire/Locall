package com.example.locall.voice.research.pipeline

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor

private const val TAG = "ChunkAnalyzer"

/**
 * Stage 2 of the pipeline ("chunk & understand"): distils one chunk of text into a structured insight
 * with a single on-device LLM call. Everything here is one inference per chunk, serialized through the
 * shared [LlmBridge] handle — the per-chunk passes are what make a large import genuinely stress the
 * device.
 */
object ChunkAnalyzer {

    private const val MAX_TOKENS = 220

    data class Analysis(
        val summary: String,
        val entities: List<String>,
        val decisions: List<String>,
        val dates: List<String>,
        val commitments: List<String>,
    )

    private val SYSTEM_PROMPT = """
        You read ONE excerpt from the user's personal documents and extract its key information so it can
        later be searched and reasoned over. Base everything ONLY on the excerpt; never invent people,
        decisions, dates or facts. Output EXACTLY these labelled sections and nothing else:
        SUMMARY: <2-3 sentences: what this excerpt is about and the main points>
        ENTITIES: <comma-separated people, projects, companies and topics mentioned; empty if none>
        DECISIONS:
        - <each decision or conclusion reached; write None if there are none>
        DATES:
        - <each date, deadline or time reference, with what it refers to; write None if there are none>
        COMMITMENTS:
        - <each promise or action someone committed to, who owes what; write None if there are none>
    """.trimIndent()

    suspend fun analyze(context: Context, chunkText: String): Analysis {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        return try {
            val raw = LlmBridge.generateText(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = chunkText,
                temperature = 0.3f,
                maxTokens = MAX_TOKENS,
                decodeThreads = decodeThreads,
            )
            parse(raw) ?: fallback(chunkText)
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed, using fallback", t)
            fallback(chunkText)
        }
    }

    private fun parse(raw: String): Analysis? {
        val text = raw.trim()
        val summary = LabeledSections.section(text, "SUMMARY", "ENTITIES", "DECISIONS", "DATES", "COMMITMENTS")
            ?: return null
        val entities = LabeledSections.section(text, "ENTITIES", "DECISIONS", "DATES", "COMMITMENTS")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() && !it.equals("none", true) }
            ?: emptyList()
        val decisions = LabeledSections.listSection(text, "DECISIONS", "DATES", "COMMITMENTS")
        val dates = LabeledSections.listSection(text, "DATES", "COMMITMENTS")
        val commitments = LabeledSections.listSection(text, "COMMITMENTS")
        if (summary.isBlank() && entities.isEmpty() && decisions.isEmpty() &&
            dates.isEmpty() && commitments.isEmpty()
        ) return null
        return Analysis(summary.trim(), entities, decisions, dates, commitments)
    }

    /** No usable model output: keep the chunk searchable with a raw excerpt as its "summary". */
    private fun fallback(chunkText: String): Analysis =
        Analysis(chunkText.take(300), emptyList(), emptyList(), emptyList(), emptyList())
}
