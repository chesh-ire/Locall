package com.example.locall.voice.assistant

import android.content.Context
import android.util.Log
import com.example.locall.voice.CommandAction
import com.example.locall.voice.CommandPipeline

private const val TAG = "AssistantOrchestrator"

/**
 * The central orchestration layer: understand a free-form request, pick ONE capability, run it through
 * the EXISTING singleton handlers ([CommandPipeline.runAction]) — preserving their session state — and
 * return the result. Prior turns are remembered in [ConversationStore] and, for referential follow-ups,
 * carried forward as `contextBlock` (e.g. an email summary grounding a later "plan my day").
 *
 * Capabilities stay independent internally; this is the single interface in front of all of them.
 */
class AssistantOrchestrator(appContext: Context) {

    private val app = appContext.applicationContext
    val store = ConversationStore()

    data class Outcome(val capability: String, val result: String)

    /**
     * Route and execute one request. [onStatus]/[confirm]/[listen] are supplied by the caller (the
     * conversation ViewModel wires them to on-screen bubbles; the hands-free path could wire voice).
     */
    suspend fun run(
        request: String,
        onStatus: (String) -> Unit,
        confirm: suspend (String) -> Boolean,
        listen: suspend (String) -> String?,
    ): Outcome {
        val recent = store.recentHistory()
        val routed = CapabilityRouter.route(app, request, recent, onStatus)
        val contextBlock = if (routed.referential) store.contextBlock() else ""
        val capability = labelFor(routed.action)
        Log.i(TAG, "run request=\"$request\" -> $capability (referential=${routed.referential})")

        val result = CommandPipeline.runAction(
            action = routed.action,
            appContext = app,
            mediaWasPlaying = false,
            onStatus = onStatus,
            confirm = confirm,
            listen = listen,
            contextBlock = contextBlock,
        )
        store.record(request, capability, result)
        return Outcome(capability, result)
    }

    fun reset() = store.clear()

    private fun labelFor(action: CommandAction): String = when (action) {
        is CommandAction.Email -> "Email"
        is CommandAction.Research -> "Knowledge"
        is CommandAction.Calendar -> "Calendar"
        is CommandAction.Agent -> "Assistant"
        is CommandAction.Memory -> "Memory"
        is CommandAction.Music -> "Music"
        is CommandAction.Battery -> "Battery"
        is CommandAction.Unrecognized -> "Assistant"
        else -> "Device"
    }
}
