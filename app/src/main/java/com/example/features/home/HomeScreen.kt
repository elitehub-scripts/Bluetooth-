package com.example.features.home

import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.bluetooth.BluetoothCapabilities
import com.example.ui.components.MeshSectionCard
import com.example.ui.components.MonospaceBadge
import com.example.ui.components.RadarPulseIndicator
import com.example.ui.components.StatusPill
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.AmberRelay
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.EmeraldVerified
import com.example.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateNearby: () -> Unit,
    onNavigateMessages: () -> Unit,
    onNavigateFiles: () -> Unit,
    onNavigateNetwork: () -> Unit,
    onNavigateContacts: () -> Unit,
    onNavigateSettings: () -> Unit
) {
    val context = LocalContext.current
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val hardware by viewModel.hardwareStatus.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val pendingMessages by viewModel.pendingMessagesCount.collectAsStateWithLifecycle()
    val pendingFiles by viewModel.pendingFileTransfersCount.collectAsStateWithLifecycle()
    val activeRelays by viewModel.activeRelayCount.collectAsStateWithLifecycle()
    val relayBytes by viewModel.relayTotalBytes.collectAsStateWithLifecycle()
    val relayHistory by viewModel.relayHistory.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.foregroundServiceRunning.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshHardware()
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshHardware()
    }

    val fileBytesUsed = viewModel.usedFileStorageBytes()
    val totalUsedBytes = relayBytes + fileBytesUsed
    val quotaBytes = settings.maxRelayStorageMb.toLong() * 1024L * 1024L

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Hero Banner & Mesh Status
        item {
            MeshSectionCard {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_mesh_hero),
                        contentDescription = "Bluetooth Global Share decentralized mesh banner",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(144.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(144.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.25f),
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadarPulseIndicator(
                                    active = serviceRunning || isScanning,
                                    color = if (hardware.isBluetoothEnabled) MaterialTheme.colorScheme.primary else CoralAlert
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "BLUETOOTH GLOBAL SHARE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = when {
                                            !hardware.hasBluetoothHardware -> "No Bluetooth Radio"
                                            !hardware.isBluetoothEnabled -> "Bluetooth Disabled"
                                            serviceRunning -> "Mesh Relay Active"
                                            else -> "Offline Ready (Standby)"
                                        },
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            StatusPill(
                                label = if (hardware.isBluetoothEnabled) "BT ON" else "BT OFF",
                                color = if (hardware.isBluetoothEnabled) EmeraldVerified else CoralAlert
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (hardware.missingPermissions.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        permissionLauncher.launch(
                                            BluetoothCapabilities.requiredRuntimePermissions().toTypedArray()
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("grant_bt_permissions_button")
                                ) {
                                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Grant Permissions")
                                }
                            } else if (hardware.hasBluetoothHardware && !hardware.isBluetoothEnabled) {
                                Button(
                                    onClick = {
                                        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("enable_bluetooth_button")
                                ) {
                                    Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Enable Bluetooth")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        if (serviceRunning) {
                                            viewModel.stopMeshService(context)
                                        } else {
                                            viewModel.startMeshService(context)
                                        }
                                    },
                                    colors = if (serviceRunning) {
                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    } else {
                                        ButtonDefaults.buttonColors()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("toggle_mesh_service_button")
                                ) {
                                    Icon(
                                        imageVector = if (serviceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (serviceRunning) "Stop Relay Service" else "Start Mesh Relay")
                                }
                            }

                            FilledTonalButton(
                                onClick = onNavigateNearby,
                                modifier = Modifier.testTag("home_scan_nearby_button")
                            ) {
                                Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Nearby")
                            }
                        }
                    }
                }
            }
        }

        // 2. Local Device Identity Card
        item {
            MeshSectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LOCAL NODE IDENTITY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = profile?.displayName ?: "Initializing identity…",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = {
                                val id = profile?.deviceId ?: return@IconButton
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Device ID", id))
                            },
                            modifier = Modifier.testTag("copy_device_id_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Device ID")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonospaceBadge(text = "ID: ${profile?.deviceId ?: "—"}")
                        StatusPill(
                            label = if (profile?.keystoreBacked == true) "Keystore E2EE" else "Local E2EE",
                            color = EmeraldVerified
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Safety Fingerprint: ${profile?.fingerprint ?: "Generating…"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 3. Live Telemetry Grid (2x3)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TelemetryTile(
                        title = "Nearby Peers",
                        value = "${discoveredDevices.size}",
                        subtitle = "Discovered in range",
                        icon = Icons.Default.Radar,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = onNavigateNearby,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryTile(
                        title = "Connected",
                        value = "${connectedPeers.size}",
                        subtitle = "Active encrypted links",
                        icon = Icons.Default.Bluetooth,
                        accent = EmeraldVerified,
                        onClick = onNavigateNearby,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TelemetryTile(
                        title = "Pending Msgs",
                        value = "$pendingMessages",
                        subtitle = "Awaiting delivery ACK",
                        icon = Icons.AutoMirrored.Filled.Chat,
                        accent = AmberRelay,
                        onClick = onNavigateMessages,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryTile(
                        title = "File Transfers",
                        value = "$pendingFiles",
                        subtitle = "Queued / Active",
                        icon = Icons.Default.FolderZip,
                        accent = MaterialTheme.colorScheme.secondary,
                        onClick = onNavigateFiles,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TelemetryTile(
                        title = "Relay Store",
                        value = "$activeRelays",
                        subtitle = if (settings.relayParticipationEnabled) "Store & Forward ON" else "Relay paused",
                        icon = Icons.Default.SwapHoriz,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = onNavigateNetwork,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryTile(
                        title = "Offline Storage",
                        value = formatByteSize(totalUsedBytes),
                        subtitle = "Quota: ${settings.maxRelayStorageMb} MB",
                        icon = Icons.Default.Storage,
                        accent = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onNavigateSettings,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 4. Storage Usage Progress & Quick Contacts / Settings Bar
        item {
            MeshSectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Relay & Media Storage Budget",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${formatByteSize(totalUsedBytes)} / ${settings.maxRelayStorageMb} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val progress = (totalUsedBytes.toFloat() / quotaBytes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onNavigateContacts,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_contacts_button")
                        ) {
                            Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Contacts & QR")
                        }
                        OutlinedButton(
                            onClick = onNavigateSettings,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_settings_button")
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Settings")
                        }
                    }
                }
            }
        }

        // 5. Honest Physical Bluetooth Range & Store-and-Forward Explanation
        item {
            MeshSectionCard(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Physical range notice",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "How Offline Store-and-Forward Works",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Bluetooth operates within ~10–100 m physical radio range and cannot connect two distant countries directly. Instead, messages are end-to-end encrypted (X25519 + AES-256-GCM) and travel hop-by-hop across participating devices as people physically move and encounter each other.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 6. Recent Relay Activity Feed
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Relay Activity",
                    style = MaterialTheme.typography.titleLarge
                )
                FilledTonalButton(onClick = onNavigateNetwork) {
                    Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("View Graph")
                }
            }
        }

        if (relayHistory.isEmpty()) {
            item {
                MeshSectionCard {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (hardware.isBluetoothEnabled) Icons.Default.SwapHoriz else Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "No relay events recorded yet. Encrypted packets forwarded or delivered over Bluetooth will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(relayHistory.take(6), key = { it.id }) { event ->
                MeshSectionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${event.action.label} • ${event.peerName}",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = event.details,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
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

@Composable
private fun TelemetryTile(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
