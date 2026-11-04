package com.example.locall.voice

import android.content.Context
import android.util.Log
import com.example.locall.voice.music.MediaPlaybackGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "VoiceOrchestrator"

data class VoiceUiState(
    val recognizerReady: Boolean = false,
    val recording: Boolean = false,
    val busy: Boolean = false,
    val transcript: String = "",
    val lastAction: String = "",
    val status: String = "Checking speech recognizer…",
)

class VoiceOrchestrator(
    appContext: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val appContext = appContext.applicationContext
    private val speech = SpeechRecognitionBridge(appContext)

    private val _ui = MutableStateFlow(VoiceUiState())
    val ui: StateFlow<VoiceUiState> = _ui.asStateFlow()

    fun init() {
        val available = speech.isAvailable()
        _ui.value = _ui.value.copy(
            recognizerReady = available,
            status = when {
                !available -> "No speech recognizer available on this device."
                else -> "Ready. Hold the button and speak."
            },
        )
    }

    fun startListening() {
        if (!_ui.value.recognizerReady || _ui.value.recording) return
        _ui.value = _ui.value.copy(recording = true, transcript = "", lastAction = "", status = "Listening…")

        scope.launch {
            val didPause = MediaPlaybackGate.pauseForCommand(appContext)
            Log.i(TAG, "startListening: awaiting speech.recognizeOnce()...")
            val text = speech.recognizeOnce(timeoutMs = 15_000)
            Log.i(TAG, "startListening: recognizeOnce returned: ${text?.let { "\"$it\"" } ?: "null"}")
            val onDeviceNote = if (speech.guaranteedOnDevice) "" else " (device recognizer — offline not guaranteed)"
            _ui.value = _ui.value.copy(recording = false, busy = true, status = "Thinking…$onDeviceNote")

            try {
                val res = CommandPipeline.runText(
                    text ?: "",
                    appContext = appContext,
                    // `didPause` captures whether media was playing *before* we paused it to
                    // listen — the honest "is media playing" signal for volume routing.
                    mediaWasPlaying = didPause,
                    onStatus = { status -> _ui.value = _ui.value.copy(status = status) },
                    confirm = { description ->
                        VoiceAccessibilityService.instance?.let {
                            ActionConfirmation.confirm(it, speech, description)
                        } ?: false
                    },
                )
                if (didPause && !res.keepMusicPaused) MediaPlaybackGate.resume(appContext)
                Log.i(TAG, "startListening: pipeline result transcript=\"${res.transcript}\" action=\"${res.action}\"")
                _ui.value = _ui.value.copy(
                    busy = false,
                    transcript = res.transcript.ifEmpty { "(nothing heard)" },
                    lastAction = res.action,
                    status = "Ready.$onDeviceNote",
                )
            } catch (t: Throwable) {
                if (didPause) MediaPlaybackGate.resume(appContext)
                Log.e(TAG, "startListening: pipeline threw", t)
                _ui.value = _ui.value.copy(
                    busy = false,
                    status = "Error: ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
    }

    fun stopListeningAndRun() {
        if (!_ui.value.recording) return
        speech.finishNow()
    }

    fun destroy() {
        speech.destroy()
    }
}
