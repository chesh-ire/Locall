package com.example.locall.voice.research.chunkperf

import com.example.locall.voice.ThermalGovernor

/** Per-chunk result + the full set of metrics required by the 105-chunk analysis spec. */
data class ChunkMetric(
    val index: Int,
    val status: String, // "OK" | "ERROR"
    // --- tokens & timing ---
    val promptTokens: Int,
    val generatedTokens: Int,
    val prefillMs: Double,
    val decodeMs: Double,
    val wallMs: Long,
    val decodeThreadsUsed: Int,
    // --- cpu & memory ---
    val cpuMs: Long,
    val rssKb: Long,
    // --- battery / energy ---
    val batteryPctStart: Int,
    val batteryPctEnd: Int,
    val chargeUahStart: Long,
    val chargeUahEnd: Long,
    // --- temperature & thermal ---
    val tempStartC: Float,
    val tempEndC: Float,
    val thermalStart: Int,
    val thermalEnd: Int,
    val headroomStart: Float,
    val headroomEnd: Float,
    // --- misc ---
    val timestampMs: Long,
    val output: String,
    val error: String?,
    /** Time spent waiting to cool down BEFORE this chunk ran (0 if no cooldown). */
    val cooldownMs: Long = 0,
) {
    /** Generation speed (tokens/s) over the decode phase. */
    val decodeTokPerSec: Double get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else 0.0

    /** Prompt (prefill) speed (tokens/s). */
    val prefillTokPerSec: Double get() = if (prefillMs > 0) promptTokens * 1000.0 / prefillMs else 0.0

    /** Effective core occupancy: cpu time / wall time (e.g. 3.4 ≈ 3.4 cores busy). */
    val par: Double get() = if (wallMs > 0) cpuMs.toDouble() / wallMs else 0.0

    /** Charge consumed for this chunk in µAh (discharge is positive). 0 if unavailable. */
    val chargeDeltaUah: Long
        get() = if (chargeUahStart > 0 && chargeUahEnd > 0) (chargeUahStart - chargeUahEnd).coerceAtLeast(0) else 0

    val rssMb: Int get() = (rssKb / 1024).toInt()
    val thermalEndName: String get() = ThermalGovernor.statusName(thermalEnd)
}

/** A full pass over the 105 chunks under one config. */
enum class RunStatus { RUNNING, PAUSED, COMPLETED, ERROR }

data class ChunkRunInfo(
    val id: String,
    val label: String,
    val config: ChunkPerfConfig,
    val createdAtMs: Long,
    val status: RunStatus,
    val chunksCompleted: Int,
    val chunksTotal: Int,
)
