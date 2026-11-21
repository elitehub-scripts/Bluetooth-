package com.example.features.settings

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.models.AppThemeMode
import com.example.core.models.BleScanModeSetting
import com.example.ui.components.MeshSectionCard
import com.example.ui.components.MonospaceBadge
import com.example.ui.viewmodel.MainViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.foregroundServiceRunning.collectAsStateWithLifecycle()

    var nameInput by remember { mutableStateOf("") }
    LaunchedEffect(profile?.displayName) {
        if (nameInput.isEmpty() && profile != null) {
            nameInput = profile!!.displayName
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Device Identity & Bluetooth System Settings
        item {
            MeshSectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Device Identity & Radio", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("Node Display Name") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("device_name_input")
                        )
                        Button(
                            onClick = { viewModel.updateDisplayName(nameInput) },
                            modifier = Modifier.testTag("save_device_name_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save name", modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Android Bluetooth Settings")
                    }
                }
            }
        }

        // 2. Theme Selection
        item {
            MeshSectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Appearance Theme", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
            }
        }

        // 3. Relay Participation, TTL, Storage & Hops
        item {
            MeshSectionCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Relay Network & Storage", style = MaterialTheme.typography.titleLarge)

                    SettingToggleRow(
                        title = "Participate in Store-and-Forward Relay",
                        subtitle = "Carry encrypted envelopes for other phones until physical delivery",
                        checked = settings.relayParticipationEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.updateSettings { it.copy(relayParticipationEnabled = enabled) }
                        }
                    )

                    SettingToggleRow(
                        title = "Background Mesh Foreground Service",
                        subtitle = if (serviceRunning) "Persistent notification active" else "Only runs while app is open",
                        checked = serviceRunning,
                        onCheckedChange = { start ->
                            if (start) viewModel.startMeshService(context)
                            else viewModel.stopMeshService(context)
                        }
                    )

                    SettingToggleRow(
                        title = "Auto-Reconnect Dropped Peers",
                        subtitle = "Bounded backoff when peers briefly move out of range",
                        checked = settings.autoReconnectEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.updateSettings { it.copy(autoReconnectEnabled = enabled) }
                        }
                    )

                    SettingToggleRow(
                        title = "Offline Message & File Notifications",
                        subtitle = "Alert locally when decrypted messages or verified files arrive",
                        checked = settings.notificationsEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.updateSettings { it.copy(notificationsEnabled = enabled) }
                        }
                    )

                    Column {
                        Text(
                            text = "Relay Storage Limit: ${settings.maxRelayStorageMb} MB",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Slider(
                            value = settings.maxRelayStorageMb.toFloat(),
                            onValueChange = { valMb ->
                                viewModel.updateSettings { it.copy(maxRelayStorageMb = valMb.roundToInt()) }
                            },
                            valueRange = 20f..500f
                        )
                    }

                    Column {
                        Text(
                            text = "Default Message TTL: ${settings.defaultMessageTtlHours} hours",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Slider(
                            value = settings.defaultMessageTtlHours.toFloat(),
                            onValueChange = { hrs ->
                                viewModel.updateSettings { it.copy(defaultMessageTtlHours = hrs.roundToInt()) }
                            },
                            valueRange = 6f..168f
                        )
                    }

                    Column {
                        Text(
                            text = "Max Relay Hop Count: ${settings.defaultMaxHops} hops",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Slider(
                            value = settings.defaultMaxHops.toFloat(),
                            onValueChange = { hops ->
                                viewModel.updateSettings { it.copy(defaultMaxHops = hops.roundToInt()) }
                            },
                            valueRange = 2f..15f
                        )
                    }

                    Column {
                        Text(
                            text = "Max Incoming File Size: ${settings.maxFileSizeMb} MB",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Slider(
                            value = settings.maxFileSizeMb.toFloat(),
                            onValueChange = { mb ->
                                viewModel.updateSettings { it.copy(maxFileSizeMb = mb.roundToInt()) }
                            },
                            valueRange = 10f..500f
                        )
                    }

                    Text("BLE Adaptive Scan Profile", style = MaterialTheme.typography.titleMedium)
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

        // 4. Cryptography & Security Details
        item {
            MeshSectionCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Cryptography & Privacy", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        text = "• Identity Signing: Ed25519 (256-bit) bound to Node ID\n" +
                            "• End-to-End Key Agreement: X25519 ECDH + HKDF-SHA256\n" +
                            "• Authenticated Encryption: AES-256-GCM (message & session)\n" +
                            "• Key Protection: Android Keystore hardware-backed master wrap\n" +
                            "• File Integrity: Streaming SHA-256 checksum before atomic save\n" +
                            "• Relay Privacy: Relay phones carry encrypted envelopes only",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    profile?.let {
                        MonospaceBadge(text = "Your Fingerprint: ${it.fingerprint}")
                    }
                }
            }
        }

        // 5. Battery & OEM Optimization Guide
        item {
            MeshSectionCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text("Battery & OS Limitations", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        text = "Android restricts continuous background Bluetooth scanning to preserve battery. Bluetooth Global Share uses adaptive scan intervals and an explicit Foreground Service (connectedDevice) with a persistent notification. On Xiaomi, Samsung, Huawei, or OnePlus devices, aggressive OEM battery savers may pause background services unless exempted in Android Battery Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Battery Optimization Settings")
                    }
                }
            }
        }

        // 6. About Application
        item {
            MeshSectionCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("About Bluetooth Global Share", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        text = "100% offline, zero-cloud, zero-SIM decentralized Bluetooth peer-to-peer messenger, file sharer, and store-and-forward relay app. Delivery across long distances relies on physical movement and encounters between participating phones over time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
