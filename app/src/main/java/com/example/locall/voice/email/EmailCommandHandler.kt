package com.example.locall.voice.email

import android.content.Context
import android.util.Log
import com.example.locall.voice.ActionConfirmation
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.email.cache.EmailRepository

private const val TAG = "EmailCommandHandler"

/** Safety cap on the draft refine loop so a run of mis-recognised refinements can't spin forever. */
private const val MAX_REFINE_TURNS = 6

/** Safety cap on the thread follow-up Q&A loop. */
private const val MAX_FOLLOWUP_TURNS = 6

class EmailCommandHandler(appContext: Context) {

    private val app = appContext.applicationContext
    private val repo = EmailRepository(app)

    /** Current email context + pending draft, kept across conversational turns. */
    private val session = EmailSession()

    suspend fun handle(
        context: Context,
        transcript: String,
        onStatus: (String) -> Unit,
        confirm: suspend (String) -> Boolean,
        speak: (String) -> Unit,
        /** Speak a prompt and capture a free-form spoken reply (null = nothing heard). Drives the reply refine loop. */
        listen: suspend (String) -> String? = { null },
    ): String {
        val normalized = transcript.trim().lowercase()

        var intent = EmailIntents.resolve(normalized)
        if (intent == null) {
            onStatus("Understanding…")
            LlmBridge.ensureLoaded(context, onStatus)
            // Reply/compose need field extraction with a different grammar than the general parser.
            intent = when {
                EmailIntents.looksLikeReply(normalized) -> EmailIntentParser.parseReply(context, transcript.trim())
                EmailIntents.looksLikeSend(normalized) -> EmailIntentParser.parseSend(context, transcript.trim())
                else -> EmailIntentParser.parse(context, transcript.trim())
            }
        }
        Log.i(TAG, "transcript=\"$transcript\" -> $intent")

        if (intent is EmailIntent.Unknown) {
            return "🤷 didn't catch what to do with your email"
        }

        // Summarising needs the on-device LLM; the fast-path (resolve) skips the load the parser path does.
        if (intent is EmailIntent.Summarize || intent is EmailIntent.SummarizeThread) {
            onStatus("Loading…")
            LlmBridge.ensureLoaded(context, onStatus)
        }

        onStatus("Connecting to Gmail…")
        val token = when (val r = GmailAuth.acquire(app)) {
            is GmailAuth.Result.Token -> r.accessToken
            is GmailAuth.Result.NeedsConsent ->
                return "📧 Open LocaLL and tap “Connect Gmail” to grant access first"
            is GmailAuth.Result.Error ->
                return "⚠️ couldn't connect to Gmail: ${r.message}"
        }

        return try {
            execute(context, token, intent, confirm, speak, listen)
        } catch (e: GmailApi.Unauthorized) {
            GmailAuth.invalidate()
            "⚠️ Gmail access expired — reconnect in LocaLL and try again"
        } catch (t: Throwable) {
            Log.w(TAG, "execute failed", t)
            "⚠️ email command failed: ${t.javaClass.simpleName}"
        }
    }

    private suspend fun execute(
        context: Context,
        token: String,
        intent: EmailIntent,
        confirm: suspend (String) -> Boolean,
        speak: (String) -> Unit,
        listen: suspend (String) -> String?,
    ): String {
        // Best-effort: fold in any changes since the last sync so the cache isn't stale.
        repo.refresh(token)
        return dispatch(context, token, intent, confirm, speak, listen)
    }

