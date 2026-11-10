package com.example.locall.voice.research.bench.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bench_runs")
data class BenchRunEntity(
    @PrimaryKey val id: String,
    val workload: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val inferenceCalls: Int,
    val promptTokens: Long,
    val generatedTokens: Long,
    val prefillMsTotal: Double,
    val decodeMsTotal: Double,
    val avgDecodeTokPerSec: Double,
    val peakRssMb: Int,
    val thermalStart: String,
    val thermalEnd: String,
    val config: String,
    val sources: String,
    val output: String,
)

@Entity(
    tableName = "bench_inferences",
    indices = [Index("runId")],
)
data class BenchInferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val ordinal: Int,
    val stage: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val prefillMs: Double,
    val decodeMs: Double,
    val wallMs: Long,
    val rssKb: Long,
)
