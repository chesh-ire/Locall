package com.example.locall.voice.research.bench

import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
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
 * Benchmark-only validation of the production decode-thread change (4 → 3), the SUSTAINED counterpart to
 * [DecodeThreadSweep]. The sweep ranked configs over a single short 128-token decode; it cannot show
 * whether 3 threads' advantage holds during longer, repeated generation as the SoC heats. This harness
 * answers exactly that, comparing ONLY the new default (3) against the previous baseline (4).
 *
 * Method (small + controlled, NOT the 105-chunk run):
 *   - For each config (3, 4 decode threads; batch fixed = [FIXED_BATCH_THREADS]; pinned to the same
 *     production performance-core domain), run a BLOCK of [SEQ_REQUESTS] back-to-back generations of
 *     [MAX_TOKENS] tokens with NO cooldown between them, so the device heats the way real sequential app
 *     usage heats it. Throughput retention (last request ÷ first request in a block) and the temperature
 *     / thermal-status / cpufreq-clamp climb across the block are the sustained-behaviour signals.
 *   - Cool to a comparable baseline BETWEEN blocks, and run [ROUNDS] rounds with the config order
 *     ALTERNATED each round (3→4, then 4→3) so "which ran first from cold" bias cancels out.
 *
 * Reuses the same safeguards as [DecodeThreadSweep]:
 *   - **Priority parity gate.** Production `run_generation` sets `setpriority(-10)` on the thread that
 *     runs each `llama_decode`; this build's ggml spawns its worker pool inside each decode on that
 *     thread, so workers inherit its nice. The benchmark JNI path sets no priority, so we boost THIS
 *     single-threaded inference-dispatcher thread to nice -10. If that cannot be achieved, the comparison
 *     would be at the wrong priority, so we ABORT without running (see [Result.abortedReason]).
 *   - **Memory-safe handle swap.** Releases the production handle, loads ONE benchmark handle, runs, then
 *     restores the production handle in the finally — the app is never left without its model.
 *   - cpuMs/token is WHOLE-CALL (prefill + decode) process-wide CPU — a CPU-work/utilization proxy, NOT
 *     energy. `chargeUah` is recorded but is unreliable while charging over USB; treat it as noise unless
 *     measured unplugged.
 *
 * Covers the validation checklist: (2) longer generation, (3) sequential/repeated inference, (4) temp &
 * thermal status over time. (1) "normal conversational responses" is a functional in-app check on the new
 * 3-thread default (there is no in-app 4-thread path to compare against). (5) battery: see the USB caveat.
 *
 * Uses ONLY the dormant benchmark JNI path; production inference and native code are untouched.
 * To remove: delete this file (and its button in PerformanceLabScreen).
 */
object SustainedDecodeCompare {

    const val TAG = "SustainedDecode"

    /** The two configs under test: the new production default (3) vs the previous baseline (4). */
    private val CONFIGS = intArrayOf(3, 4)

    private const val FIXED_BATCH_THREADS = 4
    private const val PERF_CORE_FREQ_FRACTION = 0.65

    /** Longer generation than the sweep's 128, to expose sustained behaviour. */
    const val MAX_TOKENS = 512

    /** Back-to-back generations per block (NO cooldown within a block → heat climbs). */
    const val SEQ_REQUESTS = 5

    /** Rounds; order is alternated each round (3→4, 4→3) to cancel which-first-from-cold bias. */
    const val ROUNDS = 2

    private const val BENCH_CTX = 2048
    private const val FREQ_POLL_MS = 150L
    private const val DECODE_NICE = -10

    // Best-effort settle BETWEEN blocks (never within a block). NaN headroom does not block (this device
    // often returns NaN); on timeout we proceed and flag the block as warm-started.
    private const val COOL_HEADROOM = 0.80f
    private const val COOLDOWN_POLL_MS = 3_000L
    private const val COOLDOWN_TIMEOUT_MS = 45_000L

