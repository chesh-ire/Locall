package com.example.locall.voice.research.chunkperf.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChunkRunEntity::class, ChunkMetricEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class ChunkPerfDatabase : RoomDatabase() {
    abstract fun dao(): ChunkPerfDao

    companion object {
        @Volatile private var instance: ChunkPerfDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chunk_metrics ADD COLUMN cooldownMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): ChunkPerfDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, ChunkPerfDatabase::class.java, "chunkperf.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
