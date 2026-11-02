package com.example.locall.voice.music

sealed interface MusicIntent {

    data class PlayTrack(val title: String, val artist: String? = null) : MusicIntent

    data class PlayArtist(val artist: String) : MusicIntent

    data object Pause : MusicIntent
    data object Resume : MusicIntent
    data object Skip : MusicIntent
    data object Replay : MusicIntent

    data object PlayDifferent : MusicIntent

    data class Unknown(val reason: String) : MusicIntent
}