    private const val SYSTEM_PROMPT =
        "You are a careful assistant who writes long, detailed, multi-paragraph prose answers."
    private const val USER_PROMPT =
        "Write an extensive, multi-section essay explaining how photosynthesis works end to end: the " +
            "light-dependent reactions, the Calvin cycle, chlorophyll and accessory pigments, " +
            "chemiosmosis and ATP synthesis, photorespiration, and the C3/C4/CAM adaptations, and why it " +
            "underpins life on Earth. Be thorough and write many detailed paragraphs."

    data class ReqResult(
        val decodeThreads: Int,
        val round: Int,
        /** 0-based position within the back-to-back block (0 = first/coolest, SEQ_REQUESTS-1 = hottest). */
        val seq: Int,
        val promptTokens: Int,
        val generated: Int,
        val prefillMs: Double,
        val decodeMs: Double,
        val decodeTokPerSec: Double,
        val wallMs: Long,
        /** WHOLE-CALL process CPU-ms (prefill + decode); CPU-work proxy, not energy, not decode-only. */
        val callCpuMs: Long,
        val callCpuMsPerToken: Double,
        val par: Double,
        /** Mean scaling_cur_freq across domain cores during the decode window of this request. */
        val decodeCurMeanKhz: Long,
        /** scaling_max_freq clamp (max across domain) after this request — tightens as the SoC throttles. */
        val clampMaxKhz: Long,
        val thermalEnd: Int,
        val tempEndC: Float,
        val headroomEnd: Float,
        /** Unreliable on USB; recorded for completeness only. */
        val chargeUahDelta: Long,
        /** True if the BLOCK this request belongs to started without a confirmed cool baseline. */
        val warmBlock: Boolean,
    )

    data class ConfigSummary(
        val decodeThreads: Int,
        val requests: Int,
        /** Mean decode tok/s across every request of every block for this config. */
        val meanTokPerSec: Double,
        /** Mean tok/s of the FIRST request in each block (coolest). */
        val firstReqTokPerSec: Double,
        /** Mean tok/s of the LAST request in each block (hottest). */
        val lastReqTokPerSec: Double,
        /** lastReqTokPerSec / firstReqTokPerSec × 100 — sustained throughput retention (higher = holds up). */
        val retentionPct: Double,
        val meanCallCpuMsPerToken: Double,
        val meanPar: Double,
        /** Hottest temperature reached in any block for this config. */
        val peakTempC: Double,
        /** Worst (highest) thermal status reached in any block. */
        val maxThermalStatus: Int,
        /** Tightest (lowest) cpufreq clamp observed — the strongest DVFS throttle seen. */
        val minClampMhz: Double,
    )

    data class Result(
        val summaries: List<ConfigSummary>,
        val csvPath: String?,
        /** Blocks that started without a confirmed cool baseline; "round R decode=N" labels. */
        val warmBlocks: List<String> = emptyList(),
        /** Non-null ONLY when aborted before running because production-equivalent priority (nice -10)
         *  could not be set; [summaries] is empty and nothing was measured. Priority and the production
         *  model are still restored normally. */
        val abortedReason: String? = null,
    )

