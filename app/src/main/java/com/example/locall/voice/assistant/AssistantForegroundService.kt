package com.example.locall.voice.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.locall.MainActivity

/**
 * Keeps the assistant alive while a request is running so it SURVIVES the screen turning off and the
 * user navigating away / backgrounding the app. The multi-step work (and its TTS) still runs on
 * [AssistantViewModel]'s process-scoped coroutine scope — that scope was never cancelled by code, but
 * with no foreground service the OS would freeze/kill the backgrounded process mid-run, which is why a
 * long task like "review my last 20 emails" stopped when the screen went off.
 *
 * This service, mirroring [com.example.locall.voice.research.bench.BenchmarkService]:
 *   - runs as a foreground service (dataSync type) with an ongoing notification, so Android keeps the
 *     process alive while backgrounded;
 *   - holds a PARTIAL_WAKE_LOCK for the duration, so the CPU (inference + speech) keeps running with the
 *     screen off.
 *
 * It does NOT run the work itself — it only brackets the request: [start] when a job begins, [stop] in
 * the job's finally. Tapping the notification returns the user to the conversation.
 */
class AssistantForegroundService : Service() {

    companion object {
        private const val TAG = "AssistantFgService"
        private const val CHANNEL_ID = "locall_assistant"
        private const val NOTIF_ID = 43
        private const val EXTRA_LABEL = "assistant_label"
        // Safety ceiling so a stuck job (or one left Awaiting confirm with the screen off) can never
        // hold the CPU awake indefinitely.
        private const val WAKELOCK_TIMEOUT_MS = 20L * 60L * 1000L

        /** Begin keeping the process alive for a running request. [label] shows in the notification. */
        fun start(context: Context, label: String) {
            val i = Intent(context, AssistantForegroundService::class.java)
                .putExtra(EXTRA_LABEL, label)
            context.startForegroundService(i)
        }

        /** Request finished (or cancelled) — let the process be backgrounded/killed normally again. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantForegroundService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() } ?: "Working…"
        startForegroundNotification(label)
        acquireWakeLock()
        Log.i(TAG, "assistant foreground started: $label (wakeLock held=${wakeLock?.isHeld})")
        // Don't auto-restart: a half-finished request restarted from scratch would be meaningless.
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "locall:assistant").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        Log.i(TAG, "wake lock acquired (held=${wakeLock?.isHeld}, timeoutMs=$WAKELOCK_TIMEOUT_MS)")
    }

    /**
     * Fires when the user swipes the app off Recents. We do NOT stop here — the request should keep
     * running — but log it so we can correlate a "cancellation" with the task being removed.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved — app swiped from recents; keeping the request alive")
        super.onTaskRemoved(rootIntent)
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    override fun onDestroy() {
        // If this fires while a request is still running, the OS (not our code) tore the service down
        // — the smoking gun for a background/FGS kill. The stack shows whether it was our stop() or Android.
        Log.w(TAG, "onDestroy — foreground service stopping (wakeLock held=${wakeLock?.isHeld})",
            Throwable("onDestroy call site"))
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Assistant", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LocaLL assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
