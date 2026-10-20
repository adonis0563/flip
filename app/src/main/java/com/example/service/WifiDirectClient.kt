package com.example.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Which connection path succeeded.
 */
enum class ClientJoinPath {
    WIFI_DIRECT_P2P,
    WIFI_SPECIFIER
}

/**
 * Typed failure reasons naming the exact failing step.
 */
sealed interface ClientFailureReason {
    data class ParseFailed(val field: String) : ClientFailureReason
    data class Expired(val expiryEpochSeconds: Long, val currentEpochSeconds: Long) : ClientFailureReason
    data class PermissionMissing(val permissions: List<String>) : ClientFailureReason
    data class ConfigBuildFailed(val message: String) : ClientFailureReason
    data class ConnectRejected(val code: Int, val name: String) : ClientFailureReason
    data object Timeout : ClientFailureReason
    data object Unsupported : ClientFailureReason
    data class SpecifierFailed(val message: String) : ClientFailureReason
    data class Error(val code: Int, val message: String = "") : ClientFailureReason
}

/**
 * Typed result from attempting to join a Wi-Fi Direct host.
 */
sealed interface ClientResult {
    data class Joined(
        val ownerAddress: String,
        val path: ClientJoinPath,
        val p2pAttemptFailed: ClientFailureReason? = null
    ) : ClientResult

    data class Failed(
        val reason: ClientFailureReason,
        val p2pReason: ClientFailureReason? = null,
        val specifierReason: ClientFailureReason? = null
    ) : ClientResult
}

/**
 * Wi-Fi Direct Client (Receiver) helper for Android 10+ (API 29+).
 *
 * Joins a Wi-Fi Direct host using a flip://wfd payload.
 * Trims whitespace/newlines before parsing, allows 2 minutes clock skew tolerance,
 * cleans existing groups on the receiver before connecting, and falls back to
 * WifiNetworkSpecifier if P2P connect is rejected or times out.
 * Never logs sensitive credentials.
 */
