package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * BroadcastReceiver for PackageInstaller session status callbacks.
 */
class PackageInstallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_INSTALL_STATUS = "com.example.flip.ACTION_INSTALL_STATUS"
        const val EXTRA_SESSION_ID = "com.example.flip.EXTRA_SESSION_ID"
        private const val TAG = "FlipInstall"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val rawMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))

        Log.i(TAG, "Install status code: $status, message: $rawMessage")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "STATUS_PENDING_USER_ACTION: Starting user confirmation dialog for session $sessionId")
                val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmationIntent != null) {
                    confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmationIntent)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Install succeeded for session $sessionId")
                PackageInstallerHelper.cleanupSession(context, sessionId)
                showToast(context, "App installed successfully")
            }
            else -> {
                Log.w(TAG, "Install completed with status code: $status, message: $rawMessage")
                PackageInstallerHelper.cleanupSession(context, sessionId)

                if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    PackageInstallerHelper.abandonSession(context, sessionId)
                }

                val userMessage = mapStatusToMessage(status, rawMessage)
                showToast(context, userMessage)
            }
        }
    }

    private fun mapStatusToMessage(status: Int, rawMessage: String): String {
        val msgLower = rawMessage.lowercase()
        return when {
            status == PackageInstaller.STATUS_FAILURE_CONFLICT ||
            msgLower.contains("signatures do not match") ||
            msgLower.contains("update_incompatible") -> {
                "Already installed with a different signature. Uninstall it first."
            }
            status == PackageInstaller.STATUS_FAILURE_STORAGE ||
            msgLower.contains("insufficient_storage") -> {
                "Not enough storage"
            }
            status == PackageInstaller.STATUS_FAILURE_INVALID ||
            msgLower.contains("parse_failed") ||
            msgLower.contains("corrupt") ||
            msgLower.contains("damaged") ||
            msgLower.contains("incomplete") -> {
                "The file is damaged or incomplete"
            }
            msgLower.contains("older_sdk") ||
            msgLower.contains("min sdk") ||
            msgLower.contains("newer android") -> {
                "This app needs a newer Android version"
            }
            msgLower.contains("cpu_abi") ||
            msgLower.contains("no_matching_abis") ||
            msgLower.contains("cpu") ||
            msgLower.contains("abi") -> {
                "This app doesn't fit this phone (CPU)"
            }
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                "This app needs a newer Android version"
            }
            rawMessage.isNotBlank() -> {
                rawMessage
            }
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> {
                "Installation cancelled"
            }
            else -> {
                "Installation failed ($status)"
            }
        }
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
