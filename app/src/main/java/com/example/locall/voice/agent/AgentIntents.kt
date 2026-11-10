package com.example.locall.voice.agent

object AgentIntents {

    fun matches(normalized: String): Boolean {
        val t = normalized.trim()
        if (t in EXACT) return true
        return PREFIXES.any { t.startsWith(it) } || CONTAINS.any { t.contains(it) }
    }

    private val EXACT = setOf(
        "brief me", "briefing", "what's new", "whats new", "what is new", "anything new",
        "catch me up", "what did i miss", "check my inbox", "any new messages", "inbox briefing",
        "morning briefing", "my tasks", "list my tasks", "what are my tasks", "show my tasks",
        "open tasks", "to do list", "to-do list", "what do i have to do", "what's on my list",
        "whats on my list", "plan my day", "plan today", "plan the day", "organize my day",
        "organise my day", "what should i do today", "what's on today", "whats on today",
    )

    private val PREFIXES = listOf(
        "brief my inbox", "give me a briefing", "give me my briefing",
        "add a task", "add task", "create a task", "new task",
        "remind me to", "remind me about", "remind me of", "set a reminder for",
        "complete task", "complete the task", "mark done", "mark complete", "mark the task",
        "finish task", "finish the task", "delete task", "delete the task", "remove task",
        "postpone", "reschedule", "push back the task", "push back task",
        "help me finish", "help me with", "help me plan", "make a plan for", "make a plan to",
        "create a plan for", "create a plan to", "plan for", "plan to", "prepare a study plan",
        "study plan for", "break down", "break this into tasks",
        "how am i doing on", "progress on", "goal progress", "how's my progress", "hows my progress",
        "extract tasks from", "pull tasks from", "turn this into tasks",
    )

    private val CONTAINS = listOf(
        "brief me on my inbox", "what needs my attention", "what needs a reply",
    )
}
