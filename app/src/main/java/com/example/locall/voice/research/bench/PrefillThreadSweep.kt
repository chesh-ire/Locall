package com.example.locall.voice.research.bench

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.example.locall.voice.InferenceDispatcher
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.LlmModelManager
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.research.chunkperf.ChunkCorpus
import com.example.locall.voice.research.chunkperf.DevicePowerSampler
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * STAGE 1 benchmark-only probe (device-heating investigation).
 *
 * Sweeps the PREFILL thread count (2/4/6/8) to find the configuration with the best *sustained*
 * prefill throughput before the SoC throttles. On the Snapdragon 8 Elite (2 prime + 6 performance
 * Oryon cores, no efficiency cores) the current topology heuristic classifies all 8 cores as
 * "performance", so prefill runs on all 8 — a full-SoC race to the thermal wall. This measures
 * whether capping prefill to a subset of the fastest cores sustains higher throughput and lower heat.
 *
 * It uses ONLY the dormant benchmark JNI path ([LlmBridge.loadWithCtx] / [LlmBridge.generateBenchmark])
 * which takes explicit `batchThreads` + `affinityCores`. The production inference path is NOT touched.
 * Inputs mirror the real [com.example.locall.voice.research.pipeline.ChunkAnalyzer] (same system prompt,
 * same maxTokens=320, real corpus chunks) so prompt sizes, output length and heat match production.
 *
 * Memory-safe: releases the production model handle, loads ONE benchmark handle, runs, releases it —
 * never two model copies resident at once. The production handle lazily reloads on next use.
 *
 * To remove: delete this file and its button in PerformanceLabScreen.
 */
object PrefillThreadSweep {

    const val TAG = "ThreadSweep"

    /** Prefill (batch) thread counts to sweep. Decode threads are held fixed to isolate prefill. */
    private val THREAD_CONFIGS = intArrayOf(2, 4, 6, 8)
    private const val FIXED_DECODE_THREADS = 4
    private const val CHUNKS_PER_CONFIG = 8
    // Tiny decode on purpose: this sweep isolates the PREFILL-thread effect, so decode must not
    // dominate. (Full-decode runs are swamped by decode thermal-collapse — that is a decode-thread /
    // pacing problem handled separately, not here.)
    private const val MAX_TOKENS = 16
    private const val BENCH_CTX = 2048 // n_batch = min(ctx,4096) must exceed prompt tokens for single-shot prefill

    // Cooldown gate: settle each config to the same baseline so within-burst trajectories are
    // comparable. Many devices never report low headroom even when idle (observed floor ~0.79),
    // so the target is a lenient "recovered toward the floor" level, not a true-cold level, and a
    // short timeout guarantees it never hangs. The device's own hardware thermal limits still apply.
    private const val COOL_HEADROOM = 0.80f
    private const val COOLDOWN_POLL_MS = 6_000L
    private const val COOLDOWN_TIMEOUT_MS = 90_000L

    // Throttle-onset: first chunk whose prefill tok/s falls below this fraction of the config's first chunk.
    private const val THROTTLE_FRACTION = 0.85

    /**
     * Mirror of ChunkAnalyzer.SYSTEM_PROMPT (kept in sync by hand). Copied rather than referenced so
     * this benchmark tool has no dependency on the production analyzer; prompt token count must match
     * production for the prefill timing to be faithful.
     */
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

    data class ChunkResult(
        val threads: Int,
        val index: Int,
        val promptTokens: Int,
        val generated: Int,
        val prefillMs: Double,
        val decodeMs: Double,
        val prefillTokPerSec: Double,
        val decodeTokPerSec: Double,
        val wallMs: Long,
        val par: Double,
        val headroomStart: Float,
        val headroomEnd: Float,
        val thermalEnd: Int,
        val tempEndC: Float,
        val chargeUahDelta: Long,
        /** DVFS ceiling (max scaling_max_freq across cores) after the chunk, in kHz; the real throttle signal. */
        val clampMaxKhz: Long,
        /** Actual max scaling_cur_freq across cores after the chunk, in kHz. */
        val curKhz: Long,
    )

