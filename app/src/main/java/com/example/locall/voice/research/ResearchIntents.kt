package com.example.locall.voice.research

/**
 * Grammar gate for the knowledge/research agent. Triggers on questions that span the user's whole
 * corpus ("summarise everything about my Android project", "what decisions have we made", "prepare a
 * weekly report"). Deliberately checked AFTER the email gate so email-specific asks still go to Gmail;
 * these phrasings carry no "email" keyword.
 */
object ResearchIntents {

    private val OPEN_APP_PREFIXES = listOf("open ", "launch ", "start ", "go to ")

    private val KEYWORDS = listOf(
        "knowledge base", "my documents", "my notes and", "across my", "my research",
        "in my documents", "according to my",
    )

    private val PREFIXES = listOf(
        "summarise everything", "summarize everything", "summarise all my", "summarize all my",
        "what decisions have", "what decisions did", "what did we decide",
        "what commitments", "what have i committed", "what did i promise",
        "prepare a weekly report", "weekly report", "prepare a report", "give me a weekly",
        "compare the", "compare my", "compare these",
        "ask my knowledge", "search my knowledge", "search my notes", "search my documents",
    )

    fun matches(normalized: String): Boolean {
        val t = normalized.trim()
        if (OPEN_APP_PREFIXES.any { t.startsWith(it) }) return false
        if (PREFIXES.any { t.startsWith(it) }) return true
        return KEYWORDS.any { t.contains(it) }
    }
}
