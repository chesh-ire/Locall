package com.example.locall.voice

import android.content.Context
import android.util.Log
import com.example.locall.voice.email.EmailCommandHandler
import com.example.locall.voice.memory.MemoryCommandHandler
import com.example.locall.voice.memory.MemoryRepository
import com.example.locall.voice.music.MusicCommandHandler
import com.example.locall.voice.music.MusicIntent
import com.example.locall.voice.music.MusicIntents
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "CommandPipeline"

object CommandPipeline {

    private val matcher = IntentMatcher(llmFallback = ::llmFallback)

    @Volatile private var musicHandler: MusicCommandHandler? = null
    @Volatile private var memoryHandler: MemoryCommandHandler? = null
    @Volatile private var emailHandler: EmailCommandHandler? = null

    private fun music(context: Context): MusicCommandHandler =
        musicHandler ?: MusicCommandHandler(context.applicationContext).also { musicHandler = it }

    private fun memory(context: Context): MemoryCommandHandler =
        memoryHandler ?: MemoryCommandHandler(context.applicationContext).also { memoryHandler = it }

    private fun email(context: Context): EmailCommandHandler =
        emailHandler ?: EmailCommandHandler(context.applicationContext).also { emailHandler = it }

    @Volatile private var appContextForLlm: Context? = null

    data class Result(val transcript: String, val action: String, val keepMusicPaused: Boolean = false)

    suspend fun runText(
        text: String,
        appContext: Context,
        mediaWasPlaying: Boolean = false,
        onStatus: (String) -> Unit = {},
        confirm: suspend (String) -> Boolean = { false },
    ): Result {
        Log.i(TAG, "runText: recognized=\"$text\"")
        appContextForLlm = appContext.applicationContext
        val t = text.trim()
        if (t.isEmpty()) {
            Log.w(TAG, "runText: empty after trim, nothing to do")
            return Result("", "no speech recognized")
        }

        val action = withContext(InferenceDispatcher.dispatcher) { matcher.resolve(t, onStatus) }
        Log.i(TAG, "runText: resolved to $action")
        val resultText = dispatch(action, appContext, mediaWasPlaying, onStatus, confirm)
        Log.i(TAG, "runText: dispatch result=\"$resultText\"")
        val keepPaused = action is CommandAction.Music &&
            MusicIntents.transport(action.transcript.lowercase()) == MusicIntent.Pause
        return Result(t, resultText, keepPaused)
    }

