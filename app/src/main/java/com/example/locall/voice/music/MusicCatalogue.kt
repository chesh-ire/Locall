package com.example.locall.voice.music

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "MusicCatalogue"

class MusicCatalogue(private val appContext: Context) {

    data class Track(val title: String, val artist: String, val uri: String)

    private val cache = AtomicReference<List<Track>?>(null)

    private fun tracks(): List<Track> {
        cache.get()?.let { return it }
        val loaded = load()
        cache.set(loaded)
        return loaded
    }

    private fun load(): List<Track> = try {
        val json = appContext.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uri = o.optString("uri").trim()
                val title = o.optString("title").trim()
                val artist = o.optString("artist").trim()
                if (uri.isNotEmpty() && title.isNotEmpty()) add(Track(title, artist, uri))
            }
        }.also { Log.i(TAG, "loaded ${it.size} catalogue tracks") }
    } catch (t: Throwable) {
        Log.w(TAG, "could not load $ASSET_PATH: ${t.message}")
        emptyList()
    }

    fun findTrack(title: String, artist: String? = null): Track? {
        val t = norm(title)
        if (t.isEmpty()) return null
        val a = artist?.let { norm(it) }.orEmpty()
        val all = tracks()

        fun artistOk(track: Track) = a.isEmpty() || norm(track.artist).contains(a) || a.contains(norm(track.artist))

        return all.firstOrNull { norm(it.title) == t && artistOk(it) }
            ?: all.firstOrNull { norm(it.title) == t }
            ?: all.firstOrNull { (norm(it.title).contains(t) || t.contains(norm(it.title))) && artistOk(it) }
            ?: all.firstOrNull { norm(it.title).contains(t) || t.contains(norm(it.title)) }
    }

    fun findByQuery(query: String): Track? {
        val q = contentTokens(query)
        if (q.isEmpty()) return findTrack(query)
        return tracks().firstOrNull { track ->
            val hay = contentTokens("${track.title} ${track.artist}")
            q.all { it in hay }
        } ?: findTrack(query)
    }

    fun tracksByArtist(artist: String): List<Track> {
        val a = norm(artist)
        if (a.isEmpty()) return emptyList()
        return tracks().filter { norm(it.artist).contains(a) || a.contains(norm(it.artist)) }
    }

    fun pickByArtist(artist: String, seed: Int = 0): Track? {
        val matches = tracksByArtist(artist)
        if (matches.isEmpty()) return null
        return matches[(seed.and(Int.MAX_VALUE)) % matches.size]
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun contentTokens(s: String): Set<String> =
        norm(s).split(" ").filter { it.length >= 2 }.toSet()

    private companion object {
        const val ASSET_PATH = "music/catalogue.json"
    }
}
