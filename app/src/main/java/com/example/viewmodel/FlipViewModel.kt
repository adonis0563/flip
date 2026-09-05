package com.example.viewmodel

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import android.net.wifi.p2p.WifiP2pDevice

enum class ConnectionState {
    IDLE,
    SEARCHING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

class FlipViewModel(application: Application) : AndroidViewModel(application), 
    HttpServerService.ServerListener, BleDiscoveryService.DiscoveryListener {

    private val context = getApplication<Application>().applicationContext
    private val transferService = TransferService(context)
    private val bleService = BleDiscoveryService(context, this)
    private var httpServerService: HttpServerService? = null
    private var _pendingTargetDevice: Device? = null

    private val _wifiDirectPeers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    private val wifiDirectService = WifiDirectService(context, object : WifiDirectService.DirectListener {
        override fun onPeerDiscovered(device: WifiP2pDevice) {
            val current = _wifiDirectPeers.value
            if (current.none { it.deviceAddress == device.deviceAddress }) {
                _wifiDirectPeers.value = current + device
            }
        }

        override fun onConnectionEstablished(groupOwnerIp: String) {
            if (_role.value == "SENDER") {
                val currentLocal = _localDevice.value ?: return
                val targetPort = _pendingTargetDevice?.port ?: 8080 // ✅ FIX: Use the ephemeral port advertised via BLE
                
                viewModelScope.launch(Dispatchers.IO) {
                    transferService.connectToDevice(
                        remoteIp = groupOwnerIp, // "192.168.49.1"
                        remotePort = targetPort, // ✅ FIX: Dynamic port, NOT hardcoded 8080
                        localDevice = currentLocal,
                        onSuccess = { remote ->
                            _pendingTargetDevice = null // ✅ FIX: Clean up
                            bleService.stopScanning()
                            _remoteDevice.value = remote
                            _connectionState.value = ConnectionState.CONNECTED
                            showToast("Connected to ${remote.name}")
                            checkAndQueuePreSelected()
                        },
                        onError = { err ->
                            _pendingTargetDevice = null // ✅ FIX: Clean up
                            Log.e("FlipViewModel", "Failed to connect: $err")
                            _connectionState.value = ConnectionState.SEARCHING
                            showToast("Connection failed: $err")
                        }
                    )
                }
            }
        }

        override fun onDisconnected() {
            performDisconnectCleanup()
        }

        override fun onDiscoveryFailed(message: String) {
            viewModelScope.launch(Dispatchers.Main) {
                Log.w("FlipViewModel", "Wi-Fi Direct discovery failed: $message")
                showToast(message)
                // In SENDER mode, the local HTTP server and QR pairing remain active.
                // Avoid resetting connection state so the sender session stays open for QR/manual connections.
                if (_role.value != "SENDER") {
                    _connectionState.value = ConnectionState.IDLE
                }
            }
        }
    })

    // UI state flows
    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _role = MutableStateFlow<String?>(null) // "SENDER" or "RECEIVER"
    val role: StateFlow<String?> = _role.asStateFlow()

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

    private val _showFileExplorer = MutableStateFlow(false)
    val showFileExplorer: StateFlow<Boolean> = _showFileExplorer.asStateFlow()

    // ✅ FIX: Signal to the UI that MediaStore has changed and files need to be re-queried
    private val _refreshTrigger = MutableSharedFlow<Unit>(replay = 0)
    val refreshTrigger: SharedFlow<Unit> = _refreshTrigger
    private var mediaObserver: ContentObserver? = null

    // State lock for queue running
    private var queueJob: Job? = null
    @Volatile
    private var isQueueRunning = false

    // Keep track of active transfers that should be cancelled/aborted
    private val cancelledTransferIds = mutableSetOf<String>()

    private val preSelectedFiles = java.util.Collections.synchronizedList(mutableListOf<LocalFileItem>())

    fun preSelectFiles(files: List<LocalFileItem>) {
        // ✅ FIX: Synchronize compound operations to prevent ConcurrentModificationException
        // or silent data loss if checkAndQueuePreSelected() runs concurrently.
        synchronized(preSelectedFiles) {
            preSelectedFiles.clear()
            preSelectedFiles.addAll(files)
        }
        showToast("Pre-selected ${files.size} files. They will transfer automatically when connected.")
    }

    private fun checkAndQueuePreSelected() {
        val filesToQueue = synchronized(preSelectedFiles) {
            val list = preSelectedFiles.toList()
            preSelectedFiles.clear()
            list
        }
        if (filesToQueue.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.Main) {
                filesToQueue.forEach { file ->
                    addFileToTransferQueue(file.uri, file.name, file.size)
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

        // Initialize local device profile
        val devId = UUID.randomUUID().toString().take(8)
        val devName = Build.MODEL ?: "Android Device"
        val localIp = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
        _localDevice.value = Device(id = devId, name = devName, ip = localIp, port = 8080)

        // ✅ FIX: Listen for global MediaStore changes to instantly update the file explorer
        mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                viewModelScope.launch {
                    _refreshTrigger.emit(Unit)
                }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"),
            true,
            mediaObserver!!
        )
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
        httpServerService?.stopServer()
        httpServerService = null

        _role.value = "SENDER"
        _connectionState.value = ConnectionState.SEARCHING
        _discoveredDevices.value = emptyList()
        _transferQueue.value = emptyList()

        bleService.startScanning()
        wifiDirectService.register()
        wifiDirectService.discoverPeers()
    }

    /**
     * RECEIVER: Tap "Receive" role initiation
     */
    fun startReceiverMode() {
        httpServerService?.stopServer()
        httpServerService = null

        _role.value = "RECEIVER"
        _connectionState.value = ConnectionState.SEARCHING
        _discoveredDevices.value = emptyList()
        _transferQueue.value = emptyList()

        wifiDirectService.register()
        wifiDirectService.discoverPeers()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Bind and start local HTTP server
                val server = HttpServerService(context, this@FlipViewModel)
                val port = server.startServer()
                
                // ✅ FIX: Double-check that we are still in RECEIVER mode before assigning, 
                // in case the user rapidly toggled to SENDER mode while this was starting.
                if (_role.value != "RECEIVER") {
                    server.stopServer() // Kill the ghost server immediately
                    return@launch
                }
                
                httpServerService = server
                _isServerRunning.value = true
                wifiDirectService.localHttpPort = port

                // Update local device port info
                val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
                val updatedLocal = _localDevice.value?.copy(ip = ip, port = port)
                _localDevice.value = updatedLocal

                // 2. Advertise over BLE
                if (updatedLocal != null) {
                    bleService.startAdvertising(
                        localIp = updatedLocal.ip,
                        port = updatedLocal.port,
                        deviceId = updatedLocal.id,
                        deviceName = updatedLocal.name
                    )
                }
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
     * Tapping a discovered sender device to connect
     */
    fun connectToDiscoveredDevice(target: Device) {
        _pendingTargetDevice = target
        _connectionState.value = ConnectionState.CONNECTING
        
        // ✅ ROBUSTNESS FIX: Multi-factor matching to prevent wrong-device connections
        val matchingPeer = _wifiDirectPeers.value.find { peer ->
            // Primary: Exact name match (most reliable)
            peer.deviceName.equals(target.name, ignoreCase = true) ||
            // Secondary: Name contains target name AND no other peer has the same name
            (peer.deviceName.contains(target.name, ignoreCase = true) && 
             _wifiDirectPeers.value.count { it.deviceName.contains(target.name, ignoreCase = true) } == 1)
        }
        
        if (matchingPeer != null) {
            // ✅ ROBUSTNESS FIX: Log the match for debugging
            Log.d("FlipViewModel", "Matched Wi-Fi Direct peer: ${matchingPeer.deviceName} (${matchingPeer.deviceAddress}) to BLE device: ${target.name} (${target.id})")
            wifiDirectService.connectToDevice(matchingPeer)
            showToast("Initiating Wi-Fi Direct connection to ${target.name}...")
        } else {
            Log.e("FlipViewModel", "Wi-Fi Direct peer not found for ${target.name}")
            _connectionState.value = ConnectionState.SEARCHING
            showToast("Wi-Fi Direct peer not found. Ensure Wi-Fi is turned on and both devices are nearby.")
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
                    _connectionState.value = ConnectionState.CONNECTED
                    showToast("Connected to ${remote.name}")
                    checkAndQueuePreSelected()
                },
                onError = { err ->
                    _connectionState.value = ConnectionState.IDLE
                    showToast("Failed to connect: $err")
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
        performDisconnectCleanup()
        showToast("Disconnected")
    }

    private fun performDisconnectCleanup() {
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

        bleService.stopScanning()
        bleService.stopAdvertising()
        wifiDirectService.unregister()
        httpServerService?.stopServer()
        httpServerService = null
        _isServerRunning.value = false

        _remoteDevice.value = null
        _connectionState.value = ConnectionState.IDLE
        _role.value = null
    }

    fun resetToHome() {
        performDisconnectCleanup()
    }

    fun toggleFileExplorer() {
        _showFileExplorer.value = !_showFileExplorer.value
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
                updateItemProgress(item.id, bytesTransferred)
            },
            onSuccess = {
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
                    updateItemStatus(item.id, TransferStatus.FAILED, errMsg)
                    
                    // ✅ FIX: If the error indicates a dropped connection, gracefully disconnect 
                    // instead of leaving the sender stuck in the "CONNECTED" state forever.
                    // ✅ FIX: Only drop connection on genuine network/socket errors. 
                    // Removed generic "failed" which incorrectly caught file-specific errors like "Failed to open file stream".
                    val isNetworkDrop = errMsg.contains("timeout", ignoreCase = true) || 
                                        errMsg.contains("reset", ignoreCase = true) ||
                                        errMsg.contains("closed", ignoreCase = true) ||
                                        errMsg.contains("unreachable", ignoreCase = true) ||
                                        errMsg.contains("failed to connect", ignoreCase = true)
                                        
                    if (isNetworkDrop) {
                        viewModelScope.launch(Dispatchers.Main) {
                            performDisconnectCleanup()
                            showToast("Connection lost: $errMsg")
                        }
                    }
                }
                completer.complete(Unit)
            }
        )

        completer.await()
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
        if (currentList.none { it.id == device.id }) {
            _discoveredDevices.value = currentList + device
        }
    }

    override fun onDiscoveryError(message: String) {
        if (message.contains("disabled", ignoreCase = true) || message.contains("not supported", ignoreCase = true)) {
            Log.w("BLEDiscovery", message)
        } else {
            Log.e("BLEDiscovery", message)
        }
        _bleError.value = message
    }

    // --- HTTP SERVER LISTENER CALLBACKS ---
    override fun onDeviceConnected(device: Device) {
        viewModelScope.launch(Dispatchers.Main) {
            // ✅ FIX: Enforce strict 1-to-1 connection. Reject new connections if already connected.
            if (_remoteDevice.value != null && _connectionState.value == ConnectionState.CONNECTED) {
                Log.w("FlipViewModel", "Rejected connection from ${device.name}: Already connected to another device.")
                showToast("Connection rejected: Already in a session.")
                return@launch
            }

            // Stop scanning/advertising
            bleService.stopScanning()
            bleService.stopAdvertising()
            _remoteDevice.value = device
            _connectionState.value = ConnectionState.CONNECTED
            showToast("Connected to ${device.name}")
            checkAndQueuePreSelected()
        }
    }

    override fun onDeviceDisconnected() {
        viewModelScope.launch(Dispatchers.Main) {
            performDisconnectCleanup()
            showToast("Remote device disconnected")
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
        viewModelScope.launch(Dispatchers.Main) {
            updateItemProgress(transferId, bytesTransferred)
        }
    }

    override fun onFileTransferCompleted(transferId: String, filePath: String) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(filePath),
                null
            ) { path, uri ->
                Log.d("FlipViewModel", "Successfully scanned and saved file in MediaStore: $path -> $uri")
            }
        } catch (e: Exception) {
            Log.e("FlipViewModel", "Failed to scan received file: ${e.message}")
        }

