package com.example.locall.voice.research.bench.workloads

import android.content.Context
import com.example.locall.voice.research.data.KnowledgeRepository
import com.example.locall.voice.research.data.RetrievableChunk
import com.example.locall.voice.research.pipeline.KeywordRetriever

object Corpus {

    class EmptyException : IllegalStateException(
        "Your knowledge base is empty. Import emails or notes on the Knowledge tab first.",
    )

    suspend fun all(context: Context): List<RetrievableChunk> =
        KnowledgeRepository.get(context).retrievables()

    suspend fun recent(context: Context, n: Int): List<RetrievableChunk> {
        val corpus = all(context)
        if (corpus.isEmpty()) throw EmptyException()
        return corpus
            .sortedWith(compareByDescending<RetrievableChunk> { it.createdAtMs }.thenBy { it.ordinal })
            .take(n.coerceAtLeast(1))
    }

    suspend fun topK(context: Context, query: String, k: Int): List<RetrievableChunk> {
        val corpus = all(context)
        if (corpus.isEmpty()) throw EmptyException()
        return KeywordRetriever.topK(query, corpus, k)
            .ifEmpty { corpus.sortedByDescending { it.createdAtMs }.take(k) }
    }
}
