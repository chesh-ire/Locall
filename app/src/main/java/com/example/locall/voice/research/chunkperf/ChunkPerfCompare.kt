package com.example.locall.voice.research.chunkperf

import android.content.Context
import com.example.locall.voice.research.chunkperf.data.ChunkPerfRepository
import java.io.File

/**
 * Before/after comparison of two completed runs over the IDENTICAL frozen 105 chunks.
 * Because inputs are byte-for-byte the same, every delta is attributable to the config change.
 */
object ChunkPerfCompare {

    fun describe(baseline: ChunkPerfReport, optimized: ChunkPerfReport): String = buildString {
        appendLine("=== 105-Chunk Before/After Comparison ===")
        appendLine("baseline  [${baseline.label}]: ${baseline.configDesc}")
        appendLine("optimized [${optimized.label}]: ${optimized.configDesc}")
        appendLine("(same ${baseline.chunksTotal} frozen chunks; baseline ${baseline.chunksOk} ok, optimized ${optimized.chunksOk} ok)")
        appendLine()
        row("total wall (s)", baseline.totalWallMs / 1000.0, optimized.totalWallMs / 1000.0, lowerBetter = true)
        row("decode t/s", baseline.avgDecodeTokPerSec, optimized.avgDecodeTokPerSec, lowerBetter = false)
        row("prefill t/s", baseline.avgPrefillTokPerSec, optimized.avgPrefillTokPerSec, lowerBetter = false)
        row("avg parallelism", baseline.avgPar, optimized.avgPar, lowerBetter = false)
        row("peak RAM (MB)", baseline.peakRssMb.toDouble(), optimized.peakRssMb.toDouble(), lowerBetter = true)
        row("energy (mAh)", baseline.totalChargeUah / 1000.0, optimized.totalChargeUah / 1000.0, lowerBetter = true)
        row("battery drop (%)", baseline.batteryPctDrop.toDouble(), optimized.batteryPctDrop.toDouble(), lowerBetter = true)
        row("max temp (C)", baseline.tempMaxC.toDouble(), optimized.tempMaxC.toDouble(), lowerBetter = true)
        appendLine()
        val faster = optimized.avgDecodeTokPerSec - baseline.avgDecodeTokPerSec
        val verdict = when {
            baseline.avgDecodeTokPerSec <= 0 -> "inconclusive (no baseline speed)"
            faster > 0 -> "optimized is ${pct(baseline.avgDecodeTokPerSec, optimized.avgDecodeTokPerSec)}% faster at decode"
            faster < 0 -> "optimized is ${pct(optimized.avgDecodeTokPerSec, baseline.avgDecodeTokPerSec)}% slower at decode"
            else -> "no decode-speed change"
        }
        appendLine("VERDICT: $verdict")
    }

    /** Build+persist a comparison of the latest completed baseline and optimized runs. */
    suspend fun compareLatest(context: Context): String? {
        val repo = ChunkPerfRepository.get(context)
        val baseInfo = repo.latestCompleted("baseline") ?: return null
        val optInfo = repo.latestCompleted("optimized") ?: return null
        val base = ChunkPerfReport.build(baseInfo, repo.metrics(baseInfo.id))
        val opt = ChunkPerfReport.build(optInfo, repo.metrics(optInfo.id))
        val text = describe(base, opt)
        runCatching {
            val dir = File(context.filesDir, "perf").apply { mkdirs() }
            File(dir, "chunkperf-compare.txt").writeText(text)
        }
        return text
    }

    private fun StringBuilder.row(label: String, a: Double, b: Double, lowerBetter: Boolean) {
        val delta = b - a
        val better = if (lowerBetter) delta < 0 else delta > 0
        val mark = when {
            delta == 0.0 -> "="
            better -> "✓"
            else -> "✗"
        }
        appendLine("%-18s base=%.1f  opt=%.1f  Δ=%+.1f %s".format(label, a, b, delta, mark))
    }

    private fun pct(from: Double, to: Double): String =
        if (from <= 0) "?" else "%.0f".format((to - from) / from * 100)
}