    private suspend fun dispatch(
        context: Context,
        token: String,
        intent: EmailIntent,
        confirm: suspend (String) -> Boolean,
        speak: (String) -> Unit,
        listen: suspend (String) -> String?,
    ): String {
        return when (intent) {

        is EmailIntent.ReadLatest -> {
            val ids = GmailApi.list(token, "in:inbox", max = intent.count.coerceIn(1, 3))
            if (ids.isEmpty()) return "📭 your inbox is empty"
            val m = repo.metadata(token, ids.first())
            val body = repo.body(token, ids.first())
            session.remember(ids.first(), EmailSummarizer.cleanFrom(m.from), m.subject, body)
            val spoken = "From ${EmailSummarizer.cleanFrom(m.from)}. " +
                "Subject: ${m.subject.ifBlank { "no subject" }}. " +
                body.take(600).ifBlank { m.snippet }
            speak(spoken)
            "📨 ${EmailSummarizer.cleanFrom(m.from)} — ${m.subject.ifBlank { "(no subject)" }}"
        }

        is EmailIntent.Summarize -> {
            if (intent.count == 1) {
                // "summarise the last email" → summarise that ONE email's actual content, not an overview.
                val ids = GmailApi.list(token, ensureInbox(intent.query), max = 1)
                if (ids.isEmpty()) { speak("You have no recent emails to summarise."); return "📭 nothing to summarise" }
                val m = repo.metadata(token, ids.first())
                val body = repo.body(token, ids.first())
                session.remember(ids.first(), EmailSummarizer.cleanFrom(m.from), m.subject, body)
                val summary = EmailSummarizer.summarizeOne(context, m, body)
                Log.i(TAG, "summary(latest from ${m.from}): $summary")
                speak(summary)
                // Return the summary itself so it's readable even if TTS is unavailable.
                return "📋 ${EmailSummarizer.cleanFrom(m.from)}: $summary"
            }
            val (messages, fromCache) = repo.listMessages(
                token, intent.query, max = intent.count, scope = EmailRepository.CacheScope.RECENT_INBOX,
            )
            if (messages.isEmpty()) { speak("You have no emails matching that."); return "📭 nothing to summarise" }
            val summary = EmailSummarizer.summarize(context, messages)
            Log.i(TAG, "summary(${messages.size} msgs): $summary")
            speak(cachePrefix(fromCache) + summary)
            "📋 ${cachePrefix(fromCache)}$summary"
        }

        is EmailIntent.SummarizeThread -> handleSummarizeThread(context, token, intent, speak, listen)

        is EmailIntent.Search -> search(token, intent, speak)

        is EmailIntent.FindImportant -> {
            val (messages, fromCache) = repo.listMessages(
                token, "is:important is:unread in:inbox", max = 15,
                scope = EmailRepository.CacheScope.IMPORTANT_UNREAD,
            )
            if (messages.isEmpty()) { speak("Nothing important right now."); return "✅ nothing important" }
            val summary = EmailSummarizer.summarize(context, messages)
            speak(cachePrefix(fromCache) + summary)
            "⭐ ${messages.size} important email${plural(messages.size)}${cacheTag(fromCache)}"
        }

        is EmailIntent.UnreadCount -> {
            val n = try {
                GmailApi.list(token, "is:unread in:inbox").size
            } catch (e: java.io.IOException) {
                if (e is GmailApi.Unauthorized) throw e
                val cached = repo.unreadCountCached()
                speak("You're offline. You had $cached unread email${plural(cached)} at last sync.")
                return "✉️ $cached unread (cached)"
            }
            val msg = if (n == 0) "You have no unread emails." else "You have $n unread email${plural(n)}."
            speak(msg)
            "✉️ $n unread"
        }

        is EmailIntent.MarkAllRead -> {
            val ids = GmailApi.list(token, intent.query)
            if (ids.isEmpty()) return "✅ nothing unread to mark"
            if (!confirm("mark ${ids.size} email${plural(ids.size)} as read?")) return "❌ cancelled"
            GmailApi.markRead(token, ids)
            repo.onMarkedRead(ids)
            "✅ marked ${ids.size} as read"
        }

        is EmailIntent.Archive -> {
            val ids = GmailApi.list(token, ensureInbox(intent.query))
            if (ids.isEmpty()) return "✅ nothing to archive"
            if (!confirm("archive ${ids.size} email${plural(ids.size)}?")) return "❌ cancelled"
            GmailApi.archive(token, ids)
            repo.onArchived(ids)
            "🗂️ archived ${ids.size} email${plural(ids.size)}"
        }

        is EmailIntent.Delete -> {
            // Gmail returns newest-first, so capping to count trashes the most recent matches.
            val ids = GmailApi.list(token, intent.query, max = intent.count)
            if (ids.isEmpty()) return "✅ nothing to delete"
            if (!confirm("move ${ids.size} email${plural(ids.size)} to Trash?")) return "❌ cancelled"
            GmailApi.trash(token, ids)
            repo.onTrashed(ids)
            "🗑️ trashed ${ids.size} email${plural(ids.size)}"
        }

        is EmailIntent.ClearInbox -> {
            val ids = GmailApi.list(token, "in:inbox")
            if (ids.isEmpty()) return "📭 inbox already clear"
            if (!confirm("archive ALL ${ids.size} email${plural(ids.size)} in your inbox?")) return "❌ cancelled"
            GmailApi.archive(token, ids)
            repo.onArchived(ids)
            "🧹 cleared ${ids.size} email${plural(ids.size)} from your inbox"
        }

        is EmailIntent.Send -> {
            if (intent.to.isBlank() || !intent.to.contains("@")) {
                speak("I didn't catch a valid email address to send it to.")
                return "⚠️ no valid recipient — try “send an email to name@example.com …”"
            }
            val subject = intent.subject.ifBlank { "(no subject)" }
            // Short readback IS the confirmation prompt (no separate speak) so the "yes/no" ask lands
            // quickly while the user is still primed. Body is previewed, not read in full.
            val preview = intent.body.take(60).trim()
            val bodyPreview = if (intent.body.length > 60) "$preview, and so on" else preview
            val prompt = "Ready to send to ${spokenAddress(intent.to)}, subject $subject, saying: $bodyPreview."
            if (!confirm(prompt)) return "❌ cancelled — not sent"
            GmailApi.send(token, intent.to, subject, intent.body)
            speak("Sent to ${intent.to}.")
            "📤 sent to ${intent.to}\nSubject: $subject\n${intent.body}"
        }

        is EmailIntent.Reply -> handleReply(context, token, intent, speak, listen)

        is EmailIntent.Unknown -> "🤷 didn't catch what to do with your email"
        }
    }

