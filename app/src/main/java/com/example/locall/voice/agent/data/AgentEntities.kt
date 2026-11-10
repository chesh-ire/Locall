package com.example.locall.voice.agent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object InboxSource {
    const val GMAIL = "gmail"
    const val WHATSAPP = "whatsapp"
    const val SMS = "sms"
    const val OTHER = "other"
}

object MessageClass {
    const val UNCLASSIFIED = ""
    const val NEEDS_REPLY = "needs_reply"
    const val ACTION = "action"
    const val INFO = "info"
    const val NONE = "none"

    val ALL = listOf(NEEDS_REPLY, ACTION, INFO, NONE)

    fun label(c: String): String = when (c) {
        NEEDS_REPLY -> "needs a reply"
        ACTION -> "needs an action"
        INFO -> "informational"
        NONE -> "no action needed"
        else -> "unclassified"
    }
}

object ReviewStatus {
    const val NEW = "new"
    const val BRIEFED = "briefed"
    const val REVIEWED = "reviewed"
    const val ARCHIVED = "archived"
}

@Entity(
    tableName = "agent_inbox",
    indices = [Index("receivedAtMs"), Index("reviewStatus")],
)
data class InboxItemEntity(
    @PrimaryKey val id: String,
    val source: String,
    val sender: String,
    val title: String,
    val preview: String,
    val threadRef: String,
    val classification: String,
    val summary: String,
    val reviewStatus: String,
    val handled: Boolean,
    val receivedAtMs: Long,
    val capturedAtMs: Long,
) {
    companion object {
        const val PREVIEW_CHARS = 280
    }
}

object GoalStatus {
    const val ACTIVE = "active"
    const val COMPLETED = "completed"
    const val ABANDONED = "abandoned"
}

object TaskStatus {
    const val OPEN = "open"
    const val DONE = "done"
    const val POSTPONED = "postponed"
    const val CANCELLED = "cancelled"
}

object TaskPriority {
    const val LOW = 1
    const val NORMAL = 2
    const val HIGH = 3
    const val URGENT = 4

    fun label(p: Int): String = when {
        p >= URGENT -> "urgent"
        p == HIGH -> "high"
        p == LOW -> "low"
        else -> "normal"
    }
}

@Entity(tableName = "agent_goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val detail: String,
    val status: String,
    val targetDateMs: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "agent_tasks",
    indices = [Index("status"), Index("goalId"), Index("dueAtMs")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val detail: String,
    val status: String,
    val priority: Int,
    val dueAtMs: Long?,
    val reminderAtMs: Long?,
    val goalId: String?,
    val sourceInboxId: String?,
    val dependsOn: String,
    val orderInPlan: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val completedAtMs: Long?,
) {
    fun dependsOnIds(): List<String> =
        dependsOn.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val isOpen: Boolean get() = status == TaskStatus.OPEN || status == TaskStatus.POSTPONED
}
