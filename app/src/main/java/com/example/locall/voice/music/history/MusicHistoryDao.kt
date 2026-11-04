package com.example.locall.voice.music.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MusicHistoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(track: PlayedTrackEntity): Long

    /**
     * Bump an existing row's count/recency and refresh its metadata (a later play may finally carry
     * the artist or a resolved URI the first one lacked). Returns rows affected (0 = no such key).
     */
    @Query(
        "UPDATE played_tracks SET requestCount = requestCount + 1, lastPlayedMs = :now, " +
            "title = :title, " +
            "artist = COALESCE(:artist, artist), " +
            "uri = COALESCE(:uri, uri) " +
            "WHERE `key` = :key",
    )
    suspend fun bump(key: String, title: String, artist: String?, uri: String?, now: Long): Int

    @Query("SELECT * FROM played_tracks WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): PlayedTrackEntity?

    @Query("SELECT * FROM played_tracks ORDER BY requestCount DESC, lastPlayedMs DESC LIMIT :limit")
    suspend fun mostPlayed(limit: Int): List<PlayedTrackEntity>

    @Query("SELECT * FROM played_tracks ORDER BY lastPlayedMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PlayedTrackEntity>

    /** Tracks last played within a time window (e.g. "yesterday"), most-recent first. */
    @Query(
        "SELECT * FROM played_tracks WHERE lastPlayedMs BETWEEN :fromMs AND :toMs " +
            "ORDER BY lastPlayedMs DESC LIMIT :limit",
    )
    suspend fun playedBetween(fromMs: Long, toMs: Long, limit: Int): List<PlayedTrackEntity>

    @Query(
        "SELECT * FROM played_tracks WHERE artist IS NOT NULL AND LOWER(artist) = LOWER(:artist) " +
            "ORDER BY requestCount DESC, lastPlayedMs DESC",
    )
    suspend fun byArtist(artist: String): List<PlayedTrackEntity>

    /** Artists ranked by total requests across all their tracks — backs "my favourite artists". */
    @Query(
        "SELECT artist FROM played_tracks WHERE artist IS NOT NULL AND artist != '' " +
            "GROUP BY LOWER(artist) ORDER BY SUM(requestCount) DESC, MAX(lastPlayedMs) DESC LIMIT :limit",
    )
    suspend fun topArtists(limit: Int): List<String>

    @Query("DELETE FROM played_tracks")
    suspend fun clearAll()
}
