package com.example.locall.voice.music.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One entry in the user's local listening history (feature: personal music context). A row is
 * created the first time a track is requested and its [requestCount] is bumped on every repeat,
 * so frequency- and recency-based commands ("my most played", "what I listened to yesterday")
 * are plain queries over this table. Fully on-device — no network, no backend.
 *
 * [key] is the stable identity: the Spotify URI when known (the common case now that requests are
 * resolved through the Web API), otherwise a normalized "title|artist" fallback so Accessibility-
 * only plays still accumulate instead of creating a new row every time.
 */
@Entity(tableName = "played_tracks")
data class PlayedTrackEntity(
    @PrimaryKey val key: String,
    val title: String,
    val artist: String?,
    val uri: String?,            // null when the track was never resolved to a Spotify URI
    val requestCount: Int,
    val firstPlayedMs: Long,
    val lastPlayedMs: Long,
)
