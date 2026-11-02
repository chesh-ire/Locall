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
            "reply. 2-4 sentences, plain conversational English, no markdown, no preamble."

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
            LlmBridge.generateWithGrammar(
                systemPrompt = SYSTEM_PROMPT, userPrompt = block, grammar = "",
                temperature = 0.5f, maxTokens = 200, decodeThreads = decodeThreads,
            ).trim().ifBlank { fallback(messages) }
        } catch (t: Throwable) {
            Log.w(TAG, "summarize failed, using plain fallback", t)
            fallback(messages)
        }
    }

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
