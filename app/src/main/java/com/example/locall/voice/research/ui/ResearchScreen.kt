package com.example.locall.voice.research.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.locall.ui.components.BrandHeader
import com.example.locall.ui.components.SectionCard
import com.example.locall.voice.research.data.DocumentEntity
import com.example.locall.voice.research.data.KnowledgeRepository
import com.example.locall.voice.research.importer.AppDataImporter
import com.example.locall.voice.research.importer.GmailImporter
import com.example.locall.voice.research.importer.RawDocument
import com.example.locall.voice.research.importer.TextImporter
import com.example.locall.voice.research.pipeline.KnowledgeBuilder
import com.example.locall.voice.research.pipeline.ResearchAgent
import kotlinx.coroutines.launch

@Composable
fun ResearchScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { KnowledgeRepository.get(context) }

    var tab by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var documents by remember { mutableStateOf<List<DocumentEntity>>(emptyList()) }

    suspend fun refreshDocs() { documents = repo.documents() }
    LaunchedEffect(Unit) { refreshDocs() }

    fun runImport(label: String, produce: suspend () -> List<RawDocument>) {
        if (busy) return
        busy = true; status = "$label…"; progress = null
        scope.launch {
            try {
                val docs = produce()
                if (docs.isEmpty()) { status = "Nothing to import."; return@launch }
                val result = KnowledgeBuilder.build(
                    context, docs,
                    onProgress = { p -> progress = p.current to p.total; status = "Understanding: ${p.detail}" },
                    onStatus = { s -> status = s },
                )
                status = "Imported ${result.documents} document(s), ${result.chunks} chunk(s)."
                refreshDocs()
            } catch (t: Throwable) {
                status = "Import failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = false; progress = null
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        BrandHeader(
            title = "Knowledge",
            subtitle = "Personal research agent · on-device",
        )
        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            listOf("Import", "Knowledge", "Ask").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        if (busy || status.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                val p = progress
                if (busy) {
                    Spacer(Modifier.height(6.dp))
                    if (p != null && p.second > 0) {
                        LinearProgressIndicator(
                            progress = { p.first.toFloat() / p.second },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${p.first} / ${p.second} chunks", style = MaterialTheme.typography.labelSmall)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        HorizontalDivider()
        when (tab) {
            0 -> ImportTab(busy, onImport = ::runImport, context = context)
            1 -> KnowledgeTab(
                documents = documents,
                busy = busy,
                onClear = {
                    scope.launch { repo.clearAll(); refreshDocs(); status = "Knowledge base cleared." }
                },
            )
            else -> AskTab(busy = busy, setBusy = { busy = it })
        }
    }
}

@Composable
private fun ImportTab(
    busy: Boolean,
    onImport: (String, suspend () -> List<RawDocument>) -> Unit,
    context: android.content.Context,
) {
    var gmailCount by remember { mutableStateOf("50") }
    var noteTitle by remember { mutableStateOf("") }
    var noteBody by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImport("Reading file") {
                TextImporter.fromUri(context, uri, System.currentTimeMillis())?.let { listOf(it) } ?: emptyList()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("Gmail", leading = "✉️") {
            Text("Pull recent inbox emails into the knowledge base.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = gmailCount,
                    onValueChange = { gmailCount = it.filter(Char::isDigit).take(3) },
                    label = { Text("How many") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp),
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        val n = gmailCount.toIntOrNull()?.coerceIn(1, 100) ?: 50
                        onImport("Fetching $n emails") {
                            when (val r = GmailImporter(context).import(query = "in:inbox", max = n)) {
                                is GmailImporter.Result.Ok -> r.documents
                                is GmailImporter.Result.NeedsConsent ->
                                    throw IllegalStateException("Connect Gmail on the Assistant screen first")
                                is GmailImporter.Result.Error -> throw IllegalStateException(r.message)
                            }
                        }
                    },
                ) { Text("Import from Gmail") }
            }
        }

        SectionCard("Note or transcript", leading = "📝") {
            OutlinedTextField(
                value = noteTitle, onValueChange = { noteTitle = it },
                label = { Text("Title (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = noteBody, onValueChange = { noteBody = it },
                label = { Text("Paste text…") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    enabled = !busy && noteBody.isNotBlank(),
                    onClick = {
                        val title = noteTitle; val body = noteBody
                        onImport("Adding note") {
                            listOf(TextImporter.fromText(title, body, System.currentTimeMillis()))
                        }
                        noteTitle = ""; noteBody = ""
                    },
                ) { Text("Add note") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    enabled = !busy,
                    onClick = { filePicker.launch(arrayOf("text/*", "text/plain", "text/markdown")) },
                ) { Text("Pick .txt / .md") }
            }
        }

        SectionCard("App data", leading = "📱") {
            Text("Fold your saved memories and listening history into the knowledge base.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !busy,
                onClick = { onImport("Importing app data") { AppDataImporter(context).import() } },
            ) { Text("Import app data") }
        }
    }
}

@Composable
private fun KnowledgeTab(documents: List<DocumentEntity>, busy: Boolean, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("${documents.size} document(s)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(enabled = !busy && documents.isNotEmpty(), onClick = onClear) { Text("Clear all") }
        }
        Spacer(Modifier.height(8.dp))
        if (documents.isEmpty()) {
            Text("Nothing imported yet. Use the Import tab.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(documents, key = { it.id }) { doc ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(doc.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${doc.sourceType} · ${doc.chunkCount} chunk(s) · ${doc.author}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AskTab(busy: Boolean, setBusy: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var askStatus by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var citations by remember { mutableStateOf<List<String>>(emptyList()) }
    var meta by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = question, onValueChange = { question = it },
            label = { Text("Ask across your documents…") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
        )
        Button(
            enabled = !busy && question.isNotBlank(),
            onClick = {
                setBusy(true); askStatus = "Thinking…"; answer = ""; citations = emptyList(); meta = ""
                val q = question
                scope.launch {
                    try {
                        val a = ResearchAgent.answer(context, q) { s -> askStatus = s }
                        answer = a.text; citations = a.citations
                        meta = "${a.llmPasses} inference pass(es) over ${a.sourcesConsidered} source(s)"
                        askStatus = ""
                    } catch (t: Throwable) {
                        askStatus = "Failed: ${t.javaClass.simpleName}"
                    } finally {
                        setBusy(false)
                    }
                }
            },
        ) { Text("Ask") }

        if (busy && askStatus.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(askStatus, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (answer.isNotEmpty()) {
            HorizontalDivider()
            Text(answer, style = MaterialTheme.typography.bodyMedium)
            if (citations.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Sources: ${citations.joinToString(", ")}", style = MaterialTheme.typography.labelMedium)
            }
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall)
        }
    }
}

