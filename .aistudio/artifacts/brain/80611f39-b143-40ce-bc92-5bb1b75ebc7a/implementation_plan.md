# Flip: First-Time Hotspot Pairing & 1-Tap Transfer Architecture

Flip enables high-speed, offline peer-to-peer file transfers between Android devices. This plan specifies the complete 4-stage architecture for first-time secure hotspot pairing (biometric-gated QR containing encrypted credentials and stable device identity) followed by subsequent seamless 1-tap BLE discovery, Wi-Fi hotspot joining, and socket transfer.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following key decisions were confirmed through interactive clarification and govern the full implementation:

- **Hotspot Access Warning Placement**: Displayed as a permanent warning card directly in the "First time" tab positioned immediately above the QR code: *"Anyone you pair with can use your hotspot while in the app."*
- **Receiver Hotspot Connection Trigger**: When a paired sender is detected advertising over BLE, the receiver connects upon tapping the sender card in the Nearby list, seamlessly initiating the Wi-Fi connection and handshake.
- **Android Version Compatibility**:
  - Android 10+ (API 29–35): Uses `WifiNetworkSpecifier` without `NET_CAPABILITY_INTERNET`, requesting peer connection directly with approval dialog.
  - Android 8–9 (API 26–28): Graceful fallback to `WifiConfiguration` where supported or guidance to connect via system settings / manual code entry.
- **Execution Cadence**: Strict **one-prompt-at-a-time** delivery:
  1. **Prompt 1: Foundations** (Stable Device Identity, Wi-Fi Parser with WPA3/SAE, AES-256-GCM Keystore CredentialStore, First-Time Payload Codec, Unit Tests).
  2. **Prompt 2: Sender Side** (Pulsing radio animation, BiometricPrompt gate, offline ML Kit screenshot importer, 60s timeout, FLAG_SECURE).
  3. **Prompt 3: Receiver Core** (QR scanner, WifiNetworkSpecifier joiner, port 8081 handshake, gateway handoff).
  4. **Prompt 4: Nearby List & Polish** (BLE discovery list, failure states, "Needs re-pair" recovery, Settings device management).

---

## 1. Overview & Core Concept

- **What It Does**: The first time two phones interact, the sender unlocks a biometric/PIN gate to generate a first-time QR containing its Wi-Fi hotspot details and unique device identity. The receiver scans this QR once and stores the credentials encrypted via the Android Keystore. On subsequent transfers, the receiver discovers the sender over BLE, taps to connect, and Android automatically connects to the sender's hotspot, completes a lightweight handshake, and proceeds directly with the transfer without requiring another QR scan.
- **Target Audience / Persona**: Users transferring photos, videos, and large files peer-to-peer in environments without internet, cellular connectivity, or shared local Wi-Fi.
- **Key Value**: Eliminates repetitive QR scanning and manual hotspot password typing while maintaining military-grade local credential security, preventing unauthorized access, and upholding strict privacy.

---

## 2. User Experience & Visual Design

### Key User Flows

#### Flow 1: Sender "First Time" Setup (Prompt 2)
1. Sender opens Flip, starts a transfer ("Flipping Out..."), and navigates to the **First time** tab.
2. System immediately presents a `BiometricPrompt` (*"Confirm it's you"* / *"Unlock to show your hotspot details"*).
3. If credentials are not yet saved, sender imports via:
   - Screenshot of system Wi-Fi QR (offline ML Kit barcode scanning).
   - Manual entry form (SSID, password, security including WPA3).
   - Clipboard paste of standard `WIFI:...` string.
4. Once saved and authenticated, the screen sets `FLAG_SECURE`, displays the warning: *"Anyone you pair with can use your hotspot while in the app."*, and presents the first-time QR code.
5. A 60-second countdown runs; after expiration, backgrounding, or leaving the tab, the screen locks immediately.

#### Flow 2: Receiver First-Time Scan & Save (Prompt 3)
1. Receiver opens the QR scanner (via Settings or the Nearby list "New" prompt).
2. Receiver scans the sender's `flip://first?...` QR code.
3. If already paired, receiver is prompted: *"Replace saved details for <name · short code>?"*.
4. On save, credentials are encrypted via AES-256-GCM and stored. The screen confirms: *"Paired with <name · short code>"* without displaying any passwords or raw IDs.

