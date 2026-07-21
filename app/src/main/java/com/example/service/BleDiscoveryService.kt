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
        Log.d(TAG, "startAdvertising: sessionId=$sessionId, deviceName=$deviceName")
        activeSessionId = sessionId
        sessionState = SessionState.UNCLAIMED
        activeClaimedGattDevice = null
        activePairingJson = null

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

        // 2. Real Native BLE Advertising
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled or supported. Running in BLE Simulation mode only.")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BLE advertiser not supported. Running in BLE Simulation mode only.")
            return
        }

        stopAdvertising()
        startGattServer()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true) // Must be connectable for GATT claim in V2!
            .build()

        val payload = encodePayloadV2(protocolVersion, sessionId, deviceName, deviceType)

        val data = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, payload)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "Native BLE V2 Advertisement started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                val msg = "Native BLE Adv failed: errorCode $errorCode"
                Log.e(TAG, msg)
                // We do not fail hard because the simulation registry is still active and works!
            }
        }

        advertiser?.startAdvertising(settings, data, advertiseCallback)
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
        val server = bluetoothGattServer ?: return
        val activeDevice = activeClaimedGattDevice ?: return
        val service = server.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

        // Send pairing data fragmented over GATT
        val payloadBytes = pairingJson.toByteArray(StandardCharsets.UTF_8)
        val mtu = 20 // Default fallback MTU chunk size
        
        Thread {
            try {
                var offset = 0
                val totalLength = payloadBytes.size
                var chunkIndex = 0
                val totalChunks = ((totalLength + mtu - 1) / mtu).coerceAtLeast(1)

                while (offset < totalLength) {
                    val size = (totalLength - offset).coerceAtLeast(0).coerceAtMost(mtu)
                    val chunk = ByteArray(2 + size)
                    chunk[0] = chunkIndex.toByte()
                    chunk[1] = totalChunks.toByte()
                    System.arraycopy(payloadBytes, offset, chunk, 2, size)

                    characteristic.value = chunk
                    server.notifyCharacteristicChanged(activeDevice, characteristic, false)
                    
                    offset += size
                    chunkIndex++
                    Thread.sleep(50) // Tiny delay between chunks
                }
                Log.d(TAG, "Finished native BLE GATT pairing data transfer ($totalChunks chunks)")
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
        Log.d(TAG, "startScanning")
        
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
            listener.onDeviceDiscovered(device)
        }
        BleSimulationRegistry.registerScanListener(simScanListener!!)

        // 2. Native BLE Scanning
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
                Log.e(TAG, msg)
            }
        }

        scanner?.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "Native BLE Scanning started")
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
        val record = result.scanRecord ?: return
        val manufacturerData = record.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
        
        val device = decodePayloadV2(manufacturerData, result.device.address)
        if (device != null) {
            listener.onDeviceDiscovered(device)
        }
    }

    /**
     * V2 SENDER: Establish GATT Connection to claim session and receive pairing data
     */
    fun connectToGatt(targetDevice: Device) {
        Log.d(TAG, "connectToGatt targetDevice=${targetDevice.id}")

        // 1. Simulation GATT Connection Handshake
        if (targetDevice.id.startsWith("SIM_")) {
            val sessionId = targetDevice.id.substringAfter("SIM_")
            val simReceiver = BleSimulationRegistry.activeAdvertisements[sessionId]
            if (simReceiver != null) {
                Thread {
                    Thread.sleep(800) // Simulate connection delay
                    simReceiver.onGattConnect { json ->
                        // Simulate receiving the pairing data back
                        Handler(Looper.getMainLooper()).post {
                            listener.onPairingDataReceived(json)
                        }
                    }
                }.start()
            } else {
                listener.onDiscoveryError("Simulated session no longer active")
            }
            return
        }

        // 2. Real Native GATT Connection Handshake
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            listener.onDiscoveryError("Bluetooth not available for GATT connection")
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
                    Log.d(TAG, "Native GATT MTU size negotiated: $mtu")
                    
                    // Register notifications for pairing data
                    val service = gatt?.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        
                        // Enable local descriptor CCCD notifications
                        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                        Log.d(TAG, "Subscribed to Pairing Data characteristic notifications")
                    } else {
                        listener.onDiscoveryError("Pairing characteristic not found on target device")
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
                        synchronized(this) {
                            if (sessionState != SessionState.UNCLAIMED) {
                                Log.w(TAG, "Rejecting extra client ${device.address} since session is already claimed")
                                bluetoothGattServer?.cancelConnection(device)
                                return
                            }
                            
                            sessionState = SessionState.CLAIMED
                            activeClaimedGattDevice = device
                            connectedGattDevices.add(device)
                        }

                        // Stop BLE Advertising immediately
                        stopAdvertising()

                        Handler(Looper.getMainLooper()).post {
                            activeSessionId?.let { listener.onSessionClaimed(it) }
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "GATT Server: Client disconnected: ${device.address}")
                        connectedGattDevices.remove(device)
                        if (device == activeClaimedGattDevice) {
                            activeClaimedGattDevice = null
                            sessionState = SessionState.UNCLAIMED
                        }
                    }
                }
            })

            // Add Pairing service & characteristic
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
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
