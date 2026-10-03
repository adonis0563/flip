@file:Suppress("DEPRECATION")
package com.flip.app.data.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.flip.app.utils.PermissionsHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("MissingPermission")
class WifiDirectRepository(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectRepo"
    }

    private val wifiP2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    private val _connectionStatus = MutableStateFlow("Idle")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    @Volatile
    var isHost: Boolean = false
        private set

    @Volatile
    var groupOwnerAddress: String? = null
        private set

    private val cachedSsid = AtomicReference<String?>(null)
    private val cachedPassphrase = AtomicReference<String?>(null)

    fun initialize() {
        Log.d(TAG, "[WIFI] Initializing WifiDirectRepository")
        if (wifiP2pManager == null) {
            Log.e(TAG, "[WIFI] WifiP2pManager not supported on this device")
            _connectionStatus.value = "Error: Wi-Fi P2P not supported"
            return
        }

        channel = wifiP2pManager.initialize(context, context.mainLooper) {
            Log.w(TAG, "[WIFI] Channel disconnected, re-initializing")
            _connectionStatus.value = "Channel Disconnected"
        }

        registerReceiver()
    }

    private fun registerReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Log.d(TAG, "[WIFI] Wi-Fi P2P state changed: enabled=$isEnabled")
                        if (!isEnabled) {
                            _connectionStatus.value = "Wi-Fi P2P Disabled"
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }

                        val isConnected = networkInfo?.isConnected == true
                        Log.d(TAG, "[WIFI] Connection changed: isConnected=$isConnected")

                        if (isConnected) {
                            requestConnectionAndGroupInfo()
                        } else {
                            isHost = false
                            groupOwnerAddress = null
                            cachedSsid.set(null)
                            cachedPassphrase.set(null)
                            _connectionStatus.value = "Disconnected"
                        }
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver!!,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true
        Log.d(TAG, "[WIFI] BroadcastReceiver registered")
    }

    private fun requestConnectionAndGroupInfo() {
        val ch = channel ?: return
        val mgr = wifiP2pManager ?: return

        mgr.requestConnectionInfo(ch) { info ->
            if (info == null) return@requestConnectionInfo
            Log.d(TAG, "[WIFI] ConnectionInfo: groupFormed=${info.groupFormed}, isGo=${info.isGroupOwner}, goAddr=${info.groupOwnerAddress?.hostAddress}")

            if (info.groupFormed) {
                isHost = info.isGroupOwner
                groupOwnerAddress = info.groupOwnerAddress?.hostAddress

                mgr.requestGroupInfo(ch) { group ->
                    if (group != null) {
                        val ssid = group.networkName
                        val pass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            group.passphrase
                        } else {
                            try {
                                val method = group.javaClass.getMethod("getPassphrase")
                                method.invoke(group) as? String
                            } catch (e: Exception) {
                                null
                            }
                        }
                        cachedSsid.set(ssid)
                        cachedPassphrase.set(pass)
                        Log.d(TAG, "[WIFI] GroupInfo obtained: ssid=$ssid, hasPass=${!pass.isNullOrEmpty()}")

                        if (isHost) {
                            _connectionStatus.value = "Host (Connected) - SSID: $ssid"
                        } else {
                            _connectionStatus.value = "Client (Connected) - GO: $groupOwnerAddress"
                        }
                    } else {
                        if (isHost) {
                            _connectionStatus.value = "Host (Connected)"
                        } else {
                            _connectionStatus.value = "Client (Connected)"
                        }
                    }
                }
            }
        }
    }

    fun createGroup(onSuccess: (() -> Unit)? = null, onFailure: ((Int) -> Unit)? = null) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            Log.e(TAG, "[WIFI] Cannot create group: manager or channel is null")
            _connectionStatus.value = "Error: Wi-Fi P2P not initialized"
            onFailure?.invoke(-1)
            return
        }

        if (!PermissionsHelper.hasAllPermissions(context)) {
            Log.e(TAG, "[WIFI] Cannot create group: Missing permissions")
            _connectionStatus.value = "Error: Missing permissions"
            onFailure?.invoke(-2)
            return
        }

        _connectionStatus.value = "Creating Group (Host)..."
        Log.d(TAG, "[WIFI] Creating Wi-Fi Direct group")

        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "[WIFI] createGroup onSuccess callback received")
                onSuccess?.invoke()
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "[WIFI] createGroup onFailure: reason=$reason")
                _connectionStatus.value = "Group Creation Failed: $reason"
                onFailure?.invoke(reason)
            }
        })
    }

    fun getGroupCredentials(): Pair<String?, String?> {
        val ssid = cachedSsid.get()
        val pass = cachedPassphrase.get()
        Log.d(TAG, "[WIFI] getGroupCredentials: ssid=$ssid, passAvailable=${!pass.isNullOrEmpty()}")
        return Pair(ssid, pass)
    }

    fun connectToDevice(deviceAddress: String, onSuccess: (() -> Unit)? = null, onFailure: ((Int) -> Unit)? = null) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onFailure?.invoke(-1)
            return
        }

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = android.net.wifi.WpsInfo.PBC
        }

        _connectionStatus.value = "Connecting to $deviceAddress..."
        Log.d(TAG, "[WIFI] Connecting to $deviceAddress")

        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "[WIFI] connect onSuccess")
                onSuccess?.invoke()
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "[WIFI] connect onFailure: reason=$reason")
                _connectionStatus.value = "Connect Failed: $reason"
                onFailure?.invoke(reason)
            }
        })
    }

    fun disconnect() {
        Log.d(TAG, "[WIFI] Disconnecting Wi-Fi Direct")
        val mgr = wifiP2pManager
        val ch = channel

        if (mgr != null && ch != null) {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "[WIFI] removeGroup onSuccess")
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "[WIFI] removeGroup onFailure: $reason")
                }
            })
        }

        isHost = false
        groupOwnerAddress = null
        cachedSsid.set(null)
        cachedPassphrase.set(null)
        _connectionStatus.value = "Idle"
    }

    fun cleanup() {
        Log.d(TAG, "[WIFI] Cleaning up WifiDirectRepository")
        disconnect()
        if (isReceiverRegistered && receiver != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "[WIFI] Error unregistering receiver: ${e.message}")
            }
            isReceiverRegistered = false
        }
    }
}