    data class ConfigSummary(
        val threads: Int,
        val chunks: Int,
        val firstPrefillTokPerSec: Double,
        val steadyPrefillTokPerSec: Double, // mean of the last half of the burst
        val meanDecodeTokPerSec: Double,
        val throttleOnsetIndex: Int, // 1-based chunk where prefill first dropped below THROTTLE_FRACTION; -1 if none
        val headroomStart: Float,
        val headroomPeak: Float,
        val tempRiseC: Float,
        val cooldownMs: Long,
    )

    data class Result(val summaries: List<ConfigSummary>, val csvPath: String?)

    // Runs entirely on the single-threaded inference dispatcher: every native call here
    // (loadWithCtx / generateBenchmark / countTokens / release) is BLOCKING and must never run on
    // the main thread, or the UI ANRs. Compose state writes from onProgress are thread-safe.
    suspend fun run(context: Context, onProgress: (String) -> Unit = {}): Result =
        withContext(InferenceDispatcher.dispatcher) {
        onProgress("Preparing model…")
        LlmModelManager.ensureExtracted(context) { onProgress(it) }
        val path = LlmModelManager.modelFile(context).absolutePath

        // Free the production handle so we never hold two model copies in RAM simultaneously.
        LlmBridge.release()

        val corpus = ChunkCorpus.load(context)
        val chunkCount = minOf(CHUNKS_PER_CONFIG, corpus.size)
        val cores = coresBySpeed()
        Log.i(TAG, "cores by speed (fastest first): ${cores.joinToString(",")}")

        val h = LlmBridge.loadWithCtx(path, BENCH_CTX)
        require(h != 0L) { "benchmark model failed to load" }

        val all = mutableListOf<ChunkResult>()
        val summaries = mutableListOf<ConfigSummary>()
        try {
            for (threads in THREAD_CONFIGS) {
                onProgress("Cooling before $threads-thread prefill…")
                val cooldownMs = cooldownToCold(context, onProgress)

                val affinity = cores.take(threads).toIntArray()
                val start = DevicePowerSampler.sample(context)
                var peakHeadroom = if (start.headroom.isNaN()) 0f else start.headroom
                var lastTemp = start.tempC
                val cfg = mutableListOf<ChunkResult>()

                Log.i(TAG, "=== config threads=$threads affinity=${affinity.joinToString(",")} " +
                    "headroomStart=${f2(start.headroom)} ===")

                for (i in 0 until chunkCount) {
                    val chunk = corpus[i]
                    val promptTokens = LlmBridge.countTokens(h, SYSTEM_PROMPT, chunk)
                    val before = DevicePowerSampler.sample(context)
                    val t0 = SystemClock.elapsedRealtime()
                    val csv = LlmBridge.generateBenchmark(
                        benchHandle = h,
                        systemPrompt = SYSTEM_PROMPT,
                        userPrompt = chunk,
                        maxTokens = MAX_TOKENS,
                        decodeThreads = FIXED_DECODE_THREADS,
                        batchThreads = threads,
                        affinityCores = affinity,
                    )
                    val wall = SystemClock.elapsedRealtime() - t0
                    val after = DevicePowerSampler.sample(context)

                    val p = csv.split(',')
                    val prefillMs = p.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                    val decodeMs = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    val generated = p.getOrNull(2)?.toIntOrNull() ?: 0
                    val cpuDelta = after.cpuMs - before.cpuMs

                    val r = ChunkResult(
                        threads = threads,
                        index = i,
                        promptTokens = promptTokens,
                        generated = generated,
                        prefillMs = prefillMs,
                        decodeMs = decodeMs,
                        prefillTokPerSec = if (prefillMs > 0) promptTokens * 1000.0 / prefillMs else 0.0,
                        decodeTokPerSec = if (decodeMs > 0) generated * 1000.0 / decodeMs else 0.0,
                        wallMs = wall,
                        par = if (wall > 0) cpuDelta.toDouble() / wall.toDouble() else 0.0,
                        headroomStart = before.headroom,
                        headroomEnd = after.headroom,
                        thermalEnd = after.thermalStatus,
                        tempEndC = after.tempC,
                        chargeUahDelta = after.chargeUah - before.chargeUah,
                        clampMaxKhz = maxFreqKhzAcross(cores, "scaling_max_freq"),
                        curKhz = maxFreqKhzAcross(cores, "scaling_cur_freq"),
                    )
                    cfg.add(r); all.add(r)
                    if (!after.headroom.isNaN() && after.headroom > peakHeadroom) peakHeadroom = after.headroom
                    lastTemp = after.tempC
                    Log.i(TAG, chunkLine(r))
                    onProgress(
                        "t=$threads ${i + 1}/$chunkCount · prefill ${f1(r.prefillTokPerSec)} tok/s · " +
                            "par ${f1(r.par)}x · headroom ${f2(r.headroomEnd)}",
                    )
                }

                val summary = summarize(threads, cfg, start.headroom, peakHeadroom, lastTemp - start.tempC, cooldownMs)
                summaries.add(summary)
                Log.i(TAG, summaryLine(summary))
            }
        } finally {
            LlmBridge.releaseHandle(h)
        }

        val csvPath = runCatching { writeCsv(context, all) }.getOrNull()
        Log.i(TAG, "sweep done; csv=${csvPath ?: "(write failed)"}")
        Result(summaries, csvPath)
    }

