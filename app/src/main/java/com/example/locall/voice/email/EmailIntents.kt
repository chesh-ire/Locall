package com.example.locall.voice.email

object EmailIntents {

    private val KEYWORDS = listOf("email", "emails", "e-mail", "e-mails", "inbox", "mailbox", "gmail")

    fun matches(normalized: String): Boolean {
        val t = normalized.trim()
        if (OPEN_APP_PREFIXES.any { t.startsWith(it) }) return false
        // Thread/conversation phrasing has no "email" keyword but is still ours to handle.
        if (looksLikeThreadSummary(t)) return true
        return KEYWORDS.any { kw -> containsWord(t, kw) }
    }

    /** True for "summarise this thread / catch me up on this conversation" style asks. */
    private fun looksLikeThreadSummary(t: String): Boolean =
        THREAD_SUMMARY.any { t == it || t.startsWith(it) }

    fun resolve(normalized: String): EmailIntent? {
        val t = normalized.trim()

        if (looksLikeThreadSummary(t)) return EmailIntent.SummarizeThread()

        if (t in READ_LATEST) return EmailIntent.ReadLatest(1)
        if (UNREAD_COUNT.any { t.startsWith(it) || t == it }) return EmailIntent.UnreadCount
        if (IMPORTANT.any { t.startsWith(it) }) return EmailIntent.FindImportant
        if (SUMMARIZE_LATEST.any { t.startsWith(it) }) return EmailIntent.Summarize("in:inbox", count = 1)
        if (SUMMARIZE_TODAY.any { t.startsWith(it) }) return EmailIntent.Summarize("newer_than:1d in:inbox")
        if (SUMMARIZE_ANY.any { t.startsWith(it) }) return EmailIntent.Summarize("in:inbox")

        if (MARK_ALL_READ.any { t.startsWith(it) }) return EmailIntent.MarkAllRead()
        // Spam/Trash are hidden from normal Gmail search — any delete here MUST carry in:spam/in:trash.
        // Match on the verb + folder word anywhere (phrasings vary wildly) rather than exact prefixes.
        if (isDeleteVerb(t) && mentionsSpam(t)) {
            return EmailIntent.Delete("in:spam", count = if (isSingular(t)) 1 else Int.MAX_VALUE)
        }
        if (isDeleteVerb(t) && mentionsTrash(t)) {
            return EmailIntent.Delete("in:trash", count = if (isSingular(t)) 1 else Int.MAX_VALUE)
        }
        if (DELETE_PROMOS.any { t.startsWith(it) }) return EmailIntent.Delete("category:promotions")
        if (CLEAR_INBOX.any { t == it || t.startsWith(it) }) return EmailIntent.ClearInbox

        return null
    }

    /**
     * True when the request is to REPLY to an existing email (field extraction via the LLM).
     * Runs only after the email keyword gate in [matches], so "reply/respond" is enough to disambiguate.
     */
    fun looksLikeReply(normalized: String): Boolean {
        val t = normalized.trim()
        if (OPEN_APP_PREFIXES.any { t.startsWith(it) }) return false
        return t.startsWith("reply") || t.startsWith("respond") || t.contains("reply to")
    }

    /**
     * True when the request is about composing/sending a NEW email (needs field extraction via the
     * LLM, so this only gates — it doesn't build the intent). Requires a compose verb + a recipient cue.
     */
    fun looksLikeSend(normalized: String): Boolean {
        val t = normalized.trim()
        if (OPEN_APP_PREFIXES.any { t.startsWith(it) }) return false
        val composeVerb = t.contains("send") || t.contains("compose") ||
            (t.contains("write") && (t.contains("email") || t.contains("mail")))
        val hasRecipient = t.contains(" to ") || t.contains("@")
        return composeVerb && hasRecipient
    }

    private fun containsWord(haystack: String, word: String): Boolean =
        Regex("(^|\\W)" + Regex.escape(word) + "($|\\W)").containsMatchIn(haystack)

    private val DELETE_VERBS = listOf("delete", "clear", "empty", "remove", "trash", "get rid", "throw")
    private fun isDeleteVerb(t: String) = DELETE_VERBS.any { t.contains(it) }
    private fun mentionsSpam(t: String) = t.contains("spam") || t.contains("junk")
    private fun mentionsTrash(t: String) =
        t.contains("trash") || t.contains("bin") || t.contains("deleted email") || t.contains("deleted item")
    // "the last / latest / most recent / a spam email" → a single message, not the whole folder.
    private val SINGULAR = listOf("the last", "last ", "latest", "most recent", " a spam", " one ")
    private fun isSingular(t: String) =
        !t.contains("all") && SINGULAR.any { t.contains(it) }

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
    private val SUMMARIZE_LATEST = listOf(
        "summarize the last email", "summarise the last email", "summarize my last email",
        "summarise my last email", "summarize the latest email", "summarise the latest email",
        "summarize my latest email", "summarise my latest email", "summarize the newest email",
        "summarise the newest email", "summarize the most recent email", "summarise the most recent email",
        "summarize last email", "summarise last email", "summarize latest email", "summarise latest email",
    )
    private val THREAD_SUMMARY = listOf(
        "summarize this thread", "summarise this thread", "summarize the thread", "summarise the thread",
        "summarize this conversation", "summarise this conversation", "summarize the conversation",
        "summarise the conversation", "summarize this email thread", "summarise this email thread",
        "catch me up on this thread", "catch me up on this conversation", "catch me up on this email",
        "catch me up", "recap this thread", "recap this conversation", "recap the thread",
        "what's this thread about", "whats this thread about", "what is this thread about",
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
