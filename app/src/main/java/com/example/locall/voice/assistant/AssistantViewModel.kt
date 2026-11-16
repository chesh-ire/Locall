package com.example.locall.voice.assistant

import android.content.Context
import android.util.Log
import com.example.locall.voice.SpeechRecognitionBridge
import com.example.locall.voice.music.MediaPlaybackGate
import com.example.locall.voice.music.MusicIntent
import com.example.locall.voice.music.MusicIntents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "AssistantVM"

/**
 * Drives the single conversation screen: owns the message transcript, runs each request in the
 * background through [AssistantOrchestrator], and bridges the handlers' `confirm`/`listen` callbacks to
 * on-screen bubbles. Implemented as a plain, process-scoped singleton (no AndroidX ViewModel dependency)
 * so the conversation survives activity recreation / rotation.
 *
 * State machine (all transitions confined to the main thread):
 *   Idle → Running → {AwaitingConfirm | AwaitingInput} → Running → … → Idle, plus Cancelling.
 * At most ONE interaction is ever pending — handlers call confirm/listen sequentially, never nested.
 * `submit(text)` either completes a pending `listen` deferred or starts a fresh request Job.
 */
class AssistantViewModel private constructor(appContext: Context) {

    private val app = appContext.applicationContext
    private val orchestrator = AssistantOrchestrator(app)
    private val speech = SpeechRecognitionBridge(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = AtomicLong(0L)

    enum class Phase { Idle, Running, AwaitingConfirm, AwaitingInput, Cancelling }

    data class UiState(
        val messages: List<ChatMessage> = emptyList(),
        val phase: Phase = Phase.Idle,
        val recognizerReady: Boolean = false,
        val recording: Boolean = false,
    ) {
        /** The text field + send accept input when idle or when a handler is awaiting the next turn. */
        val inputEnabled: Boolean get() = phase == Phase.Idle || phase == Phase.AwaitingInput
        val micEnabled: Boolean get() = recognizerReady && inputEnabled && !recording
        val busy: Boolean get() = phase == Phase.Running || phase == Phase.Cancelling
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // Exactly one of these is non-null at a time (guarded by the phase).
    private var pendingConfirm: CompletableDeferred<Boolean>? = null
    private var pendingListen: CompletableDeferred<String?>? = null
    private var requestJob: Job? = null
    private var statusMsgId: Long = -1L
    private var statusAllowed = false
    private var mediaPausedForTurn = false

    fun init() {
        if (!_ui.value.recognizerReady) {
            _ui.value = _ui.value.copy(recognizerReady = speech.isAvailable())
        }
    }

    // ---------------- user input ----------------

    /** User sent a typed message, or a voice capture produced [text]. */
    fun submit(text: String, fromVoice: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch {   // Main.immediate
            val waitingListen = pendingListen
            if (waitingListen != null) {
                pendingListen = null
                addMessage(ChatMessage(nextId(), ChatRole.USER, t))
                resolvePromptBubbles()
                setPhase(Phase.Running)
                waitingListen.complete(t)
                return@launch
            }
            if (_ui.value.phase != Phase.Idle) return@launch   // input disabled otherwise
            addMessage(ChatMessage(nextId(), ChatRole.USER, t))
            startRequest(t, fromVoice)
        }
    }

    private fun startRequest(text: String, fromVoice: Boolean) {
        setPhase(Phase.Running)
        statusMsgId = -1L
        statusAllowed = true
        requestJob = scope.launch {   // Main.immediate — UI mutations stay on this thread
            val keepPaused = fromVoice &&
                MusicIntents.transport(text.lowercase()) == MusicIntent.Pause
            try {
                // The handlers do CPU/network/inference work — run them off the main thread. The
                // confirm/listen/onStatus callbacks hop back to Main themselves.
                val outcome = withContext(Dispatchers.Default) {
                    orchestrator.run(
                        request = text,
                        onStatus = { s -> postStatus(s) },
                        confirm = { desc -> onScreenConfirm(desc) },
                        listen = { prompt -> onScreenListen(prompt) },
                    )
                }
                clearStatus()
                val body = outcome.result.ifBlank { "Done." }
                addMessage(ChatMessage(nextId(), ChatRole.ASSISTANT, body, capability = outcome.capability))
            } catch (c: kotlinx.coroutines.CancellationException) {
                clearStatus()
                addMessage(ChatMessage(nextId(), ChatRole.ERROR, "⏹️ Cancelled."))
            } catch (t: Throwable) {
                Log.w(TAG, "request failed", t)
                clearStatus()
                addMessage(ChatMessage(nextId(), ChatRole.ERROR, "⚠️ Something went wrong: ${t.javaClass.simpleName}"))
            } finally {
                pendingConfirm = null
                pendingListen = null
                if (mediaPausedForTurn && !keepPaused) runCatching { MediaPlaybackGate.resume(app) }
                mediaPausedForTurn = false
                requestJob = null
                setPhase(Phase.Idle)
            }
        }
    }

    /**
     * Run a direct background task that isn't a free-form request — e.g. importing a picked document.
     * Shows [userLabel] as the user turn, streams the task's status inline, and posts its returned
     * string as the assistant result. Uses the same Running→Idle machinery as [submit].
     */
    fun runTask(userLabel: String, capability: String, task: suspend ((String) -> Unit) -> String) {
        scope.launch {
            if (_ui.value.phase != Phase.Idle) return@launch
            addMessage(ChatMessage(nextId(), ChatRole.USER, userLabel))
            setPhase(Phase.Running)
            statusMsgId = -1L
            statusAllowed = true
            requestJob = scope.launch {
                try {
                    val result = withContext(Dispatchers.Default) { task { s -> postStatus(s) } }
                    clearStatus()
                    addMessage(ChatMessage(nextId(), ChatRole.ASSISTANT, result.ifBlank { "Done." }, capability = capability))
                } catch (c: kotlinx.coroutines.CancellationException) {
                    clearStatus(); addMessage(ChatMessage(nextId(), ChatRole.ERROR, "⏹️ Cancelled."))
                } catch (t: Throwable) {
                    Log.w(TAG, "task failed", t)
                    clearStatus(); addMessage(ChatMessage(nextId(), ChatRole.ERROR, "⚠️ ${t.javaClass.simpleName}"))
                } finally {
                    requestJob = null
                    setPhase(Phase.Idle)
                }
            }
        }
    }

    // ---------------- hold-to-talk ----------------

    fun startRecording() {
        val st = _ui.value
        if (!st.recognizerReady || st.recording || !st.inputEnabled) return
        _ui.value = st.copy(recording = true)
        scope.launch {
            // Pause any playing media so the recognizer hears the user (ported from VoiceOrchestrator).
            mediaPausedForTurn = runCatching { MediaPlaybackGate.pauseForCommand(app) }.getOrDefault(false)
            val text = runCatching { speech.recognizeOnce(timeoutMs = 15_000) }.getOrNull()
            _ui.value = _ui.value.copy(recording = false)
            if (text.isNullOrBlank()) {
                if (mediaPausedForTurn) { runCatching { MediaPlaybackGate.resume(app) }; mediaPausedForTurn = false }
            } else {
                submit(text, fromVoice = true)
            }
        }
    }

    fun stopRecording() {
        if (_ui.value.recording) speech.finishNow()
    }

    // ---------------- confirm / listen bridges ----------------

    private suspend fun onScreenConfirm(description: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val d = CompletableDeferred<Boolean>()
        pendingConfirm = d
        addMessage(ChatMessage(nextId(), ChatRole.CONFIRM, description, pending = true))
        setPhase(Phase.AwaitingConfirm)
        d
    }.await()

    fun answerConfirm(approved: Boolean) {
        scope.launch {
            val d = pendingConfirm ?: return@launch
            pendingConfirm = null
            updateMessages { list ->
                list.map { if (it.role == ChatRole.CONFIRM && it.pending) it.copy(pending = false, confirmed = approved) else it }
            }
            setPhase(Phase.Running)
            d.complete(approved)
        }
    }

    private suspend fun onScreenListen(prompt: String): String? = withContext(Dispatchers.Main.immediate) {
        val d = CompletableDeferred<String?>()
        pendingListen = d
        if (prompt.isNotBlank()) {
            addMessage(ChatMessage(nextId(), ChatRole.PROMPT, prompt, pending = true))
        }
        setPhase(Phase.AwaitingInput)
        d
    }.await()

    /** User dismissed a follow-up prompt without answering → let the handler's loop exit gracefully. */
    fun skipPrompt() {
        scope.launch {
            val d = pendingListen ?: return@launch
            pendingListen = null
            resolvePromptBubbles()
            setPhase(Phase.Running)
            d.complete(null)
        }
    }

    // ---------------- cancellation ----------------

    fun cancel() {
        scope.launch {
            if (_ui.value.phase == Phase.Idle) return@launch
            setPhase(Phase.Cancelling)
            // Completing a pending interaction unblocks a handler suspended in confirm/listen so its
            // loop can unwind; the Job cancel below also cancels the deferred.await() directly.
            pendingConfirm?.let { pendingConfirm = null; it.complete(false) }
            pendingListen?.let { pendingListen = null; it.complete(null) }
            requestJob?.cancel()
        }
    }

    fun clearConversation() {
        if (_ui.value.phase != Phase.Idle) return
        orchestrator.reset()
        _ui.value = _ui.value.copy(messages = emptyList())
    }

    // ---------------- helpers (main thread) ----------------

    private fun nextId() = ids.incrementAndGet()

    private fun setPhase(p: Phase) { _ui.value = _ui.value.copy(phase = p) }

    private fun addMessage(m: ChatMessage) {
        _ui.value = _ui.value.copy(messages = _ui.value.messages + m)
    }

    private fun updateMessages(transform: (List<ChatMessage>) -> List<ChatMessage>) {
        _ui.value = _ui.value.copy(messages = transform(_ui.value.messages))
    }

    private fun resolvePromptBubbles() {
        updateMessages { list -> list.map { if (it.role == ChatRole.PROMPT && it.pending) it.copy(pending = false) else it } }
    }

    private fun postStatus(text: String) {
        scope.launch {   // Main.immediate
            if (!statusAllowed) return@launch
            if (statusMsgId < 0) {
                val m = ChatMessage(nextId(), ChatRole.STATUS, text, pending = true)
                statusMsgId = m.id
                addMessage(m)
            } else {
                updateMessages { list -> list.map { if (it.id == statusMsgId) it.copy(text = text) else it } }
            }
        }
    }

    private fun clearStatus() {
        statusAllowed = false
        statusMsgId = -1L
        // Drop ANY transient status line, not just the tracked id — a status posted from the worker
        // thread just as the request finishes can land after this, so clear the role wholesale.
        if (_ui.value.messages.any { it.role == ChatRole.STATUS }) {
            updateMessages { list -> list.filterNot { it.role == ChatRole.STATUS } }
        }
    }

    companion object {
        @Volatile private var INSTANCE: AssistantViewModel? = null
        fun get(context: Context): AssistantViewModel =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AssistantViewModel(context.applicationContext).also { INSTANCE = it }
            }
    }
}
