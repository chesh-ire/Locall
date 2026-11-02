package com.example.locall.voice

import android.content.Context
import android.os.Build
import android.os.PowerManager


object ThermalGovernor {


    fun decodeThreads(context: Context): Int = decodeThreadsFor(status(context))

    fun status(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return PowerManager.THERMAL_STATUS_NONE
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }

    fun decodeThreadsFor(status: Int): Int = when {
        status >= PowerManager.THERMAL_STATUS_SEVERE -> 1
        status == PowerManager.THERMAL_STATUS_MODERATE -> 2
        status == PowerManager.THERMAL_STATUS_LIGHT -> 3
        else -> 4
    }

    fun statusName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }
}
