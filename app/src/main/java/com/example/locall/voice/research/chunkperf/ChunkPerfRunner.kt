package com.example.locall.voice.research.chunkperf

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.research.chunkperf.data.ChunkPerfRepository
import kotlinx.coroutines.delay
import java.io.File

/**
 * Runs the frozen 105-chunk corpus through the single shared [LlmBridge] model instance,
 * one chunk per inference, capturing rich per-chunk metrics.
 *
 * Guarantees:
 *  - One model instance: [LlmBridge.ensureLoaded] once; all chunks hit the same handle.
 *  - Persist across restarts: every chunk result is written immediately; resume finds the
 *    first pending index from the DB.
 *  - Never skip a chunk: a chunk is "done" only after an OK metric is persisted; errors
 *    leave it pending (retried on resume) and stop the session.
 *  - Never bypass thermal protection: a thermal gate runs BEFORE each chunk and honours
 *    [ThermalGovernor]; on overheat the session pauses and resumes after cooldown.
 */
object ChunkPerfRunner {

    const val TAG = "ChunkPerf"
    val TOTAL = ChunkCorpus.EXPECTED

    /** How long to sleep between thermal re-checks while cooling down. */
    private const val COOLDOWN_MS = 20_000L

    /** Resume only once headroom has dropped this far below the pause threshold (hysteresis). */
    private const val HEADROOM_HYSTERESIS = 0.08f

    // Same extraction task as the real import pipeline (see ChunkAnalyzer.SYSTEM_PROMPT),
    // copied here so the benchmark exercises an identical workload without coupling to it.
    private val SYSTEM_PROMPT = """
        You read ONE excerpt from the user's personal documents and extract its key information so it can
        later be searched and reasoned over. Base everything ONLY on the excerpt; never invent people,
        decisions, dates or facts. Output EXACTLY these labelled sections and nothing else:
        SUMMARY: <2-3 sentences: what this excerpt is about and the main points>
        ENTITIES: <comma-separated people, projects, companies and topics mentioned; empty if none>
        DECISIONS:
        - <each decision or conclusion reached; write None if there are none>
        DATES:
        - <each date, deadline or time reference, with what it refers to; write None if there are none>
        COMMITMENTS:
        - <each promise or action someone committed to, who owes what; write None if there are none>
    """.trimIndent()

    data class Progress(
        val index: Int,
        val total: Int,
        val completed: Int,
        val last: ChunkMetric?,
        val thermalName: String,
        val paused: Boolean,
        val note: String?,
    )

    sealed class SessionResult {
        abstract val runId: String
        abstract val completed: Int
        data class Paused(override val runId: String, override val completed: Int, val reason: String) : SessionResult()
        data class Completed(override val runId: String, override val completed: Int, val report: ChunkPerfReport) :
            SessionResult()
    }

    suspend fun runSession(
        context: Context,
        config: ChunkPerfConfig,
        onProgress: (Progress) -> Unit = {},
    ): SessionResult {
        LlmBridge.ensureLoaded(context)
        val corpus = ChunkCorpus.load(context)
        val repo = ChunkPerfRepository.get(context)

        val run = repo.findOrCreateActiveRun(config)
        val done = repo.completedIndices(run.id).toMutableSet()
        repo.updateStatus(run.id, RunStatus.RUNNING, done.size)
        Log.i(TAG, "session start run=${run.id} label=${config.label} resumeFrom=${done.size}/$TOTAL")

        var index = (0 until TOTAL).firstOrNull { it !in done }

        while (index != null) {
            // --- thermal gate (BEFORE the chunk; never bypassed) ---
            // If overheated, WAIT here (polling + cooldown sleeps) until the device cools, then
            // continue. This honours "pause on overheat, resume after cooling" without manual taps.
            val cooldownMs = waitUntilCool(context, config, repo, run.id, done.size, index, onProgress)

            val metric = processChunk(context, config, index, corpus[index], cooldownMs)
            repo.saveMetric(run.id, metric)

            if (metric.status == "OK") {
                done.add(index)
                repo.updateStatus(run.id, RunStatus.RUNNING, done.size)
            }
            onProgress(
                Progress(
                    index, TOTAL, done.size, metric,
                    ThermalGovernor.statusName(ThermalGovernor.status(context)), false, null,
                ),
            )

            if (metric.status != "OK") {
                // Never skip: stop so this chunk is retried on the next resume.
                val reason = "ERROR at chunk $index: ${metric.error}"
                repo.updateStatus(run.id, RunStatus.PAUSED, done.size)
                Log.w(TAG, "paused: $reason")
                return SessionResult.Paused(run.id, done.size, reason)
            }
            index = (0 until TOTAL).firstOrNull { it !in done }
        }

        // Loop only exits when every chunk is done (errors return early above).
        val metrics = repo.metrics(run.id)
        val report = ChunkPerfReport.build(run, metrics)
        repo.updateStatus(run.id, RunStatus.COMPLETED, done.size, report.describe())
        emitReport(context, report)
        return SessionResult.Completed(run.id, done.size, report)
    }

