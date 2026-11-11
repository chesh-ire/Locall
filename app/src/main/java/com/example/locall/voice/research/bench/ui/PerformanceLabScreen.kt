package com.example.locall.voice.research.bench.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.locall.ui.components.BrandHeader
import com.example.locall.voice.research.bench.BenchConfig
import com.example.locall.voice.research.bench.BenchRunner
import com.example.locall.voice.research.bench.ColdSingleShot
import com.example.locall.voice.research.bench.InferenceRecord
import com.example.locall.voice.research.bench.WorkflowRun
import com.example.locall.voice.research.bench.WorkloadType
import com.example.locall.voice.research.bench.data.BenchRepository
import com.example.locall.voice.research.bench.data.BenchRunEntity
import com.example.locall.voice.research.bench.workloads.DeepResearch
import com.example.locall.voice.research.chunkperf.ChunkPerfCompare
import com.example.locall.voice.research.chunkperf.ChunkPerfConfig
import com.example.locall.voice.research.chunkperf.ChunkPerfRunner
import com.example.locall.voice.research.chunkperf.data.ChunkPerfRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PerformanceLabScreen(modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        BrandHeader(
            title = "Perf Lab",
            subtitle = "Benchmark the on-device engine",
        )
        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            listOf("Run", "History", "105-Chunk").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        HorizontalDivider()
        when (tab) {
            0 -> RunTab()
            1 -> HistoryTab()
            else -> ChunkPerfTab()
        }
    }
}

@Composable
private fun RunTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var chunkCount by remember { mutableFloatStateOf(50f) }
    var groupSize by remember { mutableFloatStateOf(5f) }
    var contextBudget by remember { mutableFloatStateOf(1600f) }
    var reportOutput by remember { mutableFloatStateOf(512f) }
    var topK by remember { mutableFloatStateOf(8f) }

    var input by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf(WorkloadType.RECURSIVE_SUMMARISATION)) }

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<BenchRunner.Progress?>(null) }
    var lastRun by remember { mutableStateOf<WorkflowRun?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var evidence by remember { mutableStateOf<List<DeepResearch.Evidence>>(emptyList()) }
    var followUp by remember { mutableStateOf("") }

    var coldResult by remember { mutableStateOf<ColdSingleShot.Result?>(null) }

    fun config() = BenchConfig(
        chunkCount = chunkCount.toInt(),
        groupSize = groupSize.toInt(),
        contextBudgetTokens = contextBudget.toInt(),
        reportOutputTokens = reportOutput.toInt(),
        topK = topK.toInt(),
    )

    fun launch(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; progress = null
        scope.launch {
            try { block() } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabCard("Configuration") {
            SliderRow("Chunks retrieved", chunkCount, 10f, 60f, 50) { chunkCount = it }
            SliderRow("Group size", groupSize, 2f, 10f, 8) { groupSize = it }
            SliderRow("Context budget (tokens)", contextBudget, 256f, 1792f, 48) { contextBudget = it }
            SliderRow("Report output (tokens)", reportOutput, 128f, 900f, 0) { reportOutput = it }
            SliderRow("Top-K sources", topK, 2f, 20f, 18) { topK = it }
        }

        LabCard("Workloads") {
            Text("Select one or more; they run sequentially on the single model.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            FlowRowChips(selected) { type ->
                selected = if (type in selected) selected - type else selected + type
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("Question or goal (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && selected.isNotEmpty(),
                    onClick = {
                        val types = WorkloadType.entries.filter { it in selected }
                        launch {
                            val runs = BenchRunner.runMany(
                                context, types, config(),
                                inputs = types.associateWith { input.ifBlank { it.defaultFor() } },
                            ) { progress = it }
                            lastRun = runs.lastOrNull()
                        }
                    },
                ) { Text(if (selected.size > 1) "Run ${selected.size} selected" else "Run selected") }

                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        launch {
                            val (run, ev) = BenchRunner.deepResearch(context, config(), input) { progress = it }
                            lastRun = run; evidence = ev
                        }
                    },
                ) { Text("Deep Research") }
            }
        }

        LabCard("Cold single-shot (peak probe)") {
            Text(
                "One standalone generation (~${ColdSingleShot.MAX_TOKENS} tok, fixed prompt, no " +
                    "retrieval), then stops. Run from thermal status 0 to capture cold peak " +
                    "prefill/decode. Metrics also go to logcat tag \"ColdShot\".",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = !busy,
                onClick = { launch { coldResult = ColdSingleShot.run(context) } },
            ) { Text("Run cold single-shot") }
        }

        if (busy) LiveProgressCard(progress)
        error?.let { ErrorCard(it) }

        coldResult?.let { ColdShotCard(it) }

        lastRun?.let { run ->
            ResultCard(run)
            if (run.workload == WorkloadType.DEEP_RESEARCH && evidence.isNotEmpty()) {
                LabCard("Follow-up (reuses ${evidence.size} evidence item(s))") {
                    OutlinedTextField(
                        value = followUp, onValueChange = { followUp = it },
                        label = { Text("Ask a follow-up…") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = !busy && followUp.isNotBlank(),
                        onClick = {
                            val q = followUp
                            launch {
                                lastRun = BenchRunner.deepResearchFollowUp(
                                    context, config(), q, evidence,
                                ) { progress = it }
                            }
                            followUp = ""
                        },
                    ) { Text("Ask follow-up") }
                }
            }
        }
    }
}

