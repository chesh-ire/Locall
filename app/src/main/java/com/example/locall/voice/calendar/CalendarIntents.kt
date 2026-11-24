package com.example.locall.voice.calendar

import com.example.locall.voice.calendar.CalendarRepository.Companion.dayWindow
import java.util.Calendar

/**
 * Deterministic routing + window parsing for calendar/meeting queries. This runs BEFORE any LLM in the
 * capability router, so "what meetings do I have tomorrow" resolves with zero inference — the whole class
 * of request that used to fall through two LLM routing passes into the wrong tool.
 *
 * Scope is read-only agenda lookups ("what's on", "my meetings", "my schedule"). Creating/editing events
 * is intentionally out of scope and left to the device calendar app.
 */
object CalendarIntents {

    /** A resolved agenda request: the window to query and a human label for the spoken reply. */
    data class Query(val beginMs: Long, val endMs: Long, val label: String)

    // Words that make a request clearly about the calendar rather than, say, an email mentioning a meeting.
    private val CALENDAR_WORDS = listOf("calendar", "schedule", "agenda")
    private val EVENT_WORDS = listOf("meeting", "meetings", "appointment", "appointments", "event", "events")
    // A read/query framing — required alongside an EVENT_WORD so we don't hijack "email about the meeting".
    private val QUERY_CUES = listOf(
        "what", "whats", "what's", "do i have", "have i got", "any", "list", "show", "tell me",
        "when", "next", "upcoming", "on my", "got any",
    )
    // Never treat these as calendar reads — they belong to email / device / app-launch handlers.
    private val EXCLUDE = listOf(
        "email", "e-mail", "mail", "send", "compose", "reply", "respond",
        "open ", "launch ", "start ", "go to ", "add ", "create ", "schedule a", "set up a",
    )

    fun matches(normalized: String): Boolean {
        val t = normalized.lowercase().trim()
        if (EXCLUDE.any { t.contains(it) }) return false
        if (CALENDAR_WORDS.any { t.contains(it) }) return true
        return EVENT_WORDS.any { t.contains(it) } && QUERY_CUES.any { t.contains(it) }
    }

    /** Parse the time window the request is asking about; defaults to today when unspecified. */
    fun resolve(normalized: String, now: Long = System.currentTimeMillis()): Query {
        val t = normalized.lowercase().trim()
        return when {
            t.contains("day after tomorrow") -> day(2, "the day after tomorrow", now)
            t.contains("tomorrow") -> day(1, "tomorrow", now)
            t.contains("next week") -> weekFrom(7, "next week", now)
            t.contains("this week") || t.contains("rest of the week") || t.contains("week") ->
                restOfWeek(now)
            t.contains("tonight") || t.contains("this evening") || t.contains("today") ||
                t.contains("rest of") -> day(0, "today", now)
            // Bare "what's on my calendar / my next meeting" → today.
            else -> day(0, "today", now)
        }
    }

    private fun day(plusDays: Int, label: String, now: Long): Query {
        val (b, e) = dayWindow(plusDays, now)
        return Query(b, e, label)
    }

    /** From the start of today +[offsetDays] through the end of that 7-day span. */
    private fun weekFrom(offsetDays: Int, label: String, now: Long): Query {
        val (start, _) = dayWindow(offsetDays, now)
        val (_, end) = dayWindow(offsetDays + 6, now)
        return Query(start, end, label)
    }

    /** Now through the end of the current calendar week (Sunday night). */
    private fun restOfWeek(now: Long): Query {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val daysToSunday = (Calendar.SATURDAY - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7 + 1
        val (_, end) = dayWindow(daysToSunday - 1, now)
        return Query(now, end, "this week")
    }
}
