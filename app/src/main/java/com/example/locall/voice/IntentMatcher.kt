package com.example.locall.voice

import com.example.locall.voice.email.EmailIntents
import com.example.locall.voice.memory.MemoryIntents
import com.example.locall.voice.music.MusicIntents

class IntentMatcher(
    private val llmFallback: (suspend (String, onStatus: (String) -> Unit) -> CommandAction)? = null,
) {

    suspend fun resolve(transcript: String, onStatus: (String) -> Unit = {}): CommandAction {
        var t = transcript.trim().lowercase()
        if (t.isEmpty()) return CommandAction.Unrecognized(transcript, "empty")

        WHATSAPP_PATTERN.find(t)?.let { m -> t = "whatsapp" + t.substring(m.value.length) }
        for ((wrong, right) in WHATSAPP_MISHEARINGS) {
            if (t.startsWith(wrong)) { t = right + t.removePrefix(wrong); break }
        }

        matchGrammar(t, transcript)?.let { return it }

        llmFallback?.invoke(transcript, onStatus)?.let { return it }

        val words = t.split(" ").filter { it.isNotBlank() }
        if (words.size in 1..3) return CommandAction.OpenApp(transcript.trim())

        return CommandAction.Unrecognized(transcript, "no grammar match")
    }

    fun matchGrammar(normalized: String, original: String): CommandAction? {
        if (MusicIntents.matches(normalized)) return CommandAction.Music(original.trim())
        if (MemoryIntents.matches(normalized)) return CommandAction.Memory(original.trim())
        if (EmailIntents.matches(normalized)) return CommandAction.Email(original.trim())

        torch(normalized)?.let { return it }

        WHATSAPP_READ_PREFIXES.firstOrNull { normalized.startsWith(it) }?.let { prefix ->
            val contact = normalized.drop(prefix.length).trim()
            if (contact.isNotEmpty()) return CommandAction.ReadLastMessage(contact)
        }

        REPLY_PREFIXES.firstOrNull { normalized.startsWith(it) }?.let { prefix ->
            val instruction = original.trim().drop(prefix.length).trim()
            if (instruction.isNotEmpty()) {
                return CommandAction.ComposeReply(instruction, contact = VoiceAccessibilityService.lastContact)
            }
        }

        DICTATE_PREFIXES.firstOrNull { normalized.startsWith(it) }?.let { prefix ->
            val payload = original.trim().drop(prefix.length).trim()
            if (payload.isNotEmpty()) return CommandAction.TypeText(payload)
        }

        OPEN_PREFIXES.firstOrNull { normalized.startsWith(it) }?.let { prefix ->
            val app = normalized.drop(prefix.length).trim()
            if (app.isNotEmpty()) return CommandAction.OpenApp(app)
        }

        when {
            normalized in setOf("go back", "back", "navigate back") ->
                return CommandAction.Navigate(CommandAction.Nav.BACK)
            normalized in setOf("go home", "home screen", "home") ->
                return CommandAction.Navigate(CommandAction.Nav.HOME)
            normalized in setOf("recents", "recent apps", "show recents") ->
                return CommandAction.Navigate(CommandAction.Nav.RECENTS)
            normalized in setOf("notifications", "show notifications", "open notifications") ->
                return CommandAction.Navigate(CommandAction.Nav.NOTIFICATIONS)
            normalized in setOf("scroll down", "page down") ->
                return CommandAction.Navigate(CommandAction.Nav.SCROLL_DOWN)
            normalized in setOf("scroll up", "page up") ->
                return CommandAction.Navigate(CommandAction.Nav.SCROLL_UP)
            normalized in setOf("clear", "clear text", "clear field", "delete all") ->
                return CommandAction.ClearField
            normalized in setOf("send", "send it", "send that", "send message", "send the message") ->
                return CommandAction.SendMessage
        }

        return null
    }

    fun torch(normalized: String): CommandAction.Torch? = when (normalized) {
        in TORCH_ON -> CommandAction.Torch(on = true)
        in TORCH_OFF -> CommandAction.Torch(on = false)
        else -> null
    }

    private companion object {
        val TORCH_ON = setOf(
            "turn on flashlight", "turn on the flashlight", "turn the flashlight on",
            "turn on torch", "turn on the torch", "turn the torch on",
            "turn on flash", "turn on the flash",
            "turn on light", "turn on the light", "turn the light on",
            "flashlight on", "torch on", "light on",
            "flashlight", "torch",
        )
        val TORCH_OFF = setOf(
            "turn off flashlight", "turn off the flashlight", "turn the flashlight off",
            "turn off torch", "turn off the torch", "turn the torch off",
            "turn off flash", "turn off the flash",
            "turn off light", "turn off the light", "turn the light off",
            "flashlight off", "torch off", "light off",
        )
        val REPLY_PREFIXES = listOf(
            "reply that ", "reply saying ", "reply with ", "tell them ", "respond that ",
        )
        val DICTATE_PREFIXES = listOf("type ", "write ", "dictate ", "insert ", "append ")
        val OPEN_PREFIXES = listOf("open ", "launch ", "start ", "go to ")
        val WHATSAPP_PATTERN = Regex("^wh?a+t+'?s?[\\s-]*(u+p+|ap+)\\b")
        val WHATSAPP_MISHEARINGS = listOf(
            "wassup" to "whatsapp", "wazap" to "whatsapp",
        )
        val WHATSAPP_READ_PREFIXES = listOf(
            "open whatsapp chat with ", "open whatsapp and read last message from ",
            "read last message from ", "read the last message from ",
            "check whatsapp messages from ", "check whatsapp message from ",
            "read whatsapp message from ", "whatsapp ",
        )
    }
}
