package com.example.locall.voice.email.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One compact, incrementally-maintained summary of a Gmail thread. Keyed by threadId. The set of
 * message ids already folded into [summary] is tracked in [coveredIds] (CSV) so a re-ask only feeds
 * genuinely new messages to the model — and an unchanged thread is answered with no inference at all.
 *
 * [decisions], [actions] and [deadlines] are the structured extracts, stored newline-joined (one
 * item per line) so they parse back deterministically without a JSON pass.
 */
@Entity(tableName = "thread_summaries")
data class ThreadSummaryEntity(
    @PrimaryKey val threadId: String,
    val summary: String,
    val decisions: String,              // newline-joined list
    val actions: String,                // newline-joined list
    val deadlines: String,              // newline-joined list
    val coveredIds: String,             // CSV of message ids folded into the summary
    val messageCount: Int,
    val latestInternalDateMs: Long,
    val updatedAt: Long,
) {
    fun coveredIdSet(): Set<String> =
        coveredIds.split(',').filter { it.isNotBlank() }.toSet()

    fun decisionList(): List<String> = lines(decisions)
    fun actionList(): List<String> = lines(actions)
    fun deadlineList(): List<String> = lines(deadlines)

    private fun lines(s: String): List<String> =
        s.split('\n').map { it.trim() }.filter { it.isNotBlank() }
}
