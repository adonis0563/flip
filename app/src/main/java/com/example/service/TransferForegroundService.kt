package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.model.Device
import com.example.model.TransferItem
import com.example.model.TransferStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransferForegroundService : Service() {

    companion object {
        private const val TAG = "TransferServiceBG"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "transfer_channel"

        const val ACTION_PAUSE = "com.example.service.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.service.ACTION_RESUME"
        const val ACTION_CANCEL = "com.example.service.ACTION_CANCEL"

        const val EXTRA_TRANSFER_ID = "extra_transfer_id"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transferJob: Job? = null
    private var progressObserveJob: Job? = null

    private lateinit var transferService: TransferService
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TransferForegroundService Created")
        transferService = TransferService(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Start observing queue progress for live notification updates
        startProgressObservation()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val action = intent?.action
            val transferId = intent?.getStringExtra(EXTRA_TRANSFER_ID)

            Log.d(TAG, "onStartCommand action=$action, transferId=$transferId")

            if (transferId != null) {
                when (action) {
                    ACTION_PAUSE -> {
                        TransferManager.pauseTransfer(this, transferId)
                    }
                    ACTION_RESUME -> {
                        TransferManager.resumeTransfer(this, transferId)
                    }
                    ACTION_CANCEL -> {
                        TransferManager.cancelTransfer(this, transferId)
                    }
                }
            }

            // Always show foreground notification immediately to comply with Android OS
            showInitialNotification()

            // Trigger queue execution
            startTransferQueueProcessor()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand: ${e.message}", e)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of file transfers"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showInitialNotification() {
        try {
            val notification = buildNotification(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground notification: ${e.message}", e)
        }
    }

    private fun startProgressObservation() {
        progressObserveJob?.cancel()
        progressObserveJob = serviceScope.launch {
            TransferManager.transferQueue.collectLatest { queue ->
                val active = queue.find { it.status == TransferStatus.SENDING }
                    ?: queue.find { it.status == TransferStatus.QUEUED }
                
                if (active != null) {
                    val notification = buildNotification(active)
                    notificationManager.notify(NOTIFICATION_ID, notification)
                } else {
                    // Check if there are any transfers remaining that are paused or failed
                    val anyActive = queue.any { it.status == TransferStatus.PAUSED }
                    if (!anyActive) {
                        Log.d(TAG, "No more active or paused transfers. Stopping service.")
                        stopForeground(true)
                        stopSelf()
                    } else {
                        // Keep service in foreground showing paused/finished status
                        val pausedItem = queue.find { it.status == TransferStatus.PAUSED }
                        val notification = buildNotification(pausedItem)
                        notificationManager.notify(NOTIFICATION_ID, notification)
                    }
                }
            }
        }
    }

    private fun buildNotification(item: TransferItem?): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (item == null) {
            builder.setContentTitle("Flip File Transfer")
                .setContentText("Initializing background transfer service...")
                .setProgress(0, 0, true)
            return builder.build()
        }

        val prefix = if (item.isIncoming) "Receiving" else "Sending"
        builder.setContentTitle("$prefix: ${item.fileName}")

        val formattedSize = formatBytes(item.fileSize)
        val formattedTransferred = formatBytes(item.bytesTransferred)
        val percent = if (item.fileSize > 0) (item.bytesTransferred * 100 / item.fileSize).toInt() else 0

        when (item.status) {
            TransferStatus.PAUSED -> {
                builder.setContentText("Paused • $percent% ($formattedTransferred / $formattedSize)")
                    .setProgress(item.fileSize.toInt(), item.bytesTransferred.toInt(), false)
                    .setSmallIcon(android.R.drawable.ic_media_pause)

                // Add Resume Action
                val resumeIntent = Intent(this, TransferForegroundService::class.java).apply {
                    action = ACTION_RESUME
                    putExtra(EXTRA_TRANSFER_ID, item.id)
                }
                val resumePI = PendingIntent.getService(
                    this,
                    item.id.hashCode() + 2,
                    resumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePI)

                // Add Cancel Action
                val cancelIntent = Intent(this, TransferForegroundService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_TRANSFER_ID, item.id)
                }
                val cancelPI = PendingIntent.getService(
                    this,
                    item.id.hashCode() + 3,
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPI)
            }
            TransferStatus.QUEUED -> {
                builder.setContentText("Queued • $formattedSize")
                    .setProgress(0, 0, true)
            }
            TransferStatus.SENDING -> {
                builder.setContentText("$percent% ($formattedTransferred / $formattedSize)")
                    .setProgress(item.fileSize.toInt(), item.bytesTransferred.toInt(), false)

                // Add Pause Action
                val pauseIntent = Intent(this, TransferForegroundService::class.java).apply {
                    action = ACTION_PAUSE
                    putExtra(EXTRA_TRANSFER_ID, item.id)
                }
                val pausePI = PendingIntent.getService(
                    this,
                    item.id.hashCode() + 4,
                    pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePI)

                // Add Cancel Action
                val cancelIntent = Intent(this, TransferForegroundService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_TRANSFER_ID, item.id)
                }
                val cancelPI = PendingIntent.getService(
                    this,
                    item.id.hashCode() + 5,
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPI)
            }
            TransferStatus.COMPLETE -> {
                builder.setContentText("Completed successfully!")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
            }
            TransferStatus.FAILED -> {
                builder.setContentText("Failed: ${item.errorMessage ?: "Unknown error"}")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
            }
            TransferStatus.CANCELLED -> {
                builder.setContentText("Cancelled")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
            }
        }

        return builder.build()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun startTransferQueueProcessor() {
        if (transferJob?.isActive == true) return

        transferJob = serviceScope.launch {
            while (true) {
                val nextItem = TransferManager.getNextSendItem()
                if (nextItem == null) {
                    Log.d(TAG, "No more items in queue. Processor idle.")
                    break
                }
                processSendItem(nextItem)
            }
        }
    }

    private suspend fun processSendItem(item: TransferItem) {
        val remote = TransferManager.remoteDevice.value
        if (remote == null) {
            TransferManager.updateItemStatus(this, item.id, TransferStatus.FAILED, "No connected device")
            return
        }

        // Set status to RUNNING / SENDING
        TransferManager.updateItemStatus(this, item.id, TransferStatus.SENDING)

        // 1. Handshake to get current receiver-side offset
        var offset = 0L
        try {
            offset = withContext(Dispatchers.IO) {
                transferService.getReceiverOffset(remote.ip, remote.port, item.id, item.fileName)
            }
            Log.d(TAG, "Resume Handshake succeeded. Offset: $offset")
        } catch (e: Exception) {
            Log.e(TAG, "Resume Handshake failed: ${e.message}")
            TransferManager.updateItemStatus(this, item.id, TransferStatus.FAILED, "Handshake failed: ${e.message}")
            return
        }

        // 2. Perform upload
        val completer = kotlinx.coroutines.CompletableDeferred<Unit>()

        withContext(Dispatchers.IO) {
            transferService.uploadFile(
                remoteIp = remote.ip,
                remotePort = remote.port,
                transferId = item.id,
                fileName = item.fileName,
                fileUriStr = item.fileUri ?: "",
                offset = offset,
                totalSize = item.fileSize,
                cancelCheck = {
                    TransferManager.isCancelled(item.id)
                },
                onProgress = { bytesTransferred ->
                    TransferManager.updateItemProgress(this@TransferForegroundService, item.id, bytesTransferred)
                },
                onSuccess = {
                    TransferManager.updateItemStatus(this@TransferForegroundService, item.id, TransferStatus.COMPLETE)
                    
                    // Clean up if temporary file
                    cleanupTempTransferFile(item)
                    completer.complete(Unit)
                },
                onError = { errMsg ->
                    val status = TransferManager.transferQueue.value.find { it.id == item.id }?.status
                    if (status != TransferStatus.PAUSED) {
                        TransferManager.updateItemStatus(this@TransferForegroundService, item.id, TransferStatus.FAILED, errMsg)
                    }
                    completer.complete(Unit)
                }
            )
        }

        // Wait for the active file upload to complete
        completer.await()
    }

    private fun cleanupTempTransferFile(item: TransferItem) {
        if (item.fileUri == null) return
        if (item.fileUri.contains("/Temp/APK") || item.fileUri.contains("/TempTransfer")) {
            try {
                val path = android.net.Uri.parse(item.fileUri).path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                        Log.d(TAG, "Cleaned up temporary transfer file: $path")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup temporary file: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "TransferForegroundService Destroyed")
        serviceScope.cancel()
        progressObserveJob?.cancel()
        transferJob?.cancel()
    }
}
