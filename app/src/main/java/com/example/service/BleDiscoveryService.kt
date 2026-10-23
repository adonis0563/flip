package com.example.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.model.Device
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Production BLE Discovery Service for Flip.
 * Supports Android 10 (API 29) through Android 15 (API 35+).
 *
 * Implements real Android BluetoothLeAdvertiser and BluetoothLeScanner with:
 * - Service UUID 0000fd01-0000-1000-8000-00805f9b34fb
 * - Compact binary packing in Service Data for zero-connection discovery
 * - BluetoothGattServer serving read-only Characteristic 0000fd02 for fallback full-payload read
 * - Strict permission handling for Android 10/11 vs Android 12+
 * - Comprehensive [BLE] logging and leak-free lifecycle cleanup
 */
class BleDiscoveryService(
    private val context: Context,
    private val listener: DiscoveryListener? = null
) {
    interface DiscoveryListener {
        fun onDeviceDiscovered(device: Device)
        fun onDiscoveryError(message: String)
    }

    companion object {
        private const val TAG = "BleDiscoveryService"
        val FLIP_SERVICE_UUID: UUID = UUID.fromString("0000fd01-0000-1000-8000-00805f9b34fb")
        val FLIP_CHAR_UUID: UUID = UUID.fromString("0000fd02-0000-1000-8000-00805f9b34fb")
        val FLIP_PARCEL_UUID = ParcelUuid(FLIP_SERVICE_UUID)

        @Volatile
        var lastAdvertiseError: String? = null
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var gattServer: BluetoothGattServer? = null

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    @Volatile
    private var isAdvertising = false

    @Volatile
    private var isScanning = false

    // Cache of discovered device IDs to avoid duplicate emissions
    private val discoveredDeviceKeys = mutableSetOf<String>()

    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    // -------------------------------------------------------------
    // PERMISSION CHECKS (Supporting Android 10 to 15)
    // -------------------------------------------------------------

    private fun findActivity(ctx: Context): Activity? {
        var c = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * On Android 12+, request BLUETOOTH_ADVERTISE, BLUETOOTH_SCAN, and BLUETOOTH_CONNECT
     * at runtime before advertising, scanning or opening the GATT server, and check them before each call.
     */
    fun requestRuntimeBlePermissionsIfMissing() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            val missing = required.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                Log.w(TAG, "[BLE] Requesting missing BLE runtime permissions on Android 12+: $missing")
                findActivity(context)?.let { act ->
                    ActivityCompat.requestPermissions(act, missing.toTypedArray(), 1002)
                }
            }
        }
    }

    fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            val hasLegacyBt = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            hasLegacyBt && hasLocation
        }
    }

    fun hasGattServerPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    // -------------------------------------------------------------
    // ADVERTISING (SENDER)
    // -------------------------------------------------------------

    @Synchronized
    fun startAdvertising(
        localIp: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        flags: Byte = 0x01
    ) {
        Log.d(TAG, "[BLE] start advertising requested: IP=$localIp, Port=$port, Device=$deviceName, Flags=$flags")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestRuntimeBlePermissionsIfMissing()
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            val msg = "Bluetooth is not supported on this device"
            Log.e(TAG, "[BLE] Advertising failure: $msg")
            lastAdvertiseError = msg
            listener?.onDiscoveryError(msg)
            return
        }

        if (!adapter.isEnabled) {
            val msg = "Bluetooth is turned off"
            Log.e(TAG, "[BLE] Advertising failure: $msg")
            lastAdvertiseError = msg
            listener?.onDiscoveryError(msg)
            return
        }

        // 4. Replace isMultipleAdvertisementSupported check with bluetoothAdapter.bluetoothLeAdvertiser != null
        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            val msg = "This phone can't advertise over Bluetooth"
            Log.w(TAG, "[BLE] Advertising warning: $msg")
            lastAdvertiseError = msg
            listener?.onDiscoveryError(msg)
            return
        }
        advertiser = leAdvertiser

        if (!hasAdvertisePermission()) {
            val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "Bluetooth advertise permission (BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT) not granted"
            } else {
                "Bluetooth permission not granted"
            }
            Log.e(TAG, "[BLE] Advertising failure: $msg")
            lastAdvertiseError = msg
            listener?.onDiscoveryError(msg)
            return
        }

        // Stop any active advertising first to avoid duplicates
        stopAdvertising()

        try {
            advertiser = leAdvertiser

            // 1. Pack binary payload for Service Data:
            // [0..3]: IPv4 (4 bytes)
            // [4..5]: Port (2 bytes, big endian)
            // [6..9]: DeviceId truncated/hash (4 bytes)
            // [10]: Flags byte (bit 0 = ready, bit 1 = transport: 0=hotspot, 1=wfd)
            // [11..]: Truncated device name in UTF-8
            var serviceDataBytes = encodeServiceData(localIp, port, deviceId, deviceName, flags)

            // Requirement 1 & 2: Compute advertisement size in code:
            // 3 (flags) + 4 (service data header for 16-bit UUID) + payload bytes for service data field + 4 more if addServiceUuid is also used.
            // Remove addServiceUuid since service data UUID already identifies Flip.
            val usingServiceUuid = false
            var advertSize = 3 + 4 + serviceDataBytes.size + (if (usingServiceUuid) 4 else 0)
            Log.i(TAG, "[BLE] Advertisement packet size: $advertSize bytes (flags=3, serviceDataHeader=4, payload=${serviceDataBytes.size}, serviceUuid=${if (usingServiceUuid) 4 else 0})")

            if (advertSize > 31) {
                Log.w(TAG, "[BLE] Advertisement packet size $advertSize exceeds 31 bytes limit! Capping device name.")
                val maxPayload = if (usingServiceUuid) 20 else 24
                val maxNameBytes = (maxPayload - 11).coerceAtLeast(0) // IP(4) + Port(2) + ID(4) + Flags(1) = 11
                serviceDataBytes = encodeServiceData(localIp, port, deviceId, deviceName, flags, maxNameBytes = maxNameBytes)
                advertSize = 3 + 4 + serviceDataBytes.size + (if (usingServiceUuid) 4 else 0)
                Log.i(TAG, "[BLE] Recomputed advertisement packet size after trim: $advertSize bytes")
            }

            // 2. Start GATT Server to respond to read requests with full metadata
            startGattServer(localIp, port, deviceId, deviceName)

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            // 2. Remove addServiceUuid, since the service data UUID already identifies Flip
            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(false) // Custom packing preserves byte budget
                .setIncludeTxPowerLevel(false)
                .addServiceData(FLIP_PARCEL_UUID, serviceDataBytes)
                .build()

            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    super.onStartSuccess(settingsInEffect)
                    isAdvertising = true
                    lastAdvertiseError = null
                    Log.i(TAG, "[BLE] Advertising started successfully for device: $deviceName (IP: $localIp:$port)")
                }

                override fun onStartFailure(errorCode: Int) {
                    super.onStartFailure(errorCode)
                    isAdvertising = false
                    val errorName = when (errorCode) {
                        ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE" // 1
                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS" // 2
                        ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED" // 3
                        ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR" // 4
                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED" // 5
                        else -> "UNKNOWN_ERROR_$errorCode"
                    }
                    Log.e(TAG, "[BLE] Advertising failed: $errorName (code $errorCode)")
                    val debugMsg = "BLE Advertising failed: $errorName ($errorCode)"
                    lastAdvertiseError = debugMsg
                    listener?.onDiscoveryError(debugMsg)
                }
            }

            advertiseCallback = callback
            advertiser?.startAdvertising(settings, advertiseData, callback)
            Log.d(TAG, "[BLE] BluetoothLeAdvertiser.startAdvertising called")
        } catch (e: SecurityException) {
            Log.e(TAG, "[BLE] SecurityException starting advertisement: ${e.message}", e)
            listener?.onDiscoveryError("Bluetooth permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "[BLE] Exception starting advertisement: ${e.message}", e)
            listener?.onDiscoveryError("Failed to start BLE advertisement: ${e.message}")
        }
    }

    @Synchronized
    fun stopAdvertising() {
        Log.d(TAG, "[BLE] stop advertising requested")
        try {
            advertiseCallback?.let { cb ->
                if (hasAdvertisePermission()) {
                    advertiser?.stopAdvertising(cb)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[BLE] Exception stopping advertising: ${e.message}")
        } finally {
            advertiseCallback = null
            advertiser = null
            isAdvertising = false
        }

        // Close GATT server
        try {
            gattServer?.clearServices()
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "[BLE] Exception closing GATT server: ${e.message}")
        } finally {
            gattServer = null
        }
        Log.i(TAG, "[BLE] Advertising stopped and GATT server closed")
    }

    // -------------------------------------------------------------
    // GATT SERVER (Serves full metadata to connecting clients)
    // -------------------------------------------------------------

    private fun startGattServer(localIp: String, port: Int, deviceId: String, deviceName: String) {
        val manager = bluetoothManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestRuntimeBlePermissionsIfMissing()
        }
        if (!hasGattServerPermission()) {
            Log.w(TAG, "[BLE] GATT server permission (BLUETOOTH_CONNECT) not granted")
            return
        }

        try {
            val serverCallback = object : BluetoothGattServerCallback() {
                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice?,
                    requestId: Int,
                    offset: Int,
                    characteristic: BluetoothGattCharacteristic?
                ) {
                    super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
                    if (characteristic?.uuid == FLIP_CHAR_UUID) {
                        val payload = "flip://ip=$localIp&port=$port&id=$deviceId&name=$deviceName&sid=${DeviceIdentity.currentSessionId}"
                        val data = payload.toByteArray(Charsets.UTF_8)
                        val subData = if (offset < data.size) {
                            data.copyOfRange(offset, data.size)
                        } else {
                            ByteArray(0)
                        }
                        try {
                            if (hasGattServerPermission()) {
                                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, subData)
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "[BLE] SecurityException sending GATT response: ${e.message}")
                        }
                    } else {
                        try {
                            if (hasGattServerPermission()) {
                                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                            }
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                }
            }

            gattServer = manager.openGattServer(context, serverCallback)
            val service = BluetoothGattService(FLIP_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                FLIP_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)
            Log.d(TAG, "[BLE] BluetoothGattServer started hosting FLIP service")
        } catch (e: SecurityException) {
            Log.w(TAG, "[BLE] SecurityException starting GATT server: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "[BLE] Exception starting GATT server: ${e.message}")
        }
    }

    // -------------------------------------------------------------
    // SCANNING (RECEIVER)
    // -------------------------------------------------------------

    @Synchronized
    fun startScanning() {
        Log.d(TAG, "[BLE] start scanning requested")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestRuntimeBlePermissionsIfMissing()
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            val msg = "Bluetooth is not supported on this device"
            Log.e(TAG, "[BLE] Scan failure: $msg")
            listener?.onDiscoveryError(msg)
            return
        }

        if (!adapter.isEnabled) {
            val msg = "Bluetooth is turned off"
            Log.e(TAG, "[BLE] Scan failure: $msg")
            listener?.onDiscoveryError(msg)
            return
        }

        if (!hasScanPermission()) {
            val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "Bluetooth scan permission (BLUETOOTH_SCAN / BLUETOOTH_CONNECT) not granted"
            } else {
                "Location or Bluetooth permission not granted for BLE scanning"
            }
            Log.e(TAG, "[BLE] Scan failure: $msg")
            listener?.onDiscoveryError(msg)
            return
        }

        // Stop any active scan first
        stopScanning()
        discoveredDeviceKeys.clear()

        try {
            scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                val msg = "Failed to obtain BluetoothLeScanner"
                Log.e(TAG, "[BLE] Scan failure: $msg")
                listener?.onDiscoveryError(msg)
                return
            }

            // Requirement 2: Filter with ScanFilter.Builder().setServiceData(uuid, null) instead of setServiceUuid
            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceData(FLIP_PARCEL_UUID, null)
                    .build()
            )

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    super.onScanResult(callbackType, result)
                    if (result != null) {
                        processScanResult(result)
                    }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    super.onBatchScanResults(results)
                    results?.forEach { processScanResult(it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    super.onScanFailed(errorCode)
                    isScanning = false
                    val errorDesc = when (errorCode) {
                        SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Application registration failed"
                        SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                        SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                        else -> "Scan error $errorCode"
                    }
                    Log.e(TAG, "[BLE] Scan failed: $errorDesc")
                    listener?.onDiscoveryError("BLE Scan failed: $errorDesc")
                }
            }

            scanCallback = callback
            scanner?.startScan(filters, settings, callback)
            isScanning = true
            Log.i(TAG, "[BLE] Scan started with filter for UUID $FLIP_SERVICE_UUID")
        } catch (e: SecurityException) {
            Log.e(TAG, "[BLE] SecurityException starting scan: ${e.message}", e)
            listener?.onDiscoveryError("Bluetooth scan permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "[BLE] Exception starting scan: ${e.message}", e)
            listener?.onDiscoveryError("Failed to start BLE scan: ${e.message}")
        }
    }

    @Synchronized
    fun stopScanning() {
        Log.d(TAG, "[BLE] stop scanning requested")
        try {
            scanCallback?.let { cb ->
                if (hasScanPermission()) {
                    scanner?.stopScan(cb)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[BLE] Exception stopping scan: ${e.message}")
        } finally {
            discoveredDeviceKeys.clear()
            scanCallback = null
            scanner = null
            isScanning = false
        }
        Log.i(TAG, "[BLE] Scan stopped")
    }

    // -------------------------------------------------------------
    // PARSING & EXTRACTION
    // -------------------------------------------------------------

    private fun processScanResult(result: ScanResult) {
        val record: ScanRecord = result.scanRecord ?: return
        val serviceData = record.getServiceData(FLIP_PARCEL_UUID) ?: return

        try {
            val decodedDevice = decodeServiceData(serviceData, result.device)
            if (decodedDevice != null) {
                val dedupeKey = "${decodedDevice.id}:${decodedDevice.ip}:${decodedDevice.port}"
                if (discoveredDeviceKeys.add(dedupeKey)) {
                    Log.i(TAG, "[BLE] Device found: ${decodedDevice.name} at ${decodedDevice.ip}:${decodedDevice.port} (id: ${decodedDevice.id}, flags: ${decodedDevice.flags})")
                }
                listener?.onDeviceDiscovered(decodedDevice)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[BLE] Error decoding scan result: ${e.message}")
        }
    }

    /**
     * Compact binary encoding for service data:
     * - 4 bytes: IPv4 (zeros for Wi-Fi Direct senders)
     * - 2 bytes: Port (Big Endian)
     * - 4 bytes: Device ID prefix / hash
     * - 1 byte: Flags (bit 0 = ready, bit 1 = transport: 0=hotspot, 1=wfd)
     * - Remaining: Device name in UTF-8
     */
    private fun encodeServiceData(
        ip: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        flags: Byte = 0x01,
        maxNameBytes: Int = 10
    ): ByteArray {
        val ipBytes = try {
            InetAddress.getByName(ip).address
        } catch (e: Exception) {
            byteArrayOf(0, 0, 0, 0)
        }

        val idBytes = deviceId.take(4).toByteArray(Charsets.UTF_8).let {
            if (it.size >= 4) it.copyOf(4) else it + ByteArray(4 - it.size)
        }

        // Limit device name bytes to prevent exceeding BLE advertise payload size
        val rawNameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val nameBytes = if (rawNameBytes.size > maxNameBytes) rawNameBytes.copyOf(maxNameBytes) else rawNameBytes

        val buffer = ByteBuffer.allocate(4 + 2 + 4 + 1 + nameBytes.size)
        buffer.put(ipBytes)
        buffer.putShort(port.toShort())
        buffer.put(idBytes)
        buffer.put(flags)
        buffer.put(nameBytes)
        return buffer.array()
    }

    private fun decodeServiceData(bytes: ByteArray, bluetoothDevice: BluetoothDevice): Device? {
        if (bytes.size < 10) return null

        val buffer = ByteBuffer.wrap(bytes)
        val ipBytes = ByteArray(4)
        buffer.get(ipBytes)
        val ip = InetAddress.getByAddress(ipBytes).hostAddress ?: return null

        val port = buffer.short.toInt() and 0xFFFF
        if (port <= 0 || port > 65535) return null

        val idBytes = ByteArray(4)
        buffer.get(idBytes)
        val idPrefix = String(idBytes, Charsets.UTF_8).trim('\u0000')

        // Read flags if present (spare byte at offset 10)
        val flags = if (buffer.remaining() > 0 && bytes.size > 10) buffer.get() else 0x01.toByte()

        val remaining = buffer.remaining()
        val name = if (remaining > 0) {
            val nameBytes = ByteArray(remaining)
            buffer.get(nameBytes)
            String(nameBytes, Charsets.UTF_8).trim()
        } else {
            "Flip Device"
        }

        val fullId = if (idPrefix.isNotEmpty()) idPrefix else bluetoothDevice.address ?: UUID.randomUUID().toString()

        return Device(
            id = fullId,
            name = if (name.isNotBlank()) name else "Flip Device",
            ip = ip,
            port = port,
            deviceType = "Android BLE",
            lastSeen = System.currentTimeMillis(),
            flags = flags
        )
    }
}
