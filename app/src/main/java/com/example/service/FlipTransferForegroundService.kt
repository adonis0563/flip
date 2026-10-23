package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.Device
import com.example.service.control.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified state machine for First-Time Pairing and Connection.
 *
 * Sequence:
 * Idle → Joining → AwaitingLink → Handshaking → Connected(peerUuid, peerName) | Failed(reason)
 *
 * "Connected" is set ONLY here, only after the full 4-way control handshake
 * (HELLO, HELLO_ACK, READY, READY_ACK) completes on port 8081.
 */
sealed class FirstTimeConnectionState {
    object Idle : FirstTimeConnectionState()

    data class Joining(
        val peerName: String,
        val shortCode: String
    ) : FirstTimeConnectionState()

    data class AwaitingLink(
        val peerName: String,
        val shortCode: String
    ) : FirstTimeConnectionState()

    data class Handshaking(
        val peerName: String,
        val shortCode: String
    ) : FirstTimeConnectionState()

    data class Connected(
        val peerUuid: String,
        val peerName: String,
        val peerIp: String = "",
        val peerPort: Int = 0
    ) : FirstTimeConnectionState()

    data class Failed(
        val reason: String
    ) : FirstTimeConnectionState()
}

class FlipTransferForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    interface ServiceBridge {
        fun onCancelTransfer(transferId: String)
        fun onDisconnectSession()
        fun onPeerBye(peerName: String)
        fun onPeerLost()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        TransferNotificationManager.createNotificationChannels(this)
        Log.d(TAG, "[SERVICE] FlipTransferForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        val remoteDevice = currentRemoteDevice

        val notification = TransferNotificationManager.buildPersistentConnectionNotification(this, remoteDevice)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgsType = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(TransferNotificationManager.ID_PERSISTENT_CONNECTION, notification, fgsType)
            } else {
                startForeground(TransferNotificationManager.ID_PERSISTENT_CONNECTION, notification)
            }
            Log.d(TAG, "[SERVICE] Foreground service active for remote device: ${remoteDevice?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "[SERVICE] Failed to start foreground service: ${e.message}", e)
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "[SERVICE] Task removed from recents; service remains active while connected.")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w(TAG, "[SERVICE] Foreground service timeout reached for fgsType: $fgsType")
        TransferNotificationManager.showTransferFailedNotification(
            this,
            "timeout",
            "Session",
            "Background transfer session timed out by system."
        )
        bridge?.onDisconnectSession()
        stopForegroundService()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        pendingGates.values.forEach { it.complete(false) }
        pendingGates.clear()
        teardownFirstTimeConnection()
        ConnectionSessionManager.updateDisconnected()
        TransferNotificationManager.cancelAllNotifications(this)
        instance = null
        Log.d(TAG, "[SERVICE] FlipTransferForegroundService destroyed and notifications cleaned")
    }

    private fun stopForegroundService() {
        TransferNotificationManager.cancelAllNotifications(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "FlipTransferService"
        const val ACTION_START = "com.example.flip.ACTION_START_FGS"
        const val ACTION_STOP = "com.example.flip.ACTION_STOP_FGS"

        @Volatile
        private var instance: FlipTransferForegroundService? = null

        @Volatile
        var bridge: ServiceBridge? = null

        @Volatile
        var currentRemoteDevice: Device? = null

        private val pendingGates = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

        // --- SINGLE CONNECTION STATE MACHINE ---
        private val _connectionFlowState = MutableStateFlow<FirstTimeConnectionState>(FirstTimeConnectionState.Idle)
        val connectionFlowState: StateFlow<FirstTimeConnectionState> = _connectionFlowState.asStateFlow()

        private var senderServer: HandshakeServer? = null
        private var receiverClient: HandshakeClient? = null
        private var hotspotJoinHelper: HotspotJoinHelper? = null
        private var receiverFlowJob: Job? = null
        private var flowScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private var connectionStartTime: Long = 0L

        /**
         * SENDER: Starts HandshakeServer when "Waiting for receiver" screen opens.
         * Keeps it running for the whole screen and stops it on cancel or screen close.
         */
        @Synchronized
        fun startSenderControl(context: Context, transferPort: Int = 0) {
            teardownFirstTimeConnection()
            connectionStartTime = System.currentTimeMillis()
            _connectionFlowState.value = FirstTimeConnectionState.Idle
            ControlChannelConstants.logStep(step = "sender_waiting_start", elapsedMs = 0)

            val ownIdentity = DeviceIdentity.get(context)
            val server = HandshakeServer()
            senderServer = server

            val actualTransferPort = if (transferPort > 0) transferPort else (currentRemoteDevice?.port ?: 0)

            server.start(
                ownIdentity = ownIdentity,
                port = ControlChannelConstants.PORT,
                transferPort = actualTransferPort,
                onPeerConnected = { peerUuid, peerName, peerIp, peerPort ->
                    val elapsed = System.currentTimeMillis() - connectionStartTime
                    ControlChannelConstants.logStep(
                        step = "connected",
                        peerId = peerUuid,
                        elapsedMs = elapsed
                    )
                    // Save the peer (uuid and name, no credentials)
                    HotspotCredentialStore.saveKnownReceiver(context, peerUuid, peerName)

                    // Single source of truth sets Connected state
                    _connectionFlowState.value = FirstTimeConnectionState.Connected(
                        peerUuid = peerUuid,
                        peerName = peerName,
                        peerIp = peerIp,
                        peerPort = peerPort
                    )

                    // Continue to the transfer flow
                    val remoteDev = Device(
                        id = peerUuid,
                        name = peerName,
                        ip = if (peerIp.isNotBlank()) peerIp else "0.0.0.0",
                        port = peerPort
                    )
                    start(context, remoteDev, "SENDER")
                },
                onPeerLost = {
                    val elapsed = System.currentTimeMillis() - connectionStartTime
                    ControlChannelConstants.logStep(
                        step = "peer_lost",
                        elapsedMs = elapsed
                    )
                    teardownFirstTimeConnection()
                    _connectionFlowState.value = FirstTimeConnectionState.Failed("Connection lost.")
                    bridge?.onPeerLost()
                },
                onPeerBye = { peerName ->
                    bridge?.onPeerBye(peerName)
                }
            )
        }

        /**
         * RECEIVER: Initiates first-time connection flow using joinHotspot and HandshakeClient.
         */
        @Synchronized
        fun startReceiverFlow(
            context: Context,
            credentials: WifiCredentials,
            peerUuid: String,
            peerName: String,
            onConnected: (gatewayAddress: String) -> Unit
        ) {
            teardownFirstTimeConnection()
            connectionStartTime = System.currentTimeMillis()

            val normPeerUuid = ControlChannelConstants.normalizeUuid(peerUuid)
            val shortCode = ControlChannelConstants.getShortCode(normPeerUuid)
            val ownIdentity = DeviceIdentity.get(context)

            val joiner = HotspotJoinHelper(
                context = context,
                onNetworkLost = {
                    val elapsed = System.currentTimeMillis() - connectionStartTime
                    ControlChannelConstants.logStep(step = "network_lost", peerId = normPeerUuid, elapsedMs = elapsed)
                    if (_connectionFlowState.value is FirstTimeConnectionState.Connected) {
                        teardownFirstTimeConnection()
                        _connectionFlowState.value = FirstTimeConnectionState.Failed("Connection lost. The sender's hotspot may have turned off.")
                    }
                }
            )
            hotspotJoinHelper = joiner

            val client = HandshakeClient()
            client.onPeerBye = { byeName ->
                bridge?.onPeerBye(byeName)
            }
            receiverClient = client

            receiverFlowJob = flowScope.launch {
                // Step 1: Joining hotspot
                _connectionFlowState.value = FirstTimeConnectionState.Joining(peerName, shortCode)
                ControlChannelConstants.logStep(
                    step = "joining_hotspot",
                    peerId = normPeerUuid,
                    elapsedMs = System.currentTimeMillis() - connectionStartTime
                )

                val joinResult = joiner.joinHotspot(credentials, timeoutMs = 30000)

                if (joinResult !is JoinResult.Joined) {
                    val elapsed = System.currentTimeMillis() - connectionStartTime
                    val failReason = (joinResult as? JoinResult.Failed)?.reason ?: JoinResult.FailReason.Unavailable
                    ControlChannelConstants.logStep(
                        step = "join_failed",
                        peerId = normPeerUuid,
                        detail = failReason.name,
                        elapsedMs = elapsed
                    )

                    teardownFirstTimeConnection()

                    val message = when (failReason) {
                        JoinResult.FailReason.Unavailable, JoinResult.FailReason.Timeout -> {
                            val isSsidSeen = isSsidVisible(context, credentials.ssid)
                            if (isSsidSeen) {
                                "Couldn't connect. The password may be wrong, or the connection wasn't approved."
                            } else {
                                "Couldn't find $peerName's hotspot. Make sure it's on and close by."
                            }
                        }
                        JoinResult.FailReason.NoIp -> {
                            "Connected to the hotspot but got no address. Try again."
                        }
                        JoinResult.FailReason.Unsupported -> {
                            "Auto-connect is not supported on this Android version."
                        }
                        JoinResult.FailReason.Cancelled -> {
                            _connectionFlowState.value = FirstTimeConnectionState.Idle
                            return@launch
                        }
                    }

                    _connectionFlowState.value = FirstTimeConnectionState.Failed(message)
                    return@launch
                }

                // Step 2: AwaitingLink
                _connectionFlowState.value = FirstTimeConnectionState.AwaitingLink(peerName, shortCode)
                ControlChannelConstants.logStep(
                    step = "awaiting_link",
                    peerId = normPeerUuid,
                    elapsedMs = System.currentTimeMillis() - connectionStartTime
                )

                delay(1500L) // Small pause as network stack settles right after joining

                // Step 3: Handshaking (run HandshakeClient up to 3 attempts, 1.5s apart)
                var handshakeSuccess = false
                var lastFailResult: HandshakeResult? = null

                for (attempt in 1..3) {
                    if (!isActive) break

                    _connectionFlowState.value = FirstTimeConnectionState.Handshaking(peerName, shortCode)
                    ControlChannelConstants.logStep(
                        step = "handshake_attempt_$attempt",
                        peerId = normPeerUuid,
                        elapsedMs = System.currentTimeMillis() - connectionStartTime
                    )

                    val res = client.connect(
                        host = joinResult.gatewayAddress,
                        expectedPeerUuid = normPeerUuid,
                        ownIdentity = ownIdentity,
                        port = ControlChannelConstants.PORT,
                        onPeerLost = {
                            val elapsed = System.currentTimeMillis() - connectionStartTime
                            ControlChannelConstants.logStep(step = "peer_lost", peerId = normPeerUuid, elapsedMs = elapsed)
                            if (_connectionFlowState.value is FirstTimeConnectionState.Connected) {
                                teardownFirstTimeConnection()
                                _connectionFlowState.value = FirstTimeConnectionState.Failed("Connection lost. The sender's hotspot may have turned off.")
                            }
                            bridge?.onPeerLost()
                        },
                        onPeerBye = { byeName ->
                            bridge?.onPeerBye(byeName)
                        }
                    )

                    if (res is HandshakeResult.Success) {
                        handshakeSuccess = true
                        break
                    } else {
                        lastFailResult = res
                        if (attempt < 3 && res !is HandshakeResult.SameDevice && res !is HandshakeResult.IdMismatch) {
                            delay(1500L)
                        } else {
                            break
                        }
                    }
                }

                if (handshakeSuccess) {
                    val elapsed = System.currentTimeMillis() - connectionStartTime
                    ControlChannelConstants.logStep(
                        step = "connected",
                        peerId = normPeerUuid,
                        elapsedMs = elapsed
                    )

                    _connectionFlowState.value = FirstTimeConnectionState.Connected(
                        peerUuid = normPeerUuid,
                        peerName = peerName
                    )

                    // Pass gateway to transfer entry point
                    onConnected(joinResult.gatewayAddress)
                } else {
                    val elapsed = System.currentTimeMillis() - connectionStartTime
                    ControlChannelConstants.logStep(
                        step = "handshake_failed",
                        peerId = normPeerUuid,
                        detail = lastFailResult?.javaClass?.simpleName ?: "Unknown",
                        elapsedMs = elapsed
                    )

                    teardownFirstTimeConnection()

                    val message = when (lastFailResult) {
                        is HandshakeResult.SameDevice -> {
                            "That's this phone's own QR code."
                        }
                        is HandshakeResult.IdMismatch -> {
                            // Delete that peer's saved credentials
                            HotspotCredentialStore.forgetPeer(context, normPeerUuid)
                            CredentialStore.forgetPeer(context, normPeerUuid)
                            "This isn't the device you paired with."
                        }
                        is HandshakeResult.Timeout, is HandshakeResult.Refused, null -> {
                            val isSsidSeen = isSsidVisible(context, credentials.ssid)
                            if (isSsidSeen) {
                                "Couldn't connect. The password may be wrong, or the connection wasn't approved."
                            } else {
                                "Couldn't find $peerName's hotspot. Make sure it's on and close by."
                            }
                        }
                        is HandshakeResult.Malformed -> {
                            "Couldn't connect to ${peerName}. Try again."
                        }
                        else -> {
                            "Couldn't connect to ${peerName}. Try again."
                        }
                    }

                    _connectionFlowState.value = FirstTimeConnectionState.Failed(message)
                }
            }
        }

        private fun isSsidVisible(context: Context, targetSsid: String): Boolean {
            return try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wm?.startScan()
                val results = wm?.scanResults ?: return false
                val clean = targetSsid.removeSurrounding("\"")
                results.any {
                    val s = it.SSID?.removeSurrounding("\"") ?: ""
                    s.equals(clean, ignoreCase = true)
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * TEARDOWN: Stops heartbeat, closes control sockets, calls leaveHotspot().
         * Safe to call multiple times.
         */
        @Synchronized
        fun teardownFirstTimeConnection() {
            receiverFlowJob?.cancel()
            receiverFlowJob = null

            try {
                senderServer?.stop()
            } catch (_: Exception) {}
            senderServer = null

            try {
                receiverClient?.close()
            } catch (_: Exception) {}
            receiverClient = null

            try {
                hotspotJoinHelper?.leaveHotspot()
            } catch (_: Exception) {}
            hotspotJoinHelper = null

            ControlChannelConstants.logStep(step = "teardown_complete")
        }

        /**
         * Returns connection state machine to Idle.
         */
        @Synchronized
        fun resetFirstTimeState() {
            teardownFirstTimeConnection()
            _connectionFlowState.value = FirstTimeConnectionState.Idle
        }

        fun sendBye(name: String) {
            try {
                senderServer?.sendBye(name)
            } catch (_: Exception) {}
            try {
                receiverClient?.sendBye(name)
            } catch (_: Exception) {}
        }

        fun isControlChannelAlive(): Boolean {
            return (senderServer?.isAlive() == true) || (receiverClient?.isAlive() == true)
        }

        fun start(context: Context, remoteDevice: Device?, role: String? = null) {
            currentRemoteDevice = remoteDevice
            if (remoteDevice != null) {
                ConnectionSessionManager.updateConnected(remoteDevice, role)
                ConnectedDevicesStore.recordSession(
                    context = context,
                    displayName = remoteDevice.name,
                    shortCode = DeviceIdentity.getShortCodeFromUuid(remoteDevice.id)
                )
            }
            val intent = Intent(context, FlipTransferForegroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            teardownFirstTimeConnection()
            _connectionFlowState.value = FirstTimeConnectionState.Idle
            currentRemoteDevice = null
            ConnectionSessionManager.updateDisconnected()
            val intent = Intent(context, FlipTransferForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
            instance?.stopForegroundService()
        }

        suspend fun awaitTransferApproval(transferId: String): Boolean {
            val deferred = CompletableDeferred<Boolean>()
            pendingGates[transferId] = deferred
            return try {
                withTimeoutOrNull(60_000L) {
                    deferred.await()
                } ?: false
            } finally {
                pendingGates.remove(transferId)
            }
        }

        fun acceptTransfer(context: Context, transferId: String) {
            Log.d(TAG, "[SERVICE] Accept transfer: $transferId")
            TransferNotificationManager.cancelTransferNotification(context, transferId)
            val deferred = pendingGates.remove(transferId)
            deferred?.complete(true)
        }

        fun declineTransfer(context: Context, transferId: String) {
            Log.d(TAG, "[SERVICE] Decline transfer: $transferId")
            TransferNotificationManager.cancelTransferNotification(context, transferId)
            val deferred = pendingGates.remove(transferId)
            deferred?.complete(false)
        }

        fun cancelPendingRequest(transferId: String) {
            val deferred = pendingGates.remove(transferId)
            deferred?.complete(false)
        }

        fun cancelTransfer(context: Context, transferId: String) {
            Log.d(TAG, "[SERVICE] User requested cancel for transfer: $transferId")
            TransferNotificationManager.cancelTransferNotification(context, transferId)
            val deferred = pendingGates.remove(transferId)
            deferred?.complete(false)
            bridge?.onCancelTransfer(transferId)
        }

        fun disconnectService(context: Context) {
            Log.d(TAG, "[SERVICE] User requested disconnect from notification")
            bridge?.onDisconnectSession()
            stop(context)
        }
    }
}
