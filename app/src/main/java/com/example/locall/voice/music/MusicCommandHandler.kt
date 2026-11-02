package com.example.locall.voice.music

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge

private const val TAG = "MusicCommandHandler"

class MusicCommandHandler(appContext: Context) {

    private val catalogue = MusicCatalogue(appContext)
    private val spotify = SpotifyController(appContext)

    suspend fun handle(
        context: Context,
        transcript: String,
        onStatus: (String) -> Unit,
        playViaSearch: suspend (String) -> Boolean = { false },
    ): String {
        val normalized = transcript.trim().lowercase()

        MusicIntents.transport(normalized)?.let { return runTransport(it) }

        val query = MusicIntents.playQuery(normalized)
            ?: return "🤷 didn't catch what to play"

        if (!spotify.isSpotifyInstalled()) return "⚠️ Spotify isn't installed"

        catalogue.findByQuery(query)?.let { hit ->
            Log.i(TAG, "catalogue fast-path hit for \"$query\": ${hit.title}")
            return playTrackHit(hit)
        }

        onStatus("Finding music…")
        LlmBridge.ensureLoaded(context, onStatus)
        val intent = MusicIntentParser.parse(context, query)
        Log.i(TAG, "query=\"$query\" -> $intent")
        return play(intent, query, playViaSearch)
    }

    private fun playTrackHit(hit: MusicCatalogue.Track): String =
        if (spotify.playUri(hit.uri)) {
            MusicSession.onPlayed(MusicSession.Track(hit.title, hit.artist, hit.uri), now())
            "🎵 playing ${hit.title} — ${hit.artist}"
        } else "⚠️ couldn't open Spotify"

    private suspend fun playViaSearchOrOpen(q: String, playViaSearch: suspend (String) -> Boolean): String = when {
        playViaSearch(q) -> {
            MusicSession.onPlayed(MusicSession.Track(q, null, null), now())
            "🎵 playing \"$q\""
        }
        spotify.search(q) -> "🔎 opened Spotify search for \"$q\" (couldn't auto-play — enable Accessibility for LocaLL)"
        else -> "⚠️ couldn't open Spotify"
    }

    private fun runTransport(intent: MusicIntent): String = when (intent) {
        MusicIntent.Pause -> if (spotify.pause()) { MusicSession.onPaused(now()); "⏸️ paused" } else "⚠️ couldn't pause"
        MusicIntent.Resume -> if (spotify.resume()) { MusicSession.onResumed(now()); "▶️ resumed" } else "⚠️ couldn't resume"
        MusicIntent.Skip -> if (spotify.skip()) { MusicSession.onSkipped(now()); "⏭️ skipped" } else "⚠️ couldn't skip"
        MusicIntent.Replay -> replayLast()
        MusicIntent.PlayDifferent -> playDifferent()
        else -> "🤷 didn't understand the music command"
    }

    private fun replayLast(): String {
        val track = MusicSession.current(now())
        val uri = track?.uri
        if (track != null && uri != null) {
            return if (spotify.playUri(uri)) {
                MusicSession.onPlayed(track, now())
                "🔁 replaying ${track.title}${track.artist?.let { " — $it" } ?: ""}"
            } else "⚠️ couldn't replay"
        }
        return if (spotify.replay()) "🔁 replaying" else "⚠️ couldn't replay"
    }

    private fun playDifferent(): String {
        val track = MusicSession.current(now())
        val artist = track?.artist
        if (!artist.isNullOrBlank()) {
            val other = catalogue.tracksByArtist(artist).firstOrNull { it.uri != track.uri }
            if (other != null) return playTrackHit(other)
        }
        return if (spotify.skip()) { MusicSession.onSkipped(now()); "⏭️ playing something else" } else "⚠️ couldn't skip"
    }

    private fun now(): Long = System.currentTimeMillis()

    private suspend fun play(
        intent: MusicIntent,
        query: String,
        playViaSearch: suspend (String) -> Boolean,
    ): String = when (intent) {
        is MusicIntent.PlayTrack -> {
            val hit = catalogue.findTrack(intent.title, intent.artist) ?: catalogue.findByQuery(query)
            if (hit != null) {
                playTrackHit(hit)
            } else {
                playViaSearchOrOpen(query, playViaSearch)
            }
        }
        is MusicIntent.PlayArtist -> {
            val hit = catalogue.pickByArtist(intent.artist, seed = query.length)
            if (hit != null) playTrackHit(hit)
            else playViaSearchOrOpen(if (artistWasSpoken(intent.artist, query)) intent.artist else query, playViaSearch)
        }
        else -> "🤷 didn't catch what to play"
    }

    private fun artistWasSpoken(artist: String, query: String): Boolean {
        val q = query.lowercase()
        return artist.lowercase().split(" ").filter { it.length >= 3 }.all { it in q }
    }
}