    /**
     * Context-aware reply: the LLM drafts a natural reply from the user's intended message grounded in
     * the original email + thread, then the user refines it across turns ("make it shorter", "mention
     * the delay is due to testing") and must explicitly approve before it's sent in-thread. The model is
     * a writing assistant only — it never decides to send, and never invents commitments the user didn't make.
     */
    private suspend fun handleReply(
        context: Context,
        token: String,
        intent: EmailIntent.Reply,
        speak: (String) -> Unit,
        listen: suspend (String) -> String?,
    ): String {
        // 1. Which email? Prefer the remembered current email for a generic "reply saying …"; otherwise
        //    locate it by the spoken target (and remember it for later turns).
        val focus = resolveReplyTarget(token, intent.query)
            ?: run { speak("I couldn't find an email to reply to."); return "🔍 no email matched to reply to" }

        val ctx = GmailApi.replyContext(token, focus.id)
        if (ctx.toEmail.isBlank()) {
            speak("I couldn't tell who to reply to.")
            return "⚠️ couldn't determine the reply recipient"
        }
        val who = EmailSummarizer.cleanFrom(ctx.fromRaw).ifBlank { focus.fromName }
        val subject = if (ctx.subject.startsWith("Re:", ignoreCase = true)) ctx.subject else "Re: ${ctx.subject}"

        // 2. Grounding context, gathered once and reused for every refinement (no re-fetch per turn).
        val originalBody = focus.body.ifBlank { repo.body(token, focus.id) }
        val threadBlock = loadThreadBlock(token, ctx.threadId, originalBody)
        val brief = EmailReplyWriter.ReplyBrief(who, ctx.subject, originalBody, threadBlock)

        // 3. First draft from the user's intended message.
        var draft = EmailReplyWriter.draft(context, brief, intent.body)

        // 4. Refine ↔ approve loop — mic stays open each turn. Only an explicit approval sends.
        var prompt = "Here's a reply to $who. $draft. Shall I send it, change something, or cancel?"
        var misses = 0
        repeat(MAX_REFINE_TURNS) {
            val reply = listen(prompt)
            when {
                reply.isNullOrBlank() -> {
                    misses++
                    if (misses >= 2) { speak("Okay, I won't send it."); return "📝 not sent — I didn't catch your answer\n$draft" }
                    prompt = "Sorry, I didn't catch that. Say send it, cancel, or tell me what to change."
                }
                ActionConfirmation.isApproval(reply) -> {
                    GmailApi.sendReply(
                        token, to = ctx.toEmail, subject = subject, body = draft,
                        threadId = ctx.threadId, inReplyTo = ctx.messageIdHeader, references = ctx.references,
                    )
                    speak("Replied to $who.")
                    return "↩️ replied to $who in thread\n$subject\n$draft"
                }
                ActionConfirmation.isCancellation(reply) -> {
                    speak("Okay, I won't send it.")
                    return "❌ cancelled — not sent\n$draft"
                }
                else -> {
                    misses = 0
                    draft = EmailReplyWriter.refine(context, brief, draft, reply)
                    prompt = "How about this. $draft. Send it, change it, or cancel?"
                }
            }
        }
        speak("I'll leave that as a draft for now.")
        return "📝 not sent — last draft:\n$draft"
    }