    private fun summarize(
        threads: Int,
        cfg: List<ChunkResult>,
        headroomStart: Float,
        peakHeadroom: Float,
        tempRiseC: Float,
        cooldownMs: Long,
    ): ConfigSummary {
        val prefill = cfg.map { it.prefillTokPerSec }
        val first = prefill.firstOrNull() ?: 0.0
        val half = cfg.subList(cfg.size / 2, cfg.size)
        val steady = if (half.isNotEmpty()) half.map { it.prefillTokPerSec }.average() else 0.0
        val meanDecode = if (cfg.isNotEmpty()) cfg.map { it.decodeTokPerSec }.average() else 0.0
        val onset = if (first > 0) {
            cfg.indexOfFirst { it.prefillTokPerSec < first * THROTTLE_FRACTION }.let { if (it < 0) -1 else it + 1 }
        } else -1
        return ConfigSummary(
            threads = threads,
            chunks = cfg.size,
            firstPrefillTokPerSec = first,
            steadyPrefillTokPerSec = steady,
            meanDecodeTokPerSec = meanDecode,
            throttleOnsetIndex = onset,
            headroomStart = headroomStart,
            headroomPeak = peakHeadroom,
            tempRiseC = tempRiseC,
            cooldownMs = cooldownMs,
        )
    }