    /** Runs on the single-threaded inference dispatcher (every native call is BLOCKING). */
    suspend fun run(context: Context, onProgress: (String) -> Unit = {}): Result =
        withContext(InferenceDispatcher.dispatcher) {
            onProgress("Preparing model…")
            LlmModelManager.ensureExtracted(context) { onProgress(it) }
            val path = LlmModelManager.modelFile(context).absolutePath

            // Captured BEFORE the try so the finally can always restore the original priority.
            val tid = Process.myTid()
            val originalNice = runCatching { Process.getThreadPriority(tid) }.getOrDefault(0)

            var h = 0L
            var restored = false
            var restoreError: Throwable? = null
            var result: Result? = null
            try {
                // Free the production handle before loading the bench handle. Inside the try so the
                // finally restores production even if a later prep step throws.
                LlmBridge.release()

                val cores = coresBySpeed()
                val domain = performanceCores()
                val affinity = domain.toIntArray()
                Log.i(TAG, "cores by speed: ${cores.joinToString(",")}; prod perf-core domain=$domain")

                // Priority parity: boost this dispatcher thread to nice -10; the same thread runs every
                // generateBenchmark, so its per-decode ggml workers inherit -10 (matching production).
                val setOk = runCatching { Process.setThreadPriority(tid, DECODE_NICE) }
                    .onFailure { Log.w(TAG, "could not set nice $DECODE_NICE: ${it.message}") }
                    .isSuccess
                val niceNow = runCatching { Process.getThreadPriority(tid) }.getOrDefault(originalNice)
                val priorityParity = setOk && niceNow == DECODE_NICE
                Log.i(TAG, "decode thread nice: $originalNice -> $niceNow (target $DECODE_NICE); " +
                    "setpriority ${if (priorityParity) "SUCCEEDED (priority parity holds)"
                        else "did NOT take effect — results NOT at production priority"}")

                if (!priorityParity) {
                    Log.e(TAG, "ABORT: could not establish production-equivalent priority " +
                        "(target nice $DECODE_NICE, achieved $niceNow). Comparison NOT run.")
                    onProgress("Aborted — could not set production-equivalent priority (nice $DECODE_NICE).")
                    result = Result(
                        summaries = emptyList(),
                        csvPath = null,
                        warmBlocks = emptyList(),
                        abortedReason = "Device could not establish production-equivalent thread priority " +
                            "(target nice $DECODE_NICE, achieved $niceNow). The 3-vs-4 sustained " +
                            "comparison was NOT run: comparing at a non-production priority would be " +
                            "invalid. The original priority and production model were restored.",
                    )
                } else {
                    h = LlmBridge.loadWithCtx(path, BENCH_CTX)
                    require(h != 0L) { "benchmark model failed to load" }

                    val promptTokens = LlmBridge.countTokens(h, SYSTEM_PROMPT, USER_PROMPT)
                    Log.i(TAG, "prompt=$promptTokens tok, maxTokens=$MAX_TOKENS, seq=$SEQ_REQUESTS, rounds=$ROUNDS")

                    val all = mutableListOf<ReqResult>()
                    val warmBlocks = mutableListOf<String>()

                    for (round in 0 until ROUNDS) {
                        // Alternate order each round: round 0 = [3,4], round 1 = [4,3], …
                        val order = if (round % 2 == 0) CONFIGS.toList() else CONFIGS.toList().reversed()
                        Log.i(TAG, "=== round ${round + 1}/$ROUNDS order=${order.joinToString(",")} ===")
                        for (threads in order) {
                            onProgress("Settling before decode=$threads block (round ${round + 1})…")
                            val cooled = cooldownToCold(context, onProgress)
                            val warm = !cooled
                            if (warm) {
                                warmBlocks.add("round ${round + 1} decode=$threads")
                                Log.w(TAG, "decode=$threads round ${round + 1}: block started WITHOUT a " +
                                    "confirmed cool baseline")
                            }
                            Log.i(TAG, "--- decode=$threads block (round ${round + 1}), $SEQ_REQUESTS back-to-back ---")

                            for (seq in 0 until SEQ_REQUESTS) {
                                onProgress("decode=$threads r${round + 1} req ${seq + 1}/$SEQ_REQUESTS (sustained)…")
                                val r = runOneRequest(
                                    context, h, threads, affinity, domain, promptTokens, round, seq, warm,
                                )
                                all.add(r)
                                Log.i(TAG, reqLine(r))
                                onProgress(
                                    "decode=$threads r${round + 1} #${seq + 1}: ${f1(r.decodeTokPerSec)} tok/s · " +
                                        "${f1(r.callCpuMsPerToken)} cpuMs/tok · ${f1(r.tempEndC.toDouble())}C · " +
                                        ThermalGovernor.statusName(r.thermalEnd),
                                )
                            }
                        }
                    }

                    val summaries = CONFIGS.map { t -> summarize(t, all.filter { it.decodeThreads == t }) }
                    summaries.forEach { Log.i(TAG, summaryLine(it)) }
                    if (warmBlocks.isNotEmpty()) {
                        Log.w(TAG, "${warmBlocks.size} block(s) started warm: ${warmBlocks.joinToString("; ")}")
                    }
                    val csvPath = runCatching { writeCsv(context, all) }.getOrNull()
                    Log.i(TAG, "sustained compare done; csv=${csvPath ?: "(write failed)"}; warmBlocks=${warmBlocks.size}")
                    result = Result(summaries, csvPath, warmBlocks.toList())
                }
            } finally {
                val niceRestore = runCatching { Process.setThreadPriority(tid, originalNice) }
                niceRestore.exceptionOrNull()?.let {
                    Log.e(TAG, "FAILED to restore thread nice to $originalNice: ${it.message}")
                }
                val niceAfter = runCatching { Process.getThreadPriority(tid) }.getOrDefault(originalNice)
                if (niceRestore.isSuccess && niceAfter != originalNice) {
                    Log.e(TAG, "thread nice NOT restored: wanted $originalNice, still $niceAfter")
                }
                if (h != 0L) runCatching { LlmBridge.releaseHandle(h) }
                val restore = runCatching { LlmBridge.load(path) }
                restoreError = restore.exceptionOrNull()
                restored = restore.isSuccess && LlmBridge.isLoaded
                if (restored) Log.i(TAG, "production model restored after compare")
                else Log.e(TAG, "FAILED to restore production model: ${restoreError?.message}")
            }

            if (!restored) {
                throw IllegalStateException(
                    "sustained compare finished but the production model could not be restored; " +
                        "the app would be left without its model",
                    restoreError,
                )
            }
            result!!
        }

