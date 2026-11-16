package com.example.locall.voice.email

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor

private const val TAG = "EmailSummarizer"

object EmailSummarizer {

    private const val SYSTEM_PROMPT =
        "You are a concise assistant summarising someone's emails for them to hear aloud. " +
            "Given a numbered list of emails (sender, subject, snippet), produce a short spoken " +
            "summary: how many there are, who they're from, and which look important or need a " +
            "reply. 2-4 sentences, plain conversational English, no markdown. " +
            "Reply with ONLY the summary itself — start immediately with the content. Do NOT write any " +
            "preamble, heading, label, or line like \"Here's a spoken summary:\". Do NOT use blank " +
            "lines or bullet lists; write one short paragraph."

    private const val ONE_SYSTEM_PROMPT =
        "You summarise a SINGLE email for the user to hear aloud. Given the sender, subject and body, " +
            "say in 1-3 sentences what it's about and any action or reply needed. " +
            "Plain conversational English, no markdown. Reply with ONLY the summary itself — start " +
            "immediately with the content, no preamble, heading, label, blank lines, or lists."

    suspend fun summarize(context: Context, messages: List<GmailApi.Message>): String {
        if (messages.isEmpty()) return "You have no emails matching that."
        val block = buildString {
            messages.take(20).forEachIndexed { i, m ->
                append("${i + 1}. From: ${cleanFrom(m.from)} | Subject: ${m.subject.ifBlank { "(no subject)" }}")
                if (m.snippet.isNotBlank()) append(" | ${m.snippet.take(160)}")
                append('\n')
            }
        }
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        return try {
            // Long-form path: nativeGenerate (generateText) STOPS at the first blank line, so when the
            // model emits a "Here's a spoken summary:" preamble + blank line the real summary is lost.
            // generateStructured does not truncate at a blank line; we then strip any leading preamble.
            val raw = LlmBridge.generateStructured(
                systemPrompt = SYSTEM_PROMPT, userPrompt = block,
                temperature = 0.5f, maxTokens = 200, decodeThreads = decodeThreads,
                tag = "email-summary.digest",
            )
            val cleaned = stripPreamble(raw)
            // If the model still gave us only a preamble/stub, speak a real sender+count summary.
            if (cleaned.length < 15) fallback(messages) else cleaned
        } catch (t: Throwable) {
            Log.w(TAG, "summarize failed, using plain fallback", t)
            fallback(messages)
        }
    }

    /** Drop a leading "Here's a spoken summary:"-style preamble line the small model sometimes adds. */
    private fun stripPreamble(raw: String): String {
        var t = raw.trim()
        val nl = t.indexOf('\n')
        if (nl in 1..80) {
            val head = t.substring(0, nl).trim()
            // A short first line ending in ':' with no sentence content is a label/preamble, not summary.
            if (head.endsWith(":") && head.length <= 60 && !head.contains('.')) {
                t = t.substring(nl + 1).trim()
            }
        }
        return t.trim()
    }

    /** Summarise one email's actual content (used for "summarise the last email"). */
    suspend fun summarizeOne(context: Context, message: GmailApi.Message, body: String): String {
        val content = body.ifBlank { message.snippet }
        val from = cleanFrom(message.from)
        val subject = message.subject.ifBlank { "(no subject)" }
        if (content.isBlank()) return "Your latest email is from $from, subject $subject, but it has no readable text."
        val prompt = "From: $from\nSubject: $subject\n\n${content.take(2000)}"
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        return try {
            // Long-form path (not generateText) so a preamble + blank line can't truncate the summary;
            // then strip any leading preamble and fall back to plain text if only a stub came back.
            val raw = LlmBridge.generateStructured(
                systemPrompt = ONE_SYSTEM_PROMPT, userPrompt = prompt,
                temperature = 0.5f, maxTokens = 200, decodeThreads = decodeThreads,
                tag = "email-summary.one",
            )
            val cleaned = stripPreamble(raw)
            if (cleaned.length < 10) oneFallback(from, subject, content) else cleaned
        } catch (t: Throwable) {
            Log.w(TAG, "summarizeOne failed, using plain fallback", t)
            oneFallback(from, subject, content)
        }
    }

    private const val EACH_SYSTEM_PROMPT =
        "You summarise someone's emails for them to hear aloud. You are given a NUMBERED list of emails " +
            "(sender, subject, snippet). For EACH email output ONE line, in the SAME order and numbering, " +
            "formatted exactly: \"<number>. <one short sentence (max 16 words) saying what it's about and " +
            "any action needed>\". Output ONLY those numbered lines — one per email, no blank lines, no " +
            "preamble, no extra commentary."

