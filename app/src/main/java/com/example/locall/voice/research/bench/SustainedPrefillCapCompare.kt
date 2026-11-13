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
import com.example.locall.voice.research.chunkperf.ChunkCorpus
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
 * Benchmark-only PROTOTYPE of the prefill-thread cap (device-heating investigation, 2026-11-13).
 *
 * Why: on this device (SM-S931B, Snapdragon 8 Elite) the thermal HAL throttles off the SKIN sensor with
 * very low trips — skin 40°C = SEVERE, 42°C = CRITICAL — and the topology heuristic classifies all 8
 * Oryon cores (2 prime + 6 performance, no efficiency cores) as "performance", so production prefill runs
 * an 8-thread full-SoC race (`suggested_batch_threads = perf`). That burst is the dominant heat source
 * that tips the device into the "abnormal" SEVERE/CRITICAL state where decode self-clamps to 1 thread.
 *
 * This harness measures whether CAPPING prefill to a subset of the fastest cores keeps the device out of
 * (or at least delays its entry into) that state under realistic, repeated, prefill-heavy load. It is the
 * PREFILL analog of [SustainedDecodeCompare]: it compares the current BASELINE (full performance cluster,
 * = 8 here) against a CAPPED config ([CAP_THREADS]) back-to-back with NO cooldown inside a block, so the
 * SoC heats the way the real 105-chunk analysis heats it. The headline signals are thermal, not speed:
 * peak temperature, the worst thermal status reached, and HOW SOON SEVERE first appears.
 *
 * Isolation of the prefill effect:
 *   - Each [LlmBridge.generateBenchmark] call clears the KV cache and re-runs a full prompt prefill, so a
 *     back-to-back block = repeated prefill races — exactly the production heat pattern.
 *   - Decode is deliberately tiny ([MAX_TOKENS]) and its thread count fixed, so decode thermal-collapse
 *     (a separate decode-thread / pacing problem) does not confound the prefill-cap signal.
 *   - Inputs mirror production [com.example.locall.voice.research.pipeline.ChunkAnalyzer] (same system
 *     prompt, real corpus chunks) so prompt token counts — and thus prefill work and heat — match.
 *
 * Reuses the same safeguards as [SustainedDecodeCompare]:
 *   - **Priority parity gate.** Production `run_generation` sets `setpriority(-10)` on the thread running
 *     each `llama_decode` (prefill is a `llama_decode` of the prompt batch, so its ggml workers inherit
 *     that nice too). The benchmark JNI path sets no priority, so we boost THIS single-threaded
 *     inference-dispatcher thread to nice -10; if that cannot be achieved we ABORT without running, since
 *     comparing at a non-production priority would be invalid.
 *   - **Memory-safe handle swap.** Release the production handle, load ONE benchmark handle, run, then
 *     restore the production handle in the finally — the app is never left without its model.
 *
 * Production inference and native code are UNTOUCHED — this only drives the dormant benchmark JNI path
 * ([LlmBridge.loadWithCtx] / [generateBenchmark]) which takes explicit `batchThreads` + `affinityCores`.
 * To remove: delete this file (and its button in PerformanceLabScreen).
 */
object SustainedPrefillCapCompare {

    const val TAG = "PrefillCap"

    /** Capped prefill-thread counts under test, each compared against the full performance cluster
     *  (baseline, computed at runtime; = 8 on this device). Every config pins to the FASTEST cores
     *  (prime first). Any cap >= the cluster size is dropped (it would duplicate the baseline). */
    val CAP_THREADS = intArrayOf(6, 4)

    /** Production decode-thread default; held fixed so decode never confounds the prefill signal. */
    private const val FIXED_DECODE_THREADS = 3
    private const val PERF_CORE_FREQ_FRACTION = 0.65

    /** Tiny decode on purpose — this isolates the PREFILL-thread thermal effect. */
    const val MAX_TOKENS = 8

    /** Back-to-back prefill-heavy requests per block (NO cooldown within a block → heat climbs). */
    const val SEQ_REQUESTS = 8

    /** Rounds; order is alternated each round (baseline→cap, cap→baseline) to cancel which-first bias. */
    const val ROUNDS = 2

    private const val BENCH_CTX = 2048 // n_batch must exceed prompt tokens for single-shot prefill
    private const val FREQ_POLL_MS = 150L
    private const val DECODE_NICE = -10

    // Best-effort settle BETWEEN blocks (never within a block). NaN headroom does not block (this device
    // often returns NaN); on timeout we proceed and flag the block as warm-started.
    private const val COOL_HEADROOM = 0.80f
    private const val COOLDOWN_POLL_MS = 3_000L
    private const val COOLDOWN_TIMEOUT_MS = 90_000L

