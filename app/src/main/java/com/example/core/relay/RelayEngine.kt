package com.example.core.relay

import com.example.core.crypto.CryptoEngine
import com.example.core.database.daos.ApplicationSettingsDao
import com.example.core.database.daos.BlockedDeviceDao
import com.example.core.database.daos.ContactDao
import com.example.core.database.daos.ConversationDao
import com.example.core.database.daos.MessageDao
import com.example.core.database.daos.NetworkNodeDao
import com.example.core.database.daos.RelayHistoryDao
import com.example.core.database.daos.RelayMessageDao
import com.example.core.database.entities.ContactEntity
import com.example.core.database.entities.ConversationEntity
import com.example.core.database.entities.MessageEntity
import com.example.core.database.entities.RelayHistoryEntity
import com.example.core.database.entities.RelayMessageEntity
import com.example.core.models.DeliveryAckEnvelope
import com.example.core.models.MessageDeliveryStatus
import com.example.core.models.MessageDirection
import com.example.core.models.RelayAction
import com.example.core.models.RelayEnvelope
import com.example.core.models.RelayState
import com.example.core.models.RelaySyncOfferPayload
import com.example.core.models.RelaySyncRequestPayload
import com.example.core.models.TransportType
import com.example.core.models.TrustStatus
import com.example.core.protocol.PacketType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface RelayPacketSender {
    suspend fun sendPacket(peerDeviceId: String, type: PacketType, streamId: String, payload: ByteArray): Boolean
}

