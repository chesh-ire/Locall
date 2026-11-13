package com.example.locall.voice.research.bench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service that runs a long benchmark so it SURVIVES the screen turning off and the app being
 * backgrounded. Earlier the sustained compare was launched from the Composable's coroutine scope, which
 * the system could cancel when the Activity stopped (screen off) — and with no wake lock the CPU could
 * suspend mid-run. That is why runs "stopped automatically" after a block.
 *
 * This service:
 *   - runs as a foreground service (dataSync type) with an ongoing notification, so Android keeps the
 *     process alive while backgrounded;
 *   - holds a PARTIAL_WAKE_LOCK for the duration, so the CPU keeps running with the screen off;
 *   - executes the benchmark on its OWN process-lifetime coroutine scope (not the UI's), and publishes
 *     progress + the final report through [BenchmarkRunState] for the Perf Lab screen to observe.
 *
 * Still uses ONLY the dormant benchmark JNI path; production inference is untouched. The benchmarks
 * themselves restore the production model in their own finally blocks.
 */
class BenchmarkService : Service() {

    companion object {
        private const val TAG = "BenchmarkService"
        private const val CHANNEL_ID = "locall_benchmark"
        private const val NOTIF_ID = 42
        private const val EXTRA_KIND = "benchmark_kind"
        // Safety ceiling for the wake lock so a hung run can never hold the CPU awake indefinitely.
        private const val WAKELOCK_TIMEOUT_MS = 40L * 60L * 1000L

        /** Start the benchmark in the background. No-op if one is already running. */
        fun start(context: Context, kind: BenchmarkRunState.Kind) {
            val i = Intent(context, BenchmarkService::class.java).putExtra(EXTRA_KIND, kind.name)
            context.startForegroundService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = runCatching {
            BenchmarkRunState.Kind.valueOf(intent?.getStringExtra(EXTRA_KIND).orEmpty())
        }.getOrNull()
        if (kind == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Guard against double-start (e.g. a second tap): one benchmark at a time.
        if (BenchmarkRunState.running.value || job?.isActive == true) {
            Log.w(TAG, "benchmark already running; ignoring start for $kind")
            return START_NOT_STICKY
        }

        startForegroundNotification("${kind.label}: starting…")
        acquireWakeLock()
        BenchmarkRunState.onStart(kind)
        Log.i(TAG, "starting benchmark: $kind")

        job = scope.launch {
            val report = try {
                when (kind) {
                    BenchmarkRunState.Kind.SUSTAINED_COMPARE ->
                        SustainedDecodeCompare.report(
                            SustainedDecodeCompare.run(applicationContext) { publish(it) },
                        )
                    BenchmarkRunState.Kind.DECODE_SWEEP ->
                        DecodeThreadSweep.report(
                            DecodeThreadSweep.run(applicationContext) { publish(it) },
                        )
                    BenchmarkRunState.Kind.PACED_COMPARE ->
                        PacedDecodeCompare.report(
                            PacedDecodeCompare.run(applicationContext) { publish(it) },
                        )
                    BenchmarkRunState.Kind.PREFILL_CAP_COMPARE ->
                        SustainedPrefillCapCompare.report(
                            SustainedPrefillCapCompare.run(applicationContext) { publish(it) },
                        )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "benchmark $kind failed", t)
                "${kind.label} FAILED: ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"
            }
            BenchmarkRunState.onFinished(report)
            updateNotification("${kind.label}: done")
            Log.i(TAG, "benchmark $kind finished")
            finish()
        }

        // Don't auto-restart a benchmark if the process is killed — a half-run would be meaningless.
        return START_NOT_STICKY
    }

    private fun publish(line: String) {
        BenchmarkRunState.onProgress(line)
        updateNotification(line)
    }

    private fun finish() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_DETACH) // leave the "done" notification visible
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "locall:benchmark").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    override fun onDestroy() {
        job?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Benchmarks", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LocaLL benchmark")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
