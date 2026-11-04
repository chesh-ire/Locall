package com.example.locall.voice.battery

data class DeviceSnapshot(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val temperatureC: Float,
    val powerSaveMode: Boolean,
    val thermalStatus: Int,
    val thermalName: String,
    val brightnessPercent: Int,
    val autoBrightness: Boolean,
    val screenTimeoutSeconds: Int,
    val canWriteSettings: Boolean,
) {
    fun describe(): String = buildString {
        append("battery=$batteryPercent%")
        append(if (isCharging) " (charging)" else " (on battery)")
        if (temperatureC > 0f) append(", temp=${"%.1f".format(temperatureC)}C")
        append(", thermal=$thermalName")
        append(", battery_saver=${if (powerSaveMode) "on" else "off"}")
        append(", brightness=$brightnessPercent%")
        append(if (autoBrightness) " (auto)" else " (manual)")
        append(", screen_timeout=${screenTimeoutSeconds}s")
    }
}
