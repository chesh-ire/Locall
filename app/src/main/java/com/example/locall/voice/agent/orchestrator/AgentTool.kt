package com.example.locall.voice.agent.orchestrator

sealed interface AgentTool {

    data object BriefInbox : AgentTool

    data object ListTasks : AgentTool

    data object PlanDay : AgentTool

    data class AddTask(val title: String, val duePhrase: String = "", val priorityWord: String = "") : AgentTool

    data class CompleteTask(val ref: String) : AgentTool
    data class DeleteTask(val ref: String) : AgentTool
    data class PostponeTask(val ref: String, val whenPhrase: String) : AgentTool
    data class RemindTask(val ref: String, val whenPhrase: String) : AgentTool

    data class PlanGoal(val goal: String, val horizonDays: Int = 7) : AgentTool

    data class GoalProgress(val ref: String) : AgentTool

    data class ReplyTo(val ref: String, val body: String = "") : AgentTool

    data class ExtractTasks(val ref: String) : AgentTool

    data class DiscussMessage(val ref: String) : AgentTool

    data class Chat(val text: String) : AgentTool

    data class Unknown(val text: String) : AgentTool
}
