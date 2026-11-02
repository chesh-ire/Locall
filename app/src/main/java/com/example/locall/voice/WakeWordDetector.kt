package com.example.locall.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

class WakeWordDetector(
    modelsDir: File,
    private val threshold: Float = 0.5f,
) {
    companion object {
        private const val TAG = "WakeWord"
        const val CHUNK_SAMPLES = 1280
        private const val MEL_BINS = 32
        private const val EMB_WINDOW = 76
        private const val EMB_DIM = 96
        private const val WW_WINDOW = 16
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val sessionOpts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }

    private val melSession = loadSession(File(modelsDir, "melspectrogram.onnx"))
    private val embSession = loadSession(File(modelsDir, "embedding_model.onnx"))
    private val wwSession = loadSession(File(modelsDir, "hey_locall.onnx"))

    private val melInput = melSession.inputNames.first()
    private val embInput = embSession.inputNames.first()
    private val wwInput = wwSession.inputNames.first()

    private val melBuffer = ArrayDeque<FloatArray>()
    private val embBuffer = ArrayDeque<FloatArray>()

    init {
        Log.i(TAG, "ORT sessions ready: melInput=$melInput embInput=$embInput wwInput=$wwInput")
    }

    fun feed(chunk: ShortArray): Float? {
        require(chunk.size == CHUNK_SAMPLES) { "feed() expects $CHUNK_SAMPLES samples, got ${chunk.size}" }

        val audio = FloatBuffer.allocate(CHUNK_SAMPLES)
        for (s in chunk) audio.put(s.toFloat())
        audio.rewind()
        val (melFlat, melFrames) = OnnxTensor.createTensor(env, audio, longArrayOf(1, CHUNK_SAMPLES.toLong())).use { t ->
            melSession.run(mapOf(melInput to t)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                val flat = FloatArray(fb.remaining()); fb.get(flat)
                flat to flat.size / MEL_BINS
            }
        }
        for (f in 0 until melFrames) {
            val frame = FloatArray(MEL_BINS) { melFlat[f * MEL_BINS + it] / 10f + 2f }
            melBuffer.addLast(frame)
        }

        while (melBuffer.size > EMB_WINDOW) melBuffer.removeFirst()
        if (melBuffer.size < EMB_WINDOW) return null

        val embIn = FloatBuffer.allocate(EMB_WINDOW * MEL_BINS)
        for (r in 0 until EMB_WINDOW) for (c in 0 until MEL_BINS) embIn.put(melBuffer.elementAt(r)[c])
        embIn.rewind()
        val emb = FloatArray(EMB_DIM)
        OnnxTensor.createTensor(env, embIn, longArrayOf(1, EMB_WINDOW.toLong(), MEL_BINS.toLong(), 1)).use { t ->
            embSession.run(mapOf(embInput to t)).use { r ->
                (r[0] as OnnxTensor).floatBuffer.get(emb)
            }
        }
        embBuffer.addLast(emb)
        while (embBuffer.size > WW_WINDOW) embBuffer.removeFirst()

        if (embBuffer.size < WW_WINDOW) return null
        val wwIn = FloatBuffer.allocate(WW_WINDOW * EMB_DIM)
        for (t in 0 until WW_WINDOW) for (c in 0 until EMB_DIM) wwIn.put(embBuffer.elementAt(t)[c])
        wwIn.rewind()
        return OnnxTensor.createTensor(env, wwIn, longArrayOf(1, WW_WINDOW.toLong(), EMB_DIM.toLong())).use { t ->
            wwSession.run(mapOf(wwInput to t)).use { r ->
                (r[0] as OnnxTensor).floatBuffer.get(0)
            }
        }
    }

    fun isWake(score: Float?) = score != null && score >= threshold

    fun close() {
        melSession.close(); embSession.close(); wwSession.close()
        sessionOpts.close()
    }

    private fun loadSession(f: File): OrtSession {
        require(f.exists()) { "Missing wake-word model: ${f.absolutePath}" }
        return env.createSession(f.absolutePath, sessionOpts)
    }
}