    /**
     * Summarise a whole thread (incrementally, via the repository) and then open a short conversational
     * follow-up loop so the user can drill into decisions/actions/deadlines or ask free-form questions.
     */
    private suspend fun handleSummarizeThread(
        context: Context,
        token: String,
        intent: EmailIntent.SummarizeThread,
        speak: (String) -> Unit,
        listen: suspend (String) -> String?,
    ): String {
        val threadId = resolveThreadId(token, intent.query)
            ?: run {
                speak("I don't have an email open to summarise the thread for. Read or find an email first.")
                return "📭 no thread in focus — read or find an email first"
            }

        val summary = repo.threadSummary(context, token, threadId)
        session.rememberThreadSummary(threadId, summary)
        Log.i(TAG, "thread summary($threadId): ${summary.summary}")

        speak(spokenSummary(summary))
        followUpLoop(context, token, threadId, summary, speak, listen)
        return "🧵 ${summary.summary}" + extrasDisplay(summary)
    }

    /** Which thread? Generic ask → the in-focus email's thread; otherwise locate the anchor email by query. */
    private suspend fun resolveThreadId(token: String, query: String): String? {
        if (query.isBlank()) {
            val cur = session.current ?: return null
            return GmailApi.threadIdOf(token, cur.id).ifBlank { null }
        }
        val ids = GmailApi.list(token, ensureInbox(query), max = 1)
        val id = ids.firstOrNull() ?: return null
        val m = repo.metadata(token, id)
        val body = repo.body(token, id)
        session.remember(id, EmailSummarizer.cleanFrom(m.from), m.subject, body)
        return GmailApi.threadIdOf(token, id).ifBlank { null }
    }

    /** Spoken briefing: the summary plus a short cue about how many items are waiting to drill into. */
    private fun spokenSummary(s: ThreadSummarizer.ThreadSummary): String = buildString {
        append(s.summary)
        val cues = buildList {
            if (s.decisions.isNotEmpty()) add("${s.decisions.size} decision${plural(s.decisions.size)}")
            if (s.actions.isNotEmpty()) add("${s.actions.size} action item${plural(s.actions.size)}")
            if (s.deadlines.isNotEmpty()) add("${s.deadlines.size} deadline${plural(s.deadlines.size)}")
        }
        if (cues.isNotEmpty()) append(" I also noted ").append(joinSpoken(cues)).append(".")
    }

