package com.example.locall.voice.memory

import android.content.Context

class MemoryRepository(private val dao: MemoryDao) {

    sealed interface SaveResult {
        data class Added(val memory: MemoryEntity) : SaveResult
        data class Updated(val memory: MemoryEntity, val previousValue: String) : SaveResult
    }

    suspend fun save(key: String, value: String, text: String, now: Long): SaveResult {
        val existing = dao.findByKey(key)
        return if (existing == null) {
            val row = MemoryEntity(key = key, value = value, text = text, updatedAt = now)
            SaveResult.Added(row.copy(id = dao.insert(row)))
        } else {
            val updated = existing.copy(value = value, text = text, updatedAt = now)
            dao.update(updated)
            SaveResult.Updated(updated, existing.value)
        }
    }

    suspend fun all(): List<MemoryEntity> = dao.getAll()

    suspend fun count(): Int = dao.count()

    suspend fun clear() = dao.clear()

    suspend fun deleteMatching(topic: String): Int {
        val hits = scored(topic, dao.getAll()).map { it.first }
        hits.forEach { dao.delete(it) }
        return hits.size
    }

    suspend fun relevant(query: String, limit: Int = 3): List<MemoryEntity> =
        scored(query, dao.getAll()).take(limit).map { it.first }

    suspend fun relevantPromptBlock(query: String, limit: Int = 3): String {
        val hits = relevant(query, limit)
        if (hits.isEmpty()) return ""
        return buildString {
            append("Known facts about the user:\n")
            hits.forEach { append("- ").append(it.key).append(": ").append(it.value).append('\n') }
        }
    }

    private fun scored(query: String, memories: List<MemoryEntity>): List<Pair<MemoryEntity, Int>> {
        val qWords = tokens(query)
        if (qWords.isEmpty()) return emptyList()
        return memories
            .map { it to overlap(qWords, tokens("${it.key} ${it.value} ${it.text}")) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
    }

    private fun overlap(a: Set<String>, b: Set<String>): Int = a.count { it in b }

    private fun tokens(s: String): Set<String> =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ")
            .filter { it.length > 2 && it !in STOP_WORDS }.toSet()

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "for", "you", "your", "that", "about", "what", "does", "remember", "know", "with", "have",
        )

        @Volatile private var instance: MemoryRepository? = null

        fun get(context: Context): MemoryRepository =
            instance ?: synchronized(this) {
                instance ?: MemoryRepository(MemoryDatabase.get(context).memoryDao()).also { instance = it }
            }
    }
}
