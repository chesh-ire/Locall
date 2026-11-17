package com.example.locall.voice.assistant.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Persistence facade for conversation history. Sits between [ConversationDao] and the
 * [com.example.locall.voice.assistant.AssistantViewModel], keeping Room out of the ViewModel. All calls
 * are suspend and thread-hop onto Room's own executor, so they are safe to invoke from any dispatcher.
 */
class ConversationRepository(private val dao: ConversationDao) {

    constructor(context: Context) : this(ConversationDatabase.get(context).dao())

    /** Create a session, titling it from its first user turn. Returns the new session id. */
    suspend fun createSession(id: String, firstUserText: String, nowMs: Long): ConversationSession {
        val session = ConversationSession(
            id = id,
            title = titleFrom(firstUserText),
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
        dao.upsertSession(session)
        return session
    }

    /** Append a message and bump the session's updated time so it floats to the top of the list. */
    suspend fun saveMessage(
        sessionId: String,
        role: String,
        content: String,
        capability: String?,
        nowMs: Long,
    ): Long {
        val rowId = dao.insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                role = role,
                content = content,
                capability = capability,
                createdAtMs = nowMs,
            ),
        )
        dao.touchSession(sessionId, nowMs)
        return rowId
    }

    suspend fun messagesFor(sessionId: String): List<ChatMessageEntity> = dao.messagesFor(sessionId)

    suspend fun mostRecentSession(): ConversationSession? = dao.mostRecentSession()

    suspend fun session(id: String): ConversationSession? = dao.session(id)

    fun recentSessionsFlow(limit: Int = 100): Flow<List<ConversationSession>> =
        dao.recentSessionsFlow(limit)

    suspend fun deleteSession(id: String) = dao.deleteSession(id)

    companion object {
        const val TITLE_CHARS = 48

        /** A short, single-line title from the opening user message; falls back to a generic label. */
        fun titleFrom(text: String): String {
            val t = text.trim().replace(Regex("\\s+"), " ")
            if (t.isEmpty()) return "New conversation"
            return if (t.length > TITLE_CHARS) t.take(TITLE_CHARS - 1).trimEnd() + "…" else t
        }
    }
}
