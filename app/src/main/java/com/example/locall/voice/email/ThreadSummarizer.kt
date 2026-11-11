package com.example.locall.voice.email

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ThreadSummarizer"

/**
 * Map-reduce thread summariser for the on-device Llama-3.2-1B (n_ctx = 2048). It consolidates a whole
 * email thread into a compact, spoken-friendly briefing plus structured decisions / action items /
 * deadlines, all extracted in the SAME merge call (no separate extraction pass).
 *
 * Everything except the actual summarisation is deterministic Kotlin — chunking, ordering (done by
 * [GmailApi.threadMessages]), and parsing the model's labelled output — so the LLM is only ever invoked
 * for genuine language work, sequentially, on the single shared handle in [LlmBridge].
 */
object ThreadSummarizer {

    // Tuned for n_ctx = 2048 (prompt + input + generation share that budget). ~4 chars ≈ 1 token.
    private const val CHUNK_CHAR_BUDGET = 4000      // content chars per map chunk (~1.1k tokens)
    private const val PER_MESSAGE_CHAR_CAP = 3500   // truncate a single huge message so it can't blow a chunk
    private const val MAP_MAX_TOKENS = 200
    private const val MERGE_MAX_TOKENS = 256
    private const val MAX_MERGE_INPUTS = 6          // more intermediates than this → pre-condense (hierarchical)

    /** Compact result; the three lists are the structured extracts. */
    data class ThreadSummary(
        val summary: String,
        val decisions: List<String> = emptyList(),
        val actions: List<String> = emptyList(),
        val deadlines: List<String> = emptyList(),
    )

    private val MAP_SYSTEM_PROMPT =
        "You summarise one part of an email thread so it can later be consolidated. In 2-3 sentences, " +
            "note who said what, any decisions reached, action items (who does what), and any dates or " +
            "deadlines. Plain English, no markdown, no preamble."

    private val MERGE_SYSTEM_PROMPT = """
        You consolidate an email thread into one short briefing for the user to hear aloud, and extract the
        key items. Base everything ONLY on the input; never invent people, decisions, dates or facts.
        Output EXACTLY these four labelled sections and nothing else:
        SUMMARY: <2-4 sentences: who is involved, what the thread is about, and where it currently stands>
        DECISIONS:
        - <each decision reached; write None if there are none>
        ACTIONS:
        - <each action item, who needs to do what; write None if there are none>
        DEADLINES:
        - <each date or deadline mentioned; write None if there are none>
    """.trimIndent()

    private val CONDENSE_SYSTEM_PROMPT =
        "Condense these partial summaries of one email thread into a single tight paragraph, preserving " +
            "who did what, decisions, action items and dates. Plain English, no preamble."

    /** Full summary of an entire thread (chronological [msgs]). */
    suspend fun summarizeFull(context: Context, msgs: List<GmailApi.ThreadFullMessage>): ThreadSummary {
        if (msgs.isEmpty()) return ThreadSummary("There are no readable messages in this thread.")
        val chunks = chunk(msgs)
        Log.i(TAG, "summarizeFull: ${msgs.size} msgs -> ${chunks.size} chunk(s)")
        // Single chunk: one structured call straight over the raw content (no separate map pass).
        val blocks = if (chunks.size == 1) chunks else chunks.map { mapChunk(context, it) }
        return synthesize(context, blocks, prior = null, fallbackMsgs = msgs)
    }

    /**
     * Incremental update: fold [newMsgs] into an existing [prior] summary instead of re-reading the whole
     * thread. Only the new content is sent to the model, alongside the prior summary as context.
     */
    suspend fun update(
        context: Context,
        prior: ThreadSummary,
        newMsgs: List<GmailApi.ThreadFullMessage>,
    ): ThreadSummary {
        if (newMsgs.isEmpty()) return prior
        val chunks = chunk(newMsgs)
        Log.i(TAG, "update: ${newMsgs.size} new msg(s) -> ${chunks.size} chunk(s)")
        val blocks = if (chunks.size == 1) chunks else chunks.map { mapChunk(context, it) }
        return synthesize(context, blocks, prior = prior, fallbackMsgs = newMsgs)
    }

    // ---------- deterministic chunking ----------

