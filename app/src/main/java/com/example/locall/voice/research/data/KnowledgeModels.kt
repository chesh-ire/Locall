package com.example.locall.voice.research.data

/**
 * A chunk joined with its document + LLM insight, ready for retrieval, reasoning and citation.
 * This is the unit the retriever ranks and the agent re-reads; list fields are already parsed.
 */
data class RetrievableChunk(
    val chunkId: String,
    val docId: String,
    val docTitle: String,
    val author: String,
    val sourceType: String,
    val createdAtMs: Long,
    val ordinal: Int,
    val text: String,
    val summary: String,
    val entities: List<String>,
    val decisions: List<String>,
    val dates: List<String>,
    val commitments: List<String>,
) {
    /** Short "[Title]" style tag the model is told to cite and the UI renders. */
    val citation: String get() = "[$docTitle]"
}

/** Multi-value fields are stored newline-joined; entities as CSV. These keep encode/decode in one place. */
object InsightCodec {
    fun joinLines(items: List<String>): String = items.filter { it.isNotBlank() }.joinToString("\n")
    fun splitLines(s: String): List<String> = s.split('\n').map { it.trim() }.filter { it.isNotBlank() }
    fun joinCsv(items: List<String>): String = items.filter { it.isNotBlank() }.joinToString(", ")
    fun splitCsv(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotBlank() }
}
