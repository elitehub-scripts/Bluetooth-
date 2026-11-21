package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BluetoothGlobalShareApp
import com.example.core.bluetooth.BluetoothHardwareStatus
import com.example.core.bluetooth.ConnectedPeerInfo
import com.example.core.database.entities.ApplicationSettingsEntity
import com.example.core.database.entities.BlockedDeviceEntity
import com.example.core.database.entities.ConnectionHistoryEntity
import com.example.core.database.entities.ContactEntity
import com.example.core.database.entities.ConversationEntity
import com.example.core.database.entities.DeviceProfileEntity
import com.example.core.database.entities.FileTransferEntity
import com.example.core.database.entities.NetworkNodeEntity
import com.example.core.database.entities.RelayHistoryEntity
import com.example.core.database.entities.RelayMessageEntity
import com.example.core.models.AppThemeMode
import com.example.core.models.BleScanModeSetting
import com.example.core.models.DiscoveredDeviceUi
import com.example.data.repository.DecryptedMessageUi
import com.example.data.repository.MeshRepository
import com.example.services.bluetooth.BluetoothMeshForegroundService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
    private val repository: MeshRepository
) : AndroidViewModel(application) {

    val profile: StateFlow<DeviceProfileEntity?> = repository.deviceProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val settings: StateFlow<ApplicationSettingsEntity> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ApplicationSettingsEntity())

    val contacts: StateFlow<List<ContactEntity>> = repository.contacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blockedDevices: StateFlow<List<BlockedDeviceEntity>> = repository.blockedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<ConversationEntity>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingMessagesCount: StateFlow<Int> = repository.pendingMessagesCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val relayMessages: StateFlow<List<RelayMessageEntity>> = repository.relayMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeRelayCount: StateFlow<Int> = repository.activeRelayCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val relayTotalBytes: StateFlow<Long> = repository.relayTotalBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val relayHistory: StateFlow<List<RelayHistoryEntity>> = repository.relayHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val fileTransfers: StateFlow<List<FileTransferEntity>> = repository.fileTransfers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingFileTransfersCount: StateFlow<Int> = repository.pendingFileTransfersCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val networkNodes: StateFlow<List<NetworkNodeEntity>> = repository.networkNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionHistory: StateFlow<List<ConnectionHistoryEntity>> = repository.connectionHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hardwareStatus: StateFlow<BluetoothHardwareStatus> = repository.hardwareStatus
    val connectedPeers: StateFlow<Map<String, ConnectedPeerInfo>> = repository.connectedPeers
    val discoveredDevices: StateFlow<List<DiscoveredDeviceUi>> = repository.discoveredDevices
    val isScanning: StateFlow<Boolean> = repository.isScanning
    val isAdvertising: StateFlow<Boolean> = repository.isAdvertising
    val transportError: StateFlow<String?> = repository.transportError
    val foregroundServiceRunning: StateFlow<Boolean> = BluetoothMeshForegroundService.isRunning

    private val _myQrPayload = MutableStateFlow("")
    val myQrPayload: StateFlow<String> = _myQrPayload.asStateFlow()

    private val _uiFeedbackMessage = MutableStateFlow<String?>(null)
    val uiFeedbackMessage: StateFlow<String?> = _uiFeedbackMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureInitialized()
            _myQrPayload.value = repository.buildMyContactQrJson()
        }
    }

    fun clearFeedback() {
        _uiFeedbackMessage.value = null
    }

    fun refreshHardware() {
        repository.transportManager.refreshHardwareStatus()
    }

    fun startMeshService(context: Context) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(backgroundServiceEnabled = true) }
        }
        BluetoothMeshForegroundService.start(context)
    }

    fun stopMeshService(context: Context) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(backgroundServiceEnabled = false) }
        }
        BluetoothMeshForegroundService.stop(context)
    }

    fun startDiscoveryAndServers() {
        repository.transportManager.startServersAndDiscovery()
    }

    fun stopDiscoveryAndServers() {
        repository.transportManager.stopAllServersAndScanning()
    }

    fun triggerClassicDiscovery() {
        repository.transportManager.triggerClassicDiscoveryBurst()
    }

    fun connectToPeer(address: String, preferClassic: Boolean) {
        repository.transportManager.connectToDiscoveredDevice(address, preferClassic)
    }

    fun disconnectPeer(addressOrDeviceId: String) {
        repository.transportManager.disconnectPeer(addressOrDeviceId)
    }

    fun approvePeer(peer: ConnectedPeerInfo, verifiedManually: Boolean) {
        viewModelScope.launch {
            repository.approveConnectedPeerAsContact(peer, verifiedManually)
            _uiFeedbackMessage.value = "${peer.displayName} saved to contacts"
        }
    }

    fun verifyContact(deviceId: String) {
        viewModelScope.launch {
            repository.verifyContactManually(deviceId)
            _uiFeedbackMessage.value = "Contact fingerprint marked as verified"
        }
    }

    fun blockPeer(deviceId: String, bluetoothAddress: String?, displayName: String, reason: String) {
        viewModelScope.launch {
            repository.blockPeer(deviceId, bluetoothAddress, displayName, reason)
            _uiFeedbackMessage.value = "$displayName blocked"
        }
    }

    fun unblockPeer(deviceId: String) {
        viewModelScope.launch {
            repository.unblockPeer(deviceId)
            _uiFeedbackMessage.value = "Device unblocked"
        }
    }

    fun deleteContact(deviceId: String) {
        viewModelScope.launch {
            repository.deleteContact(deviceId)
            _uiFeedbackMessage.value = "Contact removed"
        }
    }

    fun importContactQr(rawJson: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.importContactQrJson(rawJson, markVerified = true)
            res.onSuccess { contact ->
                _uiFeedbackMessage.value = "Added ${contact.displayName}"
                onSuccess()
            }.onFailure { err ->
                _uiFeedbackMessage.value = err.message ?: "Invalid contact bundle"
            }
        }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            repository.updateDisplayName(name)
            _myQrPayload.value = repository.buildMyContactQrJson()
            _uiFeedbackMessage.value = "Device name updated"
        }
    }

    fun openConversation(contact: ContactEntity, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val convId = repository.openOrCreateConversation(contact)
            onReady(convId)
        }
    }

    fun observeMessages(conversationId: String): Flow<List<DecryptedMessageUi>> {
        return repository.observeMessagesForConversation(conversationId)
    }

    fun markConversationRead(conversationId: String) {
        viewModelScope.launch {
            repository.markConversationRead(conversationId)
        }
    }

    fun sendMessage(recipientDeviceId: String, text: String, isPriority: Boolean) {
        viewModelScope.launch {
            val res = repository.sendEncryptedMessage(recipientDeviceId, text, isPriority)
            res.onFailure { err ->
                _uiFeedbackMessage.value = err.message ?: "Failed to encrypt message"
            }
        }
    }

    fun forwardMessage(plaintext: String, toContact: ContactEntity) {
        viewModelScope.launch {
            val res = repository.sendEncryptedMessage(toContact.deviceId, "Fwd: $plaintext", false)
            res.onSuccess {
                _uiFeedbackMessage.value = "Forwarded to ${toContact.displayName}"
            }.onFailure { err ->
                _uiFeedbackMessage.value = err.message ?: "Forward failed"
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
        }
    }

    fun sendFile(uri: Uri, peerDeviceId: String, peerName: String) {
        viewModelScope.launch {
            val res = repository.sendFileToPeer(uri, peerDeviceId, peerName)
            res.onSuccess {
                _uiFeedbackMessage.value = "Queued ${it.fileName} for transfer"
            }.onFailure { err ->
                _uiFeedbackMessage.value = err.message ?: "Failed to queue file"
            }
        }
    }

    fun pauseTransfer(transferId: String) {
        viewModelScope.launch { repository.pauseFileTransfer(transferId) }
    }

    fun resumeTransfer(transferId: String) {
        repository.resumeFileTransfer(transferId)
    }

    fun cancelTransfer(transferId: String) {
        viewModelScope.launch { repository.cancelFileTransfer(transferId) }
    }

    fun usedFileStorageBytes(): Long = repository.usedFileStorageBytes()
    fun availableStorageBytes(): Long = repository.availableStorageBytes()

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setScanMode(mode: BleScanModeSetting) {
        viewModelScope.launch { repository.setScanMode(mode) }
    }

    fun updateSettings(transform: (ApplicationSettingsEntity) -> ApplicationSettingsEntity) {
        viewModelScope.launch { repository.updateSettings(transform) }
    }

    fun cleanupExpiredRelays() {
        viewModelScope.launch {
            val purged = repository.cleanupExpiredRelays()
            _uiFeedbackMessage.value = "Cleaned up $purged expired relay packet(s)"
        }
    }

    class Factory(private val app: BluetoothGlobalShareApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(app, app.container.repository) as T
        }
    }
}
