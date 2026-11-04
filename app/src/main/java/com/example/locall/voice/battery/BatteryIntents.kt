package com.example.locall.voice.battery

object BatteryIntents {

    private val KEYWORDS = listOf(
        "battery", "power saving", "power save", "save power", "save battery",
        "conserve power", "conserve battery", "extend battery", "battery life",
        "low power", "dying", "running out of charge", "running low on charge",
    )

    private val OPEN_APP_PREFIXES = listOf("open ", "launch ", "start ", "go to ")

    fun matches(normalized: String): Boolean {
        val t = normalized.trim()
        if (OPEN_APP_PREFIXES.any { t.startsWith(it) }) return false
        return KEYWORDS.any { kw -> t.contains(kw) }
    }
}
