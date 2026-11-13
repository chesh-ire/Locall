package com.example.locall.voice.research.bench

import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.content.Context
import com.example.locall.voice.InferenceDispatcher
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.LlmModelManager
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.research.chunkperf.DevicePowerSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Benchmark-only probe for the DECODE-thread question (device-heating / decode-stall investigation).
 *
 * Companion to [PrefillThreadSweep]. Where that one sweeps the *prefill* (batch) thread count, this
 * one sweeps the *decode* thread count (1/2/3/4) over a FIXED short prompt and FIXED output length,
 * to answer a sharper question: on the Snapdragon 8 Elite, decode throughput observed in the prefill
 * sweep collapsed to ~0.3 tok/s with 4 threads while production hit ~8 tok/s at 1 thread — a ~25×
 * regression that thermal throttling alone cannot explain. The leading hypothesis is a DVFS-governor
 * stall: decode's token-by-token "park → wake → tiny GEMV → park" pattern, run at the benchmark
 * path's default nice 0, lets the `walt` governor idle the cores between micro-bursts (low par, low
 * clock), rather than a memory-bandwidth wall.
 *
 * Three corrections over the prefill sweep's (unreliable) decode column:
 *   1. **Priority parity with production (verified against the native code).** Production
 *      `run_generation` calls `setpriority(PRIO_PROCESS, 0, -10)` on its OWN (calling) thread, right
 *      before its decodes (`llm_jni.cpp`). Because this build sets `GGML_OPENMP OFF` and never attaches
 *      a persistent threadpool, ggml spawns a DISPOSABLE worker pool INSIDE each `llama_decode`, on the
 *      thread that calls it, and frees it when the call returns — so workers inherit that thread's nice
 *      at decode time (ggml keeps the inherited priority for its default NORMAL prio). The benchmark JNI
 *      path (`nativeGenerateBenchmark`) does NOT call `setpriority` at all. We reproduce production from
 *      Kotlin by setting THIS inference-dispatcher thread to nice -10. Parity holds only because the
 *      dispatcher is single-threaded: the same thread sets the priority, loads the bench handle, and
 *      runs `generateBenchmark`, so the per-decode workers it spawns inherit -10 — matching production's
 *      per-decode -10. (Setting it before load vs. before the first decode is therefore immaterial; what
 *      matters is that the generateBenchmark thread carries nice -10.) `setpriority` can silently fail
 *      without CAP_SYS_NICE, so the achieved nice AND whether it took effect are logged; the parity
 *      claim only holds when that log confirms nice -10.
 *   2. **Mid-decode per-core `scaling_cur_freq` sampling.** The prefill sweep read cpufreq once AFTER
 *      the whole chunk, so it reported the frequency left over from the full-blast prefill, never the
 *      decode P-state. Here a concurrent sampler records EVERY domain core's `scaling_cur_freq`
 *      throughout the call; the sampler and the generation call share ONE elapsed-realtime origin, so
 *      samples whose timestamp falls after the (short, known) prefill window and still within the call
 *      are attributed to decode, and the true per-core min/mean across them is the decode P-state.
 *   3. **Whole-call cpuMs/token.** `chargeUah` is unreliable on USB; process CPU-time per generated
 *      token is a CPU-WORK (utilization) proxy — NOT an energy measurement — and the direct test of
 *      "are surplus threads just spinning?". NOTE: the benchmark JNI call is one opaque blocking call,
 *      so this CPU time covers the WHOLE call (prefill + decode), not decode alone — it is labelled as
 *      such everywhere and must not be read as decode-only CPU work (let alone decode-only energy). With
 *      the fixed short prompt, decode dominates, but prefill is still included.
 *
 * Reading the result (these are HYPOTHESES the data can support or weaken, NOT conclusions — a
 * bandwidth wall in particular cannot be inferred from CPU frequency alone):
 *   - If decode tok/s RISES as threads drop (4→1) and whole-call cpuMs/token falls, that is CONSISTENT
 *     WITH a governor-stall / over-threading hypothesis (more parked micro-bursts at higher thread
 *     counts). It would motivate trying fewer decode threads; it does not establish the mechanism, and
 *     it is not a basis for forcing a CPU clock floor or bypassing thermal management.
 *   - If decode tok/s is FLAT across thread counts while cur_freq sits near the clamp, that is
 *     CONSISTENT WITH (but not proof of) a memory-bandwidth limitation; corroborating evidence beyond
 *     frequency would be needed before concluding it.
 *
 * Uses ONLY the dormant benchmark JNI path ([LlmBridge.loadWithCtx] / [LlmBridge.generateBenchmark]).
 * The production inference path and native code are NOT touched. Memory-safe: releases the production
 * handle, loads ONE benchmark handle, runs, releases it, then RESTORES the production handle so the app
 * is never left without its model.
 *
 * To remove: delete this file (and its button in PerformanceLabScreen, if one is added).
 */
