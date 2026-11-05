package com.example.locall.voice

import android.util.Log

private const val TAG = "ActionConfirmation"

object ActionConfirmation {
    private val CONFIRM_WORDS = setOf(
        "yes", "yeah", "yep", "yup", "yah", "ya", "confirm", "confirmed",
        "go ahead", "do it", "sure", "okay", "ok", "send", "send it", "please do", "affirmative",
        "looks good", "sounds good", "that's good", "thats good", "perfect", "great", "send that",
    )
    private val CANCEL_WORDS = setOf(
        "no", "nope", "nah", "cancel", "stop", "don't", "dont", "abort", "never mind", "nevermind",
        "discard", "scrap it", "forget it", "don't send", "dont send", "leave it",
    )

    private fun matchesAny(reply: String, words: Set<String>): Boolean {
        val r = reply.lowercase().trim().trimEnd('.', '!', '?')
        return words.any { r == it || r.startsWith("$it ") || r.startsWith("$it,") }
    }

    /** True when a spoken follow-up is a clear approval to send (e.g. "yes", "send it", "looks good"). */
    fun isApproval(reply: String): Boolean = matchesAny(reply, CONFIRM_WORDS)

    /** True when a spoken follow-up clearly cancels/discards (e.g. "no", "cancel", "don't send"). */
    fun isCancellation(reply: String): Boolean = matchesAny(reply, CANCEL_WORDS)

    /**
     * Speak [prompt], then capture ONE free-form spoken reply and return it verbatim (null if nothing was
     * heard). Used by the email reply refine loop: unlike [confirm] it doesn't classify the answer — the
     * caller decides whether the reply was an approval, a cancel, or a refinement instruction.
     */
    suspend fun listen(
        svc: VoiceAccessibilityService,
        speech: SpeechRecognitionBridge,
        prompt: String,
        timeoutMs: Long = 12_000,
    ): String? {
        if (prompt.isNotBlank()) svc.speak(prompt)
        val reply = speech.recognizeOnce(timeoutMs = timeoutMs)?.trim()
        Log.i(TAG, "listen(\"${prompt.take(60)}…\"): heard=${reply?.let { "\"$it\"" } ?: "nothing"}")
        return reply?.ifBlank { null }
    }

    /**
     * Speak [description] and listen for a spoken yes/no. Retries once when the recognizer catches
     * nothing (a single short "yes" after a prompt is easy for the on-device recognizer to miss),
     * so a genuine answer isn't lost to one no-match. Only an explicit cancel word — or two misses —
     * counts as "no", matching the irreversible-action default of not proceeding unless clearly told to.
     */
    suspend fun confirm(
        svc: VoiceAccessibilityService,
        speech: SpeechRecognitionBridge,
        description: String,
    ): Boolean {
        repeat(2) { attempt ->
            val prompt = if (attempt == 0) "$description Say yes to confirm, or no to cancel."
            else "Sorry, I didn't catch that. Say yes to confirm, or no to cancel."
            svc.speak(prompt)
            val reply = speech.recognizeOnce(timeoutMs = 9_000)?.lowercase()?.trim()
            Log.i(TAG, "confirm attempt ${attempt + 1} (\"$description\"): heard=${reply?.let { "\"$it\"" } ?: "nothing"}")
            if (!reply.isNullOrEmpty()) {
                if (CONFIRM_WORDS.any { reply == it || reply.startsWith("$it ") }) return true
                if (CANCEL_WORDS.any { reply == it || reply.startsWith("$it ") }) return false
                // Heard something, but it wasn't a clear yes/no — give one more try, then default to no.
            }
        }
        return false
    }
}
