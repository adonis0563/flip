package com.example.viewmodel

import android.app.Application
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.Device
import com.example.model.TransferItem
import com.example.model.TransferStatus
import com.example.service.*
import com.example.service.control.HandshakeClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionState {
    IDLE,
    SEARCHING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

data class IncomingFileRequest(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val senderName: String
)

data class PendingShareRequest(
    val items: List<LocalFileItem>,
    val text: String? = null,
    val targetDevice: Device? = null
)

sealed interface OnboardingState {
    data object Loading : OnboardingState
    data object Done : OnboardingState
    data object NotDone : OnboardingState
}

data class PendingShareBannerData(
    val items: List<LocalFileItem>,
    val text: String? = null
)

data class AppShareOption(
    val appItem: LocalFileItem,
    val originalText: String
)

class FlipViewModel(application: Application) : AndroidViewModel(application), 
    HttpServerService.ServerListener, BleDiscoveryService.DiscoveryListener {

    private val context = getApplication<Application>().applicationContext
    private val transferService = TransferService(context)
    private val bleService = BleDiscoveryService(context, this)
    val wifiDirectClient = WifiDirectClient(context)
    val wifiDirectHost = WifiDirectHost(context)
    private var handshakeClient: HandshakeClient? = null
    private var httpServerService: HttpServerService? = null
    private var directSenderTimeoutJob: Job? = null
    private var groupLostDebounceJob: Job? = null
    private val isCleaningUp = AtomicBoolean(false)

    // UI state flows
    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _role = MutableStateFlow<String?>(null) // "SENDER" or "RECEIVER"
    val role: StateFlow<String?> = _role.asStateFlow()

    private val _directSenderPayloadUri = MutableStateFlow<String?>(null)
    val directSenderPayloadUri: StateFlow<String?> = _directSenderPayloadUri.asStateFlow()

    private val _localDevice = MutableStateFlow<Device?>(null)
    val localDevice: StateFlow<Device?> = _localDevice.asStateFlow()

    private val _remoteDevice = MutableStateFlow<Device?>(null)
    val remoteDevice: StateFlow<Device?> = _remoteDevice.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    private val _transferQueue = MutableStateFlow<List<TransferItem>>(emptyList())
    val transferQueue: StateFlow<List<TransferItem>> = _transferQueue.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _bleError = MutableStateFlow<String?>(null)
    val bleError: StateFlow<String?> = _bleError.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _pendingIncomingRequest = MutableStateFlow<IncomingFileRequest?>(null)
    val pendingIncomingRequest: StateFlow<IncomingFileRequest?> = _pendingIncomingRequest.asStateFlow()

    private val _pendingShareRequest = MutableStateFlow<PendingShareRequest?>(null)
    val pendingShareRequest: StateFlow<PendingShareRequest?> = _pendingShareRequest.asStateFlow()

    private val _onboardingState = MutableStateFlow<OnboardingState>(OnboardingState.Loading)
    val onboardingState: StateFlow<OnboardingState> = _onboardingState.asStateFlow()

    private val _pendingPostOnboardingShare = MutableStateFlow<PendingShareBannerData?>(null)
    val pendingPostOnboardingShare: StateFlow<PendingShareBannerData?> = _pendingPostOnboardingShare.asStateFlow()

    private val _appSharePromptState = MutableStateFlow<AppShareOption?>(null)
    val appSharePromptState: StateFlow<AppShareOption?> = _appSharePromptState.asStateFlow()

    fun setOnboardingState(state: OnboardingState) {
        _onboardingState.value = state
    }

    fun setOnboardingDone() {
        _onboardingState.value = OnboardingState.Done
    }

    fun dismissPendingPostOnboardingShare() {
        _pendingPostOnboardingShare.value = null
    }

    fun sendPendingPostOnboardingShare() {
        val pending = _pendingPostOnboardingShare.value ?: return
        _pendingPostOnboardingShare.value = null
        if (pending.items.isNotEmpty()) {
            preSelectFiles(pending.items)
        }
        if (!pending.text.isNullOrBlank()) {
            pendingSharedText = pending.text
        }
    }

    fun clearAppSharePrompt() {
        _appSharePromptState.value = null
    }

    fun chooseSendSharedApp(appItem: LocalFileItem) {
        _appSharePromptState.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val extracted = StorageService.extractApk(context, appItem)
            if (extracted != null && extracted.exists()) {
                val fileItem = LocalFileItem(
                    id = System.currentTimeMillis(),
                    uri = Uri.fromFile(extracted),
                    name = extracted.name,
                    size = extracted.length(),
                    mimeType = "application/vnd.android.package-archive",
                    category = "Apps"
                )
                withContext(Dispatchers.Main) {
                    val session = ConnectionSessionManager.session.value
                    val isConnected = session.isConnected || _connectionState.value == ConnectionState.CONNECTED
                    val target = session.remoteDevice ?: _remoteDevice.value
                    if (isConnected && target != null) {
                        _pendingShareRequest.value = PendingShareRequest(
                            items = listOf(fileItem),
                            text = null,
                            targetDevice = target
                        )
                    } else {
                        _pendingPostOnboardingShare.value = PendingShareBannerData(listOf(fileItem), null)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    showToast("Could not prepare app for sharing.")
                }
            }
        }
    }

    fun chooseSendSharedText(text: String) {
        _appSharePromptState.value = null
        viewModelScope.launch(Dispatchers.Main) {
            val session = ConnectionSessionManager.session.value
            val isConnected = session.isConnected || _connectionState.value == ConnectionState.CONNECTED
            val target = session.remoteDevice ?: _remoteDevice.value
            if (isConnected && target != null) {
                _pendingShareRequest.value = PendingShareRequest(
                    items = emptyList(),
                    text = text,
                    targetDevice = target
                )
            } else {
                _pendingPostOnboardingShare.value = PendingShareBannerData(emptyList(), text)
            }
        }
    }

    private val _openExplorerEvent = MutableStateFlow(false)
    val openExplorerEvent: StateFlow<Boolean> = _openExplorerEvent.asStateFlow()

    val autoJoinManager: AutoJoinManager = ConnectionSessionManager.getAutoJoinManager(context)
    val autoJoinState: StateFlow<AutoJoinState> = autoJoinManager.state


    val connectionFlowState: StateFlow<com.example.service.FirstTimeConnectionState> =
        com.example.service.FlipTransferForegroundService.connectionFlowState

    fun triggerOpenExplorer() {
        _openExplorerEvent.value = true
    }

    fun consumeOpenExplorer() {
        _openExplorerEvent.value = false
    }

    fun isControlChannelAlive(): Boolean {
        return handshakeClient?.isAlive() == true || FlipTransferForegroundService.isControlChannelAlive()
    }

    fun handleWifiDirectGroupState(isConnected: Boolean) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            groupLostDebounceJob?.cancel()
            groupLostDebounceJob = null
            return
        }

        if (isConnected) {
            // Cancel debounce if connection flaps back to connected
            groupLostDebounceJob?.cancel()
            groupLostDebounceJob = null
        } else {
            // Debounce 1 s to ignore flapping during setup
            if (groupLostDebounceJob?.isActive != true) {
                groupLostDebounceJob = viewModelScope.launch {
                    delay(1000L)
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        Log.i("FlipLink", "Wi-Fi Direct group lost after 1s debounce")
                        performDisconnectCleanup("group lost", "Connection ended")
                    }
                }
            }
        }
    }

    fun setHandshakeClient(client: HandshakeClient?) {
        synchronized(this) {
            this.handshakeClient = client
            client?.onPeerBye = { peerName ->
                viewModelScope.launch(Dispatchers.Main) {
                    performDisconnectCleanup("BYE", "$peerName ended the session")
                }
            }
            client?.onPeerLost = {
                viewModelScope.launch(Dispatchers.Main) {
                    performDisconnectCleanup("heartbeat", "Connection lost")
                }
            }
        }
    }

    fun acceptIncomingRequest(transferId: String) {
        _pendingIncomingRequest.value = null
        FlipTransferForegroundService.acceptTransfer(context, transferId)
    }

    fun declineIncomingRequest(transferId: String) {
        _pendingIncomingRequest.value = null
        FlipTransferForegroundService.declineTransfer(context, transferId)
    }

    // State lock for queue running
    private var queueJob: Job? = null
    @Volatile
    private var isQueueRunning = false

    // Keep track of active transfers that should be cancelled/aborted
    private val cancelledTransferIds = mutableSetOf<String>()

    private val preSelectedFiles = java.util.Collections.synchronizedList(mutableListOf<LocalFileItem>())
    private var pendingSharedText: String? = null

    fun preSelectFiles(files: List<LocalFileItem>) {
        preSelectedFiles.clear()
        preSelectedFiles.addAll(files)
        showToast("Pre-selected ${files.size} files. They will transfer automatically when connected.")
    }

    private fun checkAndQueuePreSelected() {
        val filesToQueue = synchronized(preSelectedFiles) {
            val list = preSelectedFiles.toList()
            preSelectedFiles.clear()
            list
        }
        val textToSend = pendingSharedText
        pendingSharedText = null

        if (filesToQueue.isNotEmpty() || !textToSend.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.Main) {
                filesToQueue.forEach { file ->
                    addFileToTransferQueue(file.uri, file.name, file.size)
                }
                if (!textToSend.isNullOrBlank()) {
                    sendText(textToSend)
                }
            }
        }
    }

    init {
        // Run orphaned offset markers cleanup on launch
        viewModelScope.launch(Dispatchers.IO) {
            StorageService.cleanupOrphanedMarkers()
            
            val keep = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE).getBoolean("keep_extracted_apk", false)
            if (!keep) {
                StorageService.cleanupTempApks()
            }
            StorageService.cleanupTempTransferFiles(context)
        }

        // Initialize local device profile with stable per-install UUID and session ID
        val identity = DeviceIdentity.get(context)
        val devId = identity.uuid
        val devName = identity.displayName
        val localIp = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
        _localDevice.value = Device(
            id = devId,
            name = devName,
            ip = localIp,
            port = 8080,
            sid = DeviceIdentity.currentSessionId
        )

        // Connect Wi-Fi Direct connection listeners
        wifiDirectHost.onGroupStateChanged = { isConnected ->
            handleWifiDirectGroupState(isConnected)
        }
        wifiDirectClient.onGroupStateChanged = { isConnected ->
            handleWifiDirectGroupState(isConnected)
        }

        // Connect Foreground Service bridge
        FlipTransferForegroundService.bridge = object : FlipTransferForegroundService.ServiceBridge {
            override fun onCancelTransfer(transferId: String) {
                cancelTransfer(transferId)
            }

            override fun onDisconnectSession() {
                performDisconnectCleanup("user", "Disconnected")
            }

            override fun onPeerBye(peerName: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    performDisconnectCleanup("BYE", "$peerName ended the session")
                }
            }

            override fun onPeerLost() {
                viewModelScope.launch(Dispatchers.Main) {
                    performDisconnectCleanup("heartbeat", "Connection lost")
                }
            }
        }

        // Restore active session from ConnectionSessionManager (Single source of truth)
        val initialSession = ConnectionSessionManager.session.value
        if (initialSession.isConnected && initialSession.remoteDevice != null) {
            _connectionState.value = ConnectionState.CONNECTED
            _remoteDevice.value = initialSession.remoteDevice
            _role.value = initialSession.role
        }

        viewModelScope.launch {
            ConnectionSessionManager.session.collect { session ->
                if (session.isConnected && session.remoteDevice != null) {
                    if (_connectionState.value != ConnectionState.CONNECTED) {
                        _connectionState.value = ConnectionState.CONNECTED
                        _remoteDevice.value = session.remoteDevice
                        _role.value = session.role
                    }
                } else if (!session.isConnected && _connectionState.value == ConnectionState.CONNECTED) {
                    performDisconnectCleanup("user", null)
                }
            }
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun clearBleError() {
        _bleError.value = null
    }

    /**
     * SENDER: Tap "Send" role initiation
     */
    fun startSenderMode() {
        _role.value = "SENDER"
        _connectionState.value = ConnectionState.SEARCHING
        _discoveredDevices.value = emptyList()
        _transferQueue.value = emptyList()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Bind and start local HTTP server
                val server = HttpServerService(context, this@FlipViewModel)
                val port = server.startServer()
                httpServerService = server
                _isServerRunning.value = true

                // Update local device port info
                val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
                val updatedLocal = _localDevice.value?.copy(ip = ip, port = port)
                _localDevice.value = updatedLocal

                // 2. Advertise over BLE
                if (updatedLocal != null) {
                    bleService.startAdvertising(
                        localIp = updatedLocal.ip,
                        port = updatedLocal.port,
                        deviceId = DeviceIdentity.getStableDeviceId(context),
                        deviceName = updatedLocal.name
                    )
                }
            } catch (e: Exception) {
                Log.e("FlipViewModel", "Failed to start sender server: ${e.message}")
                withContext(Dispatchers.Main) {
                    showToast("Failed to start sender server: ${e.message}")
                    resetToHome()
                }
            }
        }
    }

    /**
     * Prepares local HTTP transfer server for Wi-Fi Direct sender mode.
     */
    fun startDirectSenderMode() {
        _role.value = "SENDER"
        _directSenderPayloadUri.value = null
        directSenderTimeoutJob?.cancel()
        directSenderTimeoutJob = viewModelScope.launch {
            delay(120_000L)
            if (_connectionState.value != ConnectionState.CONNECTED) {
                Log.w("FlipViewModel", "[WFD_SENDER] 2-minute timeout reached with no receiver connection; resetting to home")
                withContext(Dispatchers.Main) {
                    showToast("Connection timed out after 2 minutes")
                }
                resetToHome()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Start local HTTP transfer server
                if (httpServerService == null) {
                    val server = HttpServerService(context, this@FlipViewModel)
                    val port = server.startServer()
                    httpServerService = server
                    _isServerRunning.value = true
                    val updatedLocal = _localDevice.value?.copy(port = port)
                    _localDevice.value = updatedLocal
                    Log.i("FlipViewModel", "[TRANSFER] Direct sender HTTP server started on port $port")
                }

                val boundHttpPort = httpServerService?.boundPort ?: _localDevice.value?.port ?: 0

                // 2. Start sender control channel with actual bound port
                try {
                    FlipTransferForegroundService.startSenderControl(context, boundHttpPort)
                } catch (e: Exception) {
                    Log.w("FlipViewModel", "[WFD_SENDER] Handshake server initialization: ${e.message}")
                }

                // 3. Start Wi-Fi Direct host
                val clientJoinedCb: (String) -> Unit = { ownerIp ->
                    Log.i("FlipViewModel", "[WFD_SENDER] Receiver joined Wi-Fi Direct group at radio level (owner IP: $ownerIp)")
                }

                val result = wifiDirectHost.start(clientJoinedCallback = clientJoinedCb)

                when (result) {
                    is HostResult.GroupReady -> {
                        Log.i("FlipViewModel", "[WFD_SENDER] Group ready: ${result.networkName}")
                        val currentLocal = _localDevice.value
                        val devId = currentLocal?.id ?: DeviceIdentity.get(context).uuid
                        val devName = currentLocal?.name ?: DeviceIdentity.get(context).displayName

                        val payloadUri = WifiDirectPayloadParser.build(
                            deviceId = devId,
                            deviceName = devName,
                            groupName = result.networkName,
                            passphrase = result.passphrase,
                            ownerAddress = result.ownerDeviceAddress
                        )
                        _directSenderPayloadUri.value = payloadUri

                        // Start BLE Advertising
                        val port = currentLocal?.port ?: (httpServerService?.boundPort ?: 0)
                        bleService.startAdvertising(
                            localIp = "0.0.0.0",
                            port = port,
                            deviceId = DeviceIdentity.getStableDeviceId(context),
                            deviceName = devName,
                            flags = 0x03.toByte()
                        )
                    }
                    is HostResult.Unsupported -> {
                        Log.e("FlipViewModel", "[WFD_SENDER] Unsupported OS version")
                        withContext(Dispatchers.Main) { showToast("Wi-Fi Direct hosting requires Android 10 or newer.") }
                    }
                    is HostResult.PermissionMissing -> {
                        Log.e("FlipViewModel", "[WFD_SENDER] Permissions missing")
                        withContext(Dispatchers.Main) { showToast("Required Wi-Fi or Nearby Devices permissions are missing.") }
                    }
                    is HostResult.Busy -> {
                        Log.e("FlipViewModel", "[WFD_SENDER] Wi-Fi Direct is busy")
                        withContext(Dispatchers.Main) { showToast("Wi-Fi Direct is currently busy. Please try again.") }
                    }
                    is HostResult.Error -> {
                        Log.e("FlipViewModel", "[WFD_SENDER] Wi-Fi Direct error: ${result.code}")
                        withContext(Dispatchers.Main) { showToast("Failed to start Wi-Fi Direct (Error ${result.code}).") }
                    }
                }
            } catch (e: Exception) {
                Log.e("FlipViewModel", "[TRANSFER] Error starting direct sender mode: ${e.message}")
            }
        }
    }

    /**
     * Connects to the joined client and enters the transfer flow.
     * Sets remote.ip = peerIp (the receiver's address), never the owner address.
     * If the receiver's HTTP /connect arrives first, uses that request's remote address instead.
     * Uses whichever comes first, and logs if a later one differs (tag "FlipLink", last number only and port).
     */
    fun onDirectClientConnected(peerIp: String, port: Int, peerId: String = "", peerName: String = "") {
        directSenderTimeoutJob?.cancel()
        directSenderTimeoutJob = null
        viewModelScope.launch(Dispatchers.Main) {
            bleService.stopScanning()
            bleService.stopAdvertising()

            val existingRemote = _remoteDevice.value
            val ownerIp = NetworkUtils.getLocalIpAddress() ?: ""

            val cleanPeerIp = peerIp.takeIf { it.isNotBlank() && it != ownerIp && it != "0.0.0.0" && !it.startsWith("127.") }
            val existingIp = existingRemote?.ip?.takeIf { it.isNotBlank() && it != ownerIp && it != "0.0.0.0" && !it.startsWith("127.") }
            val arpIp = com.example.service.control.ControlChannelConstants.getReceiverIpFromArp(ownerIp)
                ?.takeIf { it.isNotBlank() && it != ownerIp && it != "0.0.0.0" && !it.startsWith("127.") }

            val resolvedIp = cleanPeerIp ?: existingIp ?: arpIp ?: ""
            val resolvedPort = if (port > 0) port else (existingRemote?.port ?: 0)

            if (resolvedIp.isBlank() || resolvedIp == ownerIp || resolvedIp == "0.0.0.0" || resolvedIp.startsWith("127.")) {
                Log.e("FlipLink", "ERROR: Cannot connect to receiver: resolved IP is invalid or self (.${com.example.service.control.ControlChannelConstants.lastOctet(resolvedIp)}). Aborting connection.")
                showToast("Receiver not detected. Check that both phones are on the same network.")
                return@launch
            }

            val ownPort = httpServerService?.boundPort ?: _localDevice.value?.port ?: 0
            Log.i(
                "FlipLink",
                "Sender connected: own=.${com.example.service.control.ControlChannelConstants.lastOctet(ownerIp)}:$ownPort, peer=.${com.example.service.control.ControlChannelConstants.lastOctet(resolvedIp)}:$resolvedPort"
            )

            val resolvedId = if (peerId.isNotBlank()) peerId else (existingRemote?.id?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString())
            val resolvedName = if (peerName.isNotBlank() && peerName != "Receiver") peerName else (existingRemote?.name?.takeIf { it.isNotBlank() } ?: (if (peerName.isNotBlank()) peerName else "Receiver"))

            val remote = Device(
                id = resolvedId,
                name = resolvedName,
                ip = resolvedIp,
                port = resolvedPort
            )
            _remoteDevice.value = remote
            _role.value = "SENDER"
            _connectionState.value = ConnectionState.CONNECTED
            FlipTransferForegroundService.start(context, remote, "SENDER")
            showToast("Connected to ${remote.name}")
            checkAndQueuePreSelected()
        }
    }

    fun onDirectReceiverConnected(
        ownerIp: String,
        port: Int,
        peerId: String = "",
        peerName: String = "",
        handshakeClient: HandshakeClient? = null,
        onDone: (Boolean, String?) -> Unit
    ) {
        if (handshakeClient != null) {
            setHandshakeClient(handshakeClient)
        }
        val currentLocal = _localDevice.value
        if (currentLocal == null) {
            onDone(false, "Local device not ready")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            transferService.connectToDevice(
                remoteIp = ownerIp,
                remotePort = port,
                localDevice = currentLocal,
                onSuccess = { remote ->
                    viewModelScope.launch(Dispatchers.Main) {
                        bleService.stopScanning()
                        bleService.stopAdvertising()
                        _remoteDevice.value = remote
                        _role.value = "RECEIVER"
                        _connectionState.value = ConnectionState.CONNECTED
                        FlipTransferForegroundService.start(context, remote, "RECEIVER")
                        val ownIp = currentLocal.ip
                        val ownPort = currentLocal.port
                        Log.i(
                            "FlipLink",
                            "Receiver connected: own=.${com.example.service.control.ControlChannelConstants.lastOctet(ownIp)}:$ownPort, peer=.${com.example.service.control.ControlChannelConstants.lastOctet(ownerIp)}:$port"
                        )
                        showToast("Connected to ${remote.name}")
                        checkAndQueuePreSelected()
                        onDone(true, null)
                    }
                },
                onError = { err ->
                    Log.e("FlipViewModel", "Failed to connect to direct sender: $err")
                    onDone(false, err)
                }
            )
        }
    }

    fun onDirectReceiverConnected(
        ownerIp: String,
        port: Int,
        peerId: String = "",
        peerName: String = "",
        onDone: (Boolean, String?) -> Unit
    ) {
        onDirectReceiverConnected(ownerIp, port, peerId, peerName, null, onDone)
    }

    /**
     * RECEIVER: Tap "Receive" role initiation
     */
    fun startReceiverMode() {
        _role.value = "RECEIVER"
        _connectionState.value = ConnectionState.SEARCHING
        _discoveredDevices.value = emptyList()
        _transferQueue.value = emptyList()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Bind and start local HTTP server
                val server = HttpServerService(context, this@FlipViewModel)
                val port = server.startServer()
                httpServerService = server
                _isServerRunning.value = true

                // Update local device port info
                val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
                val updatedLocal = _localDevice.value?.copy(ip = ip, port = port)
                _localDevice.value = updatedLocal

                // 2. Scan over BLE and notify auto-join manager
                autoJoinManager.onBleScanStarted()
                bleService.startScanning()
            } catch (e: Exception) {
                Log.e("FlipViewModel", "Failed to start receiver server: ${e.message}")
                withContext(Dispatchers.Main) {
                    showToast("Failed to start receiver server: ${e.message}")
                    resetToHome()
                }
            }
        }
    }

    /**
     * Tapping a discovered sender device to connect (Foreground Tap)
     */
    fun connectToDiscoveredDevice(target: Device) {
        _connectionState.value = ConnectionState.CONNECTING
        val currentLocal = _localDevice.value ?: return

        val pairedPeer = com.example.service.HotspotCredentialStore.getPairedPeer(context, target.id)
        if (pairedPeer != null) {
            if (pairedPeer.needsRePair) {
                _connectionState.value = ConnectionState.SEARCHING
                showToast("Re-pairing needed for ${pairedPeer.displayName}. Scan their First time QR.")
                return
            }

            Log.i("FlipViewModel", "[HOTSPOT] Foreground tap on paired peer: ${pairedPeer.displayName} (${pairedPeer.shortCode})")
            autoJoinManager.startAutoJoin(
                pairedPeer = pairedPeer,
                port = target.port,
                onSuccess = { remote ->
                    bleService.stopScanning()
                    _remoteDevice.value = remote
                    _role.value = "RECEIVER"
                    _connectionState.value = ConnectionState.CONNECTED
                    FlipTransferForegroundService.start(context, remote, "RECEIVER")
                    showToast("Connected to ${remote.name}")
                    checkAndQueuePreSelected()
                }
            )
            return
        }

        // Direct connect over local network if already on same Wi-Fi
        viewModelScope.launch(Dispatchers.IO) {
            transferService.connectToDevice(
                remoteIp = target.ip,
                remotePort = target.port,
                localDevice = currentLocal,
                onSuccess = { remote ->
                    bleService.stopScanning()
                    _remoteDevice.value = remote
                    _role.value = "RECEIVER"
                    _connectionState.value = ConnectionState.CONNECTED
                    FlipTransferForegroundService.start(context, remote, "RECEIVER")
                    showToast("Connected to ${remote.name}")
                    checkAndQueuePreSelected()
                },
                onError = { err ->
                    Log.e("FlipViewModel", "Failed to connect: $err")
                    _connectionState.value = ConnectionState.SEARCHING
                    showToast("Connection failed. Check that both phones are on the same network.")
                }
            )
        }
    }

    /**
     * Connect directly via manual IP input (bypass BLE)
     */
    fun connectManually(ip: String, port: Int) {
        _connectionState.value = ConnectionState.CONNECTING
        val currentLocal = _localDevice.value ?: return

        viewModelScope.launch(Dispatchers.IO) {
            transferService.connectToDevice(
                remoteIp = ip,
                remotePort = port,
                localDevice = currentLocal,
                onSuccess = { remote ->
                    bleService.stopScanning()
                    bleService.stopAdvertising()
                    _remoteDevice.value = remote
                    _role.value = "RECEIVER"
                    _connectionState.value = ConnectionState.CONNECTED
                    FlipTransferForegroundService.start(context, remote, "RECEIVER")
                    showToast("Connected to ${remote.name}")
                    checkAndQueuePreSelected()
                },
                onError = { err ->
                    performDisconnectCleanup("error", "Connection failed. Check that both phones are on the same network.")
                }
            )
        }
    }

    /**
     * Graceful disconnection on button click
     */
    fun disconnect() {
        val remote = _remoteDevice.value
        if (remote != null) {
            viewModelScope.launch(Dispatchers.IO) {
                transferService.notifyDisconnect(remote.ip, remote.port)
            }
        }
        performDisconnectCleanup("user", "Disconnected")
    }

    fun performDisconnectCleanup(trigger: String = "user", reasonToast: String? = null) {
        if (!isCleaningUp.compareAndSet(false, true)) {
            Log.d("FlipLink", "Cleanup already in progress; ignoring duplicate trigger: $trigger")
            return
        }
        Log.i("FlipLink", "Cleanup triggered by: $trigger")

        directSenderTimeoutJob?.cancel()
        directSenderTimeoutJob = null
        groupLostDebounceJob?.cancel()
        groupLostDebounceJob = null

        // 1. Send BYE message to peer first (best effort, 500 ms timeout) if not triggered by BYE
        if (!trigger.equals("BYE", ignoreCase = true)) {
            val ownName = _localDevice.value?.name ?: DeviceIdentity.get(context).displayName
            try {
                runBlocking {
                    withTimeoutOrNull(500L) {
                        withContext(Dispatchers.IO) {
                            handshakeClient?.sendBye(ownName)
                            FlipTransferForegroundService.sendBye(ownName)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("FlipLink", "Failed to send BYE during cleanup: ${e.message}")
            }
        }

        // 2. Wi-Fi Direct Host stop (safe to repeat)
        try {
            wifiDirectHost.stop()
            Log.d("FlipViewModel", "[CLEANUP] wifiDirectHost stopped")
        } catch (e: Exception) {
            Log.w("FlipViewModel", "[CLEANUP] Error stopping wifiDirectHost: ${e.message}")
        }

        // 3. Wi-Fi Direct Client leave (safe to repeat)
        try {
            wifiDirectClient.leave()
            Log.d("FlipViewModel", "[CLEANUP] wifiDirectClient left group")
        } catch (e: Exception) {
            Log.w("FlipViewModel", "[CLEANUP] Error leaving wifiDirectClient: ${e.message}")
        }

        // 4. Receiver HandshakeClient close (safe to repeat)
        synchronized(this) {
            try {
                handshakeClient?.close()
                Log.d("FlipViewModel", "[CLEANUP] handshakeClient closed")
            } catch (e: Exception) {
                Log.w("FlipViewModel", "[CLEANUP] Error closing handshakeClient: ${e.message}")
            } finally {
                handshakeClient = null
            }
        }

        // 5. BLE stop scanning and advertising (safe to repeat)
        try {
            bleService.stopScanning()
            Log.d("FlipViewModel", "[CLEANUP] bleService stopped scanning")
        } catch (e: Exception) {
            Log.w("FlipViewModel", "[CLEANUP] Error stopping BLE scanning: ${e.message}")
        }
        try {
            bleService.stopAdvertising()
            Log.d("FlipViewModel", "[CLEANUP] bleService stopped advertising")
        } catch (e: Exception) {
            Log.w("FlipViewModel", "[CLEANUP] Error stopping BLE advertising: ${e.message}")
        }

        // 6. Control-channel server teardown (safe to repeat)
        try {
            FlipTransferForegroundService.teardownFirstTimeConnection()
            Log.d("FlipViewModel", "[CLEANUP] Control channel torn down")
        } catch (e: Exception) {
            Log.w("FlipViewModel", "[CLEANUP] Error tearing down control channel: ${e.message}")
        }

        // 7. HTTP server stop (safe to repeat)
        try {
            httpServerService?.stopServer()
            Log.d("FlipViewModel", "[CLEANUP] httpServerService stopped")
        } catch (e: Exception) {
            Log.w("FlipViewModel", "[CLEANUP] Error stopping HTTP server: ${e.message}")
        } finally {
            httpServerService = null
            _isServerRunning.value = false
        }

        // 8. Foreground transfer service stop (safe to repeat)
        try {
            FlipTransferForegroundService.stop(context)
        } catch (e: Exception) {
            Log.w("FlipViewModel", "[CLEANUP] Error stopping FlipTransferForegroundService: ${e.message}")
        }

        ConnectionSessionManager.updateDisconnected()
        _pendingIncomingRequest.value = null
        _pendingShareRequest.value = null

        // Cancel all pending or active transfers
        viewModelScope.launch(Dispatchers.Default) {
            synchronized(cancelledTransferIds) {
                _transferQueue.value.forEach {
                    if (it.status == TransferStatus.SENDING || it.status == TransferStatus.QUEUED || it.status == TransferStatus.PAUSED) {
                        cancelledTransferIds.add(it.id)
                    }
                }
            }
            _transferQueue.value = _transferQueue.value.map {
                if (it.status == TransferStatus.SENDING || it.status == TransferStatus.QUEUED || it.status == TransferStatus.PAUSED) {
                    it.copy(status = TransferStatus.CANCELLED)
                } else {
                    it
                }
            }
        }

        _remoteDevice.value = null
        _connectionState.value = ConnectionState.IDLE
        _role.value = null
        _directSenderPayloadUri.value = null

        if (reasonToast != null) {
            showToast(reasonToast)
        }

        isCleaningUp.set(false)
        Log.i("FlipViewModel", "[CLEANUP] performDisconnectCleanup() completed successfully")
    }

    fun resetToHome() {
        performDisconnectCleanup("user", null)
    }

    /**
     * Send plain text to the peer
     */
    fun sendText(text: String) {
        val remote = _remoteDevice.value ?: return
        if (text.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            transferService.sendText(remote.ip, remote.port, text) { success, err ->
                if (success) {
                    showToast("Text sent successfully")
                } else {
                    showToast("Failed to send text: $err")
                }
            }
        }
    }

    /**
     * Choose file and append to the send queue
     */
    fun addFileToTransferQueue(uri: Uri, fileName: String, fileSize: Long) {
        val transferId = UUID.randomUUID().toString()
        val newItem = TransferItem(
            id = transferId,
            fileName = fileName,
            fileUri = uri.toString(),
            fileSize = fileSize,
            status = TransferStatus.QUEUED,
            isIncoming = false
        )

        _transferQueue.value = _transferQueue.value + newItem
        showToast("Added $fileName to queue")

        triggerQueueProcessing()
    }

    private fun triggerQueueProcessing() {
        synchronized(this) {
            if (!isQueueRunning) {
                isQueueRunning = true
                queueJob = viewModelScope.launch(Dispatchers.IO) {
                    runQueueProcessor()
                }
            }
        }
    }

    private suspend fun runQueueProcessor() {
        try {
            while (true) {
                // Find first queued sending item
                val queue = _transferQueue.value
                val nextItem = queue.firstOrNull { it.status == TransferStatus.QUEUED }
                if (nextItem == null) {
                    isQueueRunning = false
                    break
                }

                // Execute transfer
                processSendItem(nextItem)
            }
        } finally {
            isQueueRunning = false
        }
    }

    private suspend fun processSendItem(item: TransferItem) {
        val remote = _remoteDevice.value ?: run {
            updateItemStatus(item.id, TransferStatus.FAILED, "No connected device")
            return
        }

        // Before uploading, probe TCP reachability with 2s timeout
        val isReachable = withContext(Dispatchers.IO) {
            try {
                java.net.Socket().use { probeSocket ->
                    probeSocket.connect(java.net.InetSocketAddress(remote.ip, remote.port), 2000)
                }
                true
            } catch (e: Exception) {
                Log.e(
                    "FlipLink",
                    "Pre-upload check failed to peer .${com.example.service.control.ControlChannelConstants.lastOctet(remote.ip)}:${remote.port}: ${e.message}"
                )
                false
            }
        }
        if (!isReachable) {
            withContext(Dispatchers.Main) {
                showToast("Can't reach the other phone. Try again.")
            }
            updateItemStatus(item.id, TransferStatus.FAILED, "Can't reach the other phone. Try again.")
            if (!isControlChannelAlive()) {
                viewModelScope.launch(Dispatchers.Main) {
                    performDisconnectCleanup("error", "Connection lost")
                }
            }
            return
        }

        // Update status to SENDING
        updateItemStatus(item.id, TransferStatus.SENDING)

        // 1. Perform Resume Handshake (Inquire Offset)
        var offset = 0L
        try {
            offset = transferService.getReceiverOffset(remote.ip, remote.port, item.id, item.fileName)
            Log.d("FlipViewModel", "Handshake succeeded. Resuming from offset: $offset")
        } catch (e: Exception) {
            Log.e("FlipViewModel", "Handshake failed: ${e.message}")
            updateItemStatus(item.id, TransferStatus.FAILED, "Handshake failed: ${e.message}")
            if (isSocketError(e) && !isControlChannelAlive()) {
                viewModelScope.launch(Dispatchers.Main) {
                    performDisconnectCleanup("error", "Connection lost")
                }
            }
            return
        }

        // 2. Begin Stream Upload
        val completer = CompletableDeferred<Unit>()
        
        transferService.uploadFile(
            remoteIp = remote.ip,
            remotePort = remote.port,
            transferId = item.id,
            fileName = item.fileName,
            fileUriStr = item.fileUri ?: "",
            offset = offset,
            totalSize = item.fileSize,
            cancelCheck = {
                synchronized(cancelledTransferIds) {
                    cancelledTransferIds.contains(item.id)
                }
            },
            onProgress = { bytesTransferred ->
                if (!AppForegroundTracker.isAppInForeground) {
                    TransferNotificationManager.showTransferProgressNotification(
                        context = context,
                        transferId = item.id,
                        fileName = item.fileName,
                        bytesTransferred = bytesTransferred,
                        totalBytes = item.fileSize,
                        isIncoming = false
                    )
                }
                updateItemProgress(item.id, bytesTransferred)
            },
            onSuccess = {
                if (!AppForegroundTracker.isAppInForeground) {
                    TransferNotificationManager.showTransferCompletedNotification(
                        context = context,
                        transferId = item.id,
                        fileName = item.fileName,
                        filePath = null,
                        isIncoming = false
                    )
                }
                updateItemStatus(item.id, TransferStatus.COMPLETE)
                
                // Clean up if it is a temporary APK
                if (item.fileUri?.contains("/Temp/APK") == true) {
                    val keep = context.getSharedPreferences("flip_prefs", android.content.Context.MODE_PRIVATE).getBoolean("keep_extracted_apk", false)
                    if (!keep) {
                        try {
                            val path = Uri.parse(item.fileUri).path
                            if (path != null) {
                                val tempFile = java.io.File(path)
                                if (tempFile.exists()) {
                                    tempFile.delete()
                                    Log.d("FlipViewModel", "Cleaned up temporary APK: ${tempFile.absolutePath}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("FlipViewModel", "Failed to clean up temporary APK: ${e.message}")
                        }
                    }
                } else if (item.fileUri?.contains("/TempTransfer") == true) {
                    try {
                        val path = Uri.parse(item.fileUri).path
                        if (path != null) {
                            val tempFile = java.io.File(path)
                            if (tempFile.exists()) {
                                tempFile.delete()
                                Log.d("FlipViewModel", "Cleaned up temporary transfer file: ${tempFile.absolutePath}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FlipViewModel", "Failed to clean up temporary transfer file: ${e.message}")
                    }
                }
                
                completer.complete(Unit)
            },
            onError = { errMsg ->
                // Check if it was manually paused
                val currentStatus = _transferQueue.value.firstOrNull { it.id == item.id }?.status
                if (currentStatus == TransferStatus.PAUSED) {
                    // Let paused state stay
                } else {
                    if (!AppForegroundTracker.isAppInForeground) {
                        TransferNotificationManager.showTransferFailedNotification(
                            context = context,
                            transferId = item.id,
                            fileName = item.fileName,
                            errorMessage = errMsg
                        )
                    }
                    updateItemStatus(item.id, TransferStatus.FAILED, errMsg)
                    if (isSocketErrorMessage(errMsg) && !isControlChannelAlive()) {
                        viewModelScope.launch(Dispatchers.Main) {
                            performDisconnectCleanup("error", "Connection lost")
                        }
                    }
                }
                completer.complete(Unit)
            }
        )

        completer.await()
    }

    private fun isSocketError(e: Throwable): Boolean {
        return e is java.net.SocketException ||
                e is java.net.SocketTimeoutException ||
                e is java.net.ConnectException ||
                isSocketErrorMessage(e.message ?: "")
    }

    private fun isSocketErrorMessage(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("socket") ||
                lower.contains("connection refused") ||
                lower.contains("connection reset") ||
                lower.contains("broken pipe") ||
                lower.contains("timed out") ||
                lower.contains("timeout") ||
                lower.contains("econnrefused") ||
                lower.contains("econnreset") ||
                lower.contains("network unreachable") ||
                lower.contains("no route to host") ||
                lower.contains("unexpected end of stream") ||
                lower.contains("connection abort")
    }

    private fun updateItemStatus(id: String, status: TransferStatus, errMsg: String? = null) {
        _transferQueue.value = _transferQueue.value.map {
            if (it.id == id) it.copy(status = status, errorMessage = errMsg) else it
        }
    }

    private fun updateItemProgress(id: String, bytesTransferred: Long) {
        _transferQueue.value = _transferQueue.value.map {
            if (it.id == id) it.copy(bytesTransferred = bytesTransferred) else it
        }
    }

    /**
     * Pause a sending transfer
     */
    fun pauseTransfer(id: String) {
        synchronized(cancelledTransferIds) {
            cancelledTransferIds.add(id)
        }
        _transferQueue.value = _transferQueue.value.map {
            if (it.id == id) it.copy(status = TransferStatus.PAUSED) else it
        }
        showToast("Transfer paused")
    }

    /**
     * Resume a sending transfer
     */
    fun resumeTransfer(id: String) {
        synchronized(cancelledTransferIds) {
            cancelledTransferIds.remove(id)
        }
        _transferQueue.value = _transferQueue.value.map {
            if (it.id == id) it.copy(status = TransferStatus.QUEUED, errorMessage = null) else it
        }
        showToast("Resuming transfer...")
        triggerQueueProcessing()
    }

    /**
     * Cancel an active or queued transfer
     */
    fun cancelTransfer(id: String) {
        val item = _transferQueue.value.firstOrNull { it.id == id } ?: return
        
        synchronized(cancelledTransferIds) {
            cancelledTransferIds.add(id)
        }

        _transferQueue.value = _transferQueue.value.map {
            if (it.id == id) it.copy(status = TransferStatus.CANCELLED) else it
        }

        // Notify receiver for cleanup
        val remote = _remoteDevice.value
        if (remote != null) {
            viewModelScope.launch(Dispatchers.IO) {
                transferService.notifyCancellation(remote.ip, remote.port, id)
            }
        }

        // If it's incoming, clean up the local disk files
        if (item.isIncoming) {
            StorageService.cancelTransfer(item.id, item.fileName)
        }

        showToast("Transfer cancelled")
    }

    private fun showToast(msg: String) {
        _toastMessage.value = msg
    }

    // --- BLE DISCOVERY LISTENER ---
    override fun onDeviceDiscovered(device: Device) {
        val currentList = _discoveredDevices.value
        val now = System.currentTimeMillis()
        val index = currentList.indexOfFirst { it.id == device.id }
        if (index >= 0) {
            val updated = currentList.toMutableList()
            updated[index] = device.copy(lastSeen = now)
            _discoveredDevices.value = updated
        } else {
            _discoveredDevices.value = currentList + device.copy(lastSeen = now)
        }
        autoJoinManager.onBlePeerDiscovered(
            bleDevice = device,
            onReadyToConnect = { remoteDevice ->
                connectToDiscoveredDevice(remoteDevice)
            }
        )
    }

    override fun onDiscoveryError(message: String) {
        Log.e("BLEDiscovery", message)
        _bleError.value = message
    }

    // --- HTTP SERVER LISTENER CALLBACKS ---
    override fun onDeviceConnected(device: Device) {
        viewModelScope.launch(Dispatchers.Main) {
            // Stop scanning/advertising
            bleService.stopScanning()
            bleService.stopAdvertising()
            _remoteDevice.value = device
            if (_role.value == null) _role.value = "SENDER"
            _connectionState.value = ConnectionState.CONNECTED
            FlipTransferForegroundService.start(context, device, _role.value ?: "SENDER")
            HotspotCredentialStore.saveKnownReceiver(context, device.id, device.name)
            showToast("Connected to ${device.name}")
            checkAndQueuePreSelected()
        }
    }

    override fun onDeviceDisconnected() {
        viewModelScope.launch(Dispatchers.Main) {
            performDisconnectCleanup("heartbeat", "Remote device disconnected")
        }
    }

    override fun onTextReceived(text: String) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Flip Shared Text", text)
                clipboard.setPrimaryClip(clip)
                showToast("Received: Copied to clipboard!")
            } catch (e: Exception) {
                showToast("Received text, failed to copy: ${e.message}")
            }
        }
    }

    override fun onIncomingTransferRequest(transferId: String, fileName: String, fileSize: Long): Boolean {
        val sender = _remoteDevice.value
        val senderName = sender?.name ?: "Remote Peer"

        if (AppForegroundTracker.isAppInForeground) {
            _pendingIncomingRequest.value = IncomingFileRequest(
                transferId = transferId,
                fileName = fileName,
                fileSize = fileSize,
                senderName = senderName
            )
        } else {
            TransferNotificationManager.showIncomingRequestNotification(
                context = context,
                transferId = transferId,
                senderDevice = sender,
                fileName = fileName,
                fileSize = fileSize
            )
        }

        val approved = runBlocking {
            FlipTransferForegroundService.awaitTransferApproval(transferId)
        }

        if (_pendingIncomingRequest.value?.transferId == transferId) {
            _pendingIncomingRequest.value = null
        }

        return approved
    }

    override fun onFileTransferStarted(transferId: String, fileName: String, fileSize: Long, isIncoming: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            // Check if item already exists
            val existing = _transferQueue.value.any { it.id == transferId }
            if (!existing) {
                val item = TransferItem(
                    id = transferId,
                    fileName = fileName,
                    fileSize = fileSize,
                    status = TransferStatus.SENDING,
                    isIncoming = true
                )
                _transferQueue.value = _transferQueue.value + item
            }
        }
    }

    override fun onFileTransferProgress(transferId: String, bytesTransferred: Long) {
        val item = _transferQueue.value.firstOrNull { it.id == transferId }
        val total = item?.fileSize ?: 0L
        val name = item?.fileName ?: "File"
        if (!AppForegroundTracker.isAppInForeground) {
            TransferNotificationManager.showTransferProgressNotification(
                context = context,
                transferId = transferId,
                fileName = name,
                bytesTransferred = bytesTransferred,
                totalBytes = total,
                isIncoming = true
            )
        }
        viewModelScope.launch(Dispatchers.Main) {
            updateItemProgress(transferId, bytesTransferred)
        }
    }

    override fun onFileTransferCompleted(transferId: String, filePath: String) {
        val item = _transferQueue.value.firstOrNull { it.id == transferId }
        val name = item?.fileName ?: "File"

        try {
            com.example.repository.FileRepository.onFileReceived(context, java.io.File(filePath))
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(filePath),
                null
            ) { path, uri ->
                Log.d("FlipViewModel", "Successfully scanned and saved file in MediaStore: $path -> $uri")
                com.example.repository.FileRepository.onFileReceived(context, java.io.File(filePath))
            }
        } catch (e: Exception) {
            Log.e("FlipViewModel", "Failed to scan received file: ${e.message}")
        }

        if (!AppForegroundTracker.isAppInForeground) {
            TransferNotificationManager.showTransferCompletedNotification(
                context = context,
                transferId = transferId,
                fileName = name,
                filePath = filePath,
                isIncoming = true
            )
        }

        viewModelScope.launch(Dispatchers.Main) {
            updateItemStatus(transferId, TransferStatus.COMPLETE)
            if (item != null) {
                showToast("Received and saved: ${item.fileName}")
            } else {
                showToast("File transfer completed")
            }
        }
    }

    override fun onFileTransferFailed(transferId: String, errorMessage: String) {
        val item = _transferQueue.value.firstOrNull { it.id == transferId }
        val name = item?.fileName ?: "File"
        if (!AppForegroundTracker.isAppInForeground) {
            TransferNotificationManager.showTransferFailedNotification(
                context = context,
                transferId = transferId,
                fileName = name,
                errorMessage = errorMessage
            )
        }

        viewModelScope.launch(Dispatchers.Main) {
            updateItemStatus(transferId, TransferStatus.FAILED, errorMessage)
            showToast("File transfer failed: $errorMessage")
        }
    }

    override fun onRemoteCancelled(transferId: String) {
        FlipTransferForegroundService.cancelPendingRequest(transferId)
        TransferNotificationManager.cancelTransferNotification(context, transferId)
        if (_pendingIncomingRequest.value?.transferId == transferId) {
            _pendingIncomingRequest.value = null
        }

        viewModelScope.launch(Dispatchers.Main) {
            val item = _transferQueue.value.firstOrNull { it.id == transferId }
            updateItemStatus(transferId, TransferStatus.CANCELLED)
            if (item != null) {
                StorageService.cancelTransfer(transferId, item.fileName)
            }
            showToast("Transfer cancelled by sender")
        }
    }

    override fun onCleared() {
        super.onCleared()
        performDisconnectCleanup("user", null)
    }

    fun updateLocalIpAddress(newIp: String) {
        val current = _localDevice.value ?: return
        val updated = current.copy(ip = newIp)
        _localDevice.value = updated
        
        // If we are in SENDER mode and advertising, restart advertising with the new IP!
        if (_role.value == "SENDER" && _connectionState.value == ConnectionState.SEARCHING) {
            viewModelScope.launch(Dispatchers.IO) {
                bleService.stopAdvertising()
                bleService.startAdvertising(
                    localIp = updated.ip,
                    port = updated.port,
                    deviceId = updated.id,
                    deviceName = updated.name
                )
            }
        }
    }

    fun handleIncomingShare(uris: List<Uri>, text: String?, sourceAppName: String = "the source app") {
        viewModelScope.launch(Dispatchers.Main) {
            // Check if text is a Play Store link or bare package ID of an installed app
            if (!text.isNullOrBlank() && uris.isEmpty()) {
                val playStoreRegex = """(?:https?://)?play\.google\.com/store/apps/details\?id=([a-zA-Z0-9._]+)""".toRegex()
                val barePkgRegex = """^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]+)+$""".toRegex()
                val trimmed = text.trim()
                val pkgMatch = playStoreRegex.find(trimmed)?.groupValues?.get(1)
                    ?: if (barePkgRegex.matches(trimmed)) trimmed else null

                if (pkgMatch != null) {
                    val installedApp = StorageService.getInstalledAppItem(context, pkgMatch)
                    if (installedApp != null) {
                        _appSharePromptState.value = AppShareOption(installedApp, text)
                        return@launch
                    }
                }
            }

            val items = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try {
                        var name = "unnamed_file"
                        var size = 0L

                        // 1. Get name and size using OpenableColumns without assuming a file path
                        try {
                            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                if (cursor.moveToFirst()) {
                                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: "unnamed_file"
                                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                                }
                            }
                        } catch (e: Exception) {
                            if (e is SecurityException || e is java.io.FileNotFoundException || e is IllegalArgumentException) {
                                throw e
                            }
                        }

                        if (size <= 0L) {
                            try {
                                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                    val stat = pfd.statSize
                                    if (stat > 0L) size = stat
                                }
                            } catch (e: Exception) {
                                if (e is SecurityException || e is java.io.FileNotFoundException || e is IllegalArgumentException) {
                                    throw e
                                }
                            }
                        }

                        // 2. Open via openInputStream and inspect for AndroidManifest.xml if extension is missing
                        var hasManifest = false
                        try {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                if (!name.endsWith(".apk", ignoreCase = true) && !name.endsWith(".apks", ignoreCase = true)) {
                                    try {
                                        java.util.zip.ZipInputStream(stream.buffered()).use { zis ->
                                            var entry = zis.nextEntry
                                            var count = 0
                                            while (entry != null && count < 30) {
                                                if (entry.name == "AndroidManifest.xml" || entry.name.endsWith("/AndroidManifest.xml")) {
                                                    hasManifest = true
                                                    break
                                                }
                                                count++
                                                entry = zis.nextEntry
                                            }
                                        }
                                    } catch (_: Exception) {
                                        // Non-zip file
                                    }
                                }
                            } ?: throw java.io.FileNotFoundException("Could not open input stream")
                        } catch (e: Exception) {
                            if (e is SecurityException || e is java.io.FileNotFoundException || e is IllegalArgumentException) {
                                throw e
                            }
                        }

                        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        if ((mimeType == "application/vnd.android.package-archive" || hasManifest) &&
                            !name.endsWith(".apk", ignoreCase = true) && !name.endsWith(".apks", ignoreCase = true)
                        ) {
                            name = "$name.apk"
                        }

                        val category = when {
                            name.endsWith(".apk", ignoreCase = true) || name.endsWith(".apks", ignoreCase = true) -> "Apps"
                            mimeType.startsWith("image/") -> "Images"
                            mimeType.startsWith("video/") -> "Videos"
                            mimeType.startsWith("audio/") -> "Audio"
                            else -> "Documents"
                        }

                        LocalFileItem(
                            id = uri.hashCode().toLong(),
                            uri = uri,
                            name = name,
                            size = size,
                            mimeType = mimeType,
                            category = category
                        )
                    } catch (e: Exception) {
                        Log.e("FlipViewModel", "Error reading shared URI: $uri", e)
                        if (e is SecurityException || e is java.io.FileNotFoundException || e is IllegalArgumentException) {
                            withContext(Dispatchers.Main) {
                                showToast("Couldn't read this file from $sourceAppName. Try sharing it again.")
                            }
                        }
                        null
                    }
                }
            }

            if (items.isEmpty() && text.isNullOrBlank()) {
                return@launch
            }

            val session = ConnectionSessionManager.session.value
            val isConnected = session.isConnected || _connectionState.value == ConnectionState.CONNECTED
            val target = session.remoteDevice ?: _remoteDevice.value

            if (isConnected && target != null) {
                // Already connected: show lightweight confirmation sheet without restarting pairing
                _pendingShareRequest.value = PendingShareRequest(
                    items = items,
                    text = text,
                    targetDevice = target
                )
            } else {
                // Not connected: queue items as pending confirmation banner on HomeScreen
                _pendingPostOnboardingShare.value = PendingShareBannerData(
                    items = items,
                    text = text
                )
            }
        }
    }

    fun confirmPendingShare() {
        val req = _pendingShareRequest.value ?: return
        _pendingShareRequest.value = null
        viewModelScope.launch(Dispatchers.Main) {
            req.items.forEach { file ->
                addFileToTransferQueue(file.uri, file.name, file.size)
            }
            if (!req.text.isNullOrBlank()) {
                sendText(req.text)
            }
        }
    }

    fun cancelPendingShare() {
        _pendingShareRequest.value = null
    }

    fun importSharedFiles(uris: List<Uri>) {
        handleIncomingShare(uris, null)
    }

    fun handleScannedQr(
        qrResult: String,
        targetBlePeer: Device?,
        onSuccess: () -> Unit,
        onRequiresPlainWifiConfirmation: (WifiHotspotDetails, Device) -> Unit
    ) {
        if (qrResult.startsWith(HotspotPairingProtocol.SCHEME_FIRST)) {
            val payload = HotspotPairingProtocol.parseFirstTimeQrPayload(qrResult)
            if (payload == null) {
                showToast("Invalid First Time QR code.")
                return
            }
            // Verify embedded ID matches BLE peer being connected to
            if (targetBlePeer != null && payload.deviceId != targetBlePeer.id && !targetBlePeer.id.contains(payload.shortCode)) {
                showToast("That QR belongs to a different device")
                return
            }
            // Save to encrypted storage under peer's device ID
            HotspotCredentialStore.savePairedPeer(
                context,
                PairedPeer(
                    peerDeviceId = payload.deviceId,
                    displayName = payload.deviceName,
                    ssid = payload.ssid,
                    password = payload.password,
                    security = payload.security,
                    isHidden = payload.isHidden
                )
            )
            showToast("Paired with ${payload.deviceName} · ${payload.shortCode}")
            onSuccess()
            val peer = HotspotCredentialStore.getPairedPeer(context, payload.deviceId)
            if (peer != null) {
                autoJoinManager.startAutoJoin(
                    pairedPeer = peer,
                    port = targetBlePeer?.port ?: 8080,
                    onSuccess = { dev -> connectToDiscoveredDevice(dev) }
                )
            }
        } else if (qrResult.startsWith("WIFI:", ignoreCase = true)) {
            val details = HotspotPairingProtocol.parseWifiQr(qrResult)
            if (details != null && targetBlePeer != null) {
                onRequiresPlainWifiConfirmation(details, targetBlePeer)
            } else {
                showToast("Invalid Wi-Fi QR code.")
            }
        } else if (qrResult.startsWith("flip://")) {
            // Existing pairing QR behavior unchanged
            try {
                val ip = qrResult.substringAfter("ip=").substringBefore("&")
                val port = qrResult.substringAfter("port=").substringBefore("&").toIntOrNull() ?: 8080
                connectManually(ip, port)
                onSuccess()
            } catch (e: Exception) {
                showToast("Failed to parse pairing QR: ${e.message}")
            }
        } else {
            showToast("Unrecognized QR code.")
        }
    }

    fun savePlainWifiAndAutoJoin(details: WifiHotspotDetails, targetBlePeer: Device) {
        HotspotCredentialStore.savePairedPeer(
            context,
            PairedPeer(
                peerDeviceId = targetBlePeer.id,
                displayName = targetBlePeer.name,
                ssid = details.ssid,
                password = details.password,
                security = details.security,
                isHidden = details.isHidden
            )
        )
        showToast("Paired with ${targetBlePeer.name} · ${targetBlePeer.id.take(4)}")
        val peer = HotspotCredentialStore.getPairedPeer(context, targetBlePeer.id)
        if (peer != null) {
            autoJoinManager.startAutoJoin(
                pairedPeer = peer,
                port = targetBlePeer.port,
                onSuccess = { dev -> connectToDiscoveredDevice(dev) }
            )
        }
    }
}

