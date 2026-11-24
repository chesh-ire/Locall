package com.example.locall.ui.assistant

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.locall.ui.components.BrandBackground
import com.example.locall.ui.components.FlaskIcon
import com.example.locall.ui.components.PillTone
import com.example.locall.ui.components.StatusPill
import com.example.locall.ui.components.brandBrush
import com.example.locall.ui.theme.Cyan
import com.example.locall.ui.theme.Magenta
import com.example.locall.ui.theme.SignalGreen
import com.example.locall.ui.theme.SignalRed
import com.example.locall.ui.theme.Violet
import com.example.locall.voice.assistant.AssistantViewModel
import com.example.locall.voice.assistant.ChatMessage
import com.example.locall.voice.assistant.ChatRole

private val EXAMPLES = listOf(
    "Review my last 20 emails and tell me what I missed",
    "Summarise my unread WhatsApp messages",
    "Search my documents for the Q3 budget",
    "Help me plan my day",
)

@Composable
fun ConversationScreen(
    vm: AssistantViewModel,
    modifier: Modifier = Modifier,
    onOpenMenu: () -> Unit,
    onOpenPerfLab: () -> Unit,
    onAttachDocument: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.size - 1)
    }

    BrandBackground {
        Column(modifier.fillMaxSize().imePadding()) {
            TopBar(
                busy = ui.busy,
                onOpenMenu = onOpenMenu,
                onOpenPerfLab = onOpenPerfLab,
                onNewConversation = { vm.newConversation() },
                canStartNew = ui.phase == AssistantViewModel.Phase.Idle && ui.messages.isNotEmpty(),
            )

            if (ui.messages.isEmpty()) {
                EmptyState(
                    modifier = Modifier.weight(1f),
                    onExample = { if (ui.inputEnabled) vm.submit(it) },
                    recording = ui.recording,
                    micEnabled = ui.micEnabled,
                    onStartRecording = { vm.startRecording() },
                    onStopRecording = { vm.stopRecording() },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(ui.messages, key = { it.id }) { msg ->
                        MessageBubble(
                            msg = msg,
                            onConfirm = { vm.answerConfirm(true) },
                            onCancel = { vm.answerConfirm(false) },
                            onSkip = { vm.skipPrompt() },
                        )
                    }
                }
            }

            if (ui.phase == AssistantViewModel.Phase.AwaitingInput) {
                Text(
                    "Answer above, or tap Skip to finish.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                )
            }

            InputBar(
                value = input,
                onValueChange = { input = it },
                enabled = ui.inputEnabled,
                busy = ui.busy,
                cancelling = ui.phase == AssistantViewModel.Phase.Cancelling,
                recording = ui.recording,
                micEnabled = ui.micEnabled,
                onSend = {
                    val t = input.trim()
                    if (t.isNotEmpty()) { vm.submit(t); input = "" }
                },
                onStartRecording = { vm.startRecording() },
                onStopRecording = { vm.stopRecording() },
                onAttach = onAttachDocument,
                onCancel = { vm.cancel() },
            )
        }
    }
}

