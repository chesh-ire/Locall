package com.example.locall.voice

import android.content.Context
import kotlinx.coroutines.withContext

object LlmBridge {

    init {
        System.loadLibrary("locall")
    }

    private var handle: Long = 0L

    fun load(modelPath: String) {
        if (handle != 0L) return
        handle = nativeLoad(modelPath)
        require(handle != 0L) { "llama model failed to load: $modelPath" }
    }

    val isLoaded: Boolean get() = handle != 0L

    suspend fun ensureLoaded(context: Context, onStatus: (String) -> Unit = {}) {
        PerfTrace.attach(context)
        if (isLoaded) return
        LlmModelManager.ensureExtracted(context, onStatus)
        onStatus("Loading AI model…")
        load(LlmModelManager.modelFile(context).absolutePath)
    }

    val lastStats: String get() = nativeLastStats()

    suspend fun generateJson(
        systemPrompt: String,
        transcript: String,
        decodeThreads: Int = 0,
        tag: String = "json",
    ): String {
        val req = PerfTrace.request()
        return withContext(InferenceDispatcher.dispatcher) {
            check(handle != 0L) { "call load() first" }
            val span = PerfTrace.begin(tag, req, decodeThreads)
            val out = nativeGenerate(handle, systemPrompt, transcript,  true,
                 0.2f,  200, decodeThreads)
            PerfTrace.end(span, nativeLastMetrics())
            out
        }
    }

    suspend fun generateWithGrammar(
        systemPrompt: String,
        userPrompt: String,
        grammar: String,
        temperature: Float = 0.2f,
        maxTokens: Int = 120,
        decodeThreads: Int = 0,
        tag: String = "grammar",
    ): String {
        val req = PerfTrace.request()
        return withContext(InferenceDispatcher.dispatcher) {
            check(handle != 0L) { "call load()/ensureLoaded() first" }
            val span = PerfTrace.begin(tag, req, decodeThreads)
            val out = nativeGenerateWithGrammar(handle, systemPrompt, userPrompt, grammar, temperature, maxTokens, decodeThreads)
            PerfTrace.end(span, nativeLastMetrics())
            out
        }
    }

    suspend fun generateText(
        systemPrompt: String,
        userPrompt: String,
        temperature: Float = 0.6f,
        maxTokens: Int = 80,
        decodeThreads: Int = 0,
        tag: String = "text",
    ): String {
        val req = PerfTrace.request()
        return withContext(InferenceDispatcher.dispatcher) {
            check(handle != 0L) { "call load()/ensureLoaded() first" }
            val span = PerfTrace.begin(tag, req, decodeThreads)
            val out = nativeGenerate(handle, systemPrompt, userPrompt, false, temperature, maxTokens, decodeThreads)
            PerfTrace.end(span, nativeLastMetrics())
            out
        }
    }

    /**
     * Long-form generation for structured, multi-section output (e.g. labelled SUMMARY/ENTITIES/…
     * blocks). Mirrors [generateText] but routes to [nativeGenerateLong], which does NOT stop on the
     * first blank line — a blank line separating sections must not truncate the extraction. Keeps the
     * EOG and header stops. Used by the chunk analyzer; callers wanting single-paragraph output that
     * should stop at a blank line must keep using [generateText].
     */
    suspend fun generateStructured(
        systemPrompt: String,
        userPrompt: String,
        temperature: Float = 0.3f,
        maxTokens: Int = 320,
        decodeThreads: Int = 0,
        tag: String = "structured",
    ): String {
        val req = PerfTrace.request()
        return withContext(InferenceDispatcher.dispatcher) {
            check(handle != 0L) { "call load()/ensureLoaded() first" }
            val span = PerfTrace.begin(tag, req, decodeThreads)
            val out = nativeGenerateLong(handle, systemPrompt, userPrompt, temperature, maxTokens, decodeThreads)
            PerfTrace.end(span, nativeLastMetrics())
            out
        }
    }

    suspend fun generateReply(
        instruction: String,
        decodeThreads: Int = 0,
        memoryContext: String = "",
        tag: String = "reply",
    ): String {
        val req = PerfTrace.request()
        return withContext(InferenceDispatcher.dispatcher) {
            check(handle != 0L) { "call load() first" }
            val system = if (memoryContext.isEmpty()) REPLY_SYSTEM_PROMPT
                else "$memoryContext\n$REPLY_SYSTEM_PROMPT"
            val span = PerfTrace.begin(tag, req, decodeThreads)
            val out = nativeGenerate(handle, system, instruction,  false,
                 0.7f,  60, decodeThreads)
            PerfTrace.end(span, nativeLastMetrics())
            out
        }.trim()
    }

    data class RawMetrics(
        val promptTokens: Int,
        val generatedTokens: Int,
        val prefillMs: Double,
        val decodeMs: Double,
        val rssKb: Long,
    )

    suspend fun generateMetered(
        systemPrompt: String,
        userPrompt: String,
        temperature: Float = 0.3f,
        maxTokens: Int = 256,
        decodeThreads: Int = 0,
        longForm: Boolean = true,
        tag: String = "metered",
    ): Pair<String, RawMetrics> {
        val req = PerfTrace.request()
        return withContext(InferenceDispatcher.dispatcher) {
            check(handle != 0L) { "call load()/ensureLoaded() first" }
            val span = PerfTrace.begin(tag, req, decodeThreads)
            val out = if (longForm) {
                nativeGenerateLong(handle, systemPrompt, userPrompt, temperature, maxTokens, decodeThreads)
            } else {
                nativeGenerate(handle, systemPrompt, userPrompt, false, temperature, maxTokens, decodeThreads)
            }
            val csv = nativeLastMetrics()
            PerfTrace.end(span, csv)
            out to parseMetrics(csv)
        }
    }

