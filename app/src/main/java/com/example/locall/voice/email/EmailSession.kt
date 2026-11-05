package com.example.locall.voice.email

/**
 * The "current email context" the assistant keeps between conversational turns so the user can read an
 * email and then — in the same breath or a later activation — say "reply saying …" without naming the
 * email again, and refine the draft across turns. Held on the [EmailCommandHandler] instance (which is a
 * process-lived singleton in CommandPipeline), so it survives across wake-word activations.
 *
 * It stores only lightweight identifiers plus the already-fetched sender/subject/body and a formatted
 * thread block — never the running conversation — so refinements reuse what was fetched once instead of
 * re-hitting Gmail or re-feeding the whole dialogue to the model.
 */
class EmailSession {

    /** The single email currently "in focus" (last read/searched/summarised one). */
    data class Current(
        val id: String,
        val fromName: String,
        val subject: String,
        val body: String,
    )

    @Volatile var current: Current? = null
        private set

    /** Cached thread context keyed by threadId so a multi-turn refine never re-fetches it. */
    @Volatile var threadId: String? = null
        private set
    @Volatile var threadBlock: String = ""
        private set

    /** Remember the email the user just read/found so a follow-up "reply …" knows what it refers to. */
    fun remember(id: String, fromName: String, subject: String, body: String) {
        // A new focus email invalidates any cached thread for the previous one (compare BEFORE overwriting).
        if (current?.id != id) { threadId = null; threadBlock = "" }
        current = Current(id, fromName, subject, body)
    }

    fun cacheThread(threadId: String, block: String) {
        this.threadId = threadId
        this.threadBlock = block
    }

    fun threadBlockFor(threadId: String): String =
        if (this.threadId == threadId) threadBlock else ""

    /** The thread most recently summarised, kept so conversational follow-ups reuse the compact summary. */
    @Volatile var summarizedThreadId: String? = null
        private set
    @Volatile var threadSummary: ThreadSummarizer.ThreadSummary? = null
        private set

    fun rememberThreadSummary(threadId: String, summary: ThreadSummarizer.ThreadSummary) {
        summarizedThreadId = threadId
        threadSummary = summary
    }
}
