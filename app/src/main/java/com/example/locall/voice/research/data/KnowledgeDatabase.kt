package com.example.locall.voice.research.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * On-device knowledge base for the research agent (feature 5). Fully local — the documents, their
 * chunks, the per-chunk LLM insights, and (phase 2) their embeddings never leave the device.
 */
@Database(
    entities = [
        DocumentEntity::class,
        ChunkEntity::class,
        ChunkInsightEntity::class,
        ChunkEmbeddingEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class KnowledgeDatabase : RoomDatabase() {
    abstract fun dao(): KnowledgeDao

    companion object {
        @Volatile private var instance: KnowledgeDatabase? = null

        fun get(context: Context): KnowledgeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, KnowledgeDatabase::class.java, "knowledge.db",
                ).build().also { instance = it }
            }
    }
}
