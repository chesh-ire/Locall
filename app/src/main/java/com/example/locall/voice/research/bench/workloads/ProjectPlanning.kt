package com.example.locall.voice.research.bench.workloads

import android.content.Context
import com.example.locall.voice.research.bench.BenchConfig
import com.example.locall.voice.research.bench.BenchText
import com.example.locall.voice.research.bench.InferenceMeter
import com.example.locall.voice.research.bench.WorkloadType

object ProjectPlanning : Workload {

    override val type = WorkloadType.PROJECT_PLANNING

    private const val NEW_CONSTRAINT =
        "A key dependency slips by two weeks, the team shrinks to one engineer, and an on-device " +
            "privacy review is now required before any data leaves the app."

    override suspend fun run(
        context: Context,
        config: BenchConfig,
        meter: InferenceMeter,
        input: String,
        onStage: (String) -> Unit,
    ): WorkloadOutput {
        val goal = input.ifBlank { Workload.defaultInput(WorkloadType.PROJECT_PLANNING) }

        onStage("Gathering context for the goal…")
        val related = runCatching { Corpus.topK(context, goal, config.topK) }.getOrDefault(emptyList())
        val sources = related.map { it.citation }.distinct()
        val contextText = BenchText.joinToBudget(
            related.map { BenchText.render(it, 500) }, config.contextBudgetChars,
        )

        onStage("Decomposing the goal into tasks…")
        val decomposition = meter.generate(
            stage = "decompose",
            systemPrompt = DECOMPOSE_SYSTEM,
            userPrompt = buildString {
                append("Goal: ").append(goal)
                if (contextText.isNotBlank()) append("\n\nRelevant context from the user's documents:\n").append(contextText)
            },
            maxTokens = config.reportOutputTokens,
        )

        onStage("Prioritising tasks and finding blockers…")
        val prioritised = meter.generate(
            stage = "prioritise",
            systemPrompt = PRIORITISE_SYSTEM,
            userPrompt = "Goal: $goal\n\nTask breakdown with dependencies:\n$decomposition",
            maxTokens = config.reportOutputTokens,
        )

        onStage("Replanning for the new constraint…")
        val replan = meter.generate(
            stage = "replan",
            systemPrompt = REPLAN_SYSTEM,
            userPrompt = "Goal: $goal\n\nCurrent prioritised plan:\n$prioritised\n\nNew constraint:\n$NEW_CONSTRAINT",
            maxTokens = config.reportOutputTokens,
        )

        val output = buildString {
            append("## Task breakdown\n").append(decomposition)
            append("\n\n## Prioritised plan & blockers\n").append(prioritised)
            append("\n\n## Revised plan (new constraint)\n").append(replan)
        }
        return WorkloadOutput(output, sources)
    }

    private val DECOMPOSE_SYSTEM = """
        You are a planning assistant. Break the user's goal into concrete tasks. For each task give a short
        id (T1, T2, …), a one-line description, and its dependencies (the ids it depends on, or "none").
        Output one task per line as: T<id>: <description> [depends: <ids or none>]. Be specific and
        realistic; cover the whole goal end to end.
    """.trimIndent()

    private val PRIORITISE_SYSTEM = """
        You are given a goal and a task breakdown with dependencies. Produce a prioritised execution order
        that respects the dependencies, grouped into phases. Then list BLOCKERS: tasks that are on the
        critical path or cannot start until something else is done, and why. Be concise.
    """.trimIndent()

    private val REPLAN_SYSTEM = """
        You are given a goal, a current prioritised plan, and a NEW CONSTRAINT that has just emerged.
        Produce a revised plan: what must be re-sequenced, de-scoped or added because of the constraint,
        the updated critical path, and the key risks introduced. Keep it actionable.
    """.trimIndent()
}
