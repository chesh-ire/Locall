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

    fun isAvailable(): Boolean =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) ||
            SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun recognizeOnce(timeoutMs: Long = 10_000): String? =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val sr = try {
                    ensureRecognizer()
                } catch (t: Throwable) {
                    Log.w(TAG, "could not create recognizer", t)
                    cont.resume(null)
                    return@post
                }

                var finished = false
                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    mainHandler.removeCallbacksAndMessages(RECOGNIZE_TOKEN)
                    cont.resume(result)
                }

                mainHandler.postAtTime(
                    { sr.stopListening()  },
                    RECOGNIZE_TOKEN,
                    android.os.SystemClock.uptimeMillis() + timeoutMs,
                )

                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        finish(text)
                    }
                    override fun onError(error: Int) {
                        Log.w(TAG, "recognition error: $error")
                        finish(null)
                    }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                sr.startListening(intent)

                cont.invokeOnCancellation { mainHandler.post { runCatching { sr.cancel() } } }
            }
        }

    fun finishNow() {
        mainHandler.post { recognizer?.stopListening() }
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
