package com.example.locall.voice

import com.example.locall.voice.memory.MemoryIntents
import com.example.locall.voice.music.MusicIntent
import com.example.locall.voice.music.MusicIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRoutingTest {

    private val matcher = IntentMatcher()

    private fun route(utterance: String): CommandAction? =
        matcher.matchGrammar(utterance.lowercase().trim(), utterance)


    @Test fun `play a named song routes to music`() {
        val a = route("play All Too Well")
        assertTrue(a is CommandAction.Music)
        assertEquals("play All Too Well", (a as CommandAction.Music).transcript)
    }

    @Test fun `transport commands route to music`() {
        for (cmd in listOf("pause", "resume", "skip", "next", "replay", "play it again")) {
            assertTrue("\"$cmd\" should route to music", route(cmd) is CommandAction.Music)
        }
    }

    @Test fun `play artist phrasing routes to music`() {
        assertTrue(route("play a Taylor Swift song") is CommandAction.Music)
    }

    @Test fun `MusicIntents distinguishes transport from play`() {
        assertEquals(MusicIntent.Pause, MusicIntents.transport("pause"))
        assertEquals(MusicIntent.Replay, MusicIntents.transport("play again"))
        assertNull(MusicIntents.playQuery("play again"))
        assertEquals("all too well", MusicIntents.playQuery("play all too well"))
        assertNull(MusicIntents.playQuery("pause"))
    }

    @Test fun `referential phrases resolve to transport, not a song title`() {
        assertEquals(MusicIntent.Pause, MusicIntents.transport("pause it"))
        assertEquals(MusicIntent.Resume, MusicIntents.transport("play it"))
        assertEquals(MusicIntent.Skip, MusicIntents.transport("skip it"))
        assertEquals(MusicIntent.Replay, MusicIntents.transport("do it again"))
        assertEquals(MusicIntent.Replay, MusicIntents.transport("play that song"))
        assertEquals(MusicIntent.Replay, MusicIntents.transport("the previous one"))
        assertEquals(MusicIntent.PlayDifferent, MusicIntents.transport("something else"))

        assertNull(MusicIntents.playQuery("play it"))
        assertNull(MusicIntents.playQuery("play that song"))
        assertEquals("that taylor swift song", MusicIntents.playQuery("play that taylor swift song"))
    }

    @Test fun `referential music commands route to the music module`() {
        for (cmd in listOf("pause it", "play it", "do it again", "play that song",
                "the previous one", "something else")) {
            assertTrue("\"$cmd\" should route to music", route(cmd) is CommandAction.Music)
        }
    }


    @Test fun `flashlight commands route to torch with the right on-off state`() {
        assertEquals(CommandAction.Torch(on = true), route("turn on flashlight"))
        assertEquals(CommandAction.Torch(on = true), route("flashlight on"))
        assertEquals(CommandAction.Torch(on = true), route("turn on the light"))
        assertEquals(CommandAction.Torch(on = false), route("turn off the flashlight"))
        assertEquals(CommandAction.Torch(on = false), route("torch off"))
    }


    @Test fun `remember routes to memory`() {
        val a = route("remember that my favorite color is blue")
        assertTrue(a is CommandAction.Memory)
    }

    @Test fun `memory save strips the prefix`() {
        assertEquals(
            "my favorite color is blue",
            MemoryIntents.saveStatement("remember that my favorite color is blue"),
        )
        assertEquals("i live in berlin", MemoryIntents.saveStatement("note that i live in berlin"))
    }

    @Test fun `what do you remember routes to memory as query-all`() {
        assertTrue(route("what do you remember about me") is CommandAction.Memory)
        assertEquals("", MemoryIntents.queryTopic("what do you remember about me"))
        assertEquals("my dog", MemoryIntents.queryTopic("what do you remember about my dog"))
    }

    @Test fun `forget routes to memory delete, clear is distinct`() {
        assertTrue(route("forget my address") is CommandAction.Memory)
        assertEquals("address", MemoryIntents.deleteTopic("forget my address"))
        assertEquals("john", MemoryIntents.deleteTopic("forget about john"))
        assertTrue(MemoryIntents.isClear("forget everything"))
        assertNull(MemoryIntents.deleteTopic("forget everything"))
    }


    @Test fun `plain commands do not route to music or memory`() {
        assertFalse(route("type hello world") is CommandAction.Music)
        assertFalse(route("type hello world") is CommandAction.Memory)
        assertTrue(route("type hello world") is CommandAction.TypeText)
        assertTrue(route("go back") is CommandAction.Navigate)
        assertFalse(MusicIntents.matches("open notes"))
        assertFalse(MemoryIntents.matches("open notes"))
    }
}
