package com.example.locall.voice.agent.orchestrator

import android.content.Context
import android.util.Log
import com.example.locall.voice.ActionConfirmation
import com.example.locall.voice.CommandAction
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.VoiceAccessibilityService
import com.example.locall.voice.agent.data.GoalManager
import com.example.locall.voice.agent.data.InboxItemEntity
import com.example.locall.voice.agent.data.InboxSource
import com.example.locall.voice.agent.data.MessageClass
import com.example.locall.voice.agent.data.TaskEntity
import com.example.locall.voice.agent.data.TaskManager
import com.example.locall.voice.agent.data.TaskPriority
import com.example.locall.voice.agent.follow.FollowUpManager
import com.example.locall.voice.agent.inbox.InboxManager
import com.example.locall.voice.agent.inbox.MessageClassifier
import com.example.locall.voice.agent.planning.PlanningEngine
import com.example.locall.voice.agent.planning.ProgressTracker
import com.example.locall.voice.agent.tasks.TaskExtractor
import com.example.locall.voice.agent.util.DateParser
import com.example.locall.voice.email.EmailReplyWriter
import com.example.locall.voice.email.EmailSummarizer
import com.example.locall.voice.email.GmailApi
import com.example.locall.voice.email.GmailAuth
import com.example.locall.voice.email.cache.EmailRepository

private const val TAG = "ToolExecutor"

class ToolExecutor(context: Context) {

    private val app = context.applicationContext
    private val tasks = TaskManager.get(app)
    private val goals = GoalManager.get(app)
    private val inbox = InboxManager.get(app)
    private val planner = PlanningEngine(tasks)
    private val progress = ProgressTracker(tasks, goals)
    private val ctxBuilder = ContextBuilder(tasks, goals)
    private val taskExtractor = TaskExtractor(tasks)
    private val emailRepo = EmailRepository(app)

    data class Result(
        val display: String,
        val spoken: String = display,
        val continueConversation: Boolean = false,
    )

    class Io(
        val speak: (String) -> Unit,
        val confirm: suspend (String) -> Boolean,
        val listen: suspend (String) -> String?,
        val onStatus: (String) -> Unit,
    )

    suspend fun execute(tool: AgentTool, convo: ConversationManager, io: Io): Result = when (tool) {
        is AgentTool.BriefInbox -> briefInbox(convo, io)
        is AgentTool.ListTasks -> listTasks(convo)
        is AgentTool.PlanDay -> planDay()
        is AgentTool.AddTask -> addTask(tool)
        is AgentTool.CompleteTask -> completeTask(tool.ref, convo)
        is AgentTool.DeleteTask -> deleteTask(tool.ref, convo)
        is AgentTool.PostponeTask -> postponeTask(tool, convo)
        is AgentTool.RemindTask -> remindTask(tool, convo)
        is AgentTool.PlanGoal -> planGoal(tool, convo, io)
        is AgentTool.GoalProgress -> goalProgress(tool.ref, convo)
        is AgentTool.ReplyTo -> reply(tool, convo, io)
        is AgentTool.ExtractTasks -> extractTasks(tool.ref, convo, io)
        is AgentTool.DiscussMessage -> discuss(tool.ref, convo, io)
        is AgentTool.Chat -> chat(tool.text, convo)
        is AgentTool.Unknown -> Result("🤷 I didn't catch what you'd like me to do.")
    }