    /**
     * Blocks (cooperatively, via coroutine [delay]) until the device is cool enough to run the
     * next chunk. Never pushes inference through heat — it waits. Uses hysteresis so it doesn't
     * thrash between pause and resume.
     */
    private suspend fun waitUntilCool(
        context: Context,
        config: ChunkPerfConfig,
        repo: ChunkPerfRepository,
        runId: String,
        done: Int,
        index: Int,
        onProgress: (Progress) -> Unit,
    ): Long {
        fun status() = ThermalGovernor.status(context)
        fun headroom() = ThermalGovernor.headroom(context)

        fun overheated(s: Int, h: Float) =
            s >= config.pauseThermalStatus || (!h.isNaN() && h >= config.pauseHeadroom)

        fun coolEnough(s: Int, h: Float) =
            s <= android.os.PowerManager.THERMAL_STATUS_LIGHT &&
                (h.isNaN() || h <= config.pauseHeadroom - HEADROOM_HYSTERESIS)

        if (!overheated(status(), headroom())) return 0L // common path: proceed immediately

        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            val s = status()
            val h = headroom()
            if (coolEnough(s, h)) {
                repo.updateStatus(runId, RunStatus.RUNNING, done)
                Log.i(TAG, "cooled to ${ThermalGovernor.statusName(s)} headroom=$h — resuming at chunk $index")
                return SystemClock.elapsedRealtime() - t0
            }
            repo.updateStatus(runId, RunStatus.PAUSED, done)
            val note = "cooling: thermal=${ThermalGovernor.statusName(s)} headroom=$h"
            Log.w(TAG, "$note (done $done/$TOTAL) — waiting ${COOLDOWN_MS / 1000}s")
            onProgress(Progress(index, TOTAL, done, null, ThermalGovernor.statusName(s), true, note))
            delay(COOLDOWN_MS)
        }
    }

    private suspend fun processChunk(
        context: Context,
        config: ChunkPerfConfig,
        index: Int,
        text: String,
        cooldownMs: Long,
    ): ChunkMetric {
        val threads = if (config.decodeThreads > 0) config.decodeThreads
        else ThermalGovernor.decodeThreads(context)
        val pre = DevicePowerSampler.sample(context)
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val (out, m) = LlmBridge.generateMetered(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = text,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                decodeThreads = threads,
                longForm = false,
                tag = "chunkperf",
            )
            val wall = SystemClock.elapsedRealtime() - t0
            val post = DevicePowerSampler.sample(context)
            ChunkMetric(
                index = index, status = "OK",
                promptTokens = m.promptTokens, generatedTokens = m.generatedTokens,
                prefillMs = m.prefillMs, decodeMs = m.decodeMs, wallMs = wall, decodeThreadsUsed = threads,
                cpuMs = (post.cpuMs - pre.cpuMs).coerceAtLeast(0), rssKb = m.rssKb,
                batteryPctStart = pre.batteryPct, batteryPctEnd = post.batteryPct,
                chargeUahStart = pre.chargeUah, chargeUahEnd = post.chargeUah,
                tempStartC = pre.tempC, tempEndC = post.tempC,
                thermalStart = pre.thermalStatus, thermalEnd = post.thermalStatus,
                headroomStart = pre.headroom, headroomEnd = post.headroom,
                timestampMs = System.currentTimeMillis(), output = out, error = null,
                cooldownMs = cooldownMs,
            )
        } catch (t: Throwable) {
            val wall = SystemClock.elapsedRealtime() - t0
            val post = DevicePowerSampler.sample(context)
            Log.w(TAG, "chunk $index failed", t)
            ChunkMetric(
                index = index, status = "ERROR",
                promptTokens = 0, generatedTokens = 0, prefillMs = 0.0, decodeMs = 0.0,
                wallMs = wall, decodeThreadsUsed = threads, cpuMs = 0, rssKb = 0,
                batteryPctStart = pre.batteryPct, batteryPctEnd = post.batteryPct,
                chargeUahStart = pre.chargeUah, chargeUahEnd = post.chargeUah,
                tempStartC = pre.tempC, tempEndC = post.tempC,
                thermalStart = pre.thermalStatus, thermalEnd = post.thermalStatus,
                headroomStart = pre.headroom, headroomEnd = post.headroom,
                timestampMs = System.currentTimeMillis(), output = "",
                error = t.message ?: t.javaClass.simpleName,
                cooldownMs = cooldownMs,
            )
        }
    }

    /** Dump the final report to logcat (tag ChunkPerf) and to filesDir/perf/ for adb pull. */
    private fun emitReport(context: Context, report: ChunkPerfReport) {
        val text = report.describe()
        text.lineSequence().forEach { Log.i(TAG, it) }
        runCatching {
            val dir = File(context.filesDir, "perf").apply { mkdirs() }
            File(dir, "chunkperf-${report.runId}.txt").writeText(text)
            File(dir, "chunkperf-latest-${report.label}.txt").writeText(text)
        }.onFailure { Log.w(TAG, "could not write report file", it) }
    }
}
