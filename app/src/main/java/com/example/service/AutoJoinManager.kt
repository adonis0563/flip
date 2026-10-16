package com.example.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.model.Device
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

sealed class AutoJoinState {
    object Idle : AutoJoinState()
    object ScanningBle : AutoJoinState()
    data class PeerFound(val peerId: String, val peerName: String) : AutoJoinState()
    data class NeedsFirstTime(val peerId: String, val peerName: String) : AutoJoinState()
    data class Joining(
        val peerId: String,
        val peerName: String,
        val message: String = "Approve the connection when Android asks"
    ) : AutoJoinState()
    data class Handshake(val peerId: String, val peerName: String) : AutoJoinState()
    data class Connected(val peer: Device) : AutoJoinState()
    data class Disconnected(val reason: String? = null) : AutoJoinState()
    data class Failed(
        val reason: String,
        val needsRePair: Boolean = false,
        val peerId: String? = null,
        val peerName: String? = null,
        val isSsidVisible: Boolean = false
    ) : AutoJoinState()
}

/**
 * State machine managing automatic background Wi-Fi hotspot connection.
 * Owned by the Foreground Service, exposed as StateFlow for UI observation.
 *
 * Sequence:
 * Idle -> ScanningBle -> PeerFound(id) -> NeedsFirstTime | Joining -> Handshake -> Connected -> Disconnected | Failed
 */
class AutoJoinManager(private val context: Context) {

    private val _state = MutableStateFlow<AutoJoinState>(AutoJoinState.Idle)
    val state: StateFlow<AutoJoinState> = _state.asStateFlow()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val failureCounts = ConcurrentHashMap<String, Int>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val deviceAdapter = moshi.adapter(Device::class.java)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun onBleScanStarted() {
        if (_state.value is AutoJoinState.Idle || _state.value is AutoJoinState.Disconnected) {
            _state.value = AutoJoinState.ScanningBle
        }
    }

    /**
     * Called when BLE discovers a sender device.
     * Records discovery state without auto-joining. The join is triggered on foreground tap.
     */
    fun onBlePeerDiscovered(bleDevice: Device, onReadyToConnect: (Device) -> Unit) {
        val peerId = bleDevice.id
        val peerName = bleDevice.name

        // Check if we already have this peer paired in encrypted store
        val pairedPeer = HotspotCredentialStore.getPairedPeer(context, peerId)

        if (pairedPeer == null || pairedPeer.needsRePair) {
            if (_state.value is AutoJoinState.ScanningBle || _state.value is AutoJoinState.Idle) {
                _state.value = AutoJoinState.NeedsFirstTime(peerId, peerName)
            }
        }
        // Note: Hotspot connection begins from a foreground tap on the sender card.
    }

    /**
     * Executes background Wi-Fi connection via WifiNetworkSpecifier.
     */
    fun startAutoJoin(
        pairedPeer: PairedPeer,
        port: Int = 8080,
        onSuccess: (Device) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startLegacyWifiJoin(pairedPeer, port, onSuccess)
            return
        }

        cleanupNetworkRequest()

        _state.value = AutoJoinState.Joining(
            peerId = pairedPeer.peerDeviceId,
            peerName = pairedPeer.displayName,
            message = "Approve the connection when Android asks"
        )
        Log.i(TAG, "[AUTO-JOIN] Requesting network specifier for peer: ${pairedPeer.displayName} (${pairedPeer.shortCode})")

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(pairedPeer.ssid)

        when (pairedPeer.security.uppercase()) {
            "WPA" -> specifierBuilder.setWpa2Passphrase(pairedPeer.password)
            "WPA3", "SAE", "WPA3-SAE" -> {
                specifierBuilder.setWpa3Passphrase(pairedPeer.password)
            }
            "OPEN", "NOPASS", "NONE" -> { /* Open network */ }
            else -> specifierBuilder.setWpa2Passphrase(pairedPeer.password)
        }

        if (pairedPeer.isHidden) {
            specifierBuilder.setIsHiddenSsid(true)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        var hasHandshakeStarted = false

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.i(TAG, "[AUTO-JOIN] Wi-Fi network available")
                boundNetwork = network
                try {
                    connectivityManager.bindProcessToNetwork(network)
                } catch (e: Exception) {
                    Log.w(TAG, "[AUTO-JOIN] Failed to bind process to network: ${e.message}")
                }

                if (!hasHandshakeStarted) {
                    hasHandshakeStarted = true
                    _state.value = AutoJoinState.Handshake(pairedPeer.peerDeviceId, pairedPeer.displayName)
                    performHandshake(network, pairedPeer, port, onSuccess)
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.w(TAG, "[AUTO-JOIN] Network request unavailable or user declined approval")
                handleJoinFailure(pairedPeer)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w(TAG, "[AUTO-JOIN] Wi-Fi network lost")
                _state.value = AutoJoinState.Disconnected("Connection lost, hotspot may have turned off")
            }
        }