@Composable
private fun TopBar(
    busy: Boolean,
    onOpenMenu: () -> Unit,
    onOpenPerfLab: () -> Unit,
    onNewConversation: () -> Unit,
    canStartNew: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenMenu) { Text("☰", fontSize = 22.sp) }
        Column(Modifier.weight(1f)) {
            Text("LocaLL", style = MaterialTheme.typography.titleLarge.copy(brush = brandBrush()))
            Text(
                "On-device assistant",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) StatusPill(text = "Working", tone = PillTone.Active, modifier = Modifier.padding(end = 4.dp))
        if (canStartNew) IconButton(onClick = onNewConversation) { Text("✎", fontSize = 20.sp) }
        IconButton(onClick = onOpenPerfLab) { FlaskIcon(Modifier.size(22.dp)) }
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    onExample: (String) -> Unit,
    recording: Boolean,
    micEnabled: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "How can I help?",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        HeroMic(
            recording = recording,
            enabled = micEnabled,
            onStart = onStartRecording,
            onStop = onStopRecording,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (recording) "Listening…" else "Hold to speak",
            style = MaterialTheme.typography.titleMedium,
            color = if (recording) Magenta else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        EXAMPLES.forEach { ex ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                onClick = { onExample(ex) },
            ) {
                Text(
                    "“$ex”",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroMic(
    recording: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "hero")
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (recording) 1.12f else 1.05f,
        animationSpec = infiniteRepeatable(
            tween(if (recording) 700 else 2200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val fill: Brush = if (recording) Brush.linearGradient(listOf(SignalRed, Magenta)) else brandBrush()
    // See MicButton: key pointerInput on Unit so starting to record doesn't cancel the release handler.
    val canStart by rememberUpdatedState(enabled)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(168.dp)
            .scale(breathe)
            .clip(CircleShape)
            .background(if (enabled || recording) fill else Brush.linearGradient(listOf(Color.Gray, Color.Gray)))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (canStart) {
                        onStart()
                        tryAwaitRelease()
                        onStop()
                    }
                })
            },
    ) { Text(if (recording) "●" else "🎙️", color = Color.White, fontSize = if (recording) 52.sp else 60.sp) }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onSkip: () -> Unit,
) {
    when (msg.role) {
        ChatRole.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    msg.text,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        ChatRole.ASSISTANT -> AssistantCard(msg)

        ChatRole.STATUS -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Cyan)
            Spacer(Modifier.width(10.dp))
            Text(msg.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        ChatRole.PROMPT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Column {
                AssistantCard(msg.copy(role = ChatRole.ASSISTANT))
                if (msg.pending) {
                    TextButton(onClick = onSkip) { Text("Skip") }
                }
            }
        }

        ChatRole.CONFIRM -> ConfirmCard(msg, onConfirm, onCancel)

        ChatRole.ERROR -> Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                msg.text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

@Composable
private fun AssistantCard(msg: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (msg.capability != null) {
                    Text(
                        msg.capability.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Violet,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(msg.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ConfirmCard(msg: ChatMessage, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Confirm", style = MaterialTheme.typography.labelMedium, color = Magenta)
            Spacer(Modifier.height(6.dp))
            Text(msg.text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            if (msg.pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onConfirm) { Text("Confirm") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            } else {
                val ok = msg.confirmed == true
                Text(
                    if (ok) "✓ Confirmed" else "✗ Cancelled",
                    color = if (ok) SignalGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    busy: Boolean,
    cancelling: Boolean,
    recording: Boolean,
    micEnabled: Boolean,
    onSend: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onAttach: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onAttach, enabled = enabled) { Text("＋", fontSize = 22.sp) }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (recording) "Listening…" else "Ask anything…") },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                StopMicButton(cancelling = cancelling, onStop = onCancel)
            } else if (value.isNotBlank()) {
                IconButton(onClick = onSend, enabled = enabled) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(brandBrush()), contentAlignment = Alignment.Center) {
                        Text("↑", color = Color.White, fontSize = 20.sp)
                    }
                }
            } else {
                MicButton(recording = recording, enabled = micEnabled, onStart = onStartRecording, onStop = onStopRecording)
            }
        }
    }
}

@Composable
private fun MicButton(recording: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 1f, targetValue = if (recording) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val fill: Brush = if (recording) Brush.linearGradient(listOf(SignalRed, Magenta)) else brandBrush()
    val canStart by rememberUpdatedState(enabled)
    Box(
        Modifier
            .size(48.dp)
            .scale(if (recording) pulse else 1f)
            .clip(CircleShape)
            .background(if (enabled || recording) fill else Brush.linearGradient(listOf(Color.Gray, Color.Gray)))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (canStart) {
                        onStart()
                        tryAwaitRelease()
                        onStop()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (recording) "●" else "🎙️", color = Color.White, fontSize = if (recording) 18.sp else 20.sp)
    }
}


@Composable
private fun StopMicButton(cancelling: Boolean, onStop: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "stopMic")
    val pulse by transition.animateFloat(
        initialValue = 1f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "stopPulse",
    )
    Box(
        Modifier
            .size(48.dp)
            .scale(if (cancelling) 1f else pulse)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(SignalRed, Magenta)))
            .pointerInput(cancelling) {
                if (cancelling) return@pointerInput
                detectTapGestures(onTap = { onStop() })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (cancelling) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp), color = Color.White)
        } else {
            Text("🎙️", color = Color.White, fontSize = 20.sp)
        }
    }
}
