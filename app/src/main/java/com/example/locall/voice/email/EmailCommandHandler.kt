package com.example.locall.voice.email

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge

private const val TAG = "EmailCommandHandler"

class EmailCommandHandler(appContext: Context) {

    private val app = appContext.applicationContext

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
    ): String = when (intent) {

        is EmailIntent.ReadLatest -> {
            val ids = GmailApi.list(token, "in:inbox", max = intent.count.coerceIn(1, 3))
            if (ids.isEmpty()) return "📭 your inbox is empty"
            val m = GmailApi.metadata(token, ids.first())
            val body = GmailApi.plainText(token, ids.first())
            val spoken = "From ${EmailSummarizer.cleanFrom(m.from)}. " +
                "Subject: ${m.subject.ifBlank { "no subject" }}. " +
                body.take(600).ifBlank { m.snippet }
            speak(spoken)
            "📨 ${EmailSummarizer.cleanFrom(m.from)} — ${m.subject.ifBlank { "(no subject)" }}"
        }

        is EmailIntent.Summarize -> {
            val ids = GmailApi.list(token, intent.query, max = 20)
            if (ids.isEmpty()) { speak("You have no emails matching that."); return "📭 nothing to summarise" }
            val messages = ids.map { GmailApi.metadata(token, it) }
            val summary = EmailSummarizer.summarize(context, messages)
            speak(summary)
            "📋 summarised ${messages.size} email${plural(messages.size)}"
        }

        is EmailIntent.FindImportant -> {
            val ids = GmailApi.list(token, "is:important is:unread in:inbox", max = 15)
            if (ids.isEmpty()) { speak("Nothing important right now."); return "✅ nothing important" }
            val messages = ids.map { GmailApi.metadata(token, it) }
            val summary = EmailSummarizer.summarize(context, messages)
            speak(summary)
            "⭐ ${messages.size} important email${plural(messages.size)}"
        }

        is EmailIntent.UnreadCount -> {
            val n = GmailApi.list(token, "is:unread in:inbox").size
            val msg = if (n == 0) "You have no unread emails." else "You have $n unread email${plural(n)}."
            speak(msg)
            "✉️ $n unread"
        }

        is EmailIntent.MarkAllRead -> {
            val ids = GmailApi.list(token, intent.query)
            if (ids.isEmpty()) return "✅ nothing unread to mark"
            if (!confirm("mark ${ids.size} email${plural(ids.size)} as read?")) return "❌ cancelled"
            GmailApi.markRead(token, ids)
            "✅ marked ${ids.size} as read"
        }

        is EmailIntent.Archive -> {
            val ids = GmailApi.list(token, ensureInbox(intent.query))
            if (ids.isEmpty()) return "✅ nothing to archive"
            if (!confirm("archive ${ids.size} email${plural(ids.size)}?")) return "❌ cancelled"
            GmailApi.archive(token, ids)
            "🗂️ archived ${ids.size} email${plural(ids.size)}"
        }

        is EmailIntent.Delete -> {
            val ids = GmailApi.list(token, intent.query)
            if (ids.isEmpty()) return "✅ nothing to delete"
            if (!confirm("move ${ids.size} email${plural(ids.size)} to Trash?")) return "❌ cancelled"
            GmailApi.trash(token, ids)
            "🗑️ trashed ${ids.size} email${plural(ids.size)}"
        }

        is EmailIntent.ClearInbox -> {
            val ids = GmailApi.list(token, "in:inbox")
            if (ids.isEmpty()) return "📭 inbox already clear"
            if (!confirm("archive ALL ${ids.size} email${plural(ids.size)} in your inbox?")) return "❌ cancelled"
            GmailApi.archive(token, ids)
            "🧹 cleared ${ids.size} email${plural(ids.size)} from your inbox"
        }

        is EmailIntent.Unknown -> "🤷 didn't catch what to do with your email"
    }

    private fun ensureInbox(query: String): String =
        if (query.contains("in:inbox") || query.contains("label:inbox")) query else "$query in:inbox"

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
