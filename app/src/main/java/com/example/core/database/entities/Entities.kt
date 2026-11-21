package com.example.core.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.core.models.AppThemeMode
import com.example.core.models.BleScanModeSetting
import com.example.core.models.FileTransferDirection
import com.example.core.models.FileTransferStatus
import com.example.core.models.MessageDeliveryStatus
import com.example.core.models.MessageDirection
import com.example.core.models.RelayAction
import com.example.core.models.RelayState
import com.example.core.models.TransportType
import com.example.core.models.TrustStatus

@Entity(tableName = "device_profile")
data class DeviceProfileEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val avatarColorHex: Long,
    val ed25519PublicKeyBase64: String,
    val x25519PublicKeyBase64: String,
    val fingerprint: String,
    val keystoreBacked: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["trustStatus"]),
        Index(value = ["lastSeenAt"])
    ]
)
data class ContactEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val ed25519PublicKeyBase64: String,
    val x25519PublicKeyBase64: String,
    val fingerprint: String,
    val trustStatus: TrustStatus = TrustStatus.PENDING,
    val lastSeenAt: Long,
    val lastTransport: TransportType? = null,
    val notes: String = ""
)

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["peerDeviceId"], unique = true),
        Index(value = ["lastMessageAt"])
    ]
)
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val peerDeviceId: String,
    val peerDisplayName: String,
    val lastMessageEncryptedPreview: String,
    val lastMessagePlainFallback: String,
    val lastMessageAt: Long,
    val lastMessageStatus: MessageDeliveryStatus,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "createdAt"]),
        Index(value = ["senderId"]),
        Index(value = ["recipientId"]),
        Index(value = ["status"]),
        Index(value = ["expiresAt"])
    ]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val ciphertextBase64: String,
    val localEncryptedTextBase64: String,
    val signatureBase64: String,
    val direction: MessageDirection,
    val status: MessageDeliveryStatus,
    val isPriority: Boolean = false,
    val hopCount: Int = 0,
    val maxHops: Int = 8,
    val ttlMillis: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val deliveredAt: Long? = null,
    val transportUsed: TransportType? = null
)

@Entity(
    tableName = "relay_messages",
    indices = [
        Index(value = ["recipientDeviceId"]),
        Index(value = ["relayState", "isPriority", "createdAt"]),
        Index(value = ["expiresAt"])
    ]
)
data class RelayMessageEntity(
    @PrimaryKey val messageId: String,
    val originDeviceId: String,
    val originDisplayName: String,
    val originEd25519PubBase64: String,
    val originX25519PubBase64: String,
    val recipientDeviceId: String,
    val encryptedPayloadBase64: String,
    val signatureBase64: String,
    val hopCount: Int,
    val maxHops: Int,
    val isPriority: Boolean,
    val createdAt: Long,
    val expiresAt: Long,
    val receivedAt: Long,
    val relayState: RelayState,
    val forwardedCount: Int = 0,
    val lastForwardedAt: Long? = null,
    val payloadSizeBytes: Int
)

@Entity(
    tableName = "relay_history",
    indices = [
        Index(value = ["messageId"]),
        Index(value = ["timestamp"])
    ]
)
data class RelayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val peerDeviceId: String,
    val peerName: String,
    val action: RelayAction,
    val hopNumber: Int,
    val timestamp: Long,
    val details: String
)

@Entity(
    tableName = "file_transfers",
    indices = [
        Index(value = ["peerDeviceId"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class FileTransferEntity(
    @PrimaryKey val transferId: String,
    val peerDeviceId: String,
    val peerName: String,
    val direction: FileTransferDirection,
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val sha256Checksum: String,
    val localUriOrPath: String,
    val tempFilePath: String? = null,
    val chunkSizeBytes: Int,
    val totalChunks: Int,
    val completedChunks: Int,
    val bytesTransferred: Long,
    val status: FileTransferStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val errorMessage: String? = null
)

@Entity(tableName = "trusted_devices")
data class TrustedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val ed25519PublicKeyBase64: String,
    val x25519PublicKeyBase64: String,
    val fingerprint: String,
    val verifiedManually: Boolean,
    val trustedAt: Long
)

@Entity(
    tableName = "blocked_devices",
    indices = [Index(value = ["bluetoothAddress"])]
)
data class BlockedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val bluetoothAddress: String? = null,
    val displayName: String,
    val reason: String,
    val blockedAt: Long
)

@Entity(
    tableName = "connection_history",
    indices = [
        Index(value = ["peerDeviceId"]),
        Index(value = ["connectedAt"])
    ]
)
data class ConnectionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerDeviceId: String,
    val bluetoothAddress: String,
    val peerName: String,
    val transportType: TransportType,
    val connectedAt: Long,
    val disconnectedAt: Long? = null,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val disconnectReason: String? = null
)

@Entity(
    tableName = "network_nodes",
    indices = [
        Index(value = ["isCurrentlyConnected"]),
        Index(value = ["lastSeenAt"])
    ]
)
data class NetworkNodeEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String,
    val bluetoothAddress: String,
    val isCurrentlyConnected: Boolean,
    val isRelayCapable: Boolean,
    val lastRssi: Int?,
    val hopDistance: Int,
    val lastSeenAt: Long,
    val successfulRelays: Int = 0,
    val trustStatus: TrustStatus = TrustStatus.PENDING
)

@Entity(tableName = "application_settings")
data class ApplicationSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val relayParticipationEnabled: Boolean = true,
    val maxRelayStorageMb: Int = 100,
    val defaultMessageTtlHours: Int = 72,
    val defaultMaxHops: Int = 8,
    val scanMode: BleScanModeSetting = BleScanModeSetting.BALANCED,
    val autoReconnectEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val maxFileSizeMb: Int = 250,
    val acceptFilesFromUnknown: Boolean = false,
    val backgroundServiceEnabled: Boolean = false
)
