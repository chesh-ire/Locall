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
 * Benchmark-only A/B of **thermal-aware duty cycling** (Route A, step 1 of the thermal-reduction plan):
 * does inserting short idle windows between safe generation segments delay the SoC's DVFS throttle and
 * improve SUSTAINED end-to-end throughput, versus running decode flat out?
 *
 * The production decode loop is one uninterrupted tight loop (no yielding), which drives this device
 * cold→SEVERE in ~2 min and clamps all cores from ~4.3GHz to ~2.0GHz while thermal STATUS still reads
 * LIGHT. The dormant [LlmBridge.generateBenchmarkPaced] path adds a sleep of [PaceConfig.pauseMs] ms
 * after every [PaceConfig.pauseEveryK] generated tokens — at a token boundary, so the KV cache and
 * sampler are fully consistent and the output is byte-identical to the unpaced path. This harness runs
 * the two configs under identical sustained load and reports whether the pauses actually bought a later
 * throttle onset and better retention, and at what end-to-end-throughput cost.
 *
 * Method mirrors [SustainedDecodeCompare] exactly (same proven scaffold):
 *   - Fixed decode threads ([DECODE_THREADS] = production default) and batch ([FIXED_BATCH_THREADS]);
 *     the ONLY variable is the pace. Each config runs a BLOCK of [SEQ_REQUESTS] back-to-back
 *     [MAX_TOKENS]-token generations with NO cooldown within the block (so heat climbs like real
 *     sequential use), pinned to the production perf-core domain.
 *   - Cool to a comparable baseline BETWEEN blocks; [ROUNDS] rounds with config order ALTERNATED each
 *     round so "which ran first from cold" bias cancels out.
 *
 * Safeguards carried over verbatim from [SustainedDecodeCompare]:
 *   - **Priority-parity gate.** Production decode runs at nice -10; the bench JNI path sets no priority,
 *     so we boost this single-threaded inference-dispatcher thread (which runs every generation) to
 *     nice -10. If that can't be achieved we ABORT without running — a comparison at the wrong priority
 *     would be invalid (see [Result.abortedReason]).
 *   - **Memory-safe handle swap.** Release the production handle, load ONE bench handle, run, then
 *     restore production in the finally — the app is never left without its model.
 *   - cpuMs/token is WHOLE-CALL (prefill + decode) process-wide CPU — a CPU-work/utilization proxy, NOT
 *     energy. `chargeUah` is unreliable while charging over USB.
 *
 * KEY METRIC (the honest one): end-to-end tok/s COUNTS the sleeps (user-visible), so pacing only "wins"
 * if paced end-to-end ≥ unpaced end-to-end over a sustained block — i.e. avoiding the throttle recovered
 * more than the sleeps cost. We report BOTH end-to-end tok/s (incl. sleep) and active tok/s (compute-only,
 * excl. sleep) so the trade is explicit. Throttle onset is measured off `scaling_max_freq` (the clamp the
 * thermal STATUS can't see), relative to a cold baseline sampled once before the run.
 *
 * To trace the (K, D) duty-cycle curve, re-run with different [PaceConfig] constants below. Uses ONLY the
 * dormant benchmark JNI path; production inference and native code are untouched. To remove: delete this
 * file (and its button in PerformanceLabScreen, its [BenchmarkRunState.Kind] entry, and the service wiring).
 */
object PacedDecodeCompare {

    const val TAG = "PacedDecode"

    data class PaceConfig(
        /** Short label for logs/CSV/report. */
        val label: String,
        /** FIXED pacing: sleep after every this-many generated tokens (0 ⇒ no fixed pauses). */
        val pauseEveryK: Int,
        /** Sleep duration in ms at each pause boundary (0 ⇒ no pauses). */
        val pauseMs: Int,
        /** When true, use ADAPTIVE pacing (pause only when the clamp is engaged) instead of fixed. */
        val adaptive: Boolean = false,
        /**
         * Adaptive: pause when scaling_max_freq (max across pinned cores) falls below this ABSOLUTE kHz.
         * Must be set near the HARD-throttle floor (~1017-1401 MHz on this device), NOT a fraction of the
         * idle cold ceiling: under load the clamp is always depressed to ~1700-2745 MHz, so a fraction of
         * the ~4473 MHz idle ceiling would pause during normal generation and act like always-on pacing.
         */
        val clampThresholdKhz: Long = 0,
        /** Adaptive: how often (in generated tokens) to read the clamp and decide whether to pause. */
        val checkEveryK: Int = 0,
    )

    /**
     * The configs under test. First is the unpaced baseline; the rest are pacing candidates (fixed or
     * adaptive). The clean fixed-pacing run showed fixed K16/120ms caps thermals (MODERATE vs CRITICAL, no
     * collapse) but pays a flat ~16% throughput tax on EVERY request — so this A/B tests ADAPTIVE pacing,
     * which pauses only when the DVFS clamp has engaged (cool/early requests run untaxed). Kept to a
     * 2-config A/B to match the validated runtime envelope (stays within the benchmark wake-lock ceiling).
     */
    private val CONFIGS = listOf(
        PaceConfig("unpaced", pauseEveryK = 0, pauseMs = 0),
        PaceConfig(
            "adaptive-1500MHz-120ms",
            pauseEveryK = 0,
            pauseMs = 120,
            adaptive = true,
            clampThresholdKhz = 1_500_000, // engage only at the hard-throttle floor, not normal generation
            checkEveryK = 8,
        ),
    )

    /** Fixed to the production decode-thread default so the ONLY variable across configs is the pace. */
    private const val DECODE_THREADS = 3
    private const val FIXED_BATCH_THREADS = 4
    private const val PERF_CORE_FREQ_FRACTION = 0.65

    const val MAX_TOKENS = 512

    /**
     * Back-to-back generations per block (NO cooldown within a block → heat climbs). Kept at 5: seq=8
     * (~30 min of near-100% CPU) reliably trips Samsung's /abnormal scheduler throttler MID-RUN on this
     * device, collapsing par to ~1 and voiding the data before thermal throttling can manifest. The one
     * clean run we got (seq=5, ~14 min) stayed under the /abnormal trigger while still reaching SEVERE, so
     * 5 is the viable envelope here. (On this device the scheduler penalty, not thermal DVFS, is the
     * dominant sustained-performance limiter for a CPU-pegging app — see [cpuRestrictionReason].)
     */
    const val SEQ_REQUESTS = 5

    /** Rounds; order is alternated each round to cancel which-first-from-cold bias. */
    const val ROUNDS = 2

    private const val BENCH_CTX = 2048
    private const val FREQ_POLL_MS = 150L
    private const val DECODE_NICE = -10

    /** A request counts as "clamped" if its scaling_max_freq fell below this fraction of the cold baseline. */
    private const val CLAMP_FRACTION = 0.95

    // Settle BETWEEN blocks so every block starts from a comparable, recovered state, gating on the DVFS
    // clamp (scaling_max_freq) — not the lagging thermal STATUS (which floors at ~MODERATE and is blind to
    // the clamp). A block is "recovered" when status <= MODERATE AND either:
    //   (a) FAST PATH — the clamp is back at the cold ceiling (>= CLAMP_RELEASE_FRACTION of it), i.e. truly
    //       cold; passes quickly, or
    //   (b) PLATEAU — after a minimum settle the clamp has STOPPED RISING (not up > PLATEAU_TOLERANCE vs the
    //       previous poll), i.e. the device has recovered as far as it will under the day's conditions.
    // Either must hold for [COOLDOWN_STABLE_POLLS] consecutive polls (no transient). The plateau fallback is
    // why we no longer fight an unreachable fixed target: after a morning of runs this device's achievable
    // idle ceiling drifts down (e.g. 3283 -> 2438), so a fixed 0.95x-cold gate would time out every block
    // and flag them all warm. Warm is now flagged ONLY on a true timeout (couldn't even plateau — still
    // actively hot). On timeout we proceed and flag the block warm.
    private const val CLAMP_RELEASE_FRACTION = 0.95
    private const val PLATEAU_TOLERANCE = 0.02
    private const val COOLDOWN_STABLE_POLLS = 3
    private const val COOLDOWN_MIN_SETTLE_MS = 20_000L
    private const val COOLDOWN_POLL_MS = 3_000L
    private const val COOLDOWN_TIMEOUT_MS = 180_000L

    private const val SYSTEM_PROMPT =
        "You are a careful assistant who writes long, detailed, multi-paragraph prose answers."
    private const val USER_PROMPT =
        "Write an extensive, multi-section essay explaining how photosynthesis works end to end: the " +
            "light-dependent reactions, the Calvin cycle, chlorophyll and accessory pigments, " +
            "chemiosmosis and ATP synthesis, photorespiration, and the C3/C4/CAM adaptations, and why it " +
            "underpins life on Earth. Be thorough and write many detailed paragraphs."

    data class ReqResult(
        val label: String,
        val pauseEveryK: Int,
        val pauseMs: Int,
        val round: Int,
        /** 0-based position within the back-to-back block (0 = first/coolest, SEQ_REQUESTS-1 = hottest). */
        val seq: Int,
        val promptTokens: Int,
        val generated: Int,
        val prefillMs: Double,
        /** Decode wall INCLUDING the duty-cycle sleeps (user-visible / end-to-end). */
        val decodeMs: Double,
        /** Total time spent sleeping during decode (0 for unpaced). */
        val sleptMs: Double,
        /** Compute-only decode throughput = generated / (decodeMs - sleptMs). */
        val activeTokPerSec: Double,
        /** End-to-end decode throughput = generated / decodeMs (counts the sleeps). The honest number. */
        val endToEndTokPerSec: Double,
        val wallMs: Long,
        /** WHOLE-CALL process CPU-ms (prefill + decode); CPU-work proxy, not energy, not decode-only. */
        val callCpuMs: Long,
        val callCpuMsPerToken: Double,
        val par: Double,
        /** Mean scaling_cur_freq across domain cores during the decode window of this request. */
        val decodeCurMeanKhz: Long,
        /** scaling_max_freq clamp (max across domain) after this request — tightens as the SoC throttles. */
        val clampMaxKhz: Long,
        /** True if clampMaxKhz fell below CLAMP_FRACTION of the cold baseline (DVFS throttle engaged). */
        val clamped: Boolean,
        val thermalEnd: Int,
        val tempEndC: Float,
        val headroomEnd: Float,
        /** Unreliable on USB; recorded for completeness only. */
        val chargeUahDelta: Long,
        /** True if the BLOCK this request belongs to started without a confirmed cool baseline. */
        val warmBlock: Boolean,
    )

    data class ConfigSummary(
        val label: String,
        val pauseEveryK: Int,
        val pauseMs: Int,
        val requests: Int,
        /** Mean END-TO-END decode tok/s (incl. sleeps) across every request. The honest sustained number. */
        val meanEndToEndTokPerSec: Double,
        /** Mean ACTIVE (compute-only) decode tok/s across every request. */
        val meanActiveTokPerSec: Double,
        /** End-to-end tok/s of the FIRST request in each block (coolest). */
        val firstReqTokPerSec: Double,
        /** End-to-end tok/s of the LAST request in each block (hottest). */
        val lastReqTokPerSec: Double,
        /** lastReqTokPerSec / firstReqTokPerSec × 100 — sustained throughput retention (higher = holds up). */
        val retentionPct: Double,
        /**
         * Mean seconds of in-block decode wall until scaling_max_freq first clamped (throttle onset).
         * Blocks that never clamped contribute the full block decode wall (a floor, flagged separately).
         * Higher = pacing delayed the throttle.
         */
        val meanClampOnsetSec: Double,
        /** How many blocks never clamped at all (onset was floored to the full block wall for those). */
        val blocksNeverClamped: Int,
        val meanCallCpuMsPerToken: Double,
        val meanPar: Double,
        val peakTempC: Double,
        val maxThermalStatus: Int,
        /** Tightest (lowest) cpufreq clamp observed — the strongest DVFS throttle seen. */
        val minClampMhz: Double,
    )

    data class Result(
        val coldClampMhz: Double,
        val summaries: List<ConfigSummary>,
        val csvPath: String?,
        val warmBlocks: List<String> = emptyList(),
        /** Non-null ONLY when aborted before running (app in /abnormal cgroup, or nice -10 could not be set);
         *  nothing was measured and the production model/priority are restored. */
        val abortedReason: String? = null,
    )

    /** Runs on the single-threaded inference dispatcher (every native call is BLOCKING). */
    suspend fun run(context: Context, onProgress: (String) -> Unit = {}): Result =
        withContext(InferenceDispatcher.dispatcher) {
            onProgress("Preparing model…")
            LlmModelManager.ensureExtracted(context) { onProgress(it) }
            val path = LlmModelManager.modelFile(context).absolutePath

            val tid = Process.myTid()
            val originalNice = runCatching { Process.getThreadPriority(tid) }.getOrDefault(0)

            var h = 0L
            var restored = false
            var restoreError: Throwable? = null
            var result: Result? = null
            try {
                // Sample the cold cpufreq ceiling BEFORE releasing/loading the bench model, while the device
                // is quiescent. Model extraction/load is CPU+IO heavy and depresses scaling_max_freq, which
                // under-sampled the ceiling last run (2438 vs the true ~3283 idle plateau) and left the
                // cooldown release target too lenient. performanceCores()/maxFreqKhzAcross only read sysfs.
                val domain = performanceCores()
                val affinity = domain.toIntArray()
                val coldClampKhz = maxFreqKhzAcross(domain, "scaling_max_freq")
                val hwMaxKhz = maxFreqKhzAcross(domain, "cpuinfo_max_freq")
                Log.i(TAG, "prod perf-core domain=$domain")
                Log.i(TAG, "cold ceiling (scaling_max, pre-load) = ${coldClampKhz / 1000}MHz " +
                    "(hw cpuinfo_max=${hwMaxKhz / 1000}MHz); clamped := scaling_max_freq < " +
                    "${(coldClampKhz * CLAMP_FRACTION).toLong() / 1000}MHz; cooldown release target ≥ " +
                    "${(coldClampKhz * CLAMP_RELEASE_FRACTION).toLong() / 1000}MHz. START MUST BE COLD.")

                // Guard against Samsung's /abnormal app-throttler (and any cpuset restriction). After long
                // sustained 100% CPU, the OS moves the app to the /abnormal cgroup and strips its affinity
                // down to a few non-prime cores (observed: 0-1,4-5, prime 6/7 removed) — par collapses to ~1
                // and throughput tanks for a SCHEDULER reason, NOT thermal. Any data gathered then is void,
                // so abort before running. Clear it with `adb shell am force-stop com.example.locall` + relaunch.
                val restriction = cpuRestrictionReason(domain)
                restriction?.let { Log.e(TAG, "CPU RESTRICTION DETECTED: $it") }

                LlmBridge.release()

                val setOk = runCatching { Process.setThreadPriority(tid, DECODE_NICE) }
                    .onFailure { Log.w(TAG, "could not set nice $DECODE_NICE: ${it.message}") }
                    .isSuccess
                val niceNow = runCatching { Process.getThreadPriority(tid) }.getOrDefault(originalNice)
                val priorityParity = setOk && niceNow == DECODE_NICE
                Log.i(TAG, "decode thread nice: $originalNice -> $niceNow (target $DECODE_NICE); " +
                    "setpriority ${if (priorityParity) "SUCCEEDED (priority parity holds)"
                        else "did NOT take effect — results NOT at production priority"}")

                val abortReason: String? = when {
                    restriction != null -> restriction
                    !priorityParity -> "Device could not establish production-equivalent thread priority " +
                        "(target nice $DECODE_NICE, achieved $niceNow). The paced-vs-unpaced comparison " +
                        "was NOT run: comparing at a non-production priority would be invalid. The " +
                        "original priority and production model were restored."
                    else -> null
                }
                if (abortReason != null) {
                    Log.e(TAG, "ABORT: $abortReason")
                    onProgress("Aborted — see report (CPU-restricted or wrong priority).")
                    result = Result(
                        coldClampMhz = coldClampKhz / 1000.0,
                        summaries = emptyList(),
                        csvPath = null,
                        warmBlocks = emptyList(),
                        abortedReason = abortReason,
                    )
                } else {
                    h = LlmBridge.loadWithCtx(path, BENCH_CTX)
                    require(h != 0L) { "benchmark model failed to load" }

                    val promptTokens = LlmBridge.countTokens(h, SYSTEM_PROMPT, USER_PROMPT)
                    Log.i(TAG, "prompt=$promptTokens tok, decode=$DECODE_THREADS, maxTokens=$MAX_TOKENS, " +
                        "seq=$SEQ_REQUESTS, rounds=$ROUNDS, configs=${CONFIGS.joinToString { it.label }}")

                    val all = mutableListOf<ReqResult>()
                    val warmBlocks = mutableListOf<String>()
                    // Samsung can re-flag the app into /abnormal MID-run (sustained CPU), silently collapsing
                    // par to ~1 and voiding everything after. Check before each request and bail immediately
                    // so we lose seconds, not 30 min — see [cpuRestrictionReason].
                    var midRunAbort: String? = null

                    rounds@ for (round in 0 until ROUNDS) {
                        val order = if (round % 2 == 0) CONFIGS else CONFIGS.reversed()
                        Log.i(TAG, "=== round ${round + 1}/$ROUNDS order=${order.joinToString { it.label }} ===")
                        for (cfg in order) {
                            onProgress("Settling before ${cfg.label} block (round ${round + 1})…")
                            val cooled = cooldownToCold(context, domain, coldClampKhz, onProgress)
                            val warm = !cooled
                            if (warm) {
                                warmBlocks.add("round ${round + 1} ${cfg.label}")
                                Log.w(TAG, "${cfg.label} round ${round + 1}: block started WITHOUT a " +
                                    "confirmed cool baseline")
                            }
                            Log.i(TAG, "--- ${cfg.label} block (round ${round + 1}), $SEQ_REQUESTS back-to-back ---")

                            for (seq in 0 until SEQ_REQUESTS) {
                                val restr = cpuRestrictionReason(domain)
                                if (restr != null) {
                                    midRunAbort = restr
                                    Log.e(TAG, "MID-RUN ABORT (app re-flagged /abnormal at round " +
                                        "${round + 1} ${cfg.label} req ${seq + 1}): $restr")
                                    onProgress("Aborted mid-run — app re-flagged /abnormal (see report).")
                                    break@rounds
                                }
                                onProgress("${cfg.label} r${round + 1} req ${seq + 1}/$SEQ_REQUESTS (sustained)…")
                                val r = runOneRequest(
                                    context, h, cfg, affinity, domain, promptTokens, round, seq, warm,
                                    coldClampKhz,
                                )
                                all.add(r)
                                Log.i(TAG, reqLine(r))
                                onProgress(
                                    "${cfg.label} r${round + 1} #${seq + 1}: ${f1(r.endToEndTokPerSec)} e2e tok/s " +
                                        "(${f1(r.activeTokPerSec)} active) · ${f1(r.callCpuMsPerToken)} cpuMs/tok · " +
                                        "${f1(r.tempEndC.toDouble())}C · ${ThermalGovernor.statusName(r.thermalEnd)}" +
                                        (if (r.clamped) " · CLAMPED" else ""),
                                )
                            }
                        }
                    }

                    if (midRunAbort != null) {
                        // Partial data is void (the /abnormal penalty confounds everything after it hit). Set
                        // the abort result and fall through to the finally, which restores the production model.
                        result = Result(
                            coldClampMhz = coldClampKhz / 1000.0,
                            summaries = emptyList(),
                            csvPath = null,
                            warmBlocks = warmBlocks.toList(),
                            abortedReason = "$midRunAbort\n\n(Aborted mid-run after ${all.size} request(s); " +
                                "partial data discarded as void.)",
                        )
                        Log.i(TAG, "paced compare ABORTED mid-run after ${all.size} requests")
                    } else {
                        val summaries = CONFIGS.map { cfg -> summarize(cfg, all.filter { it.label == cfg.label }) }
                        summaries.forEach { Log.i(TAG, summaryLine(it)) }
                        if (warmBlocks.isNotEmpty()) {
                            Log.w(TAG, "${warmBlocks.size} block(s) started warm: ${warmBlocks.joinToString("; ")}")
                        }
                        val csvPath = runCatching { writeCsv(context, all) }.getOrNull()
                        Log.i(TAG, "paced compare done; csv=${csvPath ?: "(write failed)"}; warmBlocks=${warmBlocks.size}")
                        result = Result(coldClampKhz / 1000.0, summaries, csvPath, warmBlocks.toList())
                    }
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
                    "paced compare finished but the production model could not be restored; " +
                        "the app would be left without its model",
                    restoreError,
                )
            }
            result!!
        }

    /** One blocking paced generation with a concurrent per-core cpufreq sampler, on the shared clock. */
    private suspend fun runOneRequest(
        context: Context,
        h: Long,
        cfg: PaceConfig,
        affinity: IntArray,
        domain: List<Int>,
        promptTokens: Int,
        round: Int,
        seq: Int,
        warm: Boolean,
        coldClampKhz: Long,
    ): ReqResult {
        val before = DevicePowerSampler.sample(context)
        val samples = CopyOnWriteArrayList<Pair<Long, LongArray>>() // (elapsedMsFromT0, perCoreKhz)
        val stop = AtomicBoolean(false)
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
                csv = if (cfg.adaptive) {
                    LlmBridge.generateBenchmarkAdaptive(
                        benchHandle = h,
                        systemPrompt = SYSTEM_PROMPT,
                        userPrompt = USER_PROMPT,
                        maxTokens = MAX_TOKENS,
                        decodeThreads = DECODE_THREADS,
                        batchThreads = FIXED_BATCH_THREADS,
                        affinityCores = affinity,
                        checkEveryK = cfg.checkEveryK,
                        pauseMs = cfg.pauseMs,
                        clampThresholdKhz = cfg.clampThresholdKhz,
                    )
                } else {
                    LlmBridge.generateBenchmarkPaced(
                        benchHandle = h,
                        systemPrompt = SYSTEM_PROMPT,
                        userPrompt = USER_PROMPT,
                        maxTokens = MAX_TOKENS,
                        decodeThreads = DECODE_THREADS,
                        batchThreads = FIXED_BATCH_THREADS,
                        affinityCores = affinity,
                        pauseEveryK = cfg.pauseEveryK,
                        pauseMs = cfg.pauseMs,
                    )
                }
                wall = SystemClock.elapsedRealtime() - t0
            } finally {
                stop.set(true)
                sampler.join()
            }

            val after = DevicePowerSampler.sample(context)
            // CSV: prefill_ms,decode_ms,generated,rss_kb,slept_ms
            val p = csv.split(',')
            val prefillMs = p.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val decodeMs = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val generated = p.getOrNull(2)?.toIntOrNull() ?: 0
            val sleptMs = p.getOrNull(4)?.toDoubleOrNull() ?: 0.0
            val activeDecodeMs = (decodeMs - sleptMs).coerceAtLeast(0.0)
            val cpuDelta = after.cpuMs - before.cpuMs

            val decodeFreqs = samples
                .filter { it.first >= prefillMs && it.first <= wall }
                .flatMap { it.second.asList() }
                .filter { it > 0 }
            val curMean = if (decodeFreqs.isNotEmpty()) decodeFreqs.average().toLong() else -1L
            val clampMaxKhz = maxFreqKhzAcross(domain, "scaling_max_freq")
            val clamped = coldClampKhz > 0 && clampMaxKhz in 1 until (coldClampKhz * CLAMP_FRACTION).toLong()

            ReqResult(
                label = cfg.label,
                pauseEveryK = cfg.pauseEveryK,
                pauseMs = cfg.pauseMs,
                round = round,
                seq = seq,
                promptTokens = promptTokens,
                generated = generated,
                prefillMs = prefillMs,
                decodeMs = decodeMs,
                sleptMs = sleptMs,
                activeTokPerSec = if (activeDecodeMs > 0) generated * 1000.0 / activeDecodeMs else 0.0,
                endToEndTokPerSec = if (decodeMs > 0) generated * 1000.0 / decodeMs else 0.0,
                wallMs = wall,
                callCpuMs = cpuDelta,
                callCpuMsPerToken = if (generated > 0) cpuDelta.toDouble() / generated else 0.0,
                par = if (wall > 0) cpuDelta.toDouble() / wall.toDouble() else 0.0,
                decodeCurMeanKhz = curMean,
                clampMaxKhz = clampMaxKhz,
                clamped = clamped,
                thermalEnd = after.thermalStatus,
                tempEndC = after.tempC,
                headroomEnd = after.headroom,
                chargeUahDelta = after.chargeUah - before.chargeUah,
                warmBlock = warm,
            )
        }
    }

    private fun summarize(cfg: PaceConfig, rs: List<ReqResult>): ConfigSummary {
        if (rs.isEmpty()) {
            return ConfigSummary(cfg.label, cfg.pauseEveryK, cfg.pauseMs, 0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0, 0.0)
        }
        val firsts = rs.filter { it.seq == 0 }.map { it.endToEndTokPerSec }
        val lasts = rs.filter { it.seq == SEQ_REQUESTS - 1 }.map { it.endToEndTokPerSec }
        val first = if (firsts.isNotEmpty()) firsts.average() else 0.0
        val last = if (lasts.isNotEmpty()) lasts.average() else 0.0

        // Throttle onset per block (round): cumulative in-block decode wall until the first clamped request.
        // Blocks that never clamp contribute the full block decode wall (a floor) and are counted separately.
        val onsets = mutableListOf<Double>()
        var neverClamped = 0
        for (round in rs.map { it.round }.distinct()) {
            val block = rs.filter { it.round == round }.sortedBy { it.seq }
            var cum = 0.0
            var onset: Double? = null
            for (r in block) {
                cum += r.decodeMs
                if (r.clamped) { onset = cum; break }
            }
            if (onset != null) onsets.add(onset) else { onsets.add(cum); neverClamped++ }
        }

        return ConfigSummary(
            label = cfg.label,
            pauseEveryK = cfg.pauseEveryK,
            pauseMs = cfg.pauseMs,
            requests = rs.size,
            meanEndToEndTokPerSec = rs.map { it.endToEndTokPerSec }.average(),
            meanActiveTokPerSec = rs.map { it.activeTokPerSec }.average(),
            firstReqTokPerSec = first,
            lastReqTokPerSec = last,
            retentionPct = if (first > 0) last / first * 100.0 else 0.0,
            meanClampOnsetSec = if (onsets.isNotEmpty()) onsets.average() / 1000.0 else 0.0,
            blocksNeverClamped = neverClamped,
            meanCallCpuMsPerToken = rs.map { it.callCpuMsPerToken }.average(),
            meanPar = rs.map { it.par }.average(),
            peakTempC = rs.maxOf { it.tempEndC.toDouble() },
            maxThermalStatus = rs.maxOf { it.thermalEnd },
            minClampMhz = rs.filter { it.clampMaxKhz > 0 }.minOfOrNull { it.clampMaxKhz / 1000.0 } ?: 0.0,
        )
    }

    /**
     * Settle until the device has recovered: status ≤ MODERATE AND either the clamp is back at the cold
     * ceiling (fast path) OR, after a minimum settle, the clamp has stopped rising (plateau) — held for
     * [COOLDOWN_STABLE_POLLS] consecutive polls. Returns false on [COOLDOWN_TIMEOUT_MS] timeout; the caller
     * runs the block anyway and flags it warm. The plateau fallback adapts to the device's current
     * achievable ceiling so blocks start from a comparable recovered state even when a true-cold ceiling is
     * unreachable (see the constants' comment). Returns true also reports via the progress line whether it
     * hit the cold target or just plateaued, so per-block recovery level is visible.
     */
    private suspend fun cooldownToCold(
        context: Context,
        domain: List<Int>,
        coldCeilingKhz: Long,
        onProgress: (String) -> Unit,
    ): Boolean {
        val target = (coldCeilingKhz * CLAMP_RELEASE_FRACTION).toLong()
        val t0 = SystemClock.elapsedRealtime()
        var prevClamp = -1L
        var stable = 0
        while (true) {
            val clamp = maxFreqKhzAcross(domain, "scaling_max_freq")
            val s = DevicePowerSampler.sample(context)
            val elapsed = SystemClock.elapsedRealtime() - t0
            val coolStatus = s.thermalStatus <= PowerManager.THERMAL_STATUS_MODERATE
            val fullyCold = coldCeilingKhz > 0 && clamp >= target
            // Plateau = clamp did not rise more than PLATEAU_TOLERANCE vs the previous poll (recovery done).
            val plateaued = prevClamp > 0 && clamp in 1..(prevClamp + (prevClamp * PLATEAU_TOLERANCE).toLong())
            val recovered = coolStatus && (fullyCold || (elapsed >= COOLDOWN_MIN_SETTLE_MS && plateaued))
            if (recovered) {
                stable++
                if (stable >= COOLDOWN_STABLE_POLLS) {
                    Log.i(TAG, "cooldown recovered after ${elapsed / 1000}s: clamp=${clamp / 1000}MHz " +
                        "(cold target ${target / 1000}MHz) via ${if (fullyCold) "COLD" else "PLATEAU"}")
                    return true
                }
            } else {
                stable = 0
            }
            prevClamp = clamp
            if (elapsed >= COOLDOWN_TIMEOUT_MS) {
                Log.w(TAG, "cooldown TIMEOUT after ${elapsed / 1000}s: clamp=${clamp / 1000}MHz " +
                    "status=${ThermalGovernor.statusName(s.thermalStatus)} — block will start warm")
                return false
            }
            onProgress(
                "Cooling… clamp=${clamp / 1000}MHz (cold≥${target / 1000}) " +
                    "status=${ThermalGovernor.statusName(s.thermalStatus)} recovered=$stable/$COOLDOWN_STABLE_POLLS " +
                    "(${(COOLDOWN_TIMEOUT_MS - elapsed) / 1000}s left)",
            )
            delay(COOLDOWN_POLL_MS)
        }
    }

    /**
     * Returns a non-null ABORT reason if the app is in Samsung's `/abnormal` cgroup — the OS anti-abuse
     * throttler that, after long sustained 100% CPU, strips the app's affinity to a few non-prime cores
     * (observed 0-1,4-5, prime 6/7 removed) so throughput collapses for a SCHEDULER reason, not thermal.
     * Any run then is void. If instead only the prime cores are missing (e.g. a plain foreground cpuset,
     * not `/abnormal`), it logs a warning but does NOT abort. Read-only (/proc/self). Clear `/abnormal`
     * with `adb shell am force-stop com.example.locall` then relaunch.
     */
    private fun cpuRestrictionReason(domain: List<Int>): String? {
        val cgroup = runCatching { File("/proc/self/cgroup").readText() }.getOrDefault("")
        val allowed = runCatching {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim()
        }.getOrNull()
        val allowedSet = parseCpuList(allowed)

        val abnormal = cgroup.lineSequence().filter { it.contains("abnormal", ignoreCase = true) }.toList()
        if (abnormal.isNotEmpty()) {
            return "App is in Samsung's /abnormal cgroup (${abnormal.joinToString("; ") { it.trim() }}); " +
                "effective allowed CPUs=[$allowed] vs perf-core domain=$domain. The OS has throttled the app " +
                "after sustained high CPU — throughput would be scheduler-capped, NOT thermal, so the run is " +
                "VOID. Clear it: `adb shell am force-stop com.example.locall`, relaunch, and avoid long " +
                "back-to-back sessions that re-trigger it."
        }
        // Prime cores = the two highest-freq entries of the domain (sorted desc by performanceCores()).
        val missingPrime = if (allowedSet.isNotEmpty()) domain.take(2).filter { it !in allowedSet } else emptyList()
        if (missingPrime.isNotEmpty()) {
            Log.w(TAG, "WARNING: effective allowed CPUs=[$allowed] exclude prime cores $missingPrime " +
                "(domain=$domain). Keep the Perf Lab screen in the foreground (top-app) for valid numbers.")
        }
        return null
    }

    /** Parse a Linux cpu-list like "0-1,4-5" into a set of core indices. */
    private fun parseCpuList(spec: String?): Set<Int> {
        if (spec.isNullOrBlank()) return emptySet()
        val out = mutableSetOf<Int>()
        for (part in spec.split(',')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            if ('-' in p) {
                val a = p.substringBefore('-').trim().toIntOrNull()
                val b = p.substringAfter('-').trim().toIntOrNull()
                if (a != null && b != null) for (i in a..b) out.add(i)
            } else {
                p.toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
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
        val file = File(dir, "paced-decode-compare.csv")
        file.printWriter().use { w ->
            w.println(
                "label,pause_every_k,pause_ms,round,seq,prompt_tokens,generated,prefill_ms,decode_ms," +
                    "slept_ms,active_tok_s,e2e_tok_s,wall_ms,call_cpu_ms,call_cpu_ms_per_tok,par," +
                    "decode_cur_mean_khz,clamp_max_khz,clamped,thermal_end,temp_end_c,headroom_end," +
                    "charge_uah_delta,warm_block",
            )
            for (r in rows) {
                w.println(
                    "${r.label},${r.pauseEveryK},${r.pauseMs},${r.round},${r.seq},${r.promptTokens}," +
                        "${r.generated},${f1(r.prefillMs)},${f1(r.decodeMs)},${f1(r.sleptMs)}," +
                        "${f1(r.activeTokPerSec)},${f1(r.endToEndTokPerSec)},${r.wallMs},${r.callCpuMs}," +
                        "${f2d(r.callCpuMsPerToken)},${f2d(r.par)},${r.decodeCurMeanKhz},${r.clampMaxKhz}," +
                        "${if (r.clamped) 1 else 0},${r.thermalEnd},${f1(r.tempEndC.toDouble())}," +
                        "${f2(r.headroomEnd)},${r.chargeUahDelta},${if (r.warmBlock) 1 else 0}",
                )
            }
        }
        return file.absolutePath
    }

    private fun reqLine(r: ReqResult): String =
        "${r.label} r${r.round} #${r.seq} gen=${r.generated}t | decode=${r.decodeMs.toInt()}ms " +
            "(slept ${r.sleptMs.toInt()}ms) e2e=${f1(r.endToEndTokPerSec)} active=${f1(r.activeTokPerSec)} tok/s | " +
            "callCpuMs/tok=${f1(r.callCpuMsPerToken)} par=${f1(r.par)}x | curMean ${r.decodeCurMeanKhz / 1000}MHz " +
            "clamp=${r.clampMaxKhz / 1000}MHz${if (r.clamped) " CLAMPED" else ""} | " +
            "temp=${f1(r.tempEndC.toDouble())}C ${ThermalGovernor.statusName(r.thermalEnd)}" +
            (if (r.warmBlock) " [warm]" else "")

    private fun summaryLine(s: ConfigSummary): String =
        "SUMMARY ${s.label} (n=${s.requests}): e2e ${f1(s.meanEndToEndTokPerSec)} tok/s " +
            "(active ${f1(s.meanActiveTokPerSec)}) · first ${f1(s.firstReqTokPerSec)} → last " +
            "${f1(s.lastReqTokPerSec)} (retain ${f1(s.retentionPct)}%) · onset ${f1(s.meanClampOnsetSec)}s " +
            "(${s.blocksNeverClamped} block(s) never clamped) · ${f1(s.meanCallCpuMsPerToken)} cpuMs/tok · " +
            "par ${f1(s.meanPar)}x · peak ${f1(s.peakTempC)}C · maxThermal " +
            "${ThermalGovernor.statusName(s.maxThermalStatus)} · minClamp ${f1(s.minClampMhz)}MHz"

    /** Human-readable report for the Perf Lab card. */
    fun report(r: Result): String = buildString {
        r.abortedReason?.let {
            appendLine("Paced-vs-unpaced compare ABORTED — not run.")
            appendLine()
            appendLine(it)
            return@buildString
        }
        appendLine("Paced decode compare (Route A duty cycling): " + CONFIGS.joinToString(" vs ") { it.label })
        appendLine("decode=$DECODE_THREADS (prod default), batch=$FIXED_BATCH_THREADS, maxTok=$MAX_TOKENS · " +
            "$SEQ_REQUESTS back-to-back/block · $ROUNDS rounds (order alternated) · nice=$DECODE_NICE")
        appendLine("cold cpufreq ceiling=${f1(r.coldClampMhz)}MHz (clamped := below ${f1(r.coldClampMhz * CLAMP_FRACTION)}MHz)")
        appendLine("e2e tok/s COUNTS sleeps (user-visible); active tok/s excludes them. cpuMs/tok = whole-call " +
            "CPU-work proxy, NOT energy; chargeUah unreliable on USB.")
        appendLine()
        appendLine("config            e2e tok/s  active   first→last (retain)     onset   cpuMs/tok  par   peakT   maxTherm   minClamp")
        for (s in r.summaries) {
            appendLine(
                "%-16s %8.1f %8.1f   %6.1f→%-6.1f (%5.1f%%) %6.1fs %9.1f %5.1f %6.1fC  %-9s %6.0fMHz".format(
                    s.label,
                    s.meanEndToEndTokPerSec,
                    s.meanActiveTokPerSec,
                    s.firstReqTokPerSec,
                    s.lastReqTokPerSec,
                    s.retentionPct,
                    s.meanClampOnsetSec,
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
        appendLine("Read: pacing WINS only if its e2e tok/s ≥ unpaced AND it delays onset / improves retention —")
        appendLine("  i.e. the avoided throttle recovered more than the sleeps cost. If paced e2e tok/s is well")
        appendLine("  below unpaced with no onset/retention gain, this (K,D) is too aggressive — retune. Peak temp")
        appendLine("  may not move (both can still reach SEVERE); the win to look for is LATER onset + higher")
        appendLine("  retention + lower cpuMs/tok, not a cooler peak. To trace the curve, re-run with other (K,D).")
    }

    fun configsLabel(): String = CONFIGS.joinToString(" vs ") { it.label }

    private fun f1(v: Double) = ((v * 10).toLong() / 10.0).toString()
    private fun f2d(v: Double) = ((v * 100).toLong() / 100.0).toString()
    private fun f2(v: Float) = if (v.isNaN()) "n/a" else ((v * 100).toLong() / 100.0).toString()
}
