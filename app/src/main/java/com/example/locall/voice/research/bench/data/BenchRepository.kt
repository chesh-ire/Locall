package com.example.locall.voice.research.bench.data

import android.content.Context
import com.example.locall.voice.research.bench.InferenceRecord
import com.example.locall.voice.research.bench.WorkflowRun
import com.example.locall.voice.research.bench.WorkloadType

class BenchRepository private constructor(context: Context) {

    private val dao = BenchDatabase.get(context).dao()

    suspend fun save(run: WorkflowRun) {
        dao.insertRun(
            BenchRunEntity(
                id = run.runId,
                workload = run.workload.name,
                createdAtMs = run.startedAtMs,
                durationMs = run.durationMs,
                inferenceCalls = run.inferenceCalls,
                promptTokens = run.totalPromptTokens,
                generatedTokens = run.totalGeneratedTokens,
                prefillMsTotal = run.totalPrefillMs,
                decodeMsTotal = run.totalDecodeMs,
                avgDecodeTokPerSec = run.avgDecodeTokensPerSec,
                peakRssMb = run.peakRssMb,
                thermalStart = run.thermalStart,
                thermalEnd = run.thermalEnd,
                config = run.config.describe(),
                sources = run.sources.joinToString(", "),
                output = run.output,
            ),
        )
        dao.insertInferences(
            run.records.map { r ->
                BenchInferenceEntity(
                    runId = run.runId,
                    ordinal = r.ordinal,
                    stage = r.stage,
                    promptTokens = r.promptTokens,
                    generatedTokens = r.generatedTokens,
                    prefillMs = r.prefillMs,
                    decodeMs = r.decodeMs,
                    wallMs = r.wallMs,
                    rssKb = r.rssKb,
                )
            },
        )
    }

    suspend fun history(): List<BenchRunEntity> = dao.allRuns()

    suspend fun records(runId: String): List<InferenceRecord> =
        dao.inferencesOf(runId).map { e ->
            InferenceRecord(
                ordinal = e.ordinal,
                stage = e.stage,
                promptTokens = e.promptTokens,
                generatedTokens = e.generatedTokens,
                prefillMs = e.prefillMs,
                decodeMs = e.decodeMs,
                wallMs = e.wallMs,
                rssKb = e.rssKb,
            )
        }

    suspend fun delete(runId: String) {
        dao.deleteInferencesOf(runId)
        dao.deleteRun(runId)
    }

    suspend fun clearAll() {
        dao.clearInferences()
        dao.clearRuns()
    }

    fun workloadOf(entity: BenchRunEntity): WorkloadType =
        runCatching { WorkloadType.valueOf(entity.workload) }.getOrDefault(WorkloadType.DEEP_RESEARCH)

    companion object {
        @Volatile private var instance: BenchRepository? = null

        fun get(context: Context): BenchRepository =
            instance ?: synchronized(this) {
                instance ?: BenchRepository(context.applicationContext).also { instance = it }
            }
    }
}
