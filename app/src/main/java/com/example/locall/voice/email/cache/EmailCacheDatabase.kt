package com.example.locall.voice.email.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local Gmail cache (feature 4): email metadata + previously-fetched bodies, the
 * incremental-sync checkpoint, and compact per-thread summaries. Reduces redundant network
 * traffic and enables offline reads.
 */
@Database(
    entities = [CachedEmailEntity::class, EmailSyncStateEntity::class, ThreadSummaryEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class EmailCacheDatabase : RoomDatabase() {
    abstract fun dao(): EmailCacheDao

    companion object {
        @Volatile private var instance: EmailCacheDatabase? = null

        // v1 → v2: add the thread_summaries table. Non-destructive — existing cached mail is kept.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `thread_summaries` (" +
                        "`threadId` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`decisions` TEXT NOT NULL, " +
                        "`actions` TEXT NOT NULL, " +
                        "`deadlines` TEXT NOT NULL, " +
                        "`coveredIds` TEXT NOT NULL, " +
                        "`messageCount` INTEGER NOT NULL, " +
                        "`latestInternalDateMs` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`threadId`))",
                )
            }
        }

        fun get(context: Context): EmailCacheDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, EmailCacheDatabase::class.java, "email_cache.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