class WifiDirectClient(
    private val context: Context
) {
    companion object {
        private const val TAG = "FlipDirect"
        private const val P2P_CONNECT_TIMEOUT_MS = 20_000L
        private const val SPECIFIER_CONNECT_TIMEOUT_MS = 20_000L

        /**
         * Resolves the human-readable WifiP2pManager error name.
         */
        fun getP2pErrorName(code: Int): String = when (code) {
            WifiP2pManager.ERROR -> "0 ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "1 P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "2 BUSY"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "3 NO_SERVICE_REQUESTS"
            else -> "$code UNKNOWN"
        }

        /**
         * Returns list of missing runtime permissions needed for Wi-Fi Direct.
         */
        fun getMissingPermissions(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
            if (sdkInt < Build.VERSION_CODES.Q) {
                return emptyList()
            }
            val missing = mutableListOf<String>()

            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    missing.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
            } else {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_WIFI_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(Manifest.permission.ACCESS_WIFI_STATE)
            }

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CHANGE_WIFI_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(Manifest.permission.CHANGE_WIFI_STATE)
            }

            return missing
        }

        fun hasRequiredPermissions(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
            return getMissingPermissions(context, sdkInt).isEmpty()
        }

        /**
         * Validates whether a MAC address string is a valid, non-placeholder physical address.
         * Placeholders like 02:00:00:00:00:00 or empty strings must return false and never throw.
         */
        fun isValidMacAddress(mac: String?): Boolean {
            if (mac.isNullOrBlank()) return false
            val trimmed = mac.trim()
            if (trimmed.equals("02:00:00:00:00:00", ignoreCase = true) ||
                trimmed.equals("00:00:00:00:00:00", ignoreCase = true) ||
                trimmed.equals("FF:FF:FF:FF:FF:FF", ignoreCase = true) ||
                trimmed.equals("null", ignoreCase = true)
            ) {
                return false
            }
            val macRegex = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
            return macRegex.matches(trimmed)
        }
    }

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var connectionReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isConnected = false

    /**
     * Joins a Wi-Fi Direct host given a raw flip://wfd URI string.
     * Trims whitespace and line breaks before parsing.
     */
    suspend fun join(
        rawPayload: String,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): ClientResult = withContext(Dispatchers.Default) {
        val sanitized = WifiDirectPayloadParser.sanitize(rawPayload)
        val parseResult = WifiDirectPayloadParser.parseDetailed(sanitized)

        when (parseResult) {
            is ParseDetailedResult.Failed -> {
                Log.w(TAG, "join: ParseFailed on field: ${parseResult.field}")
                return@withContext ClientResult.Failed(ClientFailureReason.ParseFailed(parseResult.field))
            }
            is ParseDetailedResult.Expired -> {
                Log.w(TAG, "join: Expired (expiry=${parseResult.expiryEpochSeconds}, current=${parseResult.currentEpochSeconds})")
                return@withContext ClientResult.Failed(
                    ClientFailureReason.Expired(
                        expiryEpochSeconds = parseResult.expiryEpochSeconds,
                        currentEpochSeconds = parseResult.currentEpochSeconds
                    )
                )
            }
            is ParseDetailedResult.Success -> {
                join(parseResult.payload, sdkInt)
            }
        }
    }

    /**
     * Joins a Wi-Fi Direct host given a parsed [WifiDirectPayload].
     */
    suspend fun join(
        payload: WifiDirectPayload,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): ClientResult = withContext(Dispatchers.Default) {
        Log.d(TAG, "join: requested for group=${payload.groupName}")

        if (sdkInt < Build.VERSION_CODES.Q) {
            Log.w(TAG, "join: unsupported OS version ($sdkInt < 29)")
            return@withContext ClientResult.Failed(ClientFailureReason.Unsupported)
        }

        val currentEpoch = System.currentTimeMillis() / 1000L
        if (payload.isExpired(currentEpoch)) {
            Log.w(TAG, "join: payload is expired (expiry=${payload.expiryEpochSeconds}, current=$currentEpoch)")
            return@withContext ClientResult.Failed(
                ClientFailureReason.Expired(
                    expiryEpochSeconds = payload.expiryEpochSeconds,
                    currentEpochSeconds = currentEpoch
                )
            )
        }

        val missing = getMissingPermissions(context, sdkInt)
        if (missing.isNotEmpty()) {
            Log.w(TAG, "join: PermissionMissing -> $missing")
            return@withContext ClientResult.Failed(ClientFailureReason.PermissionMissing(missing))
        }

        // Clean up prior state
        leave()

        // Path 1: Attempt Wi-Fi Direct P2P connect
        Log.d(TAG, "join: attempting Path 1 (Wi-Fi Direct P2P connect)")
        val p2pResult = attemptP2pConnect(payload)
        if (p2pResult is ClientResult.Joined) {
            Log.d(TAG, "join: Path 1 (Wi-Fi Direct P2P) succeeded with owner=${p2pResult.ownerAddress}")
            isConnected = true
            return@withContext p2pResult
        }

        val p2pFailureReason = (p2pResult as? ClientResult.Failed)?.reason
            ?: ClientFailureReason.Error(WifiP2pManager.ERROR, "P2P attempt failed")

        Log.w(TAG, "join: Path 1 failed ($p2pFailureReason), falling back to Path 2 (WifiNetworkSpecifier)")

        // Path 2: Fall back to WifiNetworkSpecifier
        val specifierResult = attemptSpecifierConnect(payload, p2pFailureReason)
        if (specifierResult is ClientResult.Joined) {
            Log.d(TAG, "join: Path 2 (WifiNetworkSpecifier) succeeded with owner=${specifierResult.ownerAddress}")
            isConnected = true
            return@withContext specifierResult
        }

        Log.w(TAG, "join: Path 2 also failed ($specifierResult)")
        return@withContext specifierResult
    }

    private suspend fun attemptP2pConnect(payload: WifiDirectPayload): ClientResult {
        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return ClientResult.Failed(ClientFailureReason.Unsupported)

        val channel = manager.initialize(context, context.mainLooper, null)
            ?: return ClientResult.Failed(ClientFailureReason.Error(WifiP2pManager.ERROR, "Channel initialize failed"))

        synchronized(this) {
            this.p2pManager = manager
            this.p2pChannel = channel
        }

        // Rule 7: If a group already exists on receiver, remove it first
        Log.d(TAG, "P2P: checking and removing any existing group on receiver")
        removeExistingGroup(manager, channel)

        // Rule 5: Build P2P config; skip setDeviceAddress if empty or placeholder MAC; never throw
        val config = try {
            buildP2pConfig(payload)
        } catch (e: Exception) {
            Log.w(TAG, "P2P: ConfigBuildFailed: ${e.message}")
            cleanupP2p()
            return ClientResult.Failed(ClientFailureReason.ConfigBuildFailed(e.message ?: "Failed to build WifiP2pConfig"))
        }

        val connectionSignal = Channel<WifiP2pInfo>(Channel.CONFLATED)

        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(recvContext: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                try {
                    manager.requestConnectionInfo(channel) { info ->
                        if (info != null && info.groupFormed && info.groupOwnerAddress != null) {
                            Log.d(TAG, "P2P connection established: groupOwnerAddress=${info.groupOwnerAddress.hostAddress}")
                            connectionSignal.trySend(info)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "P2P connection receiver error: ${e.message}")
                }
            }
        }

        synchronized(this) {
            this.connectionReceiver = receiver
        }

        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
            try {
                context.registerReceiver(receiver, filter)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register P2P connection receiver", e)
            }
        }

        // Call connect()
        val connectCallOutcome = suspendCancellableCoroutine<Pair<Boolean, Int>> { cont ->
            try {
                manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "P2P connect() initiated successfully")
                        if (cont.isActive) cont.resume(Pair(true, 0))
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "P2P connect() call failed with reason: ${getP2pErrorName(reason)}")
                        if (cont.isActive) cont.resume(Pair(false, reason))
                    }
                })
            } catch (e: SecurityException) {
                Log.w(TAG, "P2P connect SecurityException", e)
                if (cont.isActive) cont.resume(Pair(false, WifiP2pManager.ERROR))
            } catch (e: Exception) {
                Log.w(TAG, "P2P connect Exception", e)
                if (cont.isActive) cont.resume(Pair(false, WifiP2pManager.ERROR))
            }
        }

        if (!connectCallOutcome.first) {
            val code = connectCallOutcome.second
            cleanupP2p()
            return ClientResult.Failed(ClientFailureReason.ConnectRejected(code, getP2pErrorName(code)))
        }

        // Wait up to 20 seconds for connection changed broadcast
        val info = withTimeoutOrNull(P2P_CONNECT_TIMEOUT_MS) {
            connectionSignal.receive()
        }

        if (info == null) {
            Log.w(TAG, "P2P connection timed out after ${P2P_CONNECT_TIMEOUT_MS}ms")
            cleanupP2p()
            return ClientResult.Failed(ClientFailureReason.Timeout)
        }

        val realOwnerIp = info.groupOwnerAddress?.hostAddress
        if (realOwnerIp.isNullOrBlank()) {
            cleanupP2p()
            return ClientResult.Failed(ClientFailureReason.Error(WifiP2pManager.ERROR, "groupOwnerAddress is null or empty"))
        }

        return ClientResult.Joined(
            ownerAddress = realOwnerIp,
            path = ClientJoinPath.WIFI_DIRECT_P2P
        )
    }

    private suspend fun removeExistingGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): Unit = suspendCancellableCoroutine { cont ->
        try {
            manager.requestGroupInfo(channel) { existingGroup ->
                if (existingGroup != null) {
                    Log.d(TAG, "removeExistingGroup: existing group detected on receiver, removing...")
                    manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG, "removeExistingGroup: existing group removed successfully")
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onFailure(reason: Int) {
                            Log.w(TAG, "removeExistingGroup: removeGroup reported reason=${getP2pErrorName(reason)}")
                            if (cont.isActive) cont.resume(Unit)
                        }
                    })
                } else {
                    Log.d(TAG, "removeExistingGroup: no existing group on receiver")
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "removeExistingGroup: SecurityException", e)
            if (cont.isActive) cont.resume(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "removeExistingGroup: exception", e)
            if (cont.isActive) cont.resume(Unit)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildP2pConfig(payload: WifiDirectPayload): WifiP2pConfig {
        val config = WifiP2pConfig.Builder()
            .setNetworkName(payload.groupName)
            .setPassphrase(payload.passphrase)
            .build()

        val mac = payload.ownerAddress.trim()
        if (isValidMacAddress(mac)) {
            try {
                config.deviceAddress = mac
                Log.d(TAG, "buildP2pConfig: set deviceAddress to valid MAC ($mac)")
            } catch (e: Exception) {
                Log.w(TAG, "buildP2pConfig: set deviceAddress threw exception, proceeding without it: ${e.message}")
            }
        } else {
            Log.d(TAG, "buildP2pConfig: ownerAddress is empty or placeholder ('$mac'), skipping setDeviceAddress")
        }

        return config
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun attemptSpecifierConnect(
        payload: WifiDirectPayload,
        p2pFailureReason: ClientFailureReason? = null
    ): ClientResult {
        Log.d(TAG, "attemptSpecifierConnect: started WifiNetworkSpecifier fallback")
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ClientResult.Failed(
                reason = ClientFailureReason.SpecifierFailed("ConnectivityManager is null"),
                p2pReason = p2pFailureReason,
                specifierReason = ClientFailureReason.Unsupported
            )

        val specifier = try {
            WifiNetworkSpecifier.Builder()
                .setSsid(payload.groupName)
                .setWpa2Passphrase(payload.passphrase)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "attemptSpecifierConnect: WifiNetworkSpecifier.Builder failed: ${e.message}")
            return ClientResult.Failed(
                reason = ClientFailureReason.SpecifierFailed("Specifier build failed: ${e.message}"),
                p2pReason = p2pFailureReason,
                specifierReason = ClientFailureReason.SpecifierFailed("Builder error: ${e.message}")
            )
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val networkSignal = Channel<Network>(Channel.CONFLATED)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "WifiNetworkSpecifier network available: $network")
                connectivityManager.bindProcessToNetwork(network)
                networkSignal.trySend(network)
            }

            override fun onUnavailable() {
                Log.w(TAG, "WifiNetworkSpecifier network unavailable")
                networkSignal.close()
            }
        }

        synchronized(this) {
            this.networkCallback = callback
        }

        try {
            connectivityManager.requestNetwork(request, callback)
            Log.d(TAG, "WifiNetworkSpecifier requestNetwork dispatched")
        } catch (e: SecurityException) {
            Log.w(TAG, "WifiNetworkSpecifier SecurityException", e)
            cleanupSpecifier()
            return ClientResult.Failed(
                reason = ClientFailureReason.PermissionMissing(listOf("CHANGE_NETWORK_STATE")),
                p2pReason = p2pFailureReason,
                specifierReason = ClientFailureReason.PermissionMissing(listOf("CHANGE_NETWORK_STATE"))
            )
        } catch (e: Exception) {
            Log.w(TAG, "WifiNetworkSpecifier error: ${e.message}", e)
            cleanupSpecifier()
            return ClientResult.Failed(
                reason = ClientFailureReason.SpecifierFailed("requestNetwork error: ${e.message}"),
                p2pReason = p2pFailureReason,
                specifierReason = ClientFailureReason.SpecifierFailed("${e.message}")
            )
        }

        val network = withTimeoutOrNull(SPECIFIER_CONNECT_TIMEOUT_MS) {
            try {
                networkSignal.receive()
            } catch (_: Exception) {
                null
            }
        }

        if (network == null) {
            Log.w(TAG, "WifiNetworkSpecifier timed out waiting for network (${SPECIFIER_CONNECT_TIMEOUT_MS}ms)")
            cleanupSpecifier()
            return ClientResult.Failed(
                reason = ClientFailureReason.Timeout,
                p2pReason = p2pFailureReason,
                specifierReason = ClientFailureReason.Timeout
            )
        }

        // Determine owner IP: in an autonomous group, the owner is the gateway IP
        val linkProps = connectivityManager.getLinkProperties(network)
        val gatewayAddress = linkProps?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
            ?: linkProps?.routes?.firstOrNull { it.gateway != null }?.gateway?.hostAddress
            ?: "192.168.49.1"

        Log.d(TAG, "WifiNetworkSpecifier connected successfully. Gateway owner IP: $gatewayAddress")

        return ClientResult.Joined(
            ownerAddress = gatewayAddress,
            path = ClientJoinPath.WIFI_SPECIFIER,
            p2pAttemptFailed = p2pFailureReason
        )
    }

    private fun cleanupP2p() {
        connectionReceiver?.let { r ->
            try {
                context.unregisterReceiver(r)
                Log.d(TAG, "[WFD_CLIENT] P2P connection receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "[WFD_CLIENT] Error unregistering P2P receiver: ${e.message}")
            }
            connectionReceiver = null
        }

        val ch = p2pChannel
        val mgr = p2pManager

        // Step 1: cancelConnect() first (cancels a connection still in progress)
        if (mgr != null && ch != null) {
            try {
                mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "[WFD_CLIENT] cancelConnect succeeded")
                    }

                    override fun onFailure(reason: Int) {
                        Log.d(TAG, "[WFD_CLIENT] cancelConnect reported reason=${getP2pErrorName(reason)}")
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "[WFD_CLIENT] Error calling cancelConnect: ${e.message}")
            }

            // Step 2: removeGroup()
            try {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "[WFD_CLIENT] removeGroup succeeded")
                    }

                    override fun onFailure(reason: Int) {
                        Log.d(TAG, "[WFD_CLIENT] removeGroup reported reason=${getP2pErrorName(reason)}")
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "[WFD_CLIENT] Error calling removeGroup: ${e.message}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    ch.close()
                    Log.d(TAG, "[WFD_CLIENT] P2P channel closed")
                } catch (e: Exception) {
                    Log.w(TAG, "[WFD_CLIENT] Error closing P2P channel: ${e.message}")
                }
            }
        }

        p2pChannel = null
        p2pManager = null
    }

    private fun cleanupSpecifier() {
        networkCallback?.let { cb ->
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
                connectivityManager?.bindProcessToNetwork(null)
                Log.d(TAG, "[WFD_CLIENT] WifiNetworkSpecifier network callback unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "[WFD_CLIENT] Error unregistering network callback: ${e.message}")
            }
            networkCallback = null
        }
    }

    /**
     * Leaves the Wi-Fi Direct group or releases the network request.
     * Order:
     * 1. cancelConnect() (cancels a connection still in progress; safe if none)
     * 2. removeGroup() (safe if none)
     * 3. Release network request (safe if none)
     * Safe to call multiple times.
     */
    @Synchronized
    fun leave() {
        Log.d(TAG, "[WFD_CLIENT] leave: requested")
        cleanupP2p()
        cleanupSpecifier()
        isConnected = false
        Log.d(TAG, "[WFD_CLIENT] leave: completed")
    }
}
