package com.example.locall.voice.memory

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import org.json.JSONObject

private const val TAG = "MemoryExtractor"

object MemoryExtractor {

    private val MEMORY_GRAMMAR = """
        root ::= "{" ws "\"key\"" ws ":" ws string ws "," ws "\"value\"" ws ":" ws string ws "}" ws
        string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\""
        ws ::= | " " | "\n" [ \t]{0,20}
    """.trimIndent()

    private val SYSTEM_PROMPT = """
        You extract a single fact the user wants remembered into ONE JSON object:
        {"key":"<short subject, 1-3 words>","value":"<the fact>"}
        The "key" is the stable topic so a later fact about the same topic replaces this one.

        Examples:
        "my favorite color is blue" -> {"key":"favorite color","value":"blue"}
        "i'm allergic to peanuts" -> {"key":"allergy","value":"peanuts"}
        "my dog's name is Rex" -> {"key":"dog name","value":"Rex"}
        "i work at Truecaller" -> {"key":"employer","value":"Truecaller"}
        "my wife's birthday is March 3rd" -> {"key":"wife birthday","value":"March 3rd"}

        Output ONLY the JSON object, nothing else.
    """.trimIndent()

    data class Extracted(val key: String, val value: String)

    suspend fun extract(context: Context, statement: String): Extracted {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val raw = LlmBridge.generateWithGrammar(
            systemPrompt = SYSTEM_PROMPT, userPrompt = statement, grammar = MEMORY_GRAMMAR,
            temperature = 0.2f, maxTokens = 80, decodeThreads = decodeThreads,
        )
        Log.i(TAG, "extract(\"$statement\") -> $raw  ·  ${LlmBridge.lastStats}")
        return parseJson(raw, statement)
    }

    fun parseJson(raw: String, statement: String): Extracted = try {
        val o = JSONObject(raw.trim())
        val key = o.optString("key").trim()
        val value = o.optString("value").trim()
        if (key.isNotEmpty() && value.isNotEmpty()) Extracted(key.lowercase(), value)
        else Extracted(statement.lowercase(), statement)
    } catch (t: Throwable) {
        Log.w(TAG, "could not parse \"$raw\": ${t.message}")
        Extracted(statement.lowercase(), statement)
    }
}
