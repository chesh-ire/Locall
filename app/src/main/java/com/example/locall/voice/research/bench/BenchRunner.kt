package com.example.locall.voice.research.bench

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.research.bench.data.BenchRepository
import com.example.locall.voice.research.bench.workloads.ContradictionDetection
import com.example.locall.voice.research.bench.workloads.DeepResearch
import com.example.locall.voice.research.bench.workloads.LongContextQa
import com.example.locall.voice.research.bench.workloads.ProjectPlanning
import com.example.locall.voice.research.bench.workloads.RecursiveSummarisation
import com.example.locall.voice.research.bench.workloads.Workload
import java.util.UUID

private const val TAG = "BenchRunner"

object BenchRunner {

    data class Progress(
        val workload: WorkloadType,
        val stage: String,
        val calls: Int,
        val lastRecord: InferenceRecord?,
    )

    private val registry: Map<WorkloadType, Workload> = mapOf(
        WorkloadType.RECURSIVE_SUMMARISATION to RecursiveSummarisation,
        WorkloadType.CONTRADICTION_DETECTION to ContradictionDetection,
        WorkloadType.PROJECT_PLANNING to ProjectPlanning,
        WorkloadType.LONG_CONTEXT_QA to LongContextQa,
        WorkloadType.DEEP_RESEARCH to DeepResearch,
    )

    suspend fun runOne(
        context: Context,
        type: WorkloadType,
        config: BenchConfig,
        input: String = Workload.defaultInput(type),
        onProgress: (Progress) -> Unit = {},
    ): WorkflowRun {
        val workload = registry.getValue(type)
        return execute(context, type, config, onProgress) { meter, emit ->
            workload.run(context, config, meter, input) { stage -> emit(stage) }
        }
    }

    suspend fun runMany(
        context: Context,
        types: List<WorkloadType>,
        config: BenchConfig,
        inputs: Map<WorkloadType, String> = emptyMap(),
        onProgress: (Progress) -> Unit = {},
    ): List<WorkflowRun> = types.map { type ->
        runOne(context, type, config, inputs[type] ?: Workload.defaultInput(type), onProgress)
    }

    suspend fun deepResearch(
        context: Context,
        config: BenchConfig,
        question: String,
        onProgress: (Progress) -> Unit = {},
    ): Pair<WorkflowRun, List<DeepResearch.Evidence>> {
        var captured: List<DeepResearch.Evidence> = emptyList()
        val run = execute(context, WorkloadType.DEEP_RESEARCH, config, onProgress) { meter, emit ->
            val result = DeepResearch.research(context, config, meter, question) { stage -> emit(stage) }
            captured = result.evidence
            result.output
        }
        return run to captured
    }

    suspend fun deepResearchFollowUp(
        context: Context,
        config: BenchConfig,
        question: String,
        evidence: List<DeepResearch.Evidence>,
        onProgress: (Progress) -> Unit = {},
    ): WorkflowRun = execute(context, WorkloadType.DEEP_RESEARCH, config, onProgress) { meter, emit ->
        val answer = DeepResearch.followUp(meter, config, question, evidence) { stage -> emit(stage) }
        com.example.locall.voice.research.bench.workloads.WorkloadOutput(
            answer, evidence.map { it.citation }.distinct(),
        )
    }

    private suspend fun execute(
        context: Context,
        type: WorkloadType,
        config: BenchConfig,
        onProgress: (Progress) -> Unit,
        body: suspend (
            meter: InferenceMeter,
            emit: (String) -> Unit,
        ) -> com.example.locall.voice.research.bench.workloads.WorkloadOutput,
    ): WorkflowRun {
        var stage = "Loading model…"
        var meterRef: InferenceMeter? = null
        fun report() = onProgress(
            Progress(type, stage, meterRef?.records?.size ?: 0, meterRef?.records?.lastOrNull()),
        )
        report()

        LlmBridge.ensureLoaded(context) { s -> stage = s; report() }

        val meter = InferenceMeter(context, config) { report() }
        meterRef = meter
        meter.samplePeakRss()

        val thermalStart = ThermalGovernor.statusName(ThermalGovernor.status(context))
        val startedAt = System.currentTimeMillis()
        val t0 = SystemClock.elapsedRealtime()

        val output = body(meter) { s -> stage = s; report() }

        val durationMs = SystemClock.elapsedRealtime() - t0
        meter.samplePeakRss()
        val thermalEnd = ThermalGovernor.statusName(ThermalGovernor.status(context))

        val run = WorkflowRun(
            runId = UUID.randomUUID().toString(),
            workload = type,
            config = config,
            startedAtMs = startedAt,
            durationMs = durationMs,
            records = meter.records.toList(),
            peakRssKb = meter.peakRssKb,
            thermalStart = thermalStart,
            thermalEnd = thermalEnd,
            output = output.text,
            sources = output.sources,
        )
        runCatching { BenchRepository.get(context).save(run) }
            .onFailure { Log.w(TAG, "failed to persist run", it) }

        stage = "Done · ${run.inferenceCalls} calls · ${durationMs / 1000.0}s"
        report()
        Log.i(TAG, "run ${type.name}: ${run.inferenceCalls} calls, ${durationMs}ms, " +
            "${"%.1f".format(run.avgDecodeTokensPerSec)} tok/s, peak ${run.peakRssMb}MB")
        return run
    }
}
