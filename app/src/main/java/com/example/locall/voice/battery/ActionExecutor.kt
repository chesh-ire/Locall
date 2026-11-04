package com.example.locall.voice.battery

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

private const val TAG = "ActionExecutor"

object ActionExecutor {

    fun execute(context: Context, recommendations: List<BatteryRecommendation>): List<ExecResult> =
        recommendations.map { apply(context.applicationContext, it) }

    private fun apply(context: Context, rec: BatteryRecommendation): ExecResult {
        if (rec.action.needsWriteSettings && !Settings.System.canWrite(context)) {
            return ExecResult(rec.label, applied = false, verified = false, detail = "needs write-settings permission")
        }
        return try {
            when (val a = rec.action) {
                is BatteryAction.SetBrightness -> setBrightness(context, a.percent, rec.label)
                is BatteryAction.DisableAutoBrightness -> disableAutoBrightness(context, rec.label)
                is BatteryAction.SetScreenTimeout -> setTimeout(context, a.seconds, rec.label)
                is BatteryAction.EnableBatterySaver -> openBatterySaver(context, rec.label)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "apply(${rec.label}) failed", t)
            ExecResult(rec.label, applied = false, verified = false, detail = t.javaClass.simpleName)
        }
    }

    private fun setBrightness(context: Context, percent: Int, label: String): ExecResult {
        val cr = context.contentResolver
        val raw = (percent * 255 / 100).coerceIn(1, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, raw)
        val readBack = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
        val verified = kotlin.math.abs(readBack - raw) <= 2
        return ExecResult(label, applied = true, verified = verified, detail = "brightness set to $percent%")
    }

    private fun disableAutoBrightness(context: Context, label: String): ExecResult {
        val cr = context.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val verified = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, -1) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        return ExecResult(label, applied = true, verified = verified, detail = "adaptive brightness off")
    }

    private fun setTimeout(context: Context, seconds: Int, label: String): ExecResult {
        val cr = context.contentResolver
        val ms = seconds * 1000
        Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, ms)
        val verified = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, -1) == ms
        return ExecResult(label, applied = true, verified = verified, detail = "screen timeout ${seconds}s")
    }

    private fun openBatterySaver(context: Context, label: String): ExecResult {
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ExecResult(label, applied = true, verified = false, detail = "opened battery saver settings")
    }
}