    /** One blocking generation with a concurrent per-core cpufreq sampler, on the shared clock. */
    private suspend fun runOneRequest(
        context: Context,
        h: Long,
        threads: Int,
        affinity: IntArray,
        domain: List<Int>,
        promptTokens: Int,
        round: Int,
        seq: Int,
        warm: Boolean,
    ): ReqResult {
        val before = DevicePowerSampler.sample(context)
        val samples = CopyOnWriteArrayList<Pair<Long, LongArray>>() // (elapsedMsFromT0, perCoreKhz)
        val stop = AtomicBoolean(false)
        // ONE shared origin for sampler + generation so timestamps and the prefill/decode split align.
        val t0 = SystemClock.elapsedRealtime()
        return coroutineScope {
            val sampler = launch(Dispatchers.Default) {
                while (!stop.get()) {
                    samples.add((SystemClock.elapsedRealtime() - t0) to freqPerCore(domain, "scaling_cur_freq"))
                    delay(FREQ_POLL_MS)
                }
            }

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
                // Stop + join the sampler before the final device sample, so no post-generation snapshot
                // enters the decode window.
                stop.set(true)
                sampler.join()
            }

            val after = DevicePowerSampler.sample(context)
            val p = csv.split(',')
            val prefillMs = p.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val decodeMs = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val generated = p.getOrNull(2)?.toIntOrNull() ?: 0
            val cpuDelta = after.cpuMs - before.cpuMs

            // Decode-window samples = [prefillMs, wall] on the shared clock.
            val decodeFreqs = samples
                .filter { it.first >= prefillMs && it.first <= wall }
                .flatMap { it.second.asList() }
                .filter { it > 0 }
            val curMean = if (decodeFreqs.isNotEmpty()) decodeFreqs.average().toLong() else -1L

            ReqResult(
                decodeThreads = threads,
                round = round,
                seq = seq,
                promptTokens = promptTokens,
                generated = generated,
                prefillMs = prefillMs,
                decodeMs = decodeMs,
                decodeTokPerSec = if (decodeMs > 0) generated * 1000.0 / decodeMs else 0.0,
                wallMs = wall,
                callCpuMs = cpuDelta,
                callCpuMsPerToken = if (generated > 0) cpuDelta.toDouble() / generated else 0.0,
                par = if (wall > 0) cpuDelta.toDouble() / wall.toDouble() else 0.0,
                decodeCurMeanKhz = curMean,
                clampMaxKhz = maxFreqKhzAcross(domain, "scaling_max_freq"),
                thermalEnd = after.thermalStatus,
                tempEndC = after.tempC,
                headroomEnd = after.headroom,
                chargeUahDelta = after.chargeUah - before.chargeUah,
                warmBlock = warm,
            )
        }
    }

    private fun summarize(threads: Int, rs: List<ReqResult>): ConfigSummary {
        if (rs.isEmpty()) return ConfigSummary(threads, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0)
        val firsts = rs.filter { it.seq == 0 }.map { it.decodeTokPerSec }
        val lasts = rs.filter { it.seq == SEQ_REQUESTS - 1 }.map { it.decodeTokPerSec }
        val first = if (firsts.isNotEmpty()) firsts.average() else 0.0
        val last = if (lasts.isNotEmpty()) lasts.average() else 0.0
        return ConfigSummary(
            decodeThreads = threads,
            requests = rs.size,
            meanTokPerSec = rs.map { it.decodeTokPerSec }.average(),
            firstReqTokPerSec = first,
            lastReqTokPerSec = last,
            retentionPct = if (first > 0) last / first * 100.0 else 0.0,
            meanCallCpuMsPerToken = rs.map { it.callCpuMsPerToken }.average(),
            meanPar = rs.map { it.par }.average(),
            peakTempC = rs.maxOf { it.tempEndC.toDouble() },
            maxThermalStatus = rs.maxOf { it.thermalEnd },
            minClampMhz = rs.filter { it.clampMaxKhz > 0 }.minOfOrNull { it.clampMaxKhz / 1000.0 } ?: 0.0,
        )
    }

    /**
     * Best-effort settle between blocks: cool = status <= MODERATE AND (headroom unreadable OR <= target).
     * NaN headroom does NOT block (this device returns NaN often). Returns false on timeout; the caller
     * runs the block anyway and flags it warm.
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
                "Cooling… status=${ThermalGovernor.statusName(s.thermalStatus)} headroom=${f2(s.headroom)} " +
                    "(${(COOLDOWN_TIMEOUT_MS - elapsed) / 1000}s left)",
            )
            delay(COOLDOWN_POLL_MS)
        }
    }

    private fun coresBySpeed(): List<Int> {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = (0 until n).map { cpu -> cpu to readMaxFreqKhz(cpu) }
        return if (freqs.any { it.second > 0 }) freqs.sortedByDescending { it.second }.map { it.first }
        else (0 until n).toList()
    }

    private fun performanceCores(): List<Int> {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = (0 until n).map { cpu -> cpu to readMaxFreqKhz(cpu) }
        val top = freqs.maxOfOrNull { it.second } ?: -1L
        val perf = if (top > 0) {
            freqs.filter { it.second >= top * PERF_CORE_FREQ_FRACTION }
                .sortedByDescending { it.second }.map { it.first }
        } else emptyList()
        return if (perf.isNotEmpty()) perf else (0 until maxOf(1, n / 2)).toList()
    }

    private fun readMaxFreqKhz(cpu: Int): Long =
        runCatching {
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        }.getOrDefault(-1L)

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

    private fun freqPerCore(cores: List<Int>, attr: String): LongArray =
        LongArray(cores.size) { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu${cores[i]}/cpufreq/$attr").readText().trim().toLong()
            }.getOrDefault(-1L)
        }

    private fun writeCsv(context: Context, rows: List<ReqResult>): String {
        val dir = File(context.filesDir, "perf").apply { mkdirs() }
        val file = File(dir, "sustained-decode-compare.csv")
        file.printWriter().use { w ->
            w.println(
                "decode_threads,round,seq,prompt_tokens,generated,prefill_ms,decode_ms,decode_tok_s,wall_ms," +
                    "call_cpu_ms,call_cpu_ms_per_tok,par,decode_cur_mean_khz,clamp_max_khz,thermal_end," +
                    "temp_end_c,headroom_end,charge_uah_delta,warm_block",
            )
            for (r in rows) {
                w.println(
                    "${r.decodeThreads},${r.round},${r.seq},${r.promptTokens},${r.generated}," +
                        "${f1(r.prefillMs)},${f1(r.decodeMs)},${f1(r.decodeTokPerSec)},${r.wallMs}," +
                        "${r.callCpuMs},${f2d(r.callCpuMsPerToken)},${f2d(r.par)},${r.decodeCurMeanKhz}," +
                        "${r.clampMaxKhz},${r.thermalEnd},${f1(r.tempEndC.toDouble())}," +
                        "${f2(r.headroomEnd)},${r.chargeUahDelta},${if (r.warmBlock) 1 else 0}",
                )
            }
        }
        return file.absolutePath
    }

    private fun reqLine(r: ReqResult): String =
        "decode=${r.decodeThreads} r${r.round} #${r.seq} gen=${r.generated}t | " +
            "decode=${r.decodeMs.toInt()}ms (${f1(r.decodeTokPerSec)} tok/s) | callCpuMs/tok=${f1(r.callCpuMsPerToken)} " +
            "par=${f1(r.par)}x | curMean ${r.decodeCurMeanKhz / 1000}MHz clamp=${r.clampMaxKhz / 1000}MHz | " +
            "temp=${f1(r.tempEndC.toDouble())}C ${ThermalGovernor.statusName(r.thermalEnd)}" +
            (if (r.warmBlock) " [warm]" else "")

    private fun summaryLine(s: ConfigSummary): String =
        "SUMMARY decode=${s.decodeThreads} (n=${s.requests}): mean ${f1(s.meanTokPerSec)} tok/s · " +
            "first ${f1(s.firstReqTokPerSec)} → last ${f1(s.lastReqTokPerSec)} (retain ${f1(s.retentionPct)}%) · " +
            "${f1(s.meanCallCpuMsPerToken)} cpuMs/tok · par ${f1(s.meanPar)}x · peak ${f1(s.peakTempC)}C · " +
            "maxThermal ${ThermalGovernor.statusName(s.maxThermalStatus)} · minClamp ${f1(s.minClampMhz)}MHz"

    /** Human-readable report for the Perf Lab card. */
    fun report(r: Result): String = buildString {
        r.abortedReason?.let {
            appendLine("Sustained 3-vs-4 compare ABORTED — not run.")
            appendLine()
            appendLine(it)
            return@buildString
        }
        appendLine("Sustained decode compare: 3 vs 4 threads (batch fixed=$FIXED_BATCH_THREADS, prod perf-core domain)")
        appendLine("maxTok=$MAX_TOKENS · $SEQ_REQUESTS back-to-back/block · $ROUNDS rounds (order alternated) · nice=$DECODE_NICE")
        appendLine("cpuMs/tok = WHOLE-CALL process CPU work (utilization proxy, NOT energy); chargeUah unreliable on USB")
        appendLine()
        appendLine("thr  mean tok/s   first→last (retain)      cpuMs/tok   par    peakTemp  maxThermal   minClamp")
        for (s in r.summaries) {
            appendLine(
                "%-4d %8.1f    %6.1f→%-6.1f (%5.1f%%)   %8.1f   %4.1f   %6.1fC   %-10s %6.0fMHz".format(
                    s.decodeThreads,
                    s.meanTokPerSec,
                    s.firstReqTokPerSec,
                    s.lastReqTokPerSec,
                    s.retentionPct,
                    s.meanCallCpuMsPerToken,
                    s.meanPar,
                    s.peakTempC,
                    ThermalGovernor.statusName(s.maxThermalStatus),
                    s.minClampMhz,
                ),
            )
        }
        if (r.warmBlocks.isNotEmpty()) {
            appendLine()
            appendLine("NOTE ${r.warmBlocks.size} block(s) started without a confirmed cool baseline: " +
                r.warmBlocks.joinToString("; "))
        }
        r.csvPath?.let { appendLine(); appendLine("CSV: $it") }
        appendLine()
        appendLine("Read: prefer 3 threads if it holds throughput (mean + retention near 4's) at lower cpuMs/tok,")
        appendLine("  lower peak temp, and a higher/later minClamp. If 3's sustained tok/s falls well short of 4's,")
        appendLine("  the sustained advantage did NOT hold — reconsider. (A clamp drop = DVFS throttle; needs")
        appendLine("  corroboration before asserting a bandwidth cause.)")
    }

    fun configsLabel(): String = CONFIGS.joinToString(" vs ")

    private fun f1(v: Double) = ((v * 10).toLong() / 10.0).toString()
    private fun f2d(v: Double) = ((v * 100).toLong() / 100.0).toString()
    private fun f2(v: Float) = if (v.isNaN()) "n/a" else ((v * 100).toLong() / 100.0).toString()
}
