package com.example.locall.voice.music

import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import android.content.Context
import org.json.JSONObject

private const val TAG = "MusicIntentParser"

object MusicIntentParser {

    private val MUSIC_GRAMMAR = """
        root ::= "{" ws "\"kind\"" ws ":" ws kind ws "," ws "\"title\"" ws ":" ws string ws "," ws "\"artist\"" ws ":" ws string ws "}" ws
        kind ::= ( "\"play_track\"" | "\"play_artist\"" | "\"unknown\"" )
        string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\""
        ws ::= | " " | "\n" [ \t]{0,20}
    """.trimIndent()

    private val SYSTEM_PROMPT = """
        You convert a music play request into ONE JSON object.
        Always include both "title" and "artist"; use "" when not applicable.
        CRITICAL: Only fill "artist" if the user EXPLICITLY said the artist's name.
        NEVER guess or invent an artist. If no artist was said, "artist" MUST be "".
        Shapes:
        {"kind":"play_track","title":"<song name the user said>","artist":"<artist only if said, else empty>"}
        {"kind":"play_artist","title":"","artist":"<artist name the user said>"}
        {"kind":"unknown","title":"","artist":""}

        Examples:
        "busy woman" -> {"kind":"play_track","title":"busy woman","artist":""}
        "all too well" -> {"kind":"play_track","title":"all too well","artist":""}
        "shake it off by taylor swift" -> {"kind":"play_track","title":"shake it off","artist":"taylor swift"}
        "a taylor swift song" -> {"kind":"play_artist","title":"","artist":"taylor swift"}
        "some adele" -> {"kind":"play_artist","title":"","artist":"adele"}

        Output ONLY the JSON object, nothing else.
    """.trimIndent()

    suspend fun parse(context: Context, query: String): MusicIntent {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val raw = LlmBridge.generateWithGrammar(
            systemPrompt = SYSTEM_PROMPT, userPrompt = query, grammar = MUSIC_GRAMMAR,
            temperature = 0.2f, maxTokens = 80, decodeThreads = decodeThreads,
        )
        Log.i(TAG, "parse(\"$query\") -> $raw  ·  ${LlmBridge.lastStats}")
        return parseJson(raw, query)
    }

    fun parseJson(raw: String, query: String): MusicIntent = try {
        val o = JSONObject(raw.trim())
        val title = o.optString("title").trim()
        val artist = o.optString("artist").trim()
        when (o.optString("kind")) {
            "play_track" -> MusicIntent.PlayTrack(title.ifEmpty { query }, artist.ifEmpty { null })
            "play_artist" -> if (artist.isNotEmpty()) MusicIntent.PlayArtist(artist)
                else MusicIntent.PlayTrack(query)
            else -> MusicIntent.PlayTrack(query)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "could not parse \"$raw\": ${t.message}")
        MusicIntent.PlayTrack(query)
    }
}
