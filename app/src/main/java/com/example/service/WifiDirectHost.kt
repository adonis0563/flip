package com.example.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import kotlin.coroutines.resume

/**
 * Result outcomes from starting the Wi-Fi Direct host.
 */
sealed interface HostResult {
    data class GroupReady(
        val networkName: String,
        val passphrase: String,
        val ownerDeviceAddress: String
    ) : HostResult

    data object Unsupported : HostResult
    data object PermissionMissing : HostResult
    data object Busy : HostResult
    data class Error(val code: Int) : HostResult
}

private sealed interface CreateGroupOutcome {
    data object Success : CreateGroupOutcome
    data class Failure(val code: Int) : CreateGroupOutcome
    data object SecurityError : CreateGroupOutcome
}

/**
 * Wi-Fi Direct Host (Sender) helper for Android 10+ (API 29+).
 *
 * Sets up an autonomous Wi-Fi Direct group with customized network name and passphrase,
 * listens for client connection, and reports the group owner's IP address.
 * Never logs credentials.
 */
class WifiDirectHost(
    private val context: Context,
    private val onClientJoined: ((ownerIpAddress: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "FlipDirect"
        private const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private const val PASSPHRASE_LENGTH = 12

        /**
         * Checks if the required permissions for Wi-Fi Direct hosting are granted.
         */
        fun hasRequiredPermissions(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
            if (sdkInt < Build.VERSION_CODES.Q) {
                return false
            }

            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return false
                }
            } else {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return false
                }
            }

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_WIFI_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CHANGE_WIFI_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }

            return true
        }
    }

    private val secureRandom = SecureRandom()
    private var p2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var connectionReceiver: BroadcastReceiver? = null
    private var previousClientCount = 0
    private var activeClientJoinedCallback: ((ownerIpAddress: String) -> Unit)? = null

    /**
     * Starts the Wi-Fi Direct host.
     *
     * 1. Checks Android version (requires Android 10+).
     * 2. Checks permissions.
     * 3. Sets up WifiP2pManager and channel.
     * 4. Removes any existing group.
     * 5. Generates group name "DIRECT-" + 2 random letters/digits + "-Flip" and 12-char passphrase.
     * 6. Builds WifiP2pConfig and calls createGroup.
     * 7. On BUSY, removes group and retries once.
     * 8. On success, reads group info and registers connection listener.
     */
    suspend fun start(
        clientJoinedCallback: ((ownerIpAddress: String) -> Unit)? = null,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): HostResult =
        withContext(Dispatchers.Default) {
            Log.d(TAG, "start: requested")

            if (sdkInt < Build.VERSION_CODES.Q) {
                Log.w(TAG, "start: unsupported OS version ($sdkInt < 29)")
                return@withContext HostResult.Unsupported
            }

            if (!hasRequiredPermissions(context, sdkInt)) {
                Log.w(TAG, "start: required permissions missing")
                return@withContext HostResult.PermissionMissing
            }

            val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (manager == null) {
                Log.w(TAG, "start: WifiP2pManager is null")
                return@withContext HostResult.Unsupported
            }

            val p2pChannel = manager.initialize(context, context.mainLooper, null)
            if (p2pChannel == null) {
                Log.w(TAG, "start: failed to initialize channel")
                return@withContext HostResult.Error(WifiP2pManager.ERROR)
            }

            synchronized(this@WifiDirectHost) {
                this@WifiDirectHost.p2pManager = manager
                this@WifiDirectHost.channel = p2pChannel
                this@WifiDirectHost.activeClientJoinedCallback = clientJoinedCallback ?: onClientJoined
            }

            // Remove any existing group first
            Log.d(TAG, "start: checking and removing any existing group")
            removeExistingGroup(manager, p2pChannel)

            // Generate group name and passphrase
            val random2 = buildString(2) {
                append(ALPHANUMERIC[secureRandom.nextInt(ALPHANUMERIC.length)])
                append(ALPHANUMERIC[secureRandom.nextInt(ALPHANUMERIC.length)])
            }
            val groupName = "DIRECT-$random2-Flip"

            val passphrase = buildString(PASSPHRASE_LENGTH) {
                repeat(PASSPHRASE_LENGTH) {
                    append(ALPHANUMERIC[secureRandom.nextInt(ALPHANUMERIC.length)])
                }
            }

            Log.d(TAG, "start: generated groupName=$groupName (credentials not logged)")

            val config = buildWifiP2pConfig(groupName, passphrase)

            Log.d(TAG, "start: calling createGroup")
            val initialOutcome = performCreateGroup(manager, p2pChannel, config)

            val finalOutcome = if (initialOutcome is CreateGroupOutcome.Failure && initialOutcome.code == WifiP2pManager.BUSY) {
                Log.d(TAG, "start: createGroup returned BUSY, removing group and retrying once")
                removeExistingGroup(manager, p2pChannel)
                delay(400)
                performCreateGroup(manager, p2pChannel, config)
            } else {
                initialOutcome
            }

            when (finalOutcome) {
                is CreateGroupOutcome.Success -> {
                    Log.d(TAG, "start: createGroup succeeded, requesting group info")
                    val group = requestGroupInfoSuspend(manager, p2pChannel)
                    val readyNetworkName = group?.networkName?.takeIf { it.isNotBlank() } ?: groupName
                    val readyPassphrase = group?.passphrase?.takeIf { it.isNotBlank() } ?: passphrase
                    val ownerAddress = group?.owner?.deviceAddress ?: ""

                    registerConnectionReceiver(manager, p2pChannel)

                    Log.d(TAG, "start: GroupReady (networkName=$readyNetworkName, ownerAddress=$ownerAddress)")
                    HostResult.GroupReady(
                        networkName = readyNetworkName,
                        passphrase = readyPassphrase,
                        ownerDeviceAddress = ownerAddress
                    )
                }
                is CreateGroupOutcome.Failure -> {
                    Log.w(TAG, "start: createGroup failed with code=${finalOutcome.code}")
                    when (finalOutcome.code) {
                        WifiP2pManager.BUSY -> HostResult.Busy
                        WifiP2pManager.P2P_UNSUPPORTED -> HostResult.Unsupported
                        else -> HostResult.Error(finalOutcome.code)
                    }
                }
                is CreateGroupOutcome.SecurityError -> {
                    Log.w(TAG, "start: SecurityException caught, returning PermissionMissing")
                    HostResult.PermissionMissing
                }
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildWifiP2pConfig(networkName: String, passphrase: String): WifiP2pConfig {
        return WifiP2pConfig.Builder()
            .setNetworkName(networkName)
            .setPassphrase(passphrase)
            .build()
    }

    private suspend fun performCreateGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        config: WifiP2pConfig
    ): CreateGroupOutcome = suspendCancellableCoroutine { cont ->
        try {
            manager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "createGroup: action listener reported success")
                    if (cont.isActive) cont.resume(CreateGroupOutcome.Success)
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "createGroup: action listener reported failure reason=$reason")
                    if (cont.isActive) cont.resume(CreateGroupOutcome.Failure(reason))
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "createGroup: SecurityException", e)
            if (cont.isActive) cont.resume(CreateGroupOutcome.SecurityError)
        } catch (e: Exception) {
            Log.w(TAG, "createGroup: unexpected exception", e)
            if (cont.isActive) cont.resume(CreateGroupOutcome.Failure(WifiP2pManager.ERROR))
        }
    }

    private suspend fun removeExistingGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): Unit = suspendCancellableCoroutine { cont ->
        try {
            manager.requestGroupInfo(channel) { existingGroup ->
                if (existingGroup != null) {
                    Log.d(TAG, "removeExistingGroup: existing group detected, calling removeGroup")
                    manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG, "removeExistingGroup: existing group removed successfully")
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onFailure(reason: Int) {
                            Log.w(TAG, "removeExistingGroup: removeGroup reported failure reason=$reason")
                            if (cont.isActive) cont.resume(Unit)
                        }
                    })
                } else {
                    Log.d(TAG, "removeExistingGroup: no existing group found")
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

    private suspend fun requestGroupInfoSuspend(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): WifiP2pGroup? = suspendCancellableCoroutine { cont ->
        try {
            manager.requestGroupInfo(channel) { group ->
                if (cont.isActive) cont.resume(group)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "requestGroupInfoSuspend: SecurityException", e)
            if (cont.isActive) cont.resume(null)
        } catch (e: Exception) {
            Log.w(TAG, "requestGroupInfoSuspend: exception", e)
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun registerConnectionReceiver(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ) {
        unregisterReceiverInternal()
        previousClientCount = 0

        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(recvContext: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                Log.d(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION broadcast received")

                try {
                    manager.requestGroupInfo(channel) { group ->
                        if (group != null && group.isGroupOwner) {
                            val clients = group.clientList
                            val count = clients.size
                            Log.d(TAG, "Group info updated: isOwner=true, clientCount=$count")

                            if (count > previousClientCount && clients.isNotEmpty()) {
                                previousClientCount = count
                                manager.requestConnectionInfo(channel) { info ->
                                    if (info != null && info.groupFormed && info.isGroupOwner) {
                                        val realOwnerIp = info.groupOwnerAddress?.hostAddress
                                        if (!realOwnerIp.isNullOrBlank()) {
                                            Log.d(TAG, "onClientJoined: reporting owner IP: $realOwnerIp")
                                            activeClientJoinedCallback?.invoke(realOwnerIp)
                                        } else {
                                            Log.w(TAG, "onClientJoined: groupOwnerAddress is null or empty")
                                        }
                                    }
                                }
                            } else if (count < previousClientCount) {
                                previousClientCount = count
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "ConnectionReceiver: SecurityException while querying info", e)
                } catch (e: Exception) {
                    Log.w(TAG, "ConnectionReceiver: exception while querying info", e)
                }
            }
        }

        connectionReceiver = receiver

        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            Log.d(TAG, "Connection receiver registered")
        } catch (_: Exception) {
            try {
                context.registerReceiver(receiver, filter)
                Log.d(TAG, "Connection receiver registered (fallback)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register connection receiver", e)
            }
        }
    }

    private fun unregisterReceiverInternal() {
        connectionReceiver?.let { r ->
            try {
                context.unregisterReceiver(r)
                Log.d(TAG, "Connection receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering connection receiver: ${e.message}")
            }
            connectionReceiver = null
        }
    }

    /**
     * Stops the Wi-Fi Direct host, removes the group, unregisters receivers, and closes the channel.
     * Safe to call multiple times.
     */
    @Synchronized
    fun stop() {
        Log.d(TAG, "stop: requested")
        unregisterReceiverInternal()

        val ch = channel
        val mgr = p2pManager

        if (mgr != null && ch != null) {
            try {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "stop: removeGroup succeeded")
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "stop: removeGroup failed with reason: $reason")
                    }
                })
            } catch (e: SecurityException) {
                Log.w(TAG, "stop: removeGroup SecurityException", e)
            } catch (e: Exception) {
                Log.w(TAG, "stop: removeGroup error", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                ch?.close()
                Log.d(TAG, "stop: channel closed")
            } catch (e: Exception) {
                Log.w(TAG, "stop: channel close error", e)
            }
        }

        channel = null
        p2pManager = null
        previousClientCount = 0
        activeClientJoinedCallback = null
        Log.d(TAG, "stop: cleanup completed")
    }
}
