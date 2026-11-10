package com.example.locall.voice.agent.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        InboxItemEntity::class,
        GoalEntity::class,
        TaskEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun dao(): AgentDao

    companion object {
        @Volatile private var instance: AgentDatabase? = null

        fun get(context: Context): AgentDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AgentDatabase::class.java, "agent.db",
                ).build().also { instance = it }
            }
    }
}
