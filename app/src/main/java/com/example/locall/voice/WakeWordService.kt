package com.example.locall.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.example.locall.voice.music.MediaPlaybackGate
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread

class WakeWordService : Service() {

    companion object {
        const val ACTION_START = "com.example.locall.START_LISTENING"
        const val ACTION_STOP = "com.example.locall.STOP_LISTENING"
        private const val CHANNEL_ID = "locall_listening"
        private const val NOTIF_ID = 1
        private const val SR = 16_000
        private const val COOLDOWN_MS = 1_500
        private const val COMMAND_TIMEOUT_MS = 10_000L

        @Volatile var isRunning = false; private set
    }

    @Volatile private var listening = false
    private var worker: Thread? = null
    private lateinit var detector: WakeWordDetector
    private val speech by lazy { SpeechRecognitionBridge(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            else -> start()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (listening) return
        startForegroundNotification("Listening for \"hey locall\"…")

        val modelsDir = File(getExternalFilesDir("models")!!.path)
        try {
            detector = WakeWordDetector(modelsDir)
        } catch (t: Throwable) {
            android.util.Log.e("WakeWord", "detector init failed", t)
            startForegroundNotification("Detector failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        listening = true
        isRunning = true
        worker = thread(name = "wake-listen") { listenLoop() }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun listenLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        NativeThreads.pinToEfficiencyCores()

        var record = createWakeAudioRecord()
        val chunk = ShortArray(WakeWordDetector.CHUNK_SAMPLES)
        var cooldownUntilChunks = 0L
        var chunkCount = 0L
        var maxScoreWindow = 0f

        record.startRecording()
        android.util.Log.i("WakeWord", "listen loop started")
        try {
            while (listening) {
                var off = 0
                while (off < chunk.size) {
                    val n = record.read(chunk, off, chunk.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off < chunk.size) continue
                chunkCount++

                val score = detector.feed(chunk)
                if (score != null && score > maxScoreWindow) maxScoreWindow = score
                if (chunkCount % 12L == 0L) {
                    android.util.Log.i("WakeWord", "peak score last ~1s = $maxScoreWindow")
                    maxScoreWindow = 0f
                }
                if (chunkCount < cooldownUntilChunks) continue

                if (detector.isWake(score)) {
                    updateNotification("Heard you — listening to command…")

                    val didPause = MediaPlaybackGate.pauseForCommand(applicationContext)

                    record.stop(); record.release()
                    val text = runBlocking { speech.recognizeOnce(timeoutMs = COMMAND_TIMEOUT_MS) }
                    cooldownUntilChunks = chunkCount + (COOLDOWN_MS / 80)
                    val res = runCommand(text ?: "")
                    if (didPause && res?.keepMusicPaused != true) MediaPlaybackGate.resume(applicationContext)

                    updateNotification("Listening for \"hey locall\"…")
                    record = createWakeAudioRecord()
                    record.startRecording()
                }
            }
        } finally {
            record.stop(); record.release()
        }
    }

    private fun createWakeAudioRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, WakeWordDetector.CHUNK_SAMPLES * 4),
        )
    }

    private fun runCommand(text: String): CommandPipeline.Result? {
        val res = runCatching {
            runBlocking {
                CommandPipeline.runText(
                    text,
                    appContext = applicationContext,
                    onStatus = { status -> updateNotification(status) },
                    confirm = { description ->
                        VoiceAccessibilityService.instance?.let {
                            ActionConfirmation.confirm(it, speech, description)
                        } ?: false
                    },
                )
            }
        }.getOrNull()
        android.util.Log.i("WakeWord", "transcript=\"${res?.transcript}\" action=${res?.action}")
        if (res != null) updateNotification("${res.action}  ·  \"${res.transcript}\"")
        return res
    }

    private fun stopEverything() {
        listening = false
        isRunning = false
        worker?.join(500)
        if (::detector.isInitialized) detector.close()
        speech.destroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }


    private fun startForegroundNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Voice listening", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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
            .setContentTitle("LocaLL")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