class RelayEngine(
    private val cryptoEngine: CryptoEngine,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val relayMessageDao: RelayMessageDao,
    private val relayHistoryDao: RelayHistoryDao,
    private val blockedDeviceDao: BlockedDeviceDao,
    private val networkNodeDao: NetworkNodeDao,
    private val settingsDao: ApplicationSettingsDao,
    private val packetSender: RelayPacketSender,
    private val onLocalMessageArrived: (senderName: String, preview: String, conversationId: String, peerId: String) -> Unit = { _, _, _, _ -> }
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun onPeerConnected(peerDeviceId: String, peerDisplayName: String) {
        val now = System.currentTimeMillis()
        cleanupExpired(now)

        val localId = cryptoEngine.getOrCreateIdentity().deviceId
        val eligible = relayMessageDao.getEligibleForForwarding(now)
        val heldIds = eligible.map { it.messageId }
        val ackedIds = relayMessageDao.getKnownAckedIds()

        // 1. Direct delivery check: deliver any queued messages addressed to this exact peer immediately!
        val directForPeer = eligible.filter { it.recipientDeviceId == peerDeviceId }
        for (item in directForPeer) {
            val envelope = item.toRelayEnvelope()
            val bytes = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
            if (packetSender.sendPacket(peerDeviceId, PacketType.RELAY_ENVELOPE, item.messageId, bytes)) {
                relayMessageDao.updateState(item.messageId, RelayState.FORWARDED)
                // Notice: Only mark DELIVERED once recipient returns a signed DeliveryAckEnvelope!
                // If we are the origin, mark RELAYED / handed off until ACK arrives.
                val ownMsg = messageDao.getById(item.messageId)
                if (ownMsg != null && ownMsg.status == MessageDeliveryStatus.WAITING_FOR_RELAY) {
                    messageDao.updateDeliveryStatus(item.messageId, MessageDeliveryStatus.RELAYED, null)
                }
                relayHistoryDao.insert(
                    RelayHistoryEntity(
                        messageId = item.messageId,
                        peerDeviceId = peerDeviceId,
                        peerName = peerDisplayName,
                        action = RelayAction.FORWARDED_TO_PEER,
                        hopNumber = item.hopCount + 1,
                        timestamp = System.currentTimeMillis(),
                        details = "Direct handoff to destination device; awaiting signed ACK"
                    )
                )
            }
        }

        // 2. Offer remaining relay inventory & known ACKs to peer for epidemic store-and-forward
        val settings = settingsDao.getSettings()
        if (settings?.relayParticipationEnabled != false || heldIds.isNotEmpty() || ackedIds.isNotEmpty()) {
            val offer = RelaySyncOfferPayload(
                senderDeviceId = localId,
                heldMessageIds = heldIds,
                knownAckedMessageIds = ackedIds
            )
            packetSender.sendPacket(
                peerDeviceId = peerDeviceId,
                type = PacketType.RELAY_SYNC_OFFER,
                streamId = "sync-$localId",
                payload = json.encodeToString(offer).toByteArray(Charsets.UTF_8)
            )
        }
    }

    suspend fun handleIncomingPacket(
        fromPeerId: String,
        fromPeerName: String,
        transportType: TransportType,
        type: PacketType,
        payload: ByteArray
    ) {
        if (blockedDeviceDao.isBlocked(fromPeerId, null)) {
            return
        }
        when (type) {
            PacketType.MSG_ENVELOPE,
            PacketType.RELAY_ENVELOPE -> handleRelayEnvelope(fromPeerId, fromPeerName, transportType, payload)
            PacketType.RELAY_SYNC_OFFER -> handleSyncOffer(fromPeerId, fromPeerName, payload)
            PacketType.RELAY_SYNC_REQUEST -> handleSyncRequest(fromPeerId, fromPeerName, payload)
            PacketType.DELIVERY_ACK -> handleDeliveryAck(fromPeerId, fromPeerName, payload)
            else -> Unit
        }
    }

    suspend fun handleRelayEnvelope(
        fromPeerId: String,
        fromPeerName: String,
        transportType: TransportType,
        payload: ByteArray
    ) {
        val now = System.currentTimeMillis()
        val envelope = try {
            json.decodeFromString<RelayEnvelope>(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            relayHistoryDao.insert(
                RelayHistoryEntity(
                    messageId = "malformed-${now}",
                    peerDeviceId = fromPeerId,
                    peerName = fromPeerName,
                    action = RelayAction.REJECTED_MALFORMED,
                    hopNumber = 0,
                    timestamp = now,
                    details = "Unparseable relay envelope JSON"
                )
            )
            return
        }

        // 1. Check blocked origin
        if (blockedDeviceDao.isBlocked(envelope.originDeviceId, null)) {
            relayHistoryDao.insert(
                RelayHistoryEntity(
                    messageId = envelope.messageId,
                    peerDeviceId = fromPeerId,
                    peerName = fromPeerName,
                    action = RelayAction.REJECTED_BLOCKED,
                    hopNumber = envelope.hopCount,
                    timestamp = now,
                    details = "Origin device is blocked"
                )
            )
            return
        }

        // 2. Check expiration
        if (now > envelope.expiresAt) {
            relayHistoryDao.insert(
                RelayHistoryEntity(
                    messageId = envelope.messageId,
                    peerDeviceId = fromPeerId,
                    peerName = fromPeerName,
                    action = RelayAction.REJECTED_EXPIRED,
                    hopNumber = envelope.hopCount,
                    timestamp = now,
                    details = "Message TTL expired before arrival"
                )
            )
            return
        }

        // 3. Verify cryptographic Ed25519 signature & origin deviceId binding
        val sigValid = try {
            val originEdPub = CryptoEngine.fromBase64(envelope.originEd25519Pub)
            if (CryptoEngine.deriveDeviceId(originEdPub) != envelope.originDeviceId) {
                false
            } else {
                cryptoEngine.verifyEnvelopeSignature(
                    messageId = envelope.messageId,
                    originId = envelope.originDeviceId,
                    recipientId = envelope.recipientDeviceId,
                    ciphertextBase64 = envelope.encryptedPayloadBase64,
                    createdAt = envelope.createdAt,
                    expiresAt = envelope.expiresAt,
                    senderEd25519PubBase64 = envelope.originEd25519Pub,
                    signatureBase64 = envelope.signatureBase64
                )
                true
            }
        } catch (_: Throwable) {
            false
        }

        if (!sigValid) {
            relayHistoryDao.insert(
                RelayHistoryEntity(
                    messageId = envelope.messageId,
                    peerDeviceId = fromPeerId,
                    peerName = fromPeerName,
                    action = RelayAction.REJECTED_MALFORMED,
                    hopNumber = envelope.hopCount,
                    timestamp = now,
                    details = "Ed25519 envelope signature verification failed"
                )
            )
            return
        }

        val localIdentity = cryptoEngine.getOrCreateIdentity()

        // 4. Are WE the intended destination?
        if (envelope.recipientDeviceId == localIdentity.deviceId) {
            val alreadyExists = messageDao.getById(envelope.messageId) != null
            if (alreadyExists) {
                // Re-send signed Delivery ACK in case origin never received it
                sendSignedDeliveryAck(fromPeerId, envelope)
                return
            }

            val decryptedText = try {
                cryptoEngine.decryptFromSender(
                    ciphertextBase64 = envelope.encryptedPayloadBase64,
                    senderEd25519PubBase64 = envelope.originEd25519Pub,
                    senderX25519PubBase64 = envelope.originX25519Pub,
                    signatureBase64 = envelope.signatureBase64,
                    messageId = envelope.messageId,
                    senderId = envelope.originDeviceId,
                    recipientId = envelope.recipientDeviceId,
                    createdAt = envelope.createdAt,
                    expiresAt = envelope.expiresAt
                )
            } catch (_: Throwable) {
                relayHistoryDao.insert(
                    RelayHistoryEntity(
                        messageId = envelope.messageId,
                        peerDeviceId = fromPeerId,
                        peerName = fromPeerName,
                        action = RelayAction.REJECTED_MALFORMED,
                        hopNumber = envelope.hopCount,
                        timestamp = now,
                        details = "AES-GCM authenticated decryption failed for destination"
                    )
                )
                return
            }

            // Ensure contact exists cleanly
            val existingContact = contactDao.getById(envelope.originDeviceId)
            if (existingContact == null) {
                val fp = CryptoEngine.computeFingerprint(
                    CryptoEngine.fromBase64(envelope.originEd25519Pub),
                    CryptoEngine.fromBase64(envelope.originX25519Pub)
                )
                contactDao.upsert(
                    ContactEntity(
                        deviceId = envelope.originDeviceId,
                        displayName = envelope.originDisplayName,
                        ed25519PublicKeyBase64 = envelope.originEd25519Pub,
                        x25519PublicKeyBase64 = envelope.originX25519Pub,
                        fingerprint = fp,
                        trustStatus = TrustStatus.PENDING,
                        lastSeenAt = now,
                        lastTransport = transportType
                    )
                )
            }

            val conversationId = "conv-${envelope.originDeviceId}"
            val localEnc = cryptoEngine.encryptLocalSecretText(decryptedText, envelope.messageId)
            val localPreviewEnc = cryptoEngine.encryptLocalSecretText(decryptedText.take(80), conversationId)

            messageDao.upsert(
                MessageEntity(
                    messageId = envelope.messageId,
                    conversationId = conversationId,
                    senderId = envelope.originDeviceId,
                    recipientId = envelope.recipientDeviceId,
                    ciphertextBase64 = envelope.encryptedPayloadBase64,
                    localEncryptedTextBase64 = localEnc,
                    signatureBase64 = envelope.signatureBase64,
                    direction = MessageDirection.INCOMING,
                    status = MessageDeliveryStatus.RECEIVED_BY_DESTINATION,
                    isPriority = envelope.isPriority,
                    hopCount = envelope.hopCount + 1,
                    maxHops = envelope.maxHops,
                    ttlMillis = envelope.expiresAt - envelope.createdAt,
                    createdAt = envelope.createdAt,
                    expiresAt = envelope.expiresAt,
                    deliveredAt = now,
                    transportUsed = if (envelope.hopCount > 0) TransportType.RELAY else transportType
                )
            )

            val prevConv = conversationDao.getById(conversationId)
            conversationDao.upsert(
                ConversationEntity(
                    conversationId = conversationId,
                    peerDeviceId = envelope.originDeviceId,
                    peerDisplayName = existingContact?.displayName ?: envelope.originDisplayName,
                    lastMessageEncryptedPreview = localPreviewEnc,
                    lastMessagePlainFallback = decryptedText.take(48),
                    lastMessageAt = maxOf(prevConv?.lastMessageAt ?: 0L, envelope.createdAt),
                    lastMessageStatus = MessageDeliveryStatus.RECEIVED_BY_DESTINATION,
                    unreadCount = (prevConv?.unreadCount ?: 0) + 1,
                    isPinned = prevConv?.isPinned ?: false
                )
            )

            relayHistoryDao.insert(
                RelayHistoryEntity(
                    messageId = envelope.messageId,
                    peerDeviceId = fromPeerId,
                    peerName = fromPeerName,
                    action = RelayAction.DELIVERED_TO_DEST,
                    hopNumber = envelope.hopCount + 1,
                    timestamp = now,
                    details = "Decrypted locally by destination (${envelope.hopCount + 1} hop(s))"
                )
            )

            sendSignedDeliveryAck(fromPeerId, envelope)
            onLocalMessageArrived(
                existingContact?.displayName ?: envelope.originDisplayName,
                decryptedText,
                conversationId,
                envelope.originDeviceId
            )
            return
        }

        // 5. Otherwise, we act as a blind Store-and-Forward Relay Node
        val settings = settingsDao.getSettings()
        if (settings?.relayParticipationEnabled == false) {
            return
        }
        if (envelope.hopCount >= envelope.maxHops) {
            return
        }

        // Duplicate prevention
        if (relayMessageDao.getById(envelope.messageId) != null) {
            relayHistoryDao.insert(
                RelayHistoryEntity(
                    messageId = envelope.messageId,
                    peerDeviceId = fromPeerId,
                    peerName = fromPeerName,
                    action = RelayAction.REJECTED_DUPLICATE,
                    hopNumber = envelope.hopCount,
                    timestamp = now,
                    details = "Already cached in local relay store"
                )
            )
            return
        }

        val payloadBytes = payload.size
        enforceStorageQuota(
            incomingSizeBytes = payloadBytes,
            maxStorageBytes = (settings?.maxRelayStorageMb ?: 100).toLong() * 1024L * 1024L
        )

        val relayEntity = RelayMessageEntity(
            messageId = envelope.messageId,
            originDeviceId = envelope.originDeviceId,
            originDisplayName = envelope.originDisplayName,
            originEd25519PubBase64 = envelope.originEd25519Pub,
            originX25519PubBase64 = envelope.originX25519Pub,
            recipientDeviceId = envelope.recipientDeviceId,
            encryptedPayloadBase64 = envelope.encryptedPayloadBase64,
            signatureBase64 = envelope.signatureBase64,
            hopCount = envelope.hopCount + 1,
            maxHops = envelope.maxHops,
            isPriority = envelope.isPriority,
            createdAt = envelope.createdAt,
            expiresAt = envelope.expiresAt,
            receivedAt = now,
            relayState = RelayState.QUEUED,
            payloadSizeBytes = payloadBytes
        )
        relayMessageDao.insertIgnore(relayEntity)
        networkNodeDao.incrementRelayCount(fromPeerId, now)

        relayHistoryDao.insert(
            RelayHistoryEntity(
                messageId = envelope.messageId,
                peerDeviceId = fromPeerId,
                peerName = fromPeerName,
                action = RelayAction.RECEIVED_FOR_RELAY,
                hopNumber = relayEntity.hopCount,
                timestamp = now,
                details = "Stored encrypted payload (${payloadBytes} B) for ${envelope.recipientDeviceId.take(8)}…"
            )
        )
    }

    private suspend fun handleSyncOffer(fromPeerId: String, fromPeerName: String, payload: ByteArray) {
        val offer = try {
            json.decodeFromString<RelaySyncOfferPayload>(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val now = System.currentTimeMillis()
        val localId = cryptoEngine.getOrCreateIdentity().deviceId

        // Mark any of our cached relay messages that the peer already knows are delivered
        for (ackedId in offer.knownAckedMessageIds) {
            relayMessageDao.updateState(ackedId, RelayState.ACKED_DELIVERED)
        }

        // Request messages we don't already hold
        val wanted = mutableListOf<String>()
        for (id in offer.heldMessageIds) {
            if (relayMessageDao.getById(id) == null && messageDao.getById(id) == null) {
                wanted.add(id)
            }
        }
        if (wanted.isNotEmpty()) {
            val req = RelaySyncRequestPayload(requesterDeviceId = localId, wantedMessageIds = wanted)
            packetSender.sendPacket(
                peerDeviceId = fromPeerId,
                type = PacketType.RELAY_SYNC_REQUEST,
                streamId = "req-$localId",
                payload = json.encodeToString(req).toByteArray(Charsets.UTF_8)
            )
        }
    }

    private suspend fun handleSyncRequest(fromPeerId: String, fromPeerName: String, payload: ByteArray) {
        val req = try {
            json.decodeFromString<RelaySyncRequestPayload>(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val now = System.currentTimeMillis()
        for (id in req.wantedMessageIds) {
            val entity = relayMessageDao.getById(id) ?: continue
            if (now > entity.expiresAt || entity.hopCount >= entity.maxHops && entity.recipientDeviceId != fromPeerId) {
                continue
            }
            val envBytes = json.encodeToString(entity.toRelayEnvelope()).toByteArray(Charsets.UTF_8)
            if (packetSender.sendPacket(fromPeerId, PacketType.RELAY_ENVELOPE, id, envBytes)) {
                relayMessageDao.update(
                    entity.copy(
                        relayState = RelayState.FORWARDED,
                        forwardedCount = entity.forwardedCount + 1,
                        lastForwardedAt = now
                    )
                )
                val ownMsg = messageDao.getById(id)
                if (ownMsg != null && ownMsg.status == MessageDeliveryStatus.WAITING_FOR_RELAY) {
                    messageDao.updateDeliveryStatus(id, MessageDeliveryStatus.RELAYED, null)
                    conversationDao.updateLastStatus(ownMsg.conversationId, MessageDeliveryStatus.RELAYED)
                }
                relayHistoryDao.insert(
                    RelayHistoryEntity(
                        messageId = id,
                        peerDeviceId = fromPeerId,
                        peerName = fromPeerName,
                        action = RelayAction.FORWARDED_TO_PEER,
                        hopNumber = entity.hopCount,
                        timestamp = now,
                        details = "Relayed encrypted envelope to $fromPeerName"
                    )
                )
            }
        }
    }

    private suspend fun handleDeliveryAck(fromPeerId: String, fromPeerName: String, payload: ByteArray) {
        val ack = try {
            json.decodeFromString<DeliveryAckEnvelope>(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val valid = cryptoEngine.verifyDeliveryAck(
            messageId = ack.messageId,
            originId = ack.originDeviceId,
            recipientId = ack.recipientDeviceId,
            recipientEd25519PubBase64 = ack.recipientEd25519Pub,
            ackTimestamp = ack.ackTimestamp,
            signatureBase64 = ack.signatureBase64
        )
        if (!valid) return

        val now = System.currentTimeMillis()
        val ownMsg = messageDao.getById(ack.messageId)
        if (ownMsg != null && ownMsg.senderId == cryptoEngine.getOrCreateIdentity().deviceId) {
            messageDao.updateDeliveryStatus(ack.messageId, MessageDeliveryStatus.DELIVERED, ack.ackTimestamp)
            conversationDao.updateLastStatus(ownMsg.conversationId, MessageDeliveryStatus.DELIVERED)
        }
        relayMessageDao.updateState(ack.messageId, RelayState.ACKED_DELIVERED)
        relayHistoryDao.insert(
            RelayHistoryEntity(
                messageId = ack.messageId,
                peerDeviceId = fromPeerId,
                peerName = fromPeerName,
                action = RelayAction.ACK_PROPAGATED,
                hopNumber = 0,
                timestamp = now,
                details = "Verified destination delivery ACK"
            )
        )
    }

    private suspend fun sendSignedDeliveryAck(toPeerId: String, envelope: RelayEnvelope) {
        val identity = cryptoEngine.getOrCreateIdentity()
        val now = System.currentTimeMillis()
        val sig = cryptoEngine.signDeliveryAck(envelope.messageId, envelope.originDeviceId, now)
        val ack = DeliveryAckEnvelope(
            messageId = envelope.messageId,
            originDeviceId = envelope.originDeviceId,
            recipientDeviceId = identity.deviceId,
            recipientEd25519Pub = identity.ed25519PublicKeyBase64,
            ackTimestamp = now,
            signatureBase64 = sig
        )
        packetSender.sendPacket(
            peerDeviceId = toPeerId,
            type = PacketType.DELIVERY_ACK,
            streamId = "ack-${envelope.messageId}",
            payload = json.encodeToString(ack).toByteArray(Charsets.UTF_8)
        )
    }

    suspend fun cleanupExpired(now: Long = System.currentTimeMillis()): Int {
        messageDao.markExpiredMessages(now)
        return relayMessageDao.deleteExpired(now)
    }

    private suspend fun enforceStorageQuota(incomingSizeBytes: Int, maxStorageBytes: Long) {
        cleanupExpired()
        var currentBytes = relayMessageDao.getTotalBytesSync()
        if (currentBytes + incomingSizeBytes <= maxStorageBytes) return

        val candidates = relayMessageDao.getOldestLowestPriority(20)
        for (item in candidates) {
            if (currentBytes + incomingSizeBytes <= maxStorageBytes) break
            relayMessageDao.deleteById(item.messageId)
            currentBytes -= item.payloadSizeBytes
            relayHistoryDao.insert(
                RelayHistoryEntity(
                    messageId = item.messageId,
                    peerDeviceId = item.recipientDeviceId,
                    peerName = "Local Store",
                    action = RelayAction.EVICTED_QUOTA,
                    hopNumber = item.hopCount,
                    timestamp = System.currentTimeMillis(),
                    details = "Evicted oldest lowest-priority relay envelope to preserve storage quota"
                )
            )
        }
    }

    private fun RelayMessageEntity.toRelayEnvelope(): RelayEnvelope = RelayEnvelope(
        messageId = messageId,
        originDeviceId = originDeviceId,
        originDisplayName = originDisplayName,
        originEd25519Pub = originEd25519PubBase64,
        originX25519Pub = originX25519PubBase64,
        recipientDeviceId = recipientDeviceId,
        encryptedPayloadBase64 = encryptedPayloadBase64,
        signatureBase64 = signatureBase64,
        hopCount = hopCount,
        maxHops = maxHops,
        isPriority = isPriority,
        createdAt = createdAt,
        expiresAt = expiresAt
    )
}
