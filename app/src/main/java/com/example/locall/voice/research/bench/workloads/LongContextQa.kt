package com.example.locall.voice.research.bench.workloads

import android.content.Context
import com.example.locall.voice.research.bench.BenchConfig
import com.example.locall.voice.research.bench.BenchText
import com.example.locall.voice.research.bench.InferenceMeter
import com.example.locall.voice.research.bench.WorkloadType

object LongContextQa : Workload {

    override val type = WorkloadType.LONG_CONTEXT_QA

    override suspend fun run(
        context: Context,
        config: BenchConfig,
        meter: InferenceMeter,
        input: String,
        onStage: (String) -> Unit,
    ): WorkloadOutput {
        val question = input.ifBlank { Workload.defaultInput(WorkloadType.LONG_CONTEXT_QA) }
        val selected = Corpus.recent(context, config.chunkCount)
        val sources = selected.map { it.citation }.distinct()

        val corpusText = selected.joinToString("\n\n") { BenchText.render(it, 2000) }

        val maxContextTokens = (BenchConfig.MODEL_CONTEXT_TOKENS - config.stageOutputTokens - 64).coerceAtLeast(128)
        val targets = config.qaContextSweepTokens
            .map { it.coerceAtMost(maxContextTokens) }
            .distinct()
            .sorted()

        val rows = ArrayList<SweepRow>()
        var lastAnswer = ""
        targets.forEach { tokens ->
            val chars = tokens * BenchConfig.CHARS_PER_TOKEN
            val ctx = corpusText.take(chars)
            onStage("QA at ~${tokens}t context (${BenchText.estimateTokens(ctx)}t actual)")
            lastAnswer = meter.generate(
                stage = "qa ctx=${tokens}t",
                systemPrompt = QA_SYSTEM,
                userPrompt = "Context from the user's documents:\n$ctx\n\nQuestion: $question",
                maxTokens = config.stageOutputTokens,
            )
            meter.records.lastOrNull()?.let { r ->
                rows += SweepRow(
                    targetTokens = tokens,
                    promptTokens = r.promptTokens,
                    prefillMs = r.prefillMs,
                    decodeMs = r.decodeMs,
                    genTokens = r.generatedTokens,
                    decodeTokPerSec = r.decodeTokensPerSec,
                    prefillTokPerSec = r.prefillTokensPerSec,
                )
            }
        }

        val output = buildString {
            append("Long-context QA sweep — prefill vs decode by context size\n")
            append("question: ").append(question).append("\n\n")
            append("ctx(t) | prompt(t) | prefill(ms) | prefill(t/s) | decode(ms) | gen(t) | decode(t/s)\n")
            append("-------|-----------|-------------|--------------|------------|--------|-----------\n")
            rows.forEach { r ->
                append(
                    "%6d | %9d | %11.0f | %12.1f | %10.0f | %6d | %9.1f\n".format(
                        r.targetTokens, r.promptTokens, r.prefillMs, r.prefillTokPerSec,
                        r.decodeMs, r.genTokens, r.decodeTokPerSec,
                    ),
                )
            }
            append("\nLast answer:\n").append(lastAnswer)
        }
        return WorkloadOutput(output, sources)
    }

    private data class SweepRow(
        val targetTokens: Int,
        val promptTokens: Int,
        val prefillMs: Double,
        val decodeMs: Double,
        val genTokens: Int,
        val decodeTokPerSec: Double,
        val prefillTokPerSec: Double,
    )

    private val QA_SYSTEM = """
        You answer the user's question using ONLY the provided context from their documents. The answer may
        require combining evidence from different parts of the context. Cite the [sources] you use. If the
        context doesn't contain the answer, say so. Never invent facts or sources.
    """.trimIndent()
}