        viewModelScope.launch(Dispatchers.Main) {
            val item = _transferQueue.value.firstOrNull { it.id == transferId }
            updateItemStatus(transferId, TransferStatus.COMPLETE)
            if (item != null) {
                showToast("Received and saved: ${item.fileName}")
            } else {
                showToast("File transfer completed")
            }
        }
    }

    override fun onFileTransferFailed(transferId: String, errorMessage: String) {
        viewModelScope.launch(Dispatchers.Main) {
            updateItemStatus(transferId, TransferStatus.FAILED, errorMessage)
            
            // ✅ FIX: If the error indicates the sender's socket broke (Zombie Connection), 
            // gracefully disconnect instead of leaving the receiver stuck on the CONNECTED screen.
            // ✅ FIX: Only drop connection on genuine network/socket errors.
            val isNetworkDrop = errorMessage.contains("timeout", ignoreCase = true) || 
                                errorMessage.contains("reset", ignoreCase = true) ||
                                errorMessage.contains("closed", ignoreCase = true) ||
                                errorMessage.contains("unreachable", ignoreCase = true) ||
                                errorMessage.contains("failed to connect", ignoreCase = true) ||
                                errorMessage.contains("broken", ignoreCase = true)
                                
            if (isNetworkDrop) {
                performDisconnectCleanup()
                showToast("Connection lost: $errorMessage")
            } else {
                showToast("File transfer failed: $errorMessage")
            }
        }
    }

    override fun onRemoteCancelled(transferId: String) {
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
        mediaObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        super.onCleared()
        performDisconnectCleanup()
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

    fun importSharedFiles(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.Main) {
            val items = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try {
                        var name = "unnamed_file"
                        var size = 0L
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (cursor.moveToFirst()) {
                                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: "unnamed_file"
                                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                            }
                        }
                        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        val category = when {
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
                        Log.e("FlipViewModel", "Error parsing shared URI: $uri", e)
                        null
                    }
                }
            }

            if (items.isNotEmpty()) {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    // ✅ FIX: Only auto-send if we are the SENDER. Prevents connection drops when acting as Receiver.
                    if (_role.value == "SENDER") {
                        items.forEach { item ->
                            addFileToTransferQueue(item.uri, item.name, item.size)
                        }
                        showToast("Imported ${items.size} shared files to transfer queue")
                    } else {
                        showToast("Cannot send files while in Receive mode. Please disconnect first.")
                    }
                } else {
                    preSelectFiles(items)
                    startSenderMode()
                }
            }
        }
    }
}
