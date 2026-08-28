package com.example.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.model.Device
import com.example.model.TransferItem
import com.example.model.TransferStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

object TransferManager {
    private const val TAG = "TransferManager"
    private const val PREFS_NAME = "flip_transfer_prefs"
    private const val KEY_TRANSFER_QUEUE = "transfer_queue"
    private const val KEY_REMOTE_DEVICE = "remote_device"

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val transferListType = Types.newParameterizedType(List::class.java, TransferItem::class.java)
    private val transferListAdapter = moshi.adapter<List<TransferItem>>(transferListType)
    private val deviceAdapter = moshi.adapter(Device::class.java)

    private val _transferQueue = MutableStateFlow<List<TransferItem>>(emptyList())
    val transferQueue: StateFlow<List<TransferItem>> = _transferQueue.asStateFlow()

    private val _remoteDevice = MutableStateFlow<Device?>(null)
    val remoteDevice: StateFlow<Device?> = _remoteDevice.asStateFlow()

    // Keep track of active paused / cancelled items for fast query by uploadFile
    private val pausedIds = mutableSetOf<String>()
    private val cancelledIds = mutableSetOf<String>()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Load remote device
        val deviceJson = prefs.getString(KEY_REMOTE_DEVICE, null)
        if (deviceJson != null) {
            try {
                _remoteDevice.value = deviceAdapter.fromJson(deviceJson)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing saved remote device", e)
            }
        }

        // Load transfer queue
        val queueJson = prefs.getString(KEY_TRANSFER_QUEUE, null)
        if (queueJson != null) {
            try {
                val savedList = transferListAdapter.fromJson(queueJson) ?: emptyList()
                // Mark in-progress (SENDING/QUEUED) transfers as PAUSED so they can be resumed cleanly
                val mappedList = savedList.map {
                    if (it.status == TransferStatus.SENDING || it.status == TransferStatus.QUEUED) {
                        it.copy(status = TransferStatus.PAUSED)
                    } else {
                        it
                    }
                }
                _transferQueue.value = mappedList
                
                // Populate lookup sets
                synchronized(this) {
                    pausedIds.clear()
                    cancelledIds.clear()
                    mappedList.forEach {
                        if (it.status == TransferStatus.PAUSED) pausedIds.add(it.id)
                        if (it.status == TransferStatus.CANCELLED) cancelledIds.add(it.id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing saved transfer queue", e)
            }
        }
    }

    private fun saveState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val queueJson = transferListAdapter.toJson(_transferQueue.value)
        val deviceJson = _remoteDevice.value?.let { deviceAdapter.toJson(it) }
        prefs.edit().apply {
            putString(KEY_TRANSFER_QUEUE, queueJson)
            putString(KEY_REMOTE_DEVICE, deviceJson)
            apply()
        }
    }

    fun setRemoteDevice(context: Context, device: Device?) {
        _remoteDevice.value = device
        saveState(context)
    }

    fun addFileToTransferQueue(context: Context, uri: Uri, fileName: String, fileSize: Long) {
        val transferId = UUID.randomUUID().toString()
        val newItem = TransferItem(
            id = transferId,
            fileName = fileName,
            fileUri = uri.toString(),
            fileSize = fileSize,
            status = TransferStatus.QUEUED,
            isIncoming = false
        )

        synchronized(this) {
            _transferQueue.value = _transferQueue.value + newItem
        }
        saveState(context)
        triggerForegroundService(context)
    }

    fun addIncomingTransfer(context: Context, transferId: String, fileName: String, fileSize: Long) {
        synchronized(this) {
            if (_transferQueue.value.none { it.id == transferId }) {
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
        saveState(context)
        triggerForegroundService(context)
    }

    fun pauseTransfer(context: Context, id: String) {
        synchronized(this) {
            pausedIds.add(id)
            _transferQueue.value = _transferQueue.value.map {
                if (it.id == id) it.copy(status = TransferStatus.PAUSED) else it
            }
        }
        saveState(context)
    }

    fun resumeTransfer(context: Context, id: String) {
        synchronized(this) {
            pausedIds.remove(id)
            cancelledIds.remove(id)
            _transferQueue.value = _transferQueue.value.map {
                if (it.id == id) it.copy(status = TransferStatus.QUEUED, errorMessage = null) else it
            }
        }
        saveState(context)
        triggerForegroundService(context)
    }

    fun cancelTransfer(context: Context, id: String) {
        var isIncoming = false
        var fileName = ""
        synchronized(this) {
            cancelledIds.add(id)
            pausedIds.remove(id)
            _transferQueue.value = _transferQueue.value.map {
                if (it.id == id) {
                    isIncoming = it.isIncoming
                    fileName = it.fileName
                    it.copy(status = TransferStatus.CANCELLED)
                } else it
            }
        }
        saveState(context)

        if (isIncoming) {
            StorageService.cancelTransfer(id, fileName)
        } else {
            val remote = _remoteDevice.value
            if (remote != null) {
                val transferService = TransferService(context.applicationContext)
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        transferService.notifyCancellation(remote.ip, remote.port, id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to notify cancellation", e)
                    }
                }
            }
        }
    }

    fun updateItemStatus(context: Context, id: String, status: TransferStatus, errMsg: String? = null) {
        synchronized(this) {
            if (status == TransferStatus.PAUSED) pausedIds.add(id) else pausedIds.remove(id)
            if (status == TransferStatus.CANCELLED) cancelledIds.add(id) else cancelledIds.remove(id)
            _transferQueue.value = _transferQueue.value.map {
                if (it.id == id) it.copy(status = status, errorMessage = errMsg) else it
            }
        }
        saveState(context)
    }

    fun updateItemProgress(context: Context, id: String, bytesTransferred: Long) {
        synchronized(this) {
            _transferQueue.value = _transferQueue.value.map {
                if (it.id == id) it.copy(bytesTransferred = bytesTransferred) else it
            }
        }
    }

    fun isPaused(id: String): Boolean {
        return synchronized(this) { pausedIds.contains(id) }
    }

    fun isCancelled(id: String): Boolean {
        return synchronized(this) { cancelledIds.contains(id) }
    }

    fun getNextSendItem(): TransferItem? {
        return synchronized(this) {
            _transferQueue.value.firstOrNull { it.status == TransferStatus.QUEUED && !it.isIncoming }
        }
    }

    private fun triggerForegroundService(context: Context) {
        try {
            val intent = android.content.Intent(context, TransferForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger TransferForegroundService", e)
        }
    }

    fun hasActiveTransfers(): Boolean {
        return synchronized(this) {
            _transferQueue.value.any { it.status == TransferStatus.SENDING || it.status == TransferStatus.QUEUED }
        }
    }

    fun cancelAllActiveTransfers(context: Context) {
        synchronized(this) {
            _transferQueue.value = _transferQueue.value.map {
                if (it.status == TransferStatus.SENDING || it.status == TransferStatus.QUEUED || it.status == TransferStatus.PAUSED) {
                    cancelledIds.add(it.id)
                    pausedIds.remove(it.id)
                    it.copy(status = TransferStatus.CANCELLED)
                } else {
                    it
                }
            }
        }
        saveState(context)
    }

    fun clearQueue(context: Context) {
        synchronized(this) {
            _transferQueue.value = emptyList()
            pausedIds.clear()
            cancelledIds.clear()
        }
        saveState(context)
    }
}
