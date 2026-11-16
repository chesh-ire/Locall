package com.example.locall.voice.assistant

import android.content.Context
import android.util.Log
import com.example.locall.voice.CommandAction
import com.example.locall.voice.CommandPipeline
import com.example.locall.voice.IntentMatcher
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import org.json.JSONObject

private const val TAG = "CapabilityRouter"

/**
 * The central "understand the request → pick ONE capability" step of the conversational assistant.
 *
 * Routing is two-tier, mirroring the existing voice pipeline but capability-aware:
 *  1. Deterministic grammar ([IntentMatcher.grammarOnly]) — reuses every `XxxIntents.matches` plus the
 *     device-control grammar. Fast, offline, zero inference. Grammar hits are never "referential".
 *  2. On a grammar miss, an on-device LLM **capability classifier** (unlike the old device-only
 *     `llmFallback`) that returns which capability should handle the request AND whether it depends on
 *     an earlier turn's result. The chosen capability wraps the raw transcript in the matching
 *     [CommandAction]; "device" delegates to [CommandPipeline.resolveDeviceAction] to extract the
 *     specific device action.
 *
 * The transcript is passed through untouched so the downstream handlers' own keyword matching still
 * works — prior-turn context travels separately via `contextBlock` (see [ConversationStore]).
 */
object CapabilityRouter {

    data class Routed(val action: CommandAction, val referential: Boolean)

    private val matcher = IntentMatcher()

    suspend fun route(
        context: Context,
        transcript: String,
        recentHistory: String,
        onStatus: (String) -> Unit = {},
    ): Routed {
        val t = transcript.trim()
        if (t.isEmpty()) return Routed(CommandAction.Unrecognized(transcript, "empty"), false)

        matcher.grammarOnly(t)?.let {
            // Grammar bypasses the LLM's referential detection, so apply a cheap phrase heuristic here —
            // this is what lets "plan my day based on those emails" (grammar: PLAN_DAY) still carry the
            // prior email summary forward as context.
            val referential = recentHistory.isNotBlank() && looksReferential(t)
            Log.i(TAG, "grammar -> $it (referential=$referential)")
            return Routed(it, referential)
        }

        return classify(context, t, recentHistory, onStatus)
    }

    private suspend fun classify(
        context: Context,
        transcript: String,
        recentHistory: String,
        onStatus: (String) -> Unit,
    ): Routed {
        return try {
            LlmBridge.ensureLoaded(context, onStatus)
            val decodeThreads = ThermalGovernor.decodeThreads(context)
            val user = buildString {
                if (recentHistory.isNotBlank()) {
                    append("Conversation so far:\n").append(recentHistory).append("\n\n")
                }
                append("Request: ").append(transcript)
            }
            val json = LlmBridge.generateJson(CLASSIFY_SYSTEM, user, decodeThreads, tag = "assistant.route")
            Log.i(TAG, "classify raw: $json")
            parse(context, json, transcript, onStatus)
        } catch (t: Throwable) {
            Log.w(TAG, "classify failed, defaulting to chat", t)
            Routed(CommandAction.Agent(transcript), referential = false)
        }
    }

    private suspend fun parse(
        context: Context,
        raw: String,
        transcript: String,
        onStatus: (String) -> Unit,
    ): Routed {
        val obj = runCatching { JSONObject(extractJson(raw)) }.getOrNull()
            ?: return Routed(CommandAction.Agent(transcript), false)
        val cap = obj.optString("capability", "chat").lowercase().trim()
        val referential = obj.optBoolean("referential", false)
        val action = when (cap) {
            "email" -> CommandAction.Email(transcript)
            "knowledge", "research", "documents" -> CommandAction.Research(transcript)
            "planning", "agent", "tasks", "inbox" -> CommandAction.Agent(transcript)
            "memory" -> CommandAction.Memory(transcript)
            "music" -> CommandAction.Music(transcript)
            "battery" -> CommandAction.Battery(transcript)
            "device" -> CommandPipeline.resolveDeviceAction(transcript, onStatus)
            else -> CommandAction.Agent(transcript) // chat / unknown → agent's grounded chat fallback
        }
        Log.i(TAG, "classify -> capability=$cap referential=$referential action=$action")
        return Routed(action, referential)
    }

    private val REFERENTIAL_CUES = listOf(
        "those", "these", "that email", "that message", "based on that", "based on those",
        "from the email", "from those", "from these", "from the message", "above", "you just",
        "the ones you", "about them", "with that", "using that", "from that",
    )

    /** Does the request lean on a previous turn's result? Cheap phrase check for the grammar path. */
    private fun looksReferential(text: String): Boolean {
        val t = text.lowercase()
        return REFERENTIAL_CUES.any { t.contains(it) }
    }

    /** Pull the first {...} object out of the model's output in case it wraps the JSON in prose. */
    private fun extractJson(raw: String): String {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        return if (s in 0 until e) raw.substring(s, e + 1) else raw.trim()
    }

    private val CLASSIFY_SYSTEM = """
        You route a user request to exactly ONE capability of an on-device personal assistant.
        Capabilities:
        - email: Gmail — read, summarize, search, send, reply to, delete emails or the inbox.
        - knowledge: search or summarize the user's own imported documents and notes; decisions,
          commitments, or a weekly report drawn from them.
        - planning: to-do tasks, reminders, goals, "plan my day"; and the unified message inbox —
          briefing or replying to captured WhatsApp / SMS / notification messages.
        - memory: remember or recall personal facts about the user ("remember that…", "what do you
          know about me").
        - music: play, pause, skip music; play a song or artist.
        - battery: battery saving or power advice.
        - device: control the phone — open an app, type text, navigate, screenshot, lock, set an alarm
          or timer, flashlight, volume.
        - chat: a general question, or anything that doesn't fit the above.

        Also decide "referential": true if the request depends on the RESULT of an earlier turn in the
        conversation (phrases like "those", "that", "based on that", "from the emails above"); otherwise false.

        Output ONLY one JSON object, nothing else:
        {"capability":"email|knowledge|planning|memory|music|battery|device|chat","referential":true|false}

        Examples:
        "review my last 50 emails and tell me what I missed" -> {"capability":"email","referential":false}
        "now help me plan my day based on those emails" -> {"capability":"planning","referential":true}
        "summarise my unread whatsapp messages" -> {"capability":"planning","referential":false}
        "search my documents for the Q3 budget" -> {"capability":"knowledge","referential":false}
        "what's the capital of France" -> {"capability":"chat","referential":false}
    """.trimIndent()
}
