package com.example.features.nearby

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.bluetooth.BluetoothCapabilities
import com.example.core.bluetooth.ConnectedPeerInfo
import com.example.core.models.BleScanModeSetting
import com.example.core.models.DiscoveredDeviceUi
import com.example.core.models.PeerConnectionState
import com.example.ui.components.EmptyMeshState
import com.example.ui.components.MeshSectionCard
import com.example.ui.components.MonospaceBadge
import com.example.ui.components.RadarPulseIndicator
import com.example.ui.components.StatusPill
import com.example.ui.theme.AmberRelay
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.EmeraldVerified
import com.example.ui.viewmodel.MainViewModel

@Composable
fun NearbyScreen(viewModel: MainViewModel) {
    val hardware by viewModel.hardwareStatus.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val isAdvertising by viewModel.isAdvertising.collectAsStateWithLifecycle()
    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val transportError by viewModel.transportError.collectAsStateWithLifecycle()

    var inspectPeer by remember { mutableStateOf<ConnectedPeerInfo?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshHardware()
    }

    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshHardware()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Scan & Discovery Header Card
        item {
            MeshSectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadarPulseIndicator(active = isScanning || isAdvertising)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isScanning) "Scanning for Mesh Peers…" else "Nearby Bluetooth Discovery",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = buildString {
                                        append(if (isAdvertising) "BLE Advertising ON" else "Advertising OFF")
                                        append(" • ${connectedPeers.size} active link(s)")
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (!transportError.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        StatusPill(label = transportError!!, color = CoralAlert)
                    }

                    Spacer(Modifier.height(14.dp))

                    if (hardware.missingPermissions.isNotEmpty()) {
                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    BluetoothCapabilities.requiredRuntimePermissions().toTypedArray()
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("nearby_request_permissions_button")
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Grant Bluetooth & Nearby Permissions")
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    if (isScanning || isAdvertising) {
                                        viewModel.stopDiscoveryAndServers()
                                    } else {
                                        viewModel.startDiscoveryAndServers()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("toggle_scan_button")
                            ) {
                                Icon(
                                    imageVector = if (isScanning) Icons.Default.BluetoothConnected else Icons.Default.BluetoothSearching,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (isScanning || isAdvertising) "Stop Scan" else "Start BLE Scan")
                            }

                            FilledTonalButton(
                                onClick = { viewModel.triggerClassicDiscovery() },
                                modifier = Modifier.testTag("classic_discover_button")
                            ) {
                                Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Classic")
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                                }
                                discoverableLauncher.launch(intent)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("make_discoverable_button")
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Make Classic Bluetooth Discoverable (5 min)")
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Adaptive Scan Duty Cycle",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BleScanModeSetting.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.scanMode == mode,
                                onClick = { viewModel.setScanMode(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
            }
        }

        // Connected Peers Section
        if (connectedPeers.isNotEmpty()) {
            item {
                Text(
                    text = "Connected & Authenticated Peers",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            items(connectedPeers.values.toList(), key = { "conn-${it.deviceId}" }) { peer ->
                MeshSectionCard(borderColor = EmeraldVerified.copy(alpha = 0.5f)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.displayName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${peer.transportType.label} • ${peer.address}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(label = "E2EE Verified", color = EmeraldVerified)
                        }
                        Spacer(Modifier.height(8.dp))
                        MonospaceBadge(text = "FP: ${peer.fingerprint}")
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { inspectPeer = peer },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Verify / Trust")
                            }
                            OutlinedButton(
                                onClick = { viewModel.disconnectPeer(peer.deviceId) }
                            ) {
                                Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }
        }

        // Discovered Nearby Devices Section
        item {
            Text(
                text = "Nearby & Paired Devices (${devices.size})",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (devices.isEmpty()) {
            item {
                EmptyMeshState(
                    icon = Icons.Default.Bluetooth,
                    title = if (!hardware.isBluetoothEnabled) "Bluetooth is Off" else "No Compatible Devices Found Yet",
                    subtitle = if (!hardware.isBluetoothEnabled) {
                        "Enable Bluetooth on this device and another phone running Bluetooth Global Share within radio range."
                    } else {
                        "Tap Start BLE Scan or Classic to discover real nearby Android phones advertising Bluetooth Global Share, or pair via Android Bluetooth Settings."
                    }
                )
            }
        } else {
            items(devices, key = { it.address }) { dev ->
                DiscoveredDeviceCard(
                    device = dev,
                    onConnectBle = { viewModel.connectToPeer(dev.address, preferClassic = false) },
                    onConnectClassic = { viewModel.connectToPeer(dev.address, preferClassic = true) },
                    onDisconnect = { viewModel.disconnectPeer(dev.address) },
                    onInspect = {
                        dev.peerDeviceId?.let { id ->
                            inspectPeer = connectedPeers[id]
                        }
                    }
                )
            }
        }
    }

    inspectPeer?.let { peer ->
        AlertDialog(
            onDismissRequest = { inspectPeer = null },
            title = { Text("Verify Peer Identity") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = peer.displayName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Device ID: ${peer.deviceId}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = "Compare this cryptographic fingerprint in person or via QR code before trusting:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MonospaceBadge(text = peer.fingerprint)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.approvePeer(peer, verifiedManually = true)
                        inspectPeer = null
                    }
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Trust & Save Contact")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            viewModel.blockPeer(
                                peer.deviceId,
                                peer.address,
                                peer.displayName,
                                "Blocked during pairing verification"
                            )
                            inspectPeer = null
                        }
                    ) {
                        Icon(Icons.Default.Block, contentDescription = null, tint = CoralAlert, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Block", color = CoralAlert)
                    }
                    TextButton(onClick = { inspectPeer = null }) {
                        Text("Close")
                    }
                }
            }
        )
    }
}

@Composable
private fun DiscoveredDeviceCard(
    device: DiscoveredDeviceUi,
    onConnectBle: () -> Unit,
    onConnectClassic: () -> Unit,
    onDisconnect: () -> Unit,
    onInspect: () -> Unit
) {
    MeshSectionCard {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.peerDisplayName ?: device.bluetoothName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(device.address)
                            if (device.isBonded) append(" • Paired")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val stateColor = when (device.connectionState) {
                    PeerConnectionState.CONNECTED -> EmeraldVerified
                    PeerConnectionState.CONNECTING,
                    PeerConnectionState.HANDSHAKING -> AmberRelay
                    PeerConnectionState.FAILED -> CoralAlert
                    else -> ElectricBlue
                }
                StatusPill(label = device.connectionState.label, color = stateColor)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (device.supportsBle) StatusPill(label = "BLE", color = MaterialTheme.colorScheme.primary)
                if (device.supportsClassic) StatusPill(label = "Classic RFCOMM", color = ElectricBlue)
                if (device.rssi != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SignalCellularAlt,
                            contentDescription = "Signal strength",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "${device.rssi} dBm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (device.isBlocked) {
                StatusPill(label = "Blocked Device", color = CoralAlert)
            } else if (device.connectionState == PeerConnectionState.CONNECTED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onInspect, modifier = Modifier.weight(1f)) {
                        Text("Inspect Keys")
                    }
                    OutlinedButton(onClick = onDisconnect) {
                        Text("Disconnect")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (device.supportsBle) {
                        Button(
                            onClick = onConnectBle,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connect BLE")
                        }
                    }
                    if (device.supportsClassic) {
                        FilledTonalButton(
                            onClick = onConnectClassic,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connect RFCOMM")
                        }
                    }
                }
            }
        }
    }
}
