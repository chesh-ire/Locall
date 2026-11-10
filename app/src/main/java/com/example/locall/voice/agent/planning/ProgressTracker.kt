package com.example.locall.voice.agent.planning

import com.example.locall.voice.agent.data.GoalManager
import com.example.locall.voice.agent.data.TaskEntity
import com.example.locall.voice.agent.data.TaskManager
import com.example.locall.voice.agent.data.TaskStatus

class ProgressTracker(
    private val tasks: TaskManager,
    private val goals: GoalManager,
) {

    data class Progress(
        val goalId: String,
        val total: Int,
        val done: Int,
        val open: Int,
        val overdue: Int,
        val nextTask: TaskEntity?,
        val needsReplan: Boolean,
    ) {
        val pct: Int get() = if (total == 0) 0 else (done * 100) / total
        val isComplete: Boolean get() = total > 0 && done == total
    }

    suspend fun forGoal(goalId: String, now: Long = System.currentTimeMillis()): Progress {
        val all = tasks.forGoal(goalId)
        val done = all.count { it.status == TaskStatus.DONE }
        val open = all.filter { it.isOpen }
        val overdue = open.count { it.dueAtMs != null && it.dueAtMs < now }
        val next = nextActionable(all, now)
        val needsReplan = overdue > 0
        return Progress(goalId, all.size, done, open.size, overdue, next, needsReplan)
    }

    private fun nextActionable(all: List<TaskEntity>, now: Long): TaskEntity? {
        val doneIds = all.filter { it.status == TaskStatus.DONE }.map { it.id }.toSet()
        return all.filter { it.isOpen }
            .filter { it.dependsOnIds().all { dep -> dep in doneIds } }
            .minByOrNull { (it.dueAtMs ?: Long.MAX_VALUE) }
    }

    suspend fun maybeCompleteGoal(goalId: String, now: Long = System.currentTimeMillis()): Boolean {
        val p = forGoal(goalId, now)
        if (p.isComplete) {
            goals.complete(goalId, now)
            return true
        }
        return false
    }
}