object DecodeThreadSweep {

    const val TAG = "DecodeSweep"

    /** Decode thread counts to sweep. Prefill (batch) threads and the core domain are held fixed. */
    private val DECODE_CONFIGS = intArrayOf(1, 2, 3, 4)

    /** "1/2/3/4" — the swept decode thread counts, for UI labels. */
    fun decodeConfigsLabel(): String = DECODE_CONFIGS.joinToString("/")

    /** Fixed prefill threads: decode is what we vary, so prefill must not change between configs. */
    private const val FIXED_BATCH_THREADS = 4

    /** Fraction-of-top-freq threshold that native `cpu_topology.cpp` uses to classify a core as a
     *  "performance" core (`max_freq >= 0.65 * top`). The benchmark pins to this SAME full
     *  performance-core domain as production (`pin_current_thread_to(performance_cores)`), so only the
     *  decode thread count varies — not the set of cores it is allowed to run on. */
    private const val PERF_CORE_FREQ_FRACTION = 0.65

    /** Target decode length. Greedy decode may hit EOG earlier; `generated` is logged so short runs
     *  are visible. Long enough that decode dominates the short prompt's prefill. */
    const val MAX_TOKENS = 128

    /** Repeat each config; rotate order across reps to cancel residual thermal drift. */
    const val REPS = 3

    private const val BENCH_CTX = 2048
    private const val FREQ_POLL_MS = 120L

    // Best-effort settle before every measurement so the starting thermal state is as equivalent as
    // this device allows. This unit frequently returns a NaN thermal-headroom reading, so the gate must
    // NOT require a valid headroom (a strict non-NaN gate could never confirm "cool" and would stall);
    // when headroom is unreadable we fall back to thermal status. On timeout we RUN the measurement
    // anyway (never skip) and record the per-run thermal state so warm runs are visible in the data.
    private const val COOL_HEADROOM = 0.80f
    private const val COOLDOWN_POLL_MS = 3_000L
    private const val COOLDOWN_TIMEOUT_MS = 45_000L

    const val DECODE_NICE = -10 // matches production run_generation setpriority(-10)

    /** Short fixed prompt: minimal prefill, output long enough to measure decode cleanly. */
    private const val SYSTEM_PROMPT =
        "You are a careful assistant who writes long, detailed, multi-paragraph prose answers."
    private const val USER_PROMPT =
        "Explain in depth how photosynthesis works, covering both the light-dependent reactions " +
            "and the Calvin cycle, the role of chlorophyll, and why it matters for life on Earth. " +
            "Write several thorough paragraphs."

