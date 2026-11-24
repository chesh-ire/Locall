package com.example.locall.voice.calendar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

private const val TAG = "CalendarCommandHandler"

/**
 * Answers read-only agenda questions ("what meetings do I have tomorrow") straight from the device
 * calendar. No LLM is involved: the window is parsed deterministically by [CalendarIntents] and the
 * events are read from [CalendarRepository], so the reply is instant and generates no heat — unlike the
 * old path, which misrouted calendar asks through two LLM routing passes into the task planner.
 */
class CalendarCommandHandler(
    appContext: Context,
    private val repo: CalendarRepository = CalendarRepository(appContext),
) {

    private val app = appContext.applicationContext

    suspend fun handle(
        context: Context,
        transcript: String,
        onStatus: (String) -> Unit,
        speak: (String) -> Unit,
    ): String {
        if (!repo.hasPermission()) {
            openAppSettings()
            val msg = "I need permission to read your calendar. I've opened LocaLL's settings — " +
                "allow Calendar access, then ask again."
            speak(msg)
            return "grant Calendar access to LocaLL, then try again"
        }

        val query = CalendarIntents.resolve(transcript)
        onStatus("Checking your calendar…")
        val events = repo.eventsBetween(query.beginMs, query.endMs)
        Log.i(TAG, "transcript=\"$transcript\" window=${query.label} -> ${events.size} events")

        if (events.isEmpty()) {
            val msg = "You have nothing on your calendar ${query.label}."
            speak(msg)
            return msg
        }

        val count = events.size
        val header = "You have $count ${if (count == 1) "meeting" else "meetings"} ${query.label}:"
        val lines = events.joinToString("\n") { "• ${it.describe()}" }
        val display = "$header\n$lines"

        // Spoken form stays terse — first few, then a tail count — so TTS doesn't read a wall of text.
        val spokenBody = events.take(3).joinToString(". ") { spokenLine(it) }
        val tail = if (count > 3) ". And ${count - 3} more." else "."
        speak("$header $spokenBody$tail")

        return display
    }

    private fun spokenLine(e: CalendarEvent): String {
        if (e.allDay) return "${e.title}, all day"
        val time = android.text.format.DateFormat.getTimeFormat(app).format(java.util.Date(e.beginMs))
        return "${e.title} at $time"
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${app.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't open app settings", t)
        }
    }
}
