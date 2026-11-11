package com.example.locall.voice.research.pipeline

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.research.data.KnowledgeRepository
import com.example.locall.voice.research.data.RetrievableChunk

private const val TAG = "ResearchAgent"

/**
 * Stages 4–5 of the pipeline: multi-step reasoning over the knowledge base, then a grounded answer
 * with citations. A question is classified into an aggregate shape (decisions / commitments / weekly
 * report → reduce over the structured insight fields) or a general retrieval shape (retrieve top-k →
 * re-read each source → compare/contradict → synthesize). Every step is a separate LLM pass on the
 * shared on-device handle; the answer is built ONLY from the retrieved material, each fact tagged with
 * its [source] so the final text can cite it.
 */
object ResearchAgent {

    private const val TOP_K = 6
    private const val PER_SOURCE_CHARS = 1600
    private const val REDUCE_BUDGET_CHARS = 4500
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    data class Answer(
        val text: String,
        val citations: List<String>,
        val sourcesConsidered: Int,
        val llmPasses: Int,
    )

    enum class Kind { DECISIONS, COMMITMENTS, WEEKLY_REPORT, GENERAL }

    suspend fun answer(
        context: Context,
        query: String,
        onStatus: (String) -> Unit = {},
    ): Answer {
        val repo = KnowledgeRepository.get(context)
        val corpus = repo.retrievables()
        if (corpus.isEmpty()) {
            return Answer(
                "Your knowledge base is empty. Import some emails or notes first, then ask again.",
                emptyList(), 0, 0,
            )
        }
        onStatus("Loading AI model…")
        LlmBridge.ensureLoaded(context, onStatus)

        return when (classify(query)) {
            Kind.DECISIONS -> aggregate(context, query, corpus, onStatus,
                field = { it.decisions }, kindLabel = "decisions", system = DECISIONS_SYSTEM)
            Kind.COMMITMENTS -> aggregate(context, query, corpus, onStatus,
                field = { it.commitments }, kindLabel = "commitments", system = COMMITMENTS_SYSTEM)
            Kind.WEEKLY_REPORT -> weeklyReport(context, query, corpus, onStatus)
            Kind.GENERAL -> general(context, query, corpus, onStatus)
        }
    }

    // ---------- classification ----------

    fun classify(query: String): Kind {
        val t = query.lowercase()
        return when {
            t.contains("weekly report") || t.contains("week report") ||
                (t.contains("report") && (t.contains("week") || t.contains("messages") || t.contains("notes"))) ||
                t.contains("this week") || t.contains("last week") -> Kind.WEEKLY_REPORT
            t.contains("commitment") || t.contains("promis") || t.contains("incomplete") ||
                t.contains("outstanding") || t.contains("still need") || t.contains("owe") -> Kind.COMMITMENTS
            t.contains("decision") || t.contains("decided") || t.contains("what did we decide") ->
                Kind.DECISIONS
            else -> Kind.GENERAL
        }
    }

    // ---------- general: retrieve → re-read → compare → synthesize ----------

    private suspend fun general(
        context: Context,
        query: String,
        corpus: List<RetrievableChunk>,
        onStatus: (String) -> Unit,
    ): Answer {
        onStatus("Searching your knowledge…")
        val top = KeywordRetriever.topK(query, corpus, TOP_K)
            .ifEmpty { corpus.sortedByDescending { it.createdAtMs }.take(TOP_K) }

        var passes = 0
        val extracts = ArrayList<Pair<String, String>>()   // citation -> relevant facts
        top.forEachIndexed { i, chunk ->
            onStatus("Reading source ${i + 1} of ${top.size}…")
            val facts = extractRelevant(context, query, chunk); passes++
            if (facts.isNotBlank() && !facts.equals("NONE", ignoreCase = true)) {
                extracts += chunk.citation to facts
            }
        }
        if (extracts.isEmpty()) {
            return Answer(
                "I read your most relevant documents but couldn't find anything that answers that.",
                emptyList(), top.size, passes,
            )
        }

        val tagged = extracts.joinToString("\n\n") { (cite, facts) -> "$cite\n$facts" }

        var compareNotes = ""
        if (extracts.size >= 2) {
            onStatus("Comparing sources…")
            compareNotes = runStage(context, COMPARE_SYSTEM, tagged, maxTokens = 160); passes++
        }

        onStatus("Writing answer…")
        val synthInput = buildString {
            append("Question: ").append(query.trim()).append("\n\nFacts:\n").append(tagged)
            if (compareNotes.isNotBlank()) append("\n\nCross-source notes:\n").append(compareNotes)
        }
        val text = runStage(context, SYNTH_SYSTEM, synthInput, maxTokens = 300)
            .ifBlank { tagged }
        passes++

        val citations = extracts.map { it.first }.distinct()
        Log.i(TAG, "general: ${top.size} retrieved, ${extracts.size} relevant, $passes passes")
        return Answer(text.trim(), citations, top.size, passes)
    }

