package com.example.locall.voice.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TAG = "CalendarRepository"

/** One calendar event instance in a queried window. All-day events have [allDay] = true. */
data class CalendarEvent(
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String?,
    val calendarName: String?,
) {
    /** "9:00 AM – 9:30 AM  ·  Standup  ·  Room 3" style one-liner for a spoken/printed agenda. */
    fun describe(): String {
        val time = if (allDay) "All day" else "${clock(beginMs)} – ${clock(endMs)}"
        val where = location?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""
        return "$time  ·  $title$where"
    }

    private fun clock(ms: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
}

/**
 * Read-only access to the device's calendar via the system [CalendarContract] provider. No app-local
 * storage — every query hits the provider live, so events created in Google Calendar / Outlook / Samsung
 * Calendar all show up. Requires the [Manifest.permission.READ_CALENDAR] runtime permission.
 */
class CalendarRepository(appContext: Context) {

    private val app = appContext.applicationContext

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * All event instances overlapping [beginMs, endMs), ordered by start time. Uses
     * [CalendarContract.Instances] (not Events) so recurring events are expanded into concrete
     * occurrences in the window. Returns empty on missing permission or provider error.
     */
    fun eventsBetween(beginMs: Long, endMs: Long): List<CalendarEvent> {
        if (!hasPermission()) {
            Log.w(TAG, "eventsBetween: READ_CALENDAR not granted")
            return emptyList()
        }
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        return try {
            CalendarContract.Instances.query(
                app.contentResolver, projection, beginMs, endMs,
            ).use { cursor ->
                val out = ArrayList<CalendarEvent>(cursor.count)
                while (cursor.moveToNext()) {
                    val title = cursor.getString(0)?.trim().orEmpty().ifBlank { "(no title)" }
                    val begin = cursor.getLong(1)
                    val end = cursor.getLong(2)
                    val allDay = cursor.getInt(3) == 1
                    val location = cursor.getString(4)
                    val calName = cursor.getString(5)
                    out += CalendarEvent(title, begin, end, allDay, location, calName)
                }
                out.sortBy { it.beginMs }
                Log.i(TAG, "eventsBetween($beginMs,$endMs) -> ${out.size} events")
                out
            }
        } catch (t: Throwable) {
            Log.w(TAG, "calendar query failed", t)
            emptyList()
        }
    }

    companion object {
        /** [startOfDay, startOfNextDay) for the day [plusDays] from [now] in the device timezone. */
        fun dayWindow(plusDays: Int, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, plusDays)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            return start to cal.timeInMillis
        }
    }
}