    /** The conversational follow-up loop. Mic stays open each turn; "done"/cancel ends it. */
    private suspend fun followUpLoop(
        context: Context,
        token: String,
        threadId: String,
        summary: ThreadSummarizer.ThreadSummary,
        speak: (String) -> Unit,
        listen: suspend (String) -> String?,
    ) {
        var prompt = "Ask me anything about this thread — the decisions, action items, or deadlines — or say done."
        var misses = 0
        repeat(MAX_FOLLOWUP_TURNS) {
            val q = listen(prompt)
            when {
                q.isNullOrBlank() -> {
                    misses++
                    if (misses >= 2) return
                    prompt = "Sorry, I didn't catch that. Ask about the thread, or say done."
                }
                isDone(q) -> { speak("Okay."); return }
                else -> {
                    misses = 0
                    // Deterministic answers for the three extract categories — no model call needed.
                    val quick = quickAnswer(q, summary)
                    val answer = quick ?: ThreadConversation.answer(context, summary, q.trim()) {
                        GmailApi.threadMessages(token, threadId).joinToString("\n\n") {
                            "${EmailSummarizer.cleanFrom(it.from)}: ${it.body.take(1200)}"
                        }
                    }
                    speak(answer)
                    prompt = "Anything else, or say done?"
                }
            }
        }
    }

    /** Answer a "what are the action items / decisions / deadlines" ask straight from the stored lists. */
    private fun quickAnswer(question: String, s: ThreadSummarizer.ThreadSummary): String? {
        val t = question.lowercase()
        return when {
            t.contains("action") || t.contains("to-do") || t.contains("to do") || t.contains("task") ->
                if (s.actions.isEmpty()) "There are no action items noted in this thread."
                else "The action items are: ${s.actions.joinToString("; ")}."
            t.contains("decision") || t.contains("decide") || t.contains("agreed") ->
                if (s.decisions.isEmpty()) "No clear decisions were noted in this thread."
                else "The decisions were: ${s.decisions.joinToString("; ")}."
            t.contains("deadline") || t.contains("due date") || t.contains("due by") || t.contains("when is it due") ->
                if (s.deadlines.isEmpty()) "No deadlines were noted in this thread."
                else "The deadlines are: ${s.deadlines.joinToString("; ")}."
            else -> null
        }
    }

    private fun isDone(reply: String): Boolean {
        if (ActionConfirmation.isCancellation(reply)) return true
        return reply.trim().lowercase() in setOf(
            "done", "no", "nope", "nothing", "no thanks", "that's all", "thats all",
            "i'm done", "im done", "stop", "that's it", "thats it", "that is all",
        )
    }

    private fun extrasDisplay(s: ThreadSummarizer.ThreadSummary): String = buildString {
        if (s.decisions.isNotEmpty()) append("\n\nDecisions:\n").append(s.decisions.joinToString("\n") { "• $it" })
        if (s.actions.isNotEmpty()) append("\n\nAction items:\n").append(s.actions.joinToString("\n") { "• $it" })
        if (s.deadlines.isNotEmpty()) append("\n\nDeadlines:\n").append(s.deadlines.joinToString("\n") { "• $it" })
    }

