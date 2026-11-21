package com.example.features.messages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.models.MessageDirection
import com.example.core.models.TrustStatus
import com.example.data.repository.DecryptedMessageUi
import com.example.ui.components.MessageStatusPill
import com.example.ui.components.StatusPill
import com.example.ui.components.formatDurationRemaining
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.AmberRelay
import com.example.ui.theme.EmeraldVerified
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    conversationId: String,
    peerDeviceId: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val messagesFlow = remember(conversationId) { viewModel.observeMessages(conversationId) }
    val messages by messagesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val contact = contacts.firstOrNull { it.deviceId == peerDeviceId }
    val isDirectlyConnected = connectedPeers.containsKey(peerDeviceId)

    var messageInput by remember { mutableStateOf("") }
    var isPriority by remember { mutableStateOf(false) }
    var forwardCandidate by remember { mutableStateOf<DecryptedMessageUi?>(null) }

    val quickEmojis = listOf("👍", "📍", "✅", "🚨", "📡", "🔋", "🙏")

    LaunchedEffect(conversationId) {
        viewModel.markConversationRead(conversationId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = contact?.displayName ?: "Peer ${peerDeviceId.take(8)}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.width(6.dp))
                            if (contact?.trustStatus == TrustStatus.VERIFIED) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "Verified E2EE",
                                    tint = EmeraldVerified,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = if (isDirectlyConnected) "Direct Bluetooth link active" else "Store & Forward Relay mode",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDirectlyConnected) EmeraldVerified else AmberRelay
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.entity.messageId }) { msgUi ->
                    val isOutgoing = msgUi.entity.direction == MessageDirection.OUTGOING
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            shape = RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 18.dp,
                                bottomStart = if (isOutgoing) 18.dp else 4.dp,
                                bottomEnd = if (isOutgoing) 4.dp else 18.dp
                            ),
                            color = if (isOutgoing) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier.widthIn(max = 310.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (msgUi.entity.isPriority) {
                                    StatusPill(label = "PRIORITY RELAY", color = AmberRelay)
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    text = msgUi.plaintext,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = formatTimestamp(msgUi.entity.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "• Hop ${msgUi.entity.hopCount}/${msgUi.entity.maxHops}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "• TTL ${formatDurationRemaining(msgUi.entity.expiresAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    MessageStatusPill(status = msgUi.entity.status)
                                    Row {
                                        IconButton(
                                            onClick = { forwardCandidate = msgUi },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Forward,
                                                contentDescription = "Forward message",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteMessage(msgUi.entity.messageId) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete message",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Quick emoji & priority bar
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        selected = isPriority,
                        onClick = { isPriority = !isPriority },
                        leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Priority") }
                    )
                }
                items(quickEmojis) { emoji ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { messageInput += emoji }
                    ) {
                        Text(
                            text = emoji,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Composer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageInput,
                    onValueChange = { messageInput = it },
                    placeholder = {
                        Text(
                            if (isDirectlyConnected) "Message (E2EE Direct)…"
                            else "Message (Encrypted Relay Queue)…"
                        )
                    },
                    maxLines = 4,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_message_input")
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val txt = messageInput.trim()
                        if (txt.isNotEmpty()) {
                            viewModel.sendMessage(peerDeviceId, txt, isPriority)
                            messageInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("send_message_button")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send encrypted message",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    forwardCandidate?.let { msg ->
        AlertDialog(
            onDismissRequest = { forwardCandidate = null },
            title = { Text("Forward Message") },
            text = {
                val otherContacts = contacts.filter { it.deviceId != peerDeviceId }
                if (otherContacts.isEmpty()) {
                    Text("No other contacts available to forward this message.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(200.dp)
                    ) {
                        items(otherContacts, key = { it.deviceId }) { target ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.forwardMessage(msg.plaintext, target)
                                        forwardCandidate = null
                                    }
                            ) {
                                Text(
                                    text = target.displayName,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { forwardCandidate = null }) { Text("Cancel") }
            }
        )
    }
}
