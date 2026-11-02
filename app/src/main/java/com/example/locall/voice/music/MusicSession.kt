package com.example.locall.voice.music

object MusicSession {

    data class Track(val title: String, val artist: String?, val uri: String?)

    enum class Playback { PLAYING, PAUSED, UNKNOWN }

    @Volatile private var lastTrack: Track? = null
    @Volatile private var lastTouchedMs: Long = 0L

    @Volatile var playback: Playback = Playback.UNKNOWN
        private set

    fun onPlayed(track: Track, nowMs: Long) {
        lastTrack = track
        playback = Playback.PLAYING
        lastTouchedMs = nowMs
    }

    fun onPaused(nowMs: Long) { playback = Playback.PAUSED; lastTouchedMs = nowMs }

    fun onResumed(nowMs: Long) { playback = Playback.PLAYING; lastTouchedMs = nowMs }

    fun onSkipped(nowMs: Long) { lastTrack = null; playback = Playback.PLAYING; lastTouchedMs = nowMs }

    fun current(nowMs: Long): Track? =
        lastTrack?.takeIf { nowMs - lastTouchedMs <= REFERENCE_TTL_MS }

    fun reset() {
        lastTrack = null
        playback = Playback.UNKNOWN
        lastTouchedMs = 0L
    }

    const val REFERENCE_TTL_MS = 5 * 60_000L
}
