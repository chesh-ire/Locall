package com.example.locall

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.agent.PersonalAgentCommandHandler
import com.example.locall.voice.agent.data.GoalManager
import com.example.locall.voice.agent.data.TaskManager
import com.example.locall.voice.agent.inbox.InboxManager
import com.example.locall.voice.assistant.data.ConversationRepository
import com.example.locall.voice.battery.BatteryCommandHandler
import com.example.locall.voice.email.EmailCommandHandler
import com.example.locall.voice.email.cache.EmailRepository
import com.example.locall.voice.memory.MemoryCommandHandler
import com.example.locall.voice.memory.MemoryRepository
import com.example.locall.voice.music.MusicCommandHandler
import com.example.locall.voice.music.history.MusicHistoryRepository
import com.example.locall.voice.research.ResearchCommandHandler
import com.example.locall.voice.research.data.KnowledgeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The application's single composition root (manual dependency injection).
 *
 * This is the ONE place the app-scoped object graph is assembled: repositories are constructed once
 * and handed to the command handlers via constructor injection, so a handler never reaches out and
 * builds its own collaborators. That makes the wiring visible in a single file and lets tests drive a
 * handler with fakes (`MemoryCommandHandler(fakeRepo)`) instead of a real [Context].
 *
 * Lifetime: everything here is app-scoped (one instance for the process), held by [LocallApp]. Leaf
 * repositories that already expose a process singleton (`X.get(context)`) are reused as-is so there's
 * exactly one instance regardless of whether the container or a deeper, framework-instantiated class
 * (an accessibility/foreground service) resolved it.
 */
class AppContainer(context: Context) {

    private val app: Context = context.applicationContext

    // ---- Repositories (app-scoped single instances) ----

    /** Single read-through Gmail cache. Previously constructed independently in four places. */
    val emailRepository: EmailRepository = EmailRepository.get(app)
    val memoryRepository: MemoryRepository = MemoryRepository.get(app)
    val knowledgeRepository: KnowledgeRepository = KnowledgeRepository.get(app)
    val taskManager: TaskManager = TaskManager.get(app)
    val goalManager: GoalManager = GoalManager.get(app)
    val inboxManager: InboxManager = InboxManager.get(app)
    val musicHistoryRepository: MusicHistoryRepository by lazy { MusicHistoryRepository(app) }
    val conversationRepository: ConversationRepository by lazy { ConversationRepository(app) }

    // ---- Command handlers (constructor-injected, lazily built on first use) ----

    val memoryHandler: MemoryCommandHandler by lazy { MemoryCommandHandler(memoryRepository) }
    val emailHandler: EmailCommandHandler by lazy { EmailCommandHandler(app, emailRepository) }
    val musicHandler: MusicCommandHandler by lazy { MusicCommandHandler(app, musicHistoryRepository) }
    val batteryHandler: BatteryCommandHandler by lazy { BatteryCommandHandler(app) }
    val researchHandler: ResearchCommandHandler by lazy { ResearchCommandHandler(app) }
    val agentHandler: PersonalAgentCommandHandler by lazy { PersonalAgentCommandHandler(app) }
}

/**
 * Process entry point. Owns the [AppContainer]; registered via `android:name=".LocallApp"`.
 */
class LocallApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    /** App-scoped scope for fire-and-forget startup work; [SupervisorJob] so one failure stays isolated. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            try {
                LlmBridge.ensureLoaded(this@LocallApp)
            } catch (t: Throwable) {
                Log.w("LocallApp", "LLM warm-up preload failed; will load lazily on first use", t)
            }
        }
    }
}

/**
 * Resolve the app's composition root from any [Context]. Falls back to a throwaway container if the
 * process isn't backed by [LocallApp] (e.g. an isolated test) — the leaf singletons keep it coherent.
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as? LocallApp)?.container ?: AppContainer(applicationContext)
