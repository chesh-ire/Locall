package com.example.locall.voice.music.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local listening history: every requested track plus how often and how recently. Powers personal
 * music commands without a fixed in-app catalogue. Fully offline — no network, no backend.
 */
@Database(
    entities = [PlayedTrackEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MusicHistoryDatabase : RoomDatabase() {
    abstract fun dao(): MusicHistoryDao

    companion object {
        @Volatile private var instance: MusicHistoryDatabase? = null

        fun get(context: Context): MusicHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, MusicHistoryDatabase::class.java, "music_history.db",
                ).build().also { instance = it }
            }
    }
}
