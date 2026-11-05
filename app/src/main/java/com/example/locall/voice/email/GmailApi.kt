package com.example.locall.voice.email

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "GmailApi"

object GmailApi {

    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
    const val MAX_IDS = 1000

    class GmailException(message: String) : IOException(message)
    class Unauthorized : IOException("gmail 401 (token expired or revoked)")

    data class Message(
        val id: String,
        val from: String,
        val subject: String,
        val snippet: String,
        val unread: Boolean,
        val internalDateMs: Long,
        val labelIds: List<String> = emptyList(),
        val historyId: Long = 0L,
    )

    /** A single change from the incremental history feed (users.history.list). */
    sealed interface HistoryEvent {
        val id: String
        data class Added(override val id: String, val labelIds: List<String>) : HistoryEvent
        data class Deleted(override val id: String) : HistoryEvent
        data class LabelsAdded(override val id: String, val labelIds: List<String>) : HistoryEvent
        data class LabelsRemoved(override val id: String, val labelIds: List<String>) : HistoryEvent
    }

    /** Result of a history poll. [tooOld] means the start checkpoint expired → caller must full-resync. */
    data class HistoryPage(val events: List<HistoryEvent>, val newHistoryId: Long, val tooOld: Boolean)

    suspend fun list(token: String, query: String, max: Int = MAX_IDS): List<String> =
        withContext(Dispatchers.IO) {
            val ids = ArrayList<String>()
            var pageToken: String? = null
            val cap = max.coerceAtMost(MAX_IDS)
            do {
                val url = buildString {
                    append("$BASE/messages?maxResults=100")
                    append("&q=").append(enc(query))
                    pageToken?.let { append("&pageToken=").append(enc(it)) }
                }
                val obj = getJson(token, url)
                obj.optJSONArray("messages")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        ids.add(arr.getJSONObject(i).getString("id"))
                        if (ids.size >= cap) break
                    }
                }
                pageToken = if (ids.size >= cap) null else obj.optString("nextPageToken").ifEmpty { null }
            } while (pageToken != null)
            if (ids.size >= cap && pageToken != null) {
                Log.w(TAG, "list(\"$query\"): hit cap of $cap — more messages exist but were not fetched")
            }
            ids
        }

    suspend fun metadata(token: String, id: String): Message = withContext(Dispatchers.IO) {
        val url = "$BASE/messages/$id?format=metadata" +
            "&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date"
        val o = getJson(token, url)
        val headers = o.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
        fun header(name: String): String {
            for (i in 0 until headers.length()) {
                val h = headers.getJSONObject(i)
                if (h.optString("name").equals(name, ignoreCase = true)) return h.optString("value")
            }
            return ""
        }
        val labelIds = labelList(o.optJSONArray("labelIds"))
        Message(
            id = id,
            from = header("From"),
            subject = header("Subject"),
            snippet = decodeEntities(o.optString("snippet")),
            unread = labelIds.contains("UNREAD"),
            internalDateMs = o.optString("internalDate").toLongOrNull() ?: 0L,
            labelIds = labelIds,
            historyId = o.optString("historyId").toLongOrNull() ?: 0L,
        )
    }

    /** Current mailbox historyId — used to checkpoint a baseline for incremental sync. */
    suspend fun profileHistoryId(token: String): Long = withContext(Dispatchers.IO) {
        getJson(token, "$BASE/profile").optString("historyId").toLongOrNull() ?: 0L
    }

    /**
     * Incremental sync: fetch all changes since [startHistoryId] via users.history.list.
     * Returns [HistoryPage.tooOld] = true on 404 (checkpoint expired) so the caller can resync.
     */
    suspend fun history(token: String, startHistoryId: Long): HistoryPage = withContext(Dispatchers.IO) {
        val events = ArrayList<HistoryEvent>()
        var pageToken: String? = null
        var newHistoryId = startHistoryId
        do {
            val url = buildString {
                append("$BASE/history?startHistoryId=").append(startHistoryId)
                append("&historyTypes=messageAdded&historyTypes=messageDeleted")
                append("&historyTypes=labelAdded&historyTypes=labelRemoved")
                pageToken?.let { append("&pageToken=").append(enc(it)) }
            }
            val (code, body) = request("GET", url, token, null)
            if (code == 401) throw Unauthorized()
            if (code == 404) return@withContext HistoryPage(emptyList(), startHistoryId, tooOld = true)
            if (code !in 200..299) throw GmailException("GET $url -> $code: $body")
            val obj = JSONObject(body)
            obj.optString("historyId").toLongOrNull()?.let { newHistoryId = it }
            obj.optJSONArray("history")?.let { hist ->
                for (i in 0 until hist.length()) parseHistoryRecord(hist.getJSONObject(i), events)
            }
            pageToken = obj.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        HistoryPage(events, newHistoryId, tooOld = false)
    }

    private fun parseHistoryRecord(rec: JSONObject, out: MutableList<HistoryEvent>) {
        rec.optJSONArray("messagesAdded")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i).optJSONObject("message") ?: continue
                out.add(HistoryEvent.Added(m.getString("id"), labelList(m.optJSONArray("labelIds"))))
            }
        }
        rec.optJSONArray("messagesDeleted")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i).optJSONObject("message") ?: continue
                out.add(HistoryEvent.Deleted(m.getString("id")))
            }
        }
        rec.optJSONArray("labelsAdded")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val id = e.optJSONObject("message")?.optString("id")?.ifEmpty { null } ?: continue
                out.add(HistoryEvent.LabelsAdded(id, labelList(e.optJSONArray("labelIds"))))
            }
        }
        rec.optJSONArray("labelsRemoved")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val id = e.optJSONObject("message")?.optString("id")?.ifEmpty { null } ?: continue
                out.add(HistoryEvent.LabelsRemoved(id, labelList(e.optJSONArray("labelIds"))))
            }
        }
    }

    private fun labelList(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }

    suspend fun plainText(token: String, id: String): String = withContext(Dispatchers.IO) {
        val o = getJson(token, "$BASE/messages/$id?format=full")
        val payload = o.optJSONObject("payload")
            ?: return@withContext cleanReplyHistory(decodeEntities(o.optString("snippet")))
        val body = extractText(payload).ifBlank { decodeEntities(o.optString("snippet")) }
        cleanReplyHistory(body).trim().ifBlank { decodeEntities(o.optString("snippet")).trim() }
    }

    suspend fun batchModify(
        token: String,
        ids: List<String>,
        addLabelIds: List<String> = emptyList(),
        removeLabelIds: List<String> = emptyList(),
    ): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val body = JSONObject().apply {
            put("ids", JSONArray(ids))
            if (addLabelIds.isNotEmpty()) put("addLabelIds", JSONArray(addLabelIds))
            if (removeLabelIds.isNotEmpty()) put("removeLabelIds", JSONArray(removeLabelIds))
        }
        postNoContent(token, "$BASE/messages/batchModify", body)
        ids.size
    }

    suspend fun archive(token: String, ids: List<String>) =
        batchModify(token, ids, removeLabelIds = listOf("INBOX"))

    suspend fun markRead(token: String, ids: List<String>) =
        batchModify(token, ids, removeLabelIds = listOf("UNREAD"))

    // Also drops SPAM so trashing a spam message actually moves it out of Spam (SPAM and TRASH are
    // mutually exclusive); removing a label that isn't present is a harmless no-op for normal mail.
    suspend fun trash(token: String, ids: List<String>) =
        batchModify(token, ids, addLabelIds = listOf("TRASH"), removeLabelIds = listOf("INBOX", "UNREAD", "SPAM"))


    /**
     * Compose and send a plain-text email via users.messages.send.
     * From is filled in by Gmail from the authenticated account. Covered by the gmail.modify scope.
     */
    suspend fun send(token: String, to: String, subject: String, body: String): Unit =
        withContext(Dispatchers.IO) {
            val mime = buildString {
                append("To: ").append(to).append("\r\n")
                append("Subject: ").append(subject).append("\r\n")
                append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
                append("MIME-Version: 1.0\r\n")
                append("\r\n")
                append(body)
            }
            val raw = Base64.encodeToString(
                mime.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            postNoContent(token, "$BASE/messages/send", JSONObject().put("raw", raw))
        }

    /** One earlier message in a thread, used to give the reply writer conversation context. */
    data class ThreadMessage(val from: String, val text: String)

    /**
     * Fetch up to [max] most-recent messages of a thread (oldest→newest among those returned), each with
     * its sender and cleaned plain-text body. Used to ground a reply in the prior conversation.
     */
    suspend fun thread(token: String, threadId: String, max: Int = 4): List<ThreadMessage> =
        withContext(Dispatchers.IO) {
            val o = getJson(token, "$BASE/threads/$threadId?format=full")
            val msgs = o.optJSONArray("messages") ?: return@withContext emptyList()
            val out = ArrayList<ThreadMessage>(msgs.length())
            for (i in 0 until msgs.length()) {
                val m = msgs.getJSONObject(i)
                val payload = m.optJSONObject("payload")
                val headers = payload?.optJSONArray("headers") ?: JSONArray()
                var from = ""
                for (h in 0 until headers.length()) {
                    val hdr = headers.getJSONObject(h)
                    if (hdr.optString("name").equals("From", ignoreCase = true)) { from = hdr.optString("value"); break }
                }
                val body = payload?.let { cleanReplyHistory(extractText(it)) }
                    .orEmpty().ifBlank { decodeEntities(m.optString("snippet")) }.trim()
                if (body.isNotBlank()) out.add(ThreadMessage(from, body))
            }
            // Keep only the most recent [max] (they arrive oldest→newest from Gmail).
            if (out.size > max) out.subList(out.size - max, out.size).toList() else out
        }

    /** One thread message with the identity needed for chronological ordering and incremental summarisation. */
    data class ThreadFullMessage(
        val id: String,
        val from: String,
        val internalDateMs: Long,
        /** Cleaned plain-text body (quoted reply-history and signatures stripped); never blank. */
        val body: String,
    )

    /**
     * Fetch ALL messages of a thread (oldest→newest), each with its id, sender, date and cleaned body.
     * Unlike [thread] this applies no cap (the summariser chunks instead) and exposes ids/dates so the
     * caller can sort chronologically and diff which messages are new. Blank-bodied messages are dropped.
     */
    suspend fun threadMessages(token: String, threadId: String): List<ThreadFullMessage> =
        withContext(Dispatchers.IO) {
            val o = getJson(token, "$BASE/threads/$threadId?format=full")
            val msgs = o.optJSONArray("messages") ?: return@withContext emptyList()
            val out = ArrayList<ThreadFullMessage>(msgs.length())
            for (i in 0 until msgs.length()) {
                val m = msgs.getJSONObject(i)
                val payload = m.optJSONObject("payload")
                val headers = payload?.optJSONArray("headers") ?: JSONArray()
                var from = ""
                for (h in 0 until headers.length()) {
                    val hdr = headers.getJSONObject(h)
                    if (hdr.optString("name").equals("From", ignoreCase = true)) { from = hdr.optString("value"); break }
                }
                val body = payload?.let { cleanReplyHistory(extractText(it)) }
                    .orEmpty().ifBlank { decodeEntities(m.optString("snippet")) }.trim()
                if (body.isBlank()) continue
                out.add(
                    ThreadFullMessage(
                        id = m.optString("id"),
                        from = from,
                        internalDateMs = m.optString("internalDate").toLongOrNull() ?: 0L,
                        body = body,
                    ),
                )
            }
            // Gmail returns oldest→newest, but sort defensively so chronological order is guaranteed.
            out.sortedBy { it.internalDateMs }
        }

    /** The thread a message belongs to (one metadata call). */
    suspend fun threadIdOf(token: String, messageId: String): String = withContext(Dispatchers.IO) {
        getJson(token, "$BASE/messages/$messageId?format=minimal").optString("threadId")
    }

    /** Everything needed to reply in-thread to message [id]: headers + the address to reply to. */
    data class ReplyContext(
        val threadId: String,
        val messageIdHeader: String,
        val references: String,
        val subject: String,
        val fromRaw: String,
        val toEmail: String,
    )

    suspend fun replyContext(token: String, id: String): ReplyContext = withContext(Dispatchers.IO) {
        val url = "$BASE/messages/$id?format=metadata" +
            "&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Message-ID&metadataHeaders=References"
        val o = getJson(token, url)
        val headers = o.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
        fun header(name: String): String {
            for (i in 0 until headers.length()) {
                val h = headers.getJSONObject(i)
                if (h.optString("name").equals(name, ignoreCase = true)) return h.optString("value")
            }
            return ""
        }
        val from = header("From")
        ReplyContext(
            threadId = o.optString("threadId"),
            messageIdHeader = header("Message-ID"),
            references = header("References"),
            subject = header("Subject"),
            fromRaw = from,
            toEmail = extractEmail(from),
        )
    }

    /** Reply within an existing thread: sets Re: subject, In-Reply-To/References, and threadId. */
    suspend fun sendReply(
        token: String,
        to: String,
        subject: String,
        body: String,
        threadId: String,
        inReplyTo: String,
        references: String,
    ): Unit = withContext(Dispatchers.IO) {
        // References should chain the prior References plus the message being replied to.
        val refs = listOf(references, inReplyTo).filter { it.isNotBlank() }.joinToString(" ")
        val mime = buildString {
            append("To: ").append(to).append("\r\n")
            append("Subject: ").append(subject).append("\r\n")
            if (inReplyTo.isNotBlank()) append("In-Reply-To: ").append(inReplyTo).append("\r\n")
            if (refs.isNotBlank()) append("References: ").append(refs).append("\r\n")
            append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
            append("MIME-Version: 1.0\r\n")
            append("\r\n")
            append(body)
        }
        val raw = Base64.encodeToString(
            mime.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        postNoContent(token, "$BASE/messages/send", JSONObject().put("raw", raw).put("threadId", threadId))
    }

    /** Pull the bare address out of a From header like `Name <a@b.com>`, or the whole thing if already bare. */
    private fun extractEmail(from: String): String {
        Regex("<([^>]+)>").find(from)?.let { return it.groupValues[1].trim() }
        return from.trim().takeIf { it.contains("@") } ?: ""
    }

    private fun getJson(token: String, url: String): JSONObject {
        val (code, body) = request("GET", url, token, null)
        if (code == 401) throw Unauthorized()
        if (code !in 200..299) throw GmailException("GET $url -> $code: $body")
        return JSONObject(body)
    }

    private fun postNoContent(token: String, url: String, json: JSONObject) {
        val (code, body) = request("POST", url, token, json.toString())
        if (code == 401) throw Unauthorized()
        if (code !in 200..299) throw GmailException("POST $url -> $code: $body")
    }

    private fun request(method: String, url: String, token: String, jsonBody: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            jsonBody?.let { conn.outputStream.use { os -> os.write(it.toByteArray(Charsets.UTF_8)) } }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun extractText(payload: JSONObject): String {
        val mime = payload.optString("mimeType")
        if (mime == "text/plain") return decodeBody(payload)
        val parts = payload.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val t = extractText(parts.getJSONObject(i))
                if (t.isNotBlank()) return t
            }
        }
        if (mime == "text/html") return stripHtml(decodeBody(payload))
        return ""
    }

    private fun decodeBody(part: JSONObject): String {
        val data = part.optJSONObject("body")?.optString("data").orEmpty()
        if (data.isEmpty()) return ""
        return try {
            String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "base64 decode failed: ${t.message}"); ""
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("(?s)<style.*?</style>"), " ")
            .replace(Regex("(?s)<script.*?</script>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .let { decodeEntities(it) }
            .replace(Regex("\\s+"), " ")
            .trim()

    // Markers that begin quoted reply history — everything from the first match onward is dropped.
    private val REPLY_MARKERS = listOf(
        Regex("(?im)^\\s*On .+ wrote:\\s*$"),                       // Gmail/Apple: "On Tue, Jan 2, Jane <…> wrote:"
        Regex("(?im)^\\s*-{3,}\\s*Original Message\\s*-{3,}\\s*$"), // Outlook
        Regex("(?im)^\\s*_{5,}\\s*$"),                              // Outlook divider line
        Regex("(?im)^\\s*From:.+\\n\\s*Sent:.+"),                   // Outlook header block
        Regex("(?m)^\\s*>.*$"),                                     // quoted lines
    )

    // Signature delimiters — everything from the first match onward is dropped.
    private val SIGNATURE_MARKERS = listOf(
        Regex("(?m)^-- ?$"),                                        // RFC 3676 "-- " sig delimiter
        Regex("(?im)^\\s*Sent from my \\w+"),                       // "Sent from my iPhone"
        Regex("(?im)^\\s*Get Outlook for \\w+"),
    )

    private fun cleanReplyHistory(text: String): String {
        if (text.isBlank()) return text
        var cut = text.length
        for (re in REPLY_MARKERS + SIGNATURE_MARKERS) {
            re.find(text)?.let { if (it.range.first < cut) cut = it.range.first }
        }
        return text.substring(0, cut)
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun decodeEntities(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
}
