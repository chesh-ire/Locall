package com.example.locall.voice.email

object EmailIntents {

    private val KEYWORDS = listOf("email", "emails", "e-mail", "e-mails", "inbox", "mailbox", "gmail")

    fun matches(normalized: String): Boolean {
        val t = normalized.trim()
        if (OPEN_APP_PREFIXES.any { t.startsWith(it) }) return false
        return KEYWORDS.any { kw -> containsWord(t, kw) }
    }

    fun resolve(normalized: String): EmailIntent? {
        val t = normalized.trim()

        if (t in READ_LATEST) return EmailIntent.ReadLatest(1)
        if (UNREAD_COUNT.any { t.startsWith(it) || t == it }) return EmailIntent.UnreadCount
        if (IMPORTANT.any { t.startsWith(it) }) return EmailIntent.FindImportant
        if (SUMMARIZE_TODAY.any { t.startsWith(it) }) return EmailIntent.Summarize("newer_than:1d in:inbox")
        if (SUMMARIZE_ANY.any { t.startsWith(it) }) return EmailIntent.Summarize("in:inbox")

        if (MARK_ALL_READ.any { t.startsWith(it) }) return EmailIntent.MarkAllRead()
        if (DELETE_PROMOS.any { t.startsWith(it) }) return EmailIntent.Delete("category:promotions")
        if (CLEAR_INBOX.any { t == it || t.startsWith(it) }) return EmailIntent.ClearInbox

        return null
    }

    private fun containsWord(haystack: String, word: String): Boolean =
        Regex("(^|\\W)" + Regex.escape(word) + "($|\\W)").containsMatchIn(haystack)

    private val OPEN_APP_PREFIXES = listOf("open ", "launch ", "start ", "go to ")

    private val READ_LATEST = setOf(
        "read my latest email", "read my last email", "read my newest email",
        "read my latest emails", "read my email", "read my emails", "read my inbox",
        "read the latest email", "read last email", "read my most recent email",
    )
    private val UNREAD_COUNT = listOf(
        "how many unread", "how many new emails", "how many emails",
        "any new email", "any new emails", "do i have new email", "do i have any new email",
        "do i have new emails", "unread emails", "unread email count",
    )
    private val IMPORTANT = listOf(
        "do i have anything important", "anything important", "any important email",
        "any important emails", "what's important", "whats important", "important emails",
    )
    private val SUMMARIZE_TODAY = listOf(
        "summarize today's emails", "summarize todays emails", "summarise today's emails",
        "summarise todays emails", "summarize today's email", "summarize my day",
    )
    private val SUMMARIZE_ANY = listOf(
        "summarize my emails", "summarise my emails", "summarize my inbox", "summarise my inbox",
        "summarize my email", "summarise my email", "give me a summary of my emails",
    )
    private val MARK_ALL_READ = listOf(
        "mark all emails as read", "mark all as read", "mark everything as read",
        "mark all my emails as read", "mark all unread as read", "mark all unread emails as read",
        "mark inbox as read",
    )
    private val DELETE_PROMOS = listOf(
        "delete all promotional emails", "delete promotional emails", "delete promotions",
        "delete all promotions", "clear promotional emails", "clear promotions",
        "remove promotional emails", "delete promo emails",
    )
    private val CLEAR_INBOX = listOf(
        "clear my inbox", "clear inbox", "clear the inbox", "empty my inbox", "empty inbox",
        "archive all emails", "archive my inbox", "archive everything",
    )
}
