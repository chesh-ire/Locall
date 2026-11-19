package com.example.locall.voice.email

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor

private const val TAG = "EmailComposeWriter"

/**
 * The on-device LLM acting as a WRITING ASSISTANT for NEW (compose) emails — the counterpart of
 * [EmailReplyWriter] for mail that isn't a reply. It turns the user's intended message (which may be a
 * terse description like "ask them to respond immediately") into a natural, well-phrased email body, and
 * applies follow-up refinements. Like the reply writer it never adds commitments the user didn't make and
 * never sends anything itself — the handler owns the approve-before-send gate.
 */
object EmailComposeWriter {

    /** The little the writer needs about a fresh email. No original message exists yet. */
    data class ComposeBrief(val subject: String)

    private val DRAFT_SYSTEM_PROMPT = """
        You are a writing assistant helping the user compose a NEW email. You are given the subject and the
        USER'S INTENDED MESSAGE — the gist of what they want to say. This may be words they dictated, OR a
        short description of what the email should do (e.g. "ask them to respond immediately"). Write a
        natural, well-phrased email body that conveys that message.
        RULES:
        - Preserve the user's meaning EXACTLY. Never add commitments, dates, times, numbers, facts, or
          promises the user did not make. If unsure, leave it out.
        - Never invent details about the recipient or situation beyond what the user said.
        - Keep it concise and professional but warm.
        - No subject line, no headers. A short greeting ("Hi,") and sign-off are fine if natural.
        - Output ONLY the email body text, nothing else.
    """.trimIndent()

    private val REFINE_SYSTEM_PROMPT = """
        You are a writing assistant refining a draft email. You are given the current draft and an
        instruction for how to change it. Apply the instruction and return the revised email.
        RULES:
        - Keep the meaning and any facts already in the draft. Do NOT add new commitments, dates, or facts
          UNLESS the instruction explicitly tells you to include specific information.
        - Change only what the instruction asks (tone, length, add/remove a point).
        - No subject line or headers. Output ONLY the revised email body, nothing else.
    """.trimIndent()

    /** First draft: express [intent] as an email body for [brief]. */
    suspend fun draft(context: Context, brief: ComposeBrief, intent: String): String {
        val prompt = buildString {
            append("Subject: ${brief.subject.ifBlank { "(no subject)" }}\n\n")
            append("The user wants to say: \"").append(intent.trim()).append("\"\n\n")
            append("Write the email body.")
        }
        return generate(context, DRAFT_SYSTEM_PROMPT, prompt, fallback = intent.trim())
    }

    /** Apply a follow-up [instruction] to [currentDraft]. */
    suspend fun refine(context: Context, brief: ComposeBrief, currentDraft: String, instruction: String): String {
        val prompt = buildString {
            append("Current draft:\n").append(currentDraft).append("\n\n")
            if (brief.subject.isNotBlank()) append("This email's subject is \"${brief.subject}\".\n\n")
            append("Instruction: ").append(instruction.trim()).append("\n\n")
            append("Write the revised email body.")
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

    /** Small models sometimes wrap the body in quotes or a "Here's the email:" preamble — peel those off. */
    private fun stripWrapping(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("Email:").removePrefix("Here's the email:").removePrefix("Here is the email:").trim()
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1).trim()
        return s
    }
}
