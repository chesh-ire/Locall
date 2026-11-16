package com.example.locall.voice.assistant

/**
 * Cross-capability conversation memory for the central assistant. Each completed exchange is recorded
 * as a [Turn] (what the user asked, which capability ran, the result text). Two things read from it:
 *
 *  - [recentHistory] — a compact transcript given to [CapabilityRouter]'s classifier so it can route
 *    referential follow-ups ("plan my day based on those emails") to the right capability.
 *  - [contextBlock] — the most recent result(s), passed as `contextBlock` to the LLM-driven handlers
 *    when the router flags a turn as referential, so a prior email summary can ground a later plan.
 *
 * This is deliberately separate from each capability's OWN within-session memory (e.g. the agent's
 * [com.example.locall.voice.agent.orchestrator.ConversationManager] or the email session) — those keep
 * working unchanged; this only carries results ACROSS capabilities.
 */
class ConversationStore(
    private val maxTurns: Int = 8,
    private val contextCharBudget: Int = 1600,
) {
    data class Turn(val request: String, val capability: String, val result: String)

    private val turns = ArrayDeque<Turn>()

    @Synchronized
    fun record(request: String, capability: String, result: String) {
        if (result.isBlank()) return
        turns.addLast(Turn(request.trim(), capability, result.trim()))
        while (turns.size > maxTurns) turns.removeFirst()
    }

    /** Compact recent transcript for the router's classifier (oldest→newest). Empty when no history. */
    @Synchronized
    fun recentHistory(limit: Int = 4): String {
        if (turns.isEmpty()) return ""
        return turns.toList().takeLast(limit).joinToString("\n") { t ->
            "User: ${t.request}\nAssistant: ${oneLine(t.result)}"
        }
    }

    /**
     * The prior result(s) to carry into a referential turn's handler. Uses the most recent results,
     * newest first, trimmed to [contextCharBudget] so it never crowds the small model's context window.
     */
    @Synchronized
    fun contextBlock(): String {
        if (turns.isEmpty()) return ""
        val sb = StringBuilder()
        for (t in turns.toList().asReversed()) {
            val piece = stripEmoji(t.result)
            if (piece.isBlank()) continue
            if (sb.isNotEmpty() && sb.length + piece.length > contextCharBudget) break
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(piece)
            if (sb.length >= contextCharBudget) break
        }
        return sb.toString().take(contextCharBudget).trim()
    }

    @Synchronized
    fun hasHistory(): Boolean = turns.isNotEmpty()

    @Synchronized
    fun clear() = turns.clear()

    private fun oneLine(s: String): String =
        stripEmoji(s).replace(Regex("\\s+"), " ").trim().take(200)

    /** Drop leading status emoji/markup so carried context reads as plain prose to the model. */
    private fun stripEmoji(s: String): String =
        s.replace(Regex("[\\p{So}\\p{Cn}]"), "").trim()
}
