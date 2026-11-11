package com.example.locall.voice

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight, always-on performance tracer for on-device LLM inference.
 *
 * Every email-analysis flow (KB import, research Q&A, inbox triage, thread
 * summary) funnels through [LlmBridge], so wrapping each native generate call
 * here instruments all of them at once with no caller changes.
 *
 * Headline metrics captured per inference:
 *  - wall ms            : elapsed real time of the native call (user-perceived)
 *  - queue ms           : time spent waiting for the single inference thread
 *                         (exposes serialization/contention when flows overlap)
 *  - cpu ms             : process-wide CPU time delta across ALL cores. This is
 *                         the real CPU cost — it includes the transient native
 *                         compute thread-pool, which a per-thread timer misses.
 *  - par (cpu/wall)     : effective core occupancy, e.g. 3.4x ≈ 3.4 cores busy.
 *                         Directly measures how well threading is utilised.
 *  - thermal status     : PowerManager thermal status (NONE..SHUTDOWN)
 *  - headroom start→end : getThermalHeadroom() trajectory during the call
 *                         (rising toward 1.0 means throttling is imminent)
 *  - token metrics      : prompt/gen tokens, prefill/decode ms, tok/s, rss
 *
 * Filter logs with: adb logcat -s EmailPerf:V
 */
object PerfTrace {

    const val TAG = "EmailPerf"

    /** Master switch. Flip to false to silence all tracing. */
    @Volatile
    var enabled: Boolean = true

    private var appContext: Context? = null

    /** Call once with any Context so thermal state can be read on every inference. */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // Cumulative totals per tag, so a single line also shows the running cost of a flow.
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val cumWallMs = ConcurrentHashMap<String, AtomicLong>()
    private val cumCpuMs = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Captured at the call site *before* dispatching to the inference thread,
     * so the time blocked in the dispatcher queue can be measured.
     */
    fun request(): Long = if (enabled) SystemClock.elapsedRealtime() else 0L

    class Span internal constructor(
        val tag: String,
        val requestedAtMs: Long,
        val startedAtMs: Long,
        val startCpuMs: Long,
        val thermalStatus: Int,
        val headroomStart: Float,
        val decodeThreads: Int,
    )

    /** Call as the first statement inside the inference dispatcher block. */
    fun begin(tag: String, requestedAtMs: Long, decodeThreads: Int): Span? {
        if (!enabled) return null
        val now = SystemClock.elapsedRealtime()
        val status = readThermalStatus()
        return Span(
            tag = tag,
            requestedAtMs = if (requestedAtMs > 0L) requestedAtMs else now,
            startedAtMs = now,
            startCpuMs = Process.getElapsedCpuTime(),
            thermalStatus = status,
            headroomStart = readHeadroom(),
            decodeThreads = decodeThreads,
        )
    }

    /** Call after the native generate returns, inside the dispatcher block. */
    fun end(span: Span?, metricsCsv: String?) {
        if (span == null || !enabled) return
        val endWall = SystemClock.elapsedRealtime()
        val endCpu = Process.getElapsedCpuTime()
        val wallMs = endWall - span.startedAtMs
        val queueMs = span.startedAtMs - span.requestedAtMs
        val cpuMs = endCpu - span.startCpuMs
        val par = if (wallMs > 0) cpuMs.toDouble() / wallMs.toDouble() else 0.0
        val headroomEnd = readHeadroom()

        val n = counts.getOrPut(span.tag) { AtomicInteger() }.incrementAndGet()
        val cumW = cumWallMs.getOrPut(span.tag) { AtomicLong() }.addAndGet(wallMs)
        val cumC = cumCpuMs.getOrPut(span.tag) { AtomicLong() }.addAndGet(cpuMs)

        val tokPart = formatTokens(metricsCsv)
        val line = buildString {
            append("[").append(span.tag).append("] #").append(n)
            append(" wall=").append(wallMs).append("ms")
            if (queueMs > 1) append(" queue=").append(queueMs).append("ms")
            append(" cpu=").append(cpuMs).append("ms")
            append(" par=").append(fmt1(par)).append("x")
            append(" thr=").append(if (span.decodeThreads > 0) span.decodeThreads.toString() else "auto")
            append(" thermal=").append(ThermalGovernor.statusName(span.thermalStatus))
            if (!span.headroomStart.isNaN() || !headroomEnd.isNaN()) {
                append(" headroom=").append(fmt2(span.headroomStart))
                    .append("→").append(fmt2(headroomEnd))
            }
            if (tokPart.isNotEmpty()) append(' ').append(tokPart)
            append(" | cum: n=").append(n)
                .append(" wall=").append(cumW).append("ms")
                .append(" cpu=").append(cumC).append("ms")
        }
        // WARN level when we are clearly in a thermally-stressed or throttled state,
        // so hot runs stand out in logcat.
        if (span.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || headroomEnd >= 0.9f) {
            Log.w(TAG, line)
        } else {
            Log.i(TAG, line)
        }
    }

