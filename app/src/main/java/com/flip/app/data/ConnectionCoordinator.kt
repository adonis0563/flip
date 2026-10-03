package com.flip.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ConnectionCoordinator(private val context: Context) {

    companion object {
        private const val TAG = "ConnectionCoordinator"
        const val DEFAULT_TRANSFER_PORT = 8988
    }

    private val coordinatorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val bleManager = BleManager(context)
    val wifiP2pRepo = WifiP2pRepository(context)

    // Backward-compatible alias for existing UI and test references
    val wifiRepo get() = wifiP2pRepo

    private val _connectionPhase = MutableStateFlow("IDLE")
    val connectionPhase: StateFlow<String> = _connectionPhase.asStateFlow()

    fun initialize() {
        Log.d(TAG, "[COORDINATOR] Initializing ConnectionCoordinator")
        wifiP2pRepo.initialize()

        // 1. Receiver receives Wi-Fi Direct credentials from Sender via BLE GATT read
        bleManager.onCredentialsReceived = { ssid, passphrase, port ->
            Log.d(TAG, "[COORDINATOR] Credentials received via BLE GATT: SSID=$ssid, Port=$port")
            _connectionPhase.value = "CONNECTING_WIFI (SSID: $ssid, Port: $port)"

            // PHASE 2d: Silent Join with SSID + Passphrase from BLE cache
            wifiP2pRepo.joinGroupSilently(ssid, passphrase)
        }

        // 2. Sender: Receiver connected to Sender's GATT server -> Create Wi-Fi Direct group
        bleManager.onDeviceConnected = {
            Log.d(TAG, "[COORDINATOR] Receiver connected to GATT server! Creating Wi-Fi Direct group...")
            _connectionPhase.value = "RECEIVER_CONNECTED_CREATING_GROUP"
            wifiP2pRepo.createGroup()
        }

        bleManager.onError = { error ->
            Log.e(TAG, "[COORDINATOR] BLE error: $error")
            _connectionPhase.value = "BLE_ERROR: $error"
        }

        // 3. Observe Wi-Fi Direct group formation on Sender to feed credentials back into BLE GATT server
        coordinatorScope.launch {
            combine(
                wifiP2pRepo.groupSsid,
                wifiP2pRepo.groupPassphrase,
                wifiP2pRepo.isGroupFormed
            ) { ssid, pass, isFormed ->
                Triple(ssid, pass, isFormed)
            }.collect { (ssid, pass, isFormed) ->
                if (isFormed && wifiP2pRepo.isHost && !ssid.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                    Log.d(TAG, "[COORDINATOR] Group created on Sender: SSID=$ssid. Loading credentials into GATT Server.")
                    onGroupCreated(ssid, pass, DEFAULT_TRANSFER_PORT)
                }
            }
        }

        // 4. Observe Wi-Fi Direct connection state to trigger BLE cleanup once Wi-Fi tunnel is active
        coordinatorScope.launch {
            wifiP2pRepo.isGroupFormed.collect { isFormed ->
                if (isFormed) {
                    onWifiDirectConnected(
                        wifiP2pRepo.isHost,
                        wifiP2pRepo.groupOwnerAddress
                    )
                }
            }
        }
    }

    fun startAsSender() {
        Log.d(TAG, "[COORDINATOR] Starting as SENDER")
        disconnect()
        _connectionPhase.value = "SENDER_ADVERTISING_BLE"

        // 1. Start BLE GATT server and advertising
        bleManager.startGattServer()
        bleManager.startAdvertising()
    }

    fun startAsReceiver() {
        Log.d(TAG, "[COORDINATOR] Starting as RECEIVER")
        disconnect()
        _connectionPhase.value = "RECEIVER_SCANNING_BLE"

        // 1. Start BLE scanning for Flip Service UUID
        bleManager.startScanning()
    }

    fun onGroupCreated(ssid: String, pass: String, port: Int = DEFAULT_TRANSFER_PORT) {
        Log.d(TAG, "[COORDINATOR] onGroupCreated: Loading credentials into GATT Server (SSID=$ssid, Port=$port)")
        _connectionPhase.value = "SENDER_CREDENTIALS_READY (SSID: $ssid)"
        bleManager.setCredentialsForTransfer(ssid, pass, port)
    }

    fun onWifiDirectConnected(isHost: Boolean, groupOwnerAddress: String?) {
        Log.d(TAG, "[COORDINATOR] onWifiDirectConnected: isHost=$isHost, GO=$groupOwnerAddress")
        _connectionPhase.value = "TUNNEL_ACTIVE (Host=$isHost, GO=$groupOwnerAddress, Port=$DEFAULT_TRANSFER_PORT)"

        // BLE CLEANUP: Once Wi-Fi Direct encrypted tunnel is active, release BLE radio resources
        bleManager.cleanup()
        Log.d(TAG, "[COORDINATOR] BLE Cleaned up. Wi-Fi Direct tunnel active, ready for TransferManager.")
    }

    fun disconnect() {
        Log.d(TAG, "[COORDINATOR] Disconnecting all services")
        bleManager.cleanup()
        wifiP2pRepo.disconnect()
        _connectionPhase.value = "IDLE"
    }

    fun cleanup() {
        Log.d(TAG, "[COORDINATOR] Cleaning up coordinator")
        disconnect()
        wifiP2pRepo.cleanup()
        coordinatorScope.cancel()
    }
}
