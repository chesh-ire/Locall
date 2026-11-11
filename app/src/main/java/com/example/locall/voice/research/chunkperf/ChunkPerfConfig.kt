package com.example.locall.voice.research.chunkperf

import android.os.PowerManager

/**
 * Tunable knobs for one pass over the frozen 105-chunk corpus.
 *
 * All knobs here are RUNTIME-ONLY — they are applied to the single, already-loaded
 * [com.example.locall.voice.LlmBridge] model instance (decode-thread count via
 * `llama_set_n_threads`, sampling via temperature/maxTokens). The model is NEVER
 * reloaded, so baseline and optimized runs share the exact same instance. CPU affinity
 * is handled automatically in native `run_generation` (pins to performance cores).
 */
data class ChunkPerfConfig(
    val label: String,
    /** 0 = auto (follow [com.example.locall.voice.ThermalGovernor] policy). */
    val decodeThreads: Int,
    val maxTokens: Int,
    val temperature: Float,
    /** How many chunks to process per invocation ("5 per session initially"). */
    val batchSizePerSession: Int,
    /** Pause the run when thermal status reaches this level (never bypassed). */
    val pauseThermalStatus: Int,
    /** Pause when 10s thermal headroom forecast reaches this fraction of throttle. */
    val pauseHeadroom: Float,
) {
    fun describe(): String =
        "threads=${if (decodeThreads == 0) "auto" else decodeThreads}, maxTok=$maxTokens, " +
            "temp=$temperature, batch=$batchSizePerSession, " +
            "pause@${com.example.locall.voice.ThermalGovernor.statusName(pauseThermalStatus)}/" +
            "headroom${pauseHeadroom}"

    /** Compact, parseable persistence form. */
    fun encode(): String =
        listOf(label, decodeThreads, maxTokens, temperature, batchSizePerSession,
            pauseThermalStatus, pauseHeadroom).joinToString("|")

    companion object {
        fun baseline() = ChunkPerfConfig(
            label = "baseline",
            decodeThreads = 0, // auto / thermal-adaptive, mirrors the real ChunkAnalyzer path
            maxTokens = 220,
            temperature = 0.3f,
            batchSizePerSession = 5,
            pauseThermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
            pauseHeadroom = 0.95f,
        )

        fun optimized() = ChunkPerfConfig(
            label = "optimized",
            decodeThreads = 4, // fixed high thread count instead of thermal-throttled default
            maxTokens = 220,
            temperature = 0.3f,
            batchSizePerSession = 5,
            pauseThermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
            pauseHeadroom = 0.95f,
        )

        fun decode(s: String): ChunkPerfConfig {
            val p = s.split("|")
            fun str(i: Int, d: String) = p.getOrNull(i) ?: d
            fun int(i: Int, d: Int) = p.getOrNull(i)?.toIntOrNull() ?: d
            fun flt(i: Int, d: Float) = p.getOrNull(i)?.toFloatOrNull() ?: d
            return ChunkPerfConfig(
                label = str(0, "baseline"),
                decodeThreads = int(1, 0),
                maxTokens = int(2, 220),
                temperature = flt(3, 0.3f),
                batchSizePerSession = int(4, 5),
                pauseThermalStatus = int(5, PowerManager.THERMAL_STATUS_SEVERE),
                pauseHeadroom = flt(6, 0.95f),
            )
        }
    }
}
