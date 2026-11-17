package com.example.locall.voice.assistant.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved conversation. Titled from its first user turn; [updatedAtMs] advances every time a message
 * is appended so the drawer can list sessions most-recently-used first.
 */
@Entity(tableName = "conversation_sessions")
data class ConversationSession(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/**
 * A durable transcript line belonging to a [ConversationSession]. Only terminal roles are persisted
 * (user / assistant / error) — transient UI states (status spinners, pending confirm/prompt bubbles)
 * are never written. [role] stores the [com.example.locall.voice.assistant.ChatRole] name so the
 * transcript reloads faithfully; [id] is DB-assigned and doubles as the stable Compose list key.
 *
 * Deleting the parent session cascades to its messages.
 */
@Entity(
    tableName = "conversation_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val role: String,
    val content: String,
    val capability: String?,
    val createdAtMs: Long,
)
