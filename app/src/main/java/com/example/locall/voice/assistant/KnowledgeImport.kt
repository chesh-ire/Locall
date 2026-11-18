package com.example.locall.voice.assistant

import android.content.Context
import android.net.Uri
import com.example.locall.voice.research.importer.TextImporter
import com.example.locall.voice.research.pipeline.KnowledgeBuilder

/**
 * Conversational document import. Picking a file needs the system Storage Access Framework picker, so
 * the attach (+) button in the input bar launches it; the chosen document is then ingested through the
 * existing [KnowledgeBuilder] and the result is reported inline in the conversation. All on-device.
 */
object KnowledgeImport {

    /** Ingest a picked .txt/.md document into the knowledge base, reporting progress in the chat. */
    fun importFile(context: Context, vm: AssistantViewModel, uri: Uri) {
        val app = context.applicationContext
        vm.runTask(userLabel = "Import a document", capability = "Knowledge") { onStatus ->
            onStatus("Reading file…")
            val doc = TextImporter.fromUri(app, uri, System.currentTimeMillis())
                ?: return@runTask "Couldn't read that file — it needs to be a UTF-8 text/markdown file."
            val res = KnowledgeBuilder.build(
                context = app,
                docs = listOf(doc),
                onProgress = { p -> onStatus("${p.phase} ${p.current}/${p.total}…") },
                onStatus = onStatus,
            )
            "Imported “${doc.title}” — ${res.chunks} passage${if (res.chunks == 1) "" else "s"} added to your " +
                "knowledge base. Ask me anything about it."
        }
    }
}