@Composable
private fun ChunkPerfTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Idle. One tap runs all 105 chunks, auto-cooling as needed.") }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun runSession(config: ChunkPerfConfig) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                val result = ChunkPerfRunner.runSession(context, config) { p ->
                    status = "${p.completed}/${p.total} done · chunk #${p.index} · thermal ${p.thermalName}" +
                        (p.last?.let { " · ${fmt1(it.decodeTokPerSec)} t/s" } ?: "") +
                        (if (p.paused) " · PAUSED (${p.note})" else "")
                }
                output = when (result) {
                    is ChunkPerfRunner.SessionResult.Completed -> result.report.describe()
                    is ChunkPerfRunner.SessionResult.Paused ->
                        "Session paused: ${result.reason}\nProgress: ${result.completed}/${ChunkPerfRunner.TOTAL}\n" +
                            "Tap the same button again to resume from the next unfinished chunk."
                }
                status = "Done this session."
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabCard("105-Chunk Analysis") {
            Text(
                "Runs a frozen 105-chunk corpus through the single on-device model. One tap processes " +
                    "all remaining chunks, automatically waiting to cool whenever the device overheats " +
                    "(keep the app in the foreground). Progress persists across restarts; if killed, tap " +
                    "again to resume from the next unfinished chunk. Full numbers print to logcat (tag " +
                    "ChunkPerf) and appear below on completion.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = { runSession(ChunkPerfConfig.baseline()) },
                ) { Text("Baseline session") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { runSession(ChunkPerfConfig.optimized()) },
                ) { Text("Optimized session") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            val repo = ChunkPerfRepository.get(context)
                            output = repo.latestCompleted("baseline")?.let { repo.reportText(it.id) }
                                ?: "No completed baseline run yet."
                        }
                    },
                ) { Text("Show report") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            output = ChunkPerfCompare.compareLatest(context)
                                ?: "Need a completed baseline AND optimized run to compare."
                        }
                    },
                ) { Text("Compare") }
            }
        }

        if (busy) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Running…", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        } else {
            Text(status, style = MaterialTheme.typography.labelMedium)
        }

        error?.let { ErrorCard(it) }

        if (output.isNotBlank()) {
            LabCard("Result") { MonoBlock(output, maxHeight = 480) }
        }
    }
}

