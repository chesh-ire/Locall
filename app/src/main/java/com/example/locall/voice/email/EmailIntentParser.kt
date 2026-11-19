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
        {"op":"read_latest","query":""}            read ONE email aloud — only for a SINGLE newest message
        {"op":"summarize","query":"in:inbox"}      overview of MULTIPLE emails (reviewing/catching up)
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
        "review my last 20 emails and tell me what I missed" -> {"op":"summarize","query":"in:inbox"}
        "catch me up on my inbox" -> {"op":"summarize","query":"in:inbox"}
        "what did I miss in my emails today" -> {"op":"summarize","query":"newer_than:1d in:inbox"}
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
        The user wants to compose and send a NEW email. Extract ONE JSON object. Fields:
          "to"      the recipient's email address (or their name if given), or "" if none was stated,
          "subject" the subject the user stated; only if they gave none, infer a 2-4 word one,
          "body"    the user's INTENDED MESSAGE — the gist of what they want to say. This may be words
                    they dictated OR a short DESCRIPTION of what the email should say (e.g. "ask them to
                    respond immediately"). Capture it faithfully and completely; a writing assistant will
                    turn it into a polished email afterwards, so you do NOT need to phrase it as a letter.
        Do not add recipients, commitments, or facts the user didn't state. Spoken email addresses may say
        "at" for @ and "dot" for "." — write them as a normal address. Drop filler markers like "subject",
        "subject line", "body", "message", "saying", "write an email", "send an email", "compose".
        Output ONLY the JSON.

        Examples:
        "send an email to abc@gmail.com subject line be ready body please be on time"
          -> {"to":"abc@gmail.com","subject":"be ready","body":"please be on time"}
        "write an email to sam@x.com subject hi asking them to respond immediately"
          -> {"to":"sam@x.com","subject":"hi","body":"ask them to respond immediately"}
        "email john dot smith at gmail dot com and tell them I'll be there at 6pm"
          -> {"to":"john.smith@gmail.com","subject":"See you at 6pm","body":"I'll be there at 6 pm"}
        "compose an email apologising for missing the meeting"
          -> {"to":"","subject":"Apologies for missing the meeting","body":"apologise for missing the meeting"}
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
        // Deterministic fast-path: "review / summarise / catch me up on my last N emails" and
        // "what did I miss" are unambiguously a MULTI-email summary. The LLM frequently mislabels these
        // as read_latest (which reads only the single newest email, then stops) — pin them before the
        // model ever runs. Singular "read my latest email" is intentionally NOT caught here.
        deterministicSummarize(request)?.let {
            Log.i(TAG, "parse(\"$request\") -> deterministic $it")
            return it
        }
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val raw = LlmBridge.generateWithGrammar(
            systemPrompt = SYSTEM_PROMPT, userPrompt = request, grammar = EMAIL_GRAMMAR,
            temperature = 0.2f, maxTokens = 80, decodeThreads = decodeThreads,
        )
        Log.i(TAG, "parse(\"$request\") -> $raw  ·  ${LlmBridge.lastStats}")
        return parseJson(raw)
    }

    private val REVIEW_VERB = Regex(
        "\\b(summari[sz]e|review|recap|catch me up|catch up|go over|run through|brief me|" +
            "what did i miss|what i missed|what have i missed|what'?s new)\\b",
    )
    // Requires a PLURAL mail signal so singular "read/summarise the last email" is left to the LLM.
    private val MAIL_PLURAL = Regex("\\b(emails|e-?mails|inbox|messages|mails)\\b")

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
    )

    /** "review my last 20 emails / what did I miss" → Summarize over the N most recent inbox mails. */
    private fun deterministicSummarize(request: String): EmailIntent.Summarize? {
        val r = request.lowercase()
        if (!REVIEW_VERB.containsMatchIn(r) || !MAIL_PLURAL.containsMatchIn(r)) return null
        val count = extractCount(r)?.coerceIn(1, 50) ?: 20
        return EmailIntent.Summarize(query = "in:inbox", count = count)
    }

    /** Pull a spoken/typed count ("last five", "20") so we fetch exactly what the user asked for. */
    private fun extractCount(r: String): Int? {
        Regex("\\b(\\d{1,3})\\b").find(r)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { if (it in 1..999) return it }
        for ((word, n) in NUMBER_WORDS) if (Regex("\\b$word\\b").containsMatchIn(r)) return n
        return null
    }

    fun parseJson(raw: String): EmailIntent = try {
        val o = JSONObject(raw.trim())
        val query = o.optString("query").trim()
        when (o.optString("op")) {
            "read_latest" -> EmailIntent.ReadLatest(1)
            // No explicit query → "review my last N emails": the N most recent inbox messages,
            // NOT restricted to the last day (newer_than:1d would return only today's mail, which is
            // why "review my last 20 emails" could stop after a single email).
            "summarize" -> EmailIntent.Summarize(query.ifEmpty { "in:inbox" })
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
