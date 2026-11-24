package com.example.locall.voice.assistant

import android.content.Context
import android.util.Log
import com.example.locall.voice.CommandAction
import com.example.locall.voice.CommandPipeline
import com.example.locall.voice.IntentMatcher
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.calendar.CalendarIntents
import com.example.locall.voice.email.EmailIntents
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

        // Calendar agenda queries are fully deterministic (window parsed from the text, events read from
        // the system provider), so resolve them here with ZERO inference. This is the fast-path that
        // replaces the old behaviour where "what meetings do I have tomorrow" fell through two LLM
        // routing passes into the task planner before answering the wrong thing.
        if (CalendarIntents.matches(t)) {
            Log.i(TAG, "calendar grammar -> Calendar")
            return Routed(CommandAction.Calendar(t), referential = false)
        }

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
            // Constrain to the capability schema. (Previously this used generateJson, which forces
            // the DEVICE-ACTION grammar — so the classifier could only ever emit {"action":…} and
            // never the {"capability":…} it's prompted for; every request fell through to the chat
            // default, making this whole call wasted work. CAPABILITY_GRAMMAR fixes that.)
            val json = LlmBridge.generateWithGrammar(
                systemPrompt = CLASSIFY_SYSTEM, userPrompt = user, grammar = CAPABILITY_GRAMMAR,
                temperature = 0.1f, maxTokens = 48, decodeThreads = decodeThreads, tag = "assistant.route",
            )
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
        // The 1B classifier is biased toward referential=true even for self-contained requests; require
        // an actual back-reference cue before carrying a prior turn's result forward.
        val referential = obj.optBoolean("referential", false) && looksReferential(transcript)
        val action = when (cap) {
            "email" -> if (EmailIntents.matches(transcript.lowercase())) {
                CommandAction.Email(transcript)
            } else {
                Log.i(TAG, "classify said email but transcript has no email signal → chat")
                CommandAction.Agent(transcript, directChat = true)
            }
            // The 1B classifier often reads a general-KNOWLEDGE question ("capital of France") as the
            // documents capability. Only route to Research if the transcript actually references the
            // user's own docs/notes; otherwise answer it as a plain chat question.
            "knowledge", "research", "documents" -> if (looksLikeDocuments(transcript)) {
                CommandAction.Research(transcript)
            } else {
                Log.i(TAG, "classify said knowledge but transcript has no document signal → chat")
                CommandAction.Agent(transcript, directChat = true)
            }
            // The deterministic fast-path in route() catches most calendar phrasings; this is the
            // fallback for ones it didn't (e.g. the request leads with an unusual verb).
            "calendar" -> CommandAction.Calendar(transcript)
            "planning", "agent", "tasks", "inbox" -> CommandAction.Agent(transcript)
            "memory" -> CommandAction.Memory(transcript)
            "music" -> CommandAction.Music(transcript)
            "battery" -> CommandAction.Battery(transcript)
            "device" -> CommandPipeline.resolveDeviceAction(transcript, onStatus)
            // chat / unknown → answer directly; skip the agent's redundant second tool-routing pass.
            else -> CommandAction.Agent(transcript, directChat = true)
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

    private val DOCUMENT_CUES = listOf(
        "document", "documents", "my notes", "note ", "notes", "imported", "knowledge base",
        "my docs", "my files", "the report", "weekly report", "what did i save", "my pdf", "pdf",
        "decisions", "commitments", "in my knowledge", "from my documents",
    )

    /** Does the request actually reference the user's imported documents/notes (vs. general knowledge)? */
    private fun looksLikeDocuments(text: String): Boolean {
        val t = text.lowercase()
        return DOCUMENT_CUES.any { t.contains(it) }
    }

    /** Pull the first {...} object out of the model's output in case it wraps the JSON in prose. */
    private fun extractJson(raw: String): String {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        return if (s in 0 until e) raw.substring(s, e + 1) else raw.trim()
    }

    // Forces the classifier's output to be exactly the capability schema — valid JSON every time,
    // and a tiny (~15-token) decode. Mirrors the enum in CLASSIFY_SYSTEM / parse().
    private val CAPABILITY_GRAMMAR = """
        root ::= "{" ws "\"capability\"" ws ":" ws cap ws "," ws "\"referential\"" ws ":" ws bool ws "}" ws
        cap ::= "\"email\"" | "\"knowledge\"" | "\"planning\"" | "\"calendar\"" | "\"memory\"" | "\"music\"" | "\"battery\"" | "\"device\"" | "\"chat\""
        bool ::= "true" | "false"
        ws ::= [ \t\n]*
    """.trimIndent()

    // Kept deliberately SHORT: this prompt is prefilled on EVERY non-grammar request, and prefill is
    // the dominant per-call cost. The output format is enforced by CAPABILITY_GRAMMAR, so we don't
    // waste tokens describing the JSON — just the capability definitions and a few disambiguating
    // examples. "chat" is the default for anything that isn't clearly one of the tools.
    private val CLASSIFY_SYSTEM = """
        Route the request to ONE capability. Default to "chat" unless it clearly matches another.
        - email: the user's own Gmail/inbox — read, summarize, search, send, reply, delete.
        - knowledge: search/summarize the user's own imported documents and notes.
        - planning: manage the user's OWN tasks, reminders, goals ("add a task", "remind me", "plan my
          day", "make a plan to finish X"); or brief/reply to their captured WhatsApp/SMS messages.
          NOT generic advice or "how do I…" questions.
        - calendar: read the user's calendar/meetings/schedule for a day or week ("what meetings do I
          have tomorrow", "what's on my calendar", "my next meeting"). READ-ONLY agenda lookups.
        - memory: remember or recall personal facts about the user.
        - music: play, pause, skip music or a song/artist.
        - battery: battery or power advice.
        - device: control the phone — open app, type, navigate, screenshot, lock, alarm, timer, torch, volume.
        - chat: a general question or anything else (facts, advice, how-to, weather, math).

        Set "referential": true only if the request depends on an earlier turn's RESULT ("those", "that",
        "based on that", "from the emails above"); else false.

        Examples:
        "review my last 50 emails" -> email / false
        "plan my day based on those emails" -> planning / true
        "summarise my unread whatsapp" -> planning / false
        "search my documents for the Q3 budget" -> knowledge / false
        "how do I stay focused" -> chat / false
        "what's the capital of France" -> chat / false
        "add a task to call the bank" -> planning / false
        "what meetings do I have tomorrow" -> calendar / false
        "what's on my calendar today" -> calendar / false
    """.trimIndent()
}
