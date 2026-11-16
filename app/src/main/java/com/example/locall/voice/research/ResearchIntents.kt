package com.example.locall.voice.research

/**
 * Grammar gate for the knowledge/research agent. Triggers on questions that span the user's whole
 * corpus ("summarise everything about my Android project", "what decisions have we made", "prepare a
 * weekly report"), plus knowledge-base management now that the Knowledge screen is gone: importing
 * emails / notes / app data, listing documents, and clearing the base. Checked AFTER the email gate so
 * email-specific asks still go to Gmail.
 */
object ResearchIntents {

    private val OPEN_APP_PREFIXES = listOf("open ", "launch ", "start ", "go to ")

    private val KEYWORDS = listOf(
        "knowledge base", "my documents", "my notes and", "across my", "my research",
        "in my documents", "according to my",
    )

    private val PREFIXES = listOf(
        "summarise everything", "summarize everything", "summarise all my", "summarize all my",
        "what decisions have", "what decisions did", "what did we decide",
        "what commitments", "what have i committed", "what did i promise",
        "prepare a weekly report", "weekly report", "prepare a report", "give me a weekly",
        "compare the", "compare my", "compare these",
        "ask my knowledge", "search my knowledge", "search my notes", "search my documents",
    )

    // --- knowledge-base management ---
    private val IMPORT_NOTE_PREFIXES = listOf(
        "import this note", "add this note to my knowledge", "save this note", "remember this document",
        "add this document", "import this document",
    )
    private val IMPORT_EMAIL = listOf(
        "import my emails", "import my email", "import my inbox", "import my last",
        "add my emails to my knowledge", "import emails into my knowledge", "import my recent emails",
    )
    private val IMPORT_APP_DATA = listOf(
        "import my app data", "import my memories and music", "add my app data",
        "import my memories", "import my listening history",
    )
    private val LIST_DOCS = listOf(
        "list my documents", "what documents do i have", "show my documents", "show my knowledge base",
        "what's in my knowledge base", "what is in my knowledge base", "how many documents",
    )
    private val CLEAR_KB = listOf(
        "clear my knowledge base", "clear my documents", "delete my documents", "delete my knowledge base",
        "empty my knowledge base", "wipe my knowledge base", "forget my documents",
    )

    fun matches(normalized: String): Boolean {
        val t = normalized.trim()
        if (OPEN_APP_PREFIXES.any { t.startsWith(it) }) return false
        if (PREFIXES.any { t.startsWith(it) }) return true
        if (IMPORT_NOTE_PREFIXES.any { t.startsWith(it) }) return true
        if (IMPORT_EMAIL.any { t.startsWith(it) || t.contains(it) }) return true
        if (IMPORT_APP_DATA.any { t.contains(it) }) return true
        if (LIST_DOCS.any { t.startsWith(it) || t.contains(it) }) return true
        if (CLEAR_KB.any { t.startsWith(it) || t.contains(it) }) return true
        return KEYWORDS.any { t.contains(it) }
    }

    sealed interface Action {
        data class Ask(val query: String) : Action
        data class ImportEmail(val count: Int) : Action
        data object ImportAppData : Action
        data class ImportNote(val title: String, val text: String) : Action
        data object ListDocs : Action
        data object ClearKb : Action
    }

    /** Resolve the knowledge sub-action from the raw [original] transcript. Defaults to [Action.Ask]. */
    fun resolve(original: String): Action {
        val t = original.trim()
        val n = t.lowercase()
        IMPORT_NOTE_PREFIXES.firstOrNull { n.startsWith(it) }?.let { prefix ->
            val rest = t.drop(prefix.length).trimStart(':', ' ', '-', ',')
            if (rest.isNotBlank()) return Action.ImportNote(title = "", text = rest)
        }
        if (CLEAR_KB.any { n.startsWith(it) || n.contains(it) }) return Action.ClearKb
        if (LIST_DOCS.any { n.startsWith(it) || n.contains(it) }) return Action.ListDocs
        if (IMPORT_APP_DATA.any { n.contains(it) }) return Action.ImportAppData
        if (IMPORT_EMAIL.any { n.startsWith(it) || n.contains(it) }) {
            val count = Regex("\\b(\\d{1,3})\\b").find(n)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 50
            return Action.ImportEmail(count)
        }
        return Action.Ask(t)
    }
}
