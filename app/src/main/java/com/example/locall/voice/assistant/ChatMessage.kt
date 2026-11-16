package com.example.locall.voice.assistant

/** Who/what a bubble in the conversation represents. */
enum class ChatRole {
    USER,       // what the user said or typed
    ASSISTANT,  // a finished result (optionally tagged with the capability that produced it)
    STATUS,     // transient in-progress line ("Reading source 2 of 6…"); replaced by the result
    CONFIRM,    // a yes/no gate with Confirm / Cancel buttons (destructive / send actions)
    PROMPT,     // the assistant asking a follow-up and waiting for the next user turn (listen)
    ERROR,      // something went wrong
}

/**
 * One item in the conversation transcript rendered by the single assistant screen. Immutable; the
 * [AssistantViewModel] replaces list entries (e.g. flips a [STATUS] to the final [ASSISTANT] result,
 * or marks a [CONFIRM] resolved) by id.
 */
data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val capability: String? = null,
    /** CONFIRM: still awaiting a tap. PROMPT: still awaiting the next user turn. STATUS: still running. */
    val pending: Boolean = false,
    /** CONFIRM: the user's decision once resolved (for rendering ✓/✗). */
    val confirmed: Boolean? = null,
)
