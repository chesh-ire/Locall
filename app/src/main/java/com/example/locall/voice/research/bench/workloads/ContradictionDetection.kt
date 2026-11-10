package com.example.locall.voice.research.bench.workloads

import android.content.Context
import com.example.locall.voice.research.bench.BenchConfig
import com.example.locall.voice.research.bench.BenchText
import com.example.locall.voice.research.bench.InferenceMeter
import com.example.locall.voice.research.bench.WorkloadType
import com.example.locall.voice.research.data.RetrievableChunk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ContradictionDetection : Workload {

    override val type = WorkloadType.CONTRADICTION_DETECTION

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override suspend fun run(
        context: Context,
        config: BenchConfig,
        meter: InferenceMeter,
        input: String,
        onStage: (String) -> Unit,
    ): WorkloadOutput {
        val selected = Corpus.recent(context, config.chunkCount)
        val sources = selected.map { it.citation }.distinct()
        onStage("Scanning ${selected.size} chunk(s) for conflicts…")

        val claims = selected.map { render(it) }
        val batches = BenchText.packByChars(claims, config.contextBudgetChars)

        val flags = ArrayList<String>()
        batches.forEachIndexed { i, batch ->
            onStage("Comparing batch ${i + 1}/${batches.size}")
            val scan = meter.generate(
                stage = "scan ${i + 1}/${batches.size}",
                systemPrompt = SCAN_SYSTEM,
                userPrompt = batch.joinToString("\n\n"),
                maxTokens = config.stageOutputTokens,
            )
            if (scan.isNotBlank() && !isNoConflict(scan)) flags.add(scan)
        }

        if (flags.isEmpty()) {
            return WorkloadOutput("No contradictions or superseded decisions were found across your documents.", sources)
        }

        onStage("Resolving ${flags.size} flagged conflict set(s)…")
        val resolution = meter.generate(
            stage = "resolve",
            systemPrompt = RESOLVE_SYSTEM,
            userPrompt = "Conflicts found while scanning the documents:\n\n" +
                BenchText.joinToBudget(flags, config.contextBudgetChars),
            maxTokens = config.reportOutputTokens,
        )
        return WorkloadOutput(resolution.ifBlank { flags.joinToString("\n\n") }, sources)
    }

    private fun render(chunk: RetrievableChunk): String {
        val date = runCatching { dateFmt.format(Date(chunk.createdAtMs)) }.getOrDefault("undated")
        val lines = buildList {
            if (chunk.summary.isNotBlank()) add(chunk.summary)
            chunk.decisions.forEach { add("DECISION: $it") }
            chunk.dates.forEach { add("DATE: $it") }
            chunk.commitments.forEach { add("COMMITMENT: $it") }
            if (isEmpty()) add(chunk.text.take(600))
        }
        return "${chunk.citation} (dated $date):\n${lines.joinToString("\n")}"
    }

    private fun isNoConflict(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("no contradiction") || t.contains("no conflict") || t == "none"
    }

    private val SCAN_SYSTEM = """
        You are given dated, sourced claim sets from the user's documents, each tagged with its [source] and
        date. Find places where the claims CONTRADICT each other or where a later-dated statement supersedes
        an earlier decision. For each, state both positions with their [source] tags and dates. If there are
        no contradictions in this batch, reply with exactly: NO CONTRADICTIONS. Use only the given claims.
    """.trimIndent()

    private val RESOLVE_SYSTEM = """
        You are given conflicts detected across the user's documents, each citing [sources] and dates.
        For each distinct conflict, determine the LATEST supported position (prefer the most recent dated
        source) and present it as: the resolved position, then the superseded one(s), each with [source]
        references. Group clearly. Use only the given material; never invent facts or sources.
    """.trimIndent()
}
