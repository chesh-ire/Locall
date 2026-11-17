package com.example.locall.ui.assistant

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.locall.voice.assistant.AssistantViewModel
import com.example.locall.voice.assistant.data.ConversationSession

/**
 * Contents of the top-left drawer: start a new conversation, reopen or delete a saved one, and below
 * that the existing connections & permissions panel. Selecting a conversation closes the drawer via
 * [onClose] and switches the central screen to that session.
 */
@Composable
fun ConversationDrawerContent(
    vm: AssistantViewModel,
    onClose: () -> Unit,
) {
    val sessions by vm.sessions.collectAsState()

    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Conversations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { vm.newConversation(); onClose() }) {
                Text("✎ New")
            }
        }

        Spacer(Modifier.height(8.dp))

        if (sessions.isEmpty()) {
            Text(
                "No saved conversations yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        onOpen = { vm.openSession(session.id); onClose() },
                        onDelete = { vm.deleteSession(session.id) },
                    )
                }
            }
        }

        HorizontalDivider(
            Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        // Existing connections & permissions panel, scrolls independently within its own section.
        Box(Modifier.weight(1f)) { SetupSheetContent() }
    }
}

@Composable
private fun SessionRow(
    session: ConversationSession,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(
                        session.updatedAtMs,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { Text("🗑", fontSize = 16.sp) }
        }
    }
}
