package com.example.locall.voice.research.bench

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-lifetime state for a long-running benchmark executed by [BenchmarkService].
 *
 * The benchmark must survive the screen turning off and the Activity being backgrounded, so it cannot
 * live in a Composable's `rememberCoroutineScope` (which is cancelled when composition leaves / the UI
 * is torn down). The foreground service owns the work and publishes progress + the final report here;
 * the Perf Lab screen only observes these flows, so it can come and go freely while the run continues.
 */
object BenchmarkRunState {

    enum class Kind(val label: String) {
        SUSTAINED_COMPARE("Sustained 3-vs-4 compare"),
        DECODE_SWEEP("Decode thread sweep"),
        PACED_COMPARE("Paced decode compare"),
        PREFILL_CAP_COMPARE("Prefill-cap compare"),
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _kind = MutableStateFlow<Kind?>(null)
    val kind: StateFlow<Kind?> = _kind.asStateFlow()

    /** Live progress line (null when not running). */
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    /** Final human-readable report of the most recent run (persists after completion for display). */
    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    fun onStart(k: Kind) {
        _kind.value = k
        _report.value = null
        _progress.value = "Starting ${k.label}…"
        _running.value = true
    }

    fun onProgress(line: String) {
        _progress.value = line
    }

    /** Called exactly once when the run finishes (success, abort, or failure); [report] is the text to show. */
    fun onFinished(report: String) {
        _report.value = report
        _progress.value = null
        _running.value = false
    }
}
