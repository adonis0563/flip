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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

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
    val connectionStateManager = ConnectionStateManager(context)
    val flipConnectionState = connectionStateManager.connectionState

    private val transferService = TransferService(context)
    private val bleService = BleDiscoveryService(context, this)
    private val wifiHotspotManager = WifiHotspotManager(context)
    private var currentSessionId: String? = null
    private var httpServerService: HttpServerService? = null

    // Persistent theme settings
    private val prefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
    private val _themeMode = MutableStateFlow(prefs.getString("theme_mode", "system") ?: "system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
    }

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

    val transferQueue: StateFlow<List<TransferItem>> = TransferManager.transferQueue

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _bleError = MutableStateFlow<String?>(null)
    val bleError: StateFlow<String?> = _bleError.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    fun createNewTransferSession(): String {
        val sessionId = UUID.randomUUID().toString().take(12)
        _activeSessionId.value = sessionId
        currentSessionId = sessionId
        return sessionId
    }

    fun invalidateCurrentSession() {
        val id = _activeSessionId.value ?: currentSessionId
        if (id != null) {
            QrSessionManager.invalidateSession(id)
            _activeSessionId.value = null
            currentSessionId = null
        }
    }

    // State lock for queue running
    private var queueJob: Job? = null
    @Volatile
    private var isQueueRunning = false

    // Keep track of active transfers that should be cancelled/aborted
    private val cancelledTransferIds = mutableSetOf<String>()

    private val preSelectedFiles = java.util.Collections.synchronizedList(mutableListOf<LocalFileItem>())

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
        if (filesToQueue.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.Main) {
                filesToQueue.forEach { file ->
                    addFileToTransferQueue(file.uri, file.name, file.size)
                }
            }
        }
    }

    init {
        // Initialize TransferManager and restore state
        TransferManager.init(context)
        _remoteDevice.value = TransferManager.remoteDevice.value

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

        // Observe ConnectionStateManager currentIp flow to update local device IP dynamically
        viewModelScope.launch {
            connectionStateManager.currentIp.collect { ip ->
                val current = _localDevice.value
                if (current != null && current.ip != ip) {
                    _localDevice.value = current.copy(ip = ip)
                }
            }
        }

        // Synchronize remote device updates with TransferManager
        viewModelScope.launch {
            _remoteDevice.collect { device ->
                TransferManager.setRemoteDevice(context, device)
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
        TransferManager.clearQueue(context)
        
        createNewTransferSession()

        connectionStateManager.updateNetworkStatus()
        connectionStateManager.setHasDiscoveredDevices(false)
        connectionStateManager.setPeerStatus(PeerConnectionStatus.IDLE)

        // Senders scan for Receiver advertisements in V2
        bleService.startScanning()
    }

    /**
     * RECEIVER: Tap "Receive" role initiation
     */
    fun startReceiverMode() {
        _role.value = "RECEIVER"
        _connectionState.value = ConnectionState.SEARCHING
        _discoveredDevices.value = emptyList()
        TransferManager.clearQueue(context)

        connectionStateManager.updateNetworkStatus()
        connectionStateManager.setHasDiscoveredDevices(false)
        connectionStateManager.setPeerStatus(PeerConnectionStatus.IDLE)

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

                // 2. Generate Session ID and Advertise over BLE
                val sessionId = createNewTransferSession()
                
                if (updatedLocal != null) {
                    bleService.startAdvertising(
                        protocolVersion = "1.0",
                        sessionId = sessionId,
                        deviceName = updatedLocal.name,
                        deviceType = "phone"
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
     * Tapping a discovered receiver device to connect over BLE GATT
     */
    fun connectToDiscoveredDevice(target: Device) {
        _connectionState.value = ConnectionState.CONNECTING
        connectionStateManager.setPeerStatus(PeerConnectionStatus.CONNECTED)
        showToast("Connecting to ${target.name} over BLE...")
        
        viewModelScope.launch(Dispatchers.IO) {
            bleService.connectToGatt(target)
        }
    }

    /**
     * Connect directly via manual IP input (bypass BLE)
     */
    fun connectManually(ip: String, port: Int) {
        _connectionState.value = ConnectionState.CONNECTING
        connectionStateManager.setPeerStatus(PeerConnectionStatus.CONNECTED)
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
                    connectionStateManager.setPeerStatus(PeerConnectionStatus.READY)
                    showToast("Connected to ${remote.name}")
                    checkAndQueuePreSelected()
                },
                onError = { err ->
                    _connectionState.value = ConnectionState.IDLE
                    connectionStateManager.setPeerStatus(PeerConnectionStatus.IDLE)
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
        invalidateCurrentSession()

        // Cancel all pending or active transfers
        TransferManager.cancelAllActiveTransfers(context)

        bleService.stopScanning()
        bleService.stopAdvertising()
        bleService.disconnectGatt()

        wifiHotspotManager.stopHotspot()
        wifiHotspotManager.disconnectWifi()

        httpServerService?.stopServer()
        httpServerService = null
        _isServerRunning.value = false

        _remoteDevice.value = null
        _connectionState.value = ConnectionState.IDLE
        _role.value = null

        connectionStateManager.setPeerStatus(PeerConnectionStatus.IDLE)
        connectionStateManager.setHasDiscoveredDevices(false)
        connectionStateManager.updateNetworkStatus()
    }

    fun resetToHome() {
        performDisconnectCleanup()
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
        TransferManager.addFileToTransferQueue(context, uri, fileName, fileSize)
        showToast("Added $fileName to queue")
    }

    private fun updateItemStatus(id: String, status: TransferStatus, errMsg: String? = null) {
        TransferManager.updateItemStatus(context, id, status, errMsg)
    }

    private fun updateItemProgress(id: String, bytesTransferred: Long) {
        TransferManager.updateItemProgress(context, id, bytesTransferred)
    }

    /**
     * Pause a sending transfer
     */
    fun pauseTransfer(id: String) {
        TransferManager.pauseTransfer(context, id)
        showToast("Transfer paused")
    }

    /**
     * Resume a sending transfer
     */
    fun resumeTransfer(id: String) {
        TransferManager.resumeTransfer(context, id)
        showToast("Resuming transfer...")
    }

    /**
     * Cancel an active or queued transfer
     */
    fun cancelTransfer(id: String) {
        TransferManager.cancelTransfer(context, id)
        showToast("Transfer cancelled")
    }

    private fun showToast(msg: String) {
        _toastMessage.value = msg
    }

    // --- BLE DISCOVERY LISTENER ---
    override fun onDeviceDiscovered(device: Device) {
        val currentList = _discoveredDevices.value
        if (currentList.none { it.id == device.id }) {
            val newList = currentList + device
            _discoveredDevices.value = newList
            connectionStateManager.setHasDiscoveredDevices(newList.isNotEmpty())
        }
    }

    override fun onDiscoveryError(message: String) {
        Log.e("BLEDiscovery", message)
        _bleError.value = message
    }

    override fun onSessionClaimed(sessionId: String) {
        Log.d("FlipViewModel", "onSessionClaimed: sessionId=$sessionId")
        bleService.stopAdvertising()
        
        viewModelScope.launch(Dispatchers.Main) {
            _connectionState.value = ConnectionState.CONNECTING
            showToast("Session claimed! Starting Hotspot...")
            
            wifiHotspotManager.startHotspot(object : WifiHotspotManager.HotspotListener {
                override fun onHotspotStarted(ssid: String, psw: String, ip: String) {
                    Log.d("FlipViewModel", "Hotspot started: SSID=$ssid, Password=$psw, IP=$ip")
                    val moshi = com.squareup.moshi.Moshi.Builder()
                        .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                        .build()
                    val pairingMap = mapOf(
                        "sessionId" to sessionId,
                        "ssid" to ssid,
                        "password" to psw,
                        "ip" to ip,
                        "port" to (httpServerService?.boundPort?.toDouble() ?: 8080.0)
                    )
                    val adapter = moshi.adapter(Map::class.java)
                    val pairingJson = adapter.toJson(pairingMap)
                    
                    bleService.sendPairingData(pairingJson)
                    showToast("Hotspot active. Waiting for sender to join...")
                }

                override fun onHotspotFailed(error: String) {
                    Log.e("FlipViewModel", "Failed to start hotspot: $error")
                    showToast("Failed to start hotspot: $error")
                    resetToHome()
                }
            })
        }
    }

    override fun onPairingDataReceived(pairingJson: String) {
        Log.d("FlipViewModel", "onPairingDataReceived: $pairingJson")
        try {
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(Map::class.java)
            val pairingMap = adapter.fromJson(pairingJson) ?: throw IllegalArgumentException("Invalid JSON")
            
            val ssid = pairingMap["ssid"] as String
            val password = pairingMap["password"] as String
            val ip = pairingMap["ip"] as String
            val port = (pairingMap["port"] as Double).toInt()
            
            showToast("Pairing data received! Joining Hotspot...")
            _connectionState.value = ConnectionState.CONNECTING
            
            wifiHotspotManager.joinWifi(ssid, password, ip, object : WifiHotspotManager.WifiJoinListener {
                override fun onJoined(joinedIp: String) {
                    Log.d("FlipViewModel", "Joined Hotspot. Performing HTTP Handshake with $joinedIp:$port...")
                    showToast("Connected to Wi-Fi. Performing Handshake...")
                    
                    val currentLocal = _localDevice.value ?: return
                    viewModelScope.launch(Dispatchers.IO) {
                        transferService.connectToDevice(
                            remoteIp = joinedIp,
                            remotePort = port,
                            localDevice = currentLocal,
                            onSuccess = { remote ->
                                bleService.stopScanning()
                                bleService.disconnectGatt()
                                
                                viewModelScope.launch(Dispatchers.Main) {
                                    _remoteDevice.value = remote
                                    _connectionState.value = ConnectionState.CONNECTED
                                    connectionStateManager.setPeerStatus(PeerConnectionStatus.READY)
                                    showToast("Connected to ${remote.name}!")
                                    checkAndQueuePreSelected()
                                }
                            },
                            onError = { err ->
                                Log.e("FlipViewModel", "Handshake failed: $err")
                                viewModelScope.launch(Dispatchers.Main) {
                                    showToast("Handshake failed: $err")
                                    resetToHome()
                                }
                            }
                        )
                    }
                }

                override fun onFailed(error: String) {
                    Log.e("FlipViewModel", "Failed to join Wi-Fi Hotspot: $error")
                    showToast("Failed to join Wi-Fi Hotspot: $error")
                    resetToHome()
                }
            })
        } catch (e: Exception) {
            Log.e("FlipViewModel", "Error parsing pairing data: ${e.message}")
            showToast("Pairing error: ${e.message}")
            resetToHome()
        }
    }

    // --- HTTP SERVER LISTENER CALLBACKS ---
    override fun onDeviceConnected(device: Device) {
        viewModelScope.launch(Dispatchers.Main) {
            // Stop scanning/advertising
            bleService.stopScanning()
            bleService.stopAdvertising()
            _remoteDevice.value = device
            _connectionState.value = ConnectionState.CONNECTED
            connectionStateManager.setPeerStatus(PeerConnectionStatus.READY)
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
            TransferManager.addIncomingTransfer(context, transferId, fileName, fileSize)
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
            val item = TransferManager.transferQueue.value.firstOrNull { it.id == transferId }
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
            showToast("File transfer failed: $errorMessage")
        }
    }

    override fun onRemoteCancelled(transferId: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val item = TransferManager.transferQueue.value.firstOrNull { it.id == transferId }
            updateItemStatus(transferId, TransferStatus.CANCELLED)
            if (item != null) {
                StorageService.cancelTransfer(transferId, item.fileName)
            }
            showToast("Transfer cancelled by sender")
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionStateManager.release()
        performDisconnectCleanup()
    }

    fun updateLocalIpAddress(newIp: String) {
        val current = _localDevice.value ?: return
        val updated = current.copy(ip = newIp)
        _localDevice.value = updated
        
        // If we are in RECEIVER mode and advertising, restart advertising with the new IP!
        if (_role.value == "RECEIVER" && _connectionState.value == ConnectionState.SEARCHING) {
            viewModelScope.launch(Dispatchers.IO) {
                bleService.stopAdvertising()
                val sessId = currentSessionId
                if (sessId != null) {
                    bleService.startAdvertising(
                        protocolVersion = "1.0",
                        sessionId = sessId,
                        deviceName = updated.name,
                        deviceType = "phone"
                    )
                }
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
                    items.forEach { item ->
                        addFileToTransferQueue(item.uri, item.name, item.size)
                    }
                    showToast("Imported ${items.size} shared files to transfer queue")
                } else {
                    preSelectFiles(items)
                    startSenderMode()
                }
            }
        }
    }
}
