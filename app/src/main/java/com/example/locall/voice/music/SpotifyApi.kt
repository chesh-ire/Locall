package com.example.locall.voice.music

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.example.locall.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "SpotifyApi"

/**
 * Resolves a spoken song request into a concrete Spotify track via the Web API search endpoint, so
 * ANY song can be played deterministically through [SpotifyController.playUri] — no fixed in-app
 * catalogue required. Uses the client-credentials flow (app token, no user login); track search
 * needs no user scope.
 *
 * Credentials come from [BuildConfig.SPOTIFY_CLIENT_ID]/[BuildConfig.SPOTIFY_CLIENT_SECRET], which
 * app/build.gradle.kts injects from local.properties (git-ignored). When they are blank the
 * resolver is simply disabled ([isConfigured] = false) and callers fall back to the Accessibility
 * search flow — the app still builds and runs without credentials.
 */
object SpotifyApi {

    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val SEARCH_URL = "https://api.spotify.com/v1/search"

    data class Track(val uri: String, val title: String, val artist: String)

    val isConfigured: Boolean
        get() = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank() && BuildConfig.SPOTIFY_CLIENT_SECRET.isNotBlank()

    @Volatile private var token: String? = null
    @Volatile private var tokenUntilElapsedMs: Long = 0L

    /** Best-effort: resolve [query] to the top matching track, or null if disabled/no match/error. */
    suspend fun resolveTrack(query: String): Track? {
        if (!isConfigured) return null
        val q = query.trim()
        if (q.isEmpty()) return null
        return try {
            val tok = accessToken() ?: return null
            search(tok, q)
        } catch (t: Throwable) {
            Log.w(TAG, "resolveTrack(\"$q\") failed: ${t.message}")
            null
        }
    }

    private suspend fun search(token: String, query: String): Track? = withContext(Dispatchers.IO) {
        val url = "$SEARCH_URL?type=track&limit=1&q=" + enc(query)
        val (code, body) = get(url, token)
        if (code == 401) { invalidateToken(); return@withContext null }
        if (code !in 200..299) {
            Log.w(TAG, "search -> $code: ${body.take(200)}")
            return@withContext null
        }
        val items = JSONObject(body).optJSONObject("tracks")?.optJSONArray("items")
        val first = items?.takeIf { it.length() > 0 }?.getJSONObject(0) ?: return@withContext null
        val uri = first.optString("uri").trim()
        if (uri.isEmpty()) return@withContext null
        val title = first.optString("name").trim()
        val artist = first.optJSONArray("artists")
            ?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optString("name")?.trim().orEmpty()
        Track(uri = uri, title = title.ifEmpty { query }, artist = artist)
    }

    private suspend fun accessToken(): String? {
        token?.let { if (SystemClock.elapsedRealtime() < tokenUntilElapsedMs) return it }
        return fetchToken()
    }

    private suspend fun fetchToken(): String? = withContext(Dispatchers.IO) {
        val basic = Base64.encodeToString(
            "${BuildConfig.SPOTIFY_CLIENT_ID}:${BuildConfig.SPOTIFY_CLIENT_SECRET}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val (code, body) = post(TOKEN_URL, "grant_type=client_credentials", "Basic $basic")
        if (code !in 200..299) {
            Log.w(TAG, "token -> $code: ${body.take(200)}")
            return@withContext null
        }
        val obj = JSONObject(body)
        val access = obj.optString("access_token").trim().ifEmpty { return@withContext null }
        val expiresInSec = obj.optInt("expires_in", 3600)
        token = access
        // Refresh a minute early to avoid racing expiry mid-request.
        tokenUntilElapsedMs = SystemClock.elapsedRealtime() + (expiresInSec - 60).coerceAtLeast(30) * 1000L
        access
    }

    private fun invalidateToken() {
        token = null
        tokenUntilElapsedMs = 0L
    }

    // ---------- HTTP (java.net, mirroring GmailApi — no Retrofit/OkHttp dependency) ----------

    private fun get(url: String, bearer: String): Pair<Int, String> =
        request("GET", url, body = null, headers = mapOf("Authorization" to "Bearer $bearer"))

    private fun post(url: String, form: String, authorization: String): Pair<Int, String> =
        request(
            "POST", url, body = form,
            headers = mapOf(
                "Authorization" to authorization,
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
        )

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) doOutput = true
        }
        return try {
            body?.let { conn.outputStream.use { os -> os.write(it.toByteArray(Charsets.UTF_8)) } }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
