package com.example.locall.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SpeechRecognitionBridge(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRec"
    }

    @Volatile
    var guaranteedOnDevice: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** Delivers whatever the in-flight recognition has captured so far and ends it. Set while a
     *  [recognizeOnce] session is active; invoked by [finishNow] (e.g. hold-to-talk release). */
    @Volatile
    private var activeStop: (() -> Unit)? = null

    fun isAvailable(): Boolean =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) ||
            SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Capture one spoken utterance and return its transcript (null if nothing was heard).
     *
     * @param timeoutMs  Hard upper bound on the whole session. Whatever was captured so far is
     *                   returned when it elapses.
     * @param silenceMs  Trailing-silence the recognizer tolerates before deciding the speaker is
     *                   done. Passed via the platform endpointing extras. `null` keeps the OEM
     *                   default (~1-2s) — fine for short yes/no replies, too short for dictation.
     * @param continuous When true, segments are stitched: each time the recognizer ends on silence
     *                   its text is appended and listening restarts, so a thinking pause mid-sentence
     *                   no longer cuts off the command. The utterance ends only on a genuinely empty
     *                   restart (real trailing silence) or [timeoutMs]. This is the robust path for
     *                   long email dictation AND for devices whose recognizer ignores [silenceMs].
     */
    suspend fun recognizeOnce(
        timeoutMs: Long = 10_000,
        silenceMs: Long? = null,
        continuous: Boolean = false,
    ): String? =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val sr = try {
                    ensureRecognizer()
                } catch (t: Throwable) {
                    Log.w(TAG, "could not create recognizer", t)
                    cont.resume(null)
                    return@post
                }

                val buffer = StringBuilder()
                var lastPartial: String? = null
                var finished = false

                fun bufferText(): String? = buffer.toString().trim().ifBlank { null }
                // Prefer the committed segments; fall back to the latest partial if a session was
                // force-stopped or timed out mid-utterance before committing a final result.
                fun deliver(): String? = bufferText() ?: lastPartial?.trim()?.ifBlank { null }

                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    activeStop = null
                    mainHandler.removeCallbacksAndMessages(RECOGNIZE_TOKEN)
                    runCatching { sr.stopListening() }
                    cont.resume(result)
                }

                fun buildIntent(): Intent =
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        if (silenceMs != null) {
                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
                        }
                    }

                // Mutually referenced by the listener; assigned after it is built.
                var restart: () -> Unit = {}

                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim()
                        if (!text.isNullOrBlank()) {
                            if (buffer.isNotEmpty()) buffer.append(' ')
                            buffer.append(text)
                        }
                        lastPartial = null
                        if (continuous && !finished) restart() else finish(bufferText())
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { lastPartial = it }
                    }

                    override fun onError(error: Int) {
                        // In continuous mode a no-match/timeout is how we detect real trailing silence.
                        val endpointed = error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        when {
                            continuous && endpointed && bufferText() != null -> finish(bufferText())
                            continuous && endpointed -> restart() // nothing yet; keep waiting until timeout
                            else -> {
                                Log.w(TAG, "recognition error: $error")
                                finish(deliver())
                            }
                        }
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                restart = {
                    if (!finished) {
                        mainHandler.post {
                            if (!finished) {
                                runCatching { sr.startListening(buildIntent()) }
                                    .onFailure { finish(deliver()) }
                            }
                        }
                    }
                }

                // Hard upper bound — return whatever was captured rather than dropping it.
                mainHandler.postAtTime(
                    { finish(deliver()) },
                    RECOGNIZE_TOKEN,
                    android.os.SystemClock.uptimeMillis() + timeoutMs,
                )

                // Lets finishNow() (hold-to-talk release) end the turn and deliver immediately.
                activeStop = { finish(deliver()) }

                runCatching { sr.startListening(buildIntent()) }
                    .onFailure { finish(null) }

                cont.invokeOnCancellation {
                    mainHandler.post {
                        activeStop = null
                        runCatching { sr.cancel() }
                    }
                }
            }
        }

    fun finishNow() {
        mainHandler.post { activeStop?.invoke() ?: recognizer?.stopListening() }
    }

    fun destroy() {
        mainHandler.post { recognizer?.destroy(); recognizer = null }
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        guaranteedOnDevice = onDeviceAvailable
        val sr = if (onDeviceAvailable) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = sr
        return sr
    }

    private object RECOGNIZE_TOKEN
}
