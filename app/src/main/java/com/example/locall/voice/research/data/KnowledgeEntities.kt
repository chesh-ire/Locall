package com.example.locall.voice.research.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local knowledge base for the Personal Knowledge & Research Agent (feature 5).
 *
 * The store is populated by a multi-stage on-device pipeline: imported documents (emails, pasted
 * notes/transcripts, app data) are split into [ChunkEntity] pieces, each chunk is distilled by the
 * LLM into a [ChunkInsightEntity] (summary + extracted entities/decisions/dates/commitments), and
 * query-time reasoning retrieves and re-reads the most relevant chunks. [ChunkEmbeddingEntity] is a
 * phase-2 slot for a semantic (ONNX) retriever; nothing writes it yet, but the table exists so the
 * embedding backend can be added without a schema migration.
 */

/** One imported source document. [sourceType] is one of [SourceType]. */
@Entity(tableName = "kb_documents")
data class DocumentEntity(
    @PrimaryKey val id: String,         // stable id, e.g. "gmail:<msgId>", "text:<uuid>", "memory:<id>"
    val title: String,
    val sourceType: String,             // see SourceType
    val sourceRef: String,              // original reference (gmail id, file name, …) for provenance
    val author: String,                 // "who" — sender / "me" / app, used in citations
    val createdAtMs: Long,              // when the content itself is from (for time-window queries)
    val importedAtMs: Long,
    val charCount: Int,
    val chunkCount: Int,
)

/** A contiguous slice of a document's text, in reading order. */
@Entity(
    tableName = "kb_chunks",
    indices = [Index("docId")],
)
data class ChunkEntity(
    @PrimaryKey val id: String,         // "<docId>#<ordinal>"
    val docId: String,
    val ordinal: Int,
    val text: String,
)

/** The LLM-distilled understanding of a single chunk. List fields are newline-joined ("" = none). */
@Entity(
    tableName = "kb_chunk_insights",
    indices = [Index("docId")],
)
data class ChunkInsightEntity(
    @PrimaryKey val chunkId: String,
    val docId: String,
    val summary: String,
    val entities: String,               // CSV of people/projects/topics
    val decisions: String,              // newline-joined
    val dates: String,                  // newline-joined
    val commitments: String,            // newline-joined ("who owes what")
    val updatedAtMs: Long,
)

/** Phase-2 semantic vector for a chunk. [vector] is a little-endian float[] blob of length [dim]. */
@Entity(tableName = "kb_chunk_embeddings")
data class ChunkEmbeddingEntity(
    @PrimaryKey val chunkId: String,
    val dim: Int,
    val vector: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ChunkEmbeddingEntity && chunkId == other.chunkId)

    override fun hashCode(): Int = chunkId.hashCode()
}

object SourceType {
    const val GMAIL = "gmail"
    const val TEXT = "text"
    const val MEMORY = "memory"
    const val MUSIC = "music"
}