    private suspend fun briefInbox(convo: ConversationManager, io: Io): Result {
        val b = inbox.buildBriefing(io.onStatus)
        if (b.isEmpty) {
            val msg = if (b.gmailConnected) "You're all caught up — nothing new needs your attention."
                else "Nothing new to brief. Connect Gmail in LocaLL to include your email."
            return Result("📭 $msg", msg)
        }
        val spoken = buildString {
            append("You have ${b.items.size} message${plural(b.items.size)} worth noting. ")
            if (b.needsReply.isNotEmpty()) {
                append("${b.needsReply.size} need${if (b.needsReply.size == 1) "s" else ""} a reply: ")
                append(b.needsReply.take(3).joinToString("; ") { "${it.sender} about ${it.summary}" }).append(". ")
            }
            if (b.actions.isNotEmpty()) {
                append("${b.actions.size} need${if (b.actions.size == 1) "s" else ""} an action: ")
                append(b.actions.take(3).joinToString("; ") { "${it.sender}, ${it.summary}" }).append(". ")
            }
            if (b.info.isNotEmpty()) append("${b.info.size} informational. ")
            append("You can say reply to, or tell me about, any of them.")
        }
        (b.needsReply.firstOrNull() ?: b.actions.firstOrNull() ?: b.items.first()).let { convo.focusMessage(it.id) }
        val display = buildString {
            append("📨 Inbox briefing (${b.items.size})\n")
            b.items.forEach { append("• [${MessageClass.label(it.classification)}] ${it.sender}: ${it.summary}\n") }
        }
        return Result(display.trim(), spoken, continueConversation = true)
    }

    private suspend fun listTasks(convo: ConversationManager): Result {
        val (text, ids) = ctxBuilder.taskList()
        convo.rememberTaskList(ids)
        return Result("✅ Your tasks:\n$text", text)
    }

    private suspend fun planDay(): Result {
        val now = System.currentTimeMillis()
        val open = tasks.open()
        if (open.isEmpty()) return Result("🗓️ Your day is clear — no open tasks.", "Your day is clear. You have no open tasks.")
        val ordered = open.sortedWith(
            compareByDescending<TaskEntity> { it.dueAtMs != null && it.dueAtMs < now }
                .thenByDescending { it.priority }
                .thenBy { it.dueAtMs ?: Long.MAX_VALUE },
        )
        val spoken = buildString {
            append("Here's a plan for your day. ")
            ordered.take(6).forEachIndexed { i, t ->
                append("${i + 1}. ${t.title}")
                if (t.dueAtMs != null && t.dueAtMs < now) append(", overdue")
                append(". ")
            }
        }
        val display = "🗓️ Plan for today:\n" + ordered.take(10).mapIndexed { i, t ->
            val tag = when {
                t.dueAtMs != null && t.dueAtMs < now -> " (overdue)"
                t.dueAtMs != null -> " (due ${ContextBuilder.fmtDate(t.dueAtMs)})"
                else -> ""
            }
            "${i + 1}. ${t.title}$tag"
        }.joinToString("\n")
        return Result(display, spoken)
    }

    private suspend fun addTask(tool: AgentTool.AddTask): Result {
        val due = DateParser.parse(tool.duePhrase)
        val priority = when (tool.priorityWord.lowercase()) {
            "high", "urgent" -> TaskPriority.HIGH; "low" -> TaskPriority.LOW; else -> TaskPriority.NORMAL
        }
        val t = tasks.create(
            title = tool.title,
            detail = if (due == null && tool.duePhrase.isNotBlank()) "due: ${tool.duePhrase}" else "",
            priority = priority,
            dueAtMs = due,
        )
        if (due != null) FollowUpManager.schedule(app, t.id, t.title, due)
        val whenStr = due?.let { " for ${ContextBuilder.fmtDate(it)}" } ?: ""
        val msg = "Added: ${t.title}$whenStr." + if (due != null) " I'll remind you." else ""
        return Result("➕ $msg", msg)
    }

    private suspend fun completeTask(ref: String, convo: ConversationManager): Result {
        val t = resolveTask(ref, convo) ?: return Result("🔍 I couldn't find that task.", "I couldn't find that task.")
        tasks.complete(t.id)
        FollowUpManager.cancel(app, t.id)
        val goalDone = t.goalId?.let { progress.maybeCompleteGoal(it) } ?: false
        val msg = "Done: ${t.title}." + if (goalDone) " That completes the whole goal — nice work!" else ""
        return Result("✔️ $msg", msg)
    }

