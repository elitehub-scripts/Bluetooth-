package com.example.services.bluetooth

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.BluetoothGlobalShareApp
import com.example.core.bluetooth.BluetoothCapabilities
import com.example.services.notifications.MeshNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class BluetoothMeshForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMeshAndSelf()
            return START_NOT_STICKY
        }

        val container = (application as BluetoothGlobalShareApp).container
        val notification = MeshNotificationHelper.buildServiceNotification(
            context = this,
            connectedPeers = container.transportManager.connectedPeers.value.size,
            queuedRelays = 0,
            isScanning = false
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                MeshNotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(MeshNotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        }

        val hw = BluetoothCapabilities.getHardwareStatus(this)
        if (!hw.hasBluetoothHardware || (!hw.canConnect && !hw.canScan)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        _isRunning.value = true
        container.transportManager.startServersAndDiscovery()

        statusObserverJob?.cancel()
        statusObserverJob = serviceScope.launch {
            combine(
                container.transportManager.connectedPeers,
                container.database.relayMessageDao().observeActiveCount(),
                container.transportManager.discoveryAndAdvertiser.isScanning
            ) { peers, relays, scanning ->
                Triple(peers.size, relays, scanning)
            }.collect { (peerCount, relayCount, scanning) ->
                val updated = MeshNotificationHelper.buildServiceNotification(
                    context = this@BluetoothMeshForegroundService,
                    connectedPeers = peerCount,
                    queuedRelays = relayCount,
                    isScanning = scanning
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(MeshNotificationHelper.SERVICE_NOTIFICATION_ID, updated)
            }
        }

        return START_STICKY
    }

    private fun stopMeshAndSelf() {
        val container = (application as BluetoothGlobalShareApp).container
        container.transportManager.stopAllServersAndScanning()
        _isRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        statusObserverJob?.cancel()
        serviceScope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.ACTION_START_MESH_SERVICE"
        const val ACTION_STOP = "com.example.ACTION_STOP_MESH_SERVICE"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, BluetoothMeshForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BluetoothMeshForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
