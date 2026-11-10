package com.example.locall.voice.research.bench.workloads

import android.content.Context
import android.util.Log
import com.example.locall.voice.research.bench.BenchConfig
import com.example.locall.voice.research.bench.BenchText
import com.example.locall.voice.research.bench.InferenceMeter
import com.example.locall.voice.research.bench.WorkloadType
import com.example.locall.voice.research.data.RetrievableChunk

private const val TAG = "RecursiveSummarisation"

object RecursiveSummarisation : Workload {

    override val type = WorkloadType.RECURSIVE_SUMMARISATION

    override suspend fun run(
        context: Context,
        config: BenchConfig,
        meter: InferenceMeter,
        input: String,
        onStage: (String) -> Unit,
    ): WorkloadOutput {
        val selected = Corpus.recent(context, config.chunkCount)
        val sources = selected.map { it.citation }.distinct()
        onStage("Retrieved ${selected.size} chunk(s) · ${sources.size} source(s)")

        val groups = BenchText.groupsOf(selected, config.groupSize)
        val perChunkChars = (config.contextBudgetChars / config.groupSize).coerceAtLeast(400)
        var level = 0
        var digests = ArrayList<String>(groups.size)
        groups.forEachIndexed { i, group ->
            onStage("Summarising group ${i + 1}/${groups.size} (${group.size} chunks)")
            val body = group.joinToString("\n\n") { BenchText.render(it, perChunkChars) }
            val d = meter.generate(
                stage = "leaf ${i + 1}/${groups.size}",
                systemPrompt = LEAF_SYSTEM,
                userPrompt = body,
                maxTokens = config.stageOutputTokens,
            )
            if (d.isNotBlank()) digests.add(d)
        }
        if (digests.isEmpty()) return WorkloadOutput("No summary could be produced from the corpus.", sources)

        while (digests.size > 1) {
            level++
            val batches = BenchText.packByChars(digests, config.contextBudgetChars)
            if (batches.size >= digests.size) {
                val paired = digests.chunked(2)
                if (paired.size >= digests.size) break
                digests = mergeBatches(meter, config, paired, level, onStage)
            } else {
                digests = mergeBatches(meter, config, batches, level, onStage)
            }
        }

        Log.i(TAG, "done: ${selected.size} chunks → ${meter.records.size} passes, $level merge level(s)")
        return WorkloadOutput(digests.firstOrNull().orEmpty().ifBlank { "(empty summary)" }, sources)
    }

    private suspend fun mergeBatches(
        meter: InferenceMeter,
        config: BenchConfig,
        batches: List<List<String>>,
        level: Int,
        onStage: (String) -> Unit,
    ): ArrayList<String> {
        val out = ArrayList<String>(batches.size)
        batches.forEachIndexed { i, batch ->
            if (batch.size == 1) { out.add(batch.first()); return@forEachIndexed }
            onStage("Merging level $level · batch ${i + 1}/${batches.size}")
            val isFinal = batches.size == 1
            val merged = meter.generate(
                stage = "merge L$level ${i + 1}/${batches.size}",
                systemPrompt = MERGE_SYSTEM,
                userPrompt = batch.joinToString("\n\n---\n\n"),
                maxTokens = if (isFinal) config.reportOutputTokens else config.stageOutputTokens,
            )
            out.add(merged.ifBlank { batch.joinToString("\n\n") })
        }
        return out
    }

    private val LEAF_SYSTEM = """
        You read several excerpts from the user's documents, each tagged with its source in [brackets].
        Produce a compact digest using EXACTLY these labelled sections and nothing else:
        FACTS:
        - <key facts stated in the excerpts>
        DECISIONS:
        - <decisions or conclusions reached; None if there are none>
        DATES:
        - <dates, deadlines or time references, with what they refer to; None if none>
        COMMITMENTS:
        - <who promised to do what; None if none>
        OPEN QUESTIONS:
        - <unresolved questions or missing information; None if none>
        Keep the [source] tag on items where it matters. Use ONLY the excerpts; never invent anything.
    """.trimIndent()

    private val MERGE_SYSTEM = """
        You are given several partial digests of the user's documents (separated by ---), each using the
        sections FACTS / DECISIONS / DATES / COMMITMENTS / OPEN QUESTIONS. Merge them into ONE consolidated
        digest under those same section headings, deduplicating overlapping items and preserving [source]
        tags. Do not add any information that is not already present in the partial digests.
    """.trimIndent()
}
