package com.example.features.files

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.database.entities.FileTransferEntity
import com.example.core.models.FileTransferDirection
import com.example.core.models.FileTransferStatus
import com.example.ui.components.EmptyMeshState
import com.example.ui.components.FileStatusPill
import com.example.ui.components.MeshSectionCard
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.AmberRelay
import com.example.ui.theme.CoralAlert
import com.example.ui.viewmodel.MainViewModel
import java.io.File

@Composable
fun FileSharingScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val transfers by viewModel.fileTransfers.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var showPeerPicker by remember { mutableStateOf(false) }
    var apkWarningTarget by remember { mutableStateOf<FileTransferEntity?>(null) }
    var saveCopyTarget by remember { mutableStateOf<FileTransferEntity?>(null) }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            showPeerPicker = true
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            showPeerPicker = true
        }
    }

    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destUri ->
        val source = saveCopyTarget ?: return@rememberLauncherForActivityResult
        saveCopyTarget = null
        if (destUri != null && source.localUriOrPath.isNotBlank()) {
            try {
                File(source.localUriOrPath).inputStream().use { input ->
                    context.contentResolver.openOutputStream(destUri)?.use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    val activeList = transfers.filter {
        it.status == FileTransferStatus.QUEUED ||
            it.status == FileTransferStatus.TRANSFERRING ||
            it.status == FileTransferStatus.PAUSED
    }
    val historyList = transfers.filter { it !in activeList }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Send File Actions Card
        item {
            MeshSectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.UploadFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Offline Chunked File Transfer", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "Streams chunks with SHA-256 verification • Max ${settings.maxFileSizeMb} MB",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                mediaPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pick_photo_video_button")
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Photo / Video")
                        }

                        FilledTonalButton(
                            onClick = { documentPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pick_any_file_button")
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Docs / ZIP / APK")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Available internal storage: ${formatByteSize(viewModel.availableStorageBytes())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Active Transfers
        item {
            Text(
                text = "Active & Queued Transfers (${activeList.size})",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (activeList.isEmpty()) {
            item {
                EmptyMeshState(
                    icon = Icons.Default.FolderZip,
                    title = "No Active File Transfers",
                    subtitle = "Select a Photo, Video, Audio, PDF, ZIP, or APK to stream in verified chunks over Classic RFCOMM or BLE GATT."
                )
            }
        } else {
            items(activeList, key = { it.transferId }) { transfer ->
                FileTransferCard(
                    transfer = transfer,
                    onPause = { viewModel.pauseTransfer(transfer.transferId) },
                    onResume = { viewModel.resumeTransfer(transfer.transferId) },
                    onCancel = { viewModel.cancelTransfer(transfer.transferId) },
                    onOpen = {},
                    onSaveCopy = {}
                )
            }
        }

        // Transfer History
        item {
            Text(
                text = "Transfer History (${historyList.size})",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (historyList.isNotEmpty()) {
            items(historyList, key = { it.transferId }) { transfer ->
                FileTransferCard(
                    transfer = transfer,
                    onPause = {},
                    onResume = {},
                    onCancel = {},
                    onOpen = {
                        if (transfer.fileName.endsWith(".apk", ignoreCase = true) ||
                            transfer.mimeType == "application/vnd.android.package-archive"
                        ) {
                            apkWarningTarget = transfer
                        } else {
                            openFileSafely(context, transfer)
                        }
                    },
                    onSaveCopy = {
                        saveCopyTarget = transfer
                        exportDocumentLauncher.launch(transfer.fileName)
                    }
                )
            }
        }
    }

    if (showPeerPicker && pendingUri != null) {
        val candidates = buildList {
            connectedPeers.values.forEach { add(it.deviceId to "${it.displayName} (Connected • ${it.transportType.label})") }
            contacts.filter { !connectedPeers.containsKey(it.deviceId) }.forEach {
                add(it.deviceId to "${it.displayName} (Offline — Queued)")
            }
        }

        AlertDialog(
            onDismissRequest = {
                showPeerPicker = false
                pendingUri = null
            },
            title = { Text("Select Recipient Peer") },
            text = {
                if (candidates.isEmpty()) {
                    Text("No connected peers or contacts available. Connect to a nearby phone or add a contact first.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(220.dp)
                    ) {
                        items(candidates, key = { it.first }) { (peerId, label) ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val uri = pendingUri!!
                                        val cleanName = label.substringBefore(" (")
                                        viewModel.sendFile(uri, peerId, cleanName)
                                        showPeerPicker = false
                                        pendingUri = null
                                    }
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        showPeerPicker = false
                        pendingUri = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    apkWarningTarget?.let { apkTransfer ->
        AlertDialog(
            onDismissRequest = { apkWarningTarget = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = AmberRelay) },
            title = { Text("APK Package Safety Notice") },
            text = {
                Text(
                    "Bluetooth Global Share never auto-installs received APK files. Only export or inspect '${apkTransfer.fileName}' if you verified the sender's identity fingerprint."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        saveCopyTarget = apkTransfer
                        apkWarningTarget = null
                        exportDocumentLauncher.launch(apkTransfer.fileName)
                    }
                ) {
                    Text("Save Copy Instead")
                }
            },
            dismissButton = {
                TextButton(onClick = { apkWarningTarget = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun FileTransferCard(
    transfer: FileTransferEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onSaveCopy: () -> Unit
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
                        text = transfer.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(if (transfer.direction == FileTransferDirection.OUTGOING) "To: " else "From: ")
                            append(transfer.peerName)
                            append(" • ${formatByteSize(transfer.fileSizeBytes)}")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FileStatusPill(status = transfer.status)
            }

            Spacer(Modifier.height(8.dp))
            val fraction = if (transfer.fileSizeBytes > 0) {
                (transfer.bytesTransferred.toFloat() / transfer.fileSizeBytes.toFloat()).coerceIn(0f, 1f)
            } else 0f

            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Chunks ${transfer.completedChunks}/${transfer.totalChunks}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "SHA-256: ${transfer.sha256Checksum.take(12)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (!transfer.errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = transfer.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoralAlert
                )
            }

            Spacer(Modifier.height(10.dp))

            when (transfer.status) {
                FileTransferStatus.TRANSFERRING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pause")
                        }
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                }
                FileTransferStatus.PAUSED,
                FileTransferStatus.QUEUED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (transfer.direction == FileTransferDirection.OUTGOING) {
                            Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Resume")
                            }
                        }
                        OutlinedButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                }
                FileTransferStatus.COMPLETED -> {
                    if (transfer.direction == FileTransferDirection.INCOMING && transfer.localUriOrPath.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Open")
                            }
                            OutlinedButton(onClick = onSaveCopy, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Save Copy")
                            }
                        }
                    } else {
                        Text(
                            text = "Completed ${formatTimestamp(transfer.updatedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    Text(
                        text = formatTimestamp(transfer.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun openFileSafely(context: android.content.Context, transfer: FileTransferEntity) {
    val file = File(transfer.localUriOrPath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, transfer.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Open ${transfer.fileName}"))
    } catch (_: Exception) {
    }
}
