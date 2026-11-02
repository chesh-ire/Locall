package com.example.locall.voice.music

object MusicIntents {

    private val PAUSE = setOf("pause", "pause music", "pause the music", "pause song", "pause the song", "stop", "stop music", "stop the music", "stop the song", "pause it", "pause this", "pause that", "pause that song", "pause this song")
    private val RESUME = setOf("resume", "resume music", "resume the music", "resume song", "unpause", "continue", "continue playing", "keep playing", "play music", "play the music", "play it", "resume it", "resume that", "resume this", "continue it")
    private val SKIP = setOf("skip", "next", "next song", "next track", "skip song", "skip track", "skip this", "skip the song", "play next", "play the next song", "skip it", "skip that")
    private val REPLAY = setOf(
        "replay", "replay song", "replay the song", "restart", "restart song", "restart the song",
        "play again", "play it again", "repeat", "repeat song", "start over",
        "do it again", "play it one more time", "one more time", "again",
        "play that", "play that song", "play that one", "replay it", "restart it",
        "play the previous one", "play the last one", "play the previous song", "play the last song",
        "that song", "that one", "the previous one", "the last one", "the previous song", "the last song",
    )
    private val DIFFERENT = setOf(
        "something else", "play something else", "something different", "play something different",
        "a different one", "play a different one", "different song", "play a different song",
    )

    private val PLAY_PREFIXES = listOf("play ", "put on ", "start playing ")

    fun matches(normalized: String): Boolean =
        transport(normalized) != null || playQuery(normalized) != null

    fun transport(normalized: String): MusicIntent? = when (normalized) {
        in PAUSE -> MusicIntent.Pause
        in RESUME -> MusicIntent.Resume
        in SKIP -> MusicIntent.Skip
        in REPLAY -> MusicIntent.Replay
        in DIFFERENT -> MusicIntent.PlayDifferent
        else -> null
    }

    fun playQuery(normalized: String): String? {
        if (transport(normalized) != null) return null
        val prefix = PLAY_PREFIXES.firstOrNull { normalized.startsWith(it) } ?: return null
        return normalized.removePrefix(prefix).trim().ifEmpty { null }
    }
}
