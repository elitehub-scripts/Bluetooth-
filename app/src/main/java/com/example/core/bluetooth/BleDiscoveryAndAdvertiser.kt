package com.example.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import com.example.core.models.BleScanModeSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RawDiscoveredBluetoothDevice(
    val address: String,
    val name: String,
    val rssi: Int?,
    val supportsBle: Boolean,
    val supportsClassic: Boolean,
    val isBonded: Boolean,
    val isCompatibleBgsService: Boolean,
    val serviceHintDeviceId: String?,
    val lastSeenAt: Long,
    val bluetoothDevice: BluetoothDevice
)

class BleDiscoveryAndAdvertiser(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localDeviceId: String
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<Map<String, RawDiscoveredBluetoothDevice>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, RawDiscoveredBluetoothDevice>> = _discoveredDevices.asStateFlow()

    private var scanJob: Job? = null
    private var activeScanner: BluetoothLeScanner? = null
    private var activeAdvertiser: BluetoothLeAdvertiser? = null
    private var classicReceiverRegistered = false

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleBleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleBleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _lastError.value = "BLE scan error code $errorCode"
            _isScanning.value = false
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _isAdvertising.value = true
            _lastError.value = null
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
            _lastError.value = "BLE advertise failed (code $errorCode)"
        }
    }

    private val classicDiscoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    .takeIf { it != Short.MIN_VALUE.toInt() }
                if (device != null && BluetoothCapabilities.canConnect(context)) {
                    val safeName = try {
                        device.name ?: "Bluetooth Device"
                    } catch (_: SecurityException) {
                        "Bluetooth Device"
                    }
                    val bonded = try {
                        device.bondState == BluetoothDevice.BOND_BONDED
                    } catch (_: SecurityException) {
                        false
                    }
                    upsertDevice(
                        RawDiscoveredBluetoothDevice(
                            address = device.address,
                            name = safeName,
                            rssi = rssi,
                            supportsBle = device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                                device.type == BluetoothDevice.DEVICE_TYPE_DUAL,
                            supportsClassic = device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                                device.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                                device.type == BluetoothDevice.DEVICE_TYPE_UNKNOWN,
                            isBonded = bonded,
                            isCompatibleBgsService = safeName.startsWith("BGS-"),
                            serviceHintDeviceId = null,
                            lastSeenAt = System.currentTimeMillis(),
                            bluetoothDevice = device
                        )
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshBondedDevices() {
        val btAdapter = adapter ?: return
        if (!btAdapter.isEnabled || !BluetoothCapabilities.canConnect(context)) return
        try {
            btAdapter.bondedDevices?.forEach { device ->
                val name = device.name ?: "Paired Device"
                val type = device.type
                upsertDevice(
                    RawDiscoveredBluetoothDevice(
                        address = device.address,
                        name = name,
                        rssi = null,
                        supportsBle = type == BluetoothDevice.DEVICE_TYPE_LE ||
                            type == BluetoothDevice.DEVICE_TYPE_DUAL,
                        supportsClassic = type == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                            type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                            type == BluetoothDevice.DEVICE_TYPE_UNKNOWN,
                        isBonded = true,
                        isCompatibleBgsService = name.startsWith("BGS-"),
                        serviceHintDeviceId = null,
                        lastSeenAt = System.currentTimeMillis(),
                        bluetoothDevice = device
                    )
                )
            }
        } catch (_: SecurityException) {
            _lastError.value = "Missing Bluetooth connect permission"
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdaptiveScanning(mode: BleScanModeSetting) {
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _lastError.value = "Bluetooth adapter is disabled or unavailable"
            _isScanning.value = false
            return
        }
        if (!BluetoothCapabilities.canScan(context)) {
            _lastError.value = "Nearby scan permission required"
            _isScanning.value = false
            return
        }

        refreshBondedDevices()
        stopScanning()

        scanJob = scope.launch {
            while (true) {
                val scanner = btAdapter.bluetoothLeScanner
                if (scanner == null) {
                    _lastError.value = "BLE scanner unavailable on this hardware"
                    _isScanning.value = false
                    break
                }
                activeScanner = scanner
                val scanSettings = ScanSettings.Builder()
                    .setScanMode(
                        when (mode) {
                            BleScanModeSetting.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
                            BleScanModeSetting.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                            BleScanModeSetting.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
                        }
                    )
                    .build()

                val filters = listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(BluetoothConstants.BLE_SERVICE_UUID))
                        .build()
                )

                try {
                    scanner.startScan(filters, scanSettings, bleScanCallback)
                    _isScanning.value = true
                    _lastError.value = null
                } catch (e: SecurityException) {
                    _lastError.value = "Permission denied for BLE scanning"
                    _isScanning.value = false
                    break
                } catch (e: Exception) {
                    _lastError.value = e.message ?: "Unable to start BLE scan"
                    _isScanning.value = false
                    break
                }

                // Adaptive duty cycling to preserve battery
                val activeWindowMs = when (mode) {
                    BleScanModeSetting.LOW_POWER -> 8_000L
                    BleScanModeSetting.BALANCED -> 12_000L
                    BleScanModeSetting.LOW_LATENCY -> 20_000L
                }
                val pauseWindowMs = when (mode) {
                    BleScanModeSetting.LOW_POWER -> 22_000L
                    BleScanModeSetting.BALANCED -> 8_000L
                    BleScanModeSetting.LOW_LATENCY -> 3_000L
                }

                delay(activeWindowMs)
                try {
                    scanner.stopScan(bleScanCallback)
                } catch (_: Exception) {
                }
                _isScanning.value = false
                delay(pauseWindowMs)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startClassicDiscoveryBurst() {
        val btAdapter = adapter ?: return
        if (!btAdapter.isEnabled || !BluetoothCapabilities.canScan(context)) return
        registerClassicReceiverIfNeeded()
        try {
            if (btAdapter.isDiscovering) {
                btAdapter.cancelDiscovery()
            }
            btAdapter.startDiscovery()
        } catch (_: SecurityException) {
            _lastError.value = "Classic discovery permission missing"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        try {
            if (BluetoothCapabilities.canScan(context)) {
                activeScanner?.stopScan(bleScanCallback)
                if (adapter?.isDiscovering == true) {
                    adapter?.cancelDiscovery()
                }
            }
        } catch (_: Exception) {
        }
        _isScanning.value = false
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _isAdvertising.value = false
            return
        }
        if (!BluetoothCapabilities.canAdvertise(context)) {
            _lastError.value = "Bluetooth advertise permission required"
            return
        }
        val advertiser = btAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            _lastError.value = "BLE peripheral advertising not supported on this device"
            _isAdvertising.value = false
            return
        }
        activeAdvertiser = advertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val shortTag = localDeviceId.take(8).toByteArray(Charsets.UTF_8)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BluetoothConstants.BLE_SERVICE_UUID))
            .addServiceData(ParcelUuid(BluetoothConstants.BLE_SERVICE_UUID), shortTag)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            advertiser.stopAdvertising(advertiseCallback)
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: SecurityException) {
            _lastError.value = "Permission denied for BLE advertising"
        } catch (e: Exception) {
            _lastError.value = e.message
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        try {
            if (BluetoothCapabilities.canAdvertise(context)) {
                activeAdvertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (_: Exception) {
        }
        _isAdvertising.value = false
    }

    fun release() {
        stopScanning()
        stopAdvertising()
        if (classicReceiverRegistered) {
            try {
                context.unregisterReceiver(classicDiscoveryReceiver)
            } catch (_: Exception) {
            }
            classicReceiverRegistered = false
        }
    }

    private fun registerClassicReceiverIfNeeded() {
        if (!classicReceiverRegistered) {
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(classicDiscoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(classicDiscoveryReceiver, filter)
            }
            classicReceiverRegistered = true
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val record = result.scanRecord
        val hasBgsUuid = record?.serviceUuids?.any { it.uuid == BluetoothConstants.BLE_SERVICE_UUID } == true
        val serviceHint = record?.getServiceData(ParcelUuid(BluetoothConstants.BLE_SERVICE_UUID))
            ?.let { String(it, Charsets.UTF_8) }

        val safeName = if (BluetoothCapabilities.canConnect(context)) {
            try {
                device.name ?: record?.deviceName ?: "BGS Peer"
            } catch (_: SecurityException) {
                record?.deviceName ?: "BGS Peer"
            }
        } else {
            record?.deviceName ?: "BGS Peer"
        }

        val bonded = if (BluetoothCapabilities.canConnect(context)) {
            try {
                device.bondState == BluetoothDevice.BOND_BONDED
            } catch (_: SecurityException) {
                false
            }
        } else false

        upsertDevice(
            RawDiscoveredBluetoothDevice(
                address = device.address,
                name = safeName,
                rssi = result.rssi,
                supportsBle = true,
                supportsClassic = device.type == BluetoothDevice.DEVICE_TYPE_DUAL,
                isBonded = bonded,
                isCompatibleBgsService = hasBgsUuid,
                serviceHintDeviceId = serviceHint,
                lastSeenAt = System.currentTimeMillis(),
                bluetoothDevice = device
            )
        )
    }

    private fun upsertDevice(item: RawDiscoveredBluetoothDevice) {
        val current = _discoveredDevices.value.toMutableMap()
        val prev = current[item.address]
        current[item.address] = if (prev != null) {
            item.copy(
                supportsBle = item.supportsBle || prev.supportsBle,
                supportsClassic = item.supportsClassic || prev.supportsClassic,
                isCompatibleBgsService = item.isCompatibleBgsService || prev.isCompatibleBgsService,
                serviceHintDeviceId = item.serviceHintDeviceId ?: prev.serviceHintDeviceId
            )
        } else {
            item
        }
        _discoveredDevices.value = current
    }
}
