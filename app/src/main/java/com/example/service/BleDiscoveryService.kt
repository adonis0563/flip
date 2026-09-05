package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
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

    interface DiscoveryListener {
        fun onDeviceDiscovered(device: Device)
        fun onDiscoveryError(message: String)
    }

    companion object {
        private const val TAG = "BleDiscoveryService"
        // ✅ FIX: Use a safe, non-reserved 16-bit ID. 0xFFFF is reserved by Bluetooth SIG for testing 
        // and may be blocked by strict OEM BLE filters. 0x0563 is a safe custom identifier.
        private const val MANUFACTURER_ID = 0x0563 
        private val SERVICE_UUID = UUID.fromString("0000f119-0000-1000-8000-00805f9b34fb")
    }

    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled ?: false
    }

    fun startAdvertising(localIp: String, port: Int, deviceId: String, deviceName: String) {
        if (bluetoothAdapter == null) {
            listener.onDiscoveryError("Bluetooth is not supported on this device")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            listener.onDiscoveryError("Bluetooth is disabled. Please enable Bluetooth.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                listener.onDiscoveryError("Missing Bluetooth advertise permission")
                return
            }
        }
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            listener.onDiscoveryError("BLE Advertising not supported on this device")
            return
        }

        stopAdvertising()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val payload = encodePayload(localIp, port, deviceId, deviceName)

        val data = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, payload)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "BLE Advertisement started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                val msg = "BLE Adv failed: errorCode $errorCode"
                Log.e(TAG, msg)
                listener.onDiscoveryError(msg)
            }
        }

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while starting BLE advertising: ${e.message}")
            listener.onDiscoveryError("Bluetooth permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "Exception while starting BLE advertising: ${e.message}")
            listener.onDiscoveryError("Failed to start BLE advertising: ${e.message}")
        }
    }

    fun stopAdvertising() {
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
    }

    fun startScanning() {
        if (bluetoothAdapter == null) {
            listener.onDiscoveryError("Bluetooth is not supported on this device")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            listener.onDiscoveryError("Bluetooth is disabled. Please enable Bluetooth.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                listener.onDiscoveryError("Missing Bluetooth scan permission")
                return
            }
        }
        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onDiscoveryError("BLE Scanning not supported on this device")
            return
        }

        stopScanning()

        val filter = ScanFilter.Builder()
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
                val msg = "BLE Scan failed: errorCode $errorCode"
                Log.e(TAG, msg)
                listener.onDiscoveryError(msg)
            }
        }

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            Log.d(TAG, "BLE Scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while starting BLE scan: ${e.message}")
            listener.onDiscoveryError("Bluetooth scan permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "Exception while starting BLE scan: ${e.message}")
            listener.onDiscoveryError("Failed to start BLE scan: ${e.message}")
        }
    }

    fun stopScanning() {
        try {
            if (scanner != null && scanCallback != null) {
                scanner?.stopScan(scanCallback)
                Log.d(TAG, "BLE Scanning stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scanner: ${e.message}")
        } finally {
            scanCallback = null
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val manufacturerData = record.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
        
        val device = decodePayload(manufacturerData)
        if (device != null) {
            listener.onDeviceDiscovered(device)
        }
    }

    private fun encodePayload(ip: String, port: Int, id: String, name: String): ByteArray {
        val ipBytes = try {
            val ipParts = ip.split(".").map { it.toInt().toByte() }
            if (ipParts.size == 4) ipParts.toByteArray() else byteArrayOf(0, 0, 0, 0)
        } catch (e: Exception) {
            byteArrayOf(0, 0, 0, 0)
        }
        
        val portBytes = byteArrayOf((port ushr 8).toByte(), port.toByte())
        
        // Compact ID hash/slice
        val idBytes = id.take(8).padEnd(8, ' ').toByteArray(StandardCharsets.UTF_8)
        
        val maxNameLen = 10
        
        // ✅ FIX: Truncate by character to avoid slicing multi-byte UTF-8 characters (like emojis)
        var truncatedNameStr = name
        while (truncatedNameStr.toByteArray(StandardCharsets.UTF_8).size > maxNameLen) {
            truncatedNameStr = truncatedNameStr.dropLast(1)
        }
        
        val nameBytes = truncatedNameStr.toByteArray(StandardCharsets.UTF_8)
        val paddedName = nameBytes.copyOf(maxNameLen) // Pads with 0x00 to exactly 10 bytes

        val out = ByteArray(4 + 2 + 8 + maxNameLen)
        System.arraycopy(ipBytes, 0, out, 0, 4)
        System.arraycopy(portBytes, 0, out, 4, 2)
        System.arraycopy(idBytes, 0, out, 6, 8)
        System.arraycopy(paddedName, 0, out, 14, maxNameLen)

        return out
    }

    private fun decodePayload(data: ByteArray): Device? {
        if (data.size < 24) return null
        try {
            val ip = "${data[0].toInt() and 0xFF}.${data[1].toInt() and 0xFF}.${data[2].toInt() and 0xFF}.${data[3].toInt() and 0xFF}"
            val port = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            
            val id = String(data, 6, 8, StandardCharsets.UTF_8).trim()
            val name = String(data, 14, 10, StandardCharsets.UTF_8).replace("\u0000", "").trim()
            
            return Device(id, name, ip, port)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding BLE payload: ${e.message}")
        }
        return null
    }
}
