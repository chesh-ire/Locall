package com.example.locall.voice.memory

object MemoryIntents {

    private val SAVE_PREFIXES = listOf(
        "remember that ", "remember ", "note that ", "note down that ",
        "keep in mind that ", "keep in mind ", "don't forget that ", "don't forget ",
    )
    private val DELETE_PREFIXES = listOf(
        "forget that ", "forget about ", "forget my ", "forget ",
        "delete memory about ", "delete the memory about ", "delete memory ",
    )
    private val CLEAR = setOf(
        "forget everything", "forget everything about me", "forget all",
        "delete all memories", "delete my memories", "clear memories", "clear all memories",
    )
    private val LIST = setOf(
        "list memories", "list my memories", "show memories", "show my memories",
        "what do you remember", "what do you remember about me", "what do you know about me",
    )
    private val QUERY_PREFIXES = listOf(
        "what do you remember about ", "what do you know about ",
        "what do i ",
    )

    fun matches(normalized: String): Boolean =
        isClear(normalized) || saveStatement(normalized) != null || deleteTopic(normalized) != null ||
            isListAll(normalized) || queryTopic(normalized) != null

    fun isClear(normalized: String): Boolean = normalized in CLEAR

    fun isListAll(normalized: String): Boolean = normalized in LIST

    fun saveStatement(normalized: String): String? {
        val prefix = SAVE_PREFIXES.firstOrNull { normalized.startsWith(it) } ?: return null
        return normalized.removePrefix(prefix).trim().ifEmpty { null }
    }

    fun deleteTopic(normalized: String): String? {
        if (isClear(normalized)) return null
        val prefix = DELETE_PREFIXES.firstOrNull { normalized.startsWith(it) } ?: return null
        return normalized.removePrefix(prefix).trim().ifEmpty { null }
    }

    fun queryTopic(normalized: String): String? {
        if (isListAll(normalized)) return ""
        val prefix = QUERY_PREFIXES.firstOrNull { normalized.startsWith(it) } ?: return null
        return normalized.removePrefix(prefix).trim()
    }
}
