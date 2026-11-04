package com.example.locall.voice.email.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One locally-cached Gmail message. Metadata is always present; [body] is filled in lazily the
 * first time the full message is read, so repeated reads and offline access cost no network.
 */
@Entity(tableName = "cached_emails")
data class CachedEmailEntity(
    @PrimaryKey val id: String,
    val from: String,
    val subject: String,
    val snippet: String,
    val labels: String,          // CSV of Gmail labelIds, e.g. "INBOX,IMPORTANT,UNREAD"
    val unread: Boolean,
    val inInbox: Boolean,
    val internalDateMs: Long,
    val body: String?,           // null until the full body has been fetched
    val bodyFetchedAt: Long?,
    val cachedAt: Long,
) {
    fun labelSet(): Set<String> =
        labels.split(',').filter { it.isNotBlank() }.toSet()
}
