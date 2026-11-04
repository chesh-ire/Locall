package com.example.locall.voice

/**
 * Deterministic grammar for clock commands — alarms, timers, and dismissing. Pure and
 * unit-tested; [IntentMatcher] calls [parse] before falling back to the LLM. Returns a
 * fully-resolved [CommandAction] (with parsed hour/minute or duration) or null if the
 * phrase isn't a clock command.
 *
 * Spoken input is already lower-cased and trimmed by the matcher.
 */
object ClockIntents {

    fun matches(normalized: String): Boolean = parse(normalized) != null

    fun parse(normalized: String): CommandAction? {
        val n = normalized.trim()
        // Cancel/dismiss must be checked before "alarm", since "cancel my alarm" also
        // contains the alarm keyword.
        if (CANCEL_RE.containsMatchIn(n)) return CommandAction.CancelAlarm

        if (TIMER_RE.containsMatchIn(n)) {
            val secs = durationSeconds(n)
            if (secs != null && secs > 0) return CommandAction.SetTimer(secs)
            // "set a timer" with no/garbled duration: don't guess a length.
            return null
        }

        if (ALARM_RE.containsMatchIn(n)) {
            val time = timeOfDay(n) ?: return null
            return CommandAction.SetAlarm(time.first, time.second)
        }

        return null
    }

    /** Parse an absolute clock time → (hour 0–23, minute 0–59), or null. */
    fun timeOfDay(s: String): Pair<Int, Int>? {
        if (NOON_RE.containsMatchIn(s)) return 12 to 0
        if (MIDNIGHT_RE.containsMatchIn(s)) return 0 to 0

        // "quarter past 6", "half past 7", "quarter to 8", "ten past 6", "twenty to 9"
        PAST_TO_RE.find(s)?.let { m ->
            val mins = relativeMinutes(m.groupValues[1]) ?: return@let
            val baseHour = numberWord(m.groupValues[3]) ?: m.groupValues[3].toIntOrNull() ?: return@let
            val meridiem = m.groupValues[4]
            val toward = m.groupValues[2] // "past" or "to"
            var hour = applyMeridiem(baseHour, meridiem)
            var minute: Int
            if (toward == "to") {
                minute = 60 - mins
                hour = (hour + 23) % 24 // the hour before
            } else {
                minute = mins
            }
            if (hour in 0..23 && minute in 0..59) return hour to minute
        }

        // "7", "7:30", "7 30", "7 o'clock", each optionally followed by am/pm.
        CLOCK_RE.find(s)?.let { m ->
            val hour12 = m.groupValues[1].toIntOrNull() ?: return@let
            // Minute arrives via either the ":mm" or the " mm" branch.
            val minute = m.groupValues[2].ifEmpty { m.groupValues[3] }.toIntOrNull() ?: 0
            val meridiem = m.groupValues[4]
            if (hour12 in 0..23 && minute in 0..59) {
                return applyMeridiem(hour12, meridiem) to minute
            }
        }
        return null
    }

    /** Sum a spoken duration like "10 minutes", "1 hour 30 minutes", "half an hour". */
    fun durationSeconds(s: String): Int? {
        if (HALF_HOUR_RE.containsMatchIn(s)) return 30 * 60
        if (HOUR_AND_HALF_RE.containsMatchIn(s)) return 90 * 60
        if (QUARTER_HOUR_RE.containsMatchIn(s)) return 15 * 60

        var total = 0
        var found = false
        for (m in UNIT_RE.findAll(s)) {
            val amount = numberWord(m.groupValues[1]) ?: m.groupValues[1].toIntOrNull() ?: continue
            val unit = m.groupValues[2]
            val mult = when {
                unit.startsWith("h") -> 3600
                unit.startsWith("m") -> 60
                else -> 1 // seconds
            }
            total += amount * mult
            found = true
        }
        return if (found) total else null
    }

    private fun applyMeridiem(hour: Int, meridiem: String): Int = when {
        meridiem.startsWith("p") && hour < 12 -> hour + 12
        meridiem.startsWith("a") && hour == 12 -> 0
        else -> hour
    }

    private fun relativeMinutes(word: String): Int? = when (word) {
        "quarter" -> 15
        "half" -> 30
        else -> numberWord(word) ?: word.toIntOrNull()
    }

    private fun numberWord(w: String): Int? = NUMBER_WORDS[w]

    private val NUMBER_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "forty-five" to 45, "forty five" to 45,
        "fifty" to 50, "sixty" to 60,
    )

    private val CANCEL_RE = Regex("\\b(cancel|dismiss|delete|turn off|remove|stop)\\b.*\\balarm")
    private val TIMER_RE = Regex("\\btimer\\b")
    // "set an alarm…", but also the common "wake me up at…" phrasing that implies one.
    private val ALARM_RE = Regex("\\b(alarm|wake me)\\b")

    private val NOON_RE = Regex("\\b(noon|midday)\\b")
    private val MIDNIGHT_RE = Regex("\\bmidnight\\b")

    // amount of past/to, the word "past"/"to", the hour, optional meridiem
    private val PAST_TO_RE = Regex(
        "\\b(quarter|half|ten|twenty|five|\\d{1,2})\\s+(past|to)\\s+(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\b(?:\\s*(a\\.?m\\.?|p\\.?m\\.?))?"
    )

    // group1=hour, group2=":mm", group3=" mm", optional "o'clock", group4=meridiem
    private val CLOCK_RE =
        Regex("\\b(\\d{1,2})(?::(\\d{2})|\\s(\\d{2}))?\\s*(?:o'?clock)?\\s*(a\\.?m\\.?|p\\.?m\\.?)?")

    private val HALF_HOUR_RE = Regex("\\bhalf (an |a )?hour\\b")
    private val HOUR_AND_HALF_RE = Regex("\\b(an? |one )?hour and a half\\b")
    private val QUARTER_HOUR_RE = Regex("\\b(a )?quarter (of an |of a )?hour\\b")

    private val UNIT_RE = Regex(
        "\\b(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|twenty|thirty|forty|forty-five|fifty|sixty)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b"
    )
}
