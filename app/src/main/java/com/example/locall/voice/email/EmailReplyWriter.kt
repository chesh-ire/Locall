package com.example.locall.voice.email

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor

private const val TAG = "EmailReplyWriter"

/**
 * The on-device LLM acting as a WRITING ASSISTANT for email replies: it turns the user's intended
 * message (the gist they dictated) into a natural, well-phrased reply, and applies follow-up
 * refinements ("make it shorter", "sound more professional"). It never decides WHAT to commit to —
 * it only improves HOW the user's own meaning is expressed, and never sends anything itself.
 */
object EmailReplyWriter {

    /** Everything the writer needs about the email being replied to. Fetched once, reused across refinements. */
    data class ReplyBrief(
        val recipientName: String,
        val subject: String,
        val originalBody: String,
        /** Recent earlier thread messages, oldest→newest, already formatted; "" when none/unavailable. */
        val threadBlock: String,
    )

    private val DRAFT_SYSTEM_PROMPT = """
        You are a writing assistant helping the user reply to an email. You are given the original email
        (and recent thread), who the reply is addressed to, and the USER'S INTENDED MESSAGE — the gist of
        what they want to say. Rewrite their intended message as a natural, well-phrased email reply.
        RULES:
        - Preserve the user's meaning EXACTLY. Never add commitments, dates, times, numbers, facts, or
          promises the user did not make. If unsure, leave it out.
        - Never invent details about the sender, project, or situation.
        - Keep it concise and match the tone of the original email (professional but warm).
        - No subject line, no "Re:", no headers. A short greeting and sign-off are fine if natural.
        - Output ONLY the reply body text, nothing else.
    """.trimIndent()

    private val REFINE_SYSTEM_PROMPT = """
        You are a writing assistant refining an email reply draft. You are given the current draft and an
        instruction for how to change it. Apply the instruction and return the revised reply.
        RULES:
        - Keep the meaning and any facts already in the draft. Do NOT add new commitments, dates, or facts
          UNLESS the instruction explicitly tells you to include specific information.
        - Change only what the instruction asks (tone, length, add/remove a point).
        - No subject line or headers. Output ONLY the revised reply body, nothing else.
    """.trimIndent()

    /** First draft: express [intent] as a reply, grounded in [brief]. */
    suspend fun draft(context: Context, brief: ReplyBrief, intent: String): String {
        val prompt = buildString {
            append("Original email from ${brief.recipientName}:\n")
            append("Subject: ${brief.subject.ifBlank { "(no subject)" }}\n")
            append(brief.originalBody.take(1500))
            if (brief.threadBlock.isNotBlank()) {
                append("\n\nEarlier in the thread:\n").append(brief.threadBlock.take(1500))
            }
            append("\n\nThe user wants to say: \"").append(intent.trim()).append("\"\n\n")
            append("Write the reply.")
        }
        return generate(context, DRAFT_SYSTEM_PROMPT, prompt, fallback = intent.trim())
    }

    /** Apply a follow-up [instruction] to [currentDraft]. */
    suspend fun refine(context: Context, brief: ReplyBrief, currentDraft: String, instruction: String): String {
        val prompt = buildString {
            append("Current draft:\n").append(currentDraft).append("\n\n")
            append("This is a reply to ${brief.recipientName}")
            if (brief.subject.isNotBlank()) append(" about \"${brief.subject}\"")
            append(".\n\n")
            append("Instruction: ").append(instruction.trim()).append("\n\n")
            append("Write the revised reply.")
        }
        return generate(context, REFINE_SYSTEM_PROMPT, prompt, fallback = currentDraft)
    }

    private suspend fun generate(context: Context, system: String, user: String, fallback: String): String {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        return try {
            val out = LlmBridge.generateText(
                systemPrompt = system, userPrompt = user,
                temperature = 0.5f, maxTokens = 220, decodeThreads = decodeThreads,
            ).let(::stripWrapping).trim()
            Log.i(TAG, "generate -> \"$out\"  ·  ${LlmBridge.lastStats}")
            out.ifBlank { fallback }
        } catch (t: Throwable) {
            Log.w(TAG, "generate failed, using fallback", t)
            fallback
        }
    }

    /** Small models sometimes wrap the reply in quotes or a "Reply:"/"Here's" preamble — peel those off. */
    private fun stripWrapping(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("Reply:").removePrefix("Here's the reply:").removePrefix("Here is the reply:").trim()
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1).trim()
        return s
    }
}