@Composable
private fun HistoryTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BenchRepository.get(context) }

    var runs by remember { mutableStateOf<List<BenchRunEntity>>(emptyList()) }
    var compare by remember { mutableStateOf(setOf<String>()) }

    suspend fun refresh() { runs = repo.history() }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("${runs.size} run(s)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                enabled = runs.isNotEmpty(),
                onClick = { scope.launch { repo.clearAll(); refresh(); compare = emptySet() } },
            ) { Text("Clear all") }
        }
        Spacer(Modifier.height(8.dp))

        if (compare.size == 2) {
            val a = runs.first { it.id == compare.first() }
            val b = runs.first { it.id == compare.last() }
            CompareCard(a, b) { compare = emptySet() }
            Spacer(Modifier.height(8.dp))
        } else if (runs.size >= 2) {
            Text("Tick two runs to compare.", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
        }

        if (runs.isEmpty()) {
            Text("No runs yet. Run a workload on the Run tab.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(runs, key = { it.id }) { run ->
                    RunHistoryCard(
                        run = run,
                        checked = run.id in compare,
                        onCheck = { on ->
                            compare = when {
                                on && compare.size < 2 -> compare + run.id
                                on -> compare
                                else -> compare - run.id
                            }
                        },
                        loadRecords = { repo.records(run.id) },
                        onDelete = { scope.launch { repo.delete(run.id); refresh(); compare = compare - run.id } },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveProgressCard(progress: BenchRunner.Progress?) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(progress?.workload?.label ?: "Starting…", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(progress?.stage ?: "…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            val r = progress?.lastRecord
            Text(
                "calls: ${progress?.calls ?: 0}" + if (r != null)
                    " · last: ${r.promptTokens}→${r.generatedTokens}t · " +
                        "prefill ${fmt0(r.prefillMs)}ms · decode ${fmt0(r.decodeMs)}ms (${fmt1(r.decodeTokensPerSec)} t/s)"
                else "",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ColdShotCard(r: ColdSingleShot.Result) {
    LabCard("Cold single-shot — result") {
        MetricGrid(
            "prefill" to "${fmt0(r.prefillMs)}ms",
            "prefill t/s" to fmt1(r.prefillTokPerSec),
            "decode" to "${fmt0(r.decodeMs)}ms",
            "decode t/s" to fmt1(r.decodeTokPerSec),
            "prompt tok" to "${r.promptTokens}",
            "gen tok" to "${r.generatedTokens}",
            "wall" to "${fmt1(r.wallMs / 1000.0)}s",
            "threads" to "${r.decodeThreads}",
            "par" to "${fmt1(r.par)}x",
            "cpu" to "${fmt1(r.cpuMsDelta / 1000.0)}s",
            "thermal" to "${r.thermalStart}→${r.thermalEnd}",
            "temp °C" to "${fmt1(r.tempStartC.toDouble())}→${fmt1(r.tempEndC.toDouble())}",
            "headroom" to "${fmt2(r.headroomStart)}→${fmt2(r.headroomEnd)}",
            "charge Δ" to "${r.chargeUahDelta} µAh",
            "peak RAM" to "${r.rssMb} MB",
        )
        Spacer(Modifier.height(8.dp))
        MonoBlock(r.text)
    }
}

@Composable
private fun ResultCard(run: WorkflowRun) {
    LabCard("${run.workload.label} — result") {
        MetricGrid(
            "duration" to "${fmt1(run.durationMs / 1000.0)}s",
            "calls" to "${run.inferenceCalls}",
            "decode" to "${fmt1(run.avgDecodeTokensPerSec)} t/s",
            "prompt tok" to "${run.totalPromptTokens}",
            "gen tok" to "${run.totalGeneratedTokens}",
            "peak RAM" to "${run.peakRssMb} MB",
            "thermal" to "${run.thermalStart}→${run.thermalEnd}",
            "prefill" to "${fmt1(run.totalPrefillMs / 1000.0)}s",
        )
        if (run.sources.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Sources: ${run.sources.joinToString(", ")}", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(8.dp))
        MonoBlock(run.output)
    }
}

@Composable
private fun RunHistoryCard(
    run: BenchRunEntity,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    loadRecords: suspend () -> List<InferenceRecord>,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf<List<InferenceRecord>>(emptyList()) }
    LaunchedEffect(expanded) { if (expanded && records.isEmpty()) records = loadRecords() }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheck)
                Column(Modifier.weight(1f)) {
                    Text(workloadLabel(run.workload), style = MaterialTheme.typography.titleSmall)
                    Text(dateFmt.format(Date(run.createdAtMs)), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Details") }
            }
            Text(
                "${fmt1(run.durationMs / 1000.0)}s · ${run.inferenceCalls} calls · " +
                    "${fmt1(run.avgDecodeTokPerSec)} t/s · ${run.peakRssMb}MB · " +
                    "${run.generatedTokens} gen / ${run.promptTokens} prompt tok",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("thermal ${run.thermalStart}→${run.thermalEnd} · ${run.config}",
                style = MaterialTheme.typography.labelSmall)

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (run.sources.isNotBlank()) {
                    Text("Sources: ${run.sources}", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                }
                Text("Per-call trace", style = MaterialTheme.typography.labelMedium)
                MonoBlock(renderTrace(records), maxHeight = 220)
                Spacer(Modifier.height(8.dp))
                Text("Output", style = MaterialTheme.typography.labelMedium)
                MonoBlock(run.output, maxHeight = 260)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDelete) { Text("Delete run") }
            }
        }
    }
}

@Composable
private fun CompareCard(a: BenchRunEntity, b: BenchRunEntity, onClear: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Compare", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
            CompareRow("", workloadLabel(a.workload), workloadLabel(b.workload), header = true)
            CompareRow("duration", "${fmt1(a.durationMs / 1000.0)}s", "${fmt1(b.durationMs / 1000.0)}s")
            CompareRow("calls", "${a.inferenceCalls}", "${b.inferenceCalls}")
            CompareRow("decode t/s", fmt1(a.avgDecodeTokPerSec), fmt1(b.avgDecodeTokPerSec))
            CompareRow("gen tok", "${a.generatedTokens}", "${b.generatedTokens}")
            CompareRow("prompt tok", "${a.promptTokens}", "${b.promptTokens}")
            CompareRow("peak RAM", "${a.peakRssMb}MB", "${b.peakRssMb}MB")
            CompareRow("thermal end", a.thermalEnd, b.thermalEnd)
        }
    }
}

@Composable
private fun CompareRow(label: String, left: String, right: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.1f))
        Text(left, style = style, modifier = Modifier.weight(1f))
        Text(right, style = style, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ErrorCard(message: String) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(message, Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun FlowRowChips(selected: Set<WorkloadType>, onToggle: (WorkloadType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WorkloadType.entries.forEach { type ->
            FilterChip(
                selected = type in selected,
                onClick = { onToggle(type) },
                label = { Text(type.label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, steps: Int, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${value.toInt()}", style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = min..max, steps = steps)
    }
}

@Composable
private fun MetricGrid(vararg pairs: Pair<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        pairs.toList().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k, style = MaterialTheme.typography.labelSmall)
                        Text(v, style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MonoBlock(text: String, maxHeight: Int = 320) {
    val scroll = rememberScrollState()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text.ifBlank { "(empty)" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(10.dp)
                .heightIn(max = maxHeight.dp)
                .verticalScroll(scroll)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun LabCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)

private fun fmt0(d: Double) = "%.0f".format(d)
private fun fmt1(d: Double) = "%.1f".format(d)
private fun fmt2(f: Float) = if (f.isNaN()) "n/a" else "%.2f".format(f)

private fun WorkloadType.defaultFor(): String =
    com.example.locall.voice.research.bench.workloads.Workload.defaultInput(this)

private fun workloadLabel(name: String): String =
    runCatching { WorkloadType.valueOf(name).label }.getOrDefault(name)

private fun renderTrace(records: List<InferenceRecord>): String {
    if (records.isEmpty()) return "(loading…)"
    val sb = StringBuilder("# | stage | prompt→gen | prefill | decode | t/s\n")
    records.forEach { r ->
        sb.append(
            "%d | %s | %d→%d | %.0fms | %.0fms | %.1f\n".format(
                r.ordinal, r.stage, r.promptTokens, r.generatedTokens, r.prefillMs, r.decodeMs, r.decodeTokensPerSec,
            ),
        )
    }
    return sb.toString()
}
