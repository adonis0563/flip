package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.MainActivity
import com.example.R
import com.example.model.Device
import java.io.File

object TransferNotificationManager {

    const val CHANNEL_INCOMING = "flip_incoming_files"
    const val CHANNEL_TRANSFERS = "flip_transfers"
    const val CHANNEL_COMPLETED = "flip_completed"

    private const val GROUP_INCOMING = "com.example.flip.INCOMING_GROUP"

    const val ID_PERSISTENT_CONNECTION = 1001
    private const val ID_INCOMING_SUMMARY = 1002

    private val lastProgressUpdateTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Incoming files: IMPORTANCE_HIGH (heads-up), sound on
            val incomingChannel = NotificationChannel(
                CHANNEL_INCOMING,
                "Incoming files",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Heads-up alerts for incoming file requests"
                enableVibration(true)
            }

            // 2. Transfers: IMPORTANCE_LOW, silent, for progress
            val transfersChannel = NotificationChannel(
                CHANNEL_TRANSFERS,
                "Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Transfer progress and active connection status"
                setSound(null, null)
                enableVibration(false)
            }

            // 3. Completed: IMPORTANCE_DEFAULT, for done/failed
            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED,
                "Completed",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when transfers finish or fail"
            }

            nm.createNotificationChannels(listOf(incomingChannel, transfersChannel, completedChannel))
        }
    }

    /**
     * Builds the persistent notification for the Foreground Service
     * "Flip is connected to <device name · short code>"
     */
    fun buildPersistentConnectionNotification(context: Context, remoteDevice: Device?): Notification {
        val devName = remoteDevice?.name ?: "Remote Peer"
        val shortCode = remoteDevice?.id?.take(4) ?: "peer"
        val title = "Flip is connected to $devName · $shortCode"

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            101,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(context, TransferNotificationReceiver::class.java).apply {
            action = TransferNotificationReceiver.ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getBroadcast(
            context,
            102,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.flip_logo)
            .setContentTitle(title)
            .setContentText("Tap to open Flip session")
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .build()
    }

    /**
     * Shows a heads-up notification for an incoming transfer request
     */
    fun showIncomingRequestNotification(
        context: Context,
        transferId: String,
        senderDevice: Device?,
        fileName: String,
        fileSize: Long
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = getNotificationId(transferId)

        val senderName = senderDevice?.name ?: "Remote Peer"
        val shortCode = senderDevice?.id?.take(4) ?: "peer"
        val formattedSize = if (fileSize > 0) Formatter.formatFileSize(context, fileSize) else ""
        val text = if (formattedSize.isNotEmpty()) "$fileName ($formattedSize)" else fileName

        // Tap notification body -> Open app on connected screen
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("transfer_id", transferId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action Accept
        val acceptIntent = Intent(context, TransferNotificationReceiver::class.java).apply {
            action = TransferNotificationReceiver.ACTION_ACCEPT
            putExtra(TransferNotificationReceiver.EXTRA_TRANSFER_ID, transferId)
        }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId * 10 + 1,
            acceptIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action Decline
        val declineIntent = Intent(context, TransferNotificationReceiver::class.java).apply {
            action = TransferNotificationReceiver.ACTION_DECLINE
            putExtra(TransferNotificationReceiver.EXTRA_TRANSFER_ID, transferId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            notifId * 10 + 2,
            declineIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Public version for lock screen (VISIBILITY_PRIVATE with public version hiding filenames)
        val publicNotification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.flip_logo)
            .setContentTitle("Incoming file")
            .setContentText("Flip connection active")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.flip_logo)
            .setContentTitle("$senderName · $shortCode wants to send")
            .setContentText(text)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setGroup(GROUP_INCOMING)
            .addAction(android.R.drawable.ic_input_add, "Accept", acceptPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Decline", declinePendingIntent)
            .build()

        nm.notify(notifId, notification)

        // Summary notification to group multiple incoming requests
        val summaryNotification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.flip_logo)
            .setContentTitle("Incoming files")
            .setContentText("Incoming file transfer requests")
            .setGroup(GROUP_INCOMING)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

        nm.notify(ID_INCOMING_SUMMARY, summaryNotification)
    }

    /**
     * Updates an active transfer with progress on the Transfers channel (throttled ~500ms)
     */
    fun showTransferProgressNotification(
        context: Context,
        transferId: String,
        fileName: String,
        bytesTransferred: Long,
        totalBytes: Long,
        isIncoming: Boolean
    ) {
        val now = System.currentTimeMillis()
        val lastUpdate = lastProgressUpdateTimes[transferId] ?: 0L
        if (now - lastUpdate < 500L && bytesTransferred < totalBytes) {
            return // Throttle progress to at most ~twice per second
        }
        lastProgressUpdateTimes[transferId] = now

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = getNotificationId(transferId)

        val percent = if (totalBytes > 0) ((bytesTransferred * 100f) / totalBytes).toInt().coerceIn(0, 100) else 0
        val transferredFormatted = Formatter.formatFileSize(context, bytesTransferred)
        val totalFormatted = Formatter.formatFileSize(context, totalBytes)
        val actionPrefix = if (isIncoming) "Receiving" else "Sending"

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Cancel action
        val cancelIntent = Intent(context, TransferNotificationReceiver::class.java).apply {
            action = TransferNotificationReceiver.ACTION_CANCEL
            putExtra(TransferNotificationReceiver.EXTRA_TRANSFER_ID, transferId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId * 10 + 3,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.flip_logo)
            .setContentTitle("$actionPrefix $fileName")
            .setContentText("$percent% • $transferredFormatted / $totalFormatted")
            .setProgress(100, percent, totalBytes <= 0)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()

        nm.notify(notifId, notification)
    }

    /**
     * Shows a completed transfer notification on the Completed channel with an Open action
     */
    fun showTransferCompletedNotification(
        context: Context,
        transferId: String,
        fileName: String,
        filePath: String?,
        isIncoming: Boolean
    ) {
        lastProgressUpdateTimes.remove(transferId)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = getNotificationId(transferId)
        // Dismiss progress notification
        nm.cancel(notifId)

        val completedNotifId = notifId + 50000
        val title = if (isIncoming) "Received $fileName" else "Sent $fileName"

        // Body click opens file explorer in Flip
        val explorerIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("open_explorer", true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            completedNotifId,
            explorerIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(R.drawable.flip_logo)
            .setContentTitle(title)
            .setContentText("Transfer finished successfully")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // Add Open/Install action if it is an incoming file and exists on disk
        if (isIncoming && filePath != null) {
            val file = File(filePath)
            if (file.exists()) {
                val isApp = file.name.endsWith(".apk", ignoreCase = true) || file.name.endsWith(".apks", ignoreCase = true)
                if (isApp) {
                    val installIntent = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra("install_file_path", file.absolutePath)
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val installPendingIntent = PendingIntent.getActivity(
                        context,
                        completedNotifId + 1,
                        installIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    builder.addAction(android.R.drawable.stat_sys_download_done, "Install", installPendingIntent)
                } else {
                    val uri: Uri? = try {
                        FileProvider.getUriForFile(context, "com.example.fileprovider", file)
                    } catch (_: Exception) {
                        null
                    }
                    if (uri != null) {
                        val mimeType = context.contentResolver.getType(uri) ?: "*/*"
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val openPendingIntent = PendingIntent.getActivity(
                            context,
                            completedNotifId + 1,
                            viewIntent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        builder.addAction(android.R.drawable.ic_menu_view, "Open", openPendingIntent)
                    }
                }
            }
        }

        nm.notify(completedNotifId, builder.build())
    }

    /**
     * Shows a failed or cancelled transfer notification
     */
    fun showTransferFailedNotification(
        context: Context,
        transferId: String,
        fileName: String,
        errorMessage: String
    ) {
        lastProgressUpdateTimes.remove(transferId)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = getNotificationId(transferId)
        // Dismiss progress notification
        nm.cancel(notifId)

        val failedNotifId = notifId + 50000

        val explorerIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            failedNotifId,
            explorerIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(R.drawable.flip_logo)
            .setContentTitle("Transfer failed: $fileName")
            .setContentText(errorMessage)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(failedNotifId, notification)
    }

    /**
     * Dismiss a specific transfer notification
     */
    fun cancelTransferNotification(context: Context, transferId: String) {
        lastProgressUpdateTimes.remove(transferId)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(getNotificationId(transferId))
    }

    /**
     * Clean up all notifications (e.g. on disconnect or service destruction)
     */
    fun cancelAllNotifications(context: Context) {
        lastProgressUpdateTimes.clear()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
    }

    private fun getNotificationId(transferId: String): Int {
        return (transferId.hashCode() and 0x7FFFFFFF) % 100000 + 2000
    }
}
