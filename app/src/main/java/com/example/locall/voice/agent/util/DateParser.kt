package com.example.locall.voice.agent.util

import java.util.Calendar

object DateParser {

    private val WEEKDAYS = mapOf(
        "sunday" to Calendar.SUNDAY, "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY,
    )

    fun parse(phrase: String, now: Long = System.currentTimeMillis()): Long? {
        val t = phrase.lowercase().trim()
        if (t.isBlank() || t == "none" || t == "n/a") return null

        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(t)?.let { m ->
            val (y, mo, d) = m.destructured
            return dayAt(now, y.toInt(), mo.toInt() - 1, d.toInt(), 9, 0)
        }

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val clock = parseClock(t)

        return when {
            t.contains("today") || t.contains("tonight") || t.contains("end of day") || t.contains("eod") ->
                atTime(now, 0, clock ?: (if (t.contains("tonight")) 20 to 0 else 18 to 0))
            t.contains("tomorrow") -> atTime(now, 1, clock ?: (9 to 0))
            t.contains("day after tomorrow") -> atTime(now, 2, clock ?: (9 to 0))
            Regex("in (\\d+) days?").find(t) != null -> {
                val n = Regex("in (\\d+) days?").find(t)!!.groupValues[1].toInt()
                atTime(now, n, clock ?: (9 to 0))
            }
            Regex("in (\\d+) weeks?").find(t) != null -> {
                val n = Regex("in (\\d+) weeks?").find(t)!!.groupValues[1].toInt()
                atTime(now, n * 7, clock ?: (9 to 0))
            }
            Regex("in (\\d+) hours?").find(t) != null -> {
                val n = Regex("in (\\d+) hours?").find(t)!!.groupValues[1].toLong()
                now + n * 60 * 60 * 1000
            }
            t.contains("next week") -> atTime(now, 7, clock ?: (9 to 0))
            t.contains("this weekend") || t.contains("weekend") ->
                nextWeekday(cal, Calendar.SATURDAY, clock ?: (10 to 0))
            WEEKDAYS.keys.any { t.contains(it) } -> {
                val day = WEEKDAYS.entries.first { t.contains(it.key) }.value
                nextWeekday(cal, day, clock ?: (9 to 0))
            }
            else -> null
        }
    }

    private fun parseClock(t: String): Pair<Int, Int>? {
        Regex("(\\d{1,2}):(\\d{2})\\s*(am|pm)?").find(t)?.let { m ->
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
            val ap = m.groupValues[3]
            if (ap == "pm" && h < 12) h += 12; if (ap == "am" && h == 12) h = 0
            return h.coerceIn(0, 23) to min.coerceIn(0, 59)
        }
        Regex("(?:at )?(\\d{1,2})\\s*(am|pm)").find(t)?.let { m ->
            var h = m.groupValues[1].toInt(); val ap = m.groupValues[2]
            if (ap == "pm" && h < 12) h += 12; if (ap == "am" && h == 12) h = 0
            return h.coerceIn(0, 23) to 0
        }
        return null
    }

    private fun atTime(now: Long, plusDays: Int, hm: Pair<Int, Int>): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.add(Calendar.DAY_OF_YEAR, plusDays)
        cal.set(Calendar.HOUR_OF_DAY, hm.first); cal.set(Calendar.MINUTE, hm.second)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun nextWeekday(from: Calendar, targetDow: Int, hm: Pair<Int, Int>): Long {
        val cal = from.clone() as Calendar
        cal.set(Calendar.HOUR_OF_DAY, hm.first); cal.set(Calendar.MINUTE, hm.second)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        var guard = 0
        while (cal.get(Calendar.DAY_OF_WEEK) != targetDow || cal.timeInMillis <= from.timeInMillis) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            if (++guard > 8) break
        }
        return cal.timeInMillis
    }

    private fun dayAt(now: Long, y: Int, mo: Int, d: Int, h: Int, min: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(y, mo, d, h, min, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
