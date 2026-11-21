package com.example.features.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.database.entities.ContactEntity
import com.example.core.models.TrustStatus
import com.example.ui.components.EmptyMeshState
import com.example.ui.components.MeshSectionCard
import com.example.ui.components.MonospaceBadge
import com.example.ui.components.QrMatrixView
import com.example.ui.components.StatusPill
import com.example.ui.theme.AmberRelay
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.EmeraldVerified
import com.example.ui.viewmodel.MainViewModel
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

@Composable
fun ContactsScreen(
    viewModel: MainViewModel,
    onOpenConversation: (conversationId: String, peerDeviceId: String) -> Unit
) {
    val context = LocalContext.current
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val myQrPayload by viewModel.myQrPayload.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val blocked by viewModel.blockedDevices.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showMyQrDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    var verifyTarget by remember { mutableStateOf<ContactEntity?>(null) }

    val qrImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream) ?: return@use
                    val pixels = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
                    val binary = BinaryBitmap(HybridBinarizer(source))
                    val result = MultiFormatReader().decode(binary)
                    importInput = result.text
                }
            } catch (_: Exception) {
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Contacts (${contacts.size})") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Blocked (${blocked.size})") }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                MeshSectionCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Offline Identity & QR Exchange", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Share your Ed25519 + X25519 public bundle offline via QR code or add another phone's bundle.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { showMyQrDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("show_my_qr_button")
                            ) {
                                Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("My QR Code")
                            }
                            FilledTonalButton(
                                onClick = { showImportDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("add_contact_bundle_button")
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add Contact")
                            }
                        }
                    }
                }
            }

            if (selectedTab == 0) {
                if (contacts.isEmpty()) {
                    item {
                        EmptyMeshState(
                            icon = Icons.Default.Contacts,
                            title = "No Offline Contacts Saved",
                            subtitle = "Pair with a nearby device in Nearby or import a contact's QR key bundle to start encrypted messaging."
                        )
                    }
                } else {
                    items(contacts, key = { it.deviceId }) { contact ->
                        MeshSectionCard {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            text = "ID: ${contact.deviceId}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    val color = when (contact.trustStatus) {
                                        TrustStatus.VERIFIED -> EmeraldVerified
                                        TrustStatus.TRUSTED -> ElectricBlue
                                        TrustStatus.PENDING -> AmberRelay
                                    }
                                    StatusPill(label = contact.trustStatus.name, color = color)
                                }
                                Spacer(Modifier.height(6.dp))
                                MonospaceBadge(text = "FP: ${contact.fingerprint}")
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            viewModel.openConversation(contact) { convId ->
                                                onOpenConversation(convId, contact.deviceId)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Message")
                                    }
                                    FilledTonalButton(onClick = { verifyTarget = contact }) {
                                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Verify")
                                    }
                                    IconButton(onClick = { viewModel.deleteContact(contact.deviceId) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete contact")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (blocked.isEmpty()) {
                    item {
                        EmptyMeshState(
                            icon = Icons.Default.Block,
                            title = "No Blocked Devices",
                            subtitle = "Blocked devices are rejected during Bluetooth handshakes and relay forwarding."
                        )
                    }
                } else {
                    items(blocked, key = { it.deviceId }) { dev ->
                        MeshSectionCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dev.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = dev.reason,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CoralAlert
                                    )
                                }
                                OutlinedButton(onClick = { viewModel.unblockPeer(dev.deviceId) }) {
                                    Text("Unblock")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMyQrDialog) {
        AlertDialog(
            onDismissRequest = { showMyQrDialog = false },
            title = { Text("My Contact QR Code") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    QrMatrixView(payload = myQrPayload, size = 210.dp)
                    Text(
                        text = profile?.displayName ?: "",
                        style = MaterialTheme.typography.titleMedium
                    )
                    MonospaceBadge(text = profile?.fingerprint ?: "")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("BGS Contact Bundle", myQrPayload))
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy Bundle")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMyQrDialog = false }) { Text("Close") }
            }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Add Offline Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Paste a contact's QR JSON bundle or decode a saved QR screenshot via Photo Picker.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = {
                            qrImagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Decode QR Image")
                    }
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = { importInput = it },
                        label = { Text("Contact Key Bundle JSON") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_contact_json_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.importContactQr(importInput) {
                            importInput = ""
                            showImportDialog = false
                        }
                    },
                    modifier = Modifier.testTag("confirm_import_contact_button")
                ) {
                    Text("Verify & Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }

    verifyTarget?.let { contact ->
        AlertDialog(
            onDismissRequest = { verifyTarget = null },
            title = { Text("Verify Safety Fingerprint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Contact: ${contact.displayName}", style = MaterialTheme.typography.titleMedium)
                    Text("Compare this 128-bit Ed25519 + X25519 fingerprint with your peer:")
                    MonospaceBadge(text = contact.fingerprint)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.verifyContact(contact.deviceId)
                        verifyTarget = null
                    }
                ) {
                    Text("Mark Verified")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            viewModel.blockPeer(
                                contact.deviceId,
                                null,
                                contact.displayName,
                                "Blocked by user"
                            )
                            verifyTarget = null
                        }
                    ) {
                        Text("Block", color = CoralAlert)
                    }
                    TextButton(onClick = { verifyTarget = null }) { Text("Close") }
                }
            }
        )
    }
}
