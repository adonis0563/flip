package com.example.service

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.database.AppDatabase
import com.example.database.IndexedFile
import com.example.database.IndexedFileDao
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FileScanner {
    private const val TAG = "FileScanner"

    @Volatile
    var isScanning = false
        private set

    @Volatile
    var scanProgress = 0f
        private set

    @Volatile
    var scanStatusText = ""
        private set

    suspend fun scanDeviceFiles(context: Context, onProgress: (String, Float) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        if (isScanning) return@withContext
        isScanning = true
        scanProgress = 0f
        scanStatusText = "Initializing scan..."
        onProgress(scanStatusText, scanProgress)

        try {
            val db = AppDatabase.getDatabase(context)
            val dao = db.indexedFileDao()
            
            // Temporary list to batch insert
            val fileList = mutableListOf<IndexedFile>()

            // 1. Scan Images
            scanStatusText = "Scanning images..."
            scanProgress = 0.1f
            onProgress(scanStatusText, scanProgress)
            scanCategory(context, "Images", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fileList)

            // 2. Scan Videos
            scanStatusText = "Scanning videos..."
            scanProgress = 0.3f
            onProgress(scanStatusText, scanProgress)
            scanCategory(context, "Videos", MediaStore.Video.Media.EXTERNAL_CONTENT_URI, fileList)

            // 3. Scan Audio
            scanStatusText = "Scanning audio..."
            scanProgress = 0.5f
            onProgress(scanStatusText, scanProgress)
            scanCategory(context, "Audio", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, fileList)

            // 4. Scan Documents/Files from MediaStore
            scanStatusText = "Scanning documents..."
            scanProgress = 0.7f
            onProgress(scanStatusText, scanProgress)
            scanDocuments(context, fileList)

            // 5. Fallback Deep Scanner for common folders
            scanStatusText = "Running fallback deep scan..."
            scanProgress = 0.85f
            onProgress(scanStatusText, scanProgress)
            scanFallbackDirectories(context, fileList, dao)

            // 6. Clear and insert everything in one fast transaction
            scanStatusText = "Updating database..."
            scanProgress = 0.95f
            onProgress(scanStatusText, scanProgress)
            
            dao.clearAll()
            // Chunk insertions to prevent SQLite bind parameter limit errors if there are thousands of files
            fileList.chunked(1000).forEach { chunk ->
                dao.insertFiles(chunk)
            }

            scanStatusText = "Scan completed. Found ${fileList.size} files."
            scanProgress = 1.0f
            onProgress(scanStatusText, scanProgress)
            Log.d(TAG, "Scan completed. Total files indexed: ${fileList.size}")

        } catch (e: Exception) {
            Log.e(TAG, "Error scanning files", e)
            scanStatusText = "Scan failed: ${e.message}"
            onProgress(scanStatusText, scanProgress)
        } finally {
            isScanning = false
        }
    }

    private fun scanCategory(context: Context, category: String, contentUri: Uri, outputList: MutableList<IndexedFile>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        try {
            context.contentResolver.query(
                contentUri,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    if (idCol == -1 || nameCol == -1 || sizeCol == -1 || mimeCol == -1 || dateCol == -1) continue
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0) continue // Skip empty files
                    val mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val dateAdded = cursor.getLong(dateCol)

                    val fileUri = ContentUris.withAppendedId(contentUri, id)
                    outputList.add(
                        IndexedFile(
                            uriString = fileUri.toString(),
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
            Log.e(TAG, "Error querying MediaStore for $category", e)
        }
    }

    private fun scanDocuments(context: Context, outputList: MutableList<IndexedFile>) {
        val contentUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        // Select files that are not images, videos, audio and have common document extensions/mime types
        val documentExtensions = listOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", 
            "txt", "zip", "rar", "tar", "gz", "7z", "epub", "csv", "json", "xml"
        )
        
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/pdf' OR " +
                        "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'text/%' OR " +
                        "${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/msword' OR " +
                        "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/vnd.openxmlformats-officedocument.%'"

        try {
            context.contentResolver.query(
                contentUri,
                projection,
                selection,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    if (idCol == -1 || nameCol == -1 || sizeCol == -1 || mimeCol == -1 || dateCol == -1) continue
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0) continue
                    val mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val dateAdded = cursor.getLong(dateCol)

                    // Verify if it is indeed a document
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val isDoc = mimeType.startsWith("text/") || 
                                mimeType == "application/pdf" || 
                                mimeType == "application/msword" ||
                                mimeType.startsWith("application/vnd.openxmlformats-officedocument") ||
                                mimeType.contains("document") || 
                                mimeType.contains("sheet") || 
                                mimeType.contains("presentation") ||
                                documentExtensions.contains(ext)

                    if (isDoc && !mimeType.startsWith("image/") && !mimeType.startsWith("video/") && !mimeType.startsWith("audio/")) {
                        val fileUri = ContentUris.withAppendedId(contentUri, id)
                        outputList.add(
                            IndexedFile(
                                uriString = fileUri.toString(),
                                name = name,
                                size = size,
                                mimeType = mimeType,
                                category = "Documents",
                                dateAdded = dateAdded
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore for Documents", e)
        }
    }

    private fun scanFallbackDirectories(context: Context, currentList: MutableList<IndexedFile>, dao: IndexedFileDao) {
        // Deep scanner targets
        val foldersToScan = listOf(
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_MUSIC
        )

        val processedPathsOrUris = currentList.map { it.uriString }.toMutableSet()

        for (folderName in foldersToScan) {
            val folder = Environment.getExternalStoragePublicDirectory(folderName)
            if (folder.exists() && folder.isDirectory) {
                scanDirRecursively(folder, folderName, processedPathsOrUris, currentList, depth = 0)
            }
        }
    }

    private fun scanDirRecursively(dir: File, defaultCategoryName: String, processed: MutableSet<String>, outputList: MutableList<IndexedFile>, depth: Int) {
        if (depth > 4) return // Guard against excessive nesting/infinite loops
        
        val files = try { dir.listFiles() } catch (e: Exception) { null } ?: return
        for (file in files) {
            if (file.name.startsWith(".")) continue
            if (file.isDirectory) {
                scanDirRecursively(file, defaultCategoryName, processed, outputList, depth + 1)
            } else {
                val fileUriStr = Uri.fromFile(file).toString()
                if (processed.contains(fileUriStr)) continue
                
                val size = file.length()
                if (size <= 0) continue

                val name = file.name
                val ext = name.substringAfterLast('.', "").lowercase()
                
                // Categorize based on extension
                val category = when (ext) {
                    "jpg", "jpeg", "png", "webp", "gif", "bmp" -> "Images"
                    "mp4", "mkv", "mov", "avi", "3gp", "webm" -> "Videos"
                    "mp3", "wav", "m4a", "ogg", "flac", "aac" -> "Audio"
                    else -> "Documents"
                }

                val mimeType = when (ext) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    "mp4" -> "video/mp4"
                    "mkv" -> "video/x-matroska"
                    "mp3" -> "audio/mpeg"
                    "wav" -> "audio/x-wav"
                    "m4a" -> "audio/x-m4a"
                    "pdf" -> "application/pdf"
                    "zip" -> "application/zip"
                    "txt" -> "text/plain"
                    "epub" -> "application/epub+zip"
                    "apk" -> "application/vnd.android.package-archive"
                    else -> "application/octet-stream"
                }

                outputList.add(
                    IndexedFile(
                        uriString = fileUriStr,
                        name = name,
                        size = size,
                        mimeType = mimeType,
                        category = category,
                        dateAdded = file.lastModified() / 1000L
                    )
                )
                processed.add(fileUriStr)
            }
        }
    }
}
