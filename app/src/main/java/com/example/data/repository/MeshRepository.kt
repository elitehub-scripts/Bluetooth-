package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.core.bluetooth.BluetoothHardwareStatus
import com.example.core.bluetooth.BluetoothTransportManager
import com.example.core.bluetooth.ConnectedPeerInfo
import com.example.core.crypto.CryptoEngine
import com.example.core.database.AppDatabase
import com.example.core.database.entities.ApplicationSettingsEntity
import com.example.core.database.entities.BlockedDeviceEntity
import com.example.core.database.entities.ConnectionHistoryEntity
import com.example.core.database.entities.ContactEntity
import com.example.core.database.entities.ConversationEntity
import com.example.core.database.entities.DeviceProfileEntity
import com.example.core.database.entities.FileTransferEntity
import com.example.core.database.entities.MessageEntity
import com.example.core.database.entities.NetworkNodeEntity
import com.example.core.database.entities.RelayHistoryEntity
import com.example.core.database.entities.RelayMessageEntity
import com.example.core.database.entities.TrustedDeviceEntity
import com.example.core.models.AppThemeMode
import com.example.core.models.BleScanModeSetting
import com.example.core.models.ContactQrBundle
import com.example.core.models.DiscoveredDeviceUi
import com.example.core.models.MessageDeliveryStatus
import com.example.core.models.MessageDirection
import com.example.core.models.RelayEnvelope
import com.example.core.models.RelayState
import com.example.core.models.TransportType
import com.example.core.models.TrustStatus
import com.example.core.protocol.PacketType
import com.example.core.relay.RelayEngine
import com.example.core.transfer.FileTransferEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

data class DecryptedMessageUi(
    val entity: MessageEntity,
    val plaintext: String
)

class MeshRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val cryptoEngine: CryptoEngine,
    val transportManager: BluetoothTransportManager,
    private val relayEngine: RelayEngine,
    private val fileTransferEngine: FileTransferEngine
) {
    private val json = Json { ignoreUnknownKeys = true }

    val deviceProfile: Flow<DeviceProfileEntity?> = db.deviceProfileDao().observeProfile()
    val settings: Flow<ApplicationSettingsEntity> = db.applicationSettingsDao().observeSettings()
        .map { it ?: ApplicationSettingsEntity() }
    val contacts: Flow<List<ContactEntity>> = db.contactDao().observeAllContacts()
    val trustedDevices: Flow<List<TrustedDeviceEntity>> = db.trustedDeviceDao().observeAll()
    val blockedDevices: Flow<List<BlockedDeviceEntity>> = db.blockedDeviceDao().observeAll()
    val conversations: Flow<List<ConversationEntity>> = db.conversationDao().observeAll()
    val pendingMessagesCount: Flow<Int> = db.messageDao().observePendingCount()
    val relayMessages: Flow<List<RelayMessageEntity>> = db.relayMessageDao().observeAll()
    val activeRelayCount: Flow<Int> = db.relayMessageDao().observeActiveCount()
    val relayTotalBytes: Flow<Long> = db.relayMessageDao().observeTotalBytes()
    val relayHistory: Flow<List<RelayHistoryEntity>> = db.relayHistoryDao().observeRecent()
    val fileTransfers: Flow<List<FileTransferEntity>> = db.fileTransferDao().observeAll()
    val pendingFileTransfersCount: Flow<Int> = db.fileTransferDao().observePendingCount()
    val networkNodes: Flow<List<NetworkNodeEntity>> = db.networkNodeDao().observeAll()
    val connectionHistory: Flow<List<ConnectionHistoryEntity>> = db.connectionHistoryDao().observeRecent()

    val hardwareStatus: StateFlow<BluetoothHardwareStatus> = transportManager.hardwareStatus
    val connectedPeers: StateFlow<Map<String, ConnectedPeerInfo>> = transportManager.connectedPeers
    val discoveredDevices: StateFlow<List<DiscoveredDeviceUi>> = transportManager.discoveredDevices
    val isScanning: StateFlow<Boolean> = transportManager.discoveryAndAdvertiser.isScanning
    val isAdvertising: StateFlow<Boolean> = transportManager.discoveryAndAdvertiser.isAdvertising
    val transportError: StateFlow<String?> = transportManager.statusBannerError

    suspend fun ensureInitialized() {
        val identity = cryptoEngine.getOrCreateIdentity()
        if (db.deviceProfileDao().getProfile() == null) {
            val defaultName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
                .take(24)
                .ifBlank { "Android Mesh Node" }
            val now = System.currentTimeMillis()
            db.deviceProfileDao().upsert(
                DeviceProfileEntity(
                    deviceId = identity.deviceId,
                    displayName = defaultName,
                    avatarColorHex = 0xFF00E5FF,
                    ed25519PublicKeyBase64 = identity.ed25519PublicKeyBase64,
                    x25519PublicKeyBase64 = identity.x25519PublicKeyBase64,
                    fingerprint = identity.fingerprint,
                    keystoreBacked = identity.keystoreBacked,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        if (db.applicationSettingsDao().getSettings() == null) {
            db.applicationSettingsDao().upsert(ApplicationSettingsEntity())
        }
    }

    suspend fun updateDisplayName(newName: String) {
        val trimmed = newName.trim().take(32)
        if (trimmed.isEmpty()) return
        val identity = cryptoEngine.getOrCreateIdentity()
        db.deviceProfileDao().updateDisplayName(identity.deviceId, trimmed, System.currentTimeMillis())
    }

    suspend fun buildMyContactQrJson(): String {
        ensureInitialized()
        val profile = db.deviceProfileDao().getProfile()!!
        val bundle = ContactQrBundle(
            deviceId = profile.deviceId,
            displayName = profile.displayName,
            ed25519PublicKey = profile.ed25519PublicKeyBase64,
            x25519PublicKey = profile.x25519PublicKeyBase64,
            fingerprint = profile.fingerprint,
            createdAt = System.currentTimeMillis()
        )
        return json.encodeToString(bundle)
    }

    suspend fun importContactQrJson(rawJson: String, markVerified: Boolean = false): Result<ContactEntity> {
        return try {
            val bundle = json.decodeFromString<ContactQrBundle>(rawJson.trim())
            val edPub = CryptoEngine.fromBase64(bundle.ed25519PublicKey)
            val xPub = CryptoEngine.fromBase64(bundle.x25519PublicKey)
            require(edPub.size == 32 && xPub.size == 32) { "Invalid public key sizes" }
            val derivedId = CryptoEngine.deriveDeviceId(edPub)
            require(derivedId == bundle.deviceId) { "Contact deviceId does not match Ed25519 key" }
            val expectedFp = CryptoEngine.computeFingerprint(edPub, xPub)
            require(expectedFp == bundle.fingerprint) { "Fingerprint mismatch" }

            val localId = cryptoEngine.getOrCreateIdentity().deviceId
            require(bundle.deviceId != localId) { "Cannot add your own device as a contact" }

            val now = System.currentTimeMillis()
            val trust = if (markVerified) TrustStatus.VERIFIED else TrustStatus.TRUSTED
            val contact = ContactEntity(
                deviceId = bundle.deviceId,
                displayName = bundle.displayName.take(32),
                ed25519PublicKeyBase64 = bundle.ed25519PublicKey,
                x25519PublicKeyBase64 = bundle.x25519PublicKey,
                fingerprint = expectedFp,
                trustStatus = trust,
                lastSeenAt = now,
                lastTransport = null
            )
            db.contactDao().upsert(contact)
            db.trustedDeviceDao().upsert(
                TrustedDeviceEntity(
                    deviceId = bundle.deviceId,
                    displayName = contact.displayName,
                    ed25519PublicKeyBase64 = contact.ed25519PublicKeyBase64,
                    x25519PublicKeyBase64 = contact.x25519PublicKeyBase64,
                    fingerprint = expectedFp,
                    verifiedManually = markVerified,
                    trustedAt = now
                )
            )
            Result.success(contact)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun approveConnectedPeerAsContact(peer: ConnectedPeerInfo, verifiedManually: Boolean) {
        val now = System.currentTimeMillis()
        val trust = if (verifiedManually) TrustStatus.VERIFIED else TrustStatus.TRUSTED
        val contact = ContactEntity(
            deviceId = peer.deviceId,
            displayName = peer.displayName,
            ed25519PublicKeyBase64 = peer.ed25519PublicKeyBase64,
            x25519PublicKeyBase64 = peer.x25519PublicKeyBase64,
            fingerprint = peer.fingerprint,
            trustStatus = trust,
            lastSeenAt = now,
            lastTransport = peer.transportType
        )
        db.contactDao().upsert(contact)
        db.trustedDeviceDao().upsert(
            TrustedDeviceEntity(
                deviceId = peer.deviceId,
                displayName = peer.displayName,
                ed25519PublicKeyBase64 = peer.ed25519PublicKeyBase64,
                x25519PublicKeyBase64 = peer.x25519PublicKeyBase64,
                fingerprint = peer.fingerprint,
                verifiedManually = verifiedManually,
                trustedAt = now
            )
        )
        db.networkNodeDao().upsert(
            NetworkNodeEntity(
                nodeId = peer.deviceId,
                displayName = peer.displayName,
                bluetoothAddress = peer.address,
                isCurrentlyConnected = true,
                isRelayCapable = true,
                lastRssi = null,
                hopDistance = 1,
                lastSeenAt = now,
                trustStatus = trust
            )
        )
    }

    suspend fun verifyContactManually(deviceId: String) {
        val contact = db.contactDao().getById(deviceId) ?: return
        db.contactDao().updateTrustStatus(deviceId, TrustStatus.VERIFIED)
        db.trustedDeviceDao().upsert(
            TrustedDeviceEntity(
                deviceId = contact.deviceId,
                displayName = contact.displayName,
                ed25519PublicKeyBase64 = contact.ed25519PublicKeyBase64,
                x25519PublicKeyBase64 = contact.x25519PublicKeyBase64,
                fingerprint = contact.fingerprint,
                verifiedManually = true,
                trustedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun blockPeer(deviceId: String, bluetoothAddress: String?, displayName: String, reason: String) {
        db.blockedDeviceDao().upsert(
            BlockedDeviceEntity(
                deviceId = deviceId,
                bluetoothAddress = bluetoothAddress,
                displayName = displayName,
                reason = reason,
                blockedAt = System.currentTimeMillis()
            )
        )
        db.trustedDeviceDao().deleteById(deviceId)
        db.contactDao().updateTrustStatus(deviceId, TrustStatus.PENDING)
        transportManager.disconnectPeer(deviceId)
    }

    suspend fun unblockPeer(deviceId: String) {
        db.blockedDeviceDao().unblock(deviceId)
    }

    suspend fun deleteContact(deviceId: String) {
        db.contactDao().deleteById(deviceId)
        db.trustedDeviceDao().deleteById(deviceId)
    }

    // --- Offline Messaging ---

    suspend fun openOrCreateConversation(contact: ContactEntity): String {
        val convId = "conv-${contact.deviceId}"
        if (db.conversationDao().getById(convId) == null) {
            db.conversationDao().upsert(
                ConversationEntity(
                    conversationId = convId,
                    peerDeviceId = contact.deviceId,
                    peerDisplayName = contact.displayName,
                    lastMessageEncryptedPreview = "",
                    lastMessagePlainFallback = "Encrypted channel ready",
                    lastMessageAt = System.currentTimeMillis(),
                    lastMessageStatus = MessageDeliveryStatus.CREATED,
                    unreadCount = 0
                )
            )
        }
        return convId
    }

    fun observeMessagesForConversation(conversationId: String): Flow<List<DecryptedMessageUi>> {
        return db.messageDao().observeForConversation(conversationId).map { list ->
            list.map { entity ->
                val text = try {
                    cryptoEngine.decryptLocalSecretText(entity.localEncryptedTextBase64, entity.messageId)
                } catch (_: Exception) {
                    "[Encrypted message]"
                }
                DecryptedMessageUi(entity = entity, plaintext = text)
            }
        }
    }

    suspend fun markConversationRead(conversationId: String) {
        db.conversationDao().clearUnread(conversationId)
    }

    suspend fun sendEncryptedMessage(
        recipientDeviceId: String,
        plaintext: String,
        isPriority: Boolean = false
    ): Result<MessageEntity> {
        val trimmed = plaintext.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Message cannot be empty"))

        val contact = db.contactDao().getById(recipientDeviceId)
            ?: return Result.failure(IllegalStateException("Recipient is not in contacts"))
        if (db.blockedDeviceDao().isBlocked(recipientDeviceId, null)) {
            return Result.failure(IllegalStateException("Recipient is blocked"))
        }

        val identity = cryptoEngine.getOrCreateIdentity()
        val profileName = db.deviceProfileDao().getProfile()?.displayName ?: "BGS User"
        val settings = db.applicationSettingsDao().getSettings() ?: ApplicationSettingsEntity()

        val now = System.currentTimeMillis()
        val ttlMillis = settings.defaultMessageTtlHours.toLong() * 3600L * 1000L
        val expiresAt = now + ttlMillis
        val messageId = UUID.randomUUID().toString()
        val conversationId = "conv-$recipientDeviceId"

        val (ciphertext, signature) = cryptoEngine.encryptForRecipient(
            plaintext = trimmed,
            recipientX25519PubBase64 = contact.x25519PublicKeyBase64,
            messageId = messageId,
            recipientId = recipientDeviceId,
            createdAt = now,
            expiresAt = expiresAt
        )

        val localEncText = cryptoEngine.encryptLocalSecretText(trimmed, messageId)
        val localEncPreview = cryptoEngine.encryptLocalSecretText(trimmed.take(80), conversationId)

        val envelope = RelayEnvelope(
            messageId = messageId,
            originDeviceId = identity.deviceId,
            originDisplayName = profileName,
            originEd25519Pub = identity.ed25519PublicKeyBase64,
            originX25519Pub = identity.x25519PublicKeyBase64,
            recipientDeviceId = recipientDeviceId,
            encryptedPayloadBase64 = ciphertext,
            signatureBase64 = signature,
            hopCount = 0,
            maxHops = settings.defaultMaxHops,
            isPriority = isPriority,
            createdAt = now,
            expiresAt = expiresAt
        )
        val envBytes = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)

        // Try direct delivery first if recipient is physically connected
        val connectedPeersMap = transportManager.connectedPeers.value
        val directPeer = connectedPeersMap[recipientDeviceId]
        var initialStatus = MessageDeliveryStatus.CREATED
        var usedTransport: TransportType? = null

        if (directPeer != null) {
            val sentDirect = transportManager.sendPacketToPeer(
                peerDeviceId = recipientDeviceId,
                type = PacketType.MSG_ENVELOPE,
                streamId = messageId,
                payload = envBytes
            )
            if (sentDirect) {
                // Sent directly over air; stays CREATED/RELAYED until recipient returns signed DeliveryAckEnvelope!
                initialStatus = MessageDeliveryStatus.RELAYED
                usedTransport = directPeer.transportType
            } else {
                initialStatus = MessageDeliveryStatus.WAITING_FOR_RELAY
            }
        } else {
            // Try forwarding immediately to any connected relay peers
            var relayedToAny = false
            for (relayPeer in connectedPeersMap.values) {
                if (transportManager.sendPacketToPeer(
                        peerDeviceId = relayPeer.deviceId,
                        type = PacketType.RELAY_ENVELOPE,
                        streamId = messageId,
                        payload = envBytes
                    )
                ) {
                    relayedToAny = true
                }
            }
            initialStatus = if (relayedToAny) {
                MessageDeliveryStatus.RELAYED
            } else {
                MessageDeliveryStatus.WAITING_FOR_RELAY
            }
            if (relayedToAny) usedTransport = TransportType.RELAY
        }

        val messageEntity = MessageEntity(
            messageId = messageId,
            conversationId = conversationId,
            senderId = identity.deviceId,
            recipientId = recipientDeviceId,
            ciphertextBase64 = ciphertext,
            localEncryptedTextBase64 = localEncText,
            signatureBase64 = signature,
            direction = MessageDirection.OUTGOING,
            status = initialStatus,
            isPriority = isPriority,
            hopCount = 0,
            maxHops = settings.defaultMaxHops,
            ttlMillis = ttlMillis,
            createdAt = now,
            expiresAt = expiresAt,
            transportUsed = usedTransport
        )

        val convEntity = ConversationEntity(
            conversationId = conversationId,
            peerDeviceId = recipientDeviceId,
            peerDisplayName = contact.displayName,
            lastMessageEncryptedPreview = localEncPreview,
            lastMessagePlainFallback = trimmed.take(48),
            lastMessageAt = now,
            lastMessageStatus = initialStatus,
            unreadCount = 0
        )

        val relayEntity = RelayMessageEntity(
            messageId = messageId,
            originDeviceId = identity.deviceId,
            originDisplayName = profileName,
            originEd25519PubBase64 = identity.ed25519PublicKeyBase64,
            originX25519PubBase64 = identity.x25519PublicKeyBase64,
            recipientDeviceId = recipientDeviceId,
            encryptedPayloadBase64 = ciphertext,
            signatureBase64 = signature,
            hopCount = 0,
            maxHops = settings.defaultMaxHops,
            isPriority = isPriority,
            createdAt = now,
            expiresAt = expiresAt,
            receivedAt = now,
            relayState = if (initialStatus == MessageDeliveryStatus.RELAYED) RelayState.FORWARDED else RelayState.QUEUED,
            payloadSizeBytes = envBytes.size
        )

        db.meshTransactionDao().saveOutgoingMessageAndRelay(
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            relayDao = db.relayMessageDao(),
            message = messageEntity,
            conversation = convEntity,
            relayMessage = relayEntity
        )

        return Result.success(messageEntity)
    }

    suspend fun deleteMessage(messageId: String) {
        db.messageDao().deleteById(messageId)
        db.relayMessageDao().deleteById(messageId)
    }

    // --- File Sharing ---

    suspend fun sendFileToPeer(uri: Uri, peerDeviceId: String, peerName: String): Result<FileTransferEntity> {
        return fileTransferEngine.queueOutgoingUri(uri, peerDeviceId, peerName)
    }

    suspend fun pauseFileTransfer(transferId: String) = fileTransferEngine.pauseTransfer(transferId)
    fun resumeFileTransfer(transferId: String) = fileTransferEngine.startOrResumeOutgoing(transferId)
    suspend fun cancelFileTransfer(transferId: String) = fileTransferEngine.cancelTransfer(transferId)

    fun usedFileStorageBytes(): Long = fileTransferEngine.usedAppStorageBytes()
    fun availableStorageBytes(): Long = fileTransferEngine.availableInternalBytes()

    // --- Settings & Maintenance ---

    suspend fun updateSettings(transform: (ApplicationSettingsEntity) -> ApplicationSettingsEntity) {
        val current = db.applicationSettingsDao().getSettings() ?: ApplicationSettingsEntity()
        db.applicationSettingsDao().upsert(transform(current))
    }

    suspend fun setThemeMode(mode: AppThemeMode) = updateSettings { it.copy(themeMode = mode) }
    suspend fun setScanMode(mode: BleScanModeSetting) {
        updateSettings { it.copy(scanMode = mode) }
        if (isScanning.value) {
            transportManager.discoveryAndAdvertiser.startAdaptiveScanning(mode)
        }
    }

    suspend fun cleanupExpiredRelays(): Int = relayEngine.cleanupExpired()
}