    private suspend fun extractRelevant(context: Context, query: String, chunk: RetrievableChunk): String {
        val input = "Question: ${query.trim()}\n\nSource ${chunk.citation}:\n${chunk.text.take(PER_SOURCE_CHARS)}"
        return runStage(context, EXTRACT_SYSTEM, input, maxTokens = 150)
    }

    // ---------- aggregate (decisions / commitments) ----------

    private suspend fun aggregate(
        context: Context,
        query: String,
        corpus: List<RetrievableChunk>,
        onStatus: (String) -> Unit,
        field: (RetrievableChunk) -> List<String>,
        kindLabel: String,
        system: String,
    ): Answer {
        onStatus("Gathering $kindLabel…")
        val tagged = corpus.flatMap { c -> field(c).map { "${c.citation} $it" } }
        val citations = corpus.filter { field(it).isNotEmpty() }.map { it.citation }.distinct()
        if (tagged.isEmpty()) {
            return Answer("I didn't find any $kindLabel recorded in your documents.", emptyList(), corpus.size, 0)
        }
        onStatus("Consolidating $kindLabel…")
        val (material, condensePasses) = reduceToFit(context, tagged)
        val input = "Question: ${query.trim()}\n\nExtracted $kindLabel:\n$material"
        val text = runStage(context, system, input, maxTokens = 320).ifBlank { material }
        return Answer(text.trim(), citations, corpus.size, condensePasses + 1)
    }

    // ---------- weekly report ----------

    private suspend fun weeklyReport(
        context: Context,
        query: String,
        corpus: List<RetrievableChunk>,
        onStatus: (String) -> Unit,
    ): Answer {
        onStatus("Collecting this week's material…")
        val cutoff = System.currentTimeMillis() - WEEK_MS
        val recent = corpus.filter { it.createdAtMs >= cutoff }
            .ifEmpty { corpus.sortedByDescending { it.createdAtMs }.take(TOP_K) }

        val material = recent.flatMap { c ->
            buildList {
                if (c.summary.isNotBlank()) add("${c.citation} ${c.summary}")
                c.decisions.forEach { add("${c.citation} DECISION: $it") }
                c.commitments.forEach { add("${c.citation} COMMITMENT: $it") }
                c.dates.forEach { add("${c.citation} DATE: $it") }
            }
        }
        if (material.isEmpty()) {
            return Answer("There's nothing in your knowledge base to build a report from yet.", emptyList(), 0, 0)
        }
        onStatus("Reducing…")
        val (reduced, condensePasses) = reduceToFit(context, material)
        onStatus("Writing the report…")
        val input = "Request: ${query.trim()}\n\nMaterial from the user's recent documents:\n$reduced"
        val text = runStage(context, WEEKLY_REPORT_SYSTEM, input, maxTokens = 360).ifBlank { reduced }
        val citations = recent.map { it.citation }.distinct()
        return Answer(text.trim(), citations, recent.size, condensePasses + 1)
    }

    // ---------- helpers ----------

