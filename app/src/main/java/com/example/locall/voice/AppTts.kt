package com.example.locall.voice

import android.content.Context

/**
 * Process-scoped text-to-speech so the assistant can read results aloud WITHOUT depending on the
 * accessibility service being connected.
 *
 * Previously every `speak` call routed through `VoiceAccessibilityService.instance?.speak(...)`, which
 * is a silent no-op whenever Accessibility is disabled (e.g. after a reinstall, which revokes the
 * grant) — so spoken email/battery/research output just vanished with no error. This owns the single
 * process-lifetime [PiperTts] engine; [VoiceAccessibilityService] now delegates here too, so there is
 * exactly one voice model loaded regardless of who speaks.
 */
object AppTts {

    @Volatile private var engine: PiperTts? = null

    private fun engine(context: Context): PiperTts =
        engine ?: synchronized(this) {
            engine ?: PiperTts(context.applicationContext).also { engine = it }
        }

    /**
     * Speak [text] aloud. PiperTts is blocking (runs on the caller's thread) and the call is
     * serialized so successive utterances don't overlap or race the ONNX engine.
     */
    @Synchronized
    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        engine(context).speak(text)
    }
}
