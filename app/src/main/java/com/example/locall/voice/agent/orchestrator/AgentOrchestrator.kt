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
        contextBlock: String = "",
        directChat: Boolean = false,
    ): ToolExecutor.Result {
        // directChat skips the LLM tool-routing pass: the capability router already determined this is
        // a plain conversational question, so route straight to the chat tool and save one inference.
        val tool = if (directChat) AgentTool.Chat(utterance) else ToolRegistry.resolve(context, utterance, io.onStatus)
        Log.i(TAG, "utterance=\"$utterance\" directChat=$directChat -> ${tool::class.simpleName}")
        val result = executor.execute(tool, convo, io, contextBlock)
        convo.record(utterance, result.spoken)
        return result
    }
}
