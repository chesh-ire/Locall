package com.example.locall.voice

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.example.locall.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoiceA11y"

        @Volatile
        var instance: VoiceAccessibilityService? = null
            private set

        @Volatile
        var lastContact: String? = null
            private set
    }

    private val tts by lazy { PiperTts(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob())
    private var debugReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
        if (BuildConfig.DEBUG) registerDebugReceiver()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        debugReceiver?.let { unregisterReceiver(it) }
        return super.onUnbind(intent)
    }

    private fun registerDebugReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val text = intent.getStringExtra("text") ?: return
                Log.i(TAG, "DEBUG_RUN_TEXT: \"$text\"")
                scope.launch {
                    val res = CommandPipeline.runText(
                        text,
                        appContext = applicationContext,
                        mediaWasPlaying = VolumeController.isMediaPlaying(applicationContext),
                        onStatus = { status -> Log.i(TAG, "DEBUG status: $status") },
                    )
                    Log.i(TAG, "DEBUG_RUN_TEXT result: transcript=\"${res.transcript}\" action=\"${res.action}\"")
                }
            }
        }
        registerReceiver(receiver, IntentFilter("com.example.locall.DEBUG_RUN_TEXT"), Context.RECEIVER_EXPORTED)
        debugReceiver = receiver
    }

    override fun onDestroy() {
        tts.destroy()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun speak(text: String) {
        tts.speak(text)
    }

    fun execute(action: CommandAction): Boolean = when (action) {
        is CommandAction.TypeText -> typeIntoFocused(action.text, action.append)
        is CommandAction.ClearField -> typeIntoFocused("", append = false)
        is CommandAction.OpenApp -> launchApp(action.query)
        is CommandAction.Navigate -> navigate(action.nav)
        is CommandAction.SendMessage -> clickSendButton()
        is CommandAction.ReadLastMessage ->
            error("ReadLastMessage is async — call readLastMessage() instead")
        is CommandAction.ComposeReply ->
            error("ComposeReply is async — handled directly in CommandPipeline.dispatch()")
        is CommandAction.Music ->
            error("Music is handled by the music module in CommandPipeline.dispatch()")
        is CommandAction.Memory ->
            error("Memory is handled by the memory module in CommandPipeline.dispatch()")
        is CommandAction.Email ->
            error("Email is handled by the email module in CommandPipeline.dispatch()")
        is CommandAction.Battery ->
            error("Battery is handled by the battery module in CommandPipeline.dispatch()")
        is CommandAction.Research ->
            error("Research is handled by the research module in CommandPipeline.dispatch()")
        is CommandAction.Agent ->
            error("Agent is handled by the agent module in CommandPipeline.dispatch()")
        is CommandAction.Torch ->
            error("Torch is handled by TorchController in CommandPipeline.dispatch()")
        is CommandAction.Volume ->
            error("Volume is handled by VolumeController in CommandPipeline.dispatch()")
        is CommandAction.SetAlarm, is CommandAction.SetTimer, is CommandAction.CancelAlarm ->
            error("Clock actions are handled by ClockController in CommandPipeline.dispatch()")
        is CommandAction.Screenshot -> takeScreenshot()
        is CommandAction.LockScreen -> lockScreen()
        is CommandAction.Unrecognized -> {
            Log.w(TAG, "Unrecognized: ${action.transcript} (${action.reason})")
            false
        }
    }


    private fun typeIntoFocused(text: String, append: Boolean): Boolean {
        val node = findFocusedEditable() ?: run {
            Log.w(TAG, "No focused editable field to type into")
            return false
        }
        val existing = if (append) (node.text?.toString() ?: "") else ""
        val newText = if (append && existing.isNotEmpty()) "$existing $text" else text
        val ok = setNodeText(node, newText)
        node.recycle()
        return ok
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        val root = rootInActiveWindow ?: return null
        return firstEditable(root)
    }

    private fun firstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            firstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }


    private fun clickSendButton(): Boolean {
        findClickableByDescription("send")?.let { return it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return false
        val field = findFocusedEditable() ?: return false
        val ok = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        field.recycle()
        return ok
    }


    private fun launchApp(query: String): Boolean {
        val pm = packageManager
        val q = query.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
            .removeSuffix(" app").trim()
        if (q.isEmpty()) return false

        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)

        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return false

        val pkg = match.activityInfo.packageName
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return true
    }


    private fun takeScreenshot(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot needs Android 11+ (have ${android.os.Build.VERSION.SDK_INT})")
            return false
        }
        return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    private fun lockScreen(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            Log.w(TAG, "lockScreen needs Android 9+ (have ${android.os.Build.VERSION.SDK_INT})")
            return false
        }
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    private fun navigate(nav: CommandAction.Nav): Boolean = when (nav) {
        CommandAction.Nav.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        CommandAction.Nav.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        CommandAction.Nav.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        CommandAction.Nav.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        CommandAction.Nav.SCROLL_DOWN -> scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        CommandAction.Nav.SCROLL_UP -> scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun scroll(actionId: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollable(root) ?: return false
        return scrollable.performAction(actionId)
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }


    suspend fun readLastMessage(contact: String, appPackage: String = "com.whatsapp"): String? {
        Log.i(TAG, "readLastMessage: starting for contact=\"$contact\" app=$appPackage")
        ensureWakeWordListening()
        val pm = packageManager
        val launch = pm.getLaunchIntentForPackage(appPackage) ?: run {
            Log.w(TAG, "$appPackage not installed")
            return null
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launch)

        val searchIcon = waitFor { findClickableByDescription("search") } ?: run {
            Log.w(TAG, "search icon not found"); return null
        }
        Log.i(TAG, "readLastMessage: found search icon, clicking")
        searchIcon.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val searchField = waitFor { rootInActiveWindow?.let(::firstEditable) } ?: run {
            Log.w(TAG, "search field not found"); return null
        }
        Log.i(TAG, "readLastMessage: found search field, typing \"$contact\"")
        setNodeText(searchField, contact)

        delay(700)
        val firstResult = waitFor { rootInActiveWindow?.let { findBestResultRow(it, contact) } } ?: run {
            Log.w(TAG, "no search result for \"$contact\""); return null
        }
        Log.i(TAG, "readLastMessage: found result row, clicking")
        firstResult.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val chatRoot = waitFor(timeoutMs = 6000) {
            rootInActiveWindow?.takeIf { firstEditable(it) != null }
        } ?: run { Log.w(TAG, "chat screen did not load"); return null }
        Log.i(TAG, "readLastMessage: chat screen loaded, reading last message")
        delay(400)

        val message = (rootInActiveWindow ?: chatRoot).let(::lastMeaningfulText)
        Log.i(TAG, "readLastMessage: extracted message: ${message?.let { "\"$it\"" } ?: "null"}")
        lastContact = contact
        return message
    }


    suspend fun playViaSpotifySearch(query: String, appPackage: String = "com.spotify.music"): Boolean {
        Log.i(TAG, "playViaSpotifySearch: \"$query\"")
        ensureWakeWordListening()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query.trim()))).apply {
            setPackage(appPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "playViaSpotifySearch: can't open Spotify: ${t.message}")
            return false
        }

        val target = waitFor(timeoutMs = 12_000, intervalMs = 250) {
            val root = rootInActiveWindow ?: return@waitFor null
            if (root.packageName?.toString()?.contains("spotify") != true) return@waitFor null
            findSpotifyPlayTarget(root, query)
        }

        if (target == null) {
            Log.w(TAG, "playViaSpotifySearch: no tappable result (front pkg=${rootInActiveWindow?.packageName})")
            return false
        }

        Log.i(TAG, "playViaSpotifySearch: tapping \"${target.contentDescription ?: target.text}\" clickable=${target.isClickable}")
        return clickSelfOrAncestor(target)
    }

    private fun findSpotifyPlayTarget(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val qTokens = query.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ")
            .filter { it.length >= 3 }.toSet()
        if (qTokens.isEmpty()) return null

        findSpotifyPlayButton(root, qTokens)?.let { Log.i(TAG, "target: play button"); return it }

        val chrome = setOf("search", "home", "your library", "premium", "songs", "artists",
            "albums", "playlists", "podcasts & shows", "podcasts", "profile", "settings", "cancel")

        val rows = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        var songsHeaderIndex = -1
        fun rowText(n: AccessibilityNodeInfo): String {
            val sb = StringBuilder()
            fun collect(x: AccessibilityNodeInfo) {
                x.text?.toString()?.let { sb.append(it).append(' ') }
                x.contentDescription?.toString()?.let { sb.append(it).append(' ') }
                for (i in 0 until x.childCount) x.getChild(i)?.let { collect(it) }
            }
            collect(n)
            return sb.toString().lowercase().trim()
        }
        fun walk(n: AccessibilityNodeInfo) {
            val t = rowText(n)
            if (t == "songs" && songsHeaderIndex < 0) songsHeaderIndex = rows.size
            if (n.isClickable && !n.isEditable && t.isNotBlank() && t !in chrome) rows.add(n to t)
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        if (rows.isEmpty()) return null

        fun matchesQuery(t: String) = qTokens.any { it in t }

        if (songsHeaderIndex in rows.indices) {
            rows.drop(songsHeaderIndex).firstOrNull { (_, t) -> matchesQuery(t) }
                ?.let { Log.i(TAG, "target: song-row \"${it.second}\""); return it.first }
        }
        rows.firstOrNull { (_, t) -> matchesQuery(t) }
            ?.let { Log.i(TAG, "target: query-match row \"${it.second}\""); return it.first }

        return null
    }

    private fun clickSelfOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 8) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        return false
    }

    private fun findSpotifyPlayButton(root: AccessibilityNodeInfo, qTokens: Set<String>): AccessibilityNodeInfo? {
        val playWord = Regex("(^|\\s)play(\\s|$)")
        val buttons = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        fun walk(n: AccessibilityNodeInfo) {
            val desc = n.contentDescription?.toString()?.trim()?.lowercase()
            if (n.isClickable && desc != null && playWord.containsMatchIn(desc) && !desc.contains("playlist")) {
                buttons.add(n to desc)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return buttons.firstOrNull { (_, d) -> qTokens.any { it in d } }?.first
    }

    private fun ensureWakeWordListening() {
        if (WakeWordService.isRunning) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ensureWakeWordListening: RECORD_AUDIO not granted, can't auto-start")
            return
        }
        Log.i(TAG, "ensureWakeWordListening: starting WakeWordService")
        val intent = Intent(this, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private suspend fun waitFor(
        timeoutMs: Long = 4000,
        intervalMs: Long = 150,
        predicate: () -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            predicate()?.let { return it }
            delay(intervalMs)
        }
        return null
    }

    private fun findClickableByDescription(keyword: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        fun search(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val desc = n.contentDescription?.toString()
            if (n.isClickable && desc != null && desc.contains(keyword, ignoreCase = true)) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { c -> search(c)?.let { return it } }
            }
            return null
        }
        return search(root)
    }

    private fun findBestResultRow(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        fun rowText(n: AccessibilityNodeInfo): String {
            val sb = StringBuilder()
            fun collect(x: AccessibilityNodeInfo) {
                x.text?.toString()?.let { sb.append(it).append(' ') }
                for (i in 0 until x.childCount) x.getChild(i)?.let { collect(it) }
            }
            collect(n)
            return sb.toString()
        }
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        fun search(n: AccessibilityNodeInfo) {
            if (n.isClickable && !n.isEditable) {
                val t = rowText(n)
                if (t.isNotBlank()) candidates.add(n to t.lowercase())
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { search(it) }
        }
        search(root)

        val q = query.trim().lowercase()
        fun isMetaAi(text: String) = text.contains("meta ai") || text.contains("ask meta")

        candidates.firstOrNull { (_, t) -> q in t && !isMetaAi(t) }?.let { return it.first }
        return candidates.firstOrNull { (_, t) -> !isMetaAi(t) }?.first
    }

    private fun lastMeaningfulText(container: AccessibilityNodeInfo): String? {
        val timeLike = Regex("""^\d{1,2}:\d{2}(\s?[APap][Mm])?$""")
        val texts = mutableListOf<String>()
        fun collect(n: AccessibilityNodeInfo) {
            if (!n.isEditable) {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it) }
        }
        collect(container)
        return texts.filterNot { timeLike.matches(it) || it.length <= 1 }.lastOrNull()
    }
}
