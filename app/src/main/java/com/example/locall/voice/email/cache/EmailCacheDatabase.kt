package com.example.locall.voice.email.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local Gmail cache (feature 4): email metadata + previously-fetched bodies, plus the
 * incremental-sync checkpoint. Reduces redundant network traffic and enables offline reads.
 */
@Database(
    entities = [CachedEmailEntity::class, EmailSyncStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class EmailCacheDatabase : RoomDatabase() {
    abstract fun dao(): EmailCacheDao

    companion object {
        @Volatile private var instance: EmailCacheDatabase? = null

        fun get(context: Context): EmailCacheDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, EmailCacheDatabase::class.java, "email_cache.db",
                ).build().also { instance = it }
            }
    }
}