    private suspend fun deleteTask(ref: String, convo: ConversationManager): Result {
        val t = resolveTask(ref, convo) ?: return Result("🔍 I couldn't find that task.", "I couldn't find that task.")
        FollowUpManager.cancel(app, t.id)
        tasks.delete(t.id)
        return Result("🗑️ Deleted: ${t.title}.", "Deleted ${t.title}.")
    }

    private suspend fun postponeTask(tool: AgentTool.PostponeTask, convo: ConversationManager): Result {
        val t = resolveTask(tool.ref, convo) ?: return Result("🔍 I couldn't find that task.", "I couldn't find that task.")
        val until = DateParser.parse(tool.whenPhrase)
        tasks.postpone(t.id, until)
        until?.let { FollowUpManager.schedule(app, t.id, t.title, it) }
        val whenStr = until?.let { " to ${ContextBuilder.fmtDate(it)}" } ?: ""
        return Result("⏳ Postponed ${t.title}$whenStr.", "Postponed ${t.title}$whenStr.")
    }

    private suspend fun remindTask(tool: AgentTool.RemindTask, convo: ConversationManager): Result {
        val t = resolveTask(tool.ref, convo)
        val at = DateParser.parse(tool.whenPhrase)
            ?: return Result("⚠️ I couldn't work out when to remind you.", "I couldn't work out when to remind you.")
        return if (t != null) {
            FollowUpManager.schedule(app, t.id, t.title, at)
            Result("⏰ I'll remind you about ${t.title} at ${ContextBuilder.fmtDate(at)}.",
                "I'll remind you about ${t.title} at ${ContextBuilder.fmtDate(at)}.")
        } else {
            val created = tasks.create(title = tool.ref.ifBlank { "Reminder" }, dueAtMs = at)
            FollowUpManager.schedule(app, created.id, created.title, at)
            Result("⏰ Reminder set for ${ContextBuilder.fmtDate(at)}.", "Okay, I'll remind you at ${ContextBuilder.fmtDate(at)}.")
        }
    }

    private suspend fun planGoal(tool: AgentTool.PlanGoal, convo: ConversationManager, io: Io): Result {
        if (tool.goal.isBlank()) return Result("What goal should I plan?", "What goal should I plan for you?")
        val plan = planner.propose(app, tool.goal, "", tool.horizonDays, io.onStatus)
        if (plan.isEmpty) return Result("⚠️ I couldn't break that into steps.", "I couldn't break that goal into steps.")
        val preview = plan.steps.take(6).mapIndexed { i, s -> "${i + 1}. ${s.title}" }.joinToString("; ")
        val ask = "I've drafted a ${plan.steps.size}-step plan: $preview. Shall I save it and schedule reminders?"
        if (!io.confirm(ask)) return Result("❌ Plan not saved.\n$preview", "Okay, I won't save it.")
        val goal = goals.create(tool.goal, "", targetDateMs = System.currentTimeMillis() + tool.horizonDays.toLong() * 86_400_000L)
        val created = planner.commit(goal, plan)
        created.forEach { t -> t.dueAtMs?.let { FollowUpManager.schedule(app, t.id, t.title, it) } }
        convo.focusGoal(goal.id)
        val msg = "Saved your plan for ${tool.goal} with ${created.size} tasks and reminders."
        val display = "🎯 ${tool.goal}\n" + created.mapIndexed { i, t ->
            "${i + 1}. ${t.title}" + (t.dueAtMs?.let { " — ${ContextBuilder.fmtDate(it)}" } ?: "")
        }.joinToString("\n")
        return Result(display, msg)
    }

