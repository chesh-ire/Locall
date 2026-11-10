package com.example.locall.voice.agent.orchestrator

import android.content.Context
import com.example.locall.voice.agent.data.GoalManager
import com.example.locall.voice.agent.data.TaskManager
import com.example.locall.voice.agent.data.TaskPriority
import com.example.locall.voice.agent.data.TaskStatus
import com.example.locall.voice.memory.MemoryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContextBuilder(
    private val tasks: TaskManager,
    private val goals: GoalManager,
) {

    suspend fun stateBlock(context: Context, query: String, now: Long = System.currentTimeMillis()): String {
        val open = tasks.open().take(12)
        val activeGoals = goals.active().take(5)
        val memory = runCatching { MemoryRepository.get(context).relevantPromptBlock(query) }.getOrDefault("")
        return buildString {
            if (memory.isNotBlank()) append(memory).append('\n')
            if (activeGoals.isNotEmpty()) {
                append("Active goals:\n")
                activeGoals.forEach { append("- ").append(it.title).append('\n') }
            }
            if (open.isNotEmpty()) {
                append("Open tasks:\n")
                open.forEach { t ->
                    append("- ").append(t.title)
                    if (t.priority >= TaskPriority.HIGH) append(" [").append(TaskPriority.label(t.priority)).append(']')
                    t.dueAtMs?.let { append(" (due ").append(fmtDate(it)).append(')') }
                    append('\n')
                }
            }
            if (open.isEmpty() && activeGoals.isEmpty()) append("No open tasks or active goals.\n")
        }.trim()
    }

    suspend fun taskList(now: Long = System.currentTimeMillis()): Pair<String, List<String>> {
        val open = tasks.open()
        if (open.isEmpty()) return "You have no open tasks." to emptyList()
        val lines = open.take(10).mapIndexed { i, t ->
            val due = t.dueAtMs?.let {
                when {
                    it < now -> " — overdue"
                    else -> " — due ${fmtDate(it)}"
                }
            }.orEmpty()
            "${i + 1}. ${t.title}$due"
        }
        return lines.joinToString("\n") to open.take(10).map { it.id }
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        fun fmtDate(ms: Long): String = DATE_FMT.format(Date(ms))

        fun isDone(status: String) = status == TaskStatus.DONE
    }

    private fun fmtDate(ms: Long) = DATE_FMT.format(Date(ms))
}
