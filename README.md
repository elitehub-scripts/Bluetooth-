# Bluetooth Global Share

**Bluetooth Global Share** is an offline-first, zero-cloud Android application for peer-to-peer messaging, chunked file sharing, and decentralized store-and-forward relaying over Bluetooth.

---

## Important Physical Range & Connectivity Disclosure

Bluetooth is a short-range physical radio (~10–100 meters depending on environment and radio class). **Bluetooth cannot connect two phones in different countries directly.**

Instead, Bluetooth Global Share implements a realistic **decentralized store-and-forward relay network**:
- When a recipient is not physically nearby, your phone encrypts the message end-to-end (`X25519` + `HKDF-SHA256` + `AES-256-GCM`) and signs the relay envelope (`Ed25519`).
- Nearby participating phones cache only the opaque encrypted envelope and forward it hop-by-hop when they encounter other compatible devices.
- Delivery over longer distances or across regions requires a physical chain of encounters over time.
- A message is **never** marked `Delivered` just because a relay accepted it; only a signed `DeliveryAckEnvelope` from the true destination upgrades status to `Delivered`.

---

## Core Architecture

- **Bluetooth Connectivity Engine (`core/bluetooth/`)**
  - `BleDiscoveryAndAdvertiser`: Adaptive BLE advertising & duty-cycled scanning (`LOW_POWER`, `BALANCED`, `LOW_LATENCY`) + Classic Bluetooth discovery bursts.
  - `BleGattTransport`: Custom GATT Server & Client (`8B6D0001-...`) with MTU negotiation (up to 512 bytes) and flow-controlled writes.
  - `ClassicRfcommTransport`: RFCOMM socket server & client (`8B6D1001-...`) with bounded send queues (`Channel(64)`) for high-throughput file streaming.
  - `BluetoothTransportManager`: Unifies BLE GATT and Classic RFCOMM, enforces max 7 active peers, executes `X25519` + `Ed25519` session handshakes, and monitors adapter state.
- **Binary Transport Protocol (`core/protocol/`)**
  - Framed binary packets (`BG` magic, version, packet type, sequence numbers, stream ID, payload length, CRC32 checksum), MTU-aware `Fragmenter`, and bounded `ReassemblyBuffer`.
- **Cryptography & Identity (`core/crypto/`)**
  - Google Tink `Ed25519` identity signing + `X25519` key agreement + `AES-256-GCM` authenticated encryption + `SHA-256` integrity verification.
  - Private keys wrapped at rest via `AndroidKeyStore` (`AES/GCM/NoPadding`).
- **Store-and-Forward Relay Engine (`core/relay/`)**
  - TTL expiration, max hop limits, duplicate prevention, blocked-device filtering, priority relay queue, storage quota eviction, and signed delivery ACKs.
- **Chunked File Transfer (`core/transfer/`)**
  - Uses Android Photo Picker (`PickVisualMedia`) and Storage Access Framework (`OpenDocument` / `CreateDocument`) without broad storage permissions.
  - Streams chunks directly to/from temporary storage (never loads large files into RAM), supports pause/resume/cancel, verifies `SHA-256` before atomic completion, and never auto-installs `.apk` files.
- **Offline Room Persistence (`core/database/`)**
  - 12 indexed entities (`DeviceProfile`, `Contacts`, `Messages`, `Conversations`, `RelayMessages`, `RelayHistory`, `FileTransfers`, `TrustedDevices`, `BlockedDevices`, `ConnectionHistory`, `NetworkNodes`, `ApplicationSettings`).
- **Background & Battery Handling (`services/`)**
  - `BluetoothMeshForegroundService` (`foregroundServiceType="connectedDevice"`) and periodic `WorkManager` (`RelayMaintenanceWorker`).

---

## Minimum Android Version & OS Limitations

- **Minimum SDK**: Android 7.0 (API 24)
- **Target / Compile SDK**: Android 16 (API 36)
- **Permissions**:
  - Android 12+ (API 31+): `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`
  - Android 11 and lower (API <= 30): `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`
  - Android 13+ (API 33+): `POST_NOTIFICATIONS`
- **OS & OEM Restrictions**: Android restricts background BLE scanning and Doze wakeups. Aggressive manufacturer battery savers (Xiaomi MIUI, Samsung OneUI, Huawei EMUI, OnePlus OxygenOS) may suspend background services unless exempted in Android Battery Optimization settings.

---

## Building the APK (Gradle)

### Debug APK
```bash
gradle :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Signed Release APK
Configure signing keystore environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) and run:
```bash
gradle :app:assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

### Running Automated Unit & Robolectric Tests
```bash
gradle :app:testDebugUnitTest
```

> **Note on Physical Device Tests**: Multi-device Bluetooth discovery, live GATT/RFCOMM transfers, and physical multi-hop relay tests require two or more real Android phones with Bluetooth radios enabled; JVM/Robolectric tests verify protocol framing, cryptography, Room persistence, relay routing logic, file chunking/integrity, and Compose UI navigation.
