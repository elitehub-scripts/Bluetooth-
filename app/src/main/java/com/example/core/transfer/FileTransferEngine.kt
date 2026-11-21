package com.example.core.transfer

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.example.core.crypto.CryptoEngine
import com.example.core.database.daos.ApplicationSettingsDao
import com.example.core.database.daos.DeviceProfileDao
import com.example.core.database.daos.FileTransferDao
import com.example.core.database.entities.FileTransferEntity
import com.example.core.models.FileChunkAckPayload
import com.example.core.models.FileMetaPayload
import com.example.core.models.FileTransferDirection
import com.example.core.models.FileTransferStatus
import com.example.core.models.TransportType
import com.example.core.protocol.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface FilePacketSender {
    suspend fun sendPacket(peerDeviceId: String, type: PacketType, streamId: String, payload: ByteArray): Boolean
    fun getPeerTransportType(peerDeviceId: String): TransportType?
}

class FileTransferEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val cryptoEngine: CryptoEngine,
    private val fileTransferDao: FileTransferDao,
    private val profileDao: DeviceProfileDao,
    private val settingsDao: ApplicationSettingsDao,
    private val packetSender: FilePacketSender,
    private val onFileReceivedComplete: (fileName: String, fileSize: Long) -> Unit = { _, _ -> }
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tempDir = File(context.filesDir, "received_temp").apply { mkdirs() }
    private val completedDir = File(context.filesDir, "received_files").apply { mkdirs() }
    private val activeSendJobs = ConcurrentHashMap<String, Job>()
    private val pausedTransfers = ConcurrentHashMap.newKeySet<String>()
    private val cancelledTransfers = ConcurrentHashMap.newKeySet<String>()

    suspend fun queueOutgoingUri(
        uri: Uri,
        peerDeviceId: String,
        peerName: String
    ): Result<FileTransferEntity> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            var fileName = "shared_file_${System.currentTimeMillis()}"
            var fileSize = -1L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                    if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                }
            }
            val mimeType = resolver.getType(uri) ?: guessMimeFromName(fileName)

            val settings = settingsDao.getSettings()
            val maxBytes = (settings?.maxFileSizeMb ?: 250).toLong() * 1024L * 1024L

            // Stream into a bounded internal staged copy or compute SHA-256 via stream without loading into RAM
            val stagedFile = File(tempDir, "out_${UUID.randomUUID()}.stage")
            resolver.openInputStream(uri)?.use { input ->
                stagedFile.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes) {
                            stagedFile.delete()
                            return@withContext Result.failure(IllegalArgumentException("File exceeds ${settings?.maxFileSizeMb ?: 250} MB limit"))
                        }
                        output.write(buffer, 0, read)
                    }
                    fileSize = total
                }
            } ?: return@withContext Result.failure(IllegalStateException("Unable to open selected file"))

            val sha256 = FileInputStream(stagedFile).use { CryptoEngine.sha256Hex(it) }
            val transport = packetSender.getPeerTransportType(peerDeviceId)
            val chunkSize = if (transport == TransportType.BLE_GATT) 320 else 16 * 1024
            val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
            val now = System.currentTimeMillis()
            val transferId = UUID.randomUUID().toString()

            val entity = FileTransferEntity(
                transferId = transferId,
                peerDeviceId = peerDeviceId,
                peerName = peerName,
                direction = FileTransferDirection.OUTGOING,
                fileName = sanitizeFileName(fileName),
                mimeType = mimeType,
                fileSizeBytes = fileSize,
                sha256Checksum = sha256,
                localUriOrPath = stagedFile.absolutePath,
                tempFilePath = stagedFile.absolutePath,
                chunkSizeBytes = chunkSize,
                totalChunks = totalChunks,
                completedChunks = 0,
                bytesTransferred = 0L,
                status = FileTransferStatus.QUEUED,
                createdAt = now,
                updatedAt = now
            )
            fileTransferDao.upsert(entity)
            if (transport != null) {
                startOrResumeOutgoing(transferId)
            }
            Result.success(entity)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun startOrResumeOutgoing(transferId: String) {
        if (activeSendJobs[transferId]?.isActive == true) return
        pausedTransfers.remove(transferId)
        cancelledTransfers.remove(transferId)

        activeSendJobs[transferId] = scope.launch(Dispatchers.IO) {
            val transfer = fileTransferDao.getById(transferId) ?: return@launch
            if (transfer.direction != FileTransferDirection.OUTGOING) return@launch
            val sourceFile = File(transfer.localUriOrPath)
            if (!sourceFile.exists()) {
                fileTransferDao.updateProgress(
                    transferId,
                    FileTransferStatus.FAILED,
                    transfer.completedChunks,
                    transfer.bytesTransferred,
                    System.currentTimeMillis(),
                    "Local staged file no longer exists"
                )
                return@launch
            }

            val identity = cryptoEngine.getOrCreateIdentity()
            val displayName = profileDao.getProfile()?.displayName ?: "BGS Peer"
            val meta = FileMetaPayload(
                transferId = transfer.transferId,
                senderDeviceId = identity.deviceId,
                senderDisplayName = displayName,
                recipientDeviceId = transfer.peerDeviceId,
                fileName = transfer.fileName,
                mimeType = transfer.mimeType,
                fileSizeBytes = transfer.fileSizeBytes,
                sha256Checksum = transfer.sha256Checksum,
                chunkSizeBytes = transfer.chunkSizeBytes,
                totalChunks = transfer.totalChunks
            )
            val metaOk = packetSender.sendPacket(
                peerDeviceId = transfer.peerDeviceId,
                type = PacketType.FILE_META,
                streamId = transfer.transferId,
                payload = json.encodeToString(meta).toByteArray(Charsets.UTF_8)
            )
            if (!metaOk) {
                fileTransferDao.updateProgress(
                    transferId,
                    FileTransferStatus.QUEUED,
                    transfer.completedChunks,
                    transfer.bytesTransferred,
                    System.currentTimeMillis(),
                    "Peer not currently connected; queued for resume"
                )
                return@launch
            }

            fileTransferDao.updateProgress(
                transferId,
                FileTransferStatus.TRANSFERRING,
                transfer.completedChunks,
                transfer.bytesTransferred,
                System.currentTimeMillis()
            )

            RandomAccessFile(sourceFile, "r").use { raf ->
                val buffer = ByteArray(transfer.chunkSizeBytes)
                var chunkIndex = transfer.completedChunks
                var transferredBytes = chunkIndex.toLong() * transfer.chunkSizeBytes
                if (transferredBytes > transfer.fileSizeBytes) transferredBytes = transfer.fileSizeBytes
                raf.seek(transferredBytes)

                while (chunkIndex < transfer.totalChunks) {
                    if (cancelledTransfers.contains(transferId)) {
                        packetSender.sendPacket(transfer.peerDeviceId, PacketType.FILE_CANCEL, transferId, ByteArray(0))
                        fileTransferDao.updateProgress(
                            transferId,
                            FileTransferStatus.CANCELLED,
                            chunkIndex,
                            transferredBytes,
                            System.currentTimeMillis()
                        )
                        return@launch
                    }
                    if (pausedTransfers.contains(transferId)) {
                        packetSender.sendPacket(transfer.peerDeviceId, PacketType.FILE_PAUSE, transferId, ByteArray(0))
                        fileTransferDao.updateProgress(
                            transferId,
                            FileTransferStatus.PAUSED,
                            chunkIndex,
                            transferredBytes,
                            System.currentTimeMillis()
                        )
                        return@launch
                    }

                    val read = raf.read(buffer)
                    if (read <= 0) break

                    // Payload layout: [4 bytes chunkIndex][chunk bytes]
                    val packetPayload = ByteBuffer.allocate(4 + read)
                        .putInt(chunkIndex)
                        .put(buffer, 0, read)
                        .array()

                    val ok = packetSender.sendPacket(
                        peerDeviceId = transfer.peerDeviceId,
                        type = PacketType.FILE_CHUNK,
                        streamId = transfer.transferId,
                        payload = packetPayload
                    )
                    if (!ok) {
                        fileTransferDao.updateProgress(
                            transferId,
                            FileTransferStatus.PAUSED,
                            chunkIndex,
                            transferredBytes,
                            System.currentTimeMillis(),
                            "Bluetooth link interrupted; ready to resume"
                        )
                        return@launch
                    }

                    chunkIndex++
                    transferredBytes += read
                    if (chunkIndex % 4 == 0 || chunkIndex == transfer.totalChunks) {
                        fileTransferDao.updateProgress(
                            transferId,
                            FileTransferStatus.TRANSFERRING,
                            chunkIndex,
                            transferredBytes,
                            System.currentTimeMillis()
                        )
                    }
                    // Gentle pacing for BLE GATT links
                    if (transfer.chunkSizeBytes <= 512) {
                        delay(15L)
                    }
                }
            }
        }
    }

    suspend fun pauseTransfer(transferId: String) {
        pausedTransfers.add(transferId)
        val existing = fileTransferDao.getById(transferId) ?: return
        fileTransferDao.updateProgress(
            transferId,
            FileTransferStatus.PAUSED,
            existing.completedChunks,
            existing.bytesTransferred,
            System.currentTimeMillis()
        )
    }

    suspend fun cancelTransfer(transferId: String) {
        cancelledTransfers.add(transferId)
        activeSendJobs.remove(transferId)?.cancel()
        val existing = fileTransferDao.getById(transferId) ?: return
        existing.tempFilePath?.let { path ->
            File(path).delete()
        }
        fileTransferDao.updateProgress(
            transferId,
            FileTransferStatus.CANCELLED,
            existing.completedChunks,
            existing.bytesTransferred,
            System.currentTimeMillis()
        )
    }

    suspend fun onPeerConnected(peerDeviceId: String) {
        val queued = fileTransferDao.getQueuedForPeer(peerDeviceId)
        queued.forEach { startOrResumeOutgoing(it.transferId) }
    }

    suspend fun handleIncomingPacket(
        fromPeerId: String,
        fromPeerName: String,
        type: PacketType,
        streamId: String,
        payload: ByteArray
    ) = withContext(Dispatchers.IO) {
        when (type) {
            PacketType.FILE_META -> {
                val meta = try {
                    json.decodeFromString<FileMetaPayload>(String(payload, Charsets.UTF_8))
                } catch (_: Exception) {
                    return@withContext
                }
                val settings = settingsDao.getSettings()
                val maxAllowedBytes = (settings?.maxFileSizeMb ?: 250).toLong() * 1024L * 1024L
                val availableBytes = availableInternalBytes()
                if (meta.fileSizeBytes <= 0 || meta.fileSizeBytes > maxAllowedBytes || meta.fileSizeBytes > availableBytes - 10L * 1024L * 1024L) {
                    packetSender.sendPacket(fromPeerId, PacketType.FILE_CANCEL, meta.transferId, ByteArray(0))
                    fileTransferDao.upsert(
                        FileTransferEntity(
                            transferId = meta.transferId,
                            peerDeviceId = fromPeerId,
                            peerName = fromPeerName,
                            direction = FileTransferDirection.INCOMING,
                            fileName = sanitizeFileName(meta.fileName),
                            mimeType = meta.mimeType,
                            fileSizeBytes = meta.fileSizeBytes,
                            sha256Checksum = meta.sha256Checksum,
                            localUriOrPath = "",
                            tempFilePath = null,
                            chunkSizeBytes = meta.chunkSizeBytes,
                            totalChunks = meta.totalChunks,
                            completedChunks = 0,
                            bytesTransferred = 0L,
                            status = FileTransferStatus.FAILED,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            errorMessage = "Insufficient storage or exceeds size limit"
                        )
                    )
                    return@withContext
                }

                val existing = fileTransferDao.getById(meta.transferId)
                val tempFile = existing?.tempFilePath?.let { File(it) }
                    ?: File(tempDir, "${meta.transferId}.part")

                fileTransferDao.upsert(
                    FileTransferEntity(
                        transferId = meta.transferId,
                        peerDeviceId = fromPeerId,
                        peerName = fromPeerName,
                        direction = FileTransferDirection.INCOMING,
                        fileName = sanitizeFileName(meta.fileName),
                        mimeType = meta.mimeType,
                        fileSizeBytes = meta.fileSizeBytes,
                        sha256Checksum = meta.sha256Checksum,
                        localUriOrPath = "",
                        tempFilePath = tempFile.absolutePath,
                        chunkSizeBytes = meta.chunkSizeBytes,
                        totalChunks = meta.totalChunks,
                        completedChunks = existing?.completedChunks ?: 0,
                        bytesTransferred = existing?.bytesTransferred ?: 0L,
                        status = FileTransferStatus.TRANSFERRING,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            PacketType.FILE_CHUNK -> {
                if (payload.size < 4) return@withContext
                val buf = ByteBuffer.wrap(payload)
                val chunkIndex = buf.getInt()
                val chunkData = ByteArray(payload.size - 4)
                buf.get(chunkData)

                val transfer = fileTransferDao.getById(streamId) ?: return@withContext
                val tempPath = transfer.tempFilePath ?: return@withContext
                val tempFile = File(tempPath)
                if (chunkIndex < 0 || chunkIndex >= transfer.totalChunks) return@withContext

                val offset = chunkIndex.toLong() * transfer.chunkSizeBytes
                RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.seek(offset)
                    raf.write(chunkData)
                }

                val nextCompleted = maxOf(transfer.completedChunks, chunkIndex + 1)
                val newBytes = minOf(transfer.fileSizeBytes, offset + chunkData.size)

                if (nextCompleted >= transfer.totalChunks) {
                    val computedHash = FileInputStream(tempFile).use { CryptoEngine.sha256Hex(it) }
                    if (computedHash.equals(transfer.sha256Checksum, ignoreCase = true)) {
                        val finalFile = uniqueDestinationFile(transfer.fileName)
                        val moved = tempFile.renameTo(finalFile)
                        if (!moved) {
                            tempFile.copyTo(finalFile, overwrite = true)
                            tempFile.delete()
                        }
                        fileTransferDao.upsert(
                            transfer.copy(
                                localUriOrPath = finalFile.absolutePath,
                                tempFilePath = null,
                                completedChunks = transfer.totalChunks,
                                bytesTransferred = transfer.fileSizeBytes,
                                status = FileTransferStatus.COMPLETED,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        sendFileAck(fromPeerId, transfer.transferId, chunkIndex, transfer.fileSizeBytes, completed = true, integrityOk = true)
                        onFileReceivedComplete(transfer.fileName, transfer.fileSizeBytes)
                    } else {
                        tempFile.delete()
                        fileTransferDao.updateProgress(
                            transfer.transferId,
                            FileTransferStatus.INTEGRITY_FAILED,
                            nextCompleted,
                            newBytes,
                            System.currentTimeMillis(),
                            "SHA-256 verification failed; corrupted file discarded"
                        )
                        sendFileAck(fromPeerId, transfer.transferId, chunkIndex, newBytes, completed = true, integrityOk = false)
                    }
                } else {
                    if (nextCompleted % 4 == 0) {
                        fileTransferDao.updateProgress(
                            transfer.transferId,
                            FileTransferStatus.TRANSFERRING,
                            nextCompleted,
                            newBytes,
                            System.currentTimeMillis()
                        )
                    }
                }
            }

            PacketType.FILE_ACK -> {
                val ack = try {
                    json.decodeFromString<FileChunkAckPayload>(String(payload, Charsets.UTF_8))
                } catch (_: Exception) {
                    return@withContext
                }
                val transfer = fileTransferDao.getById(ack.transferId) ?: return@withContext
                if (ack.completed) {
                    val status = if (ack.integrityOk) FileTransferStatus.COMPLETED else FileTransferStatus.INTEGRITY_FAILED
                    fileTransferDao.updateProgress(
                        transfer.transferId,
                        status,
                        transfer.totalChunks,
                        transfer.fileSizeBytes,
                        System.currentTimeMillis(),
                        if (ack.integrityOk) null else "Recipient reported SHA-256 mismatch"
                    )
                    // Clean up staged outgoing copy once confirmed
                    transfer.tempFilePath?.let { File(it).delete() }
                }
            }

            PacketType.FILE_PAUSE -> {
                val transfer = fileTransferDao.getById(streamId) ?: return@withContext
                fileTransferDao.updateProgress(
                    streamId,
                    FileTransferStatus.PAUSED,
                    transfer.completedChunks,
                    transfer.bytesTransferred,
                    System.currentTimeMillis()
                )
            }

            PacketType.FILE_CANCEL -> {
                activeSendJobs.remove(streamId)?.cancel()
                val transfer = fileTransferDao.getById(streamId) ?: return@withContext
                transfer.tempFilePath?.let { File(it).delete() }
                fileTransferDao.updateProgress(
                    streamId,
                    FileTransferStatus.CANCELLED,
                    transfer.completedChunks,
                    transfer.bytesTransferred,
                    System.currentTimeMillis()
                )
            }

            else -> Unit
        }
    }

    private suspend fun sendFileAck(
        peerDeviceId: String,
        transferId: String,
        chunkIndex: Int,
        receivedBytes: Long,
        completed: Boolean,
        integrityOk: Boolean
    ) {
        val ack = FileChunkAckPayload(
            transferId = transferId,
            ackedChunkIndex = chunkIndex,
            receivedBytes = receivedBytes,
            completed = completed,
            integrityOk = integrityOk
        )
        packetSender.sendPacket(
            peerDeviceId = peerDeviceId,
            type = PacketType.FILE_ACK,
            streamId = transferId,
            payload = json.encodeToString(ack).toByteArray(Charsets.UTF_8)
        )
    }

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()
        val safe = if (cleaned.isEmpty() || cleaned == "." || cleaned == ".." || cleaned.contains("..")) {
            "received_file_${System.currentTimeMillis()}"
        } else {
            cleaned
        }
        return safe.take(120)
    }

    private fun uniqueDestinationFile(name: String): File {
        val baseDirCanonical = completedDir.canonicalPath
        var candidate = File(completedDir, sanitizeFileName(name))
        require(candidate.canonicalPath.startsWith(baseDirCanonical + File.separator)) {
            "Unsafe destination file path"
        }
        if (!candidate.exists()) return candidate
        val safeName = candidate.name
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var count = 1
        while (candidate.exists()) {
            candidate = File(completedDir, "${base}_($count)$ext")
            count++
        }
        return candidate
    }

    fun availableInternalBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            if (stat.blockCountLong <= 0L) 512L * 1024L * 1024L else stat.availableBytes
        } catch (_: Exception) {
            512L * 1024L * 1024L
        }
    }

    fun usedAppStorageBytes(): Long {
        val tempBytes = tempDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val recvBytes = completedDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return tempBytes + recvBytes
    }

    private fun guessMimeFromName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
