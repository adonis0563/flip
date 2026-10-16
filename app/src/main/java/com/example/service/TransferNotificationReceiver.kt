package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TransferNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)

        when (action) {
            ACTION_ACCEPT -> {
                if (!transferId.isNullOrEmpty()) {
                    FlipTransferForegroundService.acceptTransfer(context, transferId)
                }
            }
            ACTION_DECLINE -> {
                if (!transferId.isNullOrEmpty()) {
                    FlipTransferForegroundService.declineTransfer(context, transferId)
                }
            }
            ACTION_CANCEL -> {
                if (!transferId.isNullOrEmpty()) {
                    FlipTransferForegroundService.cancelTransfer(context, transferId)
                }
            }
            ACTION_DISCONNECT -> {
                FlipTransferForegroundService.disconnectService(context)
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.example.flip.ACTION_ACCEPT"
        const val ACTION_DECLINE = "com.example.flip.ACTION_DECLINE"
        const val ACTION_CANCEL = "com.example.flip.ACTION_CANCEL"
        const val ACTION_DISCONNECT = "com.example.flip.ACTION_DISCONNECT"
        const val EXTRA_TRANSFER_ID = "com.example.flip.EXTRA_TRANSFER_ID"
    }
}
