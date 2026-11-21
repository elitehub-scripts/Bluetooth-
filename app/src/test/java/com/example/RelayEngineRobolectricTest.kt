package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.CryptoEngine
import com.example.core.database.AppDatabase
import com.example.core.database.entities.ApplicationSettingsEntity
import com.example.core.database.entities.BlockedDeviceEntity
import com.example.core.database.entities.MessageEntity
import com.example.core.models.DeliveryAckEnvelope
import com.example.core.models.MessageDeliveryStatus
import com.example.core.models.MessageDirection
import com.example.core.models.RelayAction
import com.example.core.models.RelayEnvelope
import com.example.core.models.RelayState
import com.example.core.models.TransportType
import com.example.core.protocol.PacketType
import com.example.core.relay.RelayEngine
import com.example.core.relay.RelayPacketSender
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RelayEngineRobolectricTest {

    private lateinit var db: AppDatabase
    private lateinit var crypto: CryptoEngine
    private lateinit var relayEngine: RelayEngine
    private val sentPackets = mutableListOf<Triple<String, PacketType, ByteArray>>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        crypto = CryptoEngine(context)
        sentPackets.clear()
        relayEngine = RelayEngine(
            cryptoEngine = crypto,
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            contactDao = db.contactDao(),
            relayMessageDao = db.relayMessageDao(),
            relayHistoryDao = db.relayHistoryDao(),
            blockedDeviceDao = db.blockedDeviceDao(),
            networkNodeDao = db.networkNodeDao(),
            settingsDao = db.applicationSettingsDao(),
            packetSender = object : RelayPacketSender {
                override suspend fun sendPacket(
                    peerDeviceId: String,
                    type: PacketType,
                    streamId: String,
                    payload: ByteArray
                ): Boolean {
                    sentPackets.add(Triple(peerDeviceId, type, payload))
                    return true
                }
            }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun buildSignedEnvelope(
        messageId: String = "msg-1",
        recipientId: String = "DEST_NODE_001",
        hopCount: Int = 0,
        maxHops: Int = 5,
        createdAt: Long = System.currentTimeMillis(),
        expiresAt: Long = System.currentTimeMillis() + 3600_000L
    ): Pair<RelayEnvelope, Ed25519Sign.KeyPair> {
        val edPair = Ed25519Sign.KeyPair.newKeyPair()
        val xPriv = X25519.generatePrivateKey()
        val xPub = X25519.publicFromPrivate(xPriv)
        val originId = CryptoEngine.deriveDeviceId(edPair.publicKey)
        val cipher = CryptoEngine.toBase64("encrypted-opaque-bytes".toByteArray())
        val transcript = "BGS-ENV-V1|$messageId|$originId|$recipientId|$createdAt|$expiresAt|$cipher"
            .toByteArray(Charsets.UTF_8)
        val sig = CryptoEngine.toBase64(Ed25519Sign(edPair.privateKey).sign(transcript))
        return RelayEnvelope(
            messageId = messageId,
            originDeviceId = originId,
            originDisplayName = "Origin Phone",
            originEd25519Pub = CryptoEngine.toBase64(edPair.publicKey),
            originX25519Pub = CryptoEngine.toBase64(xPub),
            recipientDeviceId = recipientId,
            encryptedPayloadBase64 = cipher,
            signatureBase64 = sig,
            hopCount = hopCount,
            maxHops = maxHops,
            isPriority = false,
            createdAt = createdAt,
            expiresAt = expiresAt
        ) to edPair
    }

    @Test
    fun `intermediate relay caches encrypted envelope and prevents duplicates`() = runTest {
        db.applicationSettingsDao().upsert(ApplicationSettingsEntity(relayParticipationEnabled = true))
        val (env, _) = buildSignedEnvelope(messageId = "msg-relay-1")
        val bytes = json.encodeToString(env).toByteArray()

        relayEngine.handleRelayEnvelope("PEER_A", "Peer A", TransportType.BLE_GATT, bytes)
        val stored = db.relayMessageDao().getById("msg-relay-1")
        assertNotNull(stored)
        assertEquals(1, stored!!.hopCount)
        assertEquals(RelayState.QUEUED, stored.relayState)

        // Duplicate arrival should be logged as REJECTED_DUPLICATE
        relayEngine.handleRelayEnvelope("PEER_B", "Peer B", TransportType.BLE_GATT, bytes)
        val history = db.relayHistoryDao().observeRecent().first()
        assertTrue(history.any { it.action == RelayAction.REJECTED_DUPLICATE })
    }

    @Test
    fun `expired messages are rejected and purged`() = runTest {
        val past = System.currentTimeMillis() - 10_000L
        val (expiredEnv, _) = buildSignedEnvelope(
            messageId = "msg-expired-1",
            createdAt = past - 50_000L,
            expiresAt = past
        )
        relayEngine.handleRelayEnvelope(
            "PEER_A",
            "Peer A",
            TransportType.CLASSIC_RFCOMM,
            json.encodeToString(expiredEnv).toByteArray()
        )
        assertNull(db.relayMessageDao().getById("msg-expired-1"))
        val history = db.relayHistoryDao().observeRecent().first()
        assertTrue(history.any { it.action == RelayAction.REJECTED_EXPIRED })
    }

    @Test
    fun `blocked origin device is rejected`() = runTest {
        val (env, _) = buildSignedEnvelope(messageId = "msg-blocked-1")
        db.blockedDeviceDao().upsert(
            BlockedDeviceEntity(
                deviceId = env.originDeviceId,
                displayName = "Blocked Origin",
                reason = "Spam",
                blockedAt = System.currentTimeMillis()
            )
        )
        relayEngine.handleRelayEnvelope(
            "PEER_A",
            "Peer A",
            TransportType.BLE_GATT,
            json.encodeToString(env).toByteArray()
        )
        assertNull(db.relayMessageDao().getById("msg-blocked-1"))
        val history = db.relayHistoryDao().observeRecent().first()
        assertTrue(history.any { it.action == RelayAction.REJECTED_BLOCKED })
    }

    @Test
    fun `delivery ack signed by true destination upgrades status to DELIVERED`() = runTest {
        val localId = crypto.getOrCreateIdentity().deviceId
        val destEd = Ed25519Sign.KeyPair.newKeyPair()
        val destId = CryptoEngine.deriveDeviceId(destEd.publicKey)
        val now = System.currentTimeMillis()

        db.messageDao().upsert(
            MessageEntity(
                messageId = "msg-ack-1",
                conversationId = "conv-$destId",
                senderId = localId,
                recipientId = destId,
                ciphertextBase64 = "cipher",
                localEncryptedTextBase64 = "local",
                signatureBase64 = "sig",
                direction = MessageDirection.OUTGOING,
                status = MessageDeliveryStatus.RELAYED,
                ttlMillis = 3600_000L,
                createdAt = now,
                expiresAt = now + 3600_000L
            )
        )

        val ackData = "BGS-ACK-V1|msg-ack-1|$localId|$destId|$now".toByteArray(Charsets.UTF_8)
        val ackSig = CryptoEngine.toBase64(Ed25519Sign(destEd.privateKey).sign(ackData))
        val ack = DeliveryAckEnvelope(
            messageId = "msg-ack-1",
            originDeviceId = localId,
            recipientDeviceId = destId,
            recipientEd25519Pub = CryptoEngine.toBase64(destEd.publicKey),
            ackTimestamp = now,
            signatureBase64 = ackSig
        )

        relayEngine.handleIncomingPacket(
            fromPeerId = "RELAY_X",
            fromPeerName = "Relay X",
            transportType = TransportType.RELAY,
            type = PacketType.DELIVERY_ACK,
            payload = json.encodeToString(ack).toByteArray()
        )

        val updated = db.messageDao().getById("msg-ack-1")
        assertEquals(MessageDeliveryStatus.DELIVERED, updated?.status)
    }
}
