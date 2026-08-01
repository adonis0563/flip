package com.example.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.example.model.Device
import java.nio.charset.StandardCharsets
import java.util.*

@SuppressLint("MissingPermission")
class BleDiscoveryService(
    private val context: Context,
    private val listener: DiscoveryListener
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    // GATT Server (Receiver role)
    private var bluetoothGattServer: BluetoothGattServer? = null
    private val connectedGattDevices = mutableSetOf<BluetoothDevice>()
    private var activeClaimedGattDevice: BluetoothDevice? = null
    private var activeSessionId: String? = null
    private var sessionState = SessionState.UNCLAIMED
    private var activePairingJson: String? = null
    @Volatile
    private var isCccdSubscribed = false
    @Volatile
    private var pendingPairingJson: String? = null
    @Volatile
    private var negotiatedMtu = 23

    // GATT Client (Sender role)
    private var bluetoothGatt: BluetoothGatt? = null
    private val reassembledBuffer = StringBuilder()

    // Simulation Registry listener
    private var simScanListener: ((SimulatedReceiver) -> Unit)? = null

    enum class SessionState {
        UNCLAIMED,
        CLAIMED,
        VALIDATED,
        ACTIVE,
        CLEANED_UP
    }

    interface DiscoveryListener {
        fun onDeviceDiscovered(device: Device)
        fun onDiscoveryError(message: String)
        fun onSessionClaimed(sessionId: String)
        fun onPairingDataReceived(pairingJson: String)
    }

    companion object {
        private const val TAG = "BleDiscoveryService"
        private const val MANUFACTURER_ID = 0xFFFF
        val SERVICE_UUID = UUID.fromString("0000f119-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID = UUID.fromString("0000f120-0000-1000-8000-00805f9b34fb")
        val CCCD_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled ?: false
    }

    /**
     * V2 RECEIVER: Start advertising session info and run GATT server
     */
    fun startAdvertising(protocolVersion: String, sessionId: String, deviceName: String, deviceType: String) {
        Log.d(TAG, "[BLE_STAGE] Start advertising: protocolVersion=$protocolVersion, sessionId=$sessionId, deviceName=$deviceName, deviceType=$deviceType")
        
        // Stop any previous advertising session FIRST before registering new session
        stopAdvertising()

        activeSessionId = sessionId
        sessionState = SessionState.UNCLAIMED
        activeClaimedGattDevice = null
        activePairingJson = null
        isCccdSubscribed = false
        pendingPairingJson = null
        negotiatedMtu = 23

        // 1. Register in Simulation Registry (so we always have a working demo fallback)
        val simulatedReceiver = SimulatedReceiver(
            sessionId = sessionId,
            deviceName = deviceName,
            deviceType = deviceType,
            protocolVersion = protocolVersion,
            onGattConnect = { onPairingDataReceived ->
                Log.d(TAG, "[SIM] Simulated GATT connect received on Receiver")
                sessionState = SessionState.CLAIMED
                stopAdvertising() // First-connect wins: stop simulated/real advertising
                listener.onSessionClaimed(sessionId)
                
                // Set up the push callback for pairing data
                this.activePairingJsonCallback = { json ->
                    Log.d(TAG, "[SIM] Pushing simulated pairing JSON back to Sender")
                    onPairingDataReceived(json)
                }
            }
        )
        BleSimulationRegistry.registerReceiver(simulatedReceiver)
        Log.d(TAG, "[BLE_STAGE] Registered receiver in simulation registry: sessionId=$sessionId")

        // 2. Real Native BLE Advertising Permission & State Check
        val missingPermissions = checkPermissions(isAdvertising = true)
        if (missingPermissions.isNotEmpty()) {
            val errMessage = "Denied required Bluetooth permission(s) before advertising: ${missingPermissions.joinToString(", ")}"
            Log.e(TAG, "[BLE_PERMISSIONS] Cannot start advertising: $errMessage")
            listener.onDiscoveryError("BLE Advertising Error: $errMessage")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled or supported. Running in BLE Simulation mode only.")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BLE advertiser not supported. Running in BLE Simulation mode only.")
            return
        }

        startGattServer()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true) // Must be connectable for GATT claim in V2!
            .build()

        val payload = encodePayloadV2(protocolVersion, sessionId, deviceName, deviceType)

        // Primary Advertisement Data: Service UUID + Service Data (fits in 28 bytes <= 31 byte legacy BLE limit)
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), payload)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        // Scan Response Data: Manufacturer payload
        val scanResponseData = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, payload)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "[BLE_STAGE] Advertising started successfully (Native BLE)")
            }

            override fun onStartFailure(errorCode: Int) {
                val msg = "Native BLE Adv failed: errorCode $errorCode"
                Log.e(TAG, "[BLE_STAGE] Advertising failed: $msg")
                // We do not fail hard because the simulation registry is still active and works!
            }
        }

        advertiser?.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback)
    }

    private var activePairingJsonCallback: ((String) -> Unit)? = null

    fun sendPairingData(pairingJson: String) {
        Log.d(TAG, "sendPairingData: size=${pairingJson.length}")
        activePairingJson = pairingJson
        sessionState = SessionState.ACTIVE

        // Trigger simulation callback if active
        activePairingJsonCallback?.invoke(pairingJson)
        activePairingJsonCallback = null

        // Trigger real native GATT notification/write
        synchronized(this) {
            if (isCccdSubscribed) {
                Log.d(TAG, "GATT Server: Client already subscribed to CCCD notifications. Sending pairing data immediately.")
                performPairingDataTransmission(pairingJson)
            } else {
                Log.d(TAG, "GATT Server: Client not yet subscribed to CCCD notifications. Buffering pairing data until CCCD descriptor write is acknowledged.")
                pendingPairingJson = pairingJson
            }
        }
    }

    private fun performPairingDataTransmission(pairingJson: String) {
        val server = bluetoothGattServer ?: return
        val activeDevice = activeClaimedGattDevice ?: return
        val service = server.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

        // Send pairing data fragmented over GATT
        val payloadBytes = pairingJson.toByteArray(StandardCharsets.UTF_8)
        val mtuChunkSize = (negotiatedMtu - 5).coerceIn(20, 500)
        
        Thread {
            try {
                var offset = 0
                val totalLength = payloadBytes.size
                var chunkIndex = 0
                val totalChunks = ((totalLength + mtuChunkSize - 1) / mtuChunkSize).coerceAtLeast(1)

                while (offset < totalLength) {
                    val size = (totalLength - offset).coerceAtLeast(0).coerceAtMost(mtuChunkSize)
                    val chunk = ByteArray(2 + size)
                    chunk[0] = chunkIndex.toByte()
                    chunk[1] = totalChunks.toByte()
                    System.arraycopy(payloadBytes, offset, chunk, 2, size)

                    characteristic.value = chunk
                    server.notifyCharacteristicChanged(activeDevice, characteristic, false)
                    
                    offset += size
                    chunkIndex++
                    Thread.sleep(40) // Small delay between notification chunks to avoid buffer overflow
                }
                Log.d(TAG, "Finished native BLE GATT pairing data transfer ($totalChunks chunks using MTU chunk size $mtuChunkSize)")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing native GATT pairing data chunks", e)
            }
        }.start()
    }

    fun stopAdvertising() {
        BleSimulationRegistry.unregisterReceiver(activeSessionId ?: "")
        try {
            if (advertiser != null && advertiseCallback != null) {
                advertiser?.stopAdvertising(advertiseCallback)
                Log.d(TAG, "BLE Advertisement stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE advertiser: ${e.message}")
        } finally {
            advertiseCallback = null
        }
        stopGattServer()
    }

    /**
     * V2 SENDER: Start scanning for active receivers
     */
    fun startScanning() {
        Log.d(TAG, "[BLE_STAGE] Start scanning")
        
        // 1. Scan in Simulation Registry
        simScanListener = { simReceiver ->
            Log.d(TAG, "[SIM] Discovered simulated receiver: ${simReceiver.deviceName}")
            // Create a custom device where IP is "0.0.0.0" (needs BLE connect) and ID carries sessionId
            val device = Device(
                id = "SIM_${simReceiver.sessionId}",
                name = simReceiver.deviceName,
                ip = "0.0.0.0",
                port = 0,
                deviceType = simReceiver.deviceType
            )
            Log.d(TAG, "[BLE_STAGE] Device discovered: id=${device.id}, name=${device.name}")
            listener.onDeviceDiscovered(device)
        }
        BleSimulationRegistry.registerScanListener(simScanListener!!)

        // 2. Native BLE Scanning Permission & State Check
        val missingPermissions = checkPermissions(isAdvertising = false)
        if (missingPermissions.isNotEmpty()) {
            val errMessage = "Denied required Bluetooth permission(s) before scanning: ${missingPermissions.joinToString(", ")}"
            Log.e(TAG, "[BLE_PERMISSIONS] Cannot start scanning: $errMessage")
            listener.onDiscoveryError("BLE Scanning Error: $errMessage")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled or supported. Running in BLE Simulation scanning mode only.")
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE Scanner not supported. Running in BLE Simulation scanning mode only.")
            return
        }

        stopScanning()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { handleScanResult(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                val msg = "Native BLE Scan failed: errorCode $errorCode"
                Log.e(TAG, "[BLE_STAGE] Scan failed: $msg")
            }
        }

        scanner?.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "[BLE_STAGE] Native BLE Scanning started")
    }

    fun stopScanning() {
        simScanListener?.let {
            BleSimulationRegistry.unregisterScanListener(it)
        }
        simScanListener = null

        try {
            if (scanner != null && scanCallback != null) {
                scanner?.stopScan(scanCallback)
                Log.d(TAG, "Native BLE Scanning stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native BLE scanner: ${e.message}")
        } finally {
            scanCallback = null
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val devAddr = result.device?.address ?: "UNKNOWN_ADDR"
        val devName = result.device?.name ?: result.scanRecord?.deviceName ?: "UNKNOWN_NAME"
        Log.d(TAG, "[BLE] ScanResult received: device=$devAddr ($devName), rssi=${result.rssi}")

        val record = result.scanRecord
        if (record == null) {
            Log.d(TAG, "[BLE] ScanResult REJECTED for $devAddr: scanRecord is null")
            return
        }

        // Try Service Data first (primary advertisement), then Manufacturer Data (scan response)
        var payloadBytes = record.getServiceData(ParcelUuid(SERVICE_UUID))
        if (payloadBytes == null) {
            payloadBytes = record.getManufacturerSpecificData(MANUFACTURER_ID)
        }

        if (payloadBytes == null) {
            val uuids = record.serviceUuids
            if (uuids != null && uuids.contains(ParcelUuid(SERVICE_UUID))) {
                Log.d(TAG, "[BLE] Advertisement with SERVICE_UUID detected without payload, registering: $devName ($devAddr)")
                val device = Device(
                    id = devAddr,
                    name = devName,
                    ip = "0.0.0.0",
                    port = 0,
                    deviceType = "phone"
                )
                listener.onDeviceDiscovered(device)
                return
            }
            Log.d(TAG, "[BLE] ScanResult REJECTED for $devAddr: missing service/manufacturer payload for SERVICE_UUID. Present UUIDs: ${record.serviceUuids}")
            return
        }
        
        val device = decodePayloadV2(payloadBytes, devAddr)
        if (device != null) {
            Log.d(TAG, "[BLE] Device discovered & accepted: id=${device.id}, name=${device.name}")
            listener.onDeviceDiscovered(device)
        } else {
            Log.w(TAG, "[BLE] ScanResult REJECTED for $devAddr: failed to decode V2 payload bytes (len=${payloadBytes.size})")
        }
    }

    private fun checkPermissions(isAdvertising: Boolean): List<String> {
        val missing = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val scanGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val connectGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val advertiseGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED

            Log.d(TAG, "[BLE_PERMISSIONS] Android 12+ Permission Check Before Operation (isAdvertising=$isAdvertising):")
            Log.d(TAG, "  BLUETOOTH_SCAN: $scanGranted")
            Log.d(TAG, "  BLUETOOTH_CONNECT: $connectGranted")
            Log.d(TAG, "  BLUETOOTH_ADVERTISE: $advertiseGranted")

            if (isAdvertising && !advertiseGranted) {
                missing.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (!isAdvertising && !scanGranted) {
                missing.add(android.Manifest.permission.BLUETOOTH_SCAN)
            }
            if (!connectGranted) {
                missing.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarseGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val locGranted = fineGranted || coarseGranted

            Log.d(TAG, "[BLE_PERMISSIONS] Android <12 Location Permission Check: $locGranted")

            if (!locGranted) {
                missing.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return missing
    }

    /**
     * V2 SENDER: Establish GATT Connection to claim session and receive pairing data
     */
    fun connectToGatt(targetDevice: Device) {
        Log.d(TAG, "[BLE_STAGE] Handshake started (GATT connect to target device=${targetDevice.id})")

        // 1. Simulation GATT Connection Handshake
        if (targetDevice.id.startsWith("SIM_")) {
            val sessionId = targetDevice.id.substringAfter("SIM_")
            val simReceiver = BleSimulationRegistry.activeAdvertisements[sessionId]
            if (simReceiver != null) {
                Thread {
                    try {
                        Thread.sleep(800) // Simulate connection delay
                        simReceiver.onGattConnect { json ->
                            // Simulate receiving the pairing data back
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    listener.onPairingDataReceived(json)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in onPairingDataReceived: ${e.message}", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Uncaught exception in SIM BLE thread: ${e.message}", e)
                    }
                }.start()
            } else {
                val err = "Simulated session no longer active"
                Log.e(TAG, "[BLE_STAGE] Handshake failed: $err")
                listener.onDiscoveryError(err)
            }
            return
        }

        // 2. Real Native GATT Connection Handshake
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            val err = "Bluetooth not available for GATT connection"
            Log.e(TAG, "[BLE_STAGE] Handshake failed: $err")
            listener.onDiscoveryError(err)
            return
        }

        try {
            val macAddress = targetDevice.id
            val device = bluetoothAdapter.getRemoteDevice(macAddress)
            
            reassembledBuffer.setLength(0) // Clear reassembly buffer

            bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    super.onConnectionStateChange(gatt, status, newState)
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "Native GATT connected. Discovering services...")
                        gatt?.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "Native GATT disconnected")
                        gatt?.close()
                        if (bluetoothGatt == gatt) {
                            bluetoothGatt = null
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    super.onServicesDiscovered(gatt, status)
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "Native GATT services discovered. Negotiating MTU...")
                        gatt?.requestMtu(512) // Request maximum MTU
                    } else {
                        listener.onDiscoveryError("Failed to discover GATT services")
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                    super.onMtuChanged(gatt, mtu, status)
                    Log.d(TAG, "Native GATT MTU size negotiated: $mtu (status=$status)")
                    
                    // Register notifications for pairing data
                    val service = gatt?.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        val notificationSet = gatt.setCharacteristicNotification(characteristic, true)
                        Log.d(TAG, "setCharacteristicNotification result: $notificationSet")
                        
                        // Enable local descriptor CCCD notifications
                        val descriptor = characteristic.getDescriptor(CCCD_DESCRIPTOR_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            val writeInitiated = gatt.writeDescriptor(descriptor)
                            Log.d(TAG, "Subscribed to Pairing Data characteristic notifications via writeDescriptor: $writeInitiated")
                        } else {
                            Log.e(TAG, "CCCD descriptor not found on characteristic")
                            listener.onDiscoveryError("CCCD descriptor not found on target characteristic")
                        }
                    } else {
                        listener.onDiscoveryError("Pairing characteristic not found on target device")
                    }
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                    super.onDescriptorWrite(gatt, descriptor, status)
                    if (descriptor?.uuid == CCCD_DESCRIPTOR_UUID) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.d(TAG, "GATT Client: CCCD descriptor write acknowledged by server. Notification subscription active.")
                        } else {
                            Log.e(TAG, "GATT Client: CCCD descriptor write failed with status $status")
                            listener.onDiscoveryError("Failed to subscribe to pairing data notifications (status $status)")
                        }
                    }
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                    super.onCharacteristicChanged(gatt, characteristic)
                    val data = characteristic?.value ?: return
                    if (data.size < 2) return

                    val chunkIndex = data[0].toInt() and 0xFF
                    val totalChunks = data[1].toInt() and 0xFF
                    val chunkPayload = String(data, 2, data.size - 2, StandardCharsets.UTF_8)

                    Log.d(TAG, "Received chunk $chunkIndex/$totalChunks")
                    reassembledBuffer.append(chunkPayload)

                    if (chunkIndex == totalChunks - 1) {
                        val fullPairingJson = reassembledBuffer.toString()
                        Log.d(TAG, "Completed reassembly of pairing data JSON")
                        Handler(Looper.getMainLooper()).post {
                            listener.onPairingDataReceived(fullPairingJson)
                        }
                        gatt?.disconnect()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed native GATT connection setup: ${e.message}")
            listener.onDiscoveryError("GATT connection failed: ${e.message}")
        }
    }

    fun disconnectGatt() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT connection: ${e.message}")
        } finally {
            bluetoothGatt = null
        }
    }

    /**
     * V2 GATT Server Implementation
     */
    private fun startGattServer() {
        if (bluetoothManager == null) return
        try {
            bluetoothGattServer = bluetoothManager.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                    super.onConnectionStateChange(device, status, newState)
                    if (device == null) return

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "GATT Server: Client connected: ${device.address}")
                        
                        // First-Connect Wins Claim Policy
                        synchronized(this@BleDiscoveryService) {
                            if (sessionState != SessionState.UNCLAIMED) {
                                Log.w(TAG, "Rejecting extra client ${device.address} since session is already claimed")
                                bluetoothGattServer?.cancelConnection(device)
                                return
                            }
                            
                            sessionState = SessionState.CLAIMED
                            activeClaimedGattDevice = device
                            connectedGattDevices.add(device)
                        }

                        // Stop BLE Advertising immediately without destroying GATT server
                        BleSimulationRegistry.unregisterReceiver(activeSessionId ?: "")
                        try {
                            if (advertiser != null && advertiseCallback != null) {
                                advertiser?.stopAdvertising(advertiseCallback)
                                Log.d(TAG, "BLE Advertisement stopped on first connect")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping advertiser: ${e.message}")
                        } finally {
                            advertiseCallback = null
                        }

                        Handler(Looper.getMainLooper()).post {
                            activeSessionId?.let { listener.onSessionClaimed(it) }
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "GATT Server: Client disconnected: ${device.address}")
                        connectedGattDevices.remove(device)
                        if (device == activeClaimedGattDevice) {
                            activeClaimedGattDevice = null
                            sessionState = SessionState.UNCLAIMED
                            isCccdSubscribed = false
                        }
                    }
                }

                override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
                    super.onMtuChanged(device, mtu)
                    Log.d(TAG, "GATT Server: MTU negotiated to $mtu for device ${device?.address}")
                    negotiatedMtu = mtu
                }

                override fun onDescriptorWriteRequest(
                    device: BluetoothDevice?,
                    requestId: Int,
                    descriptor: BluetoothGattDescriptor?,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
                    val isCccd = descriptor?.uuid == CCCD_DESCRIPTOR_UUID
                    if (isCccd) {
                        val enableNotifications = value != null && value.isNotEmpty() && (value[0].toInt() != 0)
                        Log.d(TAG, "GATT Server: CCCD Descriptor write request from ${device?.address}, enableNotifications=$enableNotifications")
                        
                        if (responseNeeded) {
                            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                        }
                        
                        if (enableNotifications) {
                            var pendingToTransmit: String? = null
                            synchronized(this@BleDiscoveryService) {
                                isCccdSubscribed = true
                                pendingToTransmit = pendingPairingJson
                                pendingPairingJson = null
                            }
                            
                            if (pendingToTransmit != null) {
                                Log.d(TAG, "GATT Server: CCCD subscription acknowledged. Transmitting buffered pairing data now.")
                                performPairingDataTransmission(pendingToTransmit!!)
                            }
                        }
                        return
                    }

                    if (responseNeeded) {
                        bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                }
            })

            // Add Pairing service & characteristic with CCCD descriptor
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            val cccdDescriptor = BluetoothGattDescriptor(
                CCCD_DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            characteristic.addDescriptor(cccdDescriptor)
            service.addCharacteristic(characteristic)
            bluetoothGattServer?.addService(service)
            Log.d(TAG, "Native BLE GATT Server hosted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed hosting native GATT server: ${e.message}")
        }
    }

    private fun stopGattServer() {
        try {
            bluetoothGattServer?.clearServices()
            connectedGattDevices.forEach { bluetoothGattServer?.cancelConnection(it) }
            bluetoothGattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT server: ${e.message}")
        } finally {
            bluetoothGattServer = null
            connectedGattDevices.clear()
            activeClaimedGattDevice = null
            isCccdSubscribed = false
            pendingPairingJson = null
            negotiatedMtu = 23
        }
    }

    /**
     * V2 Advertisement Helpers
     */
    private fun encodePayloadV2(protocolVersion: String, sessionId: String, deviceName: String, deviceType: String): ByteArray {
        val out = ByteArray(24)
        out[0] = if (protocolVersion == "1.0") 1 else 0
        out[1] = when (deviceType.lowercase()) {
            "phone" -> 0
            "tablet" -> 1
            "desktop" -> 2
            else -> 3
        }.toByte()

        val sessBytes = sessionId.take(8).padEnd(8, ' ').toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(sessBytes, 0, out, 2, 8)

        val nameBytes = deviceName.toByteArray(StandardCharsets.UTF_8)
        val truncatedName = if (nameBytes.size > 14) nameBytes.copyOf(14) else nameBytes
        val paddedName = truncatedName.copyOf(14)
        System.arraycopy(paddedName, 0, out, 10, 14)

        return out
    }

    private fun decodePayloadV2(data: ByteArray, macAddress: String): Device? {
        if (data.size < 24) return null
        try {
            val proto = if (data[0] == 1.toByte()) "1.0" else "0.0"
            val type = when (data[1].toInt()) {
                0 -> "phone"
                1 -> "tablet"
                2 -> "desktop"
                else -> "other"
            }
            val sessionIdHash = String(data, 2, 8, StandardCharsets.UTF_8).trim()
            val name = String(data, 10, 14, StandardCharsets.UTF_8).replace("\u0000", "").trim()

            // Map native device using MAC address as ID
            return Device(
                id = macAddress,
                name = name,
                ip = "0.0.0.0", // indicates V2 BLE Device
                port = 0,
                deviceType = type
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding BLE payload V2: ${e.message}")
        }
        return null
    }
}

/**
 * Global Simulation Registry & state mappings
 */
object BleSimulationRegistry {
    val activeAdvertisements = mutableMapOf<String, SimulatedReceiver>()
    private val scanListeners = mutableSetOf<(SimulatedReceiver) -> Unit>()
    
    @Synchronized
    fun registerReceiver(receiver: SimulatedReceiver) {
        activeAdvertisements[receiver.sessionId] = receiver
        scanListeners.forEach { it(receiver) }
    }
    
    @Synchronized
    fun unregisterReceiver(sessionId: String) {
        activeAdvertisements.remove(sessionId)
    }
    
    @Synchronized
    fun registerScanListener(listener: (SimulatedReceiver) -> Unit) {
        scanListeners.add(listener)
        activeAdvertisements.values.forEach { listener(it) }
    }
    
    @Synchronized
    fun unregisterScanListener(listener: (SimulatedReceiver) -> Unit) {
        scanListeners.remove(listener)
    }
}

data class SimulatedReceiver(
    val sessionId: String,
    val deviceName: String,
    val deviceType: String,
    val protocolVersion: String,
    val onGattConnect: (onPairingDataReceived: (String) -> Unit) -> Unit
)
