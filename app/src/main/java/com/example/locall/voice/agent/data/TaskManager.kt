package com.example.locall.voice.agent.data

import android.content.Context
import java.util.UUID

class TaskManager(private val dao: AgentDao) {

    suspend fun create(
        title: String,
        detail: String = "",
        priority: Int = TaskPriority.NORMAL,
        dueAtMs: Long? = null,
        goalId: String? = null,
        sourceInboxId: String? = null,
        dependsOn: List<String> = emptyList(),
        orderInPlan: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): TaskEntity {
        val task = TaskEntity(
            id = "task:" + UUID.randomUUID().toString().take(8),
            title = title.trim(),
            detail = detail.trim(),
            status = TaskStatus.OPEN,
            priority = priority.coerceIn(TaskPriority.LOW, TaskPriority.URGENT),
            dueAtMs = dueAtMs,
            reminderAtMs = null,
            goalId = goalId,
            sourceInboxId = sourceInboxId,
            dependsOn = dependsOn.joinToString(","),
            orderInPlan = orderInPlan,
            createdAtMs = now,
            updatedAtMs = now,
            completedAtMs = null,
        )
        dao.upsertTask(task)
        return task
    }

    suspend fun get(id: String): TaskEntity? = dao.task(id)

    suspend fun update(
        id: String,
        title: String? = null,
        detail: String? = null,
        priority: Int? = null,
        dueAtMs: Long? = null,
        clearDue: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): TaskEntity? {
        val cur = dao.task(id) ?: return null
        val updated = cur.copy(
            title = title?.trim() ?: cur.title,
            detail = detail?.trim() ?: cur.detail,
            priority = priority?.coerceIn(TaskPriority.LOW, TaskPriority.URGENT) ?: cur.priority,
            dueAtMs = if (clearDue) null else (dueAtMs ?: cur.dueAtMs),
            updatedAtMs = now,
        )
        dao.upsertTask(updated)
        return updated
    }

    suspend fun complete(id: String, now: Long = System.currentTimeMillis()): TaskEntity? {
        val cur = dao.task(id) ?: return null
        val done = cur.copy(status = TaskStatus.DONE, completedAtMs = now, updatedAtMs = now, reminderAtMs = null)
        dao.upsertTask(done)
        return done
    }

    suspend fun postpone(id: String, untilMs: Long?, now: Long = System.currentTimeMillis()): TaskEntity? {
        val cur = dao.task(id) ?: return null
        val moved = cur.copy(
            status = TaskStatus.POSTPONED,
            dueAtMs = untilMs ?: cur.dueAtMs,
            updatedAtMs = now,
        )
        dao.upsertTask(moved)
        return moved
    }

    suspend fun reopen(id: String, now: Long = System.currentTimeMillis()): TaskEntity? {
        val cur = dao.task(id) ?: return null
        val open = cur.copy(status = TaskStatus.OPEN, completedAtMs = null, updatedAtMs = now)
        dao.upsertTask(open)
        return open
    }

    suspend fun setReminder(id: String, atMs: Long?, now: Long = System.currentTimeMillis()): TaskEntity? {
        val cur = dao.task(id) ?: return null
        val updated = cur.copy(reminderAtMs = atMs, updatedAtMs = now)
        dao.upsertTask(updated)
        return updated
    }

    suspend fun delete(id: String) = dao.deleteTask(id)

    suspend fun open(): List<TaskEntity> = dao.tasksByStatuses(listOf(TaskStatus.OPEN, TaskStatus.POSTPONED))

    suspend fun all(): List<TaskEntity> = dao.allTasks()

    suspend fun forGoal(goalId: String): List<TaskEntity> = dao.tasksForGoal(goalId)

    suspend fun forInboxItem(inboxId: String): List<TaskEntity> = dao.tasksForInboxItem(inboxId)

    suspend fun withReminders(): List<TaskEntity> = dao.tasksWithReminders()

    suspend fun overdue(now: Long = System.currentTimeMillis()): List<TaskEntity> =
        open().filter { it.dueAtMs != null && it.dueAtMs < now }

    suspend fun dueWithin(windowMs: Long, now: Long = System.currentTimeMillis()): List<TaskEntity> =
        open().filter { it.dueAtMs != null && it.dueAtMs in now..(now + windowMs) }

    companion object {
        @Volatile private var instance: TaskManager? = null
        fun get(context: Context): TaskManager =
            instance ?: synchronized(this) {
                instance ?: TaskManager(AgentDatabase.get(context).dao()).also { instance = it }
            }
    }
}
