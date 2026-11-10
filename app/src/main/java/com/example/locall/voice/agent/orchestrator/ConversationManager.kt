package com.example.locall.voice.agent.orchestrator

class ConversationManager(private val maxTurns: Int = 6) {

    data class Turn(val user: String, val agent: String)

    private val turns = ArrayDeque<Turn>()

    var focusInboxId: String? = null
        private set

    var focusGoalId: String? = null
        private set

    var lastTaskIds: List<String> = emptyList()
        private set

    fun record(user: String, agent: String) {
        turns.addLast(Turn(user.trim(), agent.trim()))
        while (turns.size > maxTurns) turns.removeFirst()
    }

    fun focusMessage(id: String?) { focusInboxId = id }
    fun focusGoal(id: String?) { focusGoalId = id }
    fun rememberTaskList(ids: List<String>) { lastTaskIds = ids }

    fun recentContext(): String {
        if (turns.isEmpty()) return ""
        return buildString {
            append("Recent conversation:\n")
            turns.forEach { append("User: ").append(it.user.take(160)).append('\n')
                append("You: ").append(it.agent.take(160)).append('\n') }
        }
    }

    fun reset() {
        turns.clear(); focusInboxId = null; focusGoalId = null; lastTaskIds = emptyList()
    }
}
