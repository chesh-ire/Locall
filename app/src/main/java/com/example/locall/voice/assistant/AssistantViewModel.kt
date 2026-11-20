package com.example.locall.voice.assistant

import android.content.Context
import android.util.Log
import com.example.locall.appContainer
import com.example.locall.voice.SpeechRecognitionBridge
import com.example.locall.voice.assistant.data.ChatMessageEntity
import com.example.locall.voice.assistant.data.ConversationSession
import com.example.locall.voice.music.MediaPlaybackGate
import com.example.locall.voice.music.MusicIntent
import com.example.locall.voice.music.MusicIntents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

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

    // ---- persistence ----
    private val repo = app.appContainer.conversationRepository
    /** The session the on-screen transcript currently belongs to; null until the first turn is saved. */
    @Volatile private var currentSessionId: String? = null
    /** Serialises DB writes so persisted message order matches the order they appear on screen. */
    private val persistMutex = Mutex()
    /** Guards one-time restore of the most-recent conversation on process start. */
    @Volatile private var restored = false

    /** Live list of saved conversations for the top-left drawer, most-recently-updated first. */
    val sessions: StateFlow<List<ConversationSession>> =
        repo.recentSessionsFlow().stateIn(scope, SharingStarted.Eagerly, emptyList())

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
        restoreMostRecentOnce()
    }

    /**
     * On the first [init] of the process, reopen the most recently used conversation so the transcript
     * is never silently discarded. No-ops if the user already started interacting before restore ran.
     */
    private fun restoreMostRecentOnce() {
        if (restored) return
        restored = true
        scope.launch {
            val session = repo.mostRecentSession() ?: return@launch
            if (currentSessionId != null || _ui.value.messages.isNotEmpty()) return@launch
            val restoredMsgs = repo.messagesFor(session.id).map { it.toChatMessage() }
            if (currentSessionId != null || _ui.value.messages.isNotEmpty()) return@launch
            currentSessionId = session.id
            adoptLoadedMessages(restoredMsgs)
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
                addAndPersist(ChatMessage(nextId(), ChatRole.USER, t))
                resolvePromptBubbles()
                setPhase(Phase.Running)
                waitingListen.complete(t)
                return@launch
            }
            if (_ui.value.phase != Phase.Idle) return@launch   // input disabled otherwise
            addAndPersist(ChatMessage(nextId(), ChatRole.USER, t))
            startRequest(t, fromVoice)
        }
    }

    private fun startRequest(text: String, fromVoice: Boolean) {
        setPhase(Phase.Running)
        statusMsgId = -1L
        statusAllowed = true
        val reqId = nextId()
        Log.i(TAG, "LIFECYCLE req#$reqId START: \"$text\" (fromVoice=$fromVoice)")
        requestJob = scope.launch {   // Main.immediate — UI mutations stay on this thread
            val keepPaused = fromVoice &&
                MusicIntents.transport(text.lowercase()) == MusicIntent.Pause
            // Keep the process alive (foreground service + wake lock) so the request runs to completion
            // and keeps speaking even with the screen off or the user on another page. Never let a
            // foreground-start restriction abort the request itself.
            runCatching { AssistantForegroundService.start(app, notifLabel(text)) }
                .onFailure { Log.w(TAG, "LIFECYCLE req#$reqId FGS start FAILED", it) }
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
                Log.i(TAG, "LIFECYCLE req#$reqId DONE normally: capability=${outcome.capability} resultLen=${body.length}")
                addAndPersist(ChatMessage(nextId(), ChatRole.ASSISTANT, body, capability = outcome.capability))
            } catch (c: kotlinx.coroutines.CancellationException) {
                // Distinguish WHO cancelled: a user Stop sets Phase.Cancelling; anything else (scope
                // teardown, process kill propagating, an inner coroutine) is an "external" cancel we
                // want to see. isActive==false means this job itself was cancelled.
                Log.w(
                    TAG,
                    "LIFECYCLE req#$reqId CANCELLED: phase=${_ui.value.phase} " +
                        "jobActive=${coroutineContext[Job]?.isActive} " +
                        "scopeActive=${scope.coroutineContext[Job]?.isActive} msg=${c.message}",
                    c,
                )
                clearStatus()
                addAndPersist(ChatMessage(nextId(), ChatRole.ERROR, "Cancelled."))
            } catch (t: Throwable) {
                Log.w(TAG, "LIFECYCLE req#$reqId FAILED", t)
                clearStatus()
                addAndPersist(ChatMessage(nextId(), ChatRole.ERROR, "Something went wrong: ${t.javaClass.simpleName}"))
            } finally {
                Log.i(TAG, "LIFECYCLE req#$reqId FINALLY: releasing foreground + phase→Idle")
                pendingConfirm = null
                pendingListen = null
                if (mediaPausedForTurn && !keepPaused) runCatching { MediaPlaybackGate.resume(app) }
                mediaPausedForTurn = false
                requestJob = null
                runCatching { AssistantForegroundService.stop(app) }
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
            addAndPersist(ChatMessage(nextId(), ChatRole.USER, userLabel))
            setPhase(Phase.Running)
            statusMsgId = -1L
            statusAllowed = true
            requestJob = scope.launch {
                runCatching { AssistantForegroundService.start(app, notifLabel(userLabel)) }
                try {
                    val result = withContext(Dispatchers.Default) { task { s -> postStatus(s) } }
                    clearStatus()
                    addAndPersist(ChatMessage(nextId(), ChatRole.ASSISTANT, result.ifBlank { "Done." }, capability = capability))
                } catch (c: kotlinx.coroutines.CancellationException) {
                    clearStatus(); addAndPersist(ChatMessage(nextId(), ChatRole.ERROR, "Cancelled."))
                } catch (t: Throwable) {
                    Log.w(TAG, "task failed", t)
                    clearStatus(); addAndPersist(ChatMessage(nextId(), ChatRole.ERROR, "${t.javaClass.simpleName}"))
                } finally {
                    requestJob = null
                    runCatching { AssistantForegroundService.stop(app) }
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
            // Hold-to-talk: the user ends the turn by releasing (stopRecording → finishNow), so the
            // recognizer must not endpoint on a mid-sentence pause. Large silence window + continuous
            // stitching; the release delivers whatever was captured so far.
            val text = runCatching {
                speech.recognizeOnce(timeoutMs = 120_000, silenceMs = 5_000L, continuous = true)
            }.getOrNull()
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
        // Log the caller's stack so we can tell a user-tapped Stop from any programmatic cancel.
        Log.w(TAG, "LIFECYCLE cancel() called (phase=${_ui.value.phase})", Throwable("cancel() call site"))
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

    // ---------------- conversation sessions ----------------

    /**
     * Start a fresh conversation. The current one stays saved in history; the next message opens a new
     * session row. Only the cross-capability routing memory is reset — persisted chats are untouched.
     */
    fun newConversation() {
        if (_ui.value.phase != Phase.Idle) return
        if (currentSessionId == null && _ui.value.messages.isEmpty()) return
        orchestrator.reset()
        currentSessionId = null
        _ui.value = _ui.value.copy(messages = emptyList())
    }

    /** Reopen a previously saved conversation, replacing the on-screen transcript with its messages. */
    fun openSession(sessionId: String) {
        if (_ui.value.phase != Phase.Idle) return
        if (sessionId == currentSessionId) return
        scope.launch {
            val loaded = repo.messagesFor(sessionId).map { it.toChatMessage() }
            if (_ui.value.phase != Phase.Idle) return@launch
            orchestrator.reset()
            currentSessionId = sessionId
            adoptLoadedMessages(loaded)
        }
    }

    /** Delete a saved conversation. If it is the one on screen, clear back to an empty state. */
    fun deleteSession(sessionId: String) {
        if (sessionId == currentSessionId && _ui.value.phase != Phase.Idle) return
        scope.launch {
            repo.deleteSession(sessionId)
            if (sessionId == currentSessionId) {
                orchestrator.reset()
                currentSessionId = null
                _ui.value = _ui.value.copy(messages = emptyList())
            }
        }
    }

    // ---------------- helpers (main thread) ----------------

    private fun nextId() = ids.incrementAndGet()

    /** Short, single-line foreground-notification label derived from the request/task text. */
    private fun notifLabel(text: String): String {
        val t = text.trim().replace('\n', ' ')
        return if (t.length > 60) t.take(57) + "…" else t
    }

    private fun setPhase(p: Phase) { _ui.value = _ui.value.copy(phase = p) }

    private fun addMessage(m: ChatMessage) {
        _ui.value = _ui.value.copy(messages = _ui.value.messages + m)
    }

    /** Append a durable message to the screen AND write it to the current session (created lazily). */
    private fun addAndPersist(m: ChatMessage) {
        addMessage(m)
        persist(m.role, m.text, m.capability)
    }

    private fun persist(role: ChatRole, text: String, capability: String?) {
        if (role != ChatRole.USER && role != ChatRole.ASSISTANT && role != ChatRole.ERROR) return
        scope.launch {
            persistMutex.withLock {   // keep DB insert order == on-screen order
                val now = System.currentTimeMillis()
                val sid = currentSessionId ?: run {
                    val created = repo.createSession(UUID.randomUUID().toString(), text, now)
                    currentSessionId = created.id
                    created.id
                }
                repo.saveMessage(sid, role.name, text, capability, now)
            }
        }
    }

    /** Swap the transcript for a loaded set, keeping the runtime id counter ahead of restored row ids. */
    private fun adoptLoadedMessages(loaded: List<ChatMessage>) {
        val maxId = loaded.maxOfOrNull { it.id } ?: 0L
        ids.updateAndGet { max(it, maxId) }
        _ui.value = _ui.value.copy(messages = loaded)
    }

    private fun ChatMessageEntity.toChatMessage(): ChatMessage =
        ChatMessage(
            id = id,
            role = runCatching { ChatRole.valueOf(role) }.getOrDefault(ChatRole.ASSISTANT),
            text = content,
            capability = capability,
        )

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
