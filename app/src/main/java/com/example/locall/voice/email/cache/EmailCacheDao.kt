package com.example.locall.voice.email.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmailCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(email: CachedEmailEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(emails: List<CachedEmailEntity>)

    @Query("SELECT * FROM cached_emails WHERE id = :id LIMIT 1")
    suspend fun get(id: String): CachedEmailEntity?

    @Query("SELECT * FROM cached_emails WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<CachedEmailEntity>

    @Query("DELETE FROM cached_emails WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM cached_emails WHERE inInbox = 1 AND unread = 1 ORDER BY internalDateMs DESC")
    suspend fun unreadInbox(): List<CachedEmailEntity>

    @Query("SELECT * FROM cached_emails WHERE inInbox = 1 ORDER BY internalDateMs DESC LIMIT :limit")
    suspend fun recentInbox(limit: Int): List<CachedEmailEntity>

    @Query(
        "SELECT * FROM cached_emails WHERE inInbox = 1 AND unread = 1 " +
            "AND labels LIKE '%IMPORTANT%' ORDER BY internalDateMs DESC",
    )
    suspend fun importantUnread(): List<CachedEmailEntity>

    @Query("UPDATE cached_emails SET labels = :labels, unread = :unread, inInbox = :inInbox WHERE id = :id")
    suspend fun updateLabels(id: String, labels: String, unread: Boolean, inInbox: Boolean)

    /** Evict metadata-only rows (no fetched body) older than [cutoff] to bound cache size. */
    @Query("DELETE FROM cached_emails WHERE cachedAt < :cutoff AND body IS NULL")
    suspend fun pruneMetadataOlderThan(cutoff: Long)

    @Query("DELETE FROM cached_emails")
    suspend fun clearAll()

    // ----- incremental-sync checkpoint -----

    @Query("SELECT * FROM email_sync_state WHERE id = 0 LIMIT 1")
    suspend fun syncState(): EmailSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSyncState(state: EmailSyncStateEntity)
}
