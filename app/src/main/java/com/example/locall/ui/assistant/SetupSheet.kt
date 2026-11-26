package com.example.locall.ui.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.locall.ui.components.SectionCard
import com.example.locall.ui.theme.SignalGreen
import com.example.locall.voice.WakeWordService
import com.example.locall.voice.email.GmailAuth
import kotlinx.coroutines.launch

/**
 * The top-left menu: connections & permissions, migrated from the old Setup card. Self-contained —
 * owns its permission / Gmail-consent launchers. Everything stays on-device except Gmail OAuth.
 */
@Composable
fun SetupSheetContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micGranted = granted }

    var gmailConnected by remember { mutableStateOf(GmailAuth.isConnected) }
    var gmailError by remember { mutableStateOf<String?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        when (val r = GmailAuth.resultFromConsent(context, result.data)) {
            is GmailAuth.Result.Token -> { gmailConnected = true; gmailError = null }
            is GmailAuth.Result.Error -> gmailError = r.message
            is GmailAuth.Result.NeedsConsent -> gmailError = "consent not completed"
        }
    }

    var alwaysOn by remember { mutableStateOf(WakeWordService.isRunning) }
    val notifAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Connections & permissions", style = MaterialTheme.typography.titleLarge)

        SectionCard(title = "Services") {
            SetupRow(Icons.Outlined.Mic, "Microphone", if (micGranted) "Granted" else "Needed to hear you") {
                if (micGranted) DonePill()
                else FilledTonalButton(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Grant") }
            }
            Divider()
            SetupRow(Icons.Outlined.Accessibility, "Accessibility", "Lets LocaLL type and control the device") {
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open") }
            }
            Divider()
            SetupRow(Icons.Outlined.Sms, "Message access", if (notifAccess) "WhatsApp & SMS captured" else "For WhatsApp / SMS briefings") {
                if (notifAccess) DonePill()
                else OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text("Enable") }
            }
            Divider()
            SetupRow(
                Icons.Outlined.MailOutline, "Gmail",
                gmailError?.let { "Error: $it" } ?: if (gmailConnected) "Connected" else "Read & manage email",
            ) {
                if (gmailConnected) DonePill()
                else OutlinedButton(onClick = {
                    scope.launch {
                        when (val r = GmailAuth.acquire(context)) {
                            is GmailAuth.Result.Token -> { gmailConnected = true; gmailError = null }
                            is GmailAuth.Result.NeedsConsent ->
                                consentLauncher.launch(IntentSenderRequest.Builder(r.pendingIntent).build())
                            is GmailAuth.Result.Error -> gmailError = r.message
                        }
                    }
                }) { Text("Connect") }
            }
            Divider()
            SetupRow(Icons.Outlined.Bedtime, "Always-on", "Wake with \"hey locall\"") {
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
        }
        Text(
            "All reasoning runs on-device. Only Gmail sync uses the network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        // The action never gives up width to the label column, so its button text can't be
        // squeezed into a vertical, one-character-per-line stack on a narrow drawer.
        Box(Modifier.wrapContentWidth()) { trailing() }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun DonePill() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = SignalGreen,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("Ready", style = MaterialTheme.typography.labelLarge, color = SignalGreen)
    }
}
