package com.example.locall.voice.music

sealed interface MusicIntent {

    data class PlayTrack(val title: String, val artist: String? = null) : MusicIntent

    data class PlayArtist(val artist: String) : MusicIntent

    data object Pause : MusicIntent
    data object Resume : MusicIntent
    data object Skip : MusicIntent
    data object Replay : MusicIntent

    data object PlayDifferent : MusicIntent

    // --- personalized commands, backed by local listening history ---

    /** "play my most played / my top songs / the songs I listen to the most" */
    data object PlayMostPlayed : MusicIntent

    /** "play what I was listening to / my recent songs / what I played recently" */
    data object PlayRecent : MusicIntent

    /** "play my favourite artist / something by my favourite artist" */
    data object PlayFavouriteArtist : MusicIntent

    data class Unknown(val reason: String) : MusicIntent
}
