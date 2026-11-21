package com.example.features.network

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.database.entities.NetworkNodeEntity
import com.example.core.models.RelayState
import com.example.ui.components.EmptyMeshState
import com.example.ui.components.MeshSectionCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatDurationRemaining
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.AmberRelay
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.EmeraldVerified
import com.example.ui.viewmodel.MainViewModel
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Composable
fun NetworkScreen(viewModel: MainViewModel) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val nodes by viewModel.networkNodes.collectAsStateWithLifecycle()
    val relayMessages by viewModel.relayMessages.collectAsStateWithLifecycle()
    val relayHistory by viewModel.relayHistory.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()

    var selectedNode by remember { mutableStateOf<NetworkNodeEntity?>(null) }

    val activeNodes = nodes.filter { it.isCurrentlyConnected || connectedPeers.containsKey(it.nodeId) }
    val historicalNodes = nodes.filterNot { it.isCurrentlyConnected || connectedPeers.containsKey(it.nodeId) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Node Graph Visualization
        item {
            MeshSectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Local & Relay Topology", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "${activeNodes.size} live link(s) • ${historicalNodes.size} historical encounter(s)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { viewModel.cleanupExpiredRelays() },
                            modifier = Modifier.testTag("purge_expired_relays_button")
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Purge Expired")
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    val primaryColor = MaterialTheme.colorScheme.primary
                    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
                    val nodePositions = remember { mutableMapOf<String, Offset>() }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(nodes) {
                                    detectTapGestures { tap ->
                                        val hit = nodePositions.entries.minByOrNull { (_, pos) ->
                                            hypot((tap.x - pos.x).toDouble(), (tap.y - pos.y).toDouble())
                                        }
                                        if (hit != null && hypot((tap.x - hit.value.x).toDouble(), (tap.y - hit.value.y).toDouble()) < 56.0) {
                                            selectedNode = nodes.firstOrNull { it.nodeId == hit.key }
                                        }
                                    }
                                }
                        ) {
                            nodePositions.clear()
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val innerRadius = size.minDimension * 0.27f
                            val outerRadius = size.minDimension * 0.42f

                            // Reference rings
                            drawCircle(
                                color = surfaceColor,
                                radius = innerRadius,
                                center = center,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                            )
                            drawCircle(
                                color = surfaceColor.copy(alpha = 0.6f),
                                radius = outerRadius,
                                center = center,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 1.5f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                                )
                            )

                            // Draw active physical links (solid emerald/cyan)
                            activeNodes.forEachIndexed { idx, node ->
                                val angle = (2.0 * Math.PI * idx / activeNodes.size.coerceAtLeast(1)) - Math.PI / 2.0
                                val pos = Offset(
                                    center.x + (innerRadius * cos(angle)).toFloat(),
                                    center.y + (innerRadius * sin(angle)).toFloat()
                                )
                                nodePositions[node.nodeId] = pos
                                drawLine(
                                    color = EmeraldVerified,
                                    start = center,
                                    end = pos,
                                    strokeWidth = 4f
                                )
                                drawCircle(color = EmeraldVerified, radius = 16f, center = pos)
                            }

                            // Draw historical encountered relay links (dashed amber)
                            historicalNodes.take(12).forEachIndexed { idx, node ->
                                val count = historicalNodes.take(12).size.coerceAtLeast(1)
                                val angle = (2.0 * Math.PI * idx / count) - Math.PI / 3.0
                                val pos = Offset(
                                    center.x + (outerRadius * cos(angle)).toFloat(),
                                    center.y + (outerRadius * sin(angle)).toFloat()
                                )
                                nodePositions[node.nodeId] = pos
                                drawLine(
                                    color = AmberRelay.copy(alpha = 0.55f),
                                    start = center,
                                    end = pos,
                                    strokeWidth = 2.5f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                                )
                                drawCircle(color = AmberRelay, radius = 12f, center = pos)
                            }

                            // Local device center node
                            drawCircle(color = primaryColor.copy(alpha = 0.25f), radius = 34f, center = center)
                            drawCircle(color = primaryColor, radius = 22f, center = center)
                        }

                        if (nodes.isEmpty()) {
                            Text(
                                text = "This Node (${profile?.displayName ?: "Local"})\nConnect nearby peers to map physical & relay links",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 8.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        StatusPill(label = "● This Device", color = primaryColor)
                        StatusPill(label = "— Active BT Link", color = EmeraldVerified)
                        StatusPill(label = "-- Past Relay Node", color = AmberRelay)
                    }

                    selectedNode?.let { node ->
                        Spacer(Modifier.height(10.dp))
                        MeshSectionCard(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Selected Node: ${node.displayName}", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "ID: ${node.nodeId} • Relayed: ${node.successfulRelays} • Last seen: ${formatTimestamp(node.lastSeenAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Honest Connectivity Disclaimer
        item {
            MeshSectionCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Only real physical Bluetooth connections and previously encountered relay devices are plotted. No simulated or remote internet links exist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Relay Store Queue
        item {
            Text(
                text = "Encrypted Store-and-Forward Queue (${relayMessages.size})",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (relayMessages.isEmpty()) {
            item {
                EmptyMeshState(
                    icon = Icons.Default.SwapHoriz,
                    title = "Relay Store Empty",
                    subtitle = "Encrypted messages waiting to be forwarded to other phones or awaiting delivery acknowledgements will appear here."
                )
            }
        } else {
            items(relayMessages, key = { it.messageId }) { item ->
                val stateColor = when (item.relayState) {
                    RelayState.QUEUED -> AmberRelay
                    RelayState.FORWARDED -> ElectricBlue
                    RelayState.ACKED_DELIVERED -> EmeraldVerified
                    RelayState.EXPIRED -> CoralAlert
                }
                MeshSectionCard {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Dest: ${item.recipientDeviceId.take(12)}…",
                                style = MaterialTheme.typography.titleMedium
                            )
                            StatusPill(label = item.relayState.name, color = stateColor)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Hop ${item.hopCount}/${item.maxHops} • ${formatByteSize(item.payloadSizeBytes.toLong())} • TTL ${formatDurationRemaining(item.expiresAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (item.isPriority) {
                            Spacer(Modifier.height(4.dp))
                            StatusPill(label = "PRIORITY", color = AmberRelay)
                        }
                    }
                }
            }
        }

        // Relay History Ledger
        item {
            Text(
                text = "Relay History Ledger (${relayHistory.size})",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (relayHistory.isEmpty()) {
            item {
                EmptyMeshState(
                    icon = Icons.Default.Hub,
                    title = "No Relay Logs Yet",
                    subtitle = "Forwarded envelopes, duplicate rejections, TTL expirations, and delivery ACKs are audited locally here."
                )
            }
        } else {
            items(relayHistory, key = { it.id }) { event ->
                MeshSectionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${event.action.label} (${event.peerName})",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = event.details,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatTimestamp(event.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
