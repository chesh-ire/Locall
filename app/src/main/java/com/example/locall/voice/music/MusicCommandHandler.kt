package com.example.locall.voice.music

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.music.history.MusicHistoryRepository

private const val TAG = "MusicCommandHandler"

class MusicCommandHandler(appContext: Context) {

    private val catalogue = MusicCatalogue(appContext)
    private val spotify = SpotifyController(appContext)
    private val history = MusicHistoryRepository(appContext)

    suspend fun handle(
        context: Context,
        transcript: String,
        onStatus: (String) -> Unit,
        playViaSearch: suspend (String) -> Boolean = { false },
    ): String {
        val normalized = transcript.trim().lowercase()

        MusicIntents.transport(normalized)?.let { return runTransport(it) }

        if (!spotify.isSpotifyInstalled()) return "⚠️ Spotify isn't installed"

        MusicIntents.personal(normalized)?.let { return runPersonal(it, playViaSearch) }

        val query = MusicIntents.playQuery(normalized)
            ?: return "🤷 didn't catch what to play"

        // 1. Offline cold-start: a known catalogue track plays deterministically with no network.
        catalogue.findByQuery(query)?.let { hit ->
            Log.i(TAG, "catalogue fast-path hit for \"$query\": ${hit.title}")
            return playTrack(hit.title, hit.artist, hit.uri)
        }

        // 2. Search-first: resolve the raw request to a real Spotify track → deterministic playback.
        if (SpotifyApi.isConfigured) {
            onStatus("Finding music…")
            SpotifyApi.resolveTrack(query)?.let { t ->
                Log.i(TAG, "spotify api resolved \"$query\" -> ${t.uri}")
                return playTrack(t.title, t.artist.ifBlank { null }, t.uri)
            }
        }

        // 3. Fall back to the local LLM to split title/artist, then resolve/search again.
        onStatus("Finding music…")
        LlmBridge.ensureLoaded(context, onStatus)
        val intent = MusicIntentParser.parse(context, query)
        Log.i(TAG, "query=\"$query\" -> $intent")
        return play(intent, query, playViaSearch)
    }

    // ---------- the single play sink: media + history stay in lock-step ----------

    /** Every successful play funnels through here so [MusicSession] and history are always consistent. */
    private suspend fun recordPlayed(track: MusicSession.Track) {
        val now = now()
        MusicSession.onPlayed(track, now)
        history.record(track, now)
    }

    private suspend fun playTrack(title: String, artist: String?, uri: String): String =
        if (spotify.playUri(uri)) {
            recordPlayed(MusicSession.Track(title, artist?.ifBlank { null }, uri))
            "🎵 playing $title${artist?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}"
        } else "⚠️ couldn't open Spotify"

    /** API-resolve → URI play, else Accessibility search, else open Spotify's search screen. */
    private suspend fun resolveOrSearch(query: String, playViaSearch: suspend (String) -> Boolean): String {
        if (SpotifyApi.isConfigured) {
            SpotifyApi.resolveTrack(query)?.let { t ->
                return playTrack(t.title, t.artist.ifBlank { null }, t.uri)
            }
        }
        return when {
            playViaSearch(query) -> {
                recordPlayed(MusicSession.Track(query, null, null))
                "🎵 playing \"$query\""
            }
            spotify.search(query) -> "🔎 opened Spotify search for \"$query\" (couldn't auto-play — enable Accessibility for LocaLL)"
            else -> "⚠️ couldn't open Spotify"
        }
    }

    // ---------- personalized history commands ----------

    private suspend fun runPersonal(intent: MusicIntent, playViaSearch: suspend (String) -> Boolean): String = when (intent) {
        MusicIntent.PlayMostPlayed ->
            playFromHistory(history.mostPlayed(HISTORY_PICK), "your most played", playViaSearch)
        MusicIntent.PlayRecent ->
            playFromHistory(history.recent(HISTORY_PICK), "your recently played", playViaSearch)
        MusicIntent.PlayFavouriteArtist ->
            playFromHistory(history.favouriteArtistTracks(HISTORY_PICK), "your favourite artist", playViaSearch)
        else -> "🤷 didn't understand the music command"
    }