    /** "a, b and c" for a spoken list. */
    private fun joinSpoken(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** Resolve the email to reply to: remembered current email for a generic ask, else a query lookup. */
    private suspend fun resolveReplyTarget(token: String, query: String): EmailSession.Current? {
        val current = session.current
        if (isGenericReplyTarget(query) && current != null) return current
        val ids = GmailApi.list(token, ensureInbox(query), max = 1)
        if (ids.isNotEmpty()) {
            val id = ids.first()
            current?.takeIf { it.id == id }?.let { return it } // same email already in focus — keep its body
            val m = repo.metadata(token, id)
            val body = repo.body(token, id)
            session.remember(id, EmailSummarizer.cleanFrom(m.from), m.subject, body)
            return session.current
        }
        return current // nothing matched — fall back to whatever is in focus, if anything
    }

    /** A reply with no specific target ("reply saying …") → use the remembered current email. */
    private fun isGenericReplyTarget(query: String): Boolean =
        query.trim().lowercase() in setOf("", "in:inbox", "label:inbox")

    /** Fetch + cache (per thread) a compact block of earlier thread messages, minus the focus email itself. */
    private suspend fun loadThreadBlock(token: String, threadId: String, originalBody: String): String {
        if (threadId.isBlank()) return ""
        session.threadBlockFor(threadId).let { if (it.isNotBlank()) return it }
        val msgs = try {
            GmailApi.thread(token, threadId)
        } catch (t: Throwable) {
            Log.w(TAG, "thread fetch failed", t); emptyList()
        }
        val bodyKey = originalBody.take(80)
        val block = msgs
            .filterNot { bodyKey.isNotBlank() && it.text.take(80) == bodyKey }
            .joinToString("\n\n") { "${EmailSummarizer.cleanFrom(it.from)}: ${it.text.take(400)}" }
        session.cacheThread(threadId, block)
        return block
    }

    /** Find ONE specific email by content and read the best match aloud. */
    private suspend fun search(
        token: String,
        intent: EmailIntent.Search,
        speak: (String) -> Unit,
    ): String {
        val ids = GmailApi.list(token, intent.query, max = 10)
        if (ids.isEmpty()) {
            speak("I couldn't find any email matching that.")
            return "🔍 no match for “${intent.query}”"
        }
        // Rank the top few candidates by how well subject/snippet match the topic words.
        val candidates = repo.metadataFor(token, ids.take(5))
        val best = pickBest(candidates, topicWords(intent.query)) ?: candidates.first()
        val body = repo.body(token, best.id)
        session.remember(best.id, EmailSummarizer.cleanFrom(best.from), best.subject, body)
        val more = if (ids.size > 1) " I found ${ids.size} possible matches; this looks like the one." else ""
        speak(
            "Found an email from ${EmailSummarizer.cleanFrom(best.from)}. " +
                "Subject: ${best.subject.ifBlank { "no subject" }}. " +
                body.take(600).ifBlank { best.snippet } + more,
        )
        return "🔍 ${EmailSummarizer.cleanFrom(best.from)} — ${best.subject.ifBlank { "(no subject)" }}"
    }

    /** Content words from a Gmail query, dropping operators (from:, newer_than:, etc.) and quotes. */
    private fun topicWords(query: String): List<String> =
        query.split(Regex("\\s+"))
            .filter { it.isNotBlank() && !it.contains(':') }
            .map { it.trim('"').lowercase() }
            .filter { it.length > 2 }

    /** Pick the candidate whose subject+snippet contains the most topic words; tie-break by recency. */
    private fun pickBest(
        candidates: List<GmailApi.Message>,
        words: List<String>,
    ): GmailApi.Message? {
        if (candidates.isEmpty() || words.isEmpty()) return candidates.firstOrNull()
        return candidates.maxByOrNull { m ->
            val hay = (m.subject + " " + m.snippet).lowercase()
            val score = words.count { hay.contains(it) }
            score * 1_000_000_000_000L + m.internalDateMs / 1000 // score first, recency as tie-break
        }
    }

    private fun cachePrefix(fromCache: Boolean) = if (fromCache) "You're offline, so this is from your last sync. " else ""

    private fun cacheTag(fromCache: Boolean) = if (fromCache) " (cached)" else ""

    /** Read an email address aloud clearly: "a@b.com" → "a at b dot com". */
    private fun spokenAddress(addr: String): String =
        addr.replace("@", " at ").replace(".", " dot ")

    private fun ensureInbox(query: String): String =
        if (query.contains("in:inbox") || query.contains("label:inbox")) query else "$query in:inbox"

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