    private fun parseMetrics(csv: String): RawMetrics {
        val p = csv.split(',')
        fun i(idx: Int) = p.getOrNull(idx)?.trim()?.toDoubleOrNull()?.toInt() ?: 0
        fun d(idx: Int) = p.getOrNull(idx)?.trim()?.toDoubleOrNull() ?: 0.0
        fun l(idx: Int) = p.getOrNull(idx)?.trim()?.toDoubleOrNull()?.toLong() ?: 0L
        return RawMetrics(
            promptTokens = i(0),
            generatedTokens = i(1),
            prefillMs = d(2),
            decodeMs = d(3),
            rssKb = l(4),
        )
    }

    fun release() {
        if (handle != 0L) nativeRelease(handle)
        handle = 0L
    }


    fun modelInfo(benchHandle: Long): String = nativeModelInfo(benchHandle)

    fun rssKb(): Long = nativeRssKb()

    fun loadWithCtx(modelPath: String, nCtx: Int): Long = nativeLoadWithCtx(modelPath, nCtx)

    fun releaseHandle(benchHandle: Long) = nativeRelease(benchHandle)

    fun generateBenchmark(
        benchHandle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        decodeThreads: Int,
        batchThreads: Int,
        affinityCores: IntArray,
    ): String = nativeGenerateBenchmark(
        benchHandle, systemPrompt, userPrompt, maxTokens, decodeThreads, batchThreads, affinityCores,
    )

    /**
     * Dormant benchmark-only PACED decode. Mirrors [generateBenchmark] but sleeps [pauseMs] ms after
     * every [pauseEveryK] generated tokens (0 for either ⇒ no pauses). Returns CSV
     * "prefill_ms,decode_ms,generated,rss_kb,slept_ms" where decode_ms INCLUDES the sleeps and slept_ms
     * is reported separately. Production inference is untouched; used only by [research.bench] harnesses.
     */
    fun generateBenchmarkPaced(
        benchHandle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        decodeThreads: Int,
        batchThreads: Int,
        affinityCores: IntArray,
        pauseEveryK: Int,
        pauseMs: Int,
    ): String = nativeGenerateBenchmarkPaced(
        benchHandle, systemPrompt, userPrompt, maxTokens, decodeThreads, batchThreads, affinityCores,
        pauseEveryK, pauseMs,
    )

    /**
     * Dormant benchmark-only ADAPTIVE paced decode. Sleeps [pauseMs] ms only when, checked every
     * [checkEveryK] generated tokens, scaling_max_freq across the pinned cores has dropped below
     * [clampThresholdKhz] (the DVFS clamp has engaged) — so cool requests run untaxed and pacing
     * self-engages under heat. Returns CSV "prefill_ms,decode_ms,generated,rss_kb,slept_ms" with a
     * variable slept_ms. Production inference is untouched; used only by [research.bench] harnesses.
     */
    fun generateBenchmarkAdaptive(
        benchHandle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        decodeThreads: Int,
        batchThreads: Int,
        affinityCores: IntArray,
        checkEveryK: Int,
        pauseMs: Int,
        clampThresholdKhz: Long,
    ): String = nativeGenerateBenchmarkAdaptive(
        benchHandle, systemPrompt, userPrompt, maxTokens, decodeThreads, batchThreads, affinityCores,
        checkEveryK, pauseMs, clampThresholdKhz,
    )

    val handleForBenchmark: Long get() = handle

    fun countTokens(benchHandle: Long, systemPrompt: String, userPrompt: String): Int =
        nativeCountTokens(benchHandle, systemPrompt, userPrompt)

    fun generateJsonBenchmark(benchHandle: Long, systemPrompt: String, userPrompt: String): String =
        nativeGenerate(benchHandle, systemPrompt, userPrompt,  true,
             0.2f,  200,  0)

    private const val REPLY_SYSTEM_PROMPT =
        "You draft a short WhatsApp reply (1-2 sentences, casual tone) based on the " +
            "user's instruction. Output ONLY the reply text, no quotes, no preamble."

    private external fun nativeLoad(modelPath: String): Long
    private external fun nativeGenerate(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        useCommandGrammar: Boolean,
        temperature: Float,
        maxTokens: Int,
        decodeThreads: Int,
    ): String
    private external fun nativeGenerateWithGrammar(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        grammarGbnf: String,
        temperature: Float,
        maxTokens: Int,
        decodeThreads: Int,
    ): String
    private external fun nativeGenerateLong(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float,
        maxTokens: Int,
        decodeThreads: Int,
    ): String
    private external fun nativeRelease(handle: Long)
    private external fun nativeLastStats(): String
    private external fun nativeLastMetrics(): String
    private external fun nativeModelInfo(handle: Long): String
    private external fun nativeRssKb(): Long
    private external fun nativeCountTokens(handle: Long, systemPrompt: String, userPrompt: String): Int
    private external fun nativeLoadWithCtx(modelPath: String, nCtx: Int): Long
    private external fun nativeGenerateBenchmark(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        decodeThreads: Int,
        batchThreads: Int,
        affinityCores: IntArray,
    ): String
    private external fun nativeGenerateBenchmarkPaced(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        decodeThreads: Int,
        batchThreads: Int,
        affinityCores: IntArray,
        pauseEveryK: Int,
        pauseMs: Int,
    ): String
    private external fun nativeGenerateBenchmarkAdaptive(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        decodeThreads: Int,
        batchThreads: Int,
        affinityCores: IntArray,
        checkEveryK: Int,
        pauseMs: Int,
        clampThresholdKhz: Long,
    ): String
}
