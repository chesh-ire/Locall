package com.example.locall.voice.research.pipeline

/**
 * Deterministic text chunker. Splits a document into pieces that each fit comfortably inside the
 * on-device model's 2048-token context (prompt + input + generation share that budget, ~4 chars ≈ 1
 * token). Prefers paragraph boundaries; hard-splits any single paragraph larger than the budget.
 *
 * Kept deterministic on purpose (no model call) so the LLM is only ever used for genuine language
 * work — mirroring the approach in the email ThreadSummarizer.
 */
object Chunker {

    private const val CHUNK_CHAR_BUDGET = 3200   // ~800 tokens of content, leaving room for prompt + output

    fun chunk(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= CHUNK_CHAR_BUDGET) return listOf(trimmed)

        val paragraphs = trimmed.split(Regex("\n\\s*\n"))
            .flatMap { if (it.length > CHUNK_CHAR_BUDGET) hardSplit(it) else listOf(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (p in paragraphs) {
            if (sb.isNotEmpty() && sb.length + p.length + 2 > CHUNK_CHAR_BUDGET) {
                chunks.add(sb.toString()); sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(p)
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        return chunks
    }

    /** Break an over-long paragraph on sentence-ish boundaries, falling back to a hard char cut. */
    private fun hardSplit(p: String): List<String> {
        val out = ArrayList<String>()
        var rest = p
        while (rest.length > CHUNK_CHAR_BUDGET) {
            val window = rest.substring(0, CHUNK_CHAR_BUDGET)
            val cut = window.lastIndexOfAny(charArrayOf('.', '!', '?', '\n')).let {
                if (it >= CHUNK_CHAR_BUDGET / 2) it + 1 else CHUNK_CHAR_BUDGET
            }
            out.add(rest.substring(0, cut))
            rest = rest.substring(cut)
        }
        if (rest.isNotBlank()) out.add(rest)
        return out
    }
}