    /**
     * Summarise a BATCH of emails in a SINGLE LLM call, returning one short line per email (aligned by
     * index). Doing all N in one call — instead of N separate calls — removes the per-email thinking
     * pause between spoken emails, so the caller can read them back-to-back with no gaps.
     */
    suspend fun summarizeEach(context: Context, messages: List<GmailApi.Message>): List<String> {
        if (messages.isEmpty()) return emptyList()
        val block = buildString {
            messages.forEachIndexed { i, m ->
                append("${i + 1}. From: ${cleanFrom(m.from)} | Subject: ${m.subject.ifBlank { "(no subject)" }}")
                if (m.snippet.isNotBlank()) append(" | ${m.snippet.take(200)}")
                append('\n')
            }
        }
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val raw = try {
            LlmBridge.generateStructured(
                systemPrompt = EACH_SYSTEM_PROMPT, userPrompt = block,
                temperature = 0.4f, maxTokens = (messages.size * 30).coerceIn(80, 360),
                decodeThreads = decodeThreads, tag = "email-summary.each",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "summarizeEach failed, using snippet fallback", t); ""
        }
        // Parse "N. text" lines back to a per-index map; fall back to the email's own snippet for any
        // line the model dropped or garbled, so every email still gets spoken.
        val byIndex = HashMap<Int, String>()
        val lineRe = Regex("^\\s*(\\d{1,2})[.)]\\s*(.+)$")
        for (line in raw.lines()) {
            val mt = lineRe.find(line.trim()) ?: continue
            val idx = (mt.groupValues[1].toIntOrNull() ?: continue) - 1
            val text = mt.groupValues[2].trim()
            if (idx in messages.indices && text.isNotBlank()) byIndex[idx] = text
        }
        return messages.indices.map { i ->
            byIndex[i] ?: messages[i].snippet.take(140).trim()
                .ifBlank { "About ${messages[i].subject.ifBlank { "this email" }}." }
        }
    }

    private const val BRIEF_SYSTEM_PROMPT =
        "Summarise a SINGLE email in ONE short spoken sentence (max 20 words). Given the sender, subject " +
            "and body, say what it's about and any action needed. Plain conversational English, no " +
            "markdown, no preamble, no lists. Output ONLY that one sentence."

    /**
     * One-sentence summary used when reading through a batch of emails aloud — kept short on purpose so
     * each email takes ~10s to speak (a multi-sentence summary made "review my last N" take minutes).
     * Smaller prompt (body capped at 1200) + low maxTokens also cuts LLM decode time.
     */
    suspend fun summarizeBrief(context: Context, message: GmailApi.Message, body: String): String {
        val content = body.ifBlank { message.snippet }
        val from = cleanFrom(message.from)
        val subject = message.subject.ifBlank { "(no subject)" }
        if (content.isBlank()) return "No readable text."
        // Cap the body hard: a brief one-liner doesn't need the whole email, and a smaller prompt means
        // far cheaper prefill — the 800+ token prompts were what spiked prefill to 20s+ under SEVERE heat.
        val prompt = "From: $from\nSubject: $subject\n\n${content.take(600)}"
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        return try {
            val raw = LlmBridge.generateStructured(
                systemPrompt = BRIEF_SYSTEM_PROMPT, userPrompt = prompt,
                temperature = 0.4f, maxTokens = 56, decodeThreads = decodeThreads,
                tag = "email-summary.brief",
            )
            val cleaned = stripPreamble(raw).substringBefore('\n').trim()
            if (cleaned.length < 8) "About $subject." else cleaned
        } catch (t: Throwable) {
            Log.w(TAG, "summarizeBrief failed, using plain fallback", t)
            "About $subject."
        }
    }

    private fun oneFallback(from: String, subject: String, content: String): String =
        "Your latest email is from $from, subject $subject. ${content.take(220)}"

    private fun fallback(messages: List<GmailApi.Message>): String {
        val senders = messages.take(3).joinToString(", ") { cleanFrom(it.from) }
        return "You have ${messages.size} email${if (messages.size == 1) "" else "s"}" +
            if (senders.isNotBlank()) ", including from $senders." else "."
    }

    fun cleanFrom(from: String): String {
        val name = from.substringBefore("<").trim().trim('"')
        if (name.isNotBlank()) return name
        val addr = from.substringAfter("<", from).substringBefore(">").trim()
        return addr.substringBefore("@").ifBlank { from }
    }
}
