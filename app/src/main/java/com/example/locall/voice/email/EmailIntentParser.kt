package com.example.locall.voice.email

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import org.json.JSONObject

private const val TAG = "EmailIntentParser"

object EmailIntentParser {

    private val EMAIL_GRAMMAR = """
        root ::= "{" ws "\"op\"" ws ":" ws op ws "," ws "\"query\"" ws ":" ws string ws "}" ws
        op ::= ( "\"read_latest\"" | "\"summarize\"" | "\"search\"" | "\"find_important\"" | "\"unread_count\"" | "\"mark_all_read\"" | "\"archive\"" | "\"delete\"" | "\"clear_inbox\"" | "\"unknown\"" )
        string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\""
        ws ::= | " " | "\n" [ \t]{0,20}
    """.trimIndent()

    private val SYSTEM_PROMPT = """
        You convert a spoken request about the user's Gmail inbox into ONE JSON object.
        Fields: "op" (the operation) and "query" (a Gmail search query string, or "").
        The "query" uses Gmail search syntax, e.g.:
          category:promotions, category:social, is:unread, in:inbox,
          older_than:30d, newer_than:1d, from:<name>, subject:<word>.
        Combine terms with spaces (AND). Use "" when the op needs no query.

        For "search", put the KEY CONTENT WORDS from the request into "query" (the subject or topic
        the user is looking for), plus a time filter if they mention one. Map "last week" ->
        newer_than:14d, "yesterday" -> newer_than:2d, "this month" -> newer_than:30d.

        Ops:
        {"op":"read_latest","query":""}            read the newest message aloud
        {"op":"summarize","query":"newer_than:1d in:inbox"}  summarise matching mail
        {"op":"search","query":"project deadline newer_than:14d"}  find ONE specific email by topic
        {"op":"find_important","query":""}         surface likely-important unread mail
        {"op":"unread_count","query":""}           how many unread
        {"op":"mark_all_read","query":"is:unread in:inbox"}  mark matching as read
        {"op":"archive","query":"older_than:30d in:inbox"}   remove matching from inbox
        {"op":"delete","query":"category:promotions"}        move matching to trash
        {"op":"clear_inbox","query":""}            archive everything in the inbox
        {"op":"unknown","query":""}                not an email request

        Examples:
        "read my newest message" -> {"op":"read_latest","query":""}
        "summarize emails from my manager" -> {"op":"summarize","query":"from:manager in:inbox"}
        "find the email where someone mentioned the revised project deadline, it came last week" -> {"op":"search","query":"revised project deadline newer_than:14d"}
        "search for the invoice from acme" -> {"op":"search","query":"invoice from:acme"}
        "where's that email about the team offsite" -> {"op":"search","query":"team offsite"}
        "delete all social notifications" -> {"op":"delete","query":"category:social"}
        "archive anything older than a week" -> {"op":"archive","query":"older_than:7d in:inbox"}
        "get rid of newsletters" -> {"op":"delete","query":"category:promotions"}

        Output ONLY the JSON object, nothing else.
    """.trimIndent()

    suspend fun parse(context: Context, request: String): EmailIntent {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val raw = LlmBridge.generateWithGrammar(
            systemPrompt = SYSTEM_PROMPT, userPrompt = request, grammar = EMAIL_GRAMMAR,
            temperature = 0.2f, maxTokens = 80, decodeThreads = decodeThreads,
        )
        Log.i(TAG, "parse(\"$request\") -> $raw  ·  ${LlmBridge.lastStats}")
        return parseJson(raw)
    }

    fun parseJson(raw: String): EmailIntent = try {
        val o = JSONObject(raw.trim())
        val query = o.optString("query").trim()
        when (o.optString("op")) {
            "read_latest" -> EmailIntent.ReadLatest(1)
            "summarize" -> EmailIntent.Summarize(query.ifEmpty { "newer_than:1d in:inbox" })
            "search" -> if (query.isNotEmpty()) EmailIntent.Search(query)
                else EmailIntent.Unknown("search without a topic")
            "find_important" -> EmailIntent.FindImportant
            "unread_count" -> EmailIntent.UnreadCount
            "mark_all_read" -> EmailIntent.MarkAllRead(query.ifEmpty { "is:unread in:inbox" })
            "archive" -> if (query.isNotEmpty()) EmailIntent.Archive(query)
                else EmailIntent.Unknown("archive without a scope")
            "delete" -> if (query.isNotEmpty()) EmailIntent.Delete(query)
                else EmailIntent.Unknown("delete without a scope")
            "clear_inbox" -> EmailIntent.ClearInbox
            else -> EmailIntent.Unknown("model said unknown")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "could not parse \"$raw\": ${t.message}")
        EmailIntent.Unknown("malformed model output")
    }
}
