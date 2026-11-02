package com.example.locall.voice.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MusicSessionTest {

    private val track = MusicSession.Track("All Too Well", "Taylor Swift", "spotify:track:abc")

    @Before fun clean() = MusicSession.reset()

    @Test fun `a freshly played track is the current referent`() {
        MusicSession.onPlayed(track, nowMs = 1_000)
        assertEquals(track, MusicSession.current(nowMs = 1_000))
        assertEquals(MusicSession.Playback.PLAYING, MusicSession.playback)
    }

    @Test fun `a reference goes stale after the TTL and falls through to null`() {
        MusicSession.onPlayed(track, nowMs = 1_000)
        val justInside = 1_000 + MusicSession.REFERENCE_TTL_MS
        val justPast = justInside + 1
        assertEquals(track, MusicSession.current(justInside))
        assertNull("stale reference must not resolve", MusicSession.current(justPast))
    }

    @Test fun `pause keeps the referent but marks it paused, resume flips it back`() {
        MusicSession.onPlayed(track, nowMs = 1_000)
        MusicSession.onPaused(nowMs = 2_000)
        assertEquals(track, MusicSession.current(nowMs = 2_000))
        assertEquals(MusicSession.Playback.PAUSED, MusicSession.playback)

        MusicSession.onResumed(nowMs = 3_000)
        assertEquals(MusicSession.Playback.PLAYING, MusicSession.playback)
    }

    @Test fun `pausing refreshes the TTL so a paused song stays referable`() {
        MusicSession.onPlayed(track, nowMs = 1_000)
        MusicSession.onPaused(nowMs = 1_000 + MusicSession.REFERENCE_TTL_MS - 1)
        val stillFresh = 1_000 + MusicSession.REFERENCE_TTL_MS + 5
        assertEquals(track, MusicSession.current(stillFresh))
    }

    @Test fun `skipping forgets which track is up`() {
        MusicSession.onPlayed(track, nowMs = 1_000)
        MusicSession.onSkipped(nowMs = 2_000)
        assertNull("after a skip the referent is unknown", MusicSession.current(nowMs = 2_000))
        assertEquals(MusicSession.Playback.PLAYING, MusicSession.playback)
    }

    @Test fun `reset clears everything`() {
        MusicSession.onPlayed(track, nowMs = 1_000)
        MusicSession.reset()
        assertNull(MusicSession.current(nowMs = 1_000))
        assertEquals(MusicSession.Playback.UNKNOWN, MusicSession.playback)
    }
}
