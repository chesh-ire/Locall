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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.gestures.detectTapGestures
import com.example.locall.ui.components.BrandBackground
import com.example.locall.ui.components.BrandHeader
import com.example.locall.ui.components.GlowMic
import com.example.locall.ui.components.MicState
import com.example.locall.ui.components.PillTone
import com.example.locall.ui.components.SectionCard
import com.example.locall.ui.components.StatusPill
import com.example.locall.ui.theme.LocaLLTheme
import com.example.locall.ui.theme.SignalGreen
import com.example.locall.voice.VoiceOrchestrator
import com.example.locall.voice.WakeWordService
import com.example.locall.voice.agent.follow.FollowUpManager
import com.example.locall.voice.agent.ui.TodayScreen
import com.example.locall.voice.email.GmailAuth
import com.example.locall.voice.research.bench.ui.PerformanceLabScreen
import com.example.locall.voice.research.ui.ResearchScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var orchestrator: VoiceOrchestrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orchestrator = VoiceOrchestrator(applicationContext)
        orchestrator.init()
        lifecycleScope.launch { runCatching { FollowUpManager.rescheduleAll(applicationContext) } }
        enableEdgeToEdge()
        setContent {
            LocaLLTheme {
                var tab by remember { mutableIntStateOf(0) }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = { BottomNav(tab) { tab = it } },
                ) { pad ->
                    when (tab) {
                        0 -> VoiceScreen(
                            orchestrator = orchestrator,
                            modifier = Modifier.padding(pad),
                            openA11ySettings = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                        )
                        1 -> TodayScreen(modifier = Modifier.padding(pad))
                        2 -> ResearchScreen(modifier = Modifier.padding(pad))
                        else -> PerformanceLabScreen(modifier = Modifier.padding(pad))
                    }
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

@Composable
private fun BottomNav(tab: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple("🎙️", "Assistant", 0),
        Triple("🗓️", "Today", 1),
        Triple("🔎", "Knowledge", 2),
        Triple("📊", "Perf Lab", 3),
    )
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        items.forEach { (icon, label, index) ->
            NavigationBarItem(
                selected = tab == index,
                onClick = { onSelect(index) },
                icon = { Text(icon, fontSize = 18.sp) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
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
    val scope = rememberCoroutineScope()
    var micGranted by remember { mutableStateOf(hasMicPermission(context)) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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

    var alwaysOn by remember { mutableStateOf(WakeWordService.isRunning) }

    val enabled = ui.recognizerReady && micGranted && !ui.busy
    val micState = when {
        ui.busy -> MicState.Busy
        ui.recording -> MicState.Recording
        !enabled -> MicState.Disabled
        else -> MicState.Idle
    }
    val micLabel = when {
        ui.busy -> "WORKING…"
        ui.recording -> "RELEASE TO SEND"
        !micGranted -> "MIC OFF"
        !ui.recognizerReady -> "LOADING…"
        else -> "HOLD TO TALK"
    }

    BrandBackground {
        Column(modifier.fillMaxSize()) {
            BrandHeader(
                title = "LocaLL",
                subtitle = "Offline voice control · on-device",
                trailing = {
                    StatusPill(
                        text = if (alwaysOn) "Listening" else "Idle",
                        tone = if (alwaysOn) PillTone.Active else PillTone.Neutral,
                    )
                },
            )

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (ui.status.isNotEmpty()) {
                    Text(
                        ui.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionCard(title = "Setup", leading = "⚙️") {
                    SetupRow(
                        icon = "🎤",
                        title = "Microphone",
                        subtitle = if (micGranted) "Granted" else "Needed to hear you",
                    ) {
                        if (micGranted) DonePill()
                        else FilledTonalButton(
                            onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        ) { Text("Grant") }
                    }

                    SetupDivider()
                    SetupRow(
                        icon = "👆",
                        title = "Accessibility",
                        subtitle = "Lets LocaLL type and control the device",
                    ) {
                        OutlinedButton(onClick = openA11ySettings) { Text("Enable") }
                    }

                    SetupDivider()
                    SetupRow(
                        icon = "✉️",
                        title = "Gmail",
                        subtitle = gmailError?.let { "Error: $it" }
                            ?: if (gmailConnected) "Connected" else "Read & clear email by voice",
                    ) {
                        if (gmailConnected) DonePill()
                        else OutlinedButton(
                            onClick = {
                                scope.launch {
                                    when (val r = GmailAuth.acquire(context)) {
                                        is GmailAuth.Result.Token -> { gmailConnected = true; gmailError = null }
                                        is GmailAuth.Result.NeedsConsent ->
                                            consentLauncher.launch(
                                                IntentSenderRequest.Builder(r.pendingIntent).build(),
                                            )
                                        is GmailAuth.Result.Error -> gmailError = r.message
                                    }
                                }
                            },
                        ) { Text("Connect") }
                    }

                    SetupDivider()
                    SetupRow(
                        icon = "🌙",
                        title = "Always-on",
                        subtitle = "Wake with “hey locall”",
                    ) {
                        Switch(
                            checked = alwaysOn,
                            enabled = micGranted,
                            onCheckedChange = { on ->
                                alwaysOn = on
                                val intent = Intent(context, WakeWordService::class.java).apply {
                                    action =
                                        if (on) WakeWordService.ACTION_START else WakeWordService.ACTION_STOP
                                }
                                if (on) ContextCompat.startForegroundService(context, intent)
                                else context.startService(intent)
                            },
                        )
                    }
                }

                if (ui.transcript.isNotEmpty() || ui.lastAction.isNotEmpty()) {
                    SectionCard(title = "Last exchange", leading = "💬") {
                        if (ui.transcript.isNotEmpty()) {
                            Text(
                                "HEARD",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "“${ui.transcript}”",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (ui.lastAction.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "RESULT",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(ui.lastAction, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlowMic(
                    state = micState,
                    label = micLabel,
                    gestureModifier = Modifier.pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectTapGestures(
                            onPress = {
                                orchestrator.startListening()
                                tryAwaitRelease()
                                orchestrator.stopListeningAndRun()
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupRow(
    icon: String,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun SetupDivider() {
    HorizontalDivider(
        Modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun DonePill() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("✓", color = SignalGreen, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(6.dp))
        Text(
            "Ready",
            style = MaterialTheme.typography.labelLarge,
            color = SignalGreen,
        )
    }
}
