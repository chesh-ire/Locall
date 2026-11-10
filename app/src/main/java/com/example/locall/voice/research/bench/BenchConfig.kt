package com.example.locall.voice.research.bench

data class BenchConfig(
    val chunkCount: Int = 50,
    val groupSize: Int = 5,
    val contextBudgetTokens: Int = 1600,
    val stageOutputTokens: Int = 220,
    val reportOutputTokens: Int = 512,
    val temperature: Float = 0.3f,
    val decodeThreads: Int = 0,
    val topK: Int = 8,
    val qaContextSweepTokens: List<Int> = listOf(256, 512, 1024, 1600),
) {
    val contextBudgetChars: Int get() = contextBudgetTokens * CHARS_PER_TOKEN

    fun qaSweepChars(): List<Int> = qaContextSweepTokens.map { it * CHARS_PER_TOKEN }

    fun describe(): String =
        "chunks=$chunkCount group=$groupSize ctx=${contextBudgetTokens}t " +
            "stageOut=${stageOutputTokens}t reportOut=${reportOutputTokens}t " +
            "topK=$topK threads=${if (decodeThreads == 0) "auto" else decodeThreads} temp=$temperature"

    companion object {
        const val CHARS_PER_TOKEN = 4
        const val MODEL_CONTEXT_TOKENS = 2048
    }
}
