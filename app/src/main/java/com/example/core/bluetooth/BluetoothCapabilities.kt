package com.example.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class BluetoothHardwareStatus(
    val hasBluetoothHardware: Boolean,
    val hasBleHardware: Boolean,
    val isBluetoothEnabled: Boolean,
    val adapterName: String,
    val missingPermissions: List<String>,
    val canScan: Boolean,
    val canAdvertise: Boolean,
    val canConnect: Boolean,
    val canPostNotifications: Boolean
)

object BluetoothCapabilities {

    fun requiredRuntimePermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun canScan(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun canAdvertise(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            true
        }
    }

    fun canConnect(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
    }

    fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    fun getHardwareStatus(context: Context): BluetoothHardwareStatus {
        val pm = context.packageManager
        val hasBt = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        val hasBle = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = manager?.adapter

        val scanOk = canScan(context)
        val advOk = canAdvertise(context)
        val connOk = canConnect(context)
        val notifOk = canPostNotifications(context)

        val missing = requiredRuntimePermissions().filterNot { hasPermission(context, it) }

        val adapterName = if (connOk && adapter != null) {
            try {
                adapter.name ?: "Android Device"
            } catch (_: SecurityException) {
                "Android Device"
            }
        } else {
            "Android Device"
        }

        return BluetoothHardwareStatus(
            hasBluetoothHardware = hasBt && adapter != null,
            hasBleHardware = hasBle && adapter != null,
            isBluetoothEnabled = adapter?.isEnabled == true,
            adapterName = adapterName,
            missingPermissions = missing,
            canScan = scanOk,
            canAdvertise = advOk,
            canConnect = connOk,
            canPostNotifications = notifOk
        )
    }
}
