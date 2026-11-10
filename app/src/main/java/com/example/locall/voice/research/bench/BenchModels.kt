package com.example.locall.voice.research.bench

enum class WorkloadType(val label: String, val blurb: String) {
    RECURSIVE_SUMMARISATION(
        "Recursive Summarisation",
        "Retrieve 40–60 chunks, extract facts/decisions/dates/commitments in groups of 5, then recursively merge.",
    ),
    CONTRADICTION_DETECTION(
        "Contradiction Detection",
        "Compare claims across documents, flag conflicts and outdated decisions, resolve the latest position.",
    ),
    PROJECT_PLANNING(
        "Project Planning",
        "Decompose a goal into tasks + dependencies, prioritise, find blockers, replan on new constraints.",
    ),
    LONG_CONTEXT_QA(
        "Long-context QA",
        "Sweep context sizes within model capacity; answer evidence-spanning questions; measure prefill vs decode.",
    ),
    DEEP_RESEARCH(
        "Deep Research",
        "Retrieve → summarise → detect contradictions → write a cited report; supports follow-up questions.",
    ),
}

data class InferenceRecord(
    val ordinal: Int,
    val stage: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val prefillMs: Double,
    val decodeMs: Double,
    val wallMs: Long,
    val rssKb: Long,
) {
    val decodeTokensPerSec: Double get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else 0.0

    val prefillTokensPerSec: Double get() = if (prefillMs > 0) promptTokens * 1000.0 / prefillMs else 0.0
}

data class WorkflowRun(
    val runId: String,
    val workload: WorkloadType,
    val config: BenchConfig,
    val startedAtMs: Long,
    val durationMs: Long,
    val records: List<InferenceRecord>,
    val peakRssKb: Long,
    val thermalStart: String,
    val thermalEnd: String,
    val output: String,
    val sources: List<String>,
) {
    val inferenceCalls: Int get() = records.size
    val totalPromptTokens: Long get() = records.sumOf { it.promptTokens.toLong() }
    val totalGeneratedTokens: Long get() = records.sumOf { it.generatedTokens.toLong() }
    val totalPrefillMs: Double get() = records.sumOf { it.prefillMs }
    val totalDecodeMs: Double get() = records.sumOf { it.decodeMs }

    val avgDecodeTokensPerSec: Double
        get() = if (totalDecodeMs > 0) totalGeneratedTokens * 1000.0 / totalDecodeMs else 0.0

    val peakRssMb: Int get() = (peakRssKb / 1024).toInt()
}
