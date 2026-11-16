package com.example.locall.voice.research

import android.content.Context
import android.util.Log
import com.example.locall.voice.research.data.KnowledgeRepository
import com.example.locall.voice.research.importer.AppDataImporter
import com.example.locall.voice.research.importer.GmailImporter
import com.example.locall.voice.research.importer.TextImporter
import com.example.locall.voice.research.pipeline.KnowledgeBuilder
import com.example.locall.voice.research.pipeline.ResearchAgent

private const val TAG = "ResearchCommandHandler"

/**
 * Voice/chat entry point to the knowledge base. Besides answering questions via the multi-step
 * [ResearchAgent], it now also handles knowledge-base management conversationally (the old Knowledge
 * screen is gone): importing emails / notes / app data, listing documents, and clearing the base.
 * [contextBlock] carries a prior turn's result into a referential question.
 */
class ResearchCommandHandler(appContext: Context) {

    private val app = appContext.applicationContext

    suspend fun handle(
        context: Context,
        transcript: String,
        onStatus: (String) -> Unit,
        speak: (String) -> Unit,
        confirm: suspend (String) -> Boolean = { false },
        contextBlock: String = "",
    ): String = try {
        when (val action = ResearchIntents.resolve(transcript)) {
            is ResearchIntents.Action.ListDocs -> listDocs(context)
            is ResearchIntents.Action.ClearKb -> clearKb(context, confirm, speak)
            is ResearchIntents.Action.ImportEmail -> importEmail(context, action.count, onStatus, speak)
            is ResearchIntents.Action.ImportAppData -> importAppData(context, onStatus, speak)
            is ResearchIntents.Action.ImportNote -> importNote(context, action.text, onStatus, speak)
            is ResearchIntents.Action.Ask -> ask(context, action.query, onStatus, speak, contextBlock)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "research failed", t)
        "⚠️ research failed: ${t.javaClass.simpleName}"
    }

    private suspend fun ask(
        context: Context,
        query: String,
        onStatus: (String) -> Unit,
        speak: (String) -> Unit,
        contextBlock: String,
    ): String {
        val answer = ResearchAgent.answer(context, query.trim(), onStatus, contextBlock)
        Log.i(TAG, "answer: ${answer.llmPasses} passes over ${answer.sourcesConsidered} source(s)")
        speak(forSpeech(answer.text))
        return buildString {
            append("🧠 ").append(answer.text)
            if (answer.citations.isNotEmpty()) append("\n\nSources: ").append(answer.citations.joinToString(", "))
        }
    }

    private suspend fun listDocs(context: Context): String {
        val docs = KnowledgeRepository.get(context).documents()
        if (docs.isEmpty()) return "📚 Your knowledge base is empty. Import emails, a note or a file to get started."
        return buildString {
            append("📚 ${docs.size} document${plural(docs.size)} in your knowledge base:\n")
            docs.take(30).forEach { append("• ${it.title}  ·  ${it.sourceType}, ${it.chunkCount} chunk${plural(it.chunkCount)}\n") }
            if (docs.size > 30) append("…and ${docs.size - 30} more")
        }.trim()
    }

    private suspend fun clearKb(context: Context, confirm: suspend (String) -> Boolean, speak: (String) -> Unit): String {
        val repo = KnowledgeRepository.get(context)
        val n = repo.documentCount()
        if (n == 0) return "📚 Nothing to clear — your knowledge base is already empty."
        if (!confirm("Delete all $n document${plural(n)} from your knowledge base?")) return "❌ Cancelled — nothing was deleted."
        repo.clearAll()
        speak("Cleared your knowledge base.")
        return "🗑️ Cleared $n document${plural(n)} from your knowledge base."
    }

    private suspend fun importEmail(
        context: Context,
        count: Int,
        onStatus: (String) -> Unit,
        speak: (String) -> Unit,
    ): String {
        onStatus("Connecting to Gmail…")
        return when (val r = GmailImporter(context).import(
            max = count,
            onProgress = { i, total -> onStatus("Fetching email $i of $total…") },
        )) {
            is GmailImporter.Result.NeedsConsent -> "📧 Connect Gmail first — open the menu (top-left) and tap Connect."
            is GmailImporter.Result.Error -> "⚠️ Couldn't import email: ${r.message}"
            is GmailImporter.Result.Ok -> {
                if (r.documents.isEmpty()) return "📭 No emails found to import."
                val res = KnowledgeBuilder.build(context, r.documents, onStatus = onStatus)
                speak("Imported ${res.documents} emails into your knowledge base.")
                "📚 Imported ${res.documents} email${plural(res.documents)} (${res.chunks} passage${plural(res.chunks)}) into your knowledge base."
            }
        }
    }

    private suspend fun importAppData(context: Context, onStatus: (String) -> Unit, speak: (String) -> Unit): String {
        onStatus("Collecting your app data…")
        val docs = AppDataImporter(context).import()
        if (docs.isEmpty()) return "📭 No saved memories or listening history to import yet."
        val res = KnowledgeBuilder.build(context, docs, onStatus = onStatus)
        speak("Added your app data to the knowledge base.")
        return "📚 Imported ${res.documents} item${plural(res.documents)} (${res.chunks} passage${plural(res.chunks)}) into your knowledge base."
    }

    private suspend fun importNote(context: Context, text: String, onStatus: (String) -> Unit, speak: (String) -> Unit): String {
        if (text.isBlank()) return "✍️ Tell me the note to save, e.g. “import this note: …”."
        val doc = TextImporter.fromText(title = "", text = text, createdAtMs = System.currentTimeMillis())
        val res = KnowledgeBuilder.build(context, listOf(doc), onStatus = onStatus)
        speak("Saved that note to your knowledge base.")
        return "📚 Saved “${doc.title}” (${res.chunks} passage${plural(res.chunks)}) to your knowledge base."
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    /** Drop the "[Source]" citation tags for the spoken version — they read awkwardly aloud. */
    private fun forSpeech(text: String): String =
        text.replace(Regex("\\[[^\\]]*\\]"), "").replace(Regex("[ \\t]{2,}"), " ").trim()
}
