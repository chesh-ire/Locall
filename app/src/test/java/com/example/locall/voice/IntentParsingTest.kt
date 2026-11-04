package com.example.locall.voice

import com.example.locall.voice.email.EmailIntent
import com.example.locall.voice.email.EmailIntentParser
import com.example.locall.voice.memory.MemoryExtractor
import com.example.locall.voice.music.MusicIntent
import com.example.locall.voice.music.MusicIntentParser
import com.example.locall.voice.music.MusicIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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


    @Test fun `personal intents resolve with and without a play verb`() {
        assertEquals(MusicIntent.PlayMostPlayed, MusicIntents.personal("play my most played songs"))
        assertEquals(MusicIntent.PlayMostPlayed, MusicIntents.personal("my most played"))
        assertEquals(MusicIntent.PlayFavouriteArtist, MusicIntents.personal("play my favourite artist"))
        assertEquals(MusicIntent.PlayFavouriteArtist, MusicIntents.personal("something by my favourite artist"))
        assertEquals(MusicIntent.PlayRecent, MusicIntents.personal("what i was listening to yesterday"))
    }

    @Test fun `an ordinary song request is not a personal intent`() {
        assertNull(MusicIntents.personal("play shake it off"))
        assertNull(MusicIntents.personal("play something by radiohead"))
    }

    @Test fun `personal and transport requests are routed to the music handler`() {
        assertTrue(MusicIntents.matches("my most played"))
        assertTrue(MusicIntents.matches("play what i played recently"))
        assertTrue(MusicIntents.matches("pause"))
        assertTrue(MusicIntents.matches("play teardrop by massive attack"))
    }

    @Test fun `transport still takes priority and personal does not hijack a real song`() {
        // "play that song" is a replay transport phrase, not a history query.
        assertEquals(MusicIntent.Replay, MusicIntents.transport("play that song"))
        assertNull(MusicIntents.personal("play that song"))
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


    @Test fun `search json maps to Search with the topic query`() {
        val i = EmailIntentParser.parseJson(
            """{"op":"search","query":"revised project deadline newer_than:14d"}""",
        )
        assertEquals(EmailIntent.Search("revised project deadline newer_than:14d"), i)
    }

    @Test fun `search without a topic is Unknown`() {
        val i = EmailIntentParser.parseJson("""{"op":"search","query":""}""")
        assertTrue(i is EmailIntent.Unknown)
    }

    @Test fun `summarize still parses alongside the new search op`() {
        val i = EmailIntentParser.parseJson("""{"op":"summarize","query":"from:boss in:inbox"}""")
        assertEquals(EmailIntent.Summarize("from:boss in:inbox"), i)
    }


    @Test fun `volume phrases map to Volume actions`() {
        val m = IntentMatcher()
        assertEquals(CommandAction.Volume(up = true), m.matchGrammar("volume up", "volume up"))
        assertEquals(CommandAction.Volume(up = true), m.matchGrammar("turn up the volume", "turn up the volume"))
        assertEquals(CommandAction.Volume(up = false), m.matchGrammar("volume down", "volume down"))
        assertEquals(CommandAction.Volume(up = false), m.matchGrammar("turn the volume down", "turn the volume down"))
    }

    @Test fun `bare volume word is not a volume command`() {
        assertNull(IntentMatcher().matchGrammar("volume", "volume"))
    }


    @Test fun `set alarm parses am time`() {
        assertEquals(CommandAction.SetAlarm(7, 0), ClockIntents.parse("set an alarm for 7 am"))
    }

    @Test fun `set alarm parses pm time with minutes`() {
        assertEquals(CommandAction.SetAlarm(18, 30), ClockIntents.parse("set alarm for 6:30 pm"))
    }

    @Test fun `set alarm parses quarter and half past`() {
        assertEquals(CommandAction.SetAlarm(6, 15), ClockIntents.parse("wake me up at quarter past 6 am"))
        assertEquals(CommandAction.SetAlarm(7, 30), ClockIntents.parse("set an alarm for half past 7"))
    }

    @Test fun `set alarm parses quarter to and noon and midnight`() {
        assertEquals(CommandAction.SetAlarm(7, 45), ClockIntents.parse("alarm for quarter to 8"))
        assertEquals(CommandAction.SetAlarm(12, 0), ClockIntents.parse("set an alarm for noon"))
        assertEquals(CommandAction.SetAlarm(0, 0), ClockIntents.parse("set an alarm for midnight"))
    }

    @Test fun `twelve am maps to zero hour`() {
        assertEquals(CommandAction.SetAlarm(0, 0), ClockIntents.parse("set an alarm for 12 am"))
    }

    @Test fun `set timer parses minutes and compound durations`() {
        assertEquals(CommandAction.SetTimer(600), ClockIntents.parse("set a timer for 10 minutes"))
        assertEquals(CommandAction.SetTimer(90 * 60), ClockIntents.parse("timer for 1 hour 30 minutes"))
        assertEquals(CommandAction.SetTimer(90), ClockIntents.parse("start a timer for 1 minute 30 seconds"))
    }

    @Test fun `set timer parses word durations`() {
        assertEquals(CommandAction.SetTimer(5 * 60), ClockIntents.parse("set a timer for five minutes"))
        assertEquals(CommandAction.SetTimer(30 * 60), ClockIntents.parse("set a timer for half an hour"))
    }

    @Test fun `timer without a duration does not guess`() {
        assertNull(ClockIntents.parse("set a timer"))
    }

    @Test fun `cancel beats alarm keyword`() {
        assertEquals(CommandAction.CancelAlarm, ClockIntents.parse("cancel my 7 am alarm"))
        assertEquals(CommandAction.CancelAlarm, ClockIntents.parse("turn off my alarm"))
    }

    @Test fun `non-clock phrases are not clock commands`() {
        assertNull(ClockIntents.parse("open spotify"))
        assertNull(ClockIntents.parse("play some music"))
    }

    @Test fun `screenshot and lock map through the matcher`() {
        val m = IntentMatcher()
        assertEquals(CommandAction.Screenshot, m.matchGrammar("take a screenshot", "take a screenshot"))
        assertEquals(CommandAction.LockScreen, m.matchGrammar("lock my phone", "lock my phone"))
    }

    @Test fun `clock commands route through the matcher grammar`() {
        val m = IntentMatcher()
        assertEquals(CommandAction.SetTimer(600), m.matchGrammar("set a timer for 10 minutes", "set a timer for 10 minutes"))
        assertEquals(CommandAction.SetAlarm(7, 0), m.matchGrammar("set an alarm for 7 am", "set an alarm for 7 am"))
    }
}
