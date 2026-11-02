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
        if (isLoaded) return
        LlmModelManager.ensureExtracted(context, onStatus)
        onStatus("Loading AI model…")
        load(LlmModelManager.modelFile(context).absolutePath)
    }

    val lastStats: String get() = nativeLastStats()

    suspend fun generateJson(systemPrompt: String, transcript: String, decodeThreads: Int = 0): String =
        withContext(InferenceDispatcher.dispatcher) {
            check(handle != 0L) { "call load() first" }
            nativeGenerate(handle, systemPrompt, transcript,  true,
                 0.2f,  200, decodeThreads)
        }

    suspend fun generateWithGrammar(
        systemPrompt: String,
        userPrompt: String,
        grammar: String,
        temperature: Float = 0.2f,
        maxTokens: Int = 120,
        decodeThreads: Int = 0,
    ): String = withContext(InferenceDispatcher.dispatcher) {
        check(handle != 0L) { "call load()/ensureLoaded() first" }
        nativeGenerateWithGrammar(handle, systemPrompt, userPrompt, grammar, temperature, maxTokens, decodeThreads)
    }

    suspend fun generateReply(instruction: String, decodeThreads: Int = 0, memoryContext: String = ""): String =
        withContext(InferenceDispatcher.dispatcher) {
            check(handle != 0L) { "call load() first" }
            val system = if (memoryContext.isEmpty()) REPLY_SYSTEM_PROMPT
                else "$memoryContext\n$REPLY_SYSTEM_PROMPT"
            nativeGenerate(handle, system, instruction,  false,
                 0.7f,  60, decodeThreads)
        }.trim()

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
    private external fun nativeRelease(handle: Long)
    private external fun nativeLastStats(): String
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
}
