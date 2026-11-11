package com.example.locall.voice.research.chunkperf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChunkPerfDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: ChunkRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetric(metric: ChunkMetricEntity)

    @Query("SELECT * FROM chunk_runs ORDER BY createdAtMs DESC")
    suspend fun allRuns(): List<ChunkRunEntity>

    @Query("SELECT * FROM chunk_runs WHERE id = :runId LIMIT 1")
    suspend fun run(runId: String): ChunkRunEntity?

    /** Most recent resumable run with the given label (RUNNING or PAUSED). */
    @Query(
        "SELECT * FROM chunk_runs WHERE label = :label AND status IN ('RUNNING','PAUSED') " +
            "ORDER BY createdAtMs DESC LIMIT 1",
    )
    suspend fun activeRun(label: String): ChunkRunEntity?

    @Query("SELECT * FROM chunk_runs WHERE label = :label AND status = 'COMPLETED' ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun latestCompleted(label: String): ChunkRunEntity?

    @Query("SELECT * FROM chunk_metrics WHERE runId = :runId ORDER BY chunkIndex ASC")
    suspend fun metricsOf(runId: String): List<ChunkMetricEntity>

    @Query("SELECT chunkIndex FROM chunk_metrics WHERE runId = :runId AND status = 'OK'")
    suspend fun completedIndices(runId: String): List<Int>

    @Query("DELETE FROM chunk_metrics WHERE runId = :runId")
    suspend fun deleteMetricsOf(runId: String)

    @Query("DELETE FROM chunk_runs WHERE id = :runId")
    suspend fun deleteRun(runId: String)

    @Query("DELETE FROM chunk_metrics")
    suspend fun clearMetrics()

    @Query("DELETE FROM chunk_runs")
    suspend fun clearRuns()
}
