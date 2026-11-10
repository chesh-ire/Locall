package com.example.locall.voice.agent.orchestrator

import android.content.Context
import android.util.Log

private const val TAG = "AgentOrchestrator"

class AgentOrchestrator(context: Context) {

    private val executor = ToolExecutor(context.applicationContext)

    suspend fun run(
        context: Context,
        utterance: String,
        convo: ConversationManager,
        io: ToolExecutor.Io,
    ): ToolExecutor.Result {
        val tool = ToolRegistry.resolve(context, utterance, io.onStatus)
        Log.i(TAG, "utterance=\"$utterance\" -> ${tool::class.simpleName}")
        val result = executor.execute(tool, convo, io)
        convo.record(utterance, result.spoken)
        return result
    }
}
