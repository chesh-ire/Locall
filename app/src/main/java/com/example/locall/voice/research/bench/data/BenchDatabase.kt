package com.example.locall.voice.research.bench.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BenchRunEntity::class, BenchInferenceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BenchDatabase : RoomDatabase() {
    abstract fun dao(): BenchDao

    companion object {
        @Volatile private var instance: BenchDatabase? = null

        fun get(context: Context): BenchDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, BenchDatabase::class.java, "bench.db",
                ).build().also { instance = it }
            }
    }
}
