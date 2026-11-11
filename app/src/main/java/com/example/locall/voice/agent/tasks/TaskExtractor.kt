package com.example.locall.voice.agent.tasks

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.agent.data.InboxItemEntity
import com.example.locall.voice.agent.data.TaskEntity
import com.example.locall.voice.agent.data.TaskManager
import com.example.locall.voice.agent.data.TaskPriority
import com.example.locall.voice.agent.util.DateParser

private const val TAG = "TaskExtractor"

class TaskExtractor(private val tasks: TaskManager) {

    data class Extracted(val title: String, val priority: Int, val duePhrase: String, val dueAtMs: Long?)

    private val SYSTEM = """
        Extract ONLY concrete things the USER needs to do or has committed to, from the message below.
        Ignore things other people will do. Output one line per task, max 5 tasks, in this exact form:
        TASK: <short imperative> | DUE: <when, or none> | PRIORITY: <low|normal|high>
        If there is nothing for the user to do, output exactly: NONE
        Never invent tasks that aren't implied by the message.
    """.trimIndent()

    suspend fun extract(
        context: Context,
        item: InboxItemEntity,
        bodyText: String,
        now: Long = System.currentTimeMillis(),
    ): List<TaskEntity> {
        val content = bodyText.ifBlank { item.preview }
        if (content.isBlank()) return emptyList()
        val user = "From: ${item.sender}\nSubject: ${item.title}\n\n${content.take(1800)}"
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val raw = try {
            LlmBridge.generateText(
                systemPrompt = SYSTEM, userPrompt = user,
                temperature = 0.2f, maxTokens = 200, decodeThreads = decodeThreads,
                tag = "inbox.extract-tasks",
            ).trim()
        } catch (t: Throwable) {
            Log.w(TAG, "extract failed", t); return emptyList()
        }
        val parsed = parse(raw, now)
        return parsed.map { e ->
            tasks.create(
                title = e.title,
                detail = if (e.duePhrase.isNotBlank() && e.dueAtMs == null) "due: ${e.duePhrase}" else "",
                priority = e.priority,
                dueAtMs = e.dueAtMs,
                sourceInboxId = item.id,
                now = now,
            )
        }
    }

    fun parse(raw: String, now: Long): List<Extracted> {
        if (raw.trim().equals("NONE", ignoreCase = true)) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val l = line.trim().removePrefix("-").removePrefix("•").trim()
            if (!l.contains("TASK:", ignoreCase = true)) return@mapNotNull null
            val title = field(l, "TASK")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (title.equals("none", ignoreCase = true)) return@mapNotNull null
            val duePhrase = field(l, "DUE").orEmpty()
            val priority = when (field(l, "PRIORITY")?.lowercase()) {
                "high", "urgent" -> TaskPriority.HIGH
                "low" -> TaskPriority.LOW
                else -> TaskPriority.NORMAL
            }
            Extracted(title.take(120), priority, duePhrase, DateParser.parse(duePhrase, now))
        }.take(5)
    }

    private fun field(line: String, name: String): String? {
        val idx = line.indexOf("$name:", ignoreCase = true)
        if (idx < 0) return null
        val from = idx + name.length + 1
        val end = line.indexOf('|', from).let { if (it < 0) line.length else it }
        return line.substring(from, end).trim()
    }
}
