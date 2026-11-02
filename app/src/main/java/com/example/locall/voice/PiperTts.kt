package com.example.locall.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

class PiperTts(private val context: Context) {

    companion object {
        private const val TAG = "PiperTts"
        private const val ASSET_DIR = "tts"
    }

    private val extractedDir: File by lazy { File(context.filesDir, ASSET_DIR) }
    private val tts: OfflineTts by lazy { loadTts() }

    fun speak(text: String) {
        if (text.isBlank()) return
        try {
            Log.i(TAG, "speak: loading tts engine (lazy init on first call)")
            val engine = tts
            Log.i(TAG, "speak: tts engine ready, sampleRate=${engine.sampleRate()} lang=${engine.lang()}")

            val phonemes = phonemize(text, engine)
            Log.i(TAG, "speak: phonemized into ${phonemes.size} sentence(s), " +
                    "lengths=${phonemes.map { it.size }}")

            Log.i(TAG, "speak: calling generateWithConfig...")
            val audio = engine.generateWithConfig(
                text = "", config = GenerationConfig(phonemeCodepoints = phonemes)
            )
            Log.i(TAG, "speak: generated ${audio.samples.size} samples @ ${audio.sampleRate}Hz")

            if (audio.samples.isEmpty()) {
                Log.w(TAG, "generated zero samples for: $text")
                return
            }
            playBlocking(audio.samples, audio.sampleRate)
            Log.i(TAG, "speak: playback finished")
        } catch (t: Throwable) {
            Log.e(TAG, "speak failed", t)
        }
    }

    private fun phonemize(text: String, engine: OfflineTts): Array<IntArray> {
        val voice = engine.lang().ifEmpty { "en-us" }
        Log.i(TAG, "phonemize: calling piperPhonemizeText(voice=$voice)...")
        val result = piperPhonemizeText(text, voice) ?: throw IllegalStateException("phonemize failed")
        Log.i(TAG, "phonemize: got result, numSentences=${result.numSentences}")
        return try {
            Array(result.numSentences) { i -> result.getPhonemes(i) }
        } finally {
            result.close()
        }
    }

    private fun loadTts(): OfflineTts {
        extractAssetsIfNeeded()
        Log.i(TAG, "loadTts: calling piperPhonemizeInitialize...")
        piperPhonemizeInitialize(context)
        Log.i(TAG, "loadTts: piperPhonemizeInitialize done; constructing OfflineTts...")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(extractedDir, "en_US-amy-low.onnx").absolutePath,
                    tokens = File(extractedDir, "tokens.txt").absolutePath,
                ),
                numThreads = 2,
                provider = "cpu",
            ),
        )
        val result = OfflineTts(assetManager = null, config = config)
        Log.i(TAG, "loadTts: OfflineTts constructed")
        return result
    }

    private fun extractAssetsIfNeeded() {
        val doneMarker = File(extractedDir, ".extracted")
        if (doneMarker.exists()) return
        Log.i(TAG, "extracting TTS voice assets to $extractedDir (first run only)")
        extractedDir.deleteRecursively()
        copyAssetDir(ASSET_DIR, extractedDir)
        doneMarker.createNewFile()
    }

    private fun copyAssetDir(assetDir: String, destDir: File) {
        val am = context.assets
        destDir.mkdirs()
        for (name in am.list(assetDir) ?: emptyArray()) {
            val childAssetPath = "$assetDir/$name"
            val childDest = File(destDir, name)
            val childEntries = am.list(childAssetPath)
            if (childEntries.isNullOrEmpty()) {
                am.open(childAssetPath).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                copyAssetDir(childAssetPath, childDest)
            }
        }
    }

    private fun playBlocking(samples: FloatArray, sampleRate: Int) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, samples.size * 4))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            Log.i(TAG, "playBlocking: writing ${samples.size} samples (minBuf=$minBuf)")
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            Log.i(TAG, "playBlocking: write done, starting playback")
            track.play()
            val durationMs = (samples.size.toDouble() / sampleRate * 1000).toLong() + 150
            Log.i(TAG, "playBlocking: sleeping ${durationMs}ms")
            Thread.sleep(durationMs)
            Log.i(TAG, "playBlocking: sleep done")
        } finally {
            track.stop()
            track.release()
        }
    }

    fun destroy() {
        tts.release()
    }
}
