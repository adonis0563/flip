package com.example.service

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.model.TransferItem
import java.io.File

data class LocalFileItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val category: String, // "Images", "Videos", "Audio", "Documents", "Apps"
    val dateAdded: Long = 0,
    val packageName: String? = null,
    val versionName: String? = null,
    val isSplitApk: Boolean = false,
    val appIcon: android.graphics.drawable.Drawable? = null
)

object StorageService {
    private const val TAG = "StorageService"
    
    var appFilesDir: File? = null
    
    // Use Downloads/Flip or appFilesDir as it is easily accessible to users and safe from strict scoped storage restrictions
    fun getFlipDirectory(): File {
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var flipDir = File(baseDir, "Flip")
        try {
            if (!flipDir.exists()) {
                val success = flipDir.mkdirs()
                if (!success && appFilesDir != null) {
                    flipDir = File(appFilesDir, "Flip")
                    if (!flipDir.exists()) {
                        flipDir.mkdirs()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Flip directory: ${e.message}")
            if (appFilesDir != null) {
                flipDir = File(appFilesDir, "Flip")
                if (!flipDir.exists()) {
                    flipDir.mkdirs()
                }
            }
        }
        return flipDir
    }

    fun getPartialFile(transferId: String, fileName: String): File {
        return File(getFlipDirectory(), "$fileName.$transferId.partial")
    }

    fun getOffsetFile(transferId: String, fileName: String): File {
        return File(getFlipDirectory(), "$fileName.$transferId.offset")
    }

    fun getUniqueFinalFile(fileName: String): File {
        val dir = if (fileName.endsWith(".apk", ignoreCase = true)) {
            val apkDir = File(getFlipDirectory(), "Received/APKs")
            if (!apkDir.exists()) {
                apkDir.mkdirs()
            }
            apkDir
        } else {
            getFlipDirectory()
        }
        var finalFile = File(dir, fileName)
        if (!finalFile.exists()) return finalFile

        val dotIndex = fileName.lastIndexOf('.')
        val nameWithoutExt = if (dotIndex != -1) fileName.substring(0, dotIndex) else fileName
        val ext = if (dotIndex != -1) fileName.substring(dotIndex) else ""

        var counter = 1
        while (finalFile.exists()) {
            finalFile = File(dir, "$nameWithoutExt($counter)$ext")
            counter++
        }
        return finalFile
    }

    /**
     * Confirms the exact bytes written to the partial file based on current file length
     */
    fun getConfirmedOffset(transferId: String, fileName: String): Long {
        val partialFile = getPartialFile(transferId, fileName)
        val offsetFile = getOffsetFile(transferId, fileName)
        
        if (!partialFile.exists()) {
            offsetFile.delete()
            return 0L
        }
        
        val len = partialFile.length()
        // Write/update the offset file as well
        try {
            offsetFile.writeText(len.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error writing offset file: ${e.message}")
        }
        return len
    }

    /**
     * Handles successful completion of a file transfer
     */
    fun finalizeTransfer(context: Context, transferId: String, fileName: String): File? {
        try {
            val partialFile = getPartialFile(transferId, fileName)
            val offsetFile = getOffsetFile(transferId, fileName)
            
            if (partialFile.exists()) {
                val finalFile = getUniqueFinalFile(fileName)
                
                // Rename partial file to final name first
                var renamed = partialFile.renameTo(finalFile)
                if (!renamed) {
                    Log.w(TAG, "renameTo failed, attempting manual copy fallback")
                    try {
                        partialFile.inputStream().use { input ->
                            finalFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        renamed = finalFile.exists() && finalFile.length() == partialFile.length()
                        if (renamed) {
                            partialFile.delete()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Manual copy fallback failed: ${e.message}")
                    }
                }

                if (renamed) {
                    // Then delete the offset marker (ordering matters!)
                    offsetFile.delete()
                    Log.d(TAG, "Successfully finalized transfer: ${finalFile.absolutePath}")
                    
                    // Trigger media scanner scan so files show up in Gallery/File Manager instantly
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(finalFile.absolutePath),
                        null,
                        null
                    )
                    
                    return finalFile
                } else {
                    Log.e(TAG, "Failed to finalize partial file to final name")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing transfer: ${e.message}")
        }
        return null
    }

    /**
     * Handles cancellation/deletion of an in-progress transfer
     */
    fun cancelTransfer(transferId: String, fileName: String) {
        try {
            val partialFile = getPartialFile(transferId, fileName)
            val offsetFile = getOffsetFile(transferId, fileName)
            
            // Delete partial file first
            if (partialFile.exists()) {
                partialFile.delete()
            }
            // Then delete offset marker (ordering matters!)
            if (offsetFile.exists()) {
                offsetFile.delete()
            }
            Log.d(TAG, "Successfully cleaned up cancelled transfer assets for $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling transfer clean up: ${e.message}")
        }
    }

    /**
     * Cleans up any .offset marker files whose corresponding .partial file no longer exists
     * Run once on app launch.
     */
    fun cleanupOrphanedMarkers() {
        try {
            val dir = getFlipDirectory()
            val files = dir.listFiles() ?: return
            
            // Find all offset files
            val offsetFiles = files.filter { it.name.endsWith(".offset") }
            for (offsetFile in offsetFiles) {
                // Determine corresponding partial file name:
                // E.g. movie.mp4.{transferId}.offset -> movie.mp4.{transferId}.partial
                val baseName = offsetFile.name.substringBeforeLast(".offset")
                val partialFile = File(dir, "$baseName.partial")
                
                if (!partialFile.exists()) {
                    Log.d(TAG, "Deleting orphaned offset marker: ${offsetFile.name}")
                    offsetFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during orphaned markers cleanup: ${e.message}")
        }
    }

    fun queryMediaFiles(context: Context, category: String): List<LocalFileItem> {
        val list = mutableListOf<LocalFileItem>()
        
        val contentUri = when (category) {
            "Images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "Videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "Audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED
        )

        val selection = when (category) {
            "Images" -> null
            "Videos" -> null
            "Audio" -> null
            else -> {
                // Documents or general files
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'text/%'"
            }
        }

        try {
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT 50"
            context.contentResolver.query(
                contentUri,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Unknown"
                    val size = cursor.getLong(sizeColumn)
                    val mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream"
                    val dateAdded = cursor.getLong(dateColumn)

                    val fileUri = ContentUris.withAppendedId(contentUri, id)
                    list.add(
                        LocalFileItem(
                            id = id,
                            uri = fileUri,
                            name = name,
                            size = size,
                            mimeType = mimeType,
                            category = category,
                            dateAdded = dateAdded
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("StorageService", "Error querying MediaStore for $category: ${e.message}")
        }
        
        return list
    }

    fun queryInstalledApps(context: Context, showSystemApps: Boolean = false): List<LocalFileItem> {
        val list = mutableListOf<LocalFileItem>()
        val pm = context.packageManager
        try {
            val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            var idCounter = 3000L
            for (pkg in packages) {
                val app = pkg.applicationInfo ?: continue
                
                // Exclude Flip itself
                if (app.packageName == context.packageName) {
                    continue
                }
                
                // Exclude Android Framework packages
                if (app.packageName == "android" || app.packageName == "com.android.keyguard" || app.packageName == "com.android.systemui") {
                    continue
                }
                
                val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                
                // Filter system apps by default unless toggled on
                if (isSystem && !isUpdatedSystem && !showSystemApps) {
                    continue
                }
                
                val label = app.loadLabel(pm).toString()
                val apkPath = app.publicSourceDir ?: app.sourceDir
                if (apkPath != null) {
                    val file = File(apkPath)
                    if (file.exists()) {
                        val size = file.length()
                        val apkUri = Uri.fromFile(file)
                        
                        // Detect split APK
                        val isSplit = !app.splitSourceDirs.isNullOrEmpty()
                        
                        val appIcon = try {
                            app.loadIcon(pm)
                        } catch (e: Exception) {
                            null
                        }
                        
                        list.add(
                            LocalFileItem(
                                id = idCounter++,
                                uri = apkUri,
                                name = "$label.apk",
                                size = size,
                                mimeType = "application/vnd.android.package-archive",
                                category = "Apps",
                                dateAdded = file.lastModified() / 1000L,
                                packageName = app.packageName,
                                versionName = pkg.versionName ?: "1.0",
                                isSplitApk = isSplit,
                                appIcon = appIcon
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("StorageService", "Error querying installed apps: ${e.message}")
        }
        return list.sortedBy { it.name.lowercase() }
    }

    fun listDirectoryFiles(dir: File): List<LocalFileItem> {
        val list = mutableListOf<LocalFileItem>()
        try {
            val files = dir.listFiles() ?: return emptyList()
            var idCounter = 4000L
            for (file in files) {
                if (file.name.startsWith(".")) continue
                val isDirectory = file.isDirectory
                val name = file.name
                val size = if (isDirectory) 0L else file.length()
                val mimeType = if (isDirectory) "directory" else "application/octet-stream"
                val category = if (isDirectory) "Directory" else "File"
                list.add(
                    LocalFileItem(
                        id = idCounter++,
                        uri = Uri.fromFile(file),
                        name = name,
                        size = size,
                        mimeType = mimeType,
                        category = category,
                        dateAdded = file.lastModified() / 1000L
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("StorageService", "Error listing directory ${dir.absolutePath}: ${e.message}")
        }
        return list.sortedWith(compareBy<LocalFileItem> { it.category != "Directory" }.thenBy { it.name.lowercase() })
    }

    fun getMockLocalFiles(): List<LocalFileItem> {
        return listOf(
            LocalFileItem(1001, Uri.parse("content://mock/images/vacation.jpg"), "vacation_beach.jpg", 2451000, "image/jpeg", "Images"),
            LocalFileItem(1002, Uri.parse("content://mock/images/document.png"), "scanned_receipt.png", 512000, "image/png", "Images"),
            LocalFileItem(1003, Uri.parse("content://mock/video/screencast.mp4"), "tutorial_clip.mp4", 18500000, "video/mp4", "Videos"),
            LocalFileItem(1004, Uri.parse("content://mock/audio/music.mp3"), "summer_lovin.mp3", 4200000, "audio/mpeg", "Audio"),
            LocalFileItem(1005, Uri.parse("content://mock/docs/resume.pdf"), "curriculum_vitae.pdf", 125000, "application/pdf", "Documents"),
            LocalFileItem(1006, Uri.parse("content://mock/docs/project.zip"), "source_code_archive.zip", 8500000, "application/zip", "Documents")
        )
    }

    fun getMockInstalledApps(): List<LocalFileItem> {
        return listOf(
            LocalFileItem(3001, Uri.parse("content://mock/apps/youtube.apk"), "YouTube.apk", 45000000, "application/vnd.android.package-archive", "Apps"),
            LocalFileItem(3002, Uri.parse("content://mock/apps/whatsapp.apk"), "WhatsApp.apk", 38000000, "application/vnd.android.package-archive", "Apps"),
            LocalFileItem(3003, Uri.parse("content://mock/apps/instagram.apk"), "Instagram.apk", 52000000, "application/vnd.android.package-archive", "Apps"),
            LocalFileItem(3004, Uri.parse("content://mock/apps/browser.apk"), "Chrome_Browser.apk", 75000000, "application/vnd.android.package-archive", "Apps")
        )
    }

    fun getMockStorageFiles(parentDir: File): List<LocalFileItem> {
        val list = mutableListOf<LocalFileItem>()
        var idCounter = 5000L
        
        list.add(LocalFileItem(idCounter++, Uri.fromFile(File(parentDir, "DCIM")), "DCIM", 0, "directory", "Directory"))
        list.add(LocalFileItem(idCounter++, Uri.fromFile(File(parentDir, "Download")), "Download", 0, "directory", "Directory"))
        list.add(LocalFileItem(idCounter++, Uri.fromFile(File(parentDir, "Pictures")), "Pictures", 0, "directory", "Directory"))
        list.add(LocalFileItem(idCounter++, Uri.fromFile(File(parentDir, "Documents")), "Documents", 0, "directory", "Directory"))
        
        list.add(LocalFileItem(idCounter++, Uri.parse("content://mock/docs/presentation.pptx"), "project_presentation.pptx", 12400000, "application/vnd.openxmlformats-officedocument.presentationml.presentation", "File"))
        list.add(LocalFileItem(idCounter++, Uri.parse("content://mock/docs/budget.xlsx"), "quarterly_budget.xlsx", 345000, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "File"))
        list.add(LocalFileItem(idCounter++, Uri.parse("content://mock/docs/notes.txt"), "todo_notes.txt", 1200, "text/plain", "File"))
        
        return list
    }

    fun extractApk(context: Context, item: LocalFileItem, onProgress: (String, Float) -> Unit = { _, _ -> }): File? {
        try {
            val pm = context.packageManager
            val pkgName = item.packageName ?: return null
            val appInfo = pm.getApplicationInfo(pkgName, 0)
            
            val tempDir = File(getFlipDirectory(), "Temp/APK")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }

            val zipFileName = item.name.replace(".apk", "", ignoreCase = true) + "_bundle.apks"
            val destZipFile = File(tempDir, zipFileName)
            
            onProgress("Packaging ${item.name}...", 0f)

            val sourceFiles = mutableListOf<File>()
            val baseApkPath = appInfo.publicSourceDir ?: appInfo.sourceDir ?: return null
            sourceFiles.add(File(baseApkPath))
            
            appInfo.splitSourceDirs?.forEach { splitPath ->
                sourceFiles.add(File(splitPath))
            }

            var totalBytesToCopy = 0L
            sourceFiles.forEach { file -> 
                if (file.exists()) totalBytesToCopy += file.length() 
            }
            
            var bytesCopied = 0L

            java.util.zip.ZipOutputStream(java.io.FileOutputStream(destZipFile)).use { zos ->
                sourceFiles.forEach { sourceFile ->
                    if (!sourceFile.exists()) return@forEach
                    
                    val entryName = sourceFile.name
                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                    
                    java.io.FileInputStream(sourceFile).use { fis ->
                        val buffer = ByteArray(1024 * 64)
                        var bytes = fis.read(buffer)
                        while (bytes >= 0) {
                            zos.write(buffer, 0, bytes)
                            bytesCopied += bytes
                            if (totalBytesToCopy > 0) {
                                onProgress("Packaging ${item.name}...", bytesCopied.toFloat() / totalBytesToCopy)
                            }
                            bytes = fis.read(buffer)
                        }
                    }
                    zos.closeEntry()
                }
            }

            onProgress("Packaging complete", 1f)
            return destZipFile
        } catch (e: Exception) {
            Log.e("StorageService", "Failed to package APK: ${e.message}")
        }
        return null
    }

    fun cleanupTempApks() {
        try {
            val tempDir = File(getFlipDirectory(), "Temp/APK")
            if (tempDir.exists() && tempDir.isDirectory) {
                tempDir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("StorageService", "Failed to clean temp APKs: ${e.message}")
        }
    }

    fun createTempTransferFile(context: Context, item: LocalFileItem, onProgress: (String, Float) -> Unit = { _, _ -> }): File? {
        try {
            val uri = item.uri
            val tempDir = File(context.cacheDir, "TempTransfer")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            // Generate a safe unique name
            val safeName = item.name.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            val tempFile = File(tempDir, "${item.id}_$safeName")
            
            val inputStream = if (uri.scheme == "content") {
                context.contentResolver.openInputStream(uri)
            } else {
                val path = uri.path ?: return null
                java.io.FileInputStream(File(path))
            } ?: return null
            
            onProgress("Preparing ${item.name}...", 0f)
            val totalSize = item.size
            var bytesCopied = 0L
            
            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 64)
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        if (totalSize > 0) {
                            onProgress("Preparing ${item.name}...", bytesCopied.toFloat() / totalSize)
                        }
                        bytes = input.read(buffer)
                    }
                }
            }
            onProgress("Preparation complete", 1f)
            return tempFile
        } catch (e: Exception) {
            Log.e("StorageService", "Failed to resolve URI to temp file: ${e.message}")
        }
        return null
    }

    fun cleanupTempTransferFiles(context: Context) {
        try {
            val tempDir = File(context.cacheDir, "TempTransfer")
            if (tempDir.exists() && tempDir.isDirectory) {
                tempDir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("StorageService", "Failed to clean temp transfer files: ${e.message}")
        }
    }
}
