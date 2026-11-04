package com.example.locall.voice.battery

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import org.json.JSONObject

private const val TAG = "BatteryAdvisor"

object BatteryAdvisor {

    private val ALLOWED_TIMEOUTS = listOf(15, 30, 60, 120, 300, 600)

    private val GRAMMAR = """
        root ::= "{" ws "\"actions\"" ws ":" ws "[" ws items? ws "]" ws "}" ws
        items ::= action (ws "," ws action)*
        action ::= "{" ws "\"op\"" ws ":" ws op ws "," ws "\"value\"" ws ":" ws int ws "}"
        op ::= ( "\"set_brightness\"" | "\"disable_auto_brightness\"" | "\"set_screen_timeout\"" | "\"enable_battery_saver\"" )
        int ::= "-"? ([0-9] | [1-9] [0-9]*)
        ws ::= | " " | "\n" [ \t]{0,20}
    """.trimIndent()

    private val SYSTEM_PROMPT = """
        You are an on-device battery advisor. Given the current device state and the user's
        request, choose power-saving actions that will extend battery life. Output ONE JSON
        object with an "actions" array. Each action has "op" and an integer "value".

        Ops:
        {"op":"set_brightness","value":<1-100>}        lower screen brightness to this percent
        {"op":"disable_auto_brightness","value":0}     switch off adaptive brightness
        {"op":"set_screen_timeout","value":<seconds>}  shorten the screen-off timeout
        {"op":"enable_battery_saver","value":0}         turn on the system battery saver

        Rules:
        - Only recommend actions that actually help given the state (don't lower brightness if
          it is already low; don't enable battery saver if it is already on).
        - When the battery is low or the user asks to save power, be more aggressive.
        - Prefer timeout values from: 15, 30, 60, 120.
        - Return an empty array if nothing useful can be changed.

        Output ONLY the JSON object, nothing else.
    """.trimIndent()

    suspend fun advise(context: Context, request: String, snapshot: DeviceSnapshot): List<BatteryRecommendation> {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val user = "State: ${snapshot.describe()}\nRequest: $request"
        val raw = LlmBridge.generateWithGrammar(
            systemPrompt = SYSTEM_PROMPT, userPrompt = user, grammar = GRAMMAR,
            temperature = 0.2f, maxTokens = 160, decodeThreads = decodeThreads,
        )
        Log.i(TAG, "advise(\"$request\") -> $raw  ·  ${LlmBridge.lastStats}")
        return validate(parse(raw), snapshot)
    }

    private fun parse(raw: String): List<BatteryAction> = try {
        val arr = JSONObject(raw.trim()).optJSONArray("actions") ?: return emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val value = o.optInt("value", 0)
                when (o.optString("op")) {
                    "set_brightness" -> add(BatteryAction.SetBrightness(value))
                    "disable_auto_brightness" -> add(BatteryAction.DisableAutoBrightness)
                    "set_screen_timeout" -> add(BatteryAction.SetScreenTimeout(value))
                    "enable_battery_saver" -> add(BatteryAction.EnableBatterySaver)
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "could not parse \"$raw\": ${t.message}")
        emptyList()
    }

    private fun validate(actions: List<BatteryAction>, s: DeviceSnapshot): List<BatteryRecommendation> {
        val seen = HashSet<String>()
        val out = ArrayList<BatteryRecommendation>()
        for (a in actions) {
            val key = a::class.simpleName ?: continue
            if (!seen.add(key)) continue
            val rec = when (a) {
                is BatteryAction.SetBrightness -> {
                    val target = a.percent.coerceIn(5, 100)
                    if (!s.autoBrightness && s.brightnessPercent in 0..target) null
                    else BatteryRecommendation(BatteryAction.SetBrightness(target), "Set brightness to $target%")
                }
                is BatteryAction.DisableAutoBrightness ->
                    if (!s.autoBrightness) null
                    else BatteryRecommendation(a, "Turn off adaptive brightness")
                is BatteryAction.SetScreenTimeout -> {
                    val target = snapTimeout(a.seconds)
                    if (s.screenTimeoutSeconds in 1..target) null
                    else BatteryRecommendation(BatteryAction.SetScreenTimeout(target), "Shorten screen timeout to ${target}s")
                }
                is BatteryAction.EnableBatterySaver ->
                    if (s.powerSaveMode) null
                    else BatteryRecommendation(a, "Turn on battery saver")
            }
            if (rec != null) out.add(rec)
        }
        return out
    }

    private fun snapTimeout(seconds: Int): Int =
        ALLOWED_TIMEOUTS.minByOrNull { kotlin.math.abs(it - seconds) } ?: 30

    suspend fun summarize(
        context: Context,
        before: DeviceSnapshot,
        after: DeviceSnapshot,
        results: List<ExecResult>,
    ): String {
        val applied = results.filter { it.applied }.joinToString("; ") { it.detail }
        if (applied.isEmpty()) return "Nothing to change — your device is already set up to save power."
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val user = "Before: ${before.describe()}\nAfter: ${after.describe()}\nChanges applied: $applied"
        return try {
            LlmBridge.generateText(
                systemPrompt = SUMMARY_PROMPT, userPrompt = user,
                temperature = 0.6f, maxTokens = 70, decodeThreads = decodeThreads,
            ).trim().ifEmpty { fallbackSummary(after, applied) }
        } catch (t: Throwable) {
            Log.w(TAG, "summarize failed", t)
            fallbackSummary(after, applied)
        }
    }

    private fun fallbackSummary(after: DeviceSnapshot, applied: String): String =
        "Done: $applied. Battery is at ${after.batteryPercent}%."

    private val SUMMARY_PROMPT =
        "You are a battery assistant. In ONE short friendly sentence, confirm the power-saving " +
            "changes that were just applied and the benefit. No lists, no preamble."
}
