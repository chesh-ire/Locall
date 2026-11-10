package com.example.locall.voice.agent.data

import android.content.Context
import java.util.UUID

class GoalManager(private val dao: AgentDao) {

    suspend fun create(
        title: String,
        detail: String = "",
        targetDateMs: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): GoalEntity {
        val goal = GoalEntity(
            id = "goal:" + UUID.randomUUID().toString().take(8),
            title = title.trim(),
            detail = detail.trim(),
            status = GoalStatus.ACTIVE,
            targetDateMs = targetDateMs,
            createdAtMs = now,
            updatedAtMs = now,
        )
        dao.upsertGoal(goal)
        return goal
    }

    suspend fun get(id: String): GoalEntity? = dao.goal(id)

    suspend fun active(): List<GoalEntity> = dao.goalsByStatus(GoalStatus.ACTIVE)

    suspend fun all(): List<GoalEntity> = dao.allGoals()

    suspend fun complete(id: String, now: Long = System.currentTimeMillis()): GoalEntity? =
        setStatus(id, GoalStatus.COMPLETED, now)

    suspend fun abandon(id: String, now: Long = System.currentTimeMillis()): GoalEntity? =
        setStatus(id, GoalStatus.ABANDONED, now)

    private suspend fun setStatus(id: String, status: String, now: Long): GoalEntity? {
        val cur = dao.goal(id) ?: return null
        val updated = cur.copy(status = status, updatedAtMs = now)
        dao.upsertGoal(updated)
        return updated
    }

    suspend fun delete(id: String) {
        dao.deleteTasksForGoal(id)
        dao.deleteGoal(id)
    }

    companion object {
        @Volatile private var instance: GoalManager? = null
        fun get(context: Context): GoalManager =
            instance ?: synchronized(this) {
                instance ?: GoalManager(AgentDatabase.get(context).dao()).also { instance = it }
            }
    }
}
