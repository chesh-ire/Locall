package com.example.locall.voice.agent.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.example.locall.ui.components.BrandBackground
import com.example.locall.ui.components.BrandHeader
import com.example.locall.ui.components.SectionCard
import com.example.locall.voice.agent.data.GoalEntity
import com.example.locall.voice.agent.data.GoalManager
import com.example.locall.voice.agent.data.MessageClass
import com.example.locall.voice.agent.data.TaskEntity
import com.example.locall.voice.agent.data.TaskManager
import com.example.locall.voice.agent.data.TaskStatus
import com.example.locall.voice.agent.follow.FollowUpManager
import com.example.locall.voice.agent.inbox.InboxManager
import com.example.locall.voice.agent.orchestrator.ContextBuilder
import com.example.locall.voice.agent.planning.PlanningEngine
import com.example.locall.voice.agent.planning.ProgressTracker
import com.example.locall.voice.agent.util.DateParser
import kotlinx.coroutines.launch

@Composable
fun TodayScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tasks = remember { TaskManager.get(context) }
    val goals = remember { GoalManager.get(context) }
    val inbox = remember { InboxManager.get(context) }
    val planner = remember { PlanningEngine(tasks) }
    val tracker = remember { ProgressTracker(tasks, goals) }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var notifAccess by remember { mutableStateOf(hasNotificationAccess(context)) }

    var briefing by remember { mutableStateOf<List<InboxManager.BriefItem>>(emptyList()) }
    var openTasks by remember { mutableStateOf<List<TaskEntity>>(emptyList()) }
    var activeGoals by remember { mutableStateOf<List<GoalEntity>>(emptyList()) }
    var goalProgress by remember { mutableStateOf<Map<String, ProgressTracker.Progress>>(emptyMap()) }

    var newTask by remember { mutableStateOf("") }
    var newGoal by remember { mutableStateOf("") }
    var planPreview by remember { mutableStateOf<PlanningEngine.Plan?>(null) }

    suspend fun refresh() {
        openTasks = tasks.open()
        activeGoals = goals.active()
        goalProgress = activeGoals.associate { it.id to tracker.forGoal(it.id) }
    }
    LaunchedEffect(Unit) { refresh() }

    fun run(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; status = label
        scope.launch {
            try { block() } catch (t: Throwable) { status = "Failed: ${t.javaClass.simpleName}" }
            finally { busy = false }
        }
    }

    BrandBackground {
        Column(modifier.fillMaxSize()) {
            BrandHeader(title = "Today", subtitle = "Inbox · tasks · plans — on-device")

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (status.isNotEmpty()) {
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (!notifAccess) {
                    SectionCard(title = "Message access", leading = "🔔",
                        subtitle = "Let LocaLL include WhatsApp & SMS in your briefing") {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("Enable notification access") }
                    }
                }

                SectionCard(title = "Inbox briefing", leading = "📨") {
                    Button(
                        onClick = {
                            run("Reviewing your inbox…") {
                                briefing = inbox.buildBriefing { s -> status = s }.items
                                notifAccess = hasNotificationAccess(context)
                                status = if (briefing.isEmpty()) "You're all caught up." else ""
                            }
                        },
                        enabled = !busy,
                    ) { Text(if (busy) "Working…" else "Brief me") }

                    if (briefing.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        briefing.forEach { item ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text("[${MessageClass.label(item.classification)}] ${item.sender}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(item.summary, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }

                SectionCard(title = "Tasks", leading = "✅") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTask, onValueChange = { newTask = it },
                            placeholder = { Text("Add a task (e.g. call Sam tomorrow)") },
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            enabled = newTask.isNotBlank() && !busy,
                            onClick = {
                                val text = newTask.trim(); newTask = ""
                                run("Adding task…") {
                                    val due = DateParser.parse(text)
                                    val t = tasks.create(title = text, dueAtMs = due)
                                    if (due != null) FollowUpManager.schedule(context, t.id, t.title, due)
                                    status = ""; refresh()
                                }
                            },
                        ) { Text("Add") }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (openTasks.isEmpty()) {
                        Text("No open tasks.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        openTasks.forEach { t -> TaskRow(t) { done ->
                            run(if (done) "Completing…" else "Removing…") {
                                if (done) { tasks.complete(t.id); t.goalId?.let { tracker.maybeCompleteGoal(it) } }
                                else tasks.delete(t.id)
                                FollowUpManager.cancel(context, t.id)
                                status = ""; refresh()
                            }
                        } }
                    }
                }

                SectionCard(title = "Goals & plans", leading = "🎯") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newGoal, onValueChange = { newGoal = it },
                            placeholder = { Text("A goal to plan (e.g. study plan for 30 days)") },
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            enabled = newGoal.isNotBlank() && !busy,
                            onClick = {
                                val goalText = newGoal.trim()
                                run("Planning…") {
                                    planPreview = planner.propose(context, goalText, "", horizonFrom(goalText)) { s -> status = s }
                                    status = ""
                                }
                            },
                        ) { Text("Plan") }
                    }

                    planPreview?.let { plan ->
                        Spacer(Modifier.height(10.dp))
                        Text("Proposed plan (${plan.steps.size} steps):",
                            style = MaterialTheme.typography.labelMedium)
                        plan.steps.forEachIndexed { i, s ->
                            Text("${i + 1}. ${s.title}  ·  day ${s.dayOffset}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(enabled = !busy, onClick = {
                                val goalText = newGoal.trim().ifBlank { plan.goalTitle }
                                run("Saving plan…") {
                                    val goal = goals.create(goalText, "")
                                    val created = planner.commit(goal, plan)
                                    created.forEach { ct -> ct.dueAtMs?.let { FollowUpManager.schedule(context, ct.id, ct.title, it) } }
                                    planPreview = null; newGoal = ""; status = "Plan saved with ${created.size} tasks."
                                    refresh()
                                }
                            }) { Text("Save plan") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { planPreview = null }) { Text("Discard") }
                        }
                    }

                    if (activeGoals.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        activeGoals.forEach { g ->
                            val p = goalProgress[g.id]
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(g.title, style = MaterialTheme.typography.titleSmall)
                                if (p != null) {
                                    LinearProgressIndicator(
                                        progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    )
                                    Text("${p.done}/${p.total} done · ${p.pct}%" +
                                        (p.nextTask?.let { " · next: ${it.title}" } ?: "") +
                                        (if (p.needsReplan) " · ${p.overdue} overdue" else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskEntity, onAction: (done: Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = task.status == TaskStatus.DONE, onCheckedChange = { onAction(true) })
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (task.status == TaskStatus.DONE) TextDecoration.LineThrough else null)
            task.dueAtMs?.let {
                Text("due ${ContextBuilder.fmtDate(it)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = { onAction(false) }) { Text("Delete") }
    }
}

private fun hasNotificationAccess(context: android.content.Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun horizonFrom(t: String): Int {
    val s = t.lowercase()
    Regex("(\\d+)\\s*day").find(s)?.let { return it.groupValues[1].toInt().coerceIn(1, 90) }
    Regex("(\\d+)\\s*week").find(s)?.let { return (it.groupValues[1].toInt() * 7).coerceIn(1, 90) }
    Regex("(\\d+)\\s*month").find(s)?.let { return (it.groupValues[1].toInt() * 30).coerceIn(1, 90) }
    return 7
}
