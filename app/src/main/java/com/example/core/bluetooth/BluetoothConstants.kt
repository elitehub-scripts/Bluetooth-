package com.example.core.bluetooth

import java.util.UUID

object BluetoothConstants {
    // 128-bit UUIDs dedicated to Bluetooth Global Share
    val BLE_SERVICE_UUID: UUID = UUID.fromString("8B6D0001-4E2A-4D7E-9F1A-2B3C4D5E6F01")
    val BLE_CHAR_TX_UUID: UUID = UUID.fromString("8B6D0002-4E2A-4D7E-9F1A-2B3C4D5E6F01") // Central writes here
    val BLE_CHAR_RX_UUID: UUID = UUID.fromString("8B6D0003-4E2A-4D7E-9F1A-2B3C4D5E6F01") // Peripheral notifies here
    val BLE_CHAR_IDENTITY_UUID: UUID = UUID.fromString("8B6D0004-4E2A-4D7E-9F1A-2B3C4D5E6F01") // Read local identity metadata
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Bluetooth Classic RFCOMM Service UUID & SDP Record Name
    val RFCOMM_SERVICE_UUID: UUID = UUID.fromString("8B6D1001-4E2A-4D7E-9F1A-2B3C4D5E6F01")
    const val RFCOMM_SERVICE_NAME = "BluetoothGlobalShare"

    const val MAX_ACTIVE_PEERS = 7
    const val DEFAULT_BLE_CHUNK_PAYLOAD = 180
    const val MAX_BLE_CHUNK_PAYLOAD = 440
    const val RFCOMM_CHUNK_PAYLOAD = 16 * 1024
    const val HANDSHAKE_TIMEOUT_MS = 12_000L
    const val HEARTBEAT_INTERVAL_MS = 20_000L
}
