package com.example.locall.voice.research.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface KnowledgeDao {

    // ----- writes -----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(doc: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunks(chunks: List<ChunkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInsight(insight: ChunkInsightEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbedding(embedding: ChunkEmbeddingEntity)

    // ----- reads -----

    @Query("SELECT * FROM kb_documents ORDER BY importedAtMs DESC")
    suspend fun allDocuments(): List<DocumentEntity>

    @Query("SELECT * FROM kb_documents WHERE id = :id LIMIT 1")
    suspend fun document(id: String): DocumentEntity?

    @Query("SELECT COUNT(*) FROM kb_documents")
    suspend fun documentCount(): Int

    @Query("SELECT * FROM kb_chunks")
    suspend fun allChunks(): List<ChunkEntity>

    @Query("SELECT * FROM kb_chunks WHERE id IN (:ids)")
    suspend fun chunksByIds(ids: List<String>): List<ChunkEntity>

    @Query("SELECT * FROM kb_chunk_insights")
    suspend fun allInsights(): List<ChunkInsightEntity>

    @Query("SELECT * FROM kb_chunk_embeddings")
    suspend fun allEmbeddings(): List<ChunkEmbeddingEntity>

    // ----- deletes -----

    @Query("DELETE FROM kb_chunks WHERE docId = :docId")
    suspend fun deleteChunksOf(docId: String)

    @Query("DELETE FROM kb_chunk_insights WHERE docId = :docId")
    suspend fun deleteInsightsOf(docId: String)

    @Query("DELETE FROM kb_documents WHERE id = :docId")
    suspend fun deleteDocument(docId: String)

    @Query("DELETE FROM kb_documents")
    suspend fun clearDocuments()

    @Query("DELETE FROM kb_chunks")
    suspend fun clearChunks()

    @Query("DELETE FROM kb_chunk_insights")
    suspend fun clearInsights()

    @Query("DELETE FROM kb_chunk_embeddings")
    suspend fun clearEmbeddings()
}