    data class RunResult(
        val decodeThreads: Int,
        val rep: Int,
        val promptTokens: Int,
        val generated: Int,
        val prefillMs: Double,
        val decodeMs: Double,
        val decodeTokPerSec: Double,
        val wallMs: Long,
        /** WHOLE-CALL process CPU-ms (prefill + decode); the JNI call is opaque so decode alone is not
         *  separable without native changes. A CPU-work/utilization proxy, NOT an energy measurement,
         *  and not decode-only. */
        val callCpuMs: Long,
        /** WHOLE-CALL process CPU-ms per generated token (lower = more efficient). Includes prefill. */
        val callCpuMsPerToken: Double,
        /** Effective concurrent cores over the whole call (callCpuMs/wallMs). */
        val par: Double,
        /** Lowest scaling_cur_freq observed on ANY domain core during the decode window — the true
         *  per-core minimum; reveals cores that parked to a low P-state between decode micro-bursts. */
        val decodeCurMinKhz: Long,
        /** Mean scaling_cur_freq across all per-core samples during the decode window. */
        val decodeCurMeanKhz: Long,
        /** scaling_max_freq clamp (max across the domain) after the call (DVFS ceiling). */
        val clampMaxKhz: Long,
        /** Number of time-snapshots (polls) attributed to the decode window; each covers all domain cores. */
        val freqSamples: Int,
        val headroomStart: Float,
        val headroomEnd: Float,
        val thermalEnd: Int,
        val tempEndC: Float,
        val chargeUahDelta: Long,
    )

    data class ConfigSummary(
        val decodeThreads: Int,
        val runs: Int,
        val meanDecodeTokPerSec: Double,
        /** Mean whole-call (prefill + decode) CPU-ms per generated token. Not decode-only. */
        val meanCallCpuMsPerToken: Double,
        val meanPar: Double,
        val meanDecodeCurMinMhz: Double,
        val meanClampMhz: Double,
        val meanTempEndC: Double,
    )

    data class Result(
        val summaries: List<ConfigSummary>,
        val csvPath: String?,
        /** Runs that executed WITHOUT a confirmed cool baseline (the best-effort settle timed out, e.g.
         *  headroom was unreadable). These runs are still measured and included in [summaries]; each
         *  entry is a "decode=N rep M" label. Empty when every run started from a confirmed cool state. */
        val warmRuns: List<String> = emptyList(),
        /** Non-null ONLY when the sweep was aborted before running because the device could not establish
         *  production-equivalent thread priority (nice -10). In that case [summaries] is empty and no
         *  comparison was made — the results would have been invalid. The original priority and the
         *  production model are still restored normally. */
        val abortedReason: String? = null,
    )

