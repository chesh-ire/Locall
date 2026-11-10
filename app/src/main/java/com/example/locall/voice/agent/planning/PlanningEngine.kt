package com.example.locall.voice.agent.planning

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.agent.data.GoalEntity
import com.example.locall.voice.agent.data.TaskEntity
import com.example.locall.voice.agent.data.TaskManager
import com.example.locall.voice.agent.data.TaskPriority

private const val TAG = "PlanningEngine"

class PlanningEngine(private val tasks: TaskManager) {

    data class Step(
        val title: String,
        val detail: String,
        val dayOffset: Int,
        val priority: Int,
        val dependsOnIndex: Int?,
    )

    data class Plan(
        val goalTitle: String,
        val horizonDays: Int,
        val steps: List<Step>,
    ) {
        val isEmpty get() = steps.isEmpty()
    }

    private val SYSTEM = """
        You are a planning assistant. Break the user's GOAL into a concrete, ordered list of steps.
        Output one step per line, max 12 steps, in this exact form:
        STEP: <short actionable task> | DAY: <day number from 1> | PRIORITY: <low|normal|high> | AFTER: <earlier step number or none>
        Rules:
        - Spread the DAY numbers across the available horizon; earlier, foundational steps get smaller days.
        - Use AFTER only for a genuine dependency (a step that can't start until an earlier one is done).
        - Keep steps specific and doable. Do not add commentary or a preamble.
    """.trimIndent()

    suspend fun propose(
        context: Context,
        goalTitle: String,
        goalDetail: String,
        horizonDays: Int = 7,
        onStatus: (String) -> Unit = {},
    ): Plan {
        onStatus("Loading AI model…")
        LlmBridge.ensureLoaded(context, onStatus)
        onStatus("Planning…")
        val user = buildString {
            append("GOAL: ").append(goalTitle)
            if (goalDetail.isNotBlank()) append("\nDETAIL: ").append(goalDetail)
            append("\nHORIZON: ").append(horizonDays).append(" days")
        }
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val raw = try {
            LlmBridge.generateText(
                systemPrompt = SYSTEM, userPrompt = user,
                temperature = 0.4f, maxTokens = 360, decodeThreads = decodeThreads,
            ).trim()
        } catch (t: Throwable) {
            Log.w(TAG, "propose failed", t); ""
        }
        val steps = parse(raw, horizonDays)
        Log.i(TAG, "proposed ${steps.size} steps for \"$goalTitle\"")
        return Plan(goalTitle, horizonDays, steps)
    }

    fun parse(raw: String, horizonDays: Int): List<Step> {
        val lines = raw.split('\n')
            .map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .filter { it.contains("STEP:", ignoreCase = true) }
        val n = lines.size.coerceAtLeast(1)
        return lines.mapIndexedNotNull { i, line ->
            val title = field(line, "STEP")?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val day = field(line, "DAY")?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                ?: ((i * horizonDays / n) + 1)
            val priority = when (field(line, "PRIORITY")?.lowercase()) {
                "high", "urgent" -> TaskPriority.HIGH
                "low" -> TaskPriority.LOW
                else -> TaskPriority.NORMAL
            }
            val after = field(line, "AFTER")?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                ?.let { it - 1 }?.takeIf { it in 0 until i }
            Step(title.take(120), "", day.coerceIn(1, horizonDays.coerceAtLeast(1)), priority, after)
        }
    }

    suspend fun commit(goal: GoalEntity, plan: Plan, now: Long = System.currentTimeMillis()): List<TaskEntity> {
        val created = ArrayList<TaskEntity>(plan.steps.size)
        plan.steps.forEachIndexed { i, step ->
            val due = now + step.dayOffset.toLong() * DAY_MS
            val deps = step.dependsOnIndex?.let { created.getOrNull(it)?.id }?.let { listOf(it) } ?: emptyList()
            created += tasks.create(
                title = step.title,
                detail = step.detail,
                priority = step.priority,
                dueAtMs = due,
                goalId = goal.id,
                dependsOn = deps,
                orderInPlan = i,
                now = now,
            )
        }
        return created
    }

    private fun field(line: String, name: String): String? {
        val idx = line.indexOf("$name:", ignoreCase = true)
        if (idx < 0) return null
        val from = idx + name.length + 1
        val end = line.indexOf('|', from).let { if (it < 0) line.length else it }
        return line.substring(from, end).trim()
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
