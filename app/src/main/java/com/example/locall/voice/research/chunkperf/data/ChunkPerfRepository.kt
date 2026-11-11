package com.example.locall.voice.research.chunkperf.data

import android.content.Context
import com.example.locall.voice.research.chunkperf.ChunkCorpus
import com.example.locall.voice.research.chunkperf.ChunkMetric
import com.example.locall.voice.research.chunkperf.ChunkPerfConfig
import com.example.locall.voice.research.chunkperf.ChunkRunInfo
import com.example.locall.voice.research.chunkperf.RunStatus
import java.util.UUID

/**
 * Persistence facade for the 105-chunk perf system. Every chunk result is written
 * immediately so progress survives process death / restarts; resume recomputes the first
 * pending chunk from the DB.
 */
class ChunkPerfRepository private constructor(context: Context) {

    private val dao = ChunkPerfDatabase.get(context).dao()

    /** Resume the active run for this config's label, or start a fresh one. */
    suspend fun findOrCreateActiveRun(config: ChunkPerfConfig): ChunkRunInfo {
        dao.activeRun(config.label)?.let { return it.toInfo() }
        val entity = ChunkRunEntity(
            id = UUID.randomUUID().toString(),
            label = config.label,
            configEncoded = config.encode(),
            createdAtMs = System.currentTimeMillis(),
            status = RunStatus.RUNNING.name,
            chunksCompleted = 0,
            chunksTotal = ChunkCorpus.EXPECTED,
            reportText = null,
        )
        dao.upsertRun(entity)
        return entity.toInfo()
    }

    suspend fun completedIndices(runId: String): Set<Int> = dao.completedIndices(runId).toSet()

    suspend fun saveMetric(runId: String, m: ChunkMetric) {
        dao.upsertMetric(
            ChunkMetricEntity(
                id = "$runId#${m.index}",
                runId = runId,
                chunkIndex = m.index,
                status = m.status,
                promptTokens = m.promptTokens,
                generatedTokens = m.generatedTokens,
                prefillMs = m.prefillMs.sanitize(),
                decodeMs = m.decodeMs.sanitize(),
                wallMs = m.wallMs,
                decodeThreadsUsed = m.decodeThreadsUsed,
                cpuMs = m.cpuMs,
                rssKb = m.rssKb,
                batteryPctStart = m.batteryPctStart,
                batteryPctEnd = m.batteryPctEnd,
                chargeUahStart = m.chargeUahStart,
                chargeUahEnd = m.chargeUahEnd,
                // SQLite stores NaN/Inf REAL as NULL, which violates NOT NULL — map to -1 ("unavailable").
                tempStartC = m.tempStartC.sanitize(),
                tempEndC = m.tempEndC.sanitize(),
                thermalStart = m.thermalStart,
                thermalEnd = m.thermalEnd,
                headroomStart = m.headroomStart.sanitize(),
                headroomEnd = m.headroomEnd.sanitize(),
                timestampMs = m.timestampMs,
                output = m.output,
                error = m.error,
                cooldownMs = m.cooldownMs,
            ),
        )
    }

    private fun Float.sanitize(): Float = if (isFinite()) this else -1f
    private fun Double.sanitize(): Double = if (isFinite()) this else 0.0

    suspend fun updateStatus(runId: String, status: RunStatus, completed: Int, reportText: String? = null) {
        val run = dao.run(runId) ?: return
        dao.upsertRun(
            run.copy(
                status = status.name,
                chunksCompleted = completed,
                reportText = reportText ?: run.reportText,
            ),
        )
    }

    suspend fun metrics(runId: String): List<ChunkMetric> = dao.metricsOf(runId).map { it.toMetric() }

    suspend fun history(): List<ChunkRunInfo> = dao.allRuns().map { it.toInfo() }

    suspend fun latestCompleted(label: String): ChunkRunInfo? = dao.latestCompleted(label)?.toInfo()

    suspend fun reportText(runId: String): String? = dao.run(runId)?.reportText

    suspend fun delete(runId: String) {
        dao.deleteMetricsOf(runId)
        dao.deleteRun(runId)
    }

    suspend fun clearAll() {
        dao.clearMetrics()
        dao.clearRuns()
    }

    private fun ChunkRunEntity.toInfo() = ChunkRunInfo(
        id = id,
        label = label,
        config = ChunkPerfConfig.decode(configEncoded),
        createdAtMs = createdAtMs,
        status = runCatching { RunStatus.valueOf(status) }.getOrDefault(RunStatus.PAUSED),
        chunksCompleted = chunksCompleted,
        chunksTotal = chunksTotal,
    )

    private fun ChunkMetricEntity.toMetric() = ChunkMetric(
        index = chunkIndex,
        status = status,
        promptTokens = promptTokens,
        generatedTokens = generatedTokens,
        prefillMs = prefillMs,
        decodeMs = decodeMs,
        wallMs = wallMs,
        decodeThreadsUsed = decodeThreadsUsed,
        cpuMs = cpuMs,
        rssKb = rssKb,
        batteryPctStart = batteryPctStart,
        batteryPctEnd = batteryPctEnd,
        chargeUahStart = chargeUahStart,
        chargeUahEnd = chargeUahEnd,
        tempStartC = tempStartC,
        tempEndC = tempEndC,
        thermalStart = thermalStart,
        thermalEnd = thermalEnd,
        headroomStart = headroomStart,
        headroomEnd = headroomEnd,
        timestampMs = timestampMs,
        output = output,
        error = error,
        cooldownMs = cooldownMs,
    )

    companion object {
        @Volatile private var instance: ChunkPerfRepository? = null

        fun get(context: Context): ChunkPerfRepository =
            instance ?: synchronized(this) {
                instance ?: ChunkPerfRepository(context.applicationContext).also { instance = it }
            }
    }
}
