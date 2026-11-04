package com.example.locall.voice.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import com.example.locall.voice.ThermalGovernor

object DeviceStateCollector {

    fun collect(context: Context): DeviceSnapshot {
        val app = context.applicationContext
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1

        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val tempC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val saver = power.isPowerSaveMode

        val thermal = ThermalGovernor.status(app)

        val cr = app.contentResolver
        val rawBrightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
        val brightnessPercent = if (rawBrightness >= 0) (rawBrightness * 100 / 255).coerceIn(0, 100) else -1
        val mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        val auto = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val timeoutMs = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 0)

        return DeviceSnapshot(
            batteryPercent = percent,
            isCharging = charging,
            temperatureC = tempC,
            powerSaveMode = saver,
            thermalStatus = thermal,
            thermalName = ThermalGovernor.statusName(thermal),
            brightnessPercent = brightnessPercent,
            autoBrightness = auto,
            screenTimeoutSeconds = if (timeoutMs > 0) timeoutMs / 1000 else -1,
            canWriteSettings = Settings.System.canWrite(app),
        )
    }
}
