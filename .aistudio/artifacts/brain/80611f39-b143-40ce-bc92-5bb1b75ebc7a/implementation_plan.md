# Wi-Fi Direct & BLE Architecture Overhaul

This plan details the rework of Flip's device discovery and connection pipeline, replacing heuristic peer discovery with an explicit, robust BLE-assisted Wi-Fi Direct bridge orchestrated by `ConnectionCoordinator`.

## User Review & Critical Decisions

> [!IMPORTANT]
> The overhaul replaces heuristic Wi-Fi Direct peer discovery with a deterministic Host-Bridge pattern:
> 1. **Sender** creates the Wi-Fi Direct Group as Host and extracts real credentials (`SSID`, `passphrase`).
> 2. **Sender** advertises/transmits these credentials via BLE.
> 3. **Receiver** discovers the credentials via BLE and connects directly to the Wi-Fi Direct Group.
> 4. Once connected, high-speed sockets/HTTP handle the file transfers.

- **Confirmed Decision 1**: Adopt the Host-Bridge pattern using `ConnectionCoordinator` with separate `bleManager` and `wifiRepo` components.
- **Confirmed Decision 2**: Preserve the existing Jetpack Compose UI (`FlipAppContent`), ViewModel lifecycle, and file transfer pipeline in `MainActivity.kt`, adding the coordinator bindings and status observers non-destructively.
- **Confirmed Decision 3**: Maintain the project's namespace (`com.example`) across `com.example.data` and `com.example.utils` to ensure full compatibility with Gradle and Android OS manifests.

---

## 1. Overview & Core Concept

- **The Problem**: Pure Wi-Fi Direct peer discovery frequently fails across varying Android OEMs due to MAC randomization, OEM-specific P2P discovery timeouts, and ambiguous Group Owner negotiation.
- **The Solution**: 
  - **Sender (Host)** explicitly creates a Wi-Fi Direct group using `WifiP2pManager.createGroup`.
  - **Credentials Bridge**: Real group credentials (SSID and pre-shared key) are extracted via `WifiP2pManager.requestGroupInfo`.
  - **BLE Signaling**: Credentials are broadcast via BLE advertisement / GATT payload.
  - **Receiver**: Listens for BLE signaling, extracts group credentials, and securely connects to the P2P group.
  - **Transfer Pipeline**: Upon connection establishment, socket/HTTP transfers take place at maximum 5GHz/2.4GHz Wi-Fi throughput.

---

## 2. Technical Architecture & Component Hierarchy

```
┌────────────────────────────────────────────────────────────────────────┐
│                              MainActivity                              │
│         (Lifecycle, Permissions, Compose UI + Coordinator Bridge)      │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │
                                     ▼
                     ┌───────────────────────────────┐
                     │     ConnectionCoordinator     │
                     │  - Tracks ConnectionPhase     │
                     │  - Bridges Wi-Fi & BLE        │
                     └───────┬───────────────┬───────┘
                             │               │
             ┌───────────────┘               └───────────────┐
             ▼                                               ▼
┌───────────────────────────────┐               ┌───────────────────────────────┐
│     BleConnectionManager      │               │     WifiDirectRepository      │
│  - BLE Advertising            │               │  - P2P Group Creation         │
│  - BLE Scanning               │               │  - Credential Extraction      │
│  - State flow: bleState       │               │  - Status flow: connStatus    │
└───────────────────────────────┘               └───────────────────────────────┘
```

---

## 3. Implementation Steps

### Phase 1: Foundation Helpers & Repositories
1. **`com.example.utils.PermissionsHelper`**:
   - Comprehensive helper for Android 8 through 15 (API 26 to 35).
   - Validates `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES` (API 33+), `BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT` (API 31+), and storage permissions.
   - Clean permission check and batch request contract.

2. **`com.example.data.wifi.WifiDirectRepository`**:
   - Manages `WifiP2pManager` and `WifiP2pManager.Channel`.
   - Exposes `connectionStatus` `StateFlow` (e.g. `Idle`, `Host (Connected)`, `Client (Connected)`, `Disconnected`).
   - Implements `createGroup(onSuccess, onFailure)`.
   - Implements `getGroupCredentials(): Pair<String?, String?>` via `requestGroupInfo` to obtain network name and passphrase.
   - Implements `connectToGroup(ssid, passphrase, targetAddress)` and clean group teardown.

3. **`com.example.data.ble.BleConnectionManager`**:
   - Exposes `bleState` `StateFlow`.
   - Implements safe manufacturer/service data encoding for SSID and passphrase.
   - Starts low-latency BLE advertising when Sender creates group.
   - Scans and decodes credentials on Receiver.

4. **`com.example.data.ConnectionCoordinator`**:
   - Orchestrates the state machine:
     - `startAsSender()`: Initiates Wi-Fi Direct group creation.
     - `onGroupCreated(ssid, pass)`: Broadcasts credentials through BLE.
     - `startAsReceiver()`: Begins BLE scanning for Sender credentials.
     - `onCredentialsDiscovered(ssid, pass)`: Instructs `wifiRepo` to connect.
     - `onWifiDirectConnected(isHost, groupOwnerAddress)`: Finalizes connection and transitions phase to `CONNECTED`.
     - `disconnect()`: Teardown of both BLE and Wi-Fi Direct.

### Phase 2: `MainActivity.kt` Non-Destructive Integration
1. Keep the full existing Jetpack Compose UI (`setContent { MyApplicationTheme { FlipAppContent(...) } }`) and share intents.
2. Initialize `ConnectionCoordinator`.
3. Add lifecycle observers for `coordinator.bleManager.bleState`, `coordinator.wifiRepo.connectionStatus`, and `coordinator.connectionPhase`.
4. Wire the `status.contains("Host (Connected)")` bridge to trigger `coordinator.onGroupCreated(ssid, pass)`.
5. Wire `status.contains("Connected")` to trigger `coordinator.onWifiDirectConnected(...)`.
6. Bind UI/ViewModel actions to trigger coordinator sender/receiver modes.

---

## 4. Risks & Mitigations

- **Risk**: API level differences in Wi-Fi Direct group credential extraction (`WifiP2pGroup.passphrase` is available on API 29+, on earlier versions PIN/WPS or network name inspection applies).
  - **Mitigation**: Guard API level checks with backward-compatible fallbacks for Android 8-9.
- **Risk**: Bluetooth disabled at runtime causing silent start failure.
  - **Mitigation**: Verify Bluetooth adapter state before advertising/scanning and propagate explicit errors.
- **Risk**: Breaking existing Compose UI or app build.
  - **Mitigation**: Purely additive integration that maintains all public APIs and runs `compile_applet` to verify compilation.
