package com.example.locall.voice.email.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table (id is always 0) holding the Gmail incremental-sync checkpoint.
 * [historyId] is the last mailbox history id we have applied deltas up to.
 */
@Entity(tableName = "email_sync_state")
data class EmailSyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val historyId: Long,
    val lastSyncAt: Long,
)
