package com.example.locall.voice.music.history

import android.content.Context
import android.util.Log
import com.example.locall.voice.music.MusicSession

private const val TAG = "MusicHistoryRepository"

/**
 * Write-through recorder and query surface over [MusicHistoryDao]. The handler calls [record] on
 * every successful play; the personal-command paths read back through the query helpers. All work
 * is best-effort — a history hiccup must never break playback.
 */
class MusicHistoryRepository(context: Context) {

    private val dao = MusicHistoryDatabase.get(context).dao()

    /** A played track resolved from history, ready to hand back to the player. */
    data class Entry(val title: String, val artist: String?, val uri: String?)

    /**
     * Record a play. Bumps the existing row (count + recency, backfilling artist/uri if newly
     * known) or inserts a fresh one. Keyed by URI when present, else a normalized title|artist.
     */
    suspend fun record(track: MusicSession.Track, nowMs: Long) {
        val title = track.title.trim()
        if (title.isEmpty()) return
        val key = keyFor(track.uri, title, track.artist)
        try {
            val bumped = dao.bump(key, title, track.artist?.trim()?.ifEmpty { null }, track.uri, nowMs)
            if (bumped == 0) {
                dao.insertIfAbsent(
                    PlayedTrackEntity(
                        key = key,
                        title = title,
                        artist = track.artist?.trim()?.ifEmpty { null },
                        uri = track.uri,
                        requestCount = 1,
                        firstPlayedMs = nowMs,
                        lastPlayedMs = nowMs,
                    ),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "record failed for \"$title\" (non-fatal): ${t.message}")
        }
    }

    suspend fun mostPlayed(limit: Int = 25): List<Entry> =
        safe { dao.mostPlayed(limit).map { it.toEntry() } }

    suspend fun recent(limit: Int = 25): List<Entry> =
        safe { dao.recent(limit).map { it.toEntry() } }

    suspend fun playedBetween(fromMs: Long, toMs: Long, limit: Int = 25): List<Entry> =
        safe { dao.playedBetween(fromMs, toMs, limit).map { it.toEntry() } }

    /** Tracks by the user's single most-requested artist (empty if history has no named artist). */
    suspend fun favouriteArtistTracks(limit: Int = 25): List<Entry> = safe {
        val artist = dao.topArtists(1).firstOrNull() ?: return@safe emptyList()
        dao.byArtist(artist).take(limit).map { it.toEntry() }
    }

    suspend fun topArtists(limit: Int = 5): List<String> = safe { dao.topArtists(limit) }

    suspend fun tracksByArtist(artist: String, limit: Int = 25): List<Entry> =
        safe { dao.byArtist(artist.trim()).take(limit).map { it.toEntry() } }

    private inline fun <T> safe(block: () -> List<T>): List<T> =
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "history query failed (non-fatal): ${t.message}")
            emptyList()
        }

    private fun PlayedTrackEntity.toEntry() = Entry(title, artist, uri)

    private fun keyFor(uri: String?, title: String, artist: String?): String {
        val u = uri?.trim().orEmpty()
        if (u.isNotEmpty()) return u
        val t = norm(title)
        val a = artist?.let { norm(it) }.orEmpty()
        return if (a.isEmpty()) "t:$t" else "t:$t|$a"
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
