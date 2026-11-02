package com.example.locall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.locall.ui.theme.LocaLLTheme
import com.example.locall.voice.VoiceOrchestrator
import com.example.locall.voice.WakeWordService
import com.example.locall.voice.email.GmailAuth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var orchestrator: VoiceOrchestrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orchestrator = VoiceOrchestrator(applicationContext)
        orchestrator.init()
        enableEdgeToEdge()
        setContent {
            LocaLLTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { pad ->
                    VoiceScreen(
                        orchestrator = orchestrator,
                        modifier = Modifier.padding(pad),
                        openA11ySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orchestrator.init()
    }

    override fun onDestroy() {
        orchestrator.destroy()
        super.onDestroy()
    }
}

private fun hasMicPermission(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

@Composable
private fun VoiceScreen(
    orchestrator: VoiceOrchestrator,
    modifier: Modifier = Modifier,
    openA11ySettings: () -> Unit,
) {
    val ui by orchestrator.ui.collectAsState()
    val context = LocalContext.current
    var micGranted by remember { mutableStateOf(hasMicPermission(context)) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {  }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("LocaLL — offline voice control", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(ui.status, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(24.dp))

        if (!micGranted) {
            Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Grant microphone")
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = openA11ySettings) { Text("Enable Accessibility (to type/control)") }

        Spacer(Modifier.height(8.dp))

        val scope = rememberCoroutineScope()
        var gmailConnected by remember { mutableStateOf(GmailAuth.isConnected) }
        var gmailError by remember { mutableStateOf<String?>(null) }
        val consentLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            when (val r = GmailAuth.resultFromConsent(context, result.data)) {
                is GmailAuth.Result.Token -> { gmailConnected = true; gmailError = null }
                is GmailAuth.Result.Error -> gmailError = r.message
                is GmailAuth.Result.NeedsConsent -> gmailError = "consent not completed"
            }
        }
        OutlinedButton(
            enabled = !gmailConnected,
            onClick = {
                scope.launch {
                    when (val r = GmailAuth.acquire(context)) {
                        is GmailAuth.Result.Token -> { gmailConnected = true; gmailError = null }
                        is GmailAuth.Result.NeedsConsent ->
                            consentLauncher.launch(IntentSenderRequest.Builder(r.pendingIntent).build())
                        is GmailAuth.Result.Error -> gmailError = r.message
                    }
                }
            },
        ) { Text(if (gmailConnected) "Gmail connected ✓" else "Connect Gmail (to read/clear email)") }
        gmailError?.let {
            Spacer(Modifier.height(4.dp))
            Text("Gmail: $it", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(16.dp))

        var alwaysOn by remember { mutableStateOf(WakeWordService.isRunning) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Always-on (\"hey locall\")")
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = alwaysOn,
                enabled = micGranted,
                onCheckedChange = { on ->
                    alwaysOn = on
                    val intent = Intent(context, WakeWordService::class.java).apply {
                        action = if (on) WakeWordService.ACTION_START else WakeWordService.ACTION_STOP
                    }
                    if (on) ContextCompat.startForegroundService(context, intent)
                    else context.startService(intent)
                },
            )
        }

        Spacer(Modifier.weight(1f))

        if (ui.transcript.isNotEmpty()) {
            Text("heard:", style = MaterialTheme.typography.labelMedium)
            Text("\"${ui.transcript}\"", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }
        if (ui.lastAction.isNotEmpty()) {
            Text(ui.lastAction, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.weight(1f))

        val enabled = ui.recognizerReady && micGranted && !ui.busy
        val label = when {
            ui.busy -> "…"
            ui.recording -> "● REC"
            else -> "HOLD\nTO TALK"
        }
        val circleColor = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            ui.recording -> Color(0xFFD32F2F)
            else -> MaterialTheme.colorScheme.primaryContainer
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(circleColor)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            orchestrator.startListening()
                            tryAwaitRelease()
                            orchestrator.stopListeningAndRun()
                        }
                    )
                },
        ) {
            Text(label, textAlign = TextAlign.Center, fontSize = 20.sp)
        }

        Spacer(Modifier.height(16.dp))
    }
}
