package com.example.locall.voice.agent.follow

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.locall.voice.agent.data.TaskManager

private const val TAG = "FollowUpManager"

object FollowUpManager {

    suspend fun schedule(context: Context, taskId: String, title: String, atMs: Long) {
        TaskManager.get(context).setReminder(taskId, atMs)
        arm(context, taskId, title, atMs)
    }

    suspend fun cancel(context: Context, taskId: String) {
        TaskManager.get(context).setReminder(taskId, null)
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMIND
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        }
        val existing = PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flagsForCancel())
        if (existing != null) alarmManager(context)?.cancel(existing)
    }

    suspend fun rescheduleAll(context: Context, now: Long = System.currentTimeMillis()) {
        ReminderReceiver.ensureChannel(context)
        TaskManager.get(context).withReminders().forEach { task ->
            val at = task.reminderAtMs ?: return@forEach
            if (at > now) arm(context, task.id, task.title, at)
        }
    }

    private fun arm(context: Context, taskId: String, title: String, atMs: Long) {
        ReminderReceiver.ensureChannel(context)
        val am = alarmManager(context) ?: return
        val pi = pendingIntent(context, taskId, title, flagsForCreate())
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
            Log.i(TAG, "scheduled reminder for $taskId at $atMs (exact=$canExact)")
        } catch (se: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            Log.w(TAG, "exact alarm denied, used inexact", se)
        }
    }

    private fun alarmManager(context: Context): AlarmManager? =
        ContextCompat.getSystemService(context, AlarmManager::class.java)

    private fun pendingIntent(context: Context, taskId: String, title: String, flags: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMIND
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
        }
        return PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flags)
    }

    private fun flagsForCreate() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    private fun flagsForCancel() = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
}
