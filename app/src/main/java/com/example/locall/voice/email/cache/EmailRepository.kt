package com.example.locall.voice.email.cache

import android.content.Context
import android.util.Log
import com.example.locall.voice.email.GmailApi
import java.io.IOException

private const val TAG = "EmailRepository"
private const val METADATA_TTL_MS = 7L * 24 * 60 * 60 * 1000 // evict unread-body metadata after 7 days

/**
 * Read-through cache over [GmailApi]. Metadata and bodies are served from Room when present and
 * fetched + cached on a miss. Keeps the cache fresh via Gmail's incremental history feed and
 * falls back to cached data when the network is unavailable.
 */
class EmailRepository(context: Context) {

    private val dao = EmailCacheDatabase.get(context).dao()

    /** Which cached slice to serve if a network fetch fails. */
    enum class CacheScope { RECENT_INBOX, UNREAD_INBOX, IMPORTANT_UNREAD }

    /** A list of messages plus whether it was served from the offline cache. */
    data class Listing(val messages: List<GmailApi.Message>, val fromCache: Boolean)

    // ---------- read-through metadata ----------

    suspend fun metadata(token: String, id: String): GmailApi.Message {
        dao.get(id)?.let { return it.toMessage() }
        val m = GmailApi.metadata(token, id)
        dao.upsert(m.toEntity(now()))
        return m
    }

    /** Fetch metadata for [ids] in order, using the cache for hits and the network for misses. */
    suspend fun metadataFor(token: String, ids: List<String>): List<GmailApi.Message> {
        if (ids.isEmpty()) return emptyList()
        val cached = dao.getAll(ids).associateBy { it.id }
        val fetched = HashMap<String, GmailApi.Message>()
        val missing = ids.filter { it !in cached }
        if (missing.isNotEmpty()) {
            val stamp = now()
            val rows = ArrayList<CachedEmailEntity>(missing.size)
            for (id in missing) {
                val m = GmailApi.metadata(token, id)
                fetched[id] = m
                rows.add(m.toEntity(stamp))
            }
            dao.upsertAll(rows)
        }
        return ids.mapNotNull { id -> cached[id]?.toMessage() ?: fetched[id] }
    }

    /** Full body for [id], fetched once and cached for subsequent reads. */
    suspend fun body(token: String, id: String): String {
        val existing = dao.get(id)
        existing?.body?.let { return it }
        val text = GmailApi.plainText(token, id)
        val stamp = now()
        val row = existing ?: GmailApi.metadata(token, id).toEntity(stamp)
        dao.upsert(row.copy(body = text, bodyFetchedAt = stamp))
        return text
    }

    /** List messages for [query], falling back to the cached [scope] on a network error. */
    suspend fun listMessages(
        token: String,
        query: String,
        max: Int,
        scope: CacheScope,
    ): Listing = try {
        val ids = GmailApi.list(token, query, max)
        Listing(metadataFor(token, ids), fromCache = false)
    } catch (e: GmailApi.Unauthorized) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "network list failed for \"$query\", serving cache: ${e.message}")
        Listing(cachedSlice(scope, max), fromCache = true)
    }

    /** Count of unread inbox mail from the last sync — used for the offline fallback. */
    suspend fun unreadCountCached(): Int = dao.unreadInbox().size

    private suspend fun cachedSlice(scope: CacheScope, max: Int): List<GmailApi.Message> = when (scope) {
        CacheScope.RECENT_INBOX -> dao.recentInbox(max)
        CacheScope.UNREAD_INBOX -> dao.unreadInbox()
        CacheScope.IMPORTANT_UNREAD -> dao.importantUnread()
    }.map { it.toMessage() }

    // ---------- mutation write-through ----------

    suspend fun onMarkedRead(ids: List<String>) = ids.forEach { id ->
        dao.get(id)?.let { row ->
            val labels = row.labelSet() - "UNREAD"
            dao.updateLabels(id, labels.joinToString(","), unread = false, inInbox = row.inInbox)
        }
    }

    suspend fun onArchived(ids: List<String>) = ids.forEach { id ->
        dao.get(id)?.let { row ->
            val labels = row.labelSet() - "INBOX"
            dao.updateLabels(id, labels.joinToString(","), unread = row.unread, inInbox = false)
        }
    }

    suspend fun onTrashed(ids: List<String>) = ids.forEach { dao.delete(it) }

    // ---------- incremental sync ----------

    /**
     * Best-effort: apply changes since the last checkpoint to cached rows, prune stale metadata,
     * and advance the checkpoint. On the first run it just records a baseline historyId; the cache
     * then populates lazily through read-through. Never throws — sync must not break a command.
     */
    suspend fun refresh(token: String) {
        try {
            val state = dao.syncState()
            if (state == null) {
                val baseline = GmailApi.profileHistoryId(token)
                if (baseline > 0) dao.setSyncState(EmailSyncStateEntity(historyId = baseline, lastSyncAt = now()))
                return
            }
            val page = GmailApi.history(token, state.historyId)
            if (page.tooOld) {
                // Checkpoint expired: cached label state may be stale. Drop it and re-baseline.
                dao.clearAll()
                val baseline = GmailApi.profileHistoryId(token).takeIf { it > 0 } ?: state.historyId
                dao.setSyncState(EmailSyncStateEntity(historyId = baseline, lastSyncAt = now()))
                return
            }
            page.events.forEach { applyEvent(it) }
            dao.pruneMetadataOlderThan(now() - METADATA_TTL_MS)
            dao.setSyncState(state.copy(historyId = page.newHistoryId, lastSyncAt = now()))
        } catch (e: GmailApi.Unauthorized) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "incremental refresh failed (non-fatal)", t)
        }
    }

    /** Apply a history event to the cache. Only rows we already track are updated. */
    private suspend fun applyEvent(e: GmailApi.HistoryEvent) {
        if (e is GmailApi.HistoryEvent.Deleted) { dao.delete(e.id); return }
        val row = dao.get(e.id) ?: return
        val labels: Set<String> = when (e) {
            is GmailApi.HistoryEvent.Added -> e.labelIds.toSet()
            is GmailApi.HistoryEvent.LabelsAdded -> row.labelSet() + e.labelIds
            is GmailApi.HistoryEvent.LabelsRemoved -> row.labelSet() - e.labelIds.toSet()
            is GmailApi.HistoryEvent.Deleted -> return
        }
        dao.updateLabels(
            e.id, labels.joinToString(","),
            unread = labels.contains("UNREAD"), inInbox = labels.contains("INBOX"),
        )
    }

    // ---------- mapping ----------

    private fun now(): Long = System.currentTimeMillis()

    private fun CachedEmailEntity.toMessage() = GmailApi.Message(
        id = id, from = from, subject = subject, snippet = snippet,
        unread = unread, internalDateMs = internalDateMs, labelIds = labelSet().toList(),
    )

    private fun GmailApi.Message.toEntity(stamp: Long) = CachedEmailEntity(
        id = id, from = from, subject = subject, snippet = snippet,
        labels = labelIds.joinToString(","),
        unread = unread, inInbox = labelIds.contains("INBOX"),
        internalDateMs = internalDateMs,
        body = null, bodyFetchedAt = null, cachedAt = stamp,
    )
}
