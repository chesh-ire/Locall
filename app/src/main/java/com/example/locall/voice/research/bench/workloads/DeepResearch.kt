package com.example.locall.voice.research.bench.workloads

import android.content.Context
import android.util.Log
import com.example.locall.voice.research.bench.BenchConfig
import com.example.locall.voice.research.bench.BenchText
import com.example.locall.voice.research.bench.InferenceMeter
import com.example.locall.voice.research.bench.WorkloadType

private const val TAG = "DeepResearch"

object DeepResearch : Workload {

    override val type = WorkloadType.DEEP_RESEARCH

    data class Evidence(val citation: String, val facts: String)
    data class DeepResult(val output: WorkloadOutput, val evidence: List<Evidence>)

    override suspend fun run(
        context: Context,
        config: BenchConfig,
        meter: InferenceMeter,
        input: String,
        onStage: (String) -> Unit,
    ): WorkloadOutput = research(context, config, meter, input, onStage).output

    suspend fun research(
        context: Context,
        config: BenchConfig,
        meter: InferenceMeter,
        question: String,
        onStage: (String) -> Unit,
    ): DeepResult {
        val q = question.ifBlank { Workload.defaultInput(WorkloadType.DEEP_RESEARCH) }
        onStage("Retrieving sources…")
        val top = Corpus.topK(context, q, config.topK)
        onStage("Reading ${top.size} source(s)…")

        val perSourceChars = config.contextBudgetChars.coerceAtMost(1800)
        val evidence = ArrayList<Evidence>()
        top.forEachIndexed { i, chunk ->
            onStage("Extracting from source ${i + 1}/${top.size}")
            val facts = meter.generate(
                stage = "extract ${i + 1}/${top.size}",
                systemPrompt = EXTRACT_SYSTEM,
                userPrompt = "Question: $q\n\nSource ${chunk.citation}:\n${chunk.text.take(perSourceChars)}",
                maxTokens = config.stageOutputTokens,
            )
            if (facts.isNotBlank() && !facts.equals("NONE", ignoreCase = true)) {
                evidence += Evidence(chunk.citation, facts)
            }
        }
        if (evidence.isEmpty()) {
            return DeepResult(
                WorkloadOutput("I read the most relevant documents but found nothing that answers that.", emptyList()),
                emptyList(),
            )
        }

        var contradictionNotes = ""
        if (evidence.size >= 2) {
            onStage("Detecting contradictions…")
            contradictionNotes = meter.generate(
                stage = "contradictions",
                systemPrompt = CONTRADICTION_SYSTEM,
                userPrompt = taggedEvidence(evidence, config.contextBudgetChars),
                maxTokens = config.stageOutputTokens,
            )
        }

        onStage("Consolidating evidence…")
        val material = condenseToFit(meter, config, evidence.map { "${it.citation}\n${it.facts}" })

        onStage("Writing report…")
        val report = meter.generate(
            stage = "report",
            systemPrompt = REPORT_SYSTEM,
            userPrompt = buildString {
                append("Research question: ").append(q).append("\n\nEvidence (each tagged with its [source]):\n")
                append(material)
                if (contradictionNotes.isNotBlank()) {
                    append("\n\nCross-source contradiction notes:\n").append(contradictionNotes)
                }
            },
            maxTokens = config.reportOutputTokens,
        )

        val sources = evidence.map { it.citation }.distinct()
        Log.i(TAG, "deep research: ${top.size} retrieved, ${evidence.size} with evidence, ${meter.records.size} passes")
        return DeepResult(WorkloadOutput(report.ifBlank { material }, sources), evidence)
    }

    suspend fun followUp(
        meter: InferenceMeter,
        config: BenchConfig,
        question: String,
        evidence: List<Evidence>,
        onStage: (String) -> Unit,
    ): String {
        if (evidence.isEmpty()) return "Run a Deep Research pass first — there's no evidence to follow up on."
        onStage("Answering follow-up from existing evidence…")
        val material = condenseToFit(meter, config, evidence.map { "${it.citation}\n${it.facts}" })
        return meter.generate(
            stage = "follow-up",
            systemPrompt = FOLLOWUP_SYSTEM,
            userPrompt = "Follow-up question: $question\n\nEvidence (each tagged with its [source]):\n$material",
            maxTokens = config.reportOutputTokens,
        )
    }

    private fun taggedEvidence(evidence: List<Evidence>, budgetChars: Int): String =
        BenchText.joinToBudget(evidence.map { "${it.citation}\n${it.facts}" }, budgetChars)

    private suspend fun condenseToFit(
        meter: InferenceMeter,
        config: BenchConfig,
        items: List<String>,
    ): String {
        var current = items
        var pass = 0
        while (current.joinToString("\n").length > config.contextBudgetChars && current.size > 1) {
            val batches = BenchText.packByChars(current, config.contextBudgetChars / 2)
            if (batches.size >= current.size) break
            current = batches.mapIndexed { i, batch ->
                pass++
                meter.generate(
                    stage = "condense p$pass ${i + 1}/${batches.size}",
                    systemPrompt = CONDENSE_SYSTEM,
                    userPrompt = batch.joinToString("\n\n"),
                    maxTokens = config.stageOutputTokens,
                ).ifBlank { batch.joinToString("\n").take(config.contextBudgetChars / 2) }
            }
        }
        return current.joinToString("\n\n")
    }

    private val EXTRACT_SYSTEM = """
        You are given a question and ONE source excerpt from the user's documents. Extract ONLY the facts in
        the excerpt that help answer the question, as short bullet points. If nothing in the excerpt is
        relevant, reply with exactly: NONE. Never add information that isn't in the excerpt.
    """.trimIndent()

    private val CONTRADICTION_SYSTEM = """
        You are given facts extracted from several of the user's documents, each tagged with its source in
        [brackets]. Identify where the sources CONTRADICT each other or where a later statement supersedes
        an earlier decision. For each conflict, state both positions with their [source] tags. If there are
        no contradictions, say so plainly. Use only the given facts.
    """.trimIndent()

    private val CONDENSE_SYSTEM = """
        Condense these tagged facts into fewer lines, preserving the [source] tags and all key content.
        Do not add anything new.
    """.trimIndent()

    private val REPORT_SYSTEM = """
        You are a research assistant writing a thorough, well-structured report that answers the user's
        question using ONLY the provided evidence, which is tagged with [source] markers. Write in clear
        prose with short section headings where helpful. Cite the [sources] you rely on inline. If the
        evidence is incomplete or sources disagree, say so explicitly. Never invent facts or sources.
    """.trimIndent()

    private val FOLLOWUP_SYSTEM = """
        You answer a follow-up question using ONLY the previously gathered evidence, which is tagged with
        [source] markers. Be direct, cite the [sources] you use, and say if the evidence doesn't cover it.
    """.trimIndent()
}