    /**
     * Mirror of ChunkAnalyzer.SYSTEM_PROMPT (kept in sync by hand, as in [PrefillThreadSweep]). Copied so
     * this tool has no dependency on the production analyzer; the prompt token count must match production
     * for the prefill timing — and therefore the heat — to be faithful.
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

    data class ReqResult(
        val batchThreads: Int,
        val configLabel: String,
        val round: Int,
        /** 0-based position within the back-to-back block (0 = first/coolest, SEQ_REQUESTS-1 = hottest). */
        val seq: Int,
        val promptTokens: Int,
        val generated: Int,
        val prefillMs: Double,
        val prefillTokPerSec: Double,
        val decodeMs: Double,
        val wallMs: Long,
        /** WHOLE-CALL process CPU-ms (prefill + tiny decode); CPU-work proxy, not energy. */
        val callCpuMs: Long,
        val par: Double,
        /** Mean scaling_cur_freq across domain cores during the PREFILL window of this request. */
        val prefillCurMeanKhz: Long,
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
        val batchThreads: Int,
        val configLabel: String,
        val requests: Int,
        /** Mean prefill tok/s across every request of every block for this config. */
        val meanPrefillTokPerSec: Double,
        /** Mean prefill tok/s of the FIRST request in each block (coolest). */
        val firstReqPrefillTokPerSec: Double,
        /** Mean prefill tok/s of the LAST request in each block (hottest). */
        val lastReqPrefillTokPerSec: Double,
        /** last/first × 100 — sustained prefill-throughput retention (higher = holds up better). */
        val retentionPct: Double,
        val meanPar: Double,
        /** Hottest temperature reached in any block for this config. */
        val peakTempC: Double,
        /** Worst (highest) thermal status reached in any block. THE headline "abnormal-state" signal. */
        val maxThermalStatus: Int,
        /** Mean 0-based seq at which SEVERE first appeared within a block; -1 if it never did. Lower =
         *  tips into the abnormal state sooner. */
        val meanFirstSevereSeq: Double,
        /** Tightest (lowest) cpufreq clamp observed — the strongest DVFS throttle seen. */
        val minClampMhz: Double,
    )

    data class Result(
        val summaries: List<ConfigSummary>,
        val csvPath: String?,
        /** Blocks that started without a confirmed cool baseline; "round R threads=N" labels. */
        val warmBlocks: List<String> = emptyList(),
        /** Non-null ONLY when aborted before running because production-equivalent priority (nice -10)
         *  could not be set; [summaries] is empty and nothing was measured. */
        val abortedReason: String? = null,
        /** Non-null when the cap equals the baseline (e.g. CAP_THREADS >= perf cluster size), so the two
         *  configs are degenerate; still runs, but the comparison is uninformative. */
        val degenerateNote: String? = null,
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
                LlmBridge.release()

                val perf = performanceCores() // fastest-first; the production prefill domain
                val baselineThreads = perf.size
                val domain = perf
                val caps = CAP_THREADS.filter { it < baselineThreads }.distinct().sortedDescending()
                val droppedCaps = CAP_THREADS.filter { it >= baselineThreads }.distinct()
                Log.i(TAG, "perf-core domain (fastest first)=$perf; baseline=$baselineThreads caps=$caps" +
                    (if (droppedCaps.isNotEmpty()) " (dropped ${droppedCaps.joinToString(",")})" else ""))

                val degenerateNote = when {
                    caps.isEmpty() ->
                        "No cap value is below the performance cluster size ($baselineThreads): only the " +
                            "baseline was run, so this cannot show a cap effect on THIS device."
                    droppedCaps.isNotEmpty() ->
                        "Cap value(s) ${droppedCaps.joinToString(",")} >= performance cluster size " +
                            "($baselineThreads) were dropped (they would duplicate the baseline)."
                    else -> null
                }
                degenerateNote?.let { Log.w(TAG, it) }

                // Ordered configs: baseline first, then caps high→low. (round alternation flips this.)
                val configs = buildList {
                    add(baselineThreads to "full($baselineThreads)")
                    caps.forEach { add(it to "cap($it)") }
                }

                // Priority parity: boost this dispatcher thread; the same thread runs every
                // generateBenchmark, so its per-decode ggml workers inherit -10 (matching production).
                val setOk = runCatching { Process.setThreadPriority(tid, DECODE_NICE) }
                    .onFailure { Log.w(TAG, "could not set nice $DECODE_NICE: ${it.message}") }
                    .isSuccess
                val niceNow = runCatching { Process.getThreadPriority(tid) }.getOrDefault(originalNice)
                val priorityParity = setOk && niceNow == DECODE_NICE
                Log.i(TAG, "thread nice: $originalNice -> $niceNow (target $DECODE_NICE); " +
                    "setpriority ${if (priorityParity) "SUCCEEDED (parity holds)"
                        else "did NOT take effect — results NOT at production priority"}")

                if (!priorityParity) {
                    Log.e(TAG, "ABORT: could not establish production-equivalent priority " +
                        "(target nice $DECODE_NICE, achieved $niceNow). Comparison NOT run.")
                    onProgress("Aborted — could not set production-equivalent priority (nice $DECODE_NICE).")
                    result = Result(
                        summaries = emptyList(),
                        csvPath = null,
                        abortedReason = "Device could not establish production-equivalent thread priority " +
                            "(target nice $DECODE_NICE, achieved $niceNow). The prefill-cap comparison was " +
                            "NOT run: comparing at a non-production priority would be invalid. The original " +
                            "priority and production model were restored.",
                    )
                } else {
                    h = LlmBridge.loadWithCtx(path, BENCH_CTX)
                    require(h != 0L) { "benchmark model failed to load" }

                    val corpus = ChunkCorpus.load(context)
                    require(corpus.isNotEmpty()) { "chunk corpus is empty" }
                    Log.i(TAG, "corpus=${corpus.size} chunks, maxTokens=$MAX_TOKENS, seq=$SEQ_REQUESTS, rounds=$ROUNDS")

                    val all = mutableListOf<ReqResult>()
                    val warmBlocks = mutableListOf<String>()

                    for (round in 0 until ROUNDS) {
                        val order = if (round % 2 == 0) configs else configs.reversed()
                        Log.i(TAG, "=== round ${round + 1}/$ROUNDS order=${order.joinToString(",") { it.second }} ===")
                        for ((threads, label) in order) {
                            onProgress("Settling before $label block (round ${round + 1})…")
                            val cooled = cooldownToCold(context, onProgress)
                            val warm = !cooled
                            if (warm) {
                                warmBlocks.add("round ${round + 1} $label")
                                Log.w(TAG, "$label round ${round + 1}: block started WITHOUT a confirmed cool baseline")
                            }
                            val affinity = domain.take(threads).toIntArray()
                            Log.i(TAG, "--- $label block (round ${round + 1}), $SEQ_REQUESTS back-to-back, " +
                                "affinity=${affinity.joinToString(",")} ---")

                            for (seq in 0 until SEQ_REQUESTS) {
                                val chunk = corpus[(round * SEQ_REQUESTS + seq) % corpus.size]
                                onProgress("$label r${round + 1} req ${seq + 1}/$SEQ_REQUESTS (prefill-heavy)…")
                                val r = runOneRequest(
                                    context, h, threads, label, affinity, domain, chunk, round, seq, warm,
                                )
                                all.add(r)
                                Log.i(TAG, reqLine(r))
                                onProgress(
                                    "$label r${round + 1} #${seq + 1}: prefill ${f1(r.prefillTokPerSec)} tok/s · " +
                                        "${f1(r.tempEndC.toDouble())}C · " + ThermalGovernor.statusName(r.thermalEnd),
                                )
                            }
                        }
                    }

                    val summaries = configs.map { (t, label) ->
                        summarize(t, label, all.filter { it.configLabel == label })
                    }
                    summaries.forEach { Log.i(TAG, summaryLine(it)) }
                    if (warmBlocks.isNotEmpty()) {
                        Log.w(TAG, "${warmBlocks.size} block(s) started warm: ${warmBlocks.joinToString("; ")}")
                    }
                    val csvPath = runCatching { writeCsv(context, all) }.getOrNull()
                    Log.i(TAG, "prefill-cap compare done; csv=${csvPath ?: "(write failed)"}; warmBlocks=${warmBlocks.size}")
                    result = Result(summaries, csvPath, warmBlocks.toList(), degenerateNote = degenerateNote)
                }
            } finally {
                val niceRestore = runCatching { Process.setThreadPriority(tid, originalNice) }
                niceRestore.exceptionOrNull()?.let {
                    Log.e(TAG, "FAILED to restore thread nice to $originalNice: ${it.message}")
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
                    "prefill-cap compare finished but the production model could not be restored; " +
                        "the app would be left without its model",
                    restoreError,
                )
            }
            result!!
        }

    /** One blocking prefill-heavy generation with a concurrent per-core cpufreq sampler, on a shared clock. */
    private suspend fun runOneRequest(
        context: Context,
        h: Long,
        threads: Int,
        label: String,
        affinity: IntArray,
        domain: List<Int>,
        chunk: String,
        round: Int,
        seq: Int,
        warm: Boolean,
    ): ReqResult {
        val promptTokens = LlmBridge.countTokens(h, SYSTEM_PROMPT, chunk)
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
                csv = LlmBridge.generateBenchmark(
                    benchHandle = h,
                    systemPrompt = SYSTEM_PROMPT,
                    userPrompt = chunk,
                    maxTokens = MAX_TOKENS,
                    decodeThreads = FIXED_DECODE_THREADS,
                    batchThreads = threads,
                    affinityCores = affinity,
                )
                wall = SystemClock.elapsedRealtime() - t0
            } finally {
                stop.set(true)
                sampler.join()
            }

            val after = DevicePowerSampler.sample(context)
            val p = csv.split(',')
            val prefillMs = p.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val decodeMs = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val generated = p.getOrNull(2)?.toIntOrNull() ?: 0
            val cpuDelta = after.cpuMs - before.cpuMs

            // Prefill-window samples = [0, prefillMs] on the shared clock (the heat burst we care about).
            val prefillFreqs = samples
                .filter { it.first >= 0 && it.first <= prefillMs }
                .flatMap { it.second.asList() }
                .filter { it > 0 }
            val curMean = if (prefillFreqs.isNotEmpty()) prefillFreqs.average().toLong() else -1L

            ReqResult(
                batchThreads = threads,
                configLabel = label,
                round = round,
                seq = seq,
                promptTokens = promptTokens,
                generated = generated,
                prefillMs = prefillMs,
                prefillTokPerSec = if (prefillMs > 0) promptTokens * 1000.0 / prefillMs else 0.0,
                decodeMs = decodeMs,
                wallMs = wall,
                callCpuMs = cpuDelta,
                par = if (wall > 0) cpuDelta.toDouble() / wall.toDouble() else 0.0,
                prefillCurMeanKhz = curMean,
                clampMaxKhz = maxFreqKhzAcross(domain, "scaling_max_freq"),
                thermalEnd = after.thermalStatus,
                tempEndC = after.tempC,
                headroomEnd = after.headroom,
                chargeUahDelta = after.chargeUah - before.chargeUah,
                warmBlock = warm,
            )
        }
    }

    private fun summarize(threads: Int, label: String, rs: List<ReqResult>): ConfigSummary {
        if (rs.isEmpty()) return ConfigSummary(threads, label, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, -1.0, 0.0)
        val firsts = rs.filter { it.seq == 0 }.map { it.prefillTokPerSec }
        val lasts = rs.filter { it.seq == SEQ_REQUESTS - 1 }.map { it.prefillTokPerSec }
        val first = if (firsts.isNotEmpty()) firsts.average() else 0.0
        val last = if (lasts.isNotEmpty()) lasts.average() else 0.0

        // Per-block (round) first-SEVERE seq, averaged over blocks that reached SEVERE.
        val severeThreshold = PowerManager.THERMAL_STATUS_SEVERE
        val severeSeqs = rs.groupBy { it.round }.values.mapNotNull { block ->
            block.filter { it.thermalEnd >= severeThreshold }.minOfOrNull { it.seq }
        }
        val meanFirstSevere = if (severeSeqs.isNotEmpty()) severeSeqs.average() else -1.0

        return ConfigSummary(
            batchThreads = threads,
            configLabel = label,
            requests = rs.size,
            meanPrefillTokPerSec = rs.map { it.prefillTokPerSec }.average(),
            firstReqPrefillTokPerSec = first,
            lastReqPrefillTokPerSec = last,
            retentionPct = if (first > 0) last / first * 100.0 else 0.0,
            meanPar = rs.map { it.par }.average(),
            peakTempC = rs.maxOf { it.tempEndC.toDouble() },
            maxThermalStatus = rs.maxOf { it.thermalEnd },
            meanFirstSevereSeq = meanFirstSevere,
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
        val file = File(dir, "prefill-cap-compare.csv")
        file.printWriter().use { w ->
            w.println(
                "config,batch_threads,round,seq,prompt_tokens,generated,prefill_ms,prefill_tok_s,decode_ms," +
                    "wall_ms,call_cpu_ms,par,prefill_cur_mean_khz,clamp_max_khz,thermal_end,temp_end_c," +
                    "headroom_end,charge_uah_delta,warm_block",
            )
            for (r in rows) {
                w.println(
                    "${r.configLabel},${r.batchThreads},${r.round},${r.seq},${r.promptTokens},${r.generated}," +
                        "${f1(r.prefillMs)},${f1(r.prefillTokPerSec)},${f1(r.decodeMs)},${r.wallMs}," +
                        "${r.callCpuMs},${f2d(r.par)},${r.prefillCurMeanKhz},${r.clampMaxKhz}," +
                        "${r.thermalEnd},${f1(r.tempEndC.toDouble())},${f2(r.headroomEnd)}," +
                        "${r.chargeUahDelta},${if (r.warmBlock) 1 else 0}",
                )
            }
        }
        return file.absolutePath
    }

    private fun reqLine(r: ReqResult): String =
        "${r.configLabel} r${r.round} #${r.seq} prompt=${r.promptTokens}t | " +
            "prefill=${r.prefillMs.toInt()}ms (${f1(r.prefillTokPerSec)} tok/s) | par=${f1(r.par)}x | " +
            "curMean ${r.prefillCurMeanKhz / 1000}MHz clamp=${r.clampMaxKhz / 1000}MHz | " +
            "temp=${f1(r.tempEndC.toDouble())}C ${ThermalGovernor.statusName(r.thermalEnd)}" +
            (if (r.warmBlock) " [warm]" else "")

    private fun summaryLine(s: ConfigSummary): String =
        "SUMMARY ${s.configLabel} (n=${s.requests}): prefill mean ${f1(s.meanPrefillTokPerSec)} tok/s · " +
            "first ${f1(s.firstReqPrefillTokPerSec)} → last ${f1(s.lastReqPrefillTokPerSec)} " +
            "(retain ${f1(s.retentionPct)}%) · par ${f1(s.meanPar)}x · peak ${f1(s.peakTempC)}C · " +
            "maxThermal ${ThermalGovernor.statusName(s.maxThermalStatus)} · " +
            "firstSEVERE@seq ${if (s.meanFirstSevereSeq < 0) "never" else f1(s.meanFirstSevereSeq)} · " +
            "minClamp ${f1(s.minClampMhz)}MHz"

    /** Human-readable report for the Perf Lab card. */
    fun report(r: Result): String = buildString {
        r.abortedReason?.let {
            appendLine("Prefill-cap compare ABORTED — not run.")
            appendLine()
            appendLine(it)
            return@buildString
        }
        appendLine("Sustained prefill-cap compare: full cluster vs caps ${CAP_THREADS.joinToString(",")} " +
            "(decode fixed=$FIXED_DECODE_THREADS)")
        appendLine("prefill-heavy (real chunks, maxTok=$MAX_TOKENS) · $SEQ_REQUESTS back-to-back/block · " +
            "$ROUNDS rounds (order alternated) · nice=$DECODE_NICE · pinned to fastest cores")
        r.degenerateNote?.let { appendLine(); appendLine("WARNING: $it") }
        appendLine()
        appendLine("cfg       prefill mean   first→last (retain)      par    peakTemp  maxThermal   1stSEVERE  minClamp")
        for (s in r.summaries) {
            appendLine(
                "%-9s %8.1f    %6.1f→%-6.1f (%5.1f%%)   %4.1f   %6.1fC   %-10s %-9s %6.0fMHz".format(
                    s.configLabel,
                    s.meanPrefillTokPerSec,
                    s.firstReqPrefillTokPerSec,
                    s.lastReqPrefillTokPerSec,
                    s.retentionPct,
                    s.meanPar,
                    s.peakTempC,
                    ThermalGovernor.statusName(s.maxThermalStatus),
                    if (s.meanFirstSevereSeq < 0) "never" else "#${f1(s.meanFirstSevereSeq)}",
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
        appendLine("Read (the question is HEAT, not speed): the cap WINS if it reaches a LOWER maxThermal,")
        appendLine("  a later/never 1stSEVERE, a lower peakTemp, and a higher/later minClamp — i.e. it keeps the")
        appendLine("  device out of the abnormal state — WITHOUT losing much sustained prefill tok/s. If the cap's")
        appendLine("  thermal profile matches the full cluster, capping prefill threads is not the lever; pursue")
        appendLine("  inter-call pacing or a headroom gate instead.")
    }

    fun configsLabel(): String = "full vs " + CAP_THREADS.joinToString(" vs ")

    private fun f1(v: Double) = ((v * 10).toLong() / 10.0).toString()
    private fun f2d(v: Double) = ((v * 100).toLong() / 100.0).toString()
    private fun f2(v: Float) = if (v.isNaN()) "n/a" else ((v * 100).toLong() / 100.0).toString()
}