    private suspend fun goalProgress(ref: String, convo: ConversationManager): Result {
        val goal = resolveGoal(ref, convo) ?: return Result("🔍 I couldn't find that goal.", "I couldn't find that goal.")
        val p = progress.forGoal(goal.id)
        convo.focusGoal(goal.id)
        val next = p.nextTask?.let { " Next up: ${it.title}." } ?: ""
        val replan = if (p.needsReplan) " You have ${p.overdue} overdue; say \"replan\" and I'll adjust." else ""
        val msg = "${goal.title}: ${p.done} of ${p.total} done, ${p.pct}%.$next$replan"
        return Result("📈 $msg", msg)
    }

    private suspend fun reply(tool: AgentTool.ReplyTo, convo: ConversationManager, io: Io): Result {
        val item = resolveInbox(tool.ref, convo)
            ?: return Result("🔍 I couldn't find that message.", "I couldn't find the message to reply to.")
        convo.focusMessage(item.id)
        return when (item.source) {
            InboxSource.GMAIL -> gmailReply(item, tool.body, io)
            else -> whatsappReply(item, tool.body, io)
        }
    }

    private suspend fun gmailReply(item: InboxItemEntity, intent: String, io: Io): Result {
        val token = when (val r = GmailAuth.acquire(app)) {
            is GmailAuth.Result.Token -> r.accessToken
            is GmailAuth.Result.NeedsConsent -> return Result("📧 Connect Gmail in LocaLL first.", "Please connect Gmail first.")
            is GmailAuth.Result.Error -> return Result("⚠️ Gmail error: ${r.message}", "I couldn't reach Gmail.")
        }
        val msgId = item.threadRef.ifBlank { item.id.removePrefix("gmail:") }
        return try {
            val rctx = GmailApi.replyContext(token, msgId)
            if (rctx.toEmail.isBlank()) return Result("⚠️ Couldn't tell who to reply to.", "I couldn't tell who to reply to.")
            val who = EmailSummarizer.cleanFrom(rctx.fromRaw).ifBlank { item.sender }
            val subject = if (rctx.subject.startsWith("Re:", true)) rctx.subject else "Re: ${rctx.subject}"
            val body = emailRepo.body(token, msgId)
            val brief = EmailReplyWriter.ReplyBrief(who, rctx.subject, body, "")
            val intended = intent.ifBlank { askIntent(io, who) ?: return Result("📝 No reply sent.", "Okay, maybe later.") }
            var draft = EmailReplyWriter.draft(app, brief, intended)
            var prompt = "Here's a reply to $who. $draft. Send it, change it, or cancel?"
            repeat(5) {
                val r = io.listen(prompt)
                when {
                    r.isNullOrBlank() -> return Result("📝 Not sent — I didn't catch your answer.\n$draft", "I didn't catch that, so I left it as a draft.")
                    ActionConfirmation.isApproval(r) -> {
                        GmailApi.sendReply(token, rctx.toEmail, subject, draft, rctx.threadId, rctx.messageIdHeader, rctx.references)
                        inbox.markHandled(item.id)
                        return Result("↩️ Replied to $who\n$subject\n$draft", "Replied to $who.")
                    }
                    ActionConfirmation.isCancellation(r) -> return Result("❌ Not sent.\n$draft", "Okay, I won't send it.")
                    else -> { draft = EmailReplyWriter.refine(app, brief, draft, r); prompt = "How about this. $draft. Send, change, or cancel?" }
                }
            }
            Result("📝 Left as a draft:\n$draft", "I'll leave that as a draft.")
        } catch (e: GmailApi.Unauthorized) {
            GmailAuth.invalidate(); Result("⚠️ Gmail access expired — reconnect in LocaLL.", "Gmail access expired.")
        } catch (t: Throwable) {
            Log.w(TAG, "gmailReply failed", t); Result("⚠️ Reply failed: ${t.javaClass.simpleName}", "Something went wrong sending that reply.")
        }
    }