    private suspend fun dispatch(
        action: CommandAction,
        appContext: Context,
        mediaWasPlaying: Boolean,
        onStatus: (String) -> Unit,
        confirm: suspend (String) -> Boolean,
    ): String {
        if (action is CommandAction.Volume) {
            return handleVolume(appContext, action.up, mediaWasPlaying, confirm)
        }
        if (action is CommandAction.Music) {
            val playViaSearch: suspend (String) -> Boolean = { q ->
                VoiceAccessibilityService.instance?.playViaSpotifySearch(q) ?: false
            }
            return music(appContext).handle(appContext, action.transcript, onStatus, playViaSearch)
        }
        if (action is CommandAction.Memory) {
            val speak: (String) -> Unit = { text -> VoiceAccessibilityService.instance?.speak(text) }
            return memory(appContext).handle(appContext, action.transcript, System.currentTimeMillis(), onStatus, speak)
        }
        if (action is CommandAction.Torch) {
            val ok = TorchController.set(appContext, action.on)
            return if (ok) { if (action.on) "🔦 flashlight on" else "🔦 flashlight off" }
                else "⚠️ no flashlight available"
        }
        if (action is CommandAction.SetAlarm) {
            val ok = ClockController.setAlarm(appContext, action.hour, action.minute)
            val time = "%02d:%02d".format(action.hour, action.minute)
            return if (ok) "⏰ alarm set for $time" else "⚠️ no clock app to set an alarm"
        }
        if (action is CommandAction.SetTimer) {
            val ok = ClockController.setTimer(appContext, action.seconds)
            return if (ok) "⏱️ timer set for ${formatDuration(action.seconds)}"
                else "⚠️ no clock app to set a timer"
        }
        if (action is CommandAction.CancelAlarm) {
            val ok = ClockController.showAlarms(appContext)
            return if (ok) "⏰ opened your alarms — tap one to turn it off"
                else "⚠️ no clock app to open"
        }
        if (action is CommandAction.Email) {
            val speak: (String) -> Unit = { text -> VoiceAccessibilityService.instance?.speak(text) }
            return email(appContext).handle(appContext, action.transcript, onStatus, confirm, speak)
        }

        val svc = VoiceAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "dispatch: VoiceAccessibilityService.instance is null — " +
                    "Accessibility is not enabled (or was reset) for LocaLL")
            return "⚠️ Enable Accessibility (Settings) to run commands"
        }

        if (action is CommandAction.ReadLastMessage) {
            Log.i(TAG, "dispatch: readLastMessage(contact=${action.contact}, app=${action.app})")
            val message = svc.readLastMessage(action.contact, appPackageFor(action.app))
            Log.i(TAG, "dispatch: readLastMessage returned: ${message?.let { "\"$it\"" } ?: "null"}")
            return if (message != null) {
                Log.i(TAG, "dispatch: calling svc.speak()...")
                svc.speak(message)
                Log.i(TAG, "dispatch: svc.speak() returned")
                "💬 ${action.contact}: \"$message\""
            } else {
                "couldn't find a chat/message for \"${action.contact}\""
            }
        }

        if (action is CommandAction.ComposeReply) {
            Log.i(TAG, "dispatch: composeReply(instruction=${action.instruction}, contact=${action.contact})")
            val decodeThreads = ThermalGovernor.decodeThreads(svc.applicationContext)
            Log.i(TAG, "dispatch: thermal status=${ThermalGovernor.statusName(ThermalGovernor.status(svc.applicationContext))} decode_threads=$decodeThreads")
            val memoryContext = runCatching {
                MemoryRepository.get(svc.applicationContext).relevantPromptBlock(action.instruction)
            }.getOrDefault("")
            val draft = try {
                LlmBridge.generateReply(action.instruction, decodeThreads, memoryContext)
            } catch (t: Throwable) {
                Log.w(TAG, "dispatch: generateReply failed", t)
                return "⚠️ couldn't draft a reply: ${t.javaClass.simpleName}"
            }
            if (draft.isEmpty()) return "⚠️ AI model produced an empty reply"
            val stats = LlmBridge.lastStats
            if (!confirm("type drafted reply: \"$draft\"")) return "❌ cancelled: \"$draft\""
            val typed = svc.execute(CommandAction.TypeText(draft, append = false))
            if (!typed) return "no text field focused"
            svc.speak(draft)
            return "✍️ drafted: \"$draft\"  ·  $stats"
        }

        val needsConfirm = action is CommandAction.TypeText ||
            action is CommandAction.ClearField ||
            action is CommandAction.SendMessage
        if (needsConfirm && !confirm(describe(action))) return "❌ cancelled"

        val ok = svc.execute(action)
        return when (action) {
            is CommandAction.TypeText -> if (ok) "⌨️ typed: \"${action.text}\"" else "no text field focused"
            is CommandAction.OpenApp -> if (ok) "📱 opened: ${action.query}" else "app not found: ${action.query}"
            is CommandAction.Navigate -> if (ok) "✅ ${action.nav}" else "couldn't ${action.nav}"
            is CommandAction.ClearField -> if (ok) "🧹 cleared" else "no field to clear"
            is CommandAction.SendMessage -> if (ok) "📤 sent" else "couldn't find a send button"
            is CommandAction.Screenshot -> if (ok) "📸 screenshot taken" else "⚠️ couldn't take a screenshot"
            is CommandAction.LockScreen -> if (ok) "🔒 locked" else "⚠️ couldn't lock (needs Android 9+)"
            is CommandAction.ReadLastMessage -> error("handled above")
            is CommandAction.ComposeReply -> error("handled above")
            is CommandAction.Music -> error("handled above")
            is CommandAction.Memory -> error("handled above")
            is CommandAction.Torch -> error("handled above")
            is CommandAction.Email -> error("handled above")
            is CommandAction.Volume -> error("handled above")
            is CommandAction.SetAlarm -> error("handled above")
            is CommandAction.SetTimer -> error("handled above")
            is CommandAction.CancelAlarm -> error("handled above")
            is CommandAction.Unrecognized -> "🤷 didn't understand: \"${action.transcript}\""
        }
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return buildList {
            if (h > 0) add("${h}h")
            if (m > 0) add("${m}m")
            if (s > 0) add("${s}s")
        }.joinToString(" ").ifEmpty { "0s" }
    }

    /**
     * Volume is deliberately handled here, not in the Accessibility service: it needs no
     * node traversal. When media was playing at command time we nudge the media stream
     * (and the paused track resumes afterward at the new level). When nothing was playing
     * we don't guess which volume the user meant — we ask before touching the ringer.
     */
    private suspend fun handleVolume(
        appContext: Context,
        up: Boolean,
        mediaWasPlaying: Boolean,
        confirm: suspend (String) -> Boolean,
    ): String {
        val dir = if (up) "up" else "down"
        if (mediaWasPlaying) {
            val ok = VolumeController.adjustMedia(appContext, up)
            return if (ok) "🔊 media volume $dir" else "⚠️ couldn't change media volume"
        }
        if (!confirm("turn the ringer volume $dir")) return "❌ cancelled — nothing is playing"
        val ok = VolumeController.adjustRing(appContext, up)
        return if (ok) "🔊 ringer volume $dir"
            else "⚠️ couldn't change ringer volume (needs Do-Not-Disturb access near silent)"
    }

    private fun describe(action: CommandAction): String = when (action) {
        is CommandAction.TypeText -> "type: \"${action.text}\""
        is CommandAction.ClearField -> "clear the focused text field"
        is CommandAction.SendMessage -> "send this message"
        else -> error("describe() called on an action that doesn't need confirmation: $action")
    }

    private fun appPackageFor(app: String): String = when (app.lowercase()) {
        "whatsapp" -> "com.whatsapp"
        else -> app
    }


    private suspend fun llmFallback(transcript: String, onStatus: (String) -> Unit): CommandAction {
        val context = VoiceAccessibilityService.instance?.applicationContext ?: appContextForLlm ?: run {
            Log.w(TAG, "llmFallback: no context available, skipping LLM")
            return CommandAction.Unrecognized(transcript, "no context for LLM")
        }
        return try {
            LlmBridge.ensureLoaded(context, onStatus)
            val decodeThreads = ThermalGovernor.decodeThreads(context)
            Log.i(TAG, "llmFallback: thermal status=${ThermalGovernor.statusName(ThermalGovernor.status(context))} decode_threads=$decodeThreads")
            val memoryContext = runCatching {
                MemoryRepository.get(context).relevantPromptBlock(transcript)
            }.getOrDefault("")
            val systemPrompt = if (memoryContext.isEmpty()) COMMAND_SYSTEM_PROMPT
                else "$memoryContext\n$COMMAND_SYSTEM_PROMPT"
            val json = LlmBridge.generateJson(systemPrompt, transcript, decodeThreads)
            Log.i(TAG, "llmFallback: raw output: $json")
            Log.i(TAG, "llmFallback: ${LlmBridge.lastStats}")
            parseCommandActionJson(json, transcript)
        } catch (t: Throwable) {
            Log.w(TAG, "llmFallback: failed", t)
            CommandAction.Unrecognized(transcript, "LLM error: ${t.javaClass.simpleName}")
        }
    }

    private fun parseCommandActionJson(raw: String, transcript: String): CommandAction = try {
        val obj = JSONObject(raw.trim())
        when (obj.getString("action")) {
            "type_text" -> CommandAction.TypeText(obj.getString("text"))
            "open_app" -> CommandAction.OpenApp(obj.getString("query"))
            "navigate" -> navFromString(obj.getString("nav"))
                ?.let { CommandAction.Navigate(it) }
                ?: CommandAction.Unrecognized(transcript, "bad nav value")
            "clear_field" -> CommandAction.ClearField
            "send_message" -> CommandAction.SendMessage
            "read_last_message" -> CommandAction.ReadLastMessage(
                obj.getString("contact"), obj.optString("app", "whatsapp"))
            "set_alarm" -> CommandAction.SetAlarm(
                obj.getInt("hour").coerceIn(0, 23), obj.optInt("minute", 0).coerceIn(0, 59))
            "set_timer" -> CommandAction.SetTimer(obj.getInt("seconds").coerceAtLeast(1))
            "cancel_alarm" -> CommandAction.CancelAlarm
            "screenshot" -> CommandAction.Screenshot
            "lock_screen" -> CommandAction.LockScreen
            "unrecognized" -> CommandAction.Unrecognized(transcript, obj.optString("reason", ""))
            else -> CommandAction.Unrecognized(transcript, "unknown action kind from LLM")
        }
    } catch (e: Exception) {
        Log.w(TAG, "parseCommandActionJson: couldn't parse LLM output: $raw", e)
        CommandAction.Unrecognized(transcript, "malformed LLM output")
    }

    private fun navFromString(s: String): CommandAction.Nav? = when (s) {
        "home" -> CommandAction.Nav.HOME
        "back" -> CommandAction.Nav.BACK
        "recents" -> CommandAction.Nav.RECENTS
        "notifications" -> CommandAction.Nav.NOTIFICATIONS
        "scroll_down" -> CommandAction.Nav.SCROLL_DOWN
        "scroll_up" -> CommandAction.Nav.SCROLL_UP
        else -> null
    }

    val COMMAND_SYSTEM_PROMPT = """
        You convert a spoken voice command into ONE JSON object describing the action.
        Valid shapes:
        {"action":"type_text","text":"<words to type>"}
        {"action":"open_app","query":"<app name>"}
        {"action":"navigate","nav":"home|back|recents|notifications|scroll_down|scroll_up"}
        {"action":"clear_field","_":null}
        {"action":"send_message","_":null}
        {"action":"read_last_message","contact":"<name>","app":"whatsapp"}
        {"action":"set_alarm","hour":<0-23>,"minute":<0-59>}
        {"action":"set_timer","seconds":<total seconds>}
        {"action":"cancel_alarm","_":null}
        {"action":"screenshot","_":null}
        {"action":"lock_screen","_":null}
        {"action":"unrecognized","reason":"<why not>"}

        Examples:
        "remind me to buy milk" -> {"action":"type_text","text":"remind me to buy milk"}
        "launch the camera" -> {"action":"open_app","query":"camera"}
        "wake me up at quarter past six" -> {"action":"set_alarm","hour":6,"minute":15}
        "give me five minutes" -> {"action":"set_timer","seconds":300}
        "grab a screenshot" -> {"action":"screenshot","_":null}
        "what's the weather like today" -> {"action":"unrecognized","reason":"no actionable command"}

        Output ONLY the JSON object, nothing else.
    """.trimIndent()
}
