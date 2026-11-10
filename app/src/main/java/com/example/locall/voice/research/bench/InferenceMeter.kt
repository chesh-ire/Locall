package com.example.locall.voice.research.bench

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor

private const val TAG = "InferenceMeter"

class InferenceMeter(
    private val context: Context,
    private val config: BenchConfig,
    private val onRecord: (InferenceRecord) -> Unit = {},
) {
    private val _records = ArrayList<InferenceRecord>()
    val records: List<InferenceRecord> get() = _records

    var peakRssKb: Long = 0L
        private set

    private fun decodeThreads(): Int =
        if (config.decodeThreads > 0) config.decodeThreads else ThermalGovernor.decodeThreads(context)

    suspend fun generate(
        stage: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = config.stageOutputTokens,
    ): String {
        val threads = decodeThreads()
        val t0 = SystemClock.elapsedRealtime()
        val (text, m) = LlmBridge.generateMetered(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = config.temperature,
            maxTokens = maxTokens,
            decodeThreads = threads,
        )
        val wall = SystemClock.elapsedRealtime() - t0
        val rec = InferenceRecord(
            ordinal = _records.size,
            stage = stage,
            promptTokens = m.promptTokens,
            generatedTokens = m.generatedTokens,
            prefillMs = m.prefillMs,
            decodeMs = m.decodeMs,
            wallMs = wall,
            rssKb = m.rssKb,
        )
        _records += rec
        if (m.rssKb > peakRssKb) peakRssKb = m.rssKb
        Log.i(TAG, "[$stage] prompt=${m.promptTokens}t gen=${m.generatedTokens}t " +
            "prefill=${"%.0f".format(m.prefillMs)}ms decode=${"%.0f".format(m.decodeMs)}ms " +
            "(${"%.1f".format(rec.decodeTokensPerSec)} tok/s)")
        onRecord(rec)
        return text.trim()
    }

    fun samplePeakRss() {
        val r = LlmBridge.rssKb()
        if (r > peakRssKb) peakRssKb = r
    }
}
