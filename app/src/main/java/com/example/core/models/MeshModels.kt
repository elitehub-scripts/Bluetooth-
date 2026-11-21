package com.example.core.models

import kotlinx.serialization.Serializable

enum class MessageDeliveryStatus(val label: String) {
    CREATED("Created"),
    WAITING_FOR_RELAY("Waiting for relay"),
    RELAYED("Relayed"),
    RECEIVED_BY_DESTINATION("Received by destination"),
    DELIVERED("Delivered"),
    EXPIRED("Expired"),
    FAILED("Failed")
}

enum class MessageDirection {
    OUTGOING,
    INCOMING
}

enum class TrustStatus {
    PENDING,
    TRUSTED,
    VERIFIED
}

enum class TransportType(val label: String) {
    BLE_GATT("BLE GATT"),
    CLASSIC_RFCOMM("Classic RFCOMM"),
    RELAY("Store & Forward Relay")
}

enum class RelayState {
    QUEUED,
    FORWARDED,
    ACKED_DELIVERED,
    EXPIRED
}

enum class RelayAction(val label: String) {
    RECEIVED_FOR_RELAY("Received for relay"),
    FORWARDED_TO_PEER("Forwarded to relay peer"),
    DELIVERED_TO_DEST("Delivered to destination"),
    ACK_PROPAGATED("Delivery ACK propagated"),
    REJECTED_EXPIRED("Rejected (expired TTL)"),
    REJECTED_DUPLICATE("Rejected (duplicate)"),
    REJECTED_MALFORMED("Rejected (malformed/invalid sig)"),
    REJECTED_BLOCKED("Rejected (blocked peer)"),
    EVICTED_QUOTA("Evicted (storage quota)")
}

enum class FileTransferDirection {
    OUTGOING,
    INCOMING
}

enum class FileTransferStatus(val label: String) {
    QUEUED("Queued"),
    TRANSFERRING("Transferring"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    INTEGRITY_FAILED("Integrity check failed")
}

enum class AppThemeMode(val label: String) {
    DARK("Dark Navy"),
    LIGHT("Daylight"),
    SYSTEM("System Default")
}

enum class BleScanModeSetting(val label: String, val intervalLabel: String) {
    LOW_POWER("Battery Saver", "30s duty cycle"),
    BALANCED("Balanced", "15s duty cycle"),
    LOW_LATENCY("Active Discovery", "Continuous short bursts")
}

enum class PeerConnectionState(val label: String) {
    DISCOVERED("Discovered"),
    CONNECTING("Connecting"),
    HANDSHAKING("Verifying Keys"),
    CONNECTED("Connected"),
    DISCONNECTED("Disconnected"),
    FAILED("Connection Failed")
}

data class DiscoveredDeviceUi(
    val address: String,
    val bluetoothName: String,
    val rssi: Int?,
    val supportsBle: Boolean,
    val supportsClassic: Boolean,
    val isBonded: Boolean,
    val lastSeenAt: Long,
    val peerDeviceId: String? = null,
    val peerDisplayName: String? = null,
    val connectionState: PeerConnectionState = PeerConnectionState.DISCOVERED,
    val activeTransport: TransportType? = null,
    val fingerprint: String? = null,
    val isTrusted: Boolean = false,
    val isBlocked: Boolean = false
)

@Serializable
data class ContactQrBundle(
    val version: Int = 1,
    val deviceId: String,
    val displayName: String,
    val ed25519PublicKey: String,
    val x25519PublicKey: String,
    val fingerprint: String,
    val createdAt: Long
)

@Serializable
data class RelayEnvelope(
    val messageId: String,
    val originDeviceId: String,
    val originDisplayName: String,
    val originEd25519Pub: String,
    val originX25519Pub: String,
    val recipientDeviceId: String,
    val encryptedPayloadBase64: String,
    val signatureBase64: String,
    val hopCount: Int,
    val maxHops: Int,
    val isPriority: Boolean,
    val createdAt: Long,
    val expiresAt: Long
)

@Serializable
data class DeliveryAckEnvelope(
    val messageId: String,
    val originDeviceId: String,
    val recipientDeviceId: String,
    val recipientEd25519Pub: String,
    val ackTimestamp: Long,
    val signatureBase64: String
)

@Serializable
data class PeerHandshakePayload(
    val deviceId: String,
    val displayName: String,
    val ed25519PublicKey: String,
    val x25519PublicKey: String,
    val ephemeralX25519PublicKey: String,
    val challengeNonceBase64: String,
    val protocolVersion: Int = 1,
    val timestamp: Long
)

@Serializable
data class PeerAuthResponsePayload(
    val deviceId: String,
    val signedChallengeBase64: String,
    val timestamp: Long
)

@Serializable
data class FileMetaPayload(
    val transferId: String,
    val senderDeviceId: String,
    val senderDisplayName: String,
    val recipientDeviceId: String,
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val sha256Checksum: String,
    val chunkSizeBytes: Int,
    val totalChunks: Int
)

@Serializable
data class FileChunkAckPayload(
    val transferId: String,
    val ackedChunkIndex: Int,
    val receivedBytes: Long,
    val completed: Boolean,
    val integrityOk: Boolean
)

@Serializable
data class RelaySyncOfferPayload(
    val senderDeviceId: String,
    val heldMessageIds: List<String>,
    val knownAckedMessageIds: List<String>
)

@Serializable
data class RelaySyncRequestPayload(
    val requesterDeviceId: String,
    val wantedMessageIds: List<String>
)
