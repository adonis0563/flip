package com.example.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FlipConnectionState {
    IDLE,
    STARTING_HOTSPOT,
    HOTSPOT_READY,
    SERVER_READY,
    BOOTSTRAP_READY,
    WAITING_FOR_PEER,
    WIFI_CONNECTING,
    WIFI_AVAILABLE,
    HTTP_HANDSHAKE,
    CONNECTED,
    TRANSFERRING,
    PAUSED,
    CANCELLED,
    FAILED,
    DISCONNECTED,

    // Backwards-compatible aliases
    NETWORK_AVAILABLE,
    DEVICE_FOUND,
    READY
}

enum class PeerConnectionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    READY
}

class ConnectionStateManager(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(FlipConnectionState.DISCONNECTED)
    val connectionState: StateFlow<FlipConnectionState> = _connectionState.asStateFlow()

    private val _currentIp = MutableStateFlow("127.0.0.1")
    val currentIp: StateFlow<String> = _currentIp.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    private val _hasDiscoveredDevices = MutableStateFlow(false)
    val hasDiscoveredDevices: StateFlow<Boolean> = _hasDiscoveredDevices.asStateFlow()

    private val _peerStatus = MutableStateFlow(PeerConnectionStatus.IDLE)
    val peerStatus: StateFlow<PeerConnectionStatus> = _peerStatus.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d(TAG, "Network available")
            updateNetworkStatus()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.d(TAG, "Network lost")
            updateNetworkStatus()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            updateNetworkStatus()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val enabled = state == BluetoothAdapter.STATE_ON
                Log.d(TAG, "Bluetooth state changed: enabled=$enabled")
                _isBluetoothEnabled.value = enabled
                updateOverallState()
            }
        }
    }

    init {
        // Initial values
        _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled ?: false
        updateNetworkStatus()

        // Register Network Callback
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
            try {
                connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to register fallback network callback: ${ex.message}")
            }
        }

        // Register Bluetooth Receiver
        try {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            context.registerReceiver(bluetoothReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register bluetooth receiver: ${e.message}")
        }
    }

    fun release() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback: ${e.message}")
        }
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister bluetooth receiver: ${e.message}")
        }
    }

    fun setHasDiscoveredDevices(hasDevices: Boolean) {
        _hasDiscoveredDevices.value = hasDevices
        updateOverallState()
    }

    fun setPeerStatus(status: PeerConnectionStatus) {
        _peerStatus.value = status
        updateOverallState()
    }

    fun setManualIpAddress(ip: String) {
        _currentIp.value = ip
        val available = ip != "127.0.0.1" && !ip.startsWith("127.0.0") && ip.isNotBlank()
        _isNetworkAvailable.value = available
        updateOverallState()
    }

    @Synchronized
    fun updateNetworkStatus() {
        val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
        _currentIp.value = ip
        val available = ip != "127.0.0.1" && !ip.startsWith("127.0.0") && ip.isNotBlank()
        _isNetworkAvailable.value = available
        Log.d(TAG, "updateNetworkStatus: IP=$ip available=$available")
        updateOverallState()
    }

    @Synchronized
    private fun updateOverallState() {
        val isBtEnabled = _isBluetoothEnabled.value
        val isNetAvailable = _isNetworkAvailable.value
        val hasDevices = _hasDiscoveredDevices.value
        val pStatus = _peerStatus.value

        val oldState = _connectionState.value
        val newState = when {
            pStatus == PeerConnectionStatus.READY -> FlipConnectionState.READY
            pStatus == PeerConnectionStatus.CONNECTED || pStatus == PeerConnectionStatus.CONNECTING -> FlipConnectionState.CONNECTED
            !isBtEnabled || !isNetAvailable -> FlipConnectionState.DISCONNECTED
            hasDevices -> FlipConnectionState.DEVICE_FOUND
            else -> FlipConnectionState.NETWORK_AVAILABLE
        }

        if (oldState != newState) {
            _connectionState.value = newState
            Log.d(TAG, "Connection State transitioned: $oldState -> $newState (BT=$isBtEnabled, Net=$isNetAvailable, Devices=$hasDevices, Peer=$pStatus)")
        }
    }

    companion object {
        private const val TAG = "ConnectionStateManager"

        @Volatile
        private var INSTANCE: ConnectionStateManager? = null

        fun getInstance(context: Context): ConnectionStateManager {
            return INSTANCE ?: synchronized(this) {
                val instance = ConnectionStateManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
