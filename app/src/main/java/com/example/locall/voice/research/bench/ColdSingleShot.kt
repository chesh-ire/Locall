package com.example.locall.voice.research.bench

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.research.chunkperf.DevicePowerSampler

/**
 * TEMPORARY benchmark-only probe. Issues EXACTLY ONE standalone generation from the current
 * (ideally cold) thermal state, with a fixed small prompt and ~150 tokens, then stops.
 *
 * Why it exists: the multi-call workloads heat the SoC to SEVERE within ~2-3 calls, so they
 * can't isolate COLD peak prefill/decode speed. This fires a single request so the first (and
 * only) generation runs while the device is still cold.
 *
 * It calls ONLY existing public APIs — no production inference code is modified. Everything
 * except temperature is also emitted by PerfTrace's "[cold-oneshot]" EmailPerf line; this adds
 * temp °C + charge before/after and one consolidated summary line (logcat tag "ColdShot").
 * To remove: delete this file and its button in PerformanceLabScreen.
 */
object ColdSingleShot {

    const val TAG = "ColdShot"

    // Fixed, retrieval-free, fluent-prose prompt so decode runs sustained (no early blank-stop).
    private const val SYSTEM = "You are a helpful assistant. Answer clearly and concisely."
    private const val USER =
        "Explain how a bicycle stays upright while moving, in about 120 words."
    const val MAX_TOKENS = 150

    data class Result(
        val text: String,
        val promptTokens: Int,
        val generatedTokens: Int,
        val prefillMs: Double,
        val decodeMs: Double,
        val wallMs: Long,
        val prefillTokPerSec: Double,
        val decodeTokPerSec: Double,
        val decodeThreads: Int,
        val cpuMsDelta: Long,
        val par: Double,
        val tempStartC: Float,
        val tempEndC: Float,
        val thermalStart: String,
        val thermalEnd: String,
        val headroomStart: Float,
        val headroomEnd: Float,
        val chargeUahDelta: Long,
        val rssMb: Int,
    )

    suspend fun run(context: Context): Result {
        LlmBridge.ensureLoaded(context)
        // Explicit thread count (same choice production makes) so the log is faithful; from a
        // cold status-0 device this resolves to 4.
        val threads = ThermalGovernor.decodeThreads(context)

        val before = DevicePowerSampler.sample(context)
        val t0 = SystemClock.elapsedRealtime()
        val (text, m) = LlmBridge.generateMetered(
            systemPrompt = SYSTEM,
            userPrompt = USER,
            temperature = 0.3f,
            maxTokens = MAX_TOKENS,
            decodeThreads = threads,
            longForm = true, // no blank-stop: reliably generates ~150 tokens
            tag = "cold-oneshot",
        )
        val wall = SystemClock.elapsedRealtime() - t0
        val after = DevicePowerSampler.sample(context)

        val cpuDelta = after.cpuMs - before.cpuMs
        val result = Result(
            text = text,
            promptTokens = m.promptTokens,
            generatedTokens = m.generatedTokens,
            prefillMs = m.prefillMs,
            decodeMs = m.decodeMs,
            wallMs = wall,
            prefillTokPerSec = if (m.prefillMs > 0) m.promptTokens * 1000.0 / m.prefillMs else 0.0,
            decodeTokPerSec = if (m.decodeMs > 0) m.generatedTokens * 1000.0 / m.decodeMs else 0.0,
            decodeThreads = threads,
            cpuMsDelta = cpuDelta,
            par = if (wall > 0) cpuDelta.toDouble() / wall.toDouble() else 0.0,
            tempStartC = before.tempC,
            tempEndC = after.tempC,
            thermalStart = ThermalGovernor.statusName(before.thermalStatus),
            thermalEnd = ThermalGovernor.statusName(after.thermalStatus),
            headroomStart = before.headroom,
            headroomEnd = after.headroom,
            chargeUahDelta = after.chargeUah - before.chargeUah,
            rssMb = (m.rssKb / 1024).toInt(),
        )
        Log.i(TAG, summary(result))
        return result
    }

    private fun summary(r: Result): String = buildString {
        append("COLD single-shot: ")
        append("prompt=").append(r.promptTokens).append("t gen=").append(r.generatedTokens).append("t | ")
        append("prefill=").append(r.prefillMs.toInt()).append("ms (").append(f1(r.prefillTokPerSec)).append(" tok/s) ")
        append("decode=").append(r.decodeMs.toInt()).append("ms (").append(f1(r.decodeTokPerSec)).append(" tok/s) ")
        append("wall=").append(r.wallMs).append("ms | ")
        append("threads=").append(r.decodeThreads).append(" par=").append(f1(r.par)).append("x ")
        append("cpu=").append(r.cpuMsDelta).append("ms | ")
        append("thermal ").append(r.thermalStart).append("→").append(r.thermalEnd).append(" ")
        append("temp ").append(f1(r.tempStartC.toDouble())).append("→").append(f1(r.tempEndC.toDouble())).append("C ")
        append("headroom ").append(f2(r.headroomStart)).append("→").append(f2(r.headroomEnd)).append(" | ")
        append("charge Δ=").append(r.chargeUahDelta).append("uAh rss=").append(r.rssMb).append("MB")
    }

    private fun f1(v: Double) = ((v * 10).toLong() / 10.0).toString()
    private fun f2(v: Float) = if (v.isNaN()) "n/a" else ((v * 100).toLong() / 100.0).toString()
}
