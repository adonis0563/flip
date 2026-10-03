@file:Suppress("DEPRECATION")
package com.flip.app.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.flip.app.utils.FlipConnectionListener
import com.flip.app.utils.PermissionsHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("MissingPermission")
class WifiP2pRepository(private val context: Context) : FlipConnectionListener, WifiP2pManager.GroupInfoListener {

    companion object {
        private const val TAG = "Flip_WiFiP2P"
        private const val MAX_RETRY_COUNT = 3
    }

    private val wifiP2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())

    // --- Reactive State ---
    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Idle")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _isGroupFormed = MutableStateFlow(false)
    val isGroupFormed: StateFlow<Boolean> = _isGroupFormed.asStateFlow()

    @Volatile
    var isHost: Boolean = false
        private set

    @Volatile
    var groupOwnerAddress: String? = null
        private set

    private val _groupSsid = MutableStateFlow<String?>(null)
    val groupSsid: StateFlow<String?> = _groupSsid.asStateFlow()

    private val _groupPassphrase = MutableStateFlow<String?>(null)
    val groupPassphrase: StateFlow<String?> = _groupPassphrase.asStateFlow()

    private val cachedSsid = AtomicReference<String?>(null)
    private val cachedPassphrase = AtomicReference<String?>(null)

    override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
        onGroupInfoChanged(group)
    }

    override fun onWifiDirectEnabled(enabled: Boolean) {
        Log.d(TAG, "[WIFI] Wi-Fi Direct enabled: $enabled")
        if (!enabled) {
            _connectionStatus.value = "Wi-Fi P2P Disabled"
        }
    }

    override fun onPeersDiscovered(peers: List<WifiP2pDevice>) {
        Log.d(TAG, "[WIFI] Peers discovered: ${peers.size}")
        _peers.value = peers
    }

    override fun onConnectionSuccess(isGroupOwner: Boolean, groupOwnerAddress: String?) {
        Log.d(TAG, "[WIFI] Connection success: isGO=$isGroupOwner, GO=$groupOwnerAddress")
        isHost = isGroupOwner
        this.groupOwnerAddress = groupOwnerAddress
        _isGroupFormed.value = true
        if (isGroupOwner) {
            _connectionStatus.value = "Host (Connected)"
        } else {
            _connectionStatus.value = "Client (Connected) - GO: $groupOwnerAddress"
        }
    }

    override fun onConnectionFailed(reason: String) {
        Log.d(TAG, "[WIFI] Connection failed/lost: $reason")
        isHost = false
        groupOwnerAddress = null
        _isGroupFormed.value = false
        _peers.value = emptyList()
        _groupSsid.value = null
        _groupPassphrase.value = null
        cachedSsid.set(null)
        cachedPassphrase.set(null)
        _connectionStatus.value = "Disconnected"
    }

    override fun onGroupInfoChanged(group: WifiP2pGroup?) {
        if (group != null) {
            _groupSsid.value = group.networkName
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
            _groupPassphrase.value = pass
            cachedSsid.set(group.networkName)
            cachedPassphrase.set(pass)
            Log.d("Flip_Repo", "Group SSID: ${group.networkName}, Pass: $pass")
            if (isHost) {
                _connectionStatus.value = "Host (Connected) - SSID: ${group.networkName}"
            }
        }
    }

    fun initialize() {
        Log.d(TAG, "[WIFI] Initializing WifiP2pRepository")
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
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = WifiDirectBroadcastReceiver(mgr, ch, this)

        ContextCompat.registerReceiver(
            context,
            receiver!!,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true
        Log.d(TAG, "[WIFI] WifiDirectBroadcastReceiver registered")
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
                    onGroupInfoChanged(group)
                    if (group != null) {
                        val ssid = group.networkName
                        Log.d(TAG, "[WIFI] GroupInfo: ssid=$ssid, hasPass=${!_groupPassphrase.value.isNullOrEmpty()}")

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

    fun createGroup(retryCount: Int = 0) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            Log.e(TAG, "[WIFI] Cannot create group: manager or channel is null")
            _connectionStatus.value = "Error: Wi-Fi P2P not initialized"
            return
        }

        if (!PermissionsHelper.hasAllPermissions(context)) {
            Log.e(TAG, "[WIFI] Cannot create group: Missing permissions")
            _connectionStatus.value = "Error: Missing permissions"
            return
        }

        Log.d(TAG, "[WIFI] createGroup requested (attempt $retryCount)")
        // Step 1: Clean up any existing group first (prevents "ghost group" errors)
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "[WIFI] Old group removed. Creating new one...")
                proceedWithCreation(retryCount)
            }
            override fun onFailure(reason: Int) {
                // Even if removal fails, proceed with creation
                Log.d(TAG, "[WIFI] No existing group to remove (reason=$reason). Proceeding...")
                proceedWithCreation(retryCount)
            }
        })
    }

    private fun proceedWithCreation(retryCount: Int) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        _connectionStatus.value = "Creating Wi-Fi Direct Group..."
        Log.d(TAG, "[WIFI] Calling createGroup()")

        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "✅ [WIFI] Group creation initiated.")
                _connectionStatus.value = "Group Created. Waiting for receiver..."
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "❌ [WIFI] Group creation failed: $reason")

                // RESILIENCE: Retry with exponential backoff if BUSY
                if (reason == WifiP2pManager.BUSY && retryCount < MAX_RETRY_COUNT) {
                    val delay = (100 * Math.pow(2.0, retryCount.toDouble())).toLong()
                    Log.d(TAG, "[WIFI] Retrying in ${delay}ms... (attempt ${retryCount + 1})")
                    handler.postDelayed({
                        createGroup(retryCount + 1)
                    }, delay)
                } else {
                    _connectionStatus.value = "Failed to create group: $reason"
                }
            }
        })
    }

    fun joinGroupSilently(ssid: String, passphrase: String) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            Log.e(TAG, "[WIFI] Cannot join group: manager or channel is null")
            _connectionStatus.value = "Error: Wi-Fi P2P not initialized"
            return
        }

        Log.d(TAG, "[WIFI] joinGroupSilently requested for SSID=$ssid")
        // Step 1: Remove any existing group to prevent conflicts
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "[WIFI] Old group removed before join.")
                proceedWithJoin(ssid, passphrase)
            }
            override fun onFailure(reason: Int) {
                Log.d(TAG, "[WIFI] No old group to remove before join (reason=$reason). Proceeding...")
                proceedWithJoin(ssid, passphrase)
            }
        })
    }

    private fun proceedWithJoin(ssid: String, passphrase: String) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        _connectionStatus.value = "Joining Group..."
        Log.d(TAG, "[WIFI] Joining group silently: SSID=$ssid")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Build config with 5GHz preference for max throughput
            val config = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(passphrase)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .build()

            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "✅ [WIFI] Silent join initiated for SSID: $ssid")
                    _connectionStatus.value = "Joining Group..."
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ [WIFI] Silent join failed: $reason")
                    _connectionStatus.value = "Failed to join: $reason"
                }
            })
        } else {
            val config = WifiP2pConfig().apply {
                wps.setup = WpsInfo.PBC
            }
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "✅ [WIFI] Connect initiated for SSID: $ssid")
                    _connectionStatus.value = "Joining Group..."
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ [WIFI] Connect failed: $reason")
                    _connectionStatus.value = "Failed to join: $reason"
                }
            })
        }
    }

    fun getGroupCredentials(): Pair<String?, String?> {
        // This will be populated by the BroadcastReceiver when group info changes
        return Pair(_groupSsid.value, _groupPassphrase.value)
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
        _isGroupFormed.value = false
        _peers.value = emptyList()
        _groupSsid.value = null
        _groupPassphrase.value = null
        cachedSsid.set(null)
        cachedPassphrase.set(null)
        _connectionStatus.value = "Idle"
    }

    fun cleanup() {
        Log.d(TAG, "[WIFI] Cleaning up WifiP2pRepository")
        handler.removeCallbacksAndMessages(null)
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
