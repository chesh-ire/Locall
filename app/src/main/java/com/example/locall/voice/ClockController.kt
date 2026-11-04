package com.example.locall.voice

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

private const val TAG = "ClockController"

/**
 * Fires standard [AlarmClock] intents at whatever clock app the device ships with.
 * Like [TorchController] this needs no accessibility node traversal, so the pipeline
 * runs it from the app context. We pass EXTRA_SKIP_UI so the alarm/timer is created
 * silently instead of dumping the user into the clock app's new-alarm screen.
 *
 * Requires the normal `com.android.alarm.permission.SET_ALARM` permission (manifest).
 */
object ClockController {

    fun setAlarm(context: Context, hour: Int, minute: Int): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return fire(context, intent)
    }

    fun setTimer(context: Context, seconds: Int): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return fire(context, intent)
    }

    /**
     * There's no public API to delete a specific future alarm, so the honest best-effort
     * is to open the alarm list and let the user toggle it off. ACTION_SHOW_ALARMS needs
     * no permission.
     */
    fun showAlarms(context: Context): Boolean =
        fire(context, Intent(AlarmClock.ACTION_SHOW_ALARMS))

    private fun fire(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.applicationContext.startActivity(intent)
            Log.i(TAG, "fired ${intent.action}")
            true
        } catch (t: Throwable) {
            // ActivityNotFoundException when no app handles the action, or SecurityException
            // if the clock app rejects it.
            Log.w(TAG, "couldn't fire ${intent.action}: ${t.message}")
            false
        }
    }
}
