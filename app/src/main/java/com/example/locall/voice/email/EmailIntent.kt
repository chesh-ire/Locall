package com.example.locall.voice.email

sealed interface EmailIntent {

    data class ReadLatest(val count: Int = 1) : EmailIntent

    /** [count] == 1 summarises the single newest matching email's body; otherwise an overview of up to [count]. */
    data class Summarize(val query: String = "newer_than:1d in:inbox", val count: Int = 20) : EmailIntent

    /**
     * Summarise a whole conversation (thread), incrementally. Empty [query] → the in-focus email's thread
     * (last read/searched/summarised); a non-empty query locates the anchor email first, then its thread.
     */
    data class SummarizeThread(val query: String = "") : EmailIntent

    /** Find a specific email by content (full-text Gmail query), then read the best match aloud. */
    data class Search(val query: String) : EmailIntent

    data object FindImportant : EmailIntent

    data object UnreadCount : EmailIntent

    data class MarkAllRead(val query: String = "is:unread in:inbox") : EmailIntent

    data class Archive(val query: String) : EmailIntent

    /** [count] caps how many matches to trash (most-recent first); defaults to all. */
    data class Delete(val query: String, val count: Int = Int.MAX_VALUE) : EmailIntent

    data object ClearInbox : EmailIntent

    /** Compose and send a new email. Always confirmed with the user before sending. */
    data class Send(val to: String, val subject: String, val body: String) : EmailIntent

    /**
     * Reply in-thread to the email matching [query] (or the current in-focus email when [query] is
     * generic). [body] is the user's INTENDED MESSAGE (the gist they dictated) — the LLM refines it into
     * a natural reply, the user may refine further across turns, and it's only sent on explicit approval.
     */
    data class Reply(val query: String, val body: String) : EmailIntent

    data class Unknown(val reason: String = "") : EmailIntent
}
