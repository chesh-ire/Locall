package com.example.locall.voice.agent

import android.content.Context
import android.util.Log
import com.example.locall.voice.ActionConfirmation
import com.example.locall.voice.agent.orchestrator.AgentOrchestrator
import com.example.locall.voice.agent.orchestrator.ConversationManager
import com.example.locall.voice.agent.orchestrator.ToolExecutor

private const val TAG = "PersonalAgent"

private const val MAX_FOLLOWUP_TURNS = 8

class PersonalAgentCommandHandler(appContext: Context) {

    private val app = appContext.applicationContext
    private val orchestrator = AgentOrchestrator(app)
    private val convo = ConversationManager()

    suspend fun handle(
        context: Context,
        transcript: String,
        onStatus: (String) -> Unit,
        confirm: suspend (String) -> Boolean,
        speak: (String) -> Unit,
        listen: suspend (String) -> String?,
    ): String {
        val io = ToolExecutor.Io(speak, confirm, listen, onStatus)
        val first = try {
            orchestrator.run(context, transcript, convo, io)
        } catch (t: Throwable) {
            Log.w(TAG, "agent run failed", t)
            return "⚠️ the agent hit an error: ${t.javaClass.simpleName}"
        }
        speak(forSpeech(first.spoken))

        if (!first.continueConversation) return first.display

        var display = first.display
        var misses = 0
        repeat(MAX_FOLLOWUP_TURNS) { turn ->
            val prompt = if (turn == 0) "" else "Anything else? Say done when you're finished."
            val reply = listen(prompt)
            when {
                reply.isNullOrBlank() -> {
                    if (++misses >= 2) { speak("Okay."); return display }
                }
                isDone(reply) -> { speak("Okay, done."); return display }
                else -> {
                    misses = 0
                    val res = try {
                        orchestrator.run(context, reply, convo, io)
                    } catch (t: Throwable) {
                        Log.w(TAG, "follow-up failed", t); speak("Sorry, that didn't work."); return@repeat
                    }
                    speak(forSpeech(res.spoken))
                    display = res.display
                }
            }
        }
        return display
    }

    private fun isDone(reply: String): Boolean {
        if (ActionConfirmation.isCancellation(reply)) return true
        return reply.trim().lowercase() in setOf(
            "done", "nothing", "nothing else", "no", "no thanks", "that's all", "thats all",
            "i'm done", "im done", "stop", "that's it", "thats it", "that is all", "finished",
        )
    }

    private fun forSpeech(text: String): String =
        text.replace(Regex("\\[[^\\]]*\\]"), "").replace(Regex("[ \\t]{2,}"), " ").trim()
}
