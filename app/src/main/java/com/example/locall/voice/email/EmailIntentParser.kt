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
          category:promotions, category:social, is:unread, in:inbox, in:spam, in:trash,
          older_than:30d, newer_than:1d, from:<name>, subject:<word>.
        Combine terms with spaces (AND). Use "" when the op needs no query.
        NOTE: Spam and Trash are hidden from normal search — for spam/junk requests the query MUST
        include "in:spam" (e.g. for Trash use "in:trash"), otherwise nothing is found.

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
        "delete my spam" -> {"op":"delete","query":"in:spam"}
        "empty the junk folder" -> {"op":"delete","query":"in:spam"}
        "archive anything older than a week" -> {"op":"archive","query":"older_than:7d in:inbox"}
        "get rid of newsletters" -> {"op":"delete","query":"category:promotions"}

        Output ONLY the JSON object, nothing else.
    """.trimIndent()

    private val SEND_GRAMMAR = """
        root ::= "{" ws "\"to\"" ws ":" ws string ws "," ws "\"subject\"" ws ":" ws string ws "," ws "\"body\"" ws ":" ws string ws "}" ws
        string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\""
        ws ::= | " " | "\n" [ \t]{0,20}
    """.trimIndent()

    private val SEND_SYSTEM_PROMPT = """
        The user is DICTATING an email. Your job is to TRANSCRIBE what they said into a JSON object —
        NOT to write or improve the email. Fields:
          "to"      the recipient's email address (or their name if no address is given),
          "subject" EXACTLY the subject the user stated; only if they gave none, infer a 2-4 word one,
          "body"    EXACTLY the message words the user dictated, verbatim.
        CRITICAL: Use ONLY the user's own words for the body. Never invent, expand, elaborate, rephrase,
        or add greetings, sign-offs, or sentences the user did not say. If they dictate one word, the body
        is that one word. Fix only obvious capitalization/punctuation.
        Spoken email addresses may say "at" for @ and "dot" for "." — write them as a normal address.
        Drop filler markers like "subject", "subject line", "body", "message", "saying". Output ONLY the JSON.

        Examples:
        "send an email to abc@gmail.com subject line be ready body hi"
          -> {"to":"abc@gmail.com","subject":"be ready","body":"Hi"}
        "write an email saying I'll be there at 6pm and send it to abc@gmail.com"
          -> {"to":"abc@gmail.com","subject":"See you at 6pm","body":"I'll be there at 6 pm."}
        "email john dot smith at gmail dot com subject lunch body running late"
          -> {"to":"john.smith@gmail.com","subject":"lunch","body":"Running late"}
    """.trimIndent()

    private val REPLY_GRAMMAR = """
        root ::= "{" ws "\"query\"" ws ":" ws string ws "," ws "\"body\"" ws ":" ws string ws "}" ws
        string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\""
        ws ::= | " " | "\n" [ \t]{0,20}
    """.trimIndent()

    private val REPLY_SYSTEM_PROMPT = """
        The user wants to REPLY to an existing email (same thread). Extract ONE JSON object:
          "query"  a Gmail search that LOCATES the email being replied to:
                     reply to a person        -> "from:<name>"
                     reply to the last email   -> "in:inbox"
                     reply to the email about X -> the topic words of X
          "body"   EXACTLY the reply words the user dictated, verbatim.
        CRITICAL: Use ONLY the user's own words for the body. Never invent, expand, rephrase, or add
        greetings/sign-offs. If they dictate one word, the body is that one word. Fix only caps/punctuation.
        Drop filler ("reply", "to", "the email", "saying", "tell them"). Output ONLY the JSON.

        Examples:
        "reply to the email from Arun saying I'll review it tonight"
          -> {"query":"from:Arun","body":"I'll review it tonight"}
        "reply to the last email saying thanks"
          -> {"query":"in:inbox","body":"Thanks"}
        "reply to the email about the invoice saying payment sent"
          -> {"query":"invoice","body":"Payment sent"}
    """.trimIndent()

    /** Extract a Reply intent (which email to reply to + verbatim body). */
    suspend fun parseReply(context: Context, request: String): EmailIntent {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val raw = LlmBridge.generateWithGrammar(
            systemPrompt = REPLY_SYSTEM_PROMPT, userPrompt = request, grammar = REPLY_GRAMMAR,
            temperature = 0.2f, maxTokens = 220, decodeThreads = decodeThreads,
        )
        Log.i(TAG, "parseReply(\"$request\") -> $raw  ·  ${LlmBridge.lastStats}")
        return try {
            val o = JSONObject(raw.trim())
            val query = o.optString("query").trim().ifEmpty { "in:inbox" }
            val body = o.optString("body").trim()
            if (body.isBlank()) EmailIntent.Unknown("reply without a body")
            else EmailIntent.Reply(query = query, body = body)
        } catch (t: Throwable) {
            Log.w(TAG, "could not parse reply \"$raw\": ${t.message}")
            EmailIntent.Unknown("malformed reply output")
        }
    }

    /** Extract a Send intent (recipient/subject/body) from a compose request. */
    suspend fun parseSend(context: Context, request: String): EmailIntent {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val raw = LlmBridge.generateWithGrammar(
            systemPrompt = SEND_SYSTEM_PROMPT, userPrompt = request, grammar = SEND_GRAMMAR,
            temperature = 0.2f, maxTokens = 220, decodeThreads = decodeThreads,
        )
        Log.i(TAG, "parseSend(\"$request\") -> $raw  ·  ${LlmBridge.lastStats}")
        return try {
            val o = JSONObject(raw.trim())
            val to = normalizeEmail(o.optString("to").trim())
            val subject = o.optString("subject").trim()
            val body = o.optString("body").trim()
            if (body.isBlank()) EmailIntent.Unknown("send without a body")
            else EmailIntent.Send(to = to, subject = subject, body = body)
        } catch (t: Throwable) {
            Log.w(TAG, "could not parse send \"$raw\": ${t.message}")
            EmailIntent.Unknown("malformed send output")
        }
    }

    /** Fold spoken email forms ("a b c at gmail dot com") into a normal address when it looks like one. */
    private fun normalizeEmail(raw: String): String {
        val s = raw.trim()
        if (!s.contains("@") && !s.contains(" at ", ignoreCase = true)) return s // a bare name — leave as-is
        return s.lowercase()
            .replace(Regex("\\s+at\\s+"), "@")
            .replace(Regex("\\s+dot\\s+"), ".")
            .replace(Regex("\\s+"), "")
    }

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