#### Flow 3: Seamless 1-Tap Transfer (Prompts 3 & 4)
1. Sender is in "Waiting for receiver..." state, broadcasting BLE advertisements with its stable 8-char hex identity and waiting flag.
2. Receiver opens Receive screen and sees the sender listed under **Nearby** with a **Paired** badge.
3. Receiver taps the sender card. Flip creates a `WifiNetworkSpecifier` and requests network attachment.
4. When Wi-Fi is acquired, Flip connects to the sender on handshake port `8081`. Both sides verify identities.
5. Sockets bind to the dedicated Wi-Fi network and proceed straight to file transfer.

### Visual Identity & Theme
- **Theme & Colors**: Aligns with Flip's existing dark palette (`#0F172A` Slate Dark / `#1E293B` Card Background) paired with vibrant emerald/cyan accents (`#10B981` / `#06B6D4`).
- **Pulsing Animation**: Smooth 1.5s radial pulse loop on Tab 1 using theme primary colors, automatically replaced by a clean static icon if system reduce-motion is enabled.
- **Typography & Ergonomics**: High-contrast display tags for short codes (`#A8B2C1`), 48dp minimum touch targets, and clear status badges.

---

## 3. Key Product Decisions & Trade-Offs

- **Stable UUID vs. Session ID**:
  - *Decision*: Generate an immutable, cryptographically secure random UUID on first app launch stored in Keystore-encrypted preferences. Derive `id8` (first 8 hex characters of SHA-256) and `shortCode` (first 4 chars of `id8` uppercase) for user display and protocol indexing. Any ephemeral connection tracking uses a distinct `sid`.
  - *Why*: Enables reliable 1-tap peer matching without tracking device hardware IDs (zero IMEI/MAC/ANDROID_ID usage).
- **WPA3-SAE & Escaped Wi-Fi String Parsing**:
  - *Decision*: Expand the parser to support `WPA3` / `SAE` and full backslash escaping (`\\`, `\;`, `\,`, `\:`). Map `SAE` to `WPA3` and `WPA` to `WPA2`.
  - *Why*: Modern Android 12+ hotspots default to WPA3-Personal (SAE). Missing SAE support breaks pairing on modern Pixel and Samsung flagships.
- **Network Request Without Internet Capability**:
  - *Decision*: In `NetworkRequest.Builder`, explicitly remove `NET_CAPABILITY_INTERNET`.
  - *Why*: Local-only hotspots provide no WAN connectivity. Leaving the default internet capability causes Android to reject or immediately drop the connection.
- **Zero-Crash Decryption Recovery**:
  - *Decision*: If cryptographic decryption fails (e.g. key invalidated by lock screen change or restored cloud backup), return `DecryptResult.Failed(NeedsRepair)` rather than crashing. Flag the peer entry as "Needs re-pair" and prompt the user to re-scan.
- **Strict Credential Sanitization**:
  - *Decision*: No Wi-Fi passwords, SSIDs, gateway IPs, or full UUIDs are ever emitted in logcat or general UI lists. Decrypted passwords exist in memory only during `WifiNetworkSpecifier` construction and are promptly cleared.

---

## 4. Technical Architecture & Data Strategy