    private suspend fun whatsappReply(item: InboxItemEntity, intent: String, io: Io): Result {
        val svc = VoiceAccessibilityService.instance
            ?: return Result("⚠️ Enable Accessibility to send WhatsApp replies.", "Enable Accessibility to send WhatsApp replies.")
        val intended = intent.ifBlank { askIntent(io, item.sender) ?: return Result("📝 No reply sent.", "Okay, maybe later.") }
        val decodeThreads = ThermalGovernor.decodeThreads(app)
        val draft = try {
            LlmBridge.generateReply(intended, decodeThreads)
        } catch (t: Throwable) {
            return Result("⚠️ Couldn't draft a reply.", "I couldn't draft that reply.")
        }
        if (!io.confirm("Send to ${item.sender}: \"$draft\"?")) return Result("❌ Not sent.\n$draft", "Okay, not sent.")
        svc.readLastMessage(item.threadRef.ifBlank { item.sender })
        val typed = svc.execute(CommandAction.TypeText(draft, append = false))
        if (!typed) return Result("⚠️ Opened the chat but couldn't type the reply.", "I opened the chat but couldn't type the reply.")
        val sent = svc.execute(CommandAction.SendMessage)
        return if (sent) {
            inbox.markHandled(item.id)
            Result("📤 Sent to ${item.sender}: $draft", "Sent to ${item.sender}.")
        } else {
            Result("✍️ Typed the reply to ${item.sender}; tap send to confirm.", "I typed it; tap send to confirm.")
        }
    }

    private suspend fun askIntent(io: Io, who: String): String? {
        val r = io.listen("What would you like to say to $who?")
        return r?.takeIf { it.isNotBlank() && !ActionConfirmation.isCancellation(it) }
    }

    private suspend fun extractTasks(ref: String, convo: ConversationManager, io: Io): Result {
        val item = resolveInbox(ref, convo) ?: return Result("🔍 I couldn't find that message.", "I couldn't find that message.")
        val already = tasks.forInboxItem(item.id)
        if (already.isNotEmpty()) {
            val titles = already.joinToString("; ") { it.title }
            return Result("ℹ️ Already extracted: $titles", "I already pulled those tasks: $titles")
        }
        io.onStatus("Loading AI model…"); LlmBridge.ensureLoaded(app, io.onStatus)
        val body = messageBody(item)
        val created = taskExtractor.extract(app, item, body)
        if (created.isEmpty()) return Result("✅ No action items found in that message.", "I didn't find anything you need to do in that message.")
        created.forEach { t -> t.dueAtMs?.let { FollowUpManager.schedule(app, t.id, t.title, it) } }
        val titles = created.joinToString("; ") { it.title }
        return Result("➕ Added ${created.size} task${plural(created.size)}: $titles", "I added ${created.size} task${plural(created.size)}: $titles")
    }

    private suspend fun discuss(ref: String, convo: ConversationManager, io: Io): Result {
        val item = resolveInbox(ref, convo) ?: return Result("🔍 I couldn't find that message.", "I couldn't find that message.")
        convo.focusMessage(item.id)
        inbox.markReviewed(item.id)
        io.onStatus("Loading AI model…"); LlmBridge.ensureLoaded(app, io.onStatus)
        val body = messageBody(item)
        val summary = if (item.source == InboxSource.GMAIL)
            EmailSummarizer.summarizeOne(
                app,
                GmailApi.Message(item.id, item.sender, item.title, item.preview, false, item.receivedAtMs),
                body,
            )
        else MessageClassifier.classify(app, item).summary
        val spoken = "From ${item.sender}. $summary. What would you like to do — reply, make a task, or ask something?"
        return Result("💬 ${item.sender} — ${item.title}\n$summary", spoken, continueConversation = true)
    }

