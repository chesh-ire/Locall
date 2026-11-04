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

    /** Nudge the volume one step. `up=false` lowers. The pipeline decides which audio
     *  stream to target based on whether media was playing when the command began. */
    data class Volume(val up: Boolean) : CommandAction

    data class Email(val transcript: String) : CommandAction

    /** Set a device alarm at [hour] (0–23) / [minute] (0–59) via the system clock app. */
    data class SetAlarm(val hour: Int, val minute: Int) : CommandAction

    /** Start a countdown timer for [seconds] via the system clock app. */
    data class SetTimer(val seconds: Int) : CommandAction

    /** No public API deletes a specific scheduled alarm, so this opens the alarm list. */
    data object CancelAlarm : CommandAction

    data object Screenshot : CommandAction

    data object LockScreen : CommandAction

    data class Unrecognized(val transcript: String, val reason: String = "") : CommandAction
}
