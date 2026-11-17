package com.example.locall.voice.assistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    // ---------------- sessions ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ConversationSession)

    @Query("SELECT * FROM conversation_sessions ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun mostRecentSession(): ConversationSession?

    @Query("SELECT * FROM conversation_sessions WHERE id = :id LIMIT 1")
    suspend fun session(id: String): ConversationSession?

    /** Live list for the drawer, most-recently-updated first. */
    @Query("SELECT * FROM conversation_sessions ORDER BY updatedAtMs DESC LIMIT :limit")
    fun recentSessionsFlow(limit: Int): Flow<List<ConversationSession>>

    @Query("UPDATE conversation_sessions SET title = :title, updatedAtMs = :ts WHERE id = :id")
    suspend fun renameSession(id: String, title: String, ts: Long)

    @Query("UPDATE conversation_sessions SET updatedAtMs = :ts WHERE id = :id")
    suspend fun touchSession(id: String, ts: Long)

    /** Messages cascade-delete via the foreign key. */
    @Query("DELETE FROM conversation_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    // ---------------- messages ----------------

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("SELECT * FROM conversation_messages WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun messagesFor(sessionId: String): List<ChatMessageEntity>
}
