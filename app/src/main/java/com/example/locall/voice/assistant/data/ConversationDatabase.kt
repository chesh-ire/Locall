package com.example.locall.voice.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Persistent conversation history for the central assistant. Independent of the memory / inference
 * databases — it stores only the on-screen transcript so a conversation survives process death.
 *
 * Schema changes MUST ship a Room [androidx.room.migration.Migration]; the builder intentionally does
 * not enable destructive migration, so a missing migration fails loudly rather than wiping saved chats.
 */
@Database(
    entities = [
        ConversationSession::class,
        ChatMessageEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun dao(): ConversationDao

    companion object {
        @Volatile private var instance: ConversationDatabase? = null

        fun get(context: Context): ConversationDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, ConversationDatabase::class.java, "conversations.db",
                ).build().also { instance = it }
            }
    }
}
