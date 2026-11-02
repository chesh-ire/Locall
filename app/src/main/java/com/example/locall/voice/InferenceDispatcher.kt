package com.example.locall.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object InferenceDispatcher {
    val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "llm-inference").apply { priority = Thread.NORM_PRIORITY }
        }.asCoroutineDispatcher()
}
