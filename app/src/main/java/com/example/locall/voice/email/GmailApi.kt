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
    )

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
        val labels = o.optJSONArray("labelIds")
        val unread = labels != null && (0 until labels.length()).any { labels.getString(it) == "UNREAD" }
        Message(
            id = id,
            from = header("From"),
            subject = header("Subject"),
            snippet = decodeEntities(o.optString("snippet")),
            unread = unread,
            internalDateMs = o.optString("internalDate").toLongOrNull() ?: 0L,
        )
    }

    suspend fun plainText(token: String, id: String): String = withContext(Dispatchers.IO) {
        val o = getJson(token, "$BASE/messages/$id?format=full")
        val payload = o.optJSONObject("payload") ?: return@withContext decodeEntities(o.optString("snippet"))
        extractText(payload).ifBlank { decodeEntities(o.optString("snippet")) }.trim()
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

    suspend fun trash(token: String, ids: List<String>) =
        batchModify(token, ids, addLabelIds = listOf("TRASH"), removeLabelIds = listOf("INBOX", "UNREAD"))


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

    private fun decodeEntities(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
}
