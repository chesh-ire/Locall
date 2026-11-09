package com.example.locall.voice.research.pipeline

import com.example.locall.voice.research.data.RetrievableChunk

/**
 * Stage-4 retrieval interface. The agent asks for the chunks most relevant to a question; how that
 * relevance is computed is pluggable. Phase 1 ships [KeywordRetriever] (lexical, zero dependencies);
 * phase 2 can drop in an ONNX embedding backend behind this same interface without touching the agent.
 */
interface Retriever {
    fun topK(query: String, corpus: List<RetrievableChunk>, k: Int): List<RetrievableChunk>
}

/**
 * Lexical retriever: scores each chunk by how many query terms it contains (across its text, summary
 * and extracted entities), with entity/summary hits weighted higher and recency as the tie-break.
 * Simple and fully offline; the known gap is purely-semantic matches (e.g. "Android project" vs "the
 * mobile app"), which the phase-2 embedding backend is meant to close.
 */
object KeywordRetriever : Retriever {

    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "my", "me", "i",
        "what", "which", "who", "whom", "whose", "when", "where", "how", "is", "are", "was", "were",
        "do", "does", "did", "have", "has", "had", "about", "over", "last", "all", "any", "that",
        "this", "these", "those", "been", "made", "make", "give", "summarise", "summarize", "tell",
    )

    override fun topK(query: String, corpus: List<RetrievableChunk>, k: Int): List<RetrievableChunk> {
        val terms = terms(query)
        if (terms.isEmpty() || corpus.isEmpty()) return corpus.sortedByDescending { it.createdAtMs }.take(k)
        return corpus
            .map { it to score(it, terms) }
            .filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<RetrievableChunk, Double>> { it.second }
                .thenByDescending { it.first.createdAtMs })
            .take(k)
            .map { it.first }
    }

    private fun score(chunk: RetrievableChunk, terms: List<String>): Double {
        val body = chunk.text.lowercase()
        val summary = chunk.summary.lowercase()
        val entities = chunk.entities.joinToString(" ").lowercase()
        var score = 0.0
        for (t in terms) {
            if (entities.contains(t)) score += 3.0
            if (summary.contains(t)) score += 2.0
            val occ = countOccurrences(body, t)
            if (occ > 0) score += 1.0 + minOf(occ - 1, 3) * 0.25   // present + small bonus for repeats
        }
        return score
    }

    private fun terms(query: String): List<String> =
        query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOPWORDS }
            .distinct()

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }
}
