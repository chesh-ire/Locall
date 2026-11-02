package com.example.locall.voice.email

sealed interface EmailIntent {

    data class ReadLatest(val count: Int = 1) : EmailIntent

    data class Summarize(val query: String = "newer_than:1d in:inbox") : EmailIntent

    data object FindImportant : EmailIntent

    data object UnreadCount : EmailIntent

    data class MarkAllRead(val query: String = "is:unread in:inbox") : EmailIntent

    data class Archive(val query: String) : EmailIntent

    data class Delete(val query: String) : EmailIntent

    data object ClearInbox : EmailIntent

    data class Unknown(val reason: String = "") : EmailIntent
}
