package com.example.locall.voice.agent.orchestrator

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import org.json.JSONObject

private const val TAG = "ToolRegistry"

object ToolRegistry {

    suspend fun resolve(context: Context, utterance: String, onStatus: (String) -> Unit): AgentTool {
        val t = utterance.trim()
        grammar(t)?.let { Log.i(TAG, "grammar -> ${it::class.simpleName}"); return it }
        return llm(context, t, onStatus)
    }

    fun grammar(original: String): AgentTool? {
        val t = original.lowercase().trim()

        if (t.matchesAny(BRIEF)) return AgentTool.BriefInbox
        if (t.matchesAny(PLAN_DAY)) return AgentTool.PlanDay
        if (t.matchesAny(LIST_TASKS)) return AgentTool.ListTasks

        prefix(t, original, ADD_TASK)?.let { body ->
            if (body.isNotBlank()) {
                val (title, due) = splitDue(body)
                return AgentTool.AddTask(title, due)
            }
        }
        prefix(t, original, COMPLETE)?.let { if (it.isNotBlank()) return AgentTool.CompleteTask(it) }
        prefix(t, original, DELETE_TASK)?.let { if (it.isNotBlank()) return AgentTool.DeleteTask(it) }

        POSTPONE.firstOrNull { t.startsWith(it) }?.let { p ->
            val rest = original.trim().drop(p.length).trim()
            val (ref, whenP) = splitOn(rest, listOf(" until ", " to ", " till ", " by "))
            if (ref.isNotBlank()) return AgentTool.PostponeTask(ref, whenP)
        }
        REMIND.firstOrNull { t.startsWith(it) }?.let { p ->
            val rest = original.trim().drop(p.length).trim()
            val (ref, whenP) = splitOn(rest, listOf(" at ", " on ", " in ", " tomorrow", " today"))
            val whenPhrase = if (whenP.isBlank() && (rest.contains("tomorrow") || rest.contains("today")))
                rest.substringAfterLast(' ') else whenP
            if (ref.isNotBlank()) return AgentTool.RemindTask(ref.ifBlank { rest }, whenPhrase.ifBlank { rest })
        }

        prefix(t, original, PLAN_GOAL)?.let { goal ->
            if (goal.isNotBlank()) return AgentTool.PlanGoal(goal, horizonFrom(t))
        }
        prefix(t, original, PROGRESS)?.let { return AgentTool.GoalProgress(it) }
        prefix(t, original, REPLY)?.let { ref ->
            if (ref.isNotBlank()) return AgentTool.ReplyTo(ref)
        }
        prefix(t, original, EXTRACT)?.let { if (it.isNotBlank()) return AgentTool.ExtractTasks(it) }
        prefix(t, original, DISCUSS)?.let { if (it.isNotBlank()) return AgentTool.DiscussMessage(it) }

        return null
    }

    private val SYSTEM = """
        You route a personal-assistant request to ONE tool as a JSON object. Valid tools:
        {"tool":"brief_inbox"}
        {"tool":"list_tasks"}
        {"tool":"plan_day"}
        {"tool":"add_task","title":"<text>","due":"<when or empty>","priority":"<low|normal|high>"}
        {"tool":"complete_task","ref":"<task words or number>"}
        {"tool":"delete_task","ref":"<task words or number>"}
        {"tool":"postpone_task","ref":"<task>","when":"<when>"}
        {"tool":"remind_task","ref":"<task>","when":"<when>"}
        {"tool":"plan_goal","goal":"<goal>","days":<number>}
        {"tool":"goal_progress","ref":"<goal words or empty>"}
        {"tool":"reply","ref":"<sender or subject>","body":"<what to say or empty>"}
        {"tool":"extract_tasks","ref":"<message sender or subject>"}
        {"tool":"discuss","ref":"<message sender or subject>"}
        {"tool":"chat","text":"<the question>"}
        Output ONLY the JSON object.
    """.trimIndent()

    private suspend fun llm(context: Context, utterance: String, onStatus: (String) -> Unit): AgentTool {
        return try {
            LlmBridge.ensureLoaded(context, onStatus)
            val decodeThreads = ThermalGovernor.decodeThreads(context)
            val raw = LlmBridge.generateText(
                systemPrompt = SYSTEM, userPrompt = utterance,
                temperature = 0.1f, maxTokens = 120, decodeThreads = decodeThreads,
            )
            Log.i(TAG, "llm route raw: $raw")
            parseJson(raw, utterance)
        } catch (t: Throwable) {
            Log.w(TAG, "llm route failed", t)
            AgentTool.Chat(utterance)
        }
    }

