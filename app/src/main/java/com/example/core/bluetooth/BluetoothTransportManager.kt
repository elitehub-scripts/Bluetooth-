package com.example.core.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.core.crypto.CryptoEngine
import com.example.core.crypto.ReplayGuard
import com.example.core.database.daos.ApplicationSettingsDao
import com.example.core.database.daos.BlockedDeviceDao
import com.example.core.database.daos.ConnectionHistoryDao
import com.example.core.database.daos.ContactDao
import com.example.core.database.daos.DeviceProfileDao
import com.example.core.database.daos.NetworkNodeDao
import com.example.core.database.entities.ConnectionHistoryEntity
import com.example.core.database.entities.NetworkNodeEntity
import com.example.core.models.BleScanModeSetting
import com.example.core.models.DiscoveredDeviceUi
import com.example.core.models.PeerAuthResponsePayload
import com.example.core.models.PeerConnectionState
import com.example.core.models.PeerHandshakePayload
import com.example.core.models.TransportType
import com.example.core.models.TrustStatus
import com.example.core.protocol.Fragmenter
import com.example.core.protocol.PacketType
import com.example.core.protocol.ReassemblyBuffer
import com.example.core.protocol.TransportCodec
import com.example.core.protocol.TransportFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ConnectedPeerInfo(
    val deviceId: String,
    val displayName: String,
    val address: String,
    val transportType: TransportType,
    val ed25519PublicKeyBase64: String,
    val x25519PublicKeyBase64: String,
    val fingerprint: String,
    val connectedAt: Long,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L
)

sealed class TransportEvent {
    data class PeerAuthenticated(val peer: ConnectedPeerInfo) : TransportEvent()
    data class PeerDisconnected(val deviceId: String, val address: String, val reason: String) : TransportEvent()
    data class PacketReceived(
        val peerDeviceId: String,
        val peerDisplayName: String,
        val transportType: TransportType,
        val type: PacketType,
        val streamId: String,
        val payload: ByteArray
    ) : TransportEvent()
}

class BluetoothTransportManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val cryptoEngine: CryptoEngine,
    private val profileDao: DeviceProfileDao,
    private val contactDao: ContactDao,
    private val blockedDeviceDao: BlockedDeviceDao,
    private val networkNodeDao: NetworkNodeDao,
    private val connectionHistoryDao: ConnectionHistoryDao,
    private val settingsDao: ApplicationSettingsDao
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val packetIdCounter = AtomicLong(System.currentTimeMillis() and 0xFFFF_FFFFL)
    private val reassemblyBuffer = ReassemblyBuffer()
    private val replayGuard = ReplayGuard()

    private data class PendingHandshake(
        val link: TransportLink,
        val localEphemeralPriv: ByteArray,
        val localEphemeralPub: ByteArray,
        val localChallengeNonce: ByteArray,
        var peerHandshake: PeerHandshakePayload? = null,
        var sessionKey: ByteArray? = null,
        var authenticated: Boolean = false,
        var historyRowId: Long? = null,
        var bytesSent: Long = 0L,
        var bytesReceived: Long = 0L
    )

    private val activeLinksByAddress = ConcurrentHashMap<String, PendingHandshake>()
    private val addressByDeviceId = ConcurrentHashMap<String, String>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()

    private val _hardwareStatus = MutableStateFlow(BluetoothCapabilities.getHardwareStatus(context))
    val hardwareStatus: StateFlow<BluetoothHardwareStatus> = _hardwareStatus.asStateFlow()

    private val _connectedPeers = MutableStateFlow<Map<String, ConnectedPeerInfo>>(emptyMap())
    val connectedPeers: StateFlow<Map<String, ConnectedPeerInfo>> = _connectedPeers.asStateFlow()

    private val _connectionStatesByAddress = MutableStateFlow<Map<String, PeerConnectionState>>(emptyMap())
    private val _transportEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    val transportEvents: SharedFlow<TransportEvent> = _transportEvents.asSharedFlow()

    private val _statusBannerError = MutableStateFlow<String?>(null)
    val statusBannerError: StateFlow<String?> = _statusBannerError.asStateFlow()

    val discoveryAndAdvertiser = BleDiscoveryAndAdvertiser(
        context = context,
        scope = scope,
        localDeviceId = cryptoEngine.getOrCreateIdentity().deviceId
    )

    private val bleGattTransport = BleGattTransport(
        context = context,
        scope = scope,
        localIdentityMetadataJson = { buildLocalIdentityMetadataBytes() },
        onLinkConnected = { link -> handleRawLinkConnected(link) },
        onFrameReceived = { addr, frame -> handleRawFrame(addr, frame) },
        onLinkDisconnected = { addr, reason -> handleRawLinkDisconnected(addr, reason) }
    )

    private val classicRfcommTransport = ClassicRfcommTransport(
        context = context,
        scope = scope,
        onLinkConnected = { link -> handleRawLinkConnected(link) },
        onFrameReceived = { addr, frame -> handleRawFrame(addr, frame) },
        onLinkDisconnected = { addr, reason -> handleRawLinkDisconnected(addr, reason) }
    )

    val discoveredDevices: StateFlow<List<DiscoveredDeviceUi>> = combine(
        discoveryAndAdvertiser.discoveredDevices,
        _connectionStatesByAddress,
        _connectedPeers,
        contactDao.observeAllContacts(),
        blockedDeviceDao.observeAll()
    ) { rawMap, stateMap, connectedMap, contacts, blocked ->
        val blockedIds = blocked.map { it.deviceId }.toSet()
        val blockedAddrs = blocked.mapNotNull { it.bluetoothAddress }.toSet()
        val contactsById = contacts.associateBy { it.deviceId }

        rawMap.values.map { raw ->
            val connected = connectedMap.values.firstOrNull { it.address == raw.address }
            val peerId = connected?.deviceId ?: raw.serviceHintDeviceId
            val contact = peerId?.let { contactsById[it] }
            val connState = stateMap[raw.address]
                ?: if (connected != null) PeerConnectionState.CONNECTED else PeerConnectionState.DISCOVERED

            DiscoveredDeviceUi(
                address = raw.address,
                bluetoothName = raw.name,
                rssi = raw.rssi,
                supportsBle = raw.supportsBle,
                supportsClassic = raw.supportsClassic,
                isBonded = raw.isBonded,
                lastSeenAt = raw.lastSeenAt,
                peerDeviceId = peerId,
                peerDisplayName = connected?.displayName ?: contact?.displayName,
                connectionState = connState,
                activeTransport = connected?.transportType,
                fingerprint = connected?.fingerprint ?: contact?.fingerprint,
                isTrusted = contact?.trustStatus == TrustStatus.TRUSTED || contact?.trustStatus == TrustStatus.VERIFIED,
                isBlocked = raw.address in blockedAddrs || (peerId != null && peerId in blockedIds)
            )
        }.sortedWith(
            compareByDescending<DiscoveredDeviceUi> { it.connectionState == PeerConnectionState.CONNECTED }
                .thenByDescending { it.peerDeviceId != null }
                .thenByDescending { it.rssi ?: -120 }
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var heartbeatJob: Job? = null
    private var btStateReceiverRegistered = false

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                refreshHardwareStatus()
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    stopAllServersAndScanning()
                }
            }
        }
    }

    init {
        registerBluetoothStateReceiver()
        startHeartbeatLoop()
    }

    fun refreshHardwareStatus() {
        _hardwareStatus.value = BluetoothCapabilities.getHardwareStatus(context)
    }

    fun startServersAndDiscovery(scanMode: BleScanModeSetting? = null) {
        refreshHardwareStatus()
        val hw = _hardwareStatus.value
        if (!hw.hasBluetoothHardware) {
            _statusBannerError.value = "This device does not have Bluetooth hardware."
            return
        }
        if (!hw.isBluetoothEnabled) {
            _statusBannerError.value = "Bluetooth is turned off. Enable Bluetooth to connect."
            return
        }
        if (hw.missingPermissions.isNotEmpty()) {
            _statusBannerError.value = "Bluetooth permissions are required for discovery."
            return
        }

        _statusBannerError.value = null
        bleGattTransport.startGattServer()
        classicRfcommTransport.startServer()
        discoveryAndAdvertiser.startAdvertising()
        scope.launch {
            val mode = scanMode ?: settingsDao.getSettings()?.scanMode ?: BleScanModeSetting.BALANCED
            discoveryAndAdvertiser.startAdaptiveScanning(mode)
        }
    }

    fun stopAllServersAndScanning() {
        discoveryAndAdvertiser.stopScanning()
        discoveryAndAdvertiser.stopAdvertising()
        bleGattTransport.stopGattServer()
        classicRfcommTransport.stopServer()
        activeLinksByAddress.values.toList().forEach { it.link.close() }
        activeLinksByAddress.clear()
        addressByDeviceId.clear()
        _connectedPeers.value = emptyMap()
        _connectionStatesByAddress.value = emptyMap()
        scope.launch(Dispatchers.IO) {
            networkNodeDao.markAllDisconnected()
        }
    }

    fun triggerClassicDiscoveryBurst() {
        refreshHardwareStatus()
        discoveryAndAdvertiser.startClassicDiscoveryBurst()
    }

    fun connectToDiscoveredDevice(address: String, preferClassic: Boolean) {
        refreshHardwareStatus()
        val hw = _hardwareStatus.value
        if (!hw.isBluetoothEnabled || !hw.canConnect) {
            _statusBannerError.value = "Bluetooth must be enabled with Connect permission."
            return
        }
        if (activeLinksByAddress.size >= BluetoothConstants.MAX_ACTIVE_PEERS) {
            _statusBannerError.value = "Maximum active peer limit (${BluetoothConstants.MAX_ACTIVE_PEERS}) reached."
            return
        }
        val raw = discoveryAndAdvertiser.discoveredDevices.value[address] ?: return

        scope.launch {
            if (blockedDeviceDao.isBlocked("", address)) {
                _statusBannerError.value = "Device $address is blocked."
                return@launch
            }
            updateAddressState(address, PeerConnectionState.CONNECTING)
            if (preferClassic && raw.supportsClassic) {
                classicRfcommTransport.connectToDevice(raw.bluetoothDevice)
            } else if (raw.supportsBle) {
                bleGattTransport.connectToDevice(raw.bluetoothDevice)
            } else {
                classicRfcommTransport.connectToDevice(raw.bluetoothDevice)
            }
        }
    }

    fun disconnectPeer(addressOrDeviceId: String) {
        val address = addressByDeviceId[addressOrDeviceId] ?: addressOrDeviceId
        val session = activeLinksByAddress[address]
        session?.link?.close()
        handleRawLinkDisconnected(address, "Disconnected by user")
    }

    suspend fun sendPacketToPeer(
        peerDeviceId: String,
        type: PacketType,
        streamId: String,
        payload: ByteArray,
        encryptWithSession: Boolean = true
    ): Boolean {
        val address = addressByDeviceId[peerDeviceId] ?: return false
        val session = activeLinksByAddress[address] ?: return false
        if (!session.authenticated) return false

        val finalPayload = if (encryptWithSession) {
            val key = session.sessionKey ?: return false
            val frameNonceId = packetIdCounter.incrementAndGet()
            val cipher = cryptoEngine.encryptSessionBytes(key, payload, frameNonceId)
            // Prefix 8-byte frameNonceId so receiver can decrypt with matching AAD
            val prefixed = ByteArray(8 + cipher.size)
            for (i in 0 until 8) {
                prefixed[i] = ((frameNonceId ushr ((7 - i) * 8)) and 0xFF).toByte()
            }
            System.arraycopy(cipher, 0, prefixed, 8, cipher.size)
            prefixed
        } else {
            payload
        }

        val maxChunk = session.link.maxFramePayloadBytes.coerceAtMost(TransportCodec.MAX_FRAME_PAYLOAD_BYTES)
        val frames = Fragmenter.fragment(
            packetIdGenerator = { packetIdCounter.incrementAndGet() },
            type = type,
            streamId = streamId,
            fullPayload = finalPayload,
            maxChunkPayloadBytes = maxChunk
        )

        var totalSent = 0L
        for (frame in frames) {
            val encoded = TransportCodec.encode(frame)
            if (!session.link.sendBytes(encoded)) {
                return false
            }
            totalSent += encoded.size
        }
        session.bytesSent += totalSent
        updatePeerTrafficStats(peerDeviceId, session.bytesSent, session.bytesReceived)
        return true
    }

    private fun handleRawLinkConnected(link: TransportLink) {
        scope.launch {
            if (blockedDeviceDao.isBlocked("", link.address)) {
                link.close()
                return@launch
            }
            if (activeLinksByAddress.size >= BluetoothConstants.MAX_ACTIVE_PEERS) {
                link.close()
                return@launch
            }

            val (ephPriv, ephPub) = cryptoEngine.generateEphemeralX25519KeyPair()
            val challenge = cryptoEngine.randomBytes(32)
            val pending = PendingHandshake(
                link = link,
                localEphemeralPriv = ephPriv,
                localEphemeralPub = ephPub,
                localChallengeNonce = challenge
            )
            activeLinksByAddress[link.address] = pending
            updateAddressState(link.address, PeerConnectionState.HANDSHAKING)

            val identity = cryptoEngine.getOrCreateIdentity()
            val profileName = profileDao.getProfile()?.displayName ?: "BGS Device"
            val hello = PeerHandshakePayload(
                deviceId = identity.deviceId,
                displayName = profileName,
                ed25519PublicKey = identity.ed25519PublicKeyBase64,
                x25519PublicKey = identity.x25519PublicKeyBase64,
                ephemeralX25519PublicKey = CryptoEngine.toBase64(ephPub),
                challengeNonceBase64 = CryptoEngine.toBase64(challenge),
                timestamp = System.currentTimeMillis()
            )
            val bytes = json.encodeToString(hello).toByteArray(Charsets.UTF_8)
            sendUnencryptedFrames(link, PacketType.HELLO, "hs-${identity.deviceId}", bytes)

            // Enforce handshake timeout
            delay(BluetoothConstants.HANDSHAKE_TIMEOUT_MS)
            val check = activeLinksByAddress[link.address]
            if (check != null && !check.authenticated) {
                link.close()
                handleRawLinkDisconnected(link.address, "Handshake timed out")
            }
        }
    }

    private suspend fun sendUnencryptedFrames(
        link: TransportLink,
        type: PacketType,
        streamId: String,
        payload: ByteArray
    ) {
        val frames = Fragmenter.fragment(
            packetIdGenerator = { packetIdCounter.incrementAndGet() },
            type = type,
            streamId = streamId,
            fullPayload = payload,
            maxChunkPayloadBytes = link.maxFramePayloadBytes
        )
        for (frame in frames) {
            link.sendBytes(TransportCodec.encode(frame))
        }
    }

    private fun handleRawFrame(address: String, frame: TransportFrame) {
        val pending = activeLinksByAddress[address] ?: return
        pending.bytesReceived += frame.payload.size

        if (frame.type == PacketType.PING) {
            scope.launch {
                sendUnencryptedFrames(pending.link, PacketType.PONG, "pong", ByteArray(0))
            }
            return
        }
        if (frame.type == PacketType.PONG) return

        when (val result = reassemblyBuffer.offer(frame)) {
            is ReassemblyBuffer.OfferResult.Complete -> {
                scope.launch {
                    handleCompletePayload(address, pending, result.type, result.streamId, result.data)
                }
            }
            is ReassemblyBuffer.OfferResult.Rejected -> {
                // Malformed or oversized stream rejected safely
            }
            else -> Unit
        }
    }

    private suspend fun handleCompletePayload(
        address: String,
        pending: PendingHandshake,
        type: PacketType,
        streamId: String,
        data: ByteArray
    ) {
        when (type) {
            PacketType.HELLO -> {
                val hello = try {
                    json.decodeFromString<PeerHandshakePayload>(String(data, Charsets.UTF_8))
                } catch (_: Exception) {
                    pending.link.close()
                    return
                }
                if (blockedDeviceDao.isBlocked(hello.deviceId, address)) {
                    pending.link.close()
                    handleRawLinkDisconnected(address, "Blocked peer rejected")
                    return
                }
                // Verify deviceId truly matches Ed25519 public key
                val peerEdPub = try {
                    CryptoEngine.fromBase64(hello.ed25519PublicKey)
                } catch (_: Exception) {
                    pending.link.close()
                    return
                }
                if (CryptoEngine.deriveDeviceId(peerEdPub) != hello.deviceId) {
                    pending.link.close()
                    handleRawLinkDisconnected(address, "Invalid device identity binding")
                    return
                }

                pending.peerHandshake = hello
                val localIdentity = cryptoEngine.getOrCreateIdentity()
                val sortedSalt = listOf(localIdentity.deviceId, hello.deviceId).sorted().joinToString("|")
                    .toByteArray(Charsets.UTF_8)
                val peerEphPub = CryptoEngine.fromBase64(hello.ephemeralX25519PublicKey)
                pending.sessionKey = cryptoEngine.deriveTransportSessionKey(
                    localEphemeralPriv = pending.localEphemeralPriv,
                    peerEphemeralPub = peerEphPub,
                    sessionSalt = sortedSalt
                )

                val challengeBytes = CryptoEngine.fromBase64(hello.challengeNonceBase64)
                val signedChallenge = cryptoEngine.signChallenge(challengeBytes)
                val authResp = PeerAuthResponsePayload(
                    deviceId = localIdentity.deviceId,
                    signedChallengeBase64 = signedChallenge,
                    timestamp = System.currentTimeMillis()
                )
                sendUnencryptedFrames(
                    pending.link,
                    PacketType.AUTH_RESPONSE,
                    "auth-${localIdentity.deviceId}",
                    json.encodeToString(authResp).toByteArray(Charsets.UTF_8)
                )
            }

            PacketType.AUTH_RESPONSE -> {
                val peerHello = pending.peerHandshake ?: return
                val resp = try {
                    json.decodeFromString<PeerAuthResponsePayload>(String(data, Charsets.UTF_8))
                } catch (_: Exception) {
                    pending.link.close()
                    return
                }
                if (resp.deviceId != peerHello.deviceId) {
                    pending.link.close()
                    return
                }
                val verified = cryptoEngine.verifyPeerChallenge(
                    peerDeviceId = peerHello.deviceId,
                    peerEd25519PubBase64 = peerHello.ed25519PublicKey,
                    nonceBytes = pending.localChallengeNonce,
                    signatureBase64 = resp.signedChallengeBase64
                )
                if (!verified) {
                    pending.link.close()
                    handleRawLinkDisconnected(address, "Cryptographic identity verification failed")
                    return
                }

                pending.authenticated = true
                reconnectAttempts.remove(address)
                addressByDeviceId[peerHello.deviceId] = address

                val peerEdPub = CryptoEngine.fromBase64(peerHello.ed25519PublicKey)
                val peerXPub = CryptoEngine.fromBase64(peerHello.x25519PublicKey)
                val fingerprint = CryptoEngine.computeFingerprint(peerEdPub, peerXPub)
                val now = System.currentTimeMillis()

                val historyId = connectionHistoryDao.insert(
                    ConnectionHistoryEntity(
                        peerDeviceId = peerHello.deviceId,
                        bluetoothAddress = address,
                        peerName = peerHello.displayName,
                        transportType = pending.link.transportType,
                        connectedAt = now
                    )
                )
                pending.historyRowId = historyId

                val existingContact = contactDao.getById(peerHello.deviceId)
                val trustStatus = existingContact?.trustStatus ?: TrustStatus.PENDING
                networkNodeDao.upsert(
                    NetworkNodeEntity(
                        nodeId = peerHello.deviceId,
                        displayName = peerHello.displayName,
                        bluetoothAddress = address,
                        isCurrentlyConnected = true,
                        isRelayCapable = true,
                        lastRssi = discoveryAndAdvertiser.discoveredDevices.value[address]?.rssi,
                        hopDistance = 1,
                        lastSeenAt = now,
                        trustStatus = trustStatus
                    )
                )

                val peerInfo = ConnectedPeerInfo(
                    deviceId = peerHello.deviceId,
                    displayName = peerHello.displayName,
                    address = address,
                    transportType = pending.link.transportType,
                    ed25519PublicKeyBase64 = peerHello.ed25519PublicKey,
                    x25519PublicKeyBase64 = peerHello.x25519PublicKey,
                    fingerprint = fingerprint,
                    connectedAt = now
                )
                _connectedPeers.value = _connectedPeers.value + (peerHello.deviceId to peerInfo)
                updateAddressState(address, PeerConnectionState.CONNECTED)
                _transportEvents.tryEmit(TransportEvent.PeerAuthenticated(peerInfo))
            }

            else -> {
                if (!pending.authenticated) return
                val peerHello = pending.peerHandshake ?: return
                val sessionKey = pending.sessionKey ?: return
                if (data.size < 8) return

                var frameNonceId = 0L
                for (i in 0 until 8) {
                    frameNonceId = (frameNonceId shl 8) or (data[i].toLong() and 0xFFL)
                }
                if (!replayGuard.checkAndRecord("${peerHello.deviceId}:$frameNonceId", System.currentTimeMillis())) {
                    return
                }
                val decrypted = try {
                    cryptoEngine.decryptSessionBytes(
                        sessionKey = sessionKey,
                        ciphertext = data.copyOfRange(8, data.size),
                        frameId = frameNonceId
                    )
                } catch (_: Exception) {
                    return
                }
                updatePeerTrafficStats(peerHello.deviceId, pending.bytesSent, pending.bytesReceived)
                _transportEvents.tryEmit(
                    TransportEvent.PacketReceived(
                        peerDeviceId = peerHello.deviceId,
                        peerDisplayName = peerHello.displayName,
                        transportType = pending.link.transportType,
                        type = type,
                        streamId = streamId,
                        payload = decrypted
                    )
                )
            }
        }
    }

    private fun handleRawLinkDisconnected(address: String, reason: String) {
        val session = activeLinksByAddress.remove(address)
        val peerId = session?.peerHandshake?.deviceId
        if (peerId != null) {
            addressByDeviceId.remove(peerId)
            _connectedPeers.value = _connectedPeers.value - peerId
            scope.launch(Dispatchers.IO) {
                session.historyRowId?.let { id ->
                    connectionHistoryDao.closeSession(
                        id = id,
                        disconnectedAt = System.currentTimeMillis(),
                        bytesSent = session.bytesSent,
                        bytesReceived = session.bytesReceived,
                        reason = reason
                    )
                }
                val existing = networkNodeDao.upsert(
                    NetworkNodeEntity(
                        nodeId = peerId,
                        displayName = session.peerHandshake?.displayName ?: "Peer",
                        bluetoothAddress = address,
                        isCurrentlyConnected = false,
                        isRelayCapable = true,
                        lastRssi = null,
                        hopDistance = 1,
                        lastSeenAt = System.currentTimeMillis()
                    )
                )
            }
            _transportEvents.tryEmit(TransportEvent.PeerDisconnected(peerId, address, reason))
        }
        updateAddressState(address, PeerConnectionState.DISCONNECTED)

        // Controlled reconnect if enabled & not user-closed
        if (!reason.contains("user", ignoreCase = true) && !reason.contains("Blocked", ignoreCase = true)) {
            scope.launch {
                val autoReconnect = settingsDao.getSettings()?.autoReconnectEnabled == true
                val attempts = reconnectAttempts[address] ?: 0
                if (autoReconnect && attempts < 2 && _hardwareStatus.value.isBluetoothEnabled) {
                    reconnectAttempts[address] = attempts + 1
                    delay(3_000L * (attempts + 1))
                    connectToDiscoveredDevice(address, preferClassic = session?.link?.transportType == TransportType.CLASSIC_RFCOMM)
                }
            }
        }
    }

    private fun updateAddressState(address: String, state: PeerConnectionState) {
        _connectionStatesByAddress.value = _connectionStatesByAddress.value + (address to state)
    }

    private fun updatePeerTrafficStats(deviceId: String, sent: Long, received: Long) {
        val current = _connectedPeers.value[deviceId] ?: return
        _connectedPeers.value = _connectedPeers.value + (deviceId to current.copy(
            bytesSent = sent,
            bytesReceived = received
        ))
    }

    private fun buildLocalIdentityMetadataBytes(): ByteArray {
        val id = cryptoEngine.getOrCreateIdentity()
        return """{"deviceId":"${id.deviceId}","fp":"${id.fingerprint}"}""".toByteArray(Charsets.UTF_8)
    }

    private fun startHeartbeatLoop() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(BluetoothConstants.HEARTBEAT_INTERVAL_MS)
                activeLinksByAddress.values.toList().forEach { session ->
                    if (session.authenticated) {
                        sendUnencryptedFrames(session.link, PacketType.PING, "ping", ByteArray(0))
                    }
                }
            }
        }
    }

    private fun registerBluetoothStateReceiver() {
        if (!btStateReceiverRegistered) {
            try {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(bluetoothStateReceiver, filter)
                }
                btStateReceiverRegistered = true
            } catch (_: Throwable) {
                btStateReceiverRegistered = false
            }
        }
    }

    fun release() {
        heartbeatJob?.cancel()
        if (btStateReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothStateReceiver)
            } catch (_: Exception) {
            }
            btStateReceiverRegistered = false
        }
        discoveryAndAdvertiser.release()
        bleGattTransport.release()
        classicRfcommTransport.release()
    }
}
