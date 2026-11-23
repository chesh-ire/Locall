package com.example.locall.voice

sealed interface CommandAction {

    data class TypeText(val text: String, val append: Boolean = true) : CommandAction

    data class OpenApp(val query: String) : CommandAction

    enum class Nav { HOME, BACK, RECENTS, NOTIFICATIONS, SCROLL_DOWN, SCROLL_UP }
    data class Navigate(val nav: Nav) : CommandAction

    data object ClearField : CommandAction

    data object SendMessage : CommandAction

    data class ReadLastMessage(val contact: String, val app: String = "whatsapp") : CommandAction

    data class ComposeReply(val instruction: String, val contact: String? = null) : CommandAction

    data class Music(val transcript: String) : CommandAction

    data class Memory(val transcript: String) : CommandAction

    data class Torch(val on: Boolean) : CommandAction

    data class Volume(val up: Boolean) : CommandAction

    data class Email(val transcript: String) : CommandAction

    data class Battery(val transcript: String) : CommandAction

    data class Research(val transcript: String) : CommandAction

    /**
     * [directChat] = the capability router already determined this is a plain conversational
     * question (not a tool/command), so the agent should answer it directly and SKIP its own
     * second LLM tool-routing pass. Saves one serialized inference per chat turn and avoids
     * mis-routing a question into a heavier handler (e.g. the plan generator).
     */
    data class Agent(val transcript: String, val directChat: Boolean = false) : CommandAction

    data class SetAlarm(val hour: Int, val minute: Int) : CommandAction

    data class SetTimer(val seconds: Int) : CommandAction

    data object CancelAlarm : CommandAction

    data object Screenshot : CommandAction

    data object LockScreen : CommandAction

    data class Unrecognized(val transcript: String, val reason: String = "") : CommandAction
}
