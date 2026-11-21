package com.example.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.example.core.models.TransportType
import com.example.core.protocol.DecodeFrameResult
import com.example.core.protocol.TransportCodec
import com.example.core.protocol.TransportFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

interface TransportLink {
    val address: String
    val transportType: TransportType
    val maxFramePayloadBytes: Int
    suspend fun sendBytes(bytes: ByteArray): Boolean
    fun close()
}

class BleGattTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localIdentityMetadataJson: () -> ByteArray,
    private val onLinkConnected: (TransportLink) -> Unit,
    private val onFrameReceived: (address: String, frame: TransportFrame) -> Unit,
    private val onLinkDisconnected: (address: String, reason: String) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var rxNotifyCharacteristic: BluetoothGattCharacteristic? = null

    private val clientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientWriteChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val peerMtus = ConcurrentHashMap<String, Int>()
    private val writeWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val serverNotifyWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val serverDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val receiveAccumulators = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val sendMutexes = ConcurrentHashMap<String, Mutex>()

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverDevices[addr] = device
                peerMtus.putIfAbsent(addr, 23)
                onLinkConnected(createServerLink(addr))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverDevices.remove(addr)
                receiveAccumulators.remove(addr)
                serverNotifyWaiters.remove(addr)?.complete(false)
                onLinkDisconnected(addr, "BLE GATT server disconnected (status=$status)")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            peerMtus[device.address] = mtu.coerceIn(23, 512)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!BluetoothCapabilities.canConnect(context)) return
            if (characteristic.uuid == BluetoothConstants.BLE_CHAR_IDENTITY_UUID) {
                val data = localIdentityMetadataJson()
                val slice = if (offset >= data.size) ByteArray(0) else data.copyOfRange(offset, data.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (!BluetoothCapabilities.canConnect(context)) return
            if (characteristic.uuid == BluetoothConstants.BLE_CHAR_TX_UUID && value != null) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                appendIncomingBytes(device.address, value)
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded && BluetoothCapabilities.canConnect(context)) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            serverNotifyWaiters.remove(device.address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    @SuppressLint("MissingPermission")
    fun startGattServer(): Boolean {
        val manager = bluetoothManager ?: return false
        if (!BluetoothCapabilities.canConnect(context)) return false
        if (gattServer != null) return true

        val server = try {
            manager.openGattServer(context, gattServerCallback)
        } catch (_: SecurityException) {
            null
        } ?: return false

        val service = BluetoothGattService(
            BluetoothConstants.BLE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val txChar = BluetoothGattCharacteristic(
            BluetoothConstants.BLE_CHAR_TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val rxChar = BluetoothGattCharacteristic(
            BluetoothConstants.BLE_CHAR_RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            BluetoothConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        rxChar.addDescriptor(cccd)
        rxNotifyCharacteristic = rxChar

        val idChar = BluetoothGattCharacteristic(
            BluetoothConstants.BLE_CHAR_IDENTITY_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(txChar)
        service.addCharacteristic(rxChar)
        service.addCharacteristic(idChar)

        return try {
            server.addService(service)
            gattServer = server
            true
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopGattServer() {
        try {
            if (BluetoothCapabilities.canConnect(context)) {
                gattServer?.close()
            }
        } catch (_: Exception) {
        }
        gattServer = null
        serverDevices.clear()
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice): Boolean {
        val addr = device.address
        if (!BluetoothCapabilities.canConnect(context)) return false
        if (clientGatts.containsKey(addr)) return true

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        gatt.requestMtu(512)
                    } catch (_: SecurityException) {
                        onLinkDisconnected(addr, "Permission revoked during MTU request")
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cleanupClientGatt(addr)
                    onLinkDisconnected(addr, "BLE GATT disconnected (status=$status)")
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                peerMtus[addr] = if (status == BluetoothGatt.GATT_SUCCESS) mtu.coerceIn(23, 512) else 23
                try {
                    gatt.discoverServices()
                } catch (_: SecurityException) {
                    cleanupClientGatt(addr)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    cleanupClientGatt(addr)
                    onLinkDisconnected(addr, "GATT service discovery failed ($status)")
                    return
                }
                val service = gatt.getService(BluetoothConstants.BLE_SERVICE_UUID)
                if (service == null) {
                    cleanupClientGatt(addr)
                    onLinkDisconnected(addr, "Device does not expose Bluetooth Global Share BLE service")
                    return
                }
                val txChar = service.getCharacteristic(BluetoothConstants.BLE_CHAR_TX_UUID)
                val rxChar = service.getCharacteristic(BluetoothConstants.BLE_CHAR_RX_UUID)
                if (txChar == null || rxChar == null) {
                    cleanupClientGatt(addr)
                    onLinkDisconnected(addr, "Missing required BLE characteristics")
                    return
                }
                clientWriteChars[addr] = txChar
                try {
                    gatt.setCharacteristicNotification(rxChar, true)
                    val cccd = rxChar.getDescriptor(BluetoothConstants.CCCD_UUID)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
                    } else {
                        onLinkConnected(createClientLink(addr))
                    }
                } catch (_: SecurityException) {
                    cleanupClientGatt(addr)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == BluetoothConstants.CCCD_UUID) {
                    onLinkConnected(createClientLink(addr))
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                writeWaiters.remove(addr)?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == BluetoothConstants.BLE_CHAR_RX_UUID) {
                    appendIncomingBytes(addr, value)
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                    characteristic.uuid == BluetoothConstants.BLE_CHAR_RX_UUID
                ) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value ?: return
                    appendIncomingBytes(addr, bytes)
                }
            }
        }

        return try {
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt != null) {
                clientGatts[addr] = gatt
                true
            } else false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun createClientLink(address: String): TransportLink {
        return object : TransportLink {
            override val address: String = address
            override val transportType: TransportType = TransportType.BLE_GATT
            override val maxFramePayloadBytes: Int
                get() = ((peerMtus[address] ?: 23) - 36).coerceIn(
                    BluetoothConstants.DEFAULT_BLE_CHUNK_PAYLOAD.coerceAtMost((peerMtus[address] ?: 23) - 4),
                    BluetoothConstants.MAX_BLE_CHUNK_PAYLOAD
                ).coerceAtLeast(16)

            override suspend fun sendBytes(bytes: ByteArray): Boolean {
                val mutex = sendMutexes.getOrPut(address) { Mutex() }
                return mutex.withLock {
                    sendClientBytesLocked(address, bytes)
                }
            }

            override fun close() {
                cleanupClientGatt(address)
            }
        }
    }

    private fun createServerLink(address: String): TransportLink {
        return object : TransportLink {
            override val address: String = address
            override val transportType: TransportType = TransportType.BLE_GATT
            override val maxFramePayloadBytes: Int
                get() = ((peerMtus[address] ?: 23) - 36).coerceIn(
                    BluetoothConstants.DEFAULT_BLE_CHUNK_PAYLOAD.coerceAtMost((peerMtus[address] ?: 23) - 4),
                    BluetoothConstants.MAX_BLE_CHUNK_PAYLOAD
                ).coerceAtLeast(16)

            override suspend fun sendBytes(bytes: ByteArray): Boolean {
                val mutex = sendMutexes.getOrPut(address) { Mutex() }
                return mutex.withLock {
                    sendServerBytesLocked(address, bytes)
                }
            }

            override fun close() {
                val device = serverDevices.remove(address) ?: return
                try {
                    if (BluetoothCapabilities.canConnect(context)) {
                        gattServer?.cancelConnection(device)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendClientBytesLocked(address: String, bytes: ByteArray): Boolean {
        val gatt = clientGatts[address] ?: return false
        val char = clientWriteChars[address] ?: return false
        if (!BluetoothCapabilities.canConnect(context)) return false
        val attPayloadLimit = ((peerMtus[address] ?: 23) - 3).coerceAtLeast(20)

        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + attPayloadLimit)
            val slice = bytes.copyOfRange(offset, end)
            val deferred = CompletableDeferred<Boolean>()
            writeWaiters[address] = deferred

            val initiated = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        char,
                        slice,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    char.value = slice
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(char)
                }
            } catch (_: SecurityException) {
                false
            }
            if (!initiated) {
                writeWaiters.remove(address)
                return false
            }
            val ok = withTimeoutOrNull(4_000L) { deferred.await() } ?: false
            if (!ok) return false
            offset = end
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendServerBytesLocked(address: String, bytes: ByteArray): Boolean {
        val server = gattServer ?: return false
        val device = serverDevices[address] ?: return false
        val rxChar = rxNotifyCharacteristic ?: return false
        if (!BluetoothCapabilities.canConnect(context)) return false
        val attPayloadLimit = ((peerMtus[address] ?: 23) - 3).coerceAtLeast(20)

        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + attPayloadLimit)
            val slice = bytes.copyOfRange(offset, end)
            val deferred = CompletableDeferred<Boolean>()
            serverNotifyWaiters[address] = deferred

            val initiated = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, rxChar, false, slice) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    rxChar.value = slice
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, rxChar, false)
                }
            } catch (_: SecurityException) {
                false
            }
            if (!initiated) {
                serverNotifyWaiters.remove(address)
                return false
            }
            val ok = withTimeoutOrNull(4_000L) { deferred.await() } ?: false
            if (!ok) return false
            offset = end
        }
        return true
    }

    private fun appendIncomingBytes(address: String, chunk: ByteArray) {
        val acc = receiveAccumulators.getOrPut(address) { ByteArrayOutputStream() }
        synchronized(acc) {
            acc.write(chunk)
            var current = acc.toByteArray()
            var offset = 0
            while (offset < current.size) {
                when (val decoded = TransportCodec.decode(current, offset, current.size - offset)) {
                    is DecodeFrameResult.Success -> {
                        offset += decoded.bytesConsumed
                        scope.launch(Dispatchers.Default) {
                            onFrameReceived(address, decoded.frame)
                        }
                    }
                    is DecodeFrameResult.NeedMoreBytes -> break
                    is DecodeFrameResult.Malformed -> {
                        // Resynchronize by scanning for next MAGIC header "BG"
                        val nextMagic = findNextMagic(current, offset + 1)
                        offset = if (nextMagic >= 0) nextMagic else current.size
                    }
                }
            }
            acc.reset()
            if (offset < current.size) {
                acc.write(current, offset, current.size - offset)
            }
        }
    }

    private fun findNextMagic(bytes: ByteArray, start: Int): Int {
        for (i in start until bytes.size - 1) {
            if (bytes[i] == TransportCodec.MAGIC_0 && bytes[i + 1] == TransportCodec.MAGIC_1) {
                return i
            }
        }
        return -1
    }

    @SuppressLint("MissingPermission")
    private fun cleanupClientGatt(address: String) {
        writeWaiters.remove(address)?.complete(false)
        clientWriteChars.remove(address)
        receiveAccumulators.remove(address)
        val gatt = clientGatts.remove(address)
        try {
            if (BluetoothCapabilities.canConnect(context)) {
                gatt?.disconnect()
                gatt?.close()
            }
        } catch (_: Exception) {
        }
    }

    fun release() {
        clientGatts.keys.toList().forEach { cleanupClientGatt(it) }
        stopGattServer()
    }
}
