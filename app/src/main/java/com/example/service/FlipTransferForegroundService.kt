package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.Device
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class FlipTransferForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    interface ServiceBridge {
        fun onCancelTransfer(transferId: String)
        fun onDisconnectSession()
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

    /**
     * Android 15 (API 35+) dataSync timeout callback
     */
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

        fun start(context: Context, remoteDevice: Device?, role: String? = null) {
            currentRemoteDevice = remoteDevice
            if (remoteDevice != null) {
                ConnectionSessionManager.updateConnected(remoteDevice, role)
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

        /**
         * Waits up to 60 seconds for user to accept or decline the transfer
         */
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
