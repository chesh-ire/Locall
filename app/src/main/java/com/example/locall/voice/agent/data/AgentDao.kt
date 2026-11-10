package com.example.locall.voice.agent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AgentDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInboxItemIfAbsent(item: InboxItemEntity): Long

    @Update
    suspend fun updateInboxItem(item: InboxItemEntity)

    @Query("SELECT * FROM agent_inbox WHERE id = :id LIMIT 1")
    suspend fun inboxItem(id: String): InboxItemEntity?

    @Query("SELECT * FROM agent_inbox ORDER BY receivedAtMs DESC LIMIT :limit")
    suspend fun recentInbox(limit: Int): List<InboxItemEntity>

    @Query("SELECT * FROM agent_inbox WHERE reviewStatus = :status ORDER BY receivedAtMs DESC LIMIT :limit")
    suspend fun inboxByStatus(status: String, limit: Int): List<InboxItemEntity>

    @Query("SELECT COUNT(*) FROM agent_inbox WHERE reviewStatus = :status")
    suspend fun inboxCountByStatus(status: String): Int

    @Query("DELETE FROM agent_inbox WHERE capturedAtMs < :cutoff AND reviewStatus != :keepStatus")
    suspend fun pruneInboxOlderThan(cutoff: Long, keepStatus: String): Int

    @Query("DELETE FROM agent_inbox")
    suspend fun clearInbox()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoal(goal: GoalEntity)

    @Query("SELECT * FROM agent_goals WHERE id = :id LIMIT 1")
    suspend fun goal(id: String): GoalEntity?

    @Query("SELECT * FROM agent_goals WHERE status = :status ORDER BY updatedAtMs DESC")
    suspend fun goalsByStatus(status: String): List<GoalEntity>

    @Query("SELECT * FROM agent_goals ORDER BY updatedAtMs DESC")
    suspend fun allGoals(): List<GoalEntity>

    @Query("DELETE FROM agent_goals WHERE id = :id")
    suspend fun deleteGoal(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Query("SELECT * FROM agent_tasks WHERE id = :id LIMIT 1")
    suspend fun task(id: String): TaskEntity?

    @Query("SELECT * FROM agent_tasks ORDER BY updatedAtMs DESC")
    suspend fun allTasks(): List<TaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE status IN (:statuses) ORDER BY priority DESC, dueAtMs IS NULL, dueAtMs ASC, createdAtMs ASC")
    suspend fun tasksByStatuses(statuses: List<String>): List<TaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE goalId = :goalId ORDER BY orderInPlan ASC, createdAtMs ASC")
    suspend fun tasksForGoal(goalId: String): List<TaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE sourceInboxId = :inboxId")
    suspend fun tasksForInboxItem(inboxId: String): List<TaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE reminderAtMs IS NOT NULL AND status IN ('open','postponed') ORDER BY reminderAtMs ASC")
    suspend fun tasksWithReminders(): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM agent_tasks WHERE status = :status")
    suspend fun taskCountByStatus(status: String): Int

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM agent_tasks WHERE goalId = :goalId")
    suspend fun deleteTasksForGoal(goalId: String)
}
