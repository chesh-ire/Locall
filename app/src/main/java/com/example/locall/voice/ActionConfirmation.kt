package com.example.locall.voice

import android.util.Log

private const val TAG = "ActionConfirmation"

object ActionConfirmation {
    private val CONFIRM_WORDS = setOf(
        "yes", "yeah", "yep", "yup", "confirm", "confirmed",
        "go ahead", "do it", "sure", "okay", "ok",
    )

    suspend fun confirm(
        svc: VoiceAccessibilityService,
        speech: SpeechRecognitionBridge,
        description: String,
    ): Boolean {
        svc.speak("About to $description. Say yes to confirm, or anything else to cancel.")
        val reply = speech.recognizeOnce(timeoutMs = 6_000)?.lowercase()?.trim()
        Log.i(TAG, "confirm(\"$description\"): heard=${reply?.let { "\"$it\"" } ?: "nothing"}")
        if (reply.isNullOrEmpty()) return false
        return CONFIRM_WORDS.any { reply == it || reply.startsWith("$it ") }
    }
}
