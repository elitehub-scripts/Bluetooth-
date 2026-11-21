package com.example

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.bluetooth.BluetoothCapabilities
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hardware-dependent instrumentation checks.
 * NOTE: Physical radio discovery, BLE GATT transfer, Classic RFCOMM socket transfer,
 * and multi-hop relay handoff require TWO OR MORE physical Android devices with Bluetooth enabled.
 */
@RunWith(AndroidJUnit4::class)
class HardwareBluetoothChecklistTest {

    @Test
    fun verifySystemBluetoothServicePresence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        assertNotNull("BluetoothManager system service should exist on Android devices", manager)
        BluetoothCapabilities.getHardwareStatus(context)
    }
}
