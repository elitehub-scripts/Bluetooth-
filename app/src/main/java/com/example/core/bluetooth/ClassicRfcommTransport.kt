package com.example.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.example.core.models.TransportType
import com.example.core.protocol.DecodeFrameResult
import com.example.core.protocol.TransportCodec
import com.example.core.protocol.TransportFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class ClassicRfcommTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLinkConnected: (TransportLink) -> Unit,
    private val onFrameReceived: (address: String, frame: TransportFrame) -> Unit,
    private val onLinkDisconnected: (address: String, reason: String) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private val activeSockets = ConcurrentHashMap<String, ActiveSocketSession>()

    private data class ActiveSocketSession(
        val socket: BluetoothSocket,
        val sendQueue: Channel<ByteArray>,
        val readJob: Job,
        val writeJob: Job
    )

    @SuppressLint("MissingPermission")
    fun startServer(): Boolean {
        val btAdapter = adapter ?: return false
        if (!btAdapter.isEnabled || !BluetoothCapabilities.canConnect(context)) return false
        if (acceptJob?.isActive == true) return true

        val srv = try {
            // Application layer enforces X25519 + Ed25519 + AES-256-GCM session security,
            // allowing seamless peer-to-peer connection without OS PIN friction.
            btAdapter.listenUsingInsecureRfcommWithServiceRecord(
                BluetoothConstants.RFCOMM_SERVICE_NAME,
                BluetoothConstants.RFCOMM_SERVICE_UUID
            )
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        } ?: return false

        serverSocket = srv
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val socket = srv.accept() ?: break
                    attachSocket(socket)
                } catch (_: IOException) {
                    break
                } catch (_: SecurityException) {
                    break
                }
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        val addr = device.address
        if (!BluetoothCapabilities.canConnect(context)) {
            onLinkDisconnected(addr, "Bluetooth connect permission missing")
            return
        }
        if (activeSockets.containsKey(addr)) return

        scope.launch(Dispatchers.IO) {
            val btAdapter = adapter
            try {
                if (BluetoothCapabilities.canScan(context) && btAdapter?.isDiscovering == true) {
                    btAdapter.cancelDiscovery()
                }
                val socket = device.createInsecureRfcommSocketToServiceRecord(
                    BluetoothConstants.RFCOMM_SERVICE_UUID
                )
                socket.connect()
                attachSocket(socket)
            } catch (e: IOException) {
                onLinkDisconnected(addr, e.message ?: "Classic RFCOMM connect failed")
            } catch (_: SecurityException) {
                onLinkDisconnected(addr, "Bluetooth permission denied")
            }
        }
    }

    private fun attachSocket(socket: BluetoothSocket) {
        val address = socket.remoteDevice.address
        activeSockets.remove(address)?.let { prev ->
            prev.readJob.cancel()
            prev.writeJob.cancel()
            prev.sendQueue.close()
            try {
                prev.socket.close()
            } catch (_: IOException) {
            }
        }

        // Bounded queue prevents OOM on slow RFCOMM links
        val sendQueue = Channel<ByteArray>(capacity = 64)

        val writeJob = scope.launch(Dispatchers.IO) {
            try {
                val out = socket.outputStream
                for (chunk in sendQueue) {
                    out.write(chunk)
                    out.flush()
                }
            } catch (_: IOException) {
                disconnect(address, "RFCOMM write failed")
            }
        }

        val readJob = scope.launch(Dispatchers.IO) {
            val accumulator = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            try {
                val input = socket.inputStream
                while (isActive) {
                    val read = input.read(buf)
                    if (read < 0) break
                    if (read == 0) continue
                    accumulator.write(buf, 0, read)
                    val all = accumulator.toByteArray()
                    var offset = 0
                    while (offset < all.size) {
                        when (val decoded = TransportCodec.decode(all, offset, all.size - offset)) {
                            is DecodeFrameResult.Success -> {
                                offset += decoded.bytesConsumed
                                launch(Dispatchers.Default) {
                                    onFrameReceived(address, decoded.frame)
                                }
                            }
                            is DecodeFrameResult.NeedMoreBytes -> break
                            is DecodeFrameResult.Malformed -> {
                                val nextMagic = findNextMagic(all, offset + 1)
                                offset = if (nextMagic >= 0) nextMagic else all.size
                            }
                        }
                    }
                    accumulator.reset()
                    if (offset < all.size) {
                        accumulator.write(all, offset, all.size - offset)
                    }
                }
            } catch (_: IOException) {
                // Socket closed / peer moved out of range
            } finally {
                disconnect(address, "RFCOMM stream closed")
            }
        }

        activeSockets[address] = ActiveSocketSession(socket, sendQueue, readJob, writeJob)

        val link = object : TransportLink {
            override val address: String = address
            override val transportType: TransportType = TransportType.CLASSIC_RFCOMM
            override val maxFramePayloadBytes: Int = BluetoothConstants.RFCOMM_CHUNK_PAYLOAD

            override suspend fun sendBytes(bytes: ByteArray): Boolean {
                val session = activeSockets[address] ?: return false
                return try {
                    session.sendQueue.send(bytes)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            override fun close() {
                disconnect(address, "Closed by local peer")
            }
        }
        onLinkConnected(link)
    }

    private fun findNextMagic(bytes: ByteArray, start: Int): Int {
        for (i in start until bytes.size - 1) {
            if (bytes[i] == TransportCodec.MAGIC_0 && bytes[i + 1] == TransportCodec.MAGIC_1) {
                return i
            }
        }
        return -1
    }

    private fun disconnect(address: String, reason: String) {
        val session = activeSockets.remove(address) ?: return
        session.readJob.cancel()
        session.writeJob.cancel()
        session.sendQueue.close()
        try {
            session.socket.close()
        } catch (_: IOException) {
        }
        onLinkDisconnected(address, reason)
    }

    fun release() {
        stopServer()
        activeSockets.keys.toList().forEach { disconnect(it, "Transport shutdown") }
    }
}
