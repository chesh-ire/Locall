package com.example.locall.voice.email

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.email.cache.EmailRepository

private const val TAG = "EmailCommandHandler"

class EmailCommandHandler(appContext: Context) {

    private val app = appContext.applicationContext
    private val repo = EmailRepository(app)

    suspend fun handle(
        context: Context,
        transcript: String,
        onStatus: (String) -> Unit,
        confirm: suspend (String) -> Boolean,
        speak: (String) -> Unit,
    ): String {
        val normalized = transcript.trim().lowercase()

        var intent = EmailIntents.resolve(normalized)
        if (intent == null) {
            onStatus("Understanding…")
            LlmBridge.ensureLoaded(context, onStatus)
            intent = EmailIntentParser.parse(context, transcript.trim())
        }
        Log.i(TAG, "transcript=\"$transcript\" -> $intent")

        if (intent is EmailIntent.Unknown) {
            return "🤷 didn't catch what to do with your email"
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
            execute(context, token, intent, confirm, speak)
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
    ): String {
        // Best-effort: fold in any changes since the last sync so the cache isn't stale.
        repo.refresh(token)
        return dispatch(context, token, intent, confirm, speak)
    }

    private suspend fun dispatch(
        context: Context,
        token: String,
        intent: EmailIntent,
        confirm: suspend (String) -> Boolean,
        speak: (String) -> Unit,
    ): String {
        return when (intent) {

        is EmailIntent.ReadLatest -> {
            val ids = GmailApi.list(token, "in:inbox", max = intent.count.coerceIn(1, 3))
            if (ids.isEmpty()) return "📭 your inbox is empty"
            val m = repo.metadata(token, ids.first())
            val body = repo.body(token, ids.first())
            val spoken = "From ${EmailSummarizer.cleanFrom(m.from)}. " +
                "Subject: ${m.subject.ifBlank { "no subject" }}. " +
                body.take(600).ifBlank { m.snippet }
            speak(spoken)
            "📨 ${EmailSummarizer.cleanFrom(m.from)} — ${m.subject.ifBlank { "(no subject)" }}"
        }

        is EmailIntent.Summarize -> {
            val (messages, fromCache) = repo.listMessages(
                token, intent.query, max = 20, scope = EmailRepository.CacheScope.RECENT_INBOX,
            )
            if (messages.isEmpty()) { speak("You have no emails matching that."); return "📭 nothing to summarise" }
            val summary = EmailSummarizer.summarize(context, messages)
            speak(cachePrefix(fromCache) + summary)
            "📋 summarised ${messages.size} email${plural(messages.size)}${cacheTag(fromCache)}"
        }

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
            val ids = GmailApi.list(token, intent.query)
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

        is EmailIntent.Unknown -> "🤷 didn't catch what to do with your email"
        }
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

    private fun ensureInbox(query: String): String =
        if (query.contains("in:inbox") || query.contains("label:inbox")) query else "$query in:inbox"

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
