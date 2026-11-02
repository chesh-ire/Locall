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

    data class Email(val transcript: String) : CommandAction

    data class Unrecognized(val transcript: String, val reason: String = "") : CommandAction
}