    /**
     * Runs on the single-threaded inference dispatcher (every native call is BLOCKING and must never
     * run on the main thread). The concurrent cpufreq sampler is launched on [Dispatchers.Default] so
     * it polls /sys while the blocking decode holds the inference thread.
     */
    suspend fun run(context: Context, onProgress: (String) -> Unit = {}): Result =
        withContext(InferenceDispatcher.dispatcher) {
            onProgress("Preparing model…")
            LlmModelManager.ensureExtracted(context) { onProgress(it) }
            val path = LlmModelManager.modelFile(context).absolutePath

            // Captured BEFORE the try so the finally can ALWAYS restore the original priority, no matter
            // where inside the try an exception is thrown. Reading the priority does not mutate anything.
            val tid = Process.myTid()
            val originalNice = runCatching { Process.getThreadPriority(tid) }.getOrDefault(0)

            var h = 0L
            var restored = false
            var restoreError: Throwable? = null
            var result: Result? = null
            try {
                // Never hold two model copies: free the production handle before loading the bench
                // handle. INSIDE the try so the finally below restores production even if any step
                // between this release and the end of the sweep throws.
                LlmBridge.release()

                // Use the SAME core domain as production (full performance-core cluster), so only the
                // decode thread count varies across configs — not the set of cores work may land on.
                val cores = coresBySpeed()
                val domain = performanceCores()
                val affinity = domain.toIntArray()
                Log.i(TAG, "cores by speed (fastest first): ${cores.joinToString(",")}; " +
                    "prod perf-core domain=$domain (threshold ${PERF_CORE_FREQ_FRACTION}×top)")

                // Priority parity with production: boost THIS (single-threaded inference dispatcher)
                // thread to nice -10. The same thread then runs loadWithCtx and every generateBenchmark,
                // and the disposable ggml worker pool spawned inside each llama_decode inherits this
                // thread's nice — matching production's per-decode setpriority(-10). Restored in the
                // finally below so the boost never leaks past the sweep. setpriority can fail silently
                // without CAP_SYS_NICE, so we verify the achieved nice and log whether it took effect.
                val setOk = runCatching { Process.setThreadPriority(tid, DECODE_NICE) }
                    .onFailure { Log.w(TAG, "could not set nice $DECODE_NICE: ${it.message}") }
                    .isSuccess
                val niceNow = runCatching { Process.getThreadPriority(tid) }.getOrDefault(originalNice)
                val priorityParity = setOk && niceNow == DECODE_NICE
                Log.i(TAG, "decode thread nice: $originalNice -> $niceNow (target $DECODE_NICE); " +
                    "setpriority ${if (priorityParity) "SUCCEEDED (production priority parity holds)"
                        else "did NOT take effect (no CAP_SYS_NICE?) — results NOT at production priority"}")

                if (!priorityParity) {
                    // SAFEGUARD: without production-equivalent nice -10 on the dispatcher thread, the
                    // benchmark's per-decode workers would run at a different scheduling priority than
                    // production's, so decode tok/s, CPU-work/token, and frequency could NOT be compared
                    // to production. Do NOT run the sweep — abort cleanly rather than draw conclusions
                    // from an invalid comparison. The finally below still restores the original priority
                    // and reloads the production model normally.
                    Log.e(TAG, "ABORT: device could not establish production-equivalent thread priority " +
                        "(target nice $DECODE_NICE, achieved $niceNow). Sweep NOT run — the comparison " +
                        "would be invalid. Restoring priority and production model.")
                    onProgress("Aborted — device could not set production-equivalent priority " +
                        "(nice $DECODE_NICE, got $niceNow); sweep not run.")
                    result = Result(
                        summaries = emptyList(),
                        csvPath = null,
                        warmRuns = emptyList(),
                        abortedReason = "Device could not establish production-equivalent thread " +
                            "priority (target nice $DECODE_NICE, achieved $niceNow). The decode-thread " +
                            "sweep was NOT run: comparing configs at a non-production priority would be " +
                            "invalid. The original priority and production model were restored.",
                    )
                } else {
                    h = LlmBridge.loadWithCtx(path, BENCH_CTX)
                    require(h != 0L) { "benchmark model failed to load" }

                    val all = mutableListOf<RunResult>()
                    val warmRuns = mutableListOf<String>()
                    val promptTokens = LlmBridge.countTokens(h, SYSTEM_PROMPT, USER_PROMPT)
                    Log.i(TAG, "fixed prompt = $promptTokens tokens, maxTokens=$MAX_TOKENS, reps=$REPS")

                    for (rep in 0 until REPS) {
                        // Rotate config order each rep so no thread count always runs hottest/coldest.
                        val order = rotated(DECODE_CONFIGS.toList(), rep)
                        Log.i(TAG, "=== rep ${rep + 1}/$REPS order=${order.joinToString(",")} ===")
                        for (threads in order) {
                            onProgress("Settling before decode=$threads (rep ${rep + 1})…")
                            // Best-effort settle toward a comparable baseline, then RUN the measurement
                            // regardless — never skip. If the device could not confirm a cool baseline
                            // within the timeout (e.g. headroom unreadable), we still run and record the
                            // per-run thermal state so warm runs are visible, rather than producing no
                            // data at all. Rotating config order across reps averages residual drift.
                            val cooled = cooldownToCold(context, onProgress)
                            if (!cooled) {
                                warmRuns.add("decode=$threads rep ${rep + 1}")
                                Log.w(TAG, "decode=$threads rep ${rep + 1}: ran WITHOUT a confirmed cool " +
                                    "baseline (settled ${COOLDOWN_TIMEOUT_MS / 1000}s); thermal state recorded per run")
                                onProgress("Running decode=$threads (rep ${rep + 1}) without a confirmed cool baseline…")
                            }

                            val before = DevicePowerSampler.sample(context)

                            // Concurrent per-core cpufreq sampler for the duration of the blocking call.
                            val samples = CopyOnWriteArrayList<Pair<Long, LongArray>>() // (elapsedMs, perCoreKhz)
                            val stop = AtomicBoolean(false)
                            // ONE shared elapsed-realtime origin for BOTH the sampler and the generation
                            // call, so every sample timestamp and the prefill/decode split are measured
                            // on the same clock. Captured just before the sampler starts.
                            val t0 = SystemClock.elapsedRealtime()
                            val r = coroutineScope {
                                val sampler = launch(Dispatchers.Default) {
                                    while (!stop.get()) {
                                        samples.add(
                                            (SystemClock.elapsedRealtime() - t0) to
                                                freqPerCore(domain, "scaling_cur_freq"),
                                        )
                                        delay(FREQ_POLL_MS)
                                    }
                                }

                                // Run the blocking generation, then stop+join the sampler IMMEDIATELY —
                                // before collecting any further device measurements — so no snapshot taken
                                // after generation returned can enter the window. try/finally guarantees
                                // the sampler is stopped and joined even if the generation throws.
                                val csv: String
                                val wall: Long
                                try {
                                    csv = LlmBridge.generateBenchmark(
                                        benchHandle = h,
                                        systemPrompt = SYSTEM_PROMPT,
                                        userPrompt = USER_PROMPT,
                                        maxTokens = MAX_TOKENS,
                                        decodeThreads = threads,
                                        batchThreads = FIXED_BATCH_THREADS,
                                        affinityCores = affinity,
                                    )
                                    wall = SystemClock.elapsedRealtime() - t0
                                } finally {
                                    stop.set(true)
                                    sampler.join()
                                }

                                // Final device measurement AFTER the sampler has fully stopped and joined.
                                val after = DevicePowerSampler.sample(context)
                                val p = csv.split(',')
                                val prefillMs = p.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                                val decodeMs = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                                val generated = p.getOrNull(2)?.toIntOrNull() ?: 0
                                val cpuDelta = after.cpuMs - before.cpuMs

                                // Decode window on the SHARED clock = [prefillMs, wall]: snapshots taken
                                // after the (short, known) prefill and still within the generation call.
                                // Anything outside that span — before generation started or after it
                                // returned — is excluded, so leftover-prefill and post-call P-states
                                // cannot leak in. Then take the TRUE per-core min and mean across cores.
                                val decodeSnapshots =
                                    samples.filter { it.first >= prefillMs && it.first <= wall }
                                val decodeFreqs = decodeSnapshots
                                    .flatMap { it.second.asList() }
                                    .filter { it > 0 }
                                val curMin = decodeFreqs.minOrNull() ?: -1L
                                val curMean =
                                    if (decodeFreqs.isNotEmpty()) decodeFreqs.average().toLong() else -1L

                                RunResult(
                                    decodeThreads = threads,
                                    rep = rep,
                                    promptTokens = promptTokens,
                                    generated = generated,
                                    prefillMs = prefillMs,
                                    decodeMs = decodeMs,
                                    decodeTokPerSec = if (decodeMs > 0) generated * 1000.0 / decodeMs else 0.0,
                                    wallMs = wall,
                                    callCpuMs = cpuDelta,
                                    callCpuMsPerToken = if (generated > 0) cpuDelta.toDouble() / generated else 0.0,
                                    par = if (wall > 0) cpuDelta.toDouble() / wall.toDouble() else 0.0,
                                    decodeCurMinKhz = curMin,
                                    decodeCurMeanKhz = curMean,
                                    clampMaxKhz = maxFreqKhzAcross(domain, "scaling_max_freq"),
                                    freqSamples = decodeSnapshots.size,
                                    headroomStart = before.headroom,
                                    headroomEnd = after.headroom,
                                    thermalEnd = after.thermalStatus,
                                    tempEndC = after.tempC,
                                    chargeUahDelta = after.chargeUah - before.chargeUah,
                                )
                            }

                            all.add(r)
                            Log.i(TAG, runLine(r))
                            onProgress(
                                "decode=$threads rep ${rep + 1}: ${f1(r.decodeTokPerSec)} tok/s · " +
                                    "${f1(r.callCpuMsPerToken)} cpuMs/tok(call) · par ${f1(r.par)}x · " +
                                    "curMin ${r.decodeCurMinKhz / 1000}MHz",
                            )
                        }
                    }

                    val summaries = DECODE_CONFIGS.map { t -> summarize(t, all.filter { it.decodeThreads == t }) }
                    summaries.forEach { Log.i(TAG, summaryLine(it)) }
                    if (warmRuns.isNotEmpty()) {
                        Log.w(TAG, "${warmRuns.size} run(s) executed without a confirmed cool baseline: ${warmRuns.joinToString("; ")}")
                    }
                    val csvPath = runCatching { writeCsv(context, all) }.getOrNull()
                    Log.i(TAG, "decode sweep done; csv=${csvPath ?: "(write failed)"}; warmRuns=${warmRuns.size}/${all.size}")
                    result = Result(summaries, csvPath, warmRuns.toList())
                }
            } finally {
                // This block runs even if the benchmark model failed to load or the sweep threw, and it
                // covers the production release above plus every preparation step after it.
                // Restore the dispatcher thread's nice on EVERY exit path; don't leak -10 into later
                // production inference, and don't swallow a restore failure — log and verify it.
                val niceRestore = runCatching { Process.setThreadPriority(tid, originalNice) }
                niceRestore.exceptionOrNull()?.let {
                    Log.e(TAG, "FAILED to restore thread nice to $originalNice: ${it.message}")
                }
                val niceAfter = runCatching { Process.getThreadPriority(tid) }.getOrDefault(originalNice)
                if (niceRestore.isSuccess && niceAfter != originalNice) {
                    Log.e(TAG, "thread nice NOT restored: wanted $originalNice, still $niceAfter")
                }
                // Release the benchmark handle (guarded; 0 means load failed and there's nothing to free).
                if (h != 0L) runCatching { LlmBridge.releaseHandle(h) }
                // Restore the production model so the app is not left without its handle. load() is a
                // no-op if a handle already exists and reloads from scratch when it was released above.
                val restore = runCatching { LlmBridge.load(path) }
                restoreError = restore.exceptionOrNull()
                restored = restore.isSuccess && LlmBridge.isLoaded
                if (restored) {
                    Log.i(TAG, "production model restored after sweep")
                } else {
                    Log.e(TAG, "FAILED to restore production model after sweep: ${restoreError?.message}")
                }
            }

            // The sweep must NOT report success if the app was left without its production model.
            // (If the try block threw, that exception has already propagated; we only reach here on a
            // clean sweep, so `result` is non-null.)
            if (!restored) {
                throw IllegalStateException(
                    "decode sweep finished but the production model could not be restored; " +
                        "the app would be left without its model",
                    restoreError,
                )
            }
            result!!
        }