### System Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                              FLIP SENDER                                │
│                                                                        │
│   [Biometric Gate] ───► [FirstTimeHotspotTab]                          │
│                               │                                        │
│                        (FLAG_SECURE, 60s)                              │
│                               ▼                                        │
│                      [flip://first QR Code]                            │
│                               │                                        │
│   [BLE Advertiser] ◄──── [DeviceIdentity] ───► [Keystore AES-256-GCM]   │
│   (uuid8, waiting)       (uuid, id8, code)      [CredentialStore]      │
│          │                                                             │
│   [Handshake Server] (Port 8081) ◄────────────────┐                   │
│   [HTTP File Server] (Port 8080)                  │                   │
└──────────┼────────────────────────────────────────┼────────────────────┘
           │ BLE Broadcast                          │ Wi-Fi Connection
           ▼                                        │ & Socket Handshake
┌───────────────────────────────────────────────────┼────────────────────┐
│                              FLIP RECEIVER        │                    │
│                                                   │                    │
│   [BLE Scanner] ──────► [Nearby Devices List]     │                    │
│   (Nearby Peers)              │ (1-Tap Connect)   │                    │
│                               ▼                   │                    │
│                      [ConnectionManager] ─────────┘                    │
│                               │                                        │
│                      [WifiNetworkSpecifier]                            │
│                      (No NET_CAP_INTERNET)                             │
│                               │                                        │
│   [QR Scanner] ───────► [CredentialStore] ◄──── [DeviceIdentity]       │
│   (First-time scan)     (AES-256-GCM Peers)                            │
└────────────────────────────────────────────────────────────────────────┘
```

### Data Models & Contracts (Prompt 1)

```kotlin
// Device Identity
data class DeviceIdentity(
    val uuid: String,          // Cryptographically random 128-bit UUID (e.g. 550e8400-e29b-41d4-a716-446655440000)
    val id8: String,           // First 8 hex characters of SHA-256(uuid)
    val shortCode: String,     // First 4 characters of id8, uppercased (e.g. "B2F1")
    val displayName: String    // User editable phone name (default Build.MODEL)
)

// Wi-Fi Credentials
enum class SecurityType { WPA2, WPA3, WEP, OPEN }

data class WifiCredentials(
    val ssid: String,
    val password: String,
    val security: SecurityType = SecurityType.WPA2,
    val isHidden: Boolean = false
)

// Peer Entry
data class PeerSummary(
    val uuid: String,
    val id8: String,
    val shortCode: String,
    val displayName: String,
    val hasCredentials: Boolean,
    val needsRepair: Boolean,
    val lastConnected: Long
)
```

### Four-Stage Phased Execution Plan

| Stage | Scope & Deliverables | Verification Strategy |
| :--- | :--- | :--- |
| **Stage 1: Foundations** *(Current Prompt)* | • `DeviceIdentityManager`: stable UUID generated once, stored in Keystore-backed storage, immutable across restarts.<br>• `WifiCredentialsParser`: full parser supporting `WPA2`, `WPA3` (`SAE`), `WEP`, `OPEN`, escaped chars (`\\`, `\;`, `\,`, `\:`), hidden networks.<br>• `CredentialStore`: Keystore AES-256-GCM storage with separate peer/own management, backup exclusion, graceful `DecryptFailed` handling.<br>• `FirstTimePayload`: unified encoder/decoder for `flip://first`. | Unit tests covering plain, escaped characters, SAE, open, hidden, missing SSID, garbage inputs, and Keystore persistence sanity checks. |
| **Stage 2: Sender Experience** | • Tab 1: Pulsing radio/hotspot animation loop (1.5s) with reduce-motion fallback; BLE advertisement payload preserved.<br>• Tab 2: BiometricPrompt security gate (`BIOMETRIC_STRONG` / `DEVICE_CREDENTIAL`), 60s auto-lock, `FLAG_SECURE`.<br>• Hotspot import: offline ML Kit barcode scanning for screenshots, manual input, clipboard.<br>• Hotspot access warning displayed above QR. | Biometric cancellation verifies tab return; 60s timeout relocks QR; screenshot prevention verified via `FLAG_SECURE`. |
| **Stage 3: Receiver Core** | • QR Scanner integration for `flip://first` payload.<br>• Conflict dialog: *"Replace saved details for <name · short code>?"* before overwriting.<br>• `ConnectionManager`: `WifiNetworkSpecifier` builder (WPA2/WPA3), 15s timeout, removing `NET_CAPABILITY_INTERNET`.<br>• Socket Handshake: Port 8081 peer identity confirmation, gateway detection, and seamless transition to transfer pipeline. | Two-device pairing verification: B scans A, saves peer, connects to A's hotspot, completes handshake, and starts transfer. |
| **Stage 4: Nearby List & Resilience** | • Main receive screen Nearby list driven by BLE advertisements.<br>• 1-tap connect for paired senders, scanner launch for new senders.<br>• Failure handling: specific user reasons, 2-strike "Needs re-pair" trigger if SSID visible in scan.<br>• Settings paired devices management: list, forget, forget all, re-pair. | Full end-to-end CUJ testing: dynamic peer discovery, 1-tap join, recovery from modified passwords, and device forget actions. |
