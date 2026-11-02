package com.example.locall.voice

import com.example.locall.voice.memory.MemoryExtractor
import com.example.locall.voice.music.MusicIntent
import com.example.locall.voice.music.MusicIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParsingTest {


    @Test fun `play_track json maps to PlayTrack`() {
        val i = MusicIntentParser.parseJson(
            """{"kind":"play_track","title":"All Too Well","artist":"Taylor Swift"}""", "all too well",
        )
        assertEquals(MusicIntent.PlayTrack("All Too Well", "Taylor Swift"), i)
    }

    @Test fun `play_track with empty artist drops the artist`() {
        val i = MusicIntentParser.parseJson(
            """{"kind":"play_track","title":"All Too Well","artist":""}""", "all too well",
        )
        assertEquals(MusicIntent.PlayTrack("All Too Well", null), i)
    }

    @Test fun `play_artist json maps to PlayArtist`() {
        val i = MusicIntentParser.parseJson(
            """{"kind":"play_artist","title":"","artist":"Taylor Swift"}""", "a taylor swift song",
        )
        assertEquals(MusicIntent.PlayArtist("Taylor Swift"), i)
    }

    @Test fun `malformed music json falls back to playing the raw query`() {
        val i = MusicIntentParser.parseJson("not json at all", "some song")
        assertEquals(MusicIntent.PlayTrack("some song", null), i)
    }


    @Test fun `memory json maps to key value`() {
        val e = MemoryExtractor.parseJson(
            """{"key":"Favorite Color","value":"blue"}""", "my favorite color is blue",
        )
        assertEquals("favorite color", e.key)
        assertEquals("blue", e.value)
    }

    @Test fun `malformed memory json falls back to the whole statement`() {
        val e = MemoryExtractor.parseJson("garbage", "i like tea")
        assertEquals("i like tea", e.key)
        assertEquals("i like tea", e.value)
    }

    @Test fun `empty fields fall back to the whole statement`() {
        val e = MemoryExtractor.parseJson("""{"key":"","value":""}""", "i like tea")
        assertTrue(e.key.isNotEmpty() && e.value.isNotEmpty())
    }
}
