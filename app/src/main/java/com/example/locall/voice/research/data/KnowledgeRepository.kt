package com.example.locall.voice.research.data

import android.content.Context

/**
 * Thin persistence facade over [KnowledgeDatabase]. All joining between documents, chunks and
 * insights happens here in Kotlin (the corpus is tens of documents, so loading it into memory for
 * retrieval is cheap and far simpler than SQL joins). Shared singleton so the import screen, the
 * voice handler and the agent all see the same knowledge base.
 */
class KnowledgeRepository private constructor(context: Context) {

    private val dao = KnowledgeDatabase.get(context).dao()

    suspend fun documentCount(): Int = dao.documentCount()

    suspend fun documents(): List<DocumentEntity> = dao.allDocuments()

    /** Persist one fully-processed document: its row, its chunks and their insights, replacing any prior copy. */
    suspend fun saveDocument(
        doc: DocumentEntity,
        chunks: List<ChunkEntity>,
        insights: List<ChunkInsightEntity>,
    ) {
        // Replace any earlier import of the same document id so re-imports don't duplicate chunks.
        dao.deleteInsightsOf(doc.id)
        dao.deleteChunksOf(doc.id)
        dao.upsertDocument(doc)
        dao.upsertChunks(chunks)
        insights.forEach { dao.upsertInsight(it) }
    }

    suspend fun deleteDocument(docId: String) {
        dao.deleteInsightsOf(docId)
        dao.deleteChunksOf(docId)
        dao.deleteDocument(docId)
    }

    suspend fun clearAll() {
        dao.clearInsights()
        dao.clearEmbeddings()
        dao.clearChunks()
        dao.clearDocuments()
    }

    /**
     * Every chunk joined with its document + insight. This is the corpus the retriever ranks over and
     * the aggregate queries reduce across. Chunks missing an insight (e.g. a failed analysis pass)
     * still appear, carrying their raw text so they remain searchable.
     */
    suspend fun retrievables(): List<RetrievableChunk> {
        val docs = dao.allDocuments().associateBy { it.id }
        val insights = dao.allInsights().associateBy { it.chunkId }
        return dao.allChunks().mapNotNull { chunk ->
            val doc = docs[chunk.docId] ?: return@mapNotNull null
            val ins = insights[chunk.id]
            RetrievableChunk(
                chunkId = chunk.id,
                docId = chunk.docId,
                docTitle = doc.title,
                author = doc.author,
                sourceType = doc.sourceType,
                createdAtMs = doc.createdAtMs,
                ordinal = chunk.ordinal,
                text = chunk.text,
                summary = ins?.summary.orEmpty(),
                entities = ins?.entities?.let { InsightCodec.splitCsv(it) } ?: emptyList(),
                decisions = ins?.decisions?.let { InsightCodec.splitLines(it) } ?: emptyList(),
                dates = ins?.dates?.let { InsightCodec.splitLines(it) } ?: emptyList(),
                commitments = ins?.commitments?.let { InsightCodec.splitLines(it) } ?: emptyList(),
            )
        }
    }

    companion object {
        @Volatile private var instance: KnowledgeRepository? = null

        fun get(context: Context): KnowledgeRepository =
            instance ?: synchronized(this) {
                instance ?: KnowledgeRepository(context.applicationContext).also { instance = it }
            }
    }
}
