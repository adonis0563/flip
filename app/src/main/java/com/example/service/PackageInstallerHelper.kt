package com.example.service

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles package installation using Android PackageInstaller session API.
 * Supports standalone APKs and split APK bundles (.apks).
 */
object PackageInstallerHelper {
    private const val TAG = "FlipInstall"
    
    var pendingInstallFile: File? = null
    private val activeSessionTempDirs = ConcurrentHashMap<Int, File>()

    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Checks if there was a pending APK install waiting for permission.
     * Called in Activity.onResume().
     */
    fun checkPendingInstall(context: Context) {
        val file = pendingInstallFile ?: return
        if (canInstall(context)) {
            pendingInstallFile = null
            if (file.exists()) {
                Log.i(TAG, "Install permission granted. Resuming install for ${file.name}")
                install(context, file)
            }
        }
    }

    /**
     * Entry point to install an APK or APKS file.
     */
    fun install(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }

        if (!canInstall(context)) {
            pendingInstallFile = file
            Toast.makeText(context, "Allow Flip to install apps", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open unknown app sources settings: ${e.message}")
                }
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            performSessionInstall(context, file)
        }
    }

    private suspend fun performSessionInstall(context: Context, file: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val isBundle = file.name.endsWith(".apks", ignoreCase = true)
        val tempDir = if (isBundle) {
            File(context.cacheDir, "temp_apks_${System.currentTimeMillis()}").apply { mkdirs() }
        } else null

        val extractedApks = if (isBundle && tempDir != null) {
            unzipApks(file, tempDir)
        } else emptyList()

        if (isBundle && extractedApks.isEmpty()) {
            tempDir?.deleteRecursively()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "The file is damaged or incomplete", Toast.LENGTH_LONG).show()
            }
            return
        }

        val totalSize = if (isBundle) extractedApks.sumOf { it.length() } else file.length()
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (totalSize > 0) {
            params.setSize(totalSize)
        }

        var sessionId = -1
        var session: PackageInstaller.Session? = null

        try {
            sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)
            Log.i(TAG, "Created PackageInstaller session $sessionId for ${file.name}")

            if (isBundle && tempDir != null) {
                activeSessionTempDirs[sessionId] = tempDir

                for ((index, apk) in extractedApks.withIndex()) {
                    val entryName = if (apk.nameWithoutExtension.isNotBlank()) apk.nameWithoutExtension else "split_$index"
                    apk.inputStream().use { input ->
                        session.openWrite(entryName, 0, apk.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }
            } else {
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
            }

            val intent = Intent(context, PackageInstallReceiver::class.java).apply {
                action = PackageInstallReceiver.ACTION_INSTALL_STATUS
                putExtra(PackageInstallReceiver.EXTRA_SESSION_ID, sessionId)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                flags
            )

            session.commit(pendingIntent.intentSender)
            session.close()
            Log.i(TAG, "Committed PackageInstaller session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Install failed before commit: ${e.message}", e)
            if (sessionId != -1) {
                abandonSession(context, sessionId)
                cleanupSession(context, sessionId)
            } else {
                tempDir?.deleteRecursively()
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, e.message ?: "The file is damaged or incomplete", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun cleanupSession(context: Context, sessionId: Int) {
        val tempDir = activeSessionTempDirs.remove(sessionId)
        if (tempDir != null && tempDir.exists()) {
            try {
                tempDir.deleteRecursively()
                Log.d(TAG, "Deleted temp dir for session $sessionId: ${tempDir.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up temp dir for session $sessionId: ${e.message}")
            }
        }
    }

    fun abandonSession(context: Context, sessionId: Int) {
        try {
            context.packageManager.packageInstaller.abandonSession(sessionId)
            Log.i(TAG, "Abandoned session $sessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon session $sessionId: ${e.message}")
        }
    }

    private fun unzipApks(apksFile: File, outputDir: File): List<File> {
        val apkFiles = mutableListOf<File>()
        ZipInputStream(FileInputStream(apksFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (!entry.isDirectory && entryName.endsWith(".apk", ignoreCase = true)) {
                    val simpleName = File(entryName).name
                    val targetFile = File(outputDir, simpleName)
                    targetFile.outputStream().use { fos ->
                        zis.copyTo(fos)
                    }
                    apkFiles.add(targetFile)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return apkFiles
    }
}