    private suspend fun chat(text: String, convo: ConversationManager): Result {
        LlmBridge.ensureLoaded(app) {}
        val state = ctxBuilder.stateBlock(app, text)
        val focus = convo.focusInboxId?.let { inbox.item(it) }
        val focusBlock = focus?.let { "\nMessage in focus — from ${it.sender}, subject ${it.title}: ${it.preview.take(300)}" } ?: ""
        val recent = convo.recentContext()
        val decodeThreads = ThermalGovernor.decodeThreads(app)
        val system = """
            You are LocaLL's personal agent. Answer the user's request briefly (1-3 sentences) using ONLY the
            context below (their tasks, goals, and any message in focus). If the answer isn't in the context,
            say you don't have that information. Do not invent tasks, dates, or facts.
        """.trimIndent()
        val user = buildString {
            if (recent.isNotBlank()) append(recent).append('\n')
            append("Context:\n").append(state).append(focusBlock).append("\n\nRequest: ").append(text)
        }
        val answer = try {
            LlmBridge.generateText(system, user, temperature = 0.5f, maxTokens = 160, decodeThreads = decodeThreads).trim()
        } catch (t: Throwable) {
            Log.w(TAG, "chat failed", t); "I'm not sure about that."
        }.ifBlank { "I'm not sure about that." }
        return Result("🧠 $answer", answer)
    }

    private suspend fun resolveTask(ref: String, convo: ConversationManager): TaskEntity? {
        val open = tasks.open()
        if (open.isEmpty()) return null
        if (ref.lowercase().contains("last")) return open.last()
        indexRef(ref)?.let { idx ->
            convo.lastTaskIds.getOrNull(idx)?.let { id -> return open.firstOrNull { it.id == id } }
            return open.getOrNull(idx)
        }
        if (ref.isBlank() && open.size == 1) return open.first()
        return bestByTokens(ref, open) { it.title }
    }

    private suspend fun resolveInbox(ref: String, convo: ConversationManager): InboxItemEntity? {
        if (ref.isBlank() || ref in GENERIC) return convo.focusInboxId?.let { inbox.item(it) }
        val recent = inbox.recent(30)
        if (recent.isEmpty()) return convo.focusInboxId?.let { inbox.item(it) }
        if (ref.lowercase().contains("last")) return recent.first()
        indexRef(ref)?.let { return recent.getOrNull(it) }
        return bestByTokens(ref, recent) { "${it.sender} ${it.title}" } ?: convo.focusInboxId?.let { inbox.item(it) }
    }

    private suspend fun resolveGoal(ref: String, convo: ConversationManager): com.example.locall.voice.agent.data.GoalEntity? {
        val active = goals.active()
        if (ref.isBlank()) return convo.focusGoalId?.let { goals.get(it) } ?: active.firstOrNull()
        return bestByTokens(ref, active) { it.title } ?: convo.focusGoalId?.let { goals.get(it) }
    }

    private fun indexRef(ref: String): Int? {
        val t = ref.lowercase()
        ORDINALS.entries.firstOrNull { t.contains(it.key) }?.let { return it.value }
        Regex("\\b(\\d+)\\b").find(t)?.let { return it.groupValues[1].toIntOrNull()?.minus(1)?.takeIf { n -> n >= 0 } }
        return null
    }

    private fun <T> bestByTokens(ref: String, items: List<T>, text: (T) -> String): T? {
        val q = tokenize(ref)
        if (q.isEmpty()) return null
        return items
            .map { it to tokenize(text(it)).count { w -> w in q } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.length > 2 }.toSet()

    private suspend fun messageBody(item: InboxItemEntity): String {
        if (item.source != InboxSource.GMAIL) return item.preview
        val token = (GmailAuth.acquire(app) as? GmailAuth.Result.Token)?.accessToken ?: return item.preview
        return try { emailRepo.body(token, item.threadRef.ifBlank { item.id.removePrefix("gmail:") }) }
        catch (t: Throwable) { item.preview }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    companion object {
        private val GENERIC = setOf("it", "that", "this", "the message", "the email", "them", "this one", "that one")
        private val ORDINALS = mapOf(
            "first" to 0, "1st" to 0, "second" to 1, "2nd" to 1, "third" to 2, "3rd" to 2,
            "fourth" to 3, "4th" to 3, "fifth" to 4, "5th" to 4, "last" to -1,
        )
    }
}