    private fun summarize(threads: Int, runs: List<RunResult>): ConfigSummary {
        fun mean(sel: (RunResult) -> Double) = if (runs.isEmpty()) 0.0 else runs.map(sel).average()
        return ConfigSummary(
            decodeThreads = threads,
            runs = runs.size,
            meanDecodeTokPerSec = mean { it.decodeTokPerSec },
            meanCallCpuMsPerToken = mean { it.callCpuMsPerToken },
            meanPar = mean { it.par },
            meanDecodeCurMinMhz = mean { it.decodeCurMinKhz / 1000.0 },
            meanClampMhz = mean { it.clampMaxKhz / 1000.0 },
            meanTempEndC = mean { it.tempEndC.toDouble() },
        )
    }

    /**
     * Poll until the device is cool or the timeout elapses. "Cool" = thermal status <= MODERATE AND
     * (headroom is unreadable OR headroom <= [COOL_HEADROOM]). This device frequently returns a NaN
     * headroom, so — unlike a strict gate — a NaN reading does NOT block: we fall back to thermal status
     * (otherwise the gate could never confirm cool and would stall on every run). Returns true if the
     * cool state was reached, false on timeout. The caller RUNS the measurement either way (never skips)
     * and records the per-run thermal state, so this is only a best-effort settle, not a hard gate.
     */
    private suspend fun cooldownToCold(context: Context, onProgress: (String) -> Unit): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            val s = DevicePowerSampler.sample(context)
            val cool = s.thermalStatus <= PowerManager.THERMAL_STATUS_MODERATE &&
                (s.headroom.isNaN() || s.headroom <= COOL_HEADROOM)
            if (cool) return true
            val elapsed = SystemClock.elapsedRealtime() - t0
            if (elapsed >= COOLDOWN_TIMEOUT_MS) return false
            onProgress(
                "Cooling… status=${ThermalGovernor.statusName(s.thermalStatus)} " +
                    "headroom=${f2(s.headroom)} (target ≤$COOL_HEADROOM, ${(COOLDOWN_TIMEOUT_MS - elapsed) / 1000}s left)",
            )
            delay(COOLDOWN_POLL_MS)
        }
    }

    /** CPU core indices ordered by max frequency, fastest first (reads /sys cpuinfo_max_freq). */
    private fun coresBySpeed(): List<Int> {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = (0 until n).map { cpu -> cpu to readMaxFreqKhz(cpu) }
        return if (freqs.any { it.second > 0 }) {
            freqs.sortedByDescending { it.second }.map { it.first }
        } else {
            (0 until n).toList()
        }
    }

    /**
     * The production performance-core domain: every core whose cpuinfo_max_freq is >= 0.65× the top
     * core's, fastest first — the exact classification native `detect_cpu_topology()` uses to build
     * `performance_cores` (which `run_generation` pins to). Falls back to the first half of the cores
     * (native's fallback) when /sys is unreadable, and is never empty.
     */
    private fun performanceCores(): List<Int> {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = (0 until n).map { cpu -> cpu to readMaxFreqKhz(cpu) }
        val top = freqs.maxOfOrNull { it.second } ?: -1L
        val perf = if (top > 0) {
            freqs.filter { it.second >= top * PERF_CORE_FREQ_FRACTION }
                .sortedByDescending { it.second }
                .map { it.first }
        } else {
            emptyList()
        }
        return if (perf.isNotEmpty()) perf else (0 until maxOf(1, n / 2)).toList()
    }

    private fun readMaxFreqKhz(cpu: Int): Long =
        runCatching {
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        }.getOrDefault(-1L)

    /** Max of a cpufreq attribute across the given cores, kHz (used for the DVFS clamp ceiling). */
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

    /** Snapshot of a cpufreq attribute for EACH given core, kHz (aligned to [cores]; -1 if unreadable). */
    private fun freqPerCore(cores: List<Int>, attr: String): LongArray =
        LongArray(cores.size) { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu${cores[i]}/cpufreq/$attr").readText().trim().toLong()
            }.getOrDefault(-1L)
        }

    /** Rotate a list left by [by] so config order differs per rep (cancels thermal drift). */
    private fun <T> rotated(xs: List<T>, by: Int): List<T> {
        if (xs.isEmpty()) return xs
        val k = ((by % xs.size) + xs.size) % xs.size
        return xs.drop(k) + xs.take(k)
    }

    private fun writeCsv(context: Context, rows: List<RunResult>): String {
        val dir = File(context.filesDir, "perf").apply { mkdirs() }
        val file = File(dir, "decode-sweep.csv")
        file.printWriter().use { w ->
            w.println(
                "decode_threads,rep,prompt_tokens,generated,prefill_ms,decode_ms,decode_tok_s,wall_ms," +
                    "call_cpu_ms,call_cpu_ms_per_tok,par,decode_cur_min_khz,decode_cur_mean_khz,clamp_max_khz," +
                    "freq_samples,headroom_start,headroom_end,thermal_end,temp_end_c,charge_uah_delta",
            )
            for (r in rows) {
                w.println(
                    "${r.decodeThreads},${r.rep},${r.promptTokens},${r.generated}," +
                        "${f1(r.prefillMs)},${f1(r.decodeMs)},${f1(r.decodeTokPerSec)},${r.wallMs}," +
                        "${r.callCpuMs},${f2d(r.callCpuMsPerToken)},${f2d(r.par)}," +
                        "${r.decodeCurMinKhz},${r.decodeCurMeanKhz},${r.clampMaxKhz},${r.freqSamples}," +
                        "${f2(r.headroomStart)},${f2(r.headroomEnd)},${r.thermalEnd}," +
                        "${f1(r.tempEndC.toDouble())},${r.chargeUahDelta}",
                )
            }
        }
        return file.absolutePath
    }

    private fun runLine(r: RunResult): String =
        "decode=${r.decodeThreads} rep${r.rep} prompt=${r.promptTokens}t gen=${r.generated}t | " +
            "prefill=${r.prefillMs.toInt()}ms decode=${r.decodeMs.toInt()}ms (${f1(r.decodeTokPerSec)} tok/s) | " +
            "callCpuMs/tok=${f1(r.callCpuMsPerToken)} par=${f1(r.par)}x | " +
            "decodeCur ${r.decodeCurMinKhz / 1000}-${r.decodeCurMeanKhz / 1000}MHz (n=${r.freqSamples}) " +
            "clamp=${r.clampMaxKhz / 1000}MHz | headroom ${f2(r.headroomStart)}→${f2(r.headroomEnd)} " +
            "thermal=${ThermalGovernor.statusName(r.thermalEnd)} temp=${f1(r.tempEndC.toDouble())}C"

    private fun summaryLine(s: ConfigSummary): String =
        "SUMMARY decode=${s.decodeThreads} (n=${s.runs}): ${f1(s.meanDecodeTokPerSec)} tok/s · " +
            "${f1(s.meanCallCpuMsPerToken)} cpuMs/tok(call) · par ${f1(s.meanPar)}x · " +
            "decodeCurMin ${f1(s.meanDecodeCurMinMhz)}MHz · clamp ${f1(s.meanClampMhz)}MHz · " +
            "temp ${f1(s.meanTempEndC)}C"

    /** Human-readable report for the Perf Lab card. */
    fun report(r: Result): String = buildString {
        // Aborted before running (could not match production priority) → report that and nothing else;
        // there are no results to compare.
        r.abortedReason?.let {
            appendLine("Decode thread sweep ABORTED — not run.")
            appendLine()
            appendLine(it)
            return@buildString
        }
        appendLine("Decode thread sweep (decode-threads · fixed batch=$FIXED_BATCH_THREADS · prod perf-core domain)")
        appendLine("fixed prompt · maxTok=$MAX_TOKENS · $REPS reps/config · nice=$DECODE_NICE · cold start each")
        appendLine("cpuMs/tok = WHOLE-CALL (prefill+decode) process CPU work (utilization proxy, NOT energy), not decode-only")
        appendLine()
        appendLine("thr  decode tok/s   callCpuMs/tok   par    decodeCurMin   clamp     ΔtempC(end)")
        for (s in r.summaries) {
            appendLine(
                "%-4d %8.1f      %8.1f      %4.1f   %7.0f MHz   %5.0f MHz   %5.1f".format(
                    s.decodeThreads,
                    s.meanDecodeTokPerSec,
                    s.meanCallCpuMsPerToken,
                    s.meanPar,
                    s.meanDecodeCurMinMhz,
                    s.meanClampMhz,
                    s.meanTempEndC,
                ),
            )
        }
        if (r.warmRuns.isNotEmpty()) {
            appendLine()
            appendLine("NOTE ${r.warmRuns.size} run(s) ran WITHOUT a confirmed cool baseline (settle timed out, " +
                "e.g. headroom unreadable) — still measured, thermal state is per-run above: " +
                r.warmRuns.joinToString("; "))
        }
        r.csvPath?.let { appendLine(); appendLine("CSV: $it") }
        appendLine()
        appendLine("Read (HYPOTHESES to weigh, not conclusions):")
        appendLine("  tok/s UP as threads drop + callCpuMs/tok down ~ consistent with governor-stall/over-threading;")
        appendLine("    motivates trying fewer decode threads — not forcing a clock floor or bypassing thermal mgmt.")
        appendLine("  tok/s FLAT + decodeCur near clamp ~ consistent with a bandwidth limit, but frequency alone")
        appendLine("    cannot confirm one; corroborating evidence is needed before concluding it.")
    }

    private fun f1(v: Double) = ((v * 10).toLong() / 10.0).toString()
    private fun f2d(v: Double) = ((v * 100).toLong() / 100.0).toString()
    private fun f2(v: Float) = if (v.isNaN()) "n/a" else ((v * 100).toLong() / 100.0).toString()
}