    /** Poll until the SoC is cool (status <= LIGHT and headroom low) or the timeout elapses. Returns ms spent. */
    private suspend fun cooldownToCold(context: Context, onProgress: (String) -> Unit): Long {
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            val s = DevicePowerSampler.sample(context)
            val cool = s.thermalStatus <= PowerManager.THERMAL_STATUS_MODERATE &&
                (s.headroom.isNaN() || s.headroom <= COOL_HEADROOM)
            val elapsed = SystemClock.elapsedRealtime() - t0
            if (cool || elapsed >= COOLDOWN_TIMEOUT_MS) return elapsed
            onProgress(
                "Cooling… status=${ThermalGovernor.statusName(s.thermalStatus)} " +
                    "headroom=${f2(s.headroom)} (target ≤${COOL_HEADROOM}, ${(COOLDOWN_TIMEOUT_MS - elapsed) / 1000}s left)",
            )
            delay(COOLDOWN_POLL_MS)
        }
    }

    /** CPU core indices ordered by max frequency, fastest first (reads /sys cpuinfo_max_freq like native). */
    private fun coresBySpeed(): List<Int> {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = (0 until n).map { cpu -> cpu to readMaxFreqKhz(cpu) }
        val anyKnown = freqs.any { it.second > 0 }
        return if (anyKnown) {
            freqs.sortedByDescending { it.second }.map { it.first }
        } else {
            (0 until n).toList() // fallback: natural order if /sys is unreadable
        }
    }

    private fun readMaxFreqKhz(cpu: Int): Long =
        runCatching {
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                .readText().trim().toLong()
        }.getOrDefault(-1L)

    /** Max of a cpufreq attribute (e.g. scaling_max_freq, scaling_cur_freq) across the given cores, kHz. */
    private fun maxFreqKhzAcross(cores: List<Int>, attr: String): Long {
        var m = -1L
        for (c in cores) {
            val v = runCatching {
                File("/sys/devices/system/cpu/cpu$c/cpufreq/$attr").readText().trim().toLong()
            }.getOrDefault(-1L)
            if (v > m) m = v
        }
        return m
    }

    private fun writeCsv(context: Context, rows: List<ChunkResult>): String {
        val dir = File(context.filesDir, "perf").apply { mkdirs() }
        val file = File(dir, "thread-sweep.csv")
        file.printWriter().use { w ->
            w.println("threads,index,prompt_tokens,generated,prefill_ms,decode_ms,prefill_tok_s,decode_tok_s,wall_ms,par,headroom_start,headroom_end,thermal_end,temp_end_c,charge_uah_delta,clamp_max_khz,cur_khz")
            for (r in rows) {
                w.println(
                    "${r.threads},${r.index},${r.promptTokens},${r.generated}," +
                        "${f1(r.prefillMs)},${f1(r.decodeMs)},${f1(r.prefillTokPerSec)},${f1(r.decodeTokPerSec)}," +
                        "${r.wallMs},${f2d(r.par)},${f2(r.headroomStart)},${f2(r.headroomEnd)}," +
                        "${r.thermalEnd},${f1(r.tempEndC.toDouble())},${r.chargeUahDelta},${r.clampMaxKhz},${r.curKhz}",
                )
            }
        }
        return file.absolutePath
    }

    private fun chunkLine(r: ChunkResult): String =
        "t=${r.threads} #${r.index} prompt=${r.promptTokens}t gen=${r.generated}t | " +
            "prefill=${r.prefillMs.toInt()}ms (${f1(r.prefillTokPerSec)} tok/s) " +
            "decode=${r.decodeMs.toInt()}ms (${f1(r.decodeTokPerSec)} tok/s) | " +
            "par=${f1(r.par)}x headroom ${f2(r.headroomStart)}→${f2(r.headroomEnd)} " +
            "thermal=${ThermalGovernor.statusName(r.thermalEnd)} temp=${f1(r.tempEndC.toDouble())}C " +
            "clamp=${r.clampMaxKhz / 1000}MHz cur=${r.curKhz / 1000}MHz"

    private fun summaryLine(s: ConfigSummary): String =
        "SUMMARY t=${s.threads}: first=${f1(s.firstPrefillTokPerSec)} steady=${f1(s.steadyPrefillTokPerSec)} tok/s " +
            "(decode ${f1(s.meanDecodeTokPerSec)}) throttleOnset=${if (s.throttleOnsetIndex < 0) "none" else "#${s.throttleOnsetIndex}"} " +
            "headroom ${f2(s.headroomStart)}→peak ${f2(s.headroomPeak)} tempRise ${f1(s.tempRiseC.toDouble())}C " +
            "cooldown=${s.cooldownMs / 1000}s"

    /** Human-readable multi-config report for the Perf Lab card. */
    fun report(r: Result): String = buildString {
        appendLine("Prefill thread sweep (prefill-threads · fixed decode=$FIXED_DECODE_THREADS)")
        appendLine("prompt≈chunk+extract prompt · maxTok=$MAX_TOKENS · $CHUNKS_PER_CONFIG chunks/config, cold start each")
        appendLine()
        appendLine("thr  cold→steady prefill   decode   throttle   peakHR  ΔtempC")
        for (s in r.summaries) {
            appendLine(
                "%-4d %5.1f→%-5.1f tok/s   %5.1f   %-8s  %5s  %+.1f".format(
                    s.threads,
                    s.firstPrefillTokPerSec,
                    s.steadyPrefillTokPerSec,
                    s.meanDecodeTokPerSec,
                    if (s.throttleOnsetIndex < 0) "none" else "#${s.throttleOnsetIndex}",
                    f2(s.headroomPeak),
                    s.tempRiseC,
                ),
            )
        }
        r.csvPath?.let { appendLine(); appendLine("CSV: $it") }
        appendLine()
        appendLine("Best sustained = highest 'steady' with latest throttle & lowest Δtemp.")
    }

    private fun f1(v: Double) = ((v * 10).toLong() / 10.0).toString()
    private fun f2d(v: Double) = ((v * 100).toLong() / 100.0).toString()
    private fun f2(v: Float) = if (v.isNaN()) "n/a" else ((v * 100).toLong() / 100.0).toString()
}
