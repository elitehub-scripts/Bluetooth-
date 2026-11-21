package com.example

import android.app.Application
import com.example.core.bluetooth.BluetoothTransportManager
import com.example.core.bluetooth.TransportEvent
import com.example.core.crypto.CryptoEngine
import com.example.core.database.AppDatabase
import com.example.core.models.TransportType
import com.example.core.protocol.PacketType
import com.example.core.relay.RelayEngine
import com.example.core.relay.RelayPacketSender
import com.example.core.transfer.FilePacketSender
import com.example.core.transfer.FileTransferEngine
import com.example.data.repository.MeshRepository
import com.example.services.notifications.MeshNotificationHelper
import com.example.services.relay.RelayMaintenanceWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(private val application: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.build(application) }
    val cryptoEngine: CryptoEngine by lazy { CryptoEngine(application) }

    val transportManager: BluetoothTransportManager by lazy {
        BluetoothTransportManager(
            context = application,
            scope = appScope,
            cryptoEngine = cryptoEngine,
            profileDao = database.deviceProfileDao(),
            contactDao = database.contactDao(),
            blockedDeviceDao = database.blockedDeviceDao(),
            networkNodeDao = database.networkNodeDao(),
            connectionHistoryDao = database.connectionHistoryDao(),
            settingsDao = database.applicationSettingsDao()
        )
    }

    val relayEngine: RelayEngine by lazy {
        RelayEngine(
            cryptoEngine = cryptoEngine,
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            contactDao = database.contactDao(),
            relayMessageDao = database.relayMessageDao(),
            relayHistoryDao = database.relayHistoryDao(),
            blockedDeviceDao = database.blockedDeviceDao(),
            networkNodeDao = database.networkNodeDao(),
            settingsDao = database.applicationSettingsDao(),
            packetSender = object : RelayPacketSender {
                override suspend fun sendPacket(
                    peerDeviceId: String,
                    type: PacketType,
                    streamId: String,
                    payload: ByteArray
                ): Boolean {
                    return transportManager.sendPacketToPeer(peerDeviceId, type, streamId, payload)
                }
            },
            onLocalMessageArrived = { senderName, preview, _, _ ->
                appScope.launch {
                    val settings = database.applicationSettingsDao().getSettings()
                    if (settings?.notificationsEnabled != false) {
                        MeshNotificationHelper.showIncomingMessageNotification(application, senderName, preview)
                    }
                }
            }
        )
    }

    val fileTransferEngine: FileTransferEngine by lazy {
        FileTransferEngine(
            context = application,
            scope = appScope,
            cryptoEngine = cryptoEngine,
            fileTransferDao = database.fileTransferDao(),
            profileDao = database.deviceProfileDao(),
            settingsDao = database.applicationSettingsDao(),
            packetSender = object : FilePacketSender {
                override suspend fun sendPacket(
                    peerDeviceId: String,
                    type: PacketType,
                    streamId: String,
                    payload: ByteArray
                ): Boolean {
                    return transportManager.sendPacketToPeer(peerDeviceId, type, streamId, payload)
                }

                override fun getPeerTransportType(peerDeviceId: String): TransportType? {
                    return transportManager.connectedPeers.value[peerDeviceId]?.transportType
                }
            },
            onFileReceivedComplete = { fileName, _ ->
                appScope.launch {
                    val settings = database.applicationSettingsDao().getSettings()
                    if (settings?.notificationsEnabled != false) {
                        MeshNotificationHelper.showFileReceivedNotification(application, fileName)
                    }
                }
            }
        )
    }

    val repository: MeshRepository by lazy {
        MeshRepository(
            context = application,
            db = database,
            cryptoEngine = cryptoEngine,
            transportManager = transportManager,
            relayEngine = relayEngine,
            fileTransferEngine = fileTransferEngine
        )
    }

    fun startEventBridge() {
        appScope.launch {
            repository.ensureInitialized()
        }
        appScope.launch {
            transportManager.transportEvents.collect { event ->
                when (event) {
                    is TransportEvent.PeerAuthenticated -> {
                        relayEngine.onPeerConnected(event.peer.deviceId, event.peer.displayName)
                        fileTransferEngine.onPeerConnected(event.peer.deviceId)
                    }
                    is TransportEvent.PacketReceived -> {
                        when (event.type) {
                            PacketType.MSG_ENVELOPE,
                            PacketType.RELAY_ENVELOPE,
                            PacketType.RELAY_SYNC_OFFER,
                            PacketType.RELAY_SYNC_REQUEST,
                            PacketType.DELIVERY_ACK -> {
                                relayEngine.handleIncomingPacket(
                                    fromPeerId = event.peerDeviceId,
                                    fromPeerName = event.peerDisplayName,
                                    transportType = event.transportType,
                                    type = event.type,
                                    payload = event.payload
                                )
                            }
                            PacketType.FILE_META,
                            PacketType.FILE_CHUNK,
                            PacketType.FILE_ACK,
                            PacketType.FILE_PAUSE,
                            PacketType.FILE_CANCEL -> {
                                fileTransferEngine.handleIncomingPacket(
                                    fromPeerId = event.peerDeviceId,
                                    fromPeerName = event.peerDisplayName,
                                    type = event.type,
                                    streamId = event.streamId,
                                    payload = event.payload
                                )
                            }
                            else -> Unit
                        }
                    }
                    is TransportEvent.PeerDisconnected -> Unit
                }
            }
        }
    }
}

class BluetoothGlobalShareApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MeshNotificationHelper.ensureChannels(this)
        container.startEventBridge()
        RelayMaintenanceWorker.schedule(this)
    }
}