    private fun chunk(msgs: List<GmailApi.ThreadFullMessage>): List<String> {
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (m in msgs) {
            val piece = formatMessage(m)
            if (sb.isNotEmpty() && sb.length + piece.length + 2 > CHUNK_CHAR_BUDGET) {
                chunks.add(sb.toString()); sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(piece)
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        return chunks
    }

    private val DATE_FMT = SimpleDateFormat("MMM d", Locale.getDefault())

    private fun formatMessage(m: GmailApi.ThreadFullMessage): String {
        val who = EmailSummarizer.cleanFrom(m.from)
        val date = if (m.internalDateMs > 0) " (${DATE_FMT.format(Date(m.internalDateMs))})" else ""
        return "From $who$date: ${m.body.take(PER_MESSAGE_CHAR_CAP)}"
    }

    // ---------- map ----------

    private suspend fun mapChunk(context: Context, chunkText: String): String {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        return try {
            LlmBridge.generateText(
                systemPrompt = MAP_SYSTEM_PROMPT, userPrompt = chunkText,
                temperature = 0.4f, maxTokens = MAP_MAX_TOKENS, decodeThreads = decodeThreads,
                tag = "email-thread.map",
            ).trim().ifBlank { chunkText.take(400) }
        } catch (t: Throwable) {
            Log.w(TAG, "mapChunk failed, using raw excerpt", t)
            chunkText.take(400)
        }
    }

    // ---------- reduce (merge + extract, folded into one call) ----------

    private suspend fun synthesize(
        context: Context,
        blocks: List<String>,
        prior: ThreadSummary?,
        fallbackMsgs: List<GmailApi.ThreadFullMessage>,
    ): ThreadSummary {
        // Bound the merge input: if there are too many intermediates, pre-condense them in groups first.
        var merged = blocks
        while (merged.size > MAX_MERGE_INPUTS) {
            Log.i(TAG, "synthesize: ${merged.size} blocks > $MAX_MERGE_INPUTS — condensing in groups")
            merged = merged.chunked(MAX_MERGE_INPUTS).map { condense(context, it) }
        }

        val userPrompt = buildString {
            if (prior != null) {
                append("Summary so far of the earlier part of this thread:\n")
                append(prior.summary).append('\n')
                appendList("Decisions so far", prior.decisions)
                appendList("Action items so far", prior.actions)
                appendList("Deadlines so far", prior.deadlines)
                append("\nNew messages since then:\n")
            } else {
                append("Thread content:\n")
            }
            merged.forEachIndexed { i, b -> append(if (merged.size > 1) "[$i] " else "").append(b).append("\n\n") }
            append(
                if (prior != null) {
                    "Produce an UPDATED briefing covering the whole thread (earlier summary plus the new messages)."
                } else {
                    "Produce the briefing."
                },
            )
        }

        val decodeThreads = ThermalGovernor.decodeThreads(context)
        return try {
            val raw = LlmBridge.generateText(
                systemPrompt = MERGE_SYSTEM_PROMPT, userPrompt = userPrompt,
                temperature = 0.4f, maxTokens = MERGE_MAX_TOKENS, decodeThreads = decodeThreads,
                tag = "email-thread.synth",
            )
            Log.i(TAG, "synthesize -> ${LlmBridge.lastStats}")
            parseStructured(raw) ?: ThreadSummary(raw.trim().ifBlank { fallback(fallbackMsgs) })
        } catch (t: Throwable) {
            Log.w(TAG, "synthesize failed, using plain fallback", t)
            // Keep any prior extracts so an update failure doesn't lose previously-known items.
            ThreadSummary(
                summary = fallback(fallbackMsgs),
                decisions = prior?.decisions ?: emptyList(),
                actions = prior?.actions ?: emptyList(),
                deadlines = prior?.deadlines ?: emptyList(),
            )
        }
    }

    private suspend fun condense(context: Context, blocks: List<String>): String {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val joined = blocks.joinToString("\n\n")
        return try {
            LlmBridge.generateText(
                systemPrompt = CONDENSE_SYSTEM_PROMPT, userPrompt = joined,
                temperature = 0.4f, maxTokens = MAP_MAX_TOKENS, decodeThreads = decodeThreads,
                tag = "email-thread.condense",
            ).trim().ifBlank { joined.take(600) }
        } catch (t: Throwable) {
            Log.w(TAG, "condense failed, using raw", t)
            joined.take(600)
        }
    }

    // ---------- deterministic parsing of the labelled output ----------

    /** Parse the SUMMARY/DECISIONS/ACTIONS/DEADLINES block; null if the model ignored the format. */
    private fun parseStructured(raw: String): ThreadSummary? {
        val text = raw.trim()
        val summary = section(text, "SUMMARY", "DECISIONS", "ACTIONS", "DEADLINES") ?: return null
        val decisions = listSection(text, "DECISIONS", "ACTIONS", "DEADLINES")
        val actions = listSection(text, "ACTIONS", "DEADLINES")
        val deadlines = listSection(text, "DEADLINES")
        if (summary.isBlank() && decisions.isEmpty() && actions.isEmpty() && deadlines.isEmpty()) return null
        return ThreadSummary(summary.trim(), decisions, actions, deadlines)
    }

    /** Text after `label:` up to the next of [stops] (case-insensitive labels at a line start-ish). */
    private fun section(text: String, label: String, vararg stops: String): String? {
        val start = labelIndex(text, label) ?: return null
        val from = start + label.length
        val after = text.indexOf(':', from).let { if (it in from..(from + 2)) it + 1 else from }
        var end = text.length
        for (s in stops) labelIndex(text, s)?.let { if (it in after until end) end = it }
        return text.substring(after, end).trim()
    }

    private fun listSection(text: String, label: String, vararg stops: String): List<String> {
        val body = section(text, label, *stops) ?: return emptyList()
        return body.split('\n')
            .map { it.trim().removePrefix("-").removePrefix("•").removePrefix("*").trim() }
            .filter { it.isNotBlank() && !it.equals("none", ignoreCase = true) && !it.equals("n/a", ignoreCase = true) }
    }

    private fun labelIndex(text: String, label: String): Int? {
        val i = text.indexOf(label, ignoreCase = true)
        return if (i < 0) null else i
    }

    private fun StringBuilder.appendList(title: String, items: List<String>) {
        if (items.isNotEmpty()) append(title).append(": ").append(items.joinToString("; ")).append('\n')
    }

    // ---------- fallback (no model) ----------

    private fun fallback(msgs: List<GmailApi.ThreadFullMessage>): String {
        if (msgs.isEmpty()) return "There are no readable messages in this thread."
        val people = msgs.map { EmailSummarizer.cleanFrom(it.from) }.distinct().take(3)
        val who = people.joinToString(", ")
        return "This thread has ${msgs.size} message${if (msgs.size == 1) "" else "s"}" +
            if (who.isNotBlank()) ", involving $who." else "."
    }
}
