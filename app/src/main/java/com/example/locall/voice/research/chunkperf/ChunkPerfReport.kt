package com.example.locall.voice.research.chunkperf

import com.example.locall.voice.ThermalGovernor
import kotlin.math.sqrt

/**
 * Chunk-by-chunk performance analysis over a completed (or partial) 105-chunk run:
 * totals, energy, thermal behaviour, slow-chunk detection, bottlenecks and optimization
 * opportunities. Pure Kotlin over the persisted [ChunkMetric] rows — no device access.
 */
data class ChunkPerfReport(
    val runId: String,
    val label: String,
    val configDesc: String,
    val chunksOk: Int,
    val chunksTotal: Int,
    // timing
    val totalWallMs: Long,
    val totalPrefillMs: Double,
    val totalDecodeMs: Double,
    val avgDecodeTokPerSec: Double,
    val avgPrefillTokPerSec: Double,
    // tokens
    val totalPromptTokens: Long,
    val totalGeneratedTokens: Long,
    // cpu / memory
    val avgPar: Double,
    val peakRssMb: Int,
    val avgRssMb: Int,
    // energy / battery / temp
    val totalChargeUah: Long,
    val batteryPctDrop: Int,
    val tempMinC: Float,
    val tempMaxC: Float,
    val tempAvgC: Float,
    // thermal
    val thermalHistogram: Map<String, Int>,
    val tokPerSecCool: Double, // NONE/LIGHT
    val tokPerSecHot: Double, // MODERATE+
    // findings
    val slowest: List<SlowChunk>,
    val highestRss: List<Pair<Int, Int>>, // index -> MB
    val opportunities: List<String>,
    val perChunkTable: String,
) {
    data class SlowChunk(val index: Int, val tokPerSec: Double, val wallMs: Long, val thermal: String)

    fun describe(): String = buildString {
        appendLine("=== 105-Chunk LLM Performance Report ===")
        appendLine("run=$runId  config[$label]: $configDesc")
        appendLine("chunks: $chunksOk / $chunksTotal processed")
        appendLine()
        appendLine("--- Execution time ---")
        appendLine("total wall:    ${fmt1(totalWallMs / 1000.0)}s")
        appendLine("total prefill: ${fmt1(totalPrefillMs / 1000.0)}s")
        appendLine("total decode:  ${fmt1(totalDecodeMs / 1000.0)}s")
        appendLine("avg speed:     prefill ${fmt1(avgPrefillTokPerSec)} t/s · decode ${fmt1(avgDecodeTokPerSec)} t/s")
        appendLine()
        appendLine("--- Tokens ---")
        appendLine("prompt=$totalPromptTokens  generated=$totalGeneratedTokens")
        appendLine()
        appendLine("--- CPU & memory ---")
        appendLine("avg parallelism: ${fmt1(avgPar)}x cores")
        appendLine("peak RAM: ${peakRssMb}MB · avg RAM: ${avgRssMb}MB")
        appendLine()
        appendLine("--- Energy, battery & temperature ---")
        appendLine(
            if (totalChargeUah > 0) "energy used: ${fmt1(totalChargeUah / 1000.0)} mAh"
            else "energy used: (charge counter unavailable on this device)",
        )
        appendLine("battery drop: $batteryPctDrop%")
        appendLine("temperature: min ${fmt1(tempMinC.toDouble())}C · avg ${fmt1(tempAvgC.toDouble())}C · max ${fmt1(tempMaxC.toDouble())}C")
        appendLine()
        appendLine("--- Thermal behaviour ---")
        thermalHistogram.forEach { (name, n) -> appendLine("  $name: $n chunk(s)") }
        appendLine("decode speed cool (NONE/LIGHT): ${fmt1(tokPerSecCool)} t/s")
        appendLine("decode speed hot  (MODERATE+):  ${fmt1(tokPerSecHot)} t/s")
        if (tokPerSecCool > 0 && tokPerSecHot > 0) {
            val drop = (1 - tokPerSecHot / tokPerSecCool) * 100
            appendLine("thermal slowdown: ${fmt1(drop)}% slower when hot")
        }
        appendLine()
        appendLine("--- Slowest chunks ---")
        slowest.forEach { s ->
            appendLine("  #${s.index}: ${fmt1(s.tokPerSec)} t/s · ${s.wallMs}ms · thermal ${s.thermal}")
        }
        appendLine()
        appendLine("--- Highest memory chunks ---")
        highestRss.forEach { (idx, mb) -> appendLine("  #$idx: ${mb}MB") }
        appendLine()
        appendLine("--- Optimization opportunities ---")
        if (opportunities.isEmpty()) appendLine("  (none detected)")
        opportunities.forEach { appendLine("  • $it") }
        appendLine()
        appendLine("--- Per-chunk detail ---")
        append(perChunkTable)
    }

    companion object {
        fun build(run: ChunkRunInfo, metrics: List<ChunkMetric>): ChunkPerfReport {
            val ok = metrics.filter { it.status == "OK" }
            val n = ok.size.coerceAtLeast(1)

            val totalWall = ok.sumOf { it.wallMs }
            val totalPrefill = ok.sumOf { it.prefillMs }
            val totalDecode = ok.sumOf { it.decodeMs }
            val totalPrompt = ok.sumOf { it.promptTokens.toLong() }
            val totalGen = ok.sumOf { it.generatedTokens.toLong() }
            val avgDecodeTps = if (totalDecode > 0) totalGen * 1000.0 / totalDecode else 0.0
            val avgPrefillTps = if (totalPrefill > 0) totalPrompt * 1000.0 / totalPrefill else 0.0

            val totalCpu = ok.sumOf { it.cpuMs }
            val avgPar = if (totalWall > 0) totalCpu.toDouble() / totalWall else 0.0
            val peakRss = (ok.maxOfOrNull { it.rssKb } ?: 0L).toInt() / 1024
            val avgRss = (ok.sumOf { it.rssKb } / n).toInt() / 1024

            // energy: sum of per-chunk charge deltas; fall back to run-level endpoints
            val perChunkCharge = ok.sumOf { it.chargeDeltaUah }
            val endpointCharge = run {
                val first = ok.firstOrNull { it.chargeUahStart > 0 }
                val last = ok.lastOrNull { it.chargeUahEnd > 0 }
                if (first != null && last != null) (first.chargeUahStart - last.chargeUahEnd).coerceAtLeast(0) else 0
            }
            val totalCharge = if (perChunkCharge > 0) perChunkCharge else endpointCharge

            val batteryDrop = run {
                val start = ok.firstOrNull { it.batteryPctStart >= 0 }?.batteryPctStart ?: 0
                val end = ok.lastOrNull { it.batteryPctEnd >= 0 }?.batteryPctEnd ?: 0
                (start - end).coerceAtLeast(0)
            }

            val temps = ok.flatMap { listOf(it.tempStartC, it.tempEndC) }.filter { it > 0f }
            val tempMin = temps.minOrNull() ?: 0f
            val tempMax = temps.maxOrNull() ?: 0f
            val tempAvg = if (temps.isNotEmpty()) temps.average().toFloat() else 0f

            val histogram = ok.groupingBy { ThermalGovernor.statusName(it.thermalEnd) }.eachCount()

            val cool = ok.filter { it.thermalEnd <= android.os.PowerManager.THERMAL_STATUS_LIGHT }
            val hot = ok.filter { it.thermalEnd >= android.os.PowerManager.THERMAL_STATUS_MODERATE }
            val tpsCool = cool.map { it.decodeTokPerSec }.avgOr0()
            val tpsHot = hot.map { it.decodeTokPerSec }.avgOr0()

            val slowest = ok.sortedBy { it.decodeTokPerSec }.take(5).map {
                SlowChunk(it.index, it.decodeTokPerSec, it.wallMs, it.thermalEndName)
            }
            val highestRss = ok.sortedByDescending { it.rssKb }.take(3).map { it.index to it.rssMb }

            val opportunities = deriveOpportunities(ok, avgPar, tpsCool, tpsHot)

            val perChunkTable = buildString {
                appendLine(
                    "%-5s %-8s %-11s %-10s %-6s %-7s %-9s %-11s".format(
                        "chunk", "prompt", "prefill(ms)", "decode(ms)", "gen", "RAM(MB)", "thermal", "cooldown(ms)",
                    ),
                )
                metrics.sortedBy { it.index }.forEach { m ->
                    appendLine(
                        "%-5d %-8d %-11.0f %-10.0f %-6d %-7d %-9s %-11d".format(
                            m.index, m.promptTokens, m.prefillMs, m.decodeMs, m.generatedTokens,
                            m.rssMb, m.thermalEndName, m.cooldownMs,
                        ),
                    )
                }
            }

            return ChunkPerfReport(
                runId = run.id,
                label = run.label,
                configDesc = run.config.describe(),
                chunksOk = ok.size,
                chunksTotal = run.chunksTotal,
                totalWallMs = totalWall,
                totalPrefillMs = totalPrefill,
                totalDecodeMs = totalDecode,
                avgDecodeTokPerSec = avgDecodeTps,
                avgPrefillTokPerSec = avgPrefillTps,
                totalPromptTokens = totalPrompt,
                totalGeneratedTokens = totalGen,
                avgPar = avgPar,
                peakRssMb = peakRss,
                avgRssMb = avgRss,
                totalChargeUah = totalCharge,
                batteryPctDrop = batteryDrop,
                tempMinC = tempMin,
                tempMaxC = tempMax,
                tempAvgC = tempAvg,
                thermalHistogram = histogram,
                tokPerSecCool = tpsCool,
                tokPerSecHot = tpsHot,
                slowest = slowest,
                highestRss = highestRss,
                opportunities = opportunities,
                perChunkTable = perChunkTable,
            )
        }

        private fun deriveOpportunities(
            ok: List<ChunkMetric>,
            avgPar: Double,
            tpsCool: Double,
            tpsHot: Double,
        ): List<String> {
            val out = mutableListOf<String>()
            val threads = ok.map { it.decodeThreadsUsed }.filter { it > 0 }
            val avgThreads = if (threads.isNotEmpty()) threads.average() else 0.0
            if (avgPar in 0.1..(avgThreads - 1.0) && avgThreads >= 2) {
                out += "CPU under-utilized: avg ${fmt1(avgPar)}x of ${avgThreads.toInt()} decode threads — " +
                    "raising decodeThreads or batch threads may speed up decode."
            }
            if (tpsCool > 0 && tpsHot > 0 && tpsHot < tpsCool * 0.85) {
                val drop = (1 - tpsHot / tpsCool) * 100
                out += "Thermal throttling costs ${fmt1(drop)}% decode speed when hot — " +
                    "smaller session batches or active cooldown would keep more chunks in the fast regime."
            }
            // decode-speed variance
            val tps = ok.map { it.decodeTokPerSec }.filter { it > 0 }
            if (tps.size >= 2) {
                val mean = tps.average()
                val sd = sqrt(tps.sumOf { (it - mean) * (it - mean) } / tps.size)
                if (sd > mean * 0.25) {
                    out += "High decode-speed variance (σ=${fmt1(sd)} t/s vs mean ${fmt1(mean)}) — " +
                        "performance is inconsistent chunk-to-chunk; investigate the slowest chunks above."
                }
            }
            val rssSpread = (ok.maxOfOrNull { it.rssKb } ?: 0) - (ok.minOfOrNull { it.rssKb } ?: 0)
            if (rssSpread < 20_000) {
                out += "RAM is stable across chunks (±${rssSpread / 1024}MB) — context size is adequate; " +
                    "no memory pressure from this workload."
            }
            return out
        }

        private fun List<Double>.avgOr0() = if (isEmpty()) 0.0 else average()
        private fun fmt1(d: Double) = "%.1f".format(d)
    }
}

private fun fmt1(d: Double) = "%.1f".format(d)