    private suspend fun playFromHistory(
        entries: List<MusicHistoryRepository.Entry>,
        label: String,
        playViaSearch: suspend (String) -> Boolean,
    ): String {
        if (entries.isEmpty()) return "🤷 no listening history yet — play a few songs first"
        val pick = entries.firstOrNull { !it.uri.isNullOrBlank() } ?: entries.first()
        val uri = pick.uri
        Log.i(TAG, "personal pick ($label): ${pick.title} uri=$uri")
        return if (!uri.isNullOrBlank()) {
            playTrack(pick.title, pick.artist, uri)
        } else {
            resolveOrSearch(listOfNotNull(pick.title, pick.artist).joinToString(" ").trim(), playViaSearch)
        }
    }

    // ---------- transport ----------

    private suspend fun runTransport(intent: MusicIntent): String = when (intent) {
        MusicIntent.Pause -> if (spotify.pause()) { MusicSession.onPaused(now()); "⏸️ paused" } else "⚠️ couldn't pause"
        MusicIntent.Resume -> if (spotify.resume()) { MusicSession.onResumed(now()); "▶️ resumed" } else "⚠️ couldn't resume"
        MusicIntent.Skip -> if (spotify.skip()) { MusicSession.onSkipped(now()); "⏭️ skipped" } else "⚠️ couldn't skip"
        MusicIntent.Replay -> replayLast()
        MusicIntent.PlayDifferent -> playDifferent()
        else -> "🤷 didn't understand the music command"
    }

    private suspend fun replayLast(): String {
        val track = MusicSession.current(now())
        val uri = track?.uri
        if (track != null && uri != null) {
            return if (spotify.playUri(uri)) {
                recordPlayed(track)
                "🔁 replaying ${track.title}${track.artist?.let { " — $it" } ?: ""}"
            } else "⚠️ couldn't replay"
        }
        return if (spotify.replay()) "🔁 replaying" else "⚠️ couldn't replay"
    }

    private suspend fun playDifferent(): String {
        val track = MusicSession.current(now())
        val artist = track?.artist
        if (!artist.isNullOrBlank()) {
            val other = catalogue.tracksByArtist(artist).firstOrNull { it.uri != track.uri }
            if (other != null) return playTrack(other.title, other.artist, other.uri)
        }
        return if (spotify.skip()) { MusicSession.onSkipped(now()); "⏭️ playing something else" } else "⚠️ couldn't skip"
    }

    private fun now(): Long = System.currentTimeMillis()

    // ---------- LLM-parsed play fallback ----------

    private suspend fun play(
        intent: MusicIntent,
        query: String,
        playViaSearch: suspend (String) -> Boolean,
    ): String = when (intent) {
        is MusicIntent.PlayTrack -> {
            val hit = catalogue.findTrack(intent.title, intent.artist) ?: catalogue.findByQuery(query)
            if (hit != null) {
                playTrack(hit.title, hit.artist, hit.uri)
            } else {
                resolveOrSearch(query, playViaSearch)
            }
        }
        is MusicIntent.PlayArtist -> {
            val hit = catalogue.pickByArtist(intent.artist, seed = query.length)
            if (hit != null) playTrack(hit.title, hit.artist, hit.uri)
            else resolveOrSearch(if (artistWasSpoken(intent.artist, query)) intent.artist else query, playViaSearch)
        }
        else -> "🤷 didn't catch what to play"
    }

    private fun artistWasSpoken(artist: String, query: String): Boolean {
        val q = query.lowercase()
        return artist.lowercase().split(" ").filter { it.length >= 3 }.all { it in q }
    }

    private companion object {
        const val HISTORY_PICK = 10
    }
}
