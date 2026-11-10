package com.example.locall.voice.agent.inbox

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.agent.data.InboxItemEntity
import com.example.locall.voice.agent.data.MessageClass
import com.example.locall.voice.research.pipeline.LabeledSections

private const val TAG = "MessageClassifier"

object MessageClassifier {

    data class Verdict(val classification: String, val summary: String)

    private val SYSTEM = """
        You triage one message for a busy user. Read the sender, subject and preview, then output exactly
        two labelled lines and nothing else:
        CLASS: one of needs_reply | action | informational | none
        SUMMARY: one short sentence (max 15 words) capturing what it's about.

        CLASS guide:
        - needs_reply: the sender asks a question or clearly expects a response from the user.
        - action: the user must DO something (pay, submit, schedule, review) but not necessarily reply.
        - informational: useful to know, no response or task needed (receipts, newsletters, FYIs).
        - none: promotional, automated, or irrelevant; safe to ignore.
        Never invent facts not in the message.
    """.trimIndent()

    suspend fun classify(context: Context, item: InboxItemEntity): Verdict {
        val user = buildString {
            append("Source: ").append(item.source).append('\n')
            append("From: ").append(item.sender.ifBlank { "unknown" }).append('\n')
            if (item.title.isNotBlank()) append("Subject: ").append(item.title).append('\n')
            append("Preview: ").append(item.preview.take(400).ifBlank { "(no preview)" })
        }
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        val raw = try {
            LlmBridge.generateText(
                systemPrompt = SYSTEM, userPrompt = user,
                temperature = 0.1f, maxTokens = 60, decodeThreads = decodeThreads,
            ).trim()
        } catch (t: Throwable) {
            Log.w(TAG, "classify failed", t)
            return Verdict(MessageClass.INFO, item.preview.take(80))
        }
        val cls = normalizeClass(LabeledSections.section(raw, "CLASS", "SUMMARY") ?: raw)
        val summary = LabeledSections.section(raw, "SUMMARY")
            ?.ifBlank { null }
            ?: item.preview.take(80)
        Log.i(TAG, "classify(${item.id}) -> $cls")
        return Verdict(cls, summary.take(160))
    }

    private fun normalizeClass(s: String): String {
        val t = s.lowercase()
        return when {
            t.contains("needs_reply") || t.contains("needs reply") || t.contains("reply") -> MessageClass.NEEDS_REPLY
            t.contains("action") -> MessageClass.ACTION
            t.contains("none") || t.contains("ignore") || t.contains("promo") -> MessageClass.NONE
            t.contains("info") -> MessageClass.INFO
            else -> MessageClass.INFO
        }
    }
}
