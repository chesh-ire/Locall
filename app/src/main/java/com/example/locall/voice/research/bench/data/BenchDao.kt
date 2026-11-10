package com.example.locall.voice.research.bench.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BenchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: BenchRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInferences(rows: List<BenchInferenceEntity>)

    @Query("SELECT * FROM bench_runs ORDER BY createdAtMs DESC")
    suspend fun allRuns(): List<BenchRunEntity>

    @Query("SELECT * FROM bench_runs WHERE id = :runId LIMIT 1")
    suspend fun run(runId: String): BenchRunEntity?

    @Query("SELECT * FROM bench_inferences WHERE runId = :runId ORDER BY ordinal ASC")
    suspend fun inferencesOf(runId: String): List<BenchInferenceEntity>

    @Query("DELETE FROM bench_inferences WHERE runId = :runId")
    suspend fun deleteInferencesOf(runId: String)

    @Query("DELETE FROM bench_runs WHERE id = :runId")
    suspend fun deleteRun(runId: String)

    @Query("DELETE FROM bench_runs")
    suspend fun clearRuns()

    @Query("DELETE FROM bench_inferences")
    suspend fun clearInferences()
}
