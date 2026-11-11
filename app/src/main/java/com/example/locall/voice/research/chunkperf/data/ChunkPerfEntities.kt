package com.example.locall.voice.research.chunkperf.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One full pass over the 105 chunks under a single config. */
@Entity(tableName = "chunk_runs")
data class ChunkRunEntity(
    @PrimaryKey val id: String,
    val label: String,
    val configEncoded: String,
    val createdAtMs: Long,
    val status: String, // RunStatus.name
    val chunksCompleted: Int,
    val chunksTotal: Int,
    val reportText: String?, // human-readable report, filled on completion
)

/**
 * One chunk result within a run. PK is deterministic (`runId#index`) so that re-processing
 * an ERROR chunk on resume REPLACES the old row — guaranteeing a chunk is never duplicated
 * and never silently skipped.
 */
@Entity(
    tableName = "chunk_metrics",
    indices = [Index("runId")],
)
data class ChunkMetricEntity(
    @PrimaryKey val id: String, // "$runId#$index"
    val runId: String,
    val chunkIndex: Int,
    val status: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val prefillMs: Double,
    val decodeMs: Double,
    val wallMs: Long,
    val decodeThreadsUsed: Int,
    val cpuMs: Long,
    val rssKb: Long,
    val batteryPctStart: Int,
    val batteryPctEnd: Int,
    val chargeUahStart: Long,
    val chargeUahEnd: Long,
    val tempStartC: Float,
    val tempEndC: Float,
    val thermalStart: Int,
    val thermalEnd: Int,
    val headroomStart: Float,
    val headroomEnd: Float,
    val timestampMs: Long,
    val output: String,
    val error: String?,
    val cooldownMs: Long = 0,
)
