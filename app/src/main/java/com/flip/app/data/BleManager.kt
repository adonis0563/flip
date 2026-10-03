package com.flip.app.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

class BleManager(private val context: Context) {

    companion object {
        // Flip's unique BLE identifiers
        val FLIP_SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        val CREDENTIALS_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
        val STATUS_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567892")

        private const val TAG = "Flip_BLE"
        private const val MTU_SIZE = 256
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var gattClient: BluetoothGatt? = null

    // State
    private val _bleState = MutableStateFlow("Idle")
    val bleState: StateFlow<String> = _bleState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<ScanResult>>(emptyList())
    val discoveredDevices: StateFlow<List<ScanResult>> = _discoveredDevices.asStateFlow()

    // Callbacks for the ConnectionCoordinator
    var onCredentialsReceived: ((ssid: String, passphrase: String, port: Int) -> Unit)? = null
    var onDeviceConnected: (() -> Unit)? = null
    var onCredentialsSent: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // Pending credentials to send (set by Coordinator after group is created)
    private var pendingCredentials: String? = null

    // =====================================================================
    //                      ADVERTISER (SENDER)
    // =====================================================================

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onError?.invoke("Bluetooth not available")
            return
        }

        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            onError?.invoke("BLE Advertising not supported")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(FLIP_SERVICE_UUID))
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
        _bleState.value = "Advertising"
        Log.d(TAG, "Started BLE Advertising")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising started successfully")
            _bleState.value = "Advertising"
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            _bleState.value = "Advertising Failed: $errorCode"
            onError?.invoke("Advertising failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        _bleState.value = "Idle"
        Log.d(TAG, "Stopped advertising")
    }

    // =====================================================================
    //                      GATT SERVER (SENDER)
    // =====================================================================

    @SuppressLint("MissingPermission")
    fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(FLIP_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val credentialsChar = BluetoothGattCharacteristic(
            CREDENTIALS_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val statusChar = BluetoothGattCharacteristic(
            STATUS_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(credentialsChar)
        service.addCharacteristic(statusChar)
        gattServer?.addService(service)

        Log.d(TAG, "GATT Server started with Flip service")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Receiver connected via GATT!")
                _bleState.value = "GATT Connected"
                onDeviceConnected?.invoke() // Tells Coordinator to create Wi-Fi Direct group
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Receiver disconnected from GATT")
                _bleState.value = "Idle"
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic?.uuid == CREDENTIALS_CHAR_UUID) {
                val data = pendingCredentials?.toByteArray() ?: ByteArray(0)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
                Log.d(TAG, "Sent credentials to receiver via GATT read")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic?.uuid == STATUS_CHAR_UUID) {
                val status = value?.toString(Charsets.UTF_8)
                Log.d(TAG, "Receiver status: $status")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    /**
     * Called by ConnectionCoordinator after Wi-Fi Direct group is created.
     * Stores credentials so the GATT server can serve them.
     */
    fun setCredentialsForTransfer(ssid: String, passphrase: String, port: Int) {
        val json = JSONObject().apply {
            put("ssid", ssid)
            put("passphrase", passphrase)
            put("port", port)
        }
        pendingCredentials = json.toString()
        Log.d(TAG, "Credentials ready for GATT read: $pendingCredentials")
        onCredentialsSent?.invoke()
    }

    // =====================================================================
    //                      SCANNER (RECEIVER)
    // =====================================================================

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onError?.invoke("Bluetooth not available")
            return
        }

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            onError?.invoke("BLE Scanner not available")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(FLIP_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
        _bleState.value = "Scanning"
        Log.d(TAG, "Started BLE Scanning for Flip devices")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                val currentList = _discoveredDevices.value.toMutableList()
                // Avoid duplicates
                if (currentList.none { existing -> existing.device.address == it.device.address }) {
                    currentList.add(it)
                    _discoveredDevices.value = currentList
                    Log.d(TAG, "Found Flip device: ${it.device.name} (${it.device.address})")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _bleState.value = "Scan Failed: $errorCode"
            onError?.invoke("Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        bluetoothLeScanner?.stopScan(scanCallback)
        _bleState.value = "Idle"
        Log.d(TAG, "Stopped scanning")
    }

    // =====================================================================
    //                      GATT CLIENT (RECEIVER)
    // =====================================================================

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String) {
        stopScanning()
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device == null) {
            onError?.invoke("Device not found")
            return
        }

        gattClient = device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        _bleState.value = "Connecting via GATT..."
        Log.d(TAG, "Connecting to GATT server: $deviceAddress")
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT Connected! Discovering services...")
                _bleState.value = "GATT Connected"
                gatt?.requestMtu(MTU_SIZE)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT Disconnected")
                _bleState.value = "Disconnected"
                gatt?.close()
                gattClient = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to: $mtu")
            gatt?.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(FLIP_SERVICE_UUID)
                val credentialsChar = service?.getCharacteristic(CREDENTIALS_CHAR_UUID)

                if (credentialsChar != null) {
                    // Read the credentials from the Sender's GATT server
                    gatt.readCharacteristic(credentialsChar)
                    Log.d(TAG, "Reading credentials from GATT server...")
                } else {
                    onError?.invoke("Flip credentials characteristic not found")
                }
            }
        }

        // For Android 13+ (API 33+)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(characteristic.uuid, value, status, gatt)
        }

        // For Android 8 through 12 (API 26-32)
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (characteristic != null && gatt != null) {
                handleCharacteristicRead(characteristic.uuid, characteristic.value ?: ByteArray(0), status, gatt)
            }
        }

        private fun handleCharacteristicRead(
            uuid: UUID,
            value: ByteArray,
            status: Int,
            gatt: BluetoothGatt
        ) {
            if (uuid == CREDENTIALS_CHAR_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                val jsonString = value.toString(Charsets.UTF_8)
                Log.d(TAG, "Received credentials: $jsonString")

                try {
                    val json = JSONObject(jsonString)
                    val ssid = json.getString("ssid")
                    val passphrase = json.getString("passphrase")
                    val port = json.getInt("port")

                    // Hand off to Coordinator
                    onCredentialsReceived?.invoke(ssid, passphrase, port)

                    // Disconnect BLE - we have what we need
                    gatt.disconnect()
                    _bleState.value = "Credentials received. BLE disconnecting."

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse credentials: ${e.message}")
                    onError?.invoke("Failed to parse credentials")
                }
            }
        }
    }

    // =====================================================================
    //                      CLEANUP
    // =====================================================================

    @SuppressLint("MissingPermission")
    fun cleanup() {
        stopAdvertising()
        stopScanning()
        gattClient?.disconnect()
        gattClient?.close()
        gattClient = null
        gattServer?.close()
        gattServer = null
        _bleState.value = "Idle"
        Log.d(TAG, "BLE Manager cleaned up")
    }
}
