package com.example.locall

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.random.Random
import com.example.locall.voice.CommandPipeline
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.LlmModelManager

private const val TAG = "Bench"
private val SYS_PROMPT = "You are a helpful assistant. Reply in one short sentence."
private val USER_PROMPT = "What is the capital of France?"

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class BenchmarkTests {

    private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun ensureQ4Loaded(context: Context) {
        runBlocking { LlmModelManager.ensureExtracted(context) }
        if (!LlmBridge.isLoaded) {
            LlmBridge.load(LlmModelManager.modelFile(context).absolutePath)
        }
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val rank = p / 100.0 * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = rank - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    private fun median(values: List<Double>) = percentile(values, 50.0)

    @Test
    fun test1_modelManagement() {
        val context = ctx()
        val rssBefore = LlmBridge.rssKb()
        Log.i(TAG, "model_mgmt phase=before_load rss_kb=$rssBefore")

        ensureQ4Loaded(context)
        val rssAfterLoad = LlmBridge.rssKb()
        Log.i(TAG, "model_mgmt phase=after_load rss_kb=$rssAfterLoad")

        val info = LlmBridge.modelInfo(LlmBridge.handleForBenchmark)
        info.lines().filter { it.isNotBlank() }.forEach { Log.i(TAG, "model_mgmt meta=$it") }

        val h2 = LlmBridge.loadWithCtx(LlmModelManager.modelFile(context).absolutePath, 2048)
        val rssSecondLoad = LlmBridge.rssKb()
        Log.i(TAG, "model_mgmt phase=second_handle_loaded rss_kb=$rssSecondLoad")
        LlmBridge.releaseHandle(h2)
        System.gc()
        Thread.sleep(500)
        val rssAfterUnload = LlmBridge.rssKb()
        Log.i(TAG, "model_mgmt phase=after_unload rss_kb=$rssAfterUnload")
    }

    @Test
    fun test2_inferenceOptimizationSweep() {
        val context = ctx()
        ensureQ4Loaded(context)
        val handle = LlmBridge.handleForBenchmark

        data class Cfg(val name: String, val decode: Int, val batch: Int, val cores: IntArray)
        val configs = listOf(
            Cfg("A", 1, 2, intArrayOf(6, 7)),
            Cfg("B", 2, 2, intArrayOf(6, 7)),
            Cfg("C", 2, 4, intArrayOf(6, 7)),
            Cfg("D", 2, 6, intArrayOf(6, 7)),
            Cfg("E", 1, 4, intArrayOf(6, 7)),
        )
        val TRIALS = 7

        for (cfg in configs) {
            LlmBridge.generateBenchmark(handle, SYS_PROMPT, USER_PROMPT, 60, cfg.decode, cfg.batch, cfg.cores)
        }

        val schedule = configs.flatMap { cfg -> List(TRIALS) { trial -> cfg to trial } }
            .shuffled(Random(42))
        Log.i(TAG, "sweep schedule_size=${schedule.size} trials_per_config=$TRIALS order=${schedule.joinToString(";") { it.first.name }}")

        val decodeMsByCfg = configs.associate { it.name to mutableListOf<Double>() }
        val prefillMsByCfg = configs.associate { it.name to mutableListOf<Double>() }
        val tokPerSecByCfg = configs.associate { it.name to mutableListOf<Double>() }

        schedule.forEachIndexed { runOrder, (cfg, trial) ->
            val result = LlmBridge.generateBenchmark(
                handle, SYS_PROMPT, USER_PROMPT, 60, cfg.decode, cfg.batch, cfg.cores,
            )
            val (prefillMs, decodeMs, generated, rssKb) = result.split(",")
            val tokPerSec = if (decodeMs.toDouble() > 0) generated.toDouble() * 1000.0 / decodeMs.toDouble() else 0.0
            decodeMsByCfg[cfg.name]!!.add(decodeMs.toDouble())
            prefillMsByCfg[cfg.name]!!.add(prefillMs.toDouble())
            tokPerSecByCfg[cfg.name]!!.add(tokPerSec)
            Log.i(
                TAG,
                "sweep config=${cfg.name} decode_threads=${cfg.decode} batch_threads=${cfg.batch} " +
                    "cores=${cfg.cores.joinToString(";")} trial=$trial run_order=$runOrder prefill_ms=$prefillMs " +
                    "decode_ms=$decodeMs generated=$generated tok_per_sec=%.2f rss_kb=%s".format(tokPerSec, rssKb),
            )
        }

        for (cfg in configs) {
            val d = decodeMsByCfg[cfg.name]!!
            val pf = prefillMsByCfg[cfg.name]!!
            val tps = tokPerSecByCfg[cfg.name]!!
            Log.i(
                TAG,
                "sweep_summary config=${cfg.name} trials=${d.size} " +
                    "decode_ms_median=%.1f decode_ms_p95=%.1f ".format(median(d), percentile(d, 95.0)) +
                    "prefill_ms_median=%.1f prefill_ms_p95=%.1f ".format(median(pf), percentile(pf, 95.0)) +
                    "tok_per_sec_median=%.2f tok_per_sec_p95=%.2f".format(median(tps), percentile(tps, 95.0)),
            )
        }
    }

    @Test
    fun test3_contextKvCacheScaling() {
        val context = ctx()
        val modelPath = run {
            runBlocking { LlmModelManager.ensureExtracted(context) }
            LlmModelManager.modelFile(context).absolutePath
        }

        for (nCtx in intArrayOf(512, 1024, 2048, 4096, 8192, 16384)) {
            val h = LlmBridge.loadWithCtx(modelPath, nCtx)
            if (h == 0L) {
                Log.i(TAG, "kv_cache_memory n_ctx=$nCtx load_failed=true")
                continue
            }
            val rss = LlmBridge.rssKb()
            Log.i(TAG, "kv_cache_memory n_ctx=$nCtx rss_kb=$rss")
            LlmBridge.releaseHandle(h)
            System.gc()
            Thread.sleep(300)
        }

        val h = LlmBridge.loadWithCtx(modelPath, 8192)
        require(h != 0L) { "failed to load model for latency-vs-length sweep" }
        val filler = "The quick brown fox jumps over the lazy dog near the riverbank. "
        val tail = "Summarize the above in one word."

        for (targetTokens in intArrayOf(64, 128, 256, 512, 1024, 1800)) {
            var repeats = 1
            var prompt = filler.repeat(repeats) + tail
            var actualTokens = LlmBridge.countTokens(h, SYS_PROMPT, prompt)
            while (actualTokens < targetTokens && repeats < 400) {
                val perRepeat = (actualTokens.toDouble() / repeats).coerceAtLeast(1.0)
                val needed = ((targetTokens - actualTokens) / perRepeat).toInt().coerceAtLeast(1)
                repeats += needed
                prompt = filler.repeat(repeats) + tail
                actualTokens = LlmBridge.countTokens(h, SYS_PROMPT, prompt)
            }

            val result = LlmBridge.generateBenchmark(h, SYS_PROMPT, prompt, 20, 2, 6, intArrayOf(6, 7))
            val (prefillMs, decodeMs, generated, rssKb) = result.split(",")
            val prefillPerTok = if (actualTokens > 0) prefillMs.toDouble() / actualTokens else 0.0
            Log.i(
                TAG,
                "kv_cache_latency target_tokens=$targetTokens actual_prompt_tokens=$actualTokens " +
                    "filler_repeats=$repeats prefill_ms=$prefillMs prefill_ms_per_tok=%.3f ".format(prefillPerTok) +
                    "decode_ms=$decodeMs generated=$generated rss_kb=$rssKb",
            )
        }
        LlmBridge.releaseHandle(h)
    }

    @Test
    fun test4_quantizationComparison() {
        val context = ctx()
        val q4Path = run {
            runBlocking { LlmModelManager.ensureExtracted(context) }
            LlmModelManager.modelFile(context).absolutePath
        }
        val q5Path = resolveModelPath(context, "Llama-3.2-1B-Instruct-Q5_K_M.gguf")
        val q5Exists = q5Path != null
        Log.i(TAG, "quant q5_pushed=$q5Exists path=${q5Path ?: "(not found)"}")
        if (q5Path == null) {
            Log.i(TAG, "quant SKIPPED: Q5 model not present in filesDir or externalFilesDir/llm")
            return
        }

        val pm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            context.getSystemService(Context.POWER_SERVICE) as PowerManager else null

        for ((idx, entry) in listOf("Q4_K_M" to q4Path, "Q5_K_M" to q5Path).withIndex()) {
            val (label, path) = entry
            if (idx > 0 && pm != null) {
                val coolStart = System.currentTimeMillis()
                while (pm.currentThermalStatus > PowerManager.THERMAL_STATUS_LIGHT &&
                    System.currentTimeMillis() - coolStart < 120_000) {
                    Thread.sleep(5_000)
                }
                Log.i(TAG, "quant cooldown_ms=${System.currentTimeMillis() - coolStart} status_after_cooldown=${pm.currentThermalStatus}")
            }
            val thermalBefore = pm?.currentThermalStatus ?: -1
            val fileSizeMb = File(path).length() / (1024.0 * 1024.0)
            val h = LlmBridge.loadWithCtx(path, 2048)
            require(h != 0L) { "failed to load $label at $path" }
            val rssAfterLoad = LlmBridge.rssKb()
            val info = LlmBridge.modelInfo(h).lines().firstOrNull() ?: ""
            Log.i(TAG, "quant model=$label thermal_status=$thermalBefore file_size_mb=%.1f rss_after_load_kb=$rssAfterLoad info=$info".format(fileSizeMb))

            LlmBridge.generateBenchmark(h, SYS_PROMPT, USER_PROMPT, 60, 2, 6, intArrayOf(6, 7))
            val tps = mutableListOf<Double>()
            repeat(5) { trial ->
                val result = LlmBridge.generateBenchmark(h, SYS_PROMPT, USER_PROMPT, 60, 2, 6, intArrayOf(6, 7))
                val (prefillMs, decodeMs, generated, rssKb) = result.split(",")
                val tokPerSec = if (decodeMs.toDouble() > 0) generated.toDouble() * 1000.0 / decodeMs.toDouble() else 0.0
                tps.add(tokPerSec)
                Log.i(
                    TAG,
                    "quant model=$label trial=$trial prefill_ms=$prefillMs decode_ms=$decodeMs " +
                        "generated=$generated tok_per_sec=%.2f rss_kb=%s".format(tokPerSec, rssKb),
                )
            }
            Log.i(TAG, "quant_perf_summary model=$label tok_per_sec_median=%.2f tok_per_sec_p95=%.2f".format(median(tps), percentile(tps, 95.0)))

            var validJson = 0
            var expectedMatch = 0
            for ((cmd, expectedKind) in QUALITY_COMMANDS) {
                val raw = LlmBridge.generateJsonBenchmark(h, CommandPipeline.COMMAND_SYSTEM_PROMPT, cmd).trim()
                val parsed = validateCommandJson(raw)
                if (parsed.valid) validJson++
                val match = parsed.valid && parsed.kind == expectedKind
                if (match) expectedMatch++
                Log.i(
                    TAG,
                    "quant_quality model=$label cmd=\"$cmd\" expected=$expectedKind got=${parsed.kind ?: "PARSE_FAIL"} " +
                        "valid_json=${parsed.valid} expected_match=$match raw=${raw.replace("\n", " ")}",
                )
            }
            Log.i(
                TAG,
                "quant_quality_summary model=$label commands=${QUALITY_COMMANDS.size} " +
                    "valid_json=$validJson expected_match=$expectedMatch",
            )

            LlmBridge.releaseHandle(h)
            System.gc()
            Thread.sleep(300)
        }
    }

    @Test
    fun test5_thermalUnderSustainedLoad() {
        val context = ctx()
        ensureQ4Loaded(context)
        val handle = LlmBridge.handleForBenchmark

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(TAG, "thermal SKIPPED: requires API 29+, device is ${Build.VERSION.SDK_INT}")
            return
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Log.i(TAG, "thermal headroom_initial=${pm.getThermalHeadroom(0)} status_initial=${pm.currentThermalStatus}")

        val stressCores = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val startMs = System.currentTimeMillis()
        var iteration = 0
        while (System.currentTimeMillis() - startMs < 90_000) {
            val result = LlmBridge.generateBenchmark(handle, SYS_PROMPT, USER_PROMPT, 80, 8, 8, stressCores)
            val (prefillMs, decodeMs, generated, rssKb) = result.split(",")
            val tokPerSec = if (decodeMs.toDouble() > 0) generated.toDouble() * 1000.0 / decodeMs.toDouble() else 0.0
            val status = pm.currentThermalStatus
            val headroom = pm.getThermalHeadroom(10)
            Log.i(
                TAG,
                "thermal iter=$iteration elapsed_ms=${System.currentTimeMillis() - startMs} " +
                    "thermal_status=$status headroom_10s=%.2f prefill_ms=$prefillMs decode_ms=$decodeMs ".format(headroom) +
                    "generated=$generated tok_per_sec=%.2f rss_kb=$rssKb".format(tokPerSec),
            )
            iteration++
        }
        Log.i(TAG, "thermal final_status=${pm.currentThermalStatus}")
    }


    private val QUALITY_COMMANDS = listOf(
        "type hello world" to "type_text",
        "write good morning everyone" to "type_text",
        "open chrome" to "open_app",
        "launch the camera app" to "open_app",
        "go back" to "navigate",
        "scroll down" to "navigate",
        "show my notifications" to "navigate",
        "clear the text field" to "clear_field",
        "send the message" to "send_message",
        "read my last message from mom on whatsapp" to "read_last_message",
        "what's the weather like today" to "unrecognized",
        "tell me a joke" to "unrecognized",
    )

    private data class ParsedCmd(val valid: Boolean, val kind: String?)

    private val VALID_KINDS = setOf(
        "type_text", "open_app", "navigate", "clear_field",
        "send_message", "read_last_message", "unrecognized",
    )

    private fun validateCommandJson(raw: String): ParsedCmd = try {
        val obj = JSONObject(raw)
        val kind = obj.getString("action")
        val bodyOk = when (kind) {
            "type_text" -> obj.has("text")
            "open_app" -> obj.has("query")
            "navigate" -> obj.has("nav") && obj.getString("nav") in setOf(
                "home", "back", "recents", "notifications", "scroll_down", "scroll_up")
            "clear_field", "send_message" -> obj.has("_")
            "read_last_message" -> obj.has("contact") && obj.has("app")
            "unrecognized" -> obj.has("reason")
            else -> false
        }
        ParsedCmd(valid = kind in VALID_KINDS && bodyOk, kind = if (kind in VALID_KINDS) kind else null)
    } catch (e: Exception) {
        ParsedCmd(valid = false, kind = null)
    }

    private fun resolveModelPath(context: Context, filename: String): String? {
        val candidates = listOfNotNull(
            File(File(context.filesDir, "llm"), filename),
            context.getExternalFilesDir(null)?.let { File(File(it, "llm"), filename) },
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath
    }
}
