package com.example.locall.voice.agent.data

class InboxRepository(private val dao: AgentDao) {

    suspend fun upsert(item: InboxItemEntity): Boolean {
        val bounded = item.copy(preview = item.preview.take(InboxItemEntity.PREVIEW_CHARS))
        return dao.insertInboxItemIfAbsent(bounded) != -1L
    }

    suspend fun get(id: String): InboxItemEntity? = dao.inboxItem(id)

    suspend fun save(item: InboxItemEntity) = dao.updateInboxItem(item)

    suspend fun unprocessed(limit: Int = 30): List<InboxItemEntity> =
        dao.inboxByStatus(ReviewStatus.NEW, limit)

    suspend fun recent(limit: Int = 30): List<InboxItemEntity> = dao.recentInbox(limit)

    suspend fun newCount(): Int = dao.inboxCountByStatus(ReviewStatus.NEW)

    suspend fun setClassification(id: String, classification: String, summary: String) {
        val cur = dao.inboxItem(id) ?: return
        dao.updateInboxItem(cur.copy(classification = classification, summary = summary))
    }

    suspend fun setStatus(id: String, status: String) {
        val cur = dao.inboxItem(id) ?: return
        dao.updateInboxItem(cur.copy(reviewStatus = status))
    }

    suspend fun markHandled(id: String) {
        val cur = dao.inboxItem(id) ?: return
        dao.updateInboxItem(cur.copy(handled = true, reviewStatus = ReviewStatus.REVIEWED))
    }

    suspend fun prune(retentionMs: Long = DEFAULT_RETENTION_MS, now: Long = System.currentTimeMillis()): Int =
        dao.pruneInboxOlderThan(now - retentionMs, ReviewStatus.NEW)

    suspend fun clear() = dao.clearInbox()

    companion object {
        const val DEFAULT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