    /** Log a one-line summary of all flows seen so far (call e.g. at end of an import). */
    fun logSummary(context: String) {
        if (!enabled) return
        if (counts.isEmpty()) {
            Log.i(TAG, "summary [$context]: no inference recorded")
            return
        }
        Log.i(TAG, "===== PERF SUMMARY [$context] =====")
        for (tag in counts.keys.sorted()) {
            val n = counts[tag]?.get() ?: 0
            val w = cumWallMs[tag]?.get() ?: 0L
            val c = cumCpuMs[tag]?.get() ?: 0L
            val avgW = if (n > 0) w / n else 0L
            val par = if (w > 0) c.toDouble() / w.toDouble() else 0.0
            Log.i(
                TAG,
                "  $tag: n=$n totalWall=${w}ms avgWall=${avgW}ms totalCpu=${c}ms par=${fmt1(par)}x",
            )
        }
        Log.i(TAG, "thermal now: ${ThermalGovernor.statusName(readThermalStatus())} headroom=${fmt2(readHeadroom())}")
        Log.i(TAG, "===================================")
    }

    /** Reset cumulative counters (e.g. at the start of a fresh import run). */
    fun reset() {
        counts.clear(); cumWallMs.clear(); cumCpuMs.clear()
    }

    private fun readThermalStatus(): Int {
        val ctx = appContext ?: return PowerManager.THERMAL_STATUS_NONE
        return ThermalGovernor.status(ctx)
    }

    private fun readHeadroom(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
        val ctx = appContext ?: return Float.NaN
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.getThermalHeadroom(10) // 10s forecast; NaN if unavailable or called too often
        } catch (t: Throwable) {
            Float.NaN
        }
    }

    // metricsCsv = "promptTokens,generatedTokens,prefillMs,decodeMs,rssKb" (see LlmBridge.parseMetrics)
    private fun formatTokens(csv: String?): String {
        if (csv.isNullOrBlank()) return ""
        val p = csv.split(',')
        fun d(i: Int) = p.getOrNull(i)?.trim()?.toDoubleOrNull() ?: 0.0
        val prompt = d(0).toInt()
        val gen = d(1).toInt()
        val prefill = d(2)
        val decode = d(3)
        val rssMb = d(4) / 1024.0
        if (prompt == 0 && gen == 0) return ""
        val tokps = if (decode > 0) gen / (decode / 1000.0) else 0.0
        return "tok: p=$prompt g=$gen prefill=${fmt0(prefill)}ms decode=${fmt0(decode)}ms (${fmt1(tokps)} tok/s) rss=${fmt0(rssMb)}MB"
    }

    private fun fmt0(v: Double) = ((v + 0.5).toInt()).toString()
    private fun fmt1(v: Double) = ((v * 10).toLong() / 10.0).toString()
    private fun fmt2(v: Float) = if (v.isNaN()) "n/a" else ((v * 100).toLong() / 100.0).toString()
}
