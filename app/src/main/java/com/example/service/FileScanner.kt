package com.example.service

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.util.Log
import com.example.database.AppDatabase
import com.example.database.IndexedFile
import com.example.database.IndexedFileDao
import java.io.File
import java.security.MessageDigest
import java.io.InputStream
import java.io.FileInputStream
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

    @Volatile
    var lastScanTimestamp: Long = System.currentTimeMillis() / 1000 - 30
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

            // 4b. Scan Documents/Files from Persisted SAF Tree URI
            val prefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
            val storedUri = prefs.getString("saf_tree_uri", null)
            if (storedUri != null) {
                try {
                    val treeUri = Uri.parse(storedUri)
                    val persistedUris = context.contentResolver.persistedUriPermissions
                    val hasPermission = persistedUris.any { it.uri == treeUri && it.isReadPermission }
                    if (hasPermission) {
                        Log.d(TAG, "Scanning documents from persisted tree URI: $storedUri")
                        scanDocumentTree(context, treeUri, fileList)
                    } else {
                        Log.w(TAG, "Persisted tree URI found but permission is missing/revoked: $storedUri")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed scanning persisted document tree", e)
                }
            }

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

            lastScanTimestamp = System.currentTimeMillis() / 1000
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

    private fun calculateHash(context: Context, uriString: String?, pathStr: String?): String? {
        var inputStream: InputStream? = null
        try {
            if (!pathStr.isNullOrBlank()) {
                val file = File(pathStr)
                if (file.exists() && file.isFile) {
                    inputStream = FileInputStream(file)
                }
            }
            if (inputStream == null && !uriString.isNullOrBlank()) {
                inputStream = context.contentResolver.openInputStream(Uri.parse(uriString))
            }
            if (inputStream == null) return null

            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            val read = inputStream.read(buffer)
            if (read > 0) {
                digest.update(buffer, 0, read)
            }
            val hashBytes = digest.digest()
            return hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Hash calculation failed for path=$pathStr uri=$uriString", e)
            return null
        } finally {
            try { inputStream?.close() } catch (e: Exception) {}
        }
    }

    private fun addOrMergeFile(
        context: Context,
        fileList: MutableList<IndexedFile>,
        name: String,
        size: Long,
        dateModified: Long,
        uriString: String?,
        path: String?,
        mimeType: String,
        category: String
    ) {
        if (size <= 0) return

        // 1. Check for an existing match using filename + size + modified date (tolerance of 2 seconds)
        val matchIndex = fileList.indexOfFirst { item ->
            item.name.equals(name, ignoreCase = true) &&
                    item.size == size &&
                    Math.abs(item.dateAdded - dateModified) <= 2
        }

        if (matchIndex != -1) {
            val existing = fileList[matchIndex]
            
            // Suspect false positive check if names match but we want to verify with hash
            val existingHash = existing.hash ?: calculateHash(context, existing.uriString, existing.path)
            val newHash = calculateHash(context, uriString, path)

            val isSameFile = if (existingHash != null && newHash != null) {
                existingHash == newHash
            } else {
                // If we couldn't get a hash for one or both, we assume they are the same file
                // because filename, size, and modified date are identical.
                true
            }

            if (isSameFile) {
                // Merge/update the existing record
                // Attach the URI if only the path was known, or vice versa
                // Always prioritize content:// URI for uriString as it has better compatibility, but preserve path.
                val mergedUri = if (existing.uriString.startsWith("content://")) {
                    existing.uriString
                } else if (uriString != null && uriString.startsWith("content://")) {
                    uriString
                } else {
                    existing.uriString
                }
                val mergedPath = existing.path ?: path
                val mergedHash = existingHash ?: newHash

                fileList[matchIndex] = existing.copy(
                    uriString = mergedUri,
                    path = mergedPath,
                    hash = mergedHash,
                    mimeType = if (existing.mimeType == "application/octet-stream" && mimeType != "application/octet-stream") mimeType else existing.mimeType,
                    category = if (existing.category == "Documents" && category != "Documents") category else existing.category
                )
                return
            }
        }

        // No match found, insert new record
        val fileHash = calculateHash(context, uriString, path)
        fileList.add(
            IndexedFile(
                uriString = uriString ?: "",
                path = path,
                name = name,
                size = size,
                mimeType = mimeType,
                category = category,
                dateAdded = dateModified,
                hash = fileHash
            )
        )
    }

    private fun scanCategory(context: Context, category: String, contentUri: Uri, outputList: MutableList<IndexedFile>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
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
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (cursor.moveToNext()) {
                    if (idCol == -1 || nameCol == -1 || sizeCol == -1 || mimeCol == -1 || dateCol == -1) continue
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0) continue // Skip empty files
                    val mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val dateAdded = cursor.getLong(dateCol)
                    val path = if (dataCol != -1) cursor.getString(dataCol) else null

                    val fileUri = ContentUris.withAppendedId(contentUri, id)
                    
                    addOrMergeFile(
                        context = context,
                        fileList = outputList,
                        name = name,
                        size = size,
                        dateModified = dateAdded,
                        uriString = fileUri.toString(),
                        path = path,
                        mimeType = mimeType,
                        category = category
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore for $category", e)
        }
    }

    private fun scanDocumentTree(context: Context, treeUri: Uri, outputList: MutableList<IndexedFile>) {
        try {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            scanDocumentDirectory(context, treeUri, rootUri, outputList, depth = 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting document tree scan", e)
        }
    }

    private fun scanDocumentDirectory(context: Context, treeUri: Uri, parentDocUri: Uri, outputList: MutableList<IndexedFile>, depth: Int) {
        if (depth > 6) return // prevent infinite loops
        try {
            val parentDocId = DocumentsContract.getDocumentId(parentDocUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            
            val documentExtensions = listOf(
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", 
                "txt", "zip", "rar", "tar", "gz", "7z", "epub", "csv", "json", "xml"
            )

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val dateCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                
                while (cursor.moveToNext()) {
                    if (idCol == -1 || nameCol == -1 || mimeCol == -1 || sizeCol == -1 || dateCol == -1) continue
                    val docId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val size = cursor.getLong(sizeCol)
                    val lastModified = cursor.getLong(dateCol) / 1000L // convert ms to seconds
                    
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        scanDocumentDirectory(context, treeUri, docUri, outputList, depth + 1)
                    } else {
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
                            if (size > 0) {
                                addOrMergeFile(
                                    context = context,
                                    fileList = outputList,
                                    name = name,
                                    size = size,
                                    dateModified = lastModified,
                                    uriString = docUri.toString(),
                                    path = null,
                                    mimeType = mimeType,
                                    category = "Documents"
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying documents from directory tree", e)
        }
    }

    private fun scanDocuments(context: Context, outputList: MutableList<IndexedFile>) {
        val contentUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )

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
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (cursor.moveToNext()) {
                    if (idCol == -1 || nameCol == -1 || sizeCol == -1 || mimeCol == -1 || dateCol == -1) continue
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0) continue
                    val mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val dateAdded = cursor.getLong(dateCol)
                    val path = if (dataCol != -1) cursor.getString(dataCol) else null

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
                        
                        addOrMergeFile(
                            context = context,
                            fileList = outputList,
                            name = name,
                            size = size,
                            dateModified = dateAdded,
                            uriString = fileUri.toString(),
                            path = path,
                            mimeType = mimeType,
                            category = "Documents"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore for Documents", e)
        }
    }

    private fun scanFallbackDirectories(context: Context, currentList: MutableList<IndexedFile>, dao: IndexedFileDao) {
        val foldersToScan = listOf(
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_MUSIC
        )

        for (folderName in foldersToScan) {
            val folder = Environment.getExternalStoragePublicDirectory(folderName)
            if (folder.exists() && folder.isDirectory) {
                scanDirRecursively(context, folder, folderName, currentList, depth = 0)
            }
        }
    }

    private fun scanDirRecursively(context: Context, dir: File, defaultCategoryName: String, outputList: MutableList<IndexedFile>, depth: Int) {
        if (depth > 4) return 
        
        val files = try { dir.listFiles() } catch (e: Exception) { null } ?: return
        for (file in files) {
            if (file.name.startsWith(".")) continue
            if (file.isDirectory) {
                scanDirRecursively(context, file, defaultCategoryName, outputList, depth + 1)
            } else {
                val size = file.length()
                if (size <= 0) continue

                val name = file.name
                val ext = name.substringAfterLast('.', "").lowercase()
                
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

                val fileUriStr = Uri.fromFile(file).toString()
                
                addOrMergeFile(
                    context = context,
                    fileList = outputList,
                    name = name,
                    size = size,
                    dateModified = file.lastModified() / 1000L,
                    uriString = fileUriStr,
                    path = file.absolutePath,
                    mimeType = mimeType,
                    category = category
                )
            }
        }
    }

    suspend fun performIncrementalScan(context: Context) = withContext(Dispatchers.IO) {
        if (isScanning) return@withContext
        val currentTimestamp = System.currentTimeMillis() / 1000
        val db = AppDatabase.getDatabase(context)
        val dao = db.indexedFileDao()
        
        Log.d(TAG, "Starting incremental scan. Checking changes since timestamp: $lastScanTimestamp")
        
        val fileList = mutableListOf<IndexedFile>()
        
        // 1. Scan for new or modified media in MediaStore
        scanCategoryIncremental(context, "Images", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fileList, lastScanTimestamp)
        scanCategoryIncremental(context, "Videos", MediaStore.Video.Media.EXTERNAL_CONTENT_URI, fileList, lastScanTimestamp)
        scanCategoryIncremental(context, "Audio", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, fileList, lastScanTimestamp)
        scanDocumentsIncremental(context, fileList, lastScanTimestamp)
        
        if (fileList.isNotEmpty()) {
            Log.d(TAG, "Incremental scan found ${fileList.size} new/modified items. Inserting/updating in DB.")
            // Filter out files that already exist to avoid redundancy
            val newFiles = fileList.filter { file ->
                !dao.exists(file.uriString, file.name, file.size)
            }
            if (newFiles.isNotEmpty()) {
                dao.insertFiles(newFiles)
            }
        }
        
        // 2. Sync / prune deleted items from DB
        pruneDeletedDbFiles(context, dao)
        
        lastScanTimestamp = currentTimestamp - 2 // 2-second overlap to be safe
    }

    private fun scanCategoryIncremental(
        context: Context,
        category: String,
        contentUri: Uri,
        outputList: MutableList<IndexedFile>,
        sinceTimestamp: Long
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )
        val selection = "${MediaStore.MediaColumns.DATE_MODIFIED} > ?"
        val selectionArgs = arrayOf(sinceTimestamp.toString())

        try {
            context.contentResolver.query(
                contentUri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (cursor.moveToNext()) {
                    if (idCol == -1 || nameCol == -1 || sizeCol == -1 || mimeCol == -1 || dateCol == -1) continue
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0) continue
                    val mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val dateAdded = cursor.getLong(dateCol)
                    val path = if (dataCol != -1) cursor.getString(dataCol) else null
                    val fileUri = ContentUris.withAppendedId(contentUri, id)

                    addOrMergeFile(
                        context = context,
                        fileList = outputList,
                        name = name,
                        size = size,
                        dateModified = dateAdded,
                        uriString = fileUri.toString(),
                        path = path,
                        mimeType = mimeType,
                        category = category
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in incremental scan for category $category", e)
        }
    }

    private fun scanDocumentsIncremental(
        context: Context,
        outputList: MutableList<IndexedFile>,
        sinceTimestamp: Long
    ) {
        val contentUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )
        
        val selection = "(${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/pdf' OR " +
                        "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'text/%' OR " +
                        "${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/msword' OR " +
                        "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/vnd.openxmlformats-officedocument.%') " +
                        "AND ${MediaStore.MediaColumns.DATE_MODIFIED} > ?"
        val selectionArgs = arrayOf(sinceTimestamp.toString())

        try {
            context.contentResolver.query(
                contentUri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (cursor.moveToNext()) {
                    if (idCol == -1 || nameCol == -1 || sizeCol == -1 || mimeCol == -1 || dateCol == -1) continue
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0) continue
                    val mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val dateAdded = cursor.getLong(dateCol)
                    val path = if (dataCol != -1) cursor.getString(dataCol) else null
                    val fileUri = ContentUris.withAppendedId(contentUri, id)

                    addOrMergeFile(
                        context = context,
                        fileList = outputList,
                        name = name,
                        size = size,
                        dateModified = dateAdded,
                        uriString = fileUri.toString(),
                        path = path,
                        mimeType = mimeType,
                        category = "Documents"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in incremental scan for Documents", e)
        }
    }

    private suspend fun pruneDeletedDbFiles(context: Context, dao: IndexedFileDao) {
        val allDbFiles = dao.getAllFiles()
        if (allDbFiles.isEmpty()) return
        
        val activeIds = mutableSetOf<Long>()
        val projections = arrayOf(MediaStore.MediaColumns._ID)
        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )
        
        for (contentUri in uris) {
            try {
                context.contentResolver.query(contentUri, projections, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (idCol != -1) {
                        while (cursor.moveToNext()) {
                            activeIds.add(cursor.getLong(idCol))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching active IDs from $contentUri", e)
            }
        }
        
        val toDeleteIds = mutableListOf<Long>()
        for (file in allDbFiles) {
            val uriStr = file.uriString
            if (uriStr.startsWith("content://")) {
                try {
                    val id = ContentUris.parseId(Uri.parse(uriStr))
                    if (!activeIds.contains(id)) {
                        toDeleteIds.add(file.id)
                    }
                } catch (e: Exception) {
                    var exists = false
                    try {
                        context.contentResolver.openAssetFileDescriptor(Uri.parse(uriStr), "r")?.use {
                            exists = true
                        }
                    } catch (ex: Exception) {
                        exists = false
                    }
                    if (!exists) {
                        toDeleteIds.add(file.id)
                    }
                }
            } else if (file.path != null) {
                val f = File(file.path)
                if (!f.exists()) {
                    toDeleteIds.add(file.id)
                }
            }
        }
        
        if (toDeleteIds.isNotEmpty()) {
            Log.d(TAG, "Incremental prune: deleting ${toDeleteIds.size} files from database.")
            toDeleteIds.chunked(900).forEach { chunk ->
                dao.deleteByIds(chunk)
            }
        }
    }
}