    /**
     * Join tagged facts into one string under [REDUCE_BUDGET_CHARS]. If they're too big for the
     * context window, condense them in groups first (one LLM pass per oversized group) — the same
     * hierarchical reduce the email summariser uses. Returns the material and how many LLM passes it cost.
     */
    private suspend fun reduceToFit(context: Context, facts: List<String>): Pair<String, Int> {
        var items = facts
        var passes = 0
        while (items.joinToString("\n").length > REDUCE_BUDGET_CHARS && items.size > 1) {
            val groups = packByBudget(items, REDUCE_BUDGET_CHARS / 2)
            if (groups.size >= items.size) break   // can't shrink further
            items = groups.map { group ->
                passes++
                runStage(context, CONDENSE_SYSTEM, group.joinToString("\n"), maxTokens = 220)
                    .ifBlank { group.joinToString("\n").take(REDUCE_BUDGET_CHARS / 2) }
            }
        }
        return items.joinToString("\n") to passes
    }

    private fun packByBudget(items: List<String>, budget: Int): List<List<String>> {
        val groups = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var len = 0
        for (it in items) {
            if (cur.isNotEmpty() && len + it.length > budget) {
                groups.add(cur); cur = ArrayList(); len = 0
            }
            cur.add(it); len += it.length + 1
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups
    }

    private suspend fun runStage(context: Context, system: String, user: String, maxTokens: Int): String {
        val decodeThreads = ThermalGovernor.decodeThreads(context)
        return try {
            LlmBridge.generateText(
                systemPrompt = system, userPrompt = user,
                temperature = 0.3f, maxTokens = maxTokens, decodeThreads = decodeThreads,
                tag = "research.qa",
            ).trim()
        } catch (t: Throwable) {
            Log.w(TAG, "runStage failed", t); ""
        }
    }

    // ---------- prompts ----------

    private val EXTRACT_SYSTEM = """
        You are given a question and ONE source excerpt from the user's documents. Extract ONLY the facts
        in the excerpt that help answer the question, as short bullet points. If the excerpt contains
        nothing relevant, reply with exactly: NONE. Never add information that isn't in the excerpt.
    """.trimIndent()

    private val COMPARE_SYSTEM = """
        You are given facts extracted from several of the user's documents, each tagged with its source in
        [brackets]. In a few short lines, note where the sources AGREE and where they CONTRADICT each other.
        If there are no contradictions, say so. Keep the [source] tags. Use only the given facts.
    """.trimIndent()

    private val SYNTH_SYSTEM = """
        You answer the user's question using ONLY the provided facts, which are each tagged with their
        source in [brackets]. Write a clear, direct answer and cite the sources you used by their
        [bracketed] tag. If the facts don't fully answer the question, say what is missing. Never invent
        facts or sources.
    """.trimIndent()

    private val DECISIONS_SYSTEM = """
        You are given decisions extracted from the user's documents, each tagged with its source in
        [brackets]. Consolidate them into a clear list for the user, merging duplicates and keeping a
        [source] tag on each decision. Use ONLY the given material; never invent decisions.
    """.trimIndent()

    private val COMMITMENTS_SYSTEM = """
        You are given commitments and promises extracted from the user's documents, each tagged with its
        source in [brackets]. List the commitments, keeping the [source] tag on each. Completion cannot be
        verified from the documents unless the text says it was done — treat the rest as still outstanding
        and group them under "Still outstanding". Never invent commitments.
    """.trimIndent()

    private val WEEKLY_REPORT_SYSTEM = """
        You write a concise weekly report from the user's recent documents. You are given summaries,
        decisions, commitments and dates, each tagged with its source in [brackets]. Produce short
        sections — Highlights, Decisions, Commitments / next steps, Upcoming dates — keeping [source] tags.
        Use ONLY the provided material; never invent anything.
    """.trimIndent()

    private val CONDENSE_SYSTEM = """
        Condense these tagged facts into fewer lines, preserving the [source] tags and the key content.
        Do not add anything new.
    """.trimIndent()
}