    fun parseJson(raw: String, utterance: String): AgentTool = try {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        val json = if (start in 0 until end) raw.substring(start, end + 1) else raw.trim()
        val o = JSONObject(json)
        when (o.getString("tool")) {
            "brief_inbox" -> AgentTool.BriefInbox
            "list_tasks" -> AgentTool.ListTasks
            "plan_day" -> AgentTool.PlanDay
            "add_task" -> AgentTool.AddTask(o.optString("title"), o.optString("due"), o.optString("priority"))
            "complete_task" -> AgentTool.CompleteTask(o.optString("ref"))
            "delete_task" -> AgentTool.DeleteTask(o.optString("ref"))
            "postpone_task" -> AgentTool.PostponeTask(o.optString("ref"), o.optString("when"))
            "remind_task" -> AgentTool.RemindTask(o.optString("ref"), o.optString("when"))
            "plan_goal" -> AgentTool.PlanGoal(o.optString("goal"), o.optInt("days", 7).coerceIn(1, 90))
            "goal_progress" -> AgentTool.GoalProgress(o.optString("ref"))
            "reply" -> AgentTool.ReplyTo(o.optString("ref"), o.optString("body"))
            "extract_tasks" -> AgentTool.ExtractTasks(o.optString("ref"))
            "discuss" -> AgentTool.DiscussMessage(o.optString("ref"))
            "chat" -> AgentTool.Chat(o.optString("text", utterance))
            else -> AgentTool.Chat(utterance)
        }
    } catch (e: Exception) {
        Log.w(TAG, "parseJson failed: $raw", e)
        AgentTool.Chat(utterance)
    }

    private fun String.matchesAny(set: Set<String>) = this in set || set.any { this == it }

    private fun prefix(normalized: String, original: String, prefixes: List<String>): String? {
        val p = prefixes.firstOrNull { normalized.startsWith(it) } ?: return null
        return original.trim().drop(p.length).trim()
    }

    private fun splitDue(text: String): Pair<String, String> {
        val (title, due) = splitOn(text, listOf(" by ", " due ", " before ", " on ", " tomorrow", " today", " tonight"))
        if (due.isBlank()) {
            val m = Regex("\\b(today|tonight|tomorrow|next week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")
                .find(text)
            if (m != null) return text.replace(m.value, "").trim() to m.value
        }
        return title to due
    }

    private fun splitOn(text: String, seps: List<String>): Pair<String, String> {
        for (s in seps) {
            val idx = text.indexOf(s)
            if (idx >= 0) return text.substring(0, idx).trim() to text.substring(idx + s.length).trim()
        }
        return text.trim() to ""
    }

    private fun horizonFrom(t: String): Int {
        Regex("(\\d+)\\s*day").find(t)?.let { return it.groupValues[1].toInt().coerceIn(1, 90) }
        Regex("(\\d+)\\s*week").find(t)?.let { return (it.groupValues[1].toInt() * 7).coerceIn(1, 90) }
        Regex("(\\d+)\\s*month").find(t)?.let { return (it.groupValues[1].toInt() * 30).coerceIn(1, 90) }
        return 7
    }

    private val BRIEF = setOf(
        "brief me", "brief my inbox", "briefing", "what's new", "whats new", "what is new",
        "catch me up", "any new messages", "check my inbox", "inbox briefing", "morning briefing",
        "what did i miss", "anything new",
    )
    private val PLAN_DAY = setOf(
        "plan my day", "plan the day", "plan today", "what should i do today",
        "what's on today", "whats on today", "organize my day", "organise my day",
    )
    private val LIST_TASKS = setOf(
        "my tasks", "list my tasks", "what are my tasks", "show my tasks", "what do i have to do",
        "what's on my list", "whats on my list", "open tasks", "to do list", "to-do list",
    )
    private val ADD_TASK = listOf("add a task to ", "add task ", "add a task ", "remind me to ", "i need to ", "create a task ", "new task ")
    private val COMPLETE = listOf("complete task ", "complete ", "mark done ", "mark complete ", "finish task ", "done with ", "i finished ", "i've done ", "ive done ")
    private val DELETE_TASK = listOf("delete task ", "delete the task ", "remove task ", "cancel task ")
    private val POSTPONE = listOf("postpone ", "push back ", "move ", "reschedule ", "delay ")
    private val REMIND = listOf("remind me about ", "set a reminder for ", "remind me of ", "remind me ")
    private val PLAN_GOAL = listOf("help me finish ", "help me with ", "make a plan for ", "plan for ", "create a plan for ", "prepare a study plan for ", "study plan for ", "i want to ", "plan to ")
    private val PROGRESS = listOf("how am i doing on ", "progress on ", "how's my progress", "hows my progress", "goal progress")
    private val REPLY = listOf("reply to ", "respond to ", "answer ")
    private val EXTRACT = listOf("extract tasks from ", "pull tasks from ", "any tasks in ", "turn into tasks ")
    private val DISCUSS = listOf("tell me about ", "discuss ", "what does ", "open the message from ", "read the message from ")
}
