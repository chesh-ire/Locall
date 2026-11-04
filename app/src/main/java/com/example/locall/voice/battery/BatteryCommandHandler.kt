package com.example.locall.voice.battery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.example.locall.voice.LlmBridge

private const val TAG = "BatteryCommandHandler"

class BatteryCommandHandler(appContext: Context) {

    private val app = appContext.applicationContext

    suspend fun handle(
        context: Context,
        transcript: String,
        onStatus: (String) -> Unit,
        confirm: suspend (String) -> Boolean,
        speak: (String) -> Unit,
    ): String {
        onStatus("Inspecting battery…")
        val before = DeviceStateCollector.collect(app)
        Log.i(TAG, "snapshot: ${before.describe()}")

        onStatus("Understanding…")
        LlmBridge.ensureLoaded(context, onStatus)
        val recommendations = BatteryAdvisor.advise(context, transcript, before)
        Log.i(TAG, "recommendations: ${recommendations.map { it.label }}")

        if (recommendations.isEmpty()) {
            val msg = "You're at ${before.batteryPercent}% and already set up to save power — nothing to change."
            speak(msg)
            return "🔋 ${before.batteryPercent}% — nothing to change"
        }

        if (recommendations.any { it.action.needsWriteSettings } && !before.canWriteSettings) {
            requestWriteSettings()
            speak("I need permission to change system settings. I've opened the screen — allow LocaLL, then ask again.")
            return "⚙️ grant “Modify system settings” to LocaLL, then try again"
        }

        val plan = recommendations.joinToString("; ") { it.label }
        if (!confirm("apply these power-saving changes: $plan")) {
            return "❌ cancelled — left your settings as they were"
        }

        onStatus("Applying…")
        val results = ActionExecutor.execute(app, recommendations)
        val after = DeviceStateCollector.collect(app)
        Log.i(TAG, "results: $results")

        val applied = results.filter { it.applied }
        if (applied.isEmpty()) {
            speak("I couldn't apply those changes.")
            return "⚠️ couldn't apply power-saving changes"
        }

        onStatus("Summarising…")
        val summary = BatteryAdvisor.summarize(context, before, after, results)
        speak(summary)

        val verified = applied.count { it.verified }
        return "🔋 applied ${applied.size} change${if (applied.size == 1) "" else "s"}" +
            (if (verified > 0) " ($verified verified)" else "")
    }

    private fun requestWriteSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't open write-settings screen", t)
        }
    }
}
