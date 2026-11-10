package com.example.locall.voice.research.bench.workloads

import android.content.Context
import com.example.locall.voice.research.bench.BenchConfig
import com.example.locall.voice.research.bench.InferenceMeter
import com.example.locall.voice.research.bench.WorkloadType

data class WorkloadOutput(val text: String, val sources: List<String>)

interface Workload {
    val type: WorkloadType

    suspend fun run(
        context: Context,
        config: BenchConfig,
        meter: InferenceMeter,
        input: String,
        onStage: (String) -> Unit,
    ): WorkloadOutput

    companion object {
        fun defaultInput(type: WorkloadType): String = when (type) {
            WorkloadType.RECURSIVE_SUMMARISATION ->
                "Everything important across my documents"
            WorkloadType.CONTRADICTION_DETECTION ->
                "Conflicting claims, decisions that were later changed, and outdated plans"
            WorkloadType.PROJECT_PLANNING ->
                "Plan and ship the on-device research assistant: ingestion, retrieval, reasoning, and a benchmark harness"
            WorkloadType.LONG_CONTEXT_QA ->
                "What are the key decisions and deadlines, and where is there disagreement?"
            WorkloadType.DEEP_RESEARCH ->
                "Give me a thorough, source-referenced briefing on the most important topics in my knowledge base"
        }
    }
}