        activeNetworkCallback = callback

        try {
            // Request network with 15s timeout
            connectivityManager.requestNetwork(request, callback, 15000)
        } catch (e: Exception) {
            Log.e(TAG, "[AUTO-JOIN] Failed to requestNetwork: ${e.message}", e)
            _state.value = AutoJoinState.Failed(
                reason = "Failed to initiate Wi-Fi connection: ${e.message}",
                peerId = pairedPeer.peerDeviceId,
                peerName = pairedPeer.displayName
            )
        }
    }

    private fun performHandshake(
        network: Network,
        pairedPeer: PairedPeer,
        port: Int,
        onSuccess: (Device) -> Unit
    ) {
        scope.launch {
            try {
                // Determine gateway address from LinkProperties
                val linkProperties: LinkProperties? = connectivityManager.getLinkProperties(network)
                val gatewayAddress = linkProperties?.routes
                    ?.firstOrNull { it.hasGateway() && it.gateway is Inet4Address }
                    ?.gateway?.hostAddress ?: "192.168.43.1"

                val url = "http://$gatewayAddress:$port/connect"

                // Create local Device profile payload
                val prefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
                val savedName = prefs.getString("device_name", null)
                val devName = if (!savedName.isNullOrBlank()) savedName else (Build.MODEL ?: "Android Device")
                val localDev = Device(
                    id = DeviceIdentity.getStableDeviceId(context),
                    name = devName,
                    ip = "0.0.0.0",
                    port = 8080,
                    sid = DeviceIdentity.currentSessionId
                )

                val body = deviceAdapter.toJson(localDev).toRequestBody("application/json".toMediaTypeOrNull())
                val httpRequest = Request.Builder().url(url).post(body).build()

                // Execute call bound to network socket factory
                val clientWithNetwork = httpClient.newBuilder()
                    .socketFactory(network.socketFactory)
                    .build()

                val response = clientWithNetwork.newCall(httpRequest).execute()
                response.use {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: ""
                        val remoteDevice = deviceAdapter.fromJson(responseBody)
                        if (remoteDevice != null) {
                            // Verify peer identity (by device ID / short code only - name stays display-only)
                            val cleanRemoteId = remoteDevice.id.removePrefix("flip-")
                            val isIdMatched = remoteDevice.id.equals(pairedPeer.peerDeviceId, ignoreCase = true) ||
                                cleanRemoteId.equals(pairedPeer.peerDeviceId, ignoreCase = true) ||
                                pairedPeer.peerDeviceId.startsWith(cleanRemoteId, ignoreCase = true) ||
                                cleanRemoteId.startsWith(pairedPeer.shortCode, ignoreCase = true)

                            if (isIdMatched) {
                                Log.i(TAG, "[AUTO-JOIN] Handshake successful, peer verified: ${remoteDevice.name} (${remoteDevice.id})")
                                failureCounts.remove(pairedPeer.peerDeviceId)
                                HotspotCredentialStore.updateLastConnected(context, pairedPeer.peerDeviceId)
                                val finalDevice = remoteDevice.copy(ip = gatewayAddress, port = port)
                                _state.value = AutoJoinState.Connected(finalDevice)
                                mainHandler.post { onSuccess(finalDevice) }
                            } else {
                                Log.e(TAG, "[AUTO-JOIN] Handshake identity mismatch! Expected ${pairedPeer.peerDeviceId}, got ${remoteDevice.id}")
                                cleanupNetworkRequest()
                                _state.value = AutoJoinState.Failed(
                                    reason = "This isn't the device you paired with.",
                                    peerId = pairedPeer.peerDeviceId,
                                    peerName = pairedPeer.displayName
                                )
                            }
                        } else {
                            handleJoinFailure(pairedPeer, "Invalid handshake response from peer")
                        }
                    } else {
                        handleJoinFailure(pairedPeer, "Peer rejected handshake (HTTP ${response.code})")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[AUTO-JOIN] Handshake error: ${e.message}", e)
                handleJoinFailure(pairedPeer, "Connection timeout during handshake")
            }
        }
    }

    private fun handleJoinFailure(pairedPeer: PairedPeer, customReason: String? = null) {
        cleanupNetworkRequest()
        val count = (failureCounts[pairedPeer.peerDeviceId] ?: 0) + 1
        failureCounts[pairedPeer.peerDeviceId] = count

        val isSsidSeen = checkIsSsidVisible(pairedPeer.ssid)

        if (count >= 2) {
            HotspotCredentialStore.markNeedsRePair(context, pairedPeer.peerDeviceId, true)
            val msg = if (isSsidSeen) {
                "Unable to connect to ${pairedPeer.displayName}. Hotspot password may have changed."
            } else {
                "Is ${pairedPeer.displayName}'s hotspot on?"
            }
            _state.value = AutoJoinState.Failed(
                reason = msg,
                needsRePair = true,
                peerId = pairedPeer.peerDeviceId,
                peerName = pairedPeer.displayName,
                isSsidVisible = isSsidSeen
            )
        } else {
            val msg = customReason ?: if (isSsidSeen) {
                "Connection failed. Please ensure you approved Android's network dialog."
            } else {
                "Is ${pairedPeer.displayName}'s hotspot on?"
            }
            _state.value = AutoJoinState.Failed(
                reason = msg,
                needsRePair = false,
                peerId = pairedPeer.peerDeviceId,
                peerName = pairedPeer.displayName,
                isSsidVisible = isSsidSeen
            )
        }
    }

    private fun checkIsSsidVisible(targetSsid: String): Boolean {
        return try {
            val results = wifiManager?.scanResults ?: return false
            results.any { it.SSID == targetSsid || it.SSID == "\"$targetSsid\"" }
        } catch (_: Exception) {
            false
        }
    }

    fun cleanupNetworkRequest() {
        activeNetworkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        activeNetworkCallback = null
        boundNetwork = null
        try {
            connectivityManager.bindProcessToNetwork(null)
        } catch (_: Exception) {}
    }

    fun reset() {
        cleanupNetworkRequest()
        _state.value = AutoJoinState.Idle
    }

    @Suppress("DEPRECATION")
    private fun startLegacyWifiJoin(
        pairedPeer: PairedPeer,
        port: Int,
        onSuccess: (Device) -> Unit
    ) {
        val wm = wifiManager
        if (wm == null || !wm.isWifiEnabled) {
            _state.value = AutoJoinState.Failed(
                reason = "Please turn on Wi-Fi to join ${pairedPeer.displayName}'s hotspot.",
                peerId = pairedPeer.peerDeviceId,
                peerName = pairedPeer.displayName
            )
            return
        }

        cleanupNetworkRequest()

        _state.value = AutoJoinState.Joining(
            peerId = pairedPeer.peerDeviceId,
            peerName = pairedPeer.displayName,
            message = "Connecting to ${pairedPeer.displayName}'s hotspot..."
        )

        try {
            val conf = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"${pairedPeer.ssid}\""
                if (pairedPeer.security.equals("OPEN", ignoreCase = true) || pairedPeer.security.equals("NOPASS", ignoreCase = true) || pairedPeer.security.equals("NONE", ignoreCase = true)) {
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                } else {
                    // WPA2 fallback
                    preSharedKey = "\"${pairedPeer.password}\""
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                hiddenSSID = pairedPeer.isHidden
            }

            val netId = wm.addNetwork(conf)
            if (netId != -1) {
                wm.disconnect()
                wm.enableNetwork(netId, true)
                wm.reconnect()
                Log.i(TAG, "[AUTO-JOIN] Legacy Wi-Fi configuration applied (netId=$netId)")

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()

                var hasHandshakeStarted = false

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.i(TAG, "[AUTO-JOIN] Legacy Wi-Fi network available")
                        boundNetwork = network
                        try {
                            connectivityManager.bindProcessToNetwork(network)
                        } catch (_: Exception) {}

                        if (!hasHandshakeStarted) {
                            hasHandshakeStarted = true
                            _state.value = AutoJoinState.Handshake(pairedPeer.peerDeviceId, pairedPeer.displayName)
                            performHandshake(network, pairedPeer, port, onSuccess)
                        }
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.w(TAG, "[AUTO-JOIN] Legacy Wi-Fi network lost")
                        _state.value = AutoJoinState.Disconnected("Connection lost, hotspot may have turned off")
                    }
                }

                activeNetworkCallback = callback
                connectivityManager.requestNetwork(request, callback, 15000)
            } else {
                _state.value = AutoJoinState.Failed(
                    reason = "Unable to configure Wi-Fi on Android 9.",
                    peerId = pairedPeer.peerDeviceId,
                    peerName = pairedPeer.displayName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "[AUTO-JOIN] Legacy Wi-Fi configuration failed: ${e.message}", e)
            _state.value = AutoJoinState.Failed(
                reason = "Legacy Wi-Fi join failed: ${e.message}",
                peerId = pairedPeer.peerDeviceId,
                peerName = pairedPeer.displayName
            )
        }
    }

    companion object {
        private const val TAG = "AutoJoinManager"
    }
}
