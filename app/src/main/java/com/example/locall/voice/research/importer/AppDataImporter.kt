package com.example.locall.voice.research.importer

import android.content.Context
import android.util.Log
import com.example.locall.voice.memory.MemoryDatabase
import com.example.locall.voice.music.history.MusicHistoryDatabase
import com.example.locall.voice.research.data.SourceType

private const val TAG = "AppDataImporter"

/**
 * Folds LocaLL's own on-device data into the knowledge base so the agent can reason over it too —
 * e.g. "what commitments have I made" can draw on saved memories. Each source collapses to a single
 * document (the rows are short facts); the pipeline chunks them if they grow large.
 */
class AppDataImporter(context: Context) {

    private val app = context.applicationContext

    suspend fun import(): List<RawDocument> {
        val docs = ArrayList<RawDocument>(2)
        memoriesDoc()?.let { docs += it }
        musicDoc()?.let { docs += it }
        return docs
    }

    private suspend fun memoriesDoc(): RawDocument? = try {
        val memories = MemoryDatabase.get(app).memoryDao().getAll()
        if (memories.isEmpty()) null else {
            val text = memories.joinToString("\n") { m ->
                "- ${m.text.ifBlank { "${m.key}: ${m.value}" }}"
            }
            RawDocument(
                id = "memory:all",
                title = "My saved memories",
                sourceType = SourceType.MEMORY,
                sourceRef = "memories.db",
                author = "me",
                createdAtMs = memories.maxOf { it.updatedAt },
                text = "Personal facts and notes I've saved to LocaLL:\n$text",
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "memoriesDoc failed", t); null
    }

    private suspend fun musicDoc(): RawDocument? = try {
        val dao = MusicHistoryDatabase.get(app).dao()
        val recent = dao.recent(limit = 40)
        if (recent.isEmpty()) null else {
            val text = recent.joinToString("\n") { t ->
                "- ${t.title}${t.artist?.let { " by $it" } ?: ""} (played ${t.requestCount}x)"
            }
            RawDocument(
                id = "music:history",
                title = "My listening history",
                sourceType = SourceType.MUSIC,
                sourceRef = "music_history.db",
                author = "me",
                createdAtMs = recent.maxOf { it.lastPlayedMs },
                text = "Tracks I've played recently on LocaLL:\n$text",
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "musicDoc failed", t); null
    }
}
