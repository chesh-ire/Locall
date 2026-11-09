package com.example.locall.voice.research.pipeline

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.research.data.ChunkEntity
import com.example.locall.voice.research.data.ChunkInsightEntity
import com.example.locall.voice.research.data.DocumentEntity
import com.example.locall.voice.research.data.InsightCodec
import com.example.locall.voice.research.data.KnowledgeRepository
import com.example.locall.voice.research.importer.RawDocument

private const val TAG = "KnowledgeBuilder"

/**
 * Stages 1–3 of the pipeline: take imported [RawDocument]s, chunk them, run the per-chunk
 * [ChunkAnalyzer], and persist the result as the knowledge base. All analysis funnels through the
 * single on-device inference thread, so a 50-document import is tens-to-hundreds of sequential LLM
 * passes — reported chunk-by-chunk so the UI can show real progress.
 */
object KnowledgeBuilder {

    data class Progress(val phase: String, val current: Int, val total: Int, val detail: String = "")

    data class Result(val documents: Int, val chunks: Int)

    suspend fun build(
        context: Context,
        docs: List<RawDocument>,
        onProgress: (Progress) -> Unit = {},
        onStatus: (String) -> Unit = {},
    ): Result {
        if (docs.isEmpty()) return Result(0, 0)
        onStatus("Loading AI model…")
        LlmBridge.ensureLoaded(context, onStatus)

        val repo = KnowledgeRepository.get(context)
        val now = System.currentTimeMillis()

        // Chunk everything first so progress can be reported against a real total.
        val plan = docs.map { doc -> doc to Chunker.chunk(doc.text) }.filter { it.second.isNotEmpty() }
        val totalChunks = plan.sumOf { it.second.size }
        var done = 0
        var savedDocs = 0

        for ((doc, pieces) in plan) {
            val chunkEntities = ArrayList<ChunkEntity>(pieces.size)
            val insightEntities = ArrayList<ChunkInsightEntity>(pieces.size)
            pieces.forEachIndexed { ordinal, pieceText ->
                val chunkId = "${doc.id}#$ordinal"
                chunkEntities += ChunkEntity(chunkId, doc.id, ordinal, pieceText)
                onProgress(Progress("Understanding", done, totalChunks, doc.title))
                val a = ChunkAnalyzer.analyze(context, pieceText)
                insightEntities += ChunkInsightEntity(
                    chunkId = chunkId,
                    docId = doc.id,
                    summary = a.summary,
                    entities = InsightCodec.joinCsv(a.entities),
                    decisions = InsightCodec.joinLines(a.decisions),
                    dates = InsightCodec.joinLines(a.dates),
                    commitments = InsightCodec.joinLines(a.commitments),
                    updatedAtMs = now,
                )
                done++
            }
            repo.saveDocument(
                doc = DocumentEntity(
                    id = doc.id,
                    title = doc.title,
                    sourceType = doc.sourceType,
                    sourceRef = doc.sourceRef,
                    author = doc.author,
                    createdAtMs = doc.createdAtMs,
                    importedAtMs = now,
                    charCount = doc.text.length,
                    chunkCount = pieces.size,
                ),
                chunks = chunkEntities,
                insights = insightEntities,
            )
            savedDocs++
            Log.i(TAG, "built '${doc.title}' -> ${pieces.size} chunk(s)")
            onProgress(Progress("Understanding", done, totalChunks, doc.title))
        }
        return Result(savedDocs, done)
    }
}
