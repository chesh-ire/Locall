package com.example.locall.voice.memory

import android.content.Context
import android.util.Log

private const val TAG = "MemoryCommandHandler"

class MemoryCommandHandler(appContext: Context) {

    private val repo = MemoryRepository.get(appContext)

    suspend fun handle(
        context: Context,
        transcript: String,
        now: Long,
        onStatus: (String) -> Unit,
        speak: (String) -> Unit = {},
    ): String {
        val normalized = transcript.trim().lowercase()

        MemoryIntents.saveStatement(normalized)?.let { return save(context, it, onStatus) }
        if (MemoryIntents.isClear(normalized)) return clear()
        MemoryIntents.deleteTopic(normalized)?.let { return delete(it) }
        MemoryIntents.queryTopic(normalized)?.let { return query(it, speak) }

        return "🤷 didn't catch that memory command"
    }

    private suspend fun save(context: Context, statement: String, onStatus: (String) -> Unit): String {
        onStatus("Saving memory…")
        com.example.locall.voice.LlmBridge.ensureLoaded(context, onStatus)
        val extracted = MemoryExtractor.extract(context, statement)
        Log.i(TAG, "save: key=\"${extracted.key}\" value=\"${extracted.value}\"")
        return when (val r = repo.save(extracted.key, extracted.value, statement, System.currentTimeMillis())) {
            is MemoryRepository.SaveResult.Added ->
                "🧠 remembered — ${r.memory.key}: ${r.memory.value}"
            is MemoryRepository.SaveResult.Updated ->
                "🧠 updated ${r.memory.key}: \"${r.previousValue}\" → \"${r.memory.value}\""
        }
    }

    private suspend fun delete(topic: String): String {
        val removed = repo.deleteMatching(topic)
        return if (removed > 0) "🗑️ forgot $removed ${plural(removed)} about \"$topic\""
        else "I had nothing saved about \"$topic\""
    }

    private suspend fun clear(): String {
        val n = repo.count()
        repo.clear()
        return if (n > 0) "🗑️ cleared all $n ${plural(n)}" else "I had no memories to clear"
    }

    private suspend fun query(topic: String, speak: (String) -> Unit): String {
        val hits = if (topic.isEmpty()) repo.all() else repo.relevant(topic, limit = 5)
        if (hits.isEmpty()) {
            val answer = if (topic.isEmpty()) "I don't have any memories saved yet."
            else "I don't remember anything about \"$topic\"."
            speak(answer)
            return "🧠 $answer"
        }
        val spoken = buildString {
            append(if (topic.isEmpty()) "Here's what I remember. " else "Here's what I remember about $topic. ")
            append(hits.joinToString(". ") { "${it.key} is ${it.value}" })
        }
        speak(spoken)
        return "🧠 " + hits.joinToString("\n") { "• ${it.key}: ${it.value}" }
    }

    private fun plural(n: Int) = if (n == 1) "memory" else "memories"
}
