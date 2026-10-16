package com.example.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.example.model.FileEntry
import com.example.service.LocalFileItem
import com.example.service.StorageService
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-scoped singleton repository for file loading, preloading, live updates,
 * and canonical deduplication in Flip In-App File Explorer.
 */
object FileRepository {
    private const val TAG = "FileRepository"
    private const val CHUNK_SIZE = 100

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Category StateFlows
    private val _images = MutableStateFlow<List<FileEntry>>(emptyList())
    val images: StateFlow<List<FileEntry>> = _images.asStateFlow()

    private val _videos = MutableStateFlow<List<FileEntry>>(emptyList())
    val videos: StateFlow<List<FileEntry>> = _videos.asStateFlow()

    private val _audio = MutableStateFlow<List<FileEntry>>(emptyList())
    val audio: StateFlow<List<FileEntry>> = _audio.asStateFlow()

    private val _documents = MutableStateFlow<List<FileEntry>>(emptyList())
    val documents: StateFlow<List<FileEntry>> = _documents.asStateFlow()

    private val _apps = MutableStateFlow<List<FileEntry>>(emptyList())
    val apps: StateFlow<List<FileEntry>> = _apps.asStateFlow()

    private val _storage = MutableStateFlow<List<FileEntry>>(emptyList())
    val storage: StateFlow<List<FileEntry>> = _storage.asStateFlow()

    // Loading indicators per category (true only when cache is empty and actively loading)
    private val _isLoadingMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isLoadingMap: StateFlow<Map<String, Boolean>> = _isLoadingMap.asStateFlow()

    private val isObserverRegistered = AtomicBoolean(false)
    private var mediaObserver: ContentObserver? = null
    private var observerDebounceJob: Job? = null
    private var currentStorageDirectory: File? = null

    // Cache of SAF documents imported by user
    private val importedSafDocuments = mutableListOf<LocalFileItem>()

    fun getCategoryFlow(category: String): StateFlow<List<FileEntry>> {
        return when (category) {
            "Images" -> images
            "Videos" -> videos
            "Audio" -> audio
            "Documents" -> documents
            "Apps" -> apps
            "Storage" -> storage
            else -> images
        }
    }

    fun isCategoryLoading(category: String): Boolean {
        return _isLoadingMap.value[category] == true
    }

    /**
     * Builds canonical deduplication key without per-item IPC or filesystem overhead.
     * Order of preference:
     * 1. MediaStore URI collection + _ID (fast, zero IPC, perfectly unique across Scoped Storage)
     * 2. Direct file absolute path (for storage navigation and filesystem scans)
     * 3. Fallback: name + size + dateModified
     */
    fun buildCanonicalKey(
        context: Context,
        uri: Uri,
        name: String,
        size: Long,
        dateModified: Long
    ): String {
        if (isMediaStoreUri(uri)) {
            val collectionAndId = resolveMediaStoreIdentity(uri)
            if (!collectionAndId.isNullOrBlank()) {
                return "ms:$collectionAndId"
            }
        }

        if (uri.scheme == "file") {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                return "file:${path.trimEnd('/').lowercase(Locale.ROOT)}"
            }
        }

        val cleanName = name.trim().lowercase(Locale.ROOT)
        return "fallback:$cleanName:$size:$dateModified"
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        return uri.scheme == ContentResolver.SCHEME_CONTENT &&
                (uri.authority == "media" || uri.authority == MediaStore.AUTHORITY)
    }

    private fun resolveMediaStoreIdentity(uri: Uri): String? {
        val path = uri.path ?: return null
        return "${uri.authority}$path"
    }

    /**
     * Preloads categories at app start or right after permissions are granted.
     * Starts in parallel with Images prioritized first.
     */
    fun preload(context: Context) {
        val appContext = context.applicationContext
        registerContentObserver(appContext)

        repositoryScope.launch {
            // Load Images first for instant display on the default tab
            val imagesJob = launch { loadCategoryInternal(appContext, "Images") }
            
            // Concurrently load other categories in background
            val otherJobs = listOf(
                launch { loadCategoryInternal(appContext, "Videos") },
                launch { loadCategoryInternal(appContext, "Audio") },
                launch { loadCategoryInternal(appContext, "Documents") },
                launch { loadCategoryInternal(appContext, "Apps") }
            )

            imagesJob.join()
            otherJobs.forEach { it.join() }
        }
    }

    /**
     * Called when storage permissions are revoked. Clears caches.
     */
    fun onPermissionRevoked() {
        _images.value = emptyList()
        _videos.value = emptyList()
        _audio.value = emptyList()
        _documents.value = emptyList()
        _apps.value = emptyList()
        _storage.value = emptyList()
        _isLoadingMap.value = emptyMap()
    }

    /**
     * Silent refresh on app resume (no loading spinner shown).
     */
    fun refreshSilently(context: Context) {
        val appContext = context.applicationContext
        repositoryScope.launch {
            loadCategoryInternal(appContext, "Images", isSilent = true)
            loadCategoryInternal(appContext, "Videos", isSilent = true)
            loadCategoryInternal(appContext, "Audio", isSilent = true)
            loadCategoryInternal(appContext, "Documents", isSilent = true)
        }
    }

    /**
     * Adds SAF-selected documents and updates the Documents flow.
     */
    fun addSafDocuments(context: Context, docs: List<LocalFileItem>) {
        synchronized(importedSafDocuments) {
            importedSafDocuments.addAll(docs)
        }
        val newEntries = docs.map { toFileEntry(context, it) }
        val current = _documents.value
        _documents.value = deduplicateEntries(newEntries + current)
    }

    /**
     * Directly notifies repository that a file was received via Flip.
     * Immediately prepends to the relevant category without waiting for ContentObserver.
     */
    fun onFileReceived(context: Context, file: File) {
        if (!file.exists()) return

        val name = file.name
        val size = file.length()
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val dateModified = file.lastModified() / 1000L
        val fileUri = Uri.fromFile(file)

        val category = when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> "Images"
            "mp4", "mkv", "mov", "avi", "3gp", "webm" -> "Videos"
            "mp3", "wav", "m4a", "ogg", "flac", "aac" -> "Audio"
            "apk" -> "Apps"
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
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }

        val entry = FileEntry(
            key = "file:${file.canonicalPath.lowercase(Locale.ROOT)}",
            displayUri = fileUri,
            name = name,
            size = size,
            mimeType = mimeType,
            dateModified = dateModified,
            category = category
        )

        // Prepend directly to affected category StateFlow
        when (category) {
            "Images" -> _images.value = deduplicateEntries(listOf(entry) + _images.value)
            "Videos" -> _videos.value = deduplicateEntries(listOf(entry) + _videos.value)
            "Audio" -> _audio.value = deduplicateEntries(listOf(entry) + _audio.value)
            "Documents" -> _documents.value = deduplicateEntries(listOf(entry) + _documents.value)
            "Apps" -> _apps.value = deduplicateEntries(listOf(entry) + _apps.value)
        }
        
        // Also update storage if viewing this directory
        if (currentStorageDirectory != null && file.parentFile?.absolutePath == currentStorageDirectory?.absolutePath) {
            _storage.value = deduplicateEntries(listOf(entry) + _storage.value)
        }
    }

    sealed class DeleteResult {
        data class Success(val deletedCount: Int) : DeleteResult()
        data class RequiresConsent(val intentSender: android.content.IntentSender, val pendingEntries: List<FileEntry>) : DeleteResult()
        data class Error(val message: String) : DeleteResult()
    }

    /**
     * Purges specific keys from all category StateFlows immediately.
     */
    fun purgeKeys(keys: Set<String>) {
        if (keys.isEmpty()) return
        _images.value = _images.value.filterNot { it.key in keys }
        _videos.value = _videos.value.filterNot { it.key in keys }
        _audio.value = _audio.value.filterNot { it.key in keys }
        _documents.value = _documents.value.filterNot { it.key in keys }
        _storage.value = _storage.value.filterNot { it.key in keys }
        Log.i(TAG, "[DELETE] Purged ${keys.size} key(s) from memory cache")
    }

    /**
     * Deletes files from storage and MediaStore, updating cached flows immediately.
     * Supports Scoped Storage user consent requests on Android 10+ (Q/R+).
     */
    fun deleteFilesWithConsent(context: Context, filesToDelete: List<FileEntry>): DeleteResult {
        if (filesToDelete.isEmpty()) return DeleteResult.Success(0)
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        var deletedCount = 0
        val deletedKeys = mutableSetOf<String>()
        val consentRequiredUris = mutableListOf<Uri>()
        val consentRequiredEntries = mutableListOf<FileEntry>()

        for (entry in filesToDelete) {
            var success = false
            try {
                // 1. If it's a MediaStore content URI, delete via ContentResolver
                if (isMediaStoreUri(entry.displayUri)) {
                    val rows = resolver.delete(entry.displayUri, null, null)
                    if (rows > 0) {
                        success = true
                    }
                }
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    consentRequiredUris.add(entry.displayUri)
                    consentRequiredEntries.add(entry)
                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is android.app.RecoverableSecurityException) {
                    return DeleteResult.RequiresConsent(e.userAction.actionIntent.intentSender, listOf(entry))
                } else {
                    Log.w(TAG, "SecurityException deleting ${entry.displayUri}: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ContentResolver delete failed for ${entry.displayUri}: ${e.message}")
            }

            // 2. If not deleted via MediaStore and not queued for consent, attempt direct file deletion
            if (!success && !consentRequiredEntries.contains(entry)) {
                try {
                    val file = when {
                        entry.displayUri.scheme == "file" -> entry.displayUri.path?.let { File(it) }
                        entry.key.startsWith("file:") -> File(entry.key.removePrefix("file:"))
                        else -> null
                    }
                    if (file != null && file.exists()) {
                        success = if (file.isDirectory) file.deleteRecursively() else file.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Direct file delete failed for ${entry.name}: ${e.message}")
                }
            }

            if (success) {
                deletedCount++
                deletedKeys.add(entry.key)
            }
        }

        // Immediately purge deleted files from all Category StateFlows
        if (deletedKeys.isNotEmpty()) {
            purgeKeys(deletedKeys)
            Log.i(TAG, "[DELETE] Successfully deleted and purged $deletedCount file(s)")
        }

        if (consentRequiredUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(resolver, consentRequiredUris)
                return DeleteResult.RequiresConsent(pendingIntent.intentSender, consentRequiredEntries)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create MediaStore delete request: ${e.message}", e)
            }
        }

        return DeleteResult.Success(deletedCount)
    }

    /**
     * Backward-compatible deleteFiles method returning the deleted count.
     */
    fun deleteFiles(context: Context, filesToDelete: List<FileEntry>): Int {
        val result = deleteFilesWithConsent(context, filesToDelete)
        return when (result) {
            is DeleteResult.Success -> result.deletedCount
            else -> 0
        }
    }

    /**
     * Loads storage files for a specific directory.
     */
    fun loadStorageDirectory(context: Context, dir: File) {
        currentStorageDirectory = dir
        repositoryScope.launch {
            setCategoryLoading("Storage", _storage.value.isEmpty())
            val raw = StorageService.listDirectoryFiles(dir)
            val entries = raw.map { toFileEntry(context, it) }
            _storage.value = deduplicateEntries(entries)
            setCategoryLoading("Storage", false)
        }
    }

    /**
     * Loads installed apps.
     */
    fun loadApps(context: Context, showSystemApps: Boolean = false) {
        repositoryScope.launch {
            if (_apps.value.isEmpty()) {
                setCategoryLoading("Apps", true)
            }
            val raw = StorageService.queryInstalledApps(context, showSystemApps = showSystemApps)
            val entries = raw.map { toFileEntry(context, it) }
            _apps.value = deduplicateEntries(entries)
            setCategoryLoading("Apps", false)
        }
    }

    /**
     * Internal method querying MediaStore with minimal projections,
     * emitting in chunks for fast first-frame UI rendering.
     */
    private suspend fun loadCategoryInternal(
        context: Context,
        category: String,
        isSilent: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val targetFlow = when (category) {
            "Images" -> _images
            "Videos" -> _videos
            "Audio" -> _audio
            "Documents" -> _documents
            "Apps" -> _apps
            else -> return@withContext
        }

        val isCacheEmpty = targetFlow.value.isEmpty()
        if (isCacheEmpty && !isSilent) {
            setCategoryLoading(category, true)
        }

        if (category == "Apps") {
            val raw = StorageService.queryInstalledApps(context, showSystemApps = false)
            val entries = raw.map { toFileEntry(context, it) }
            targetFlow.value = deduplicateEntries(entries)
            setCategoryLoading(category, false)
            return@withContext
        }

        val contentUri = when (category) {
            "Images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "Videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "Audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        // Minimal fast projection
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val selection = when (category) {
            "Images", "Videos", "Audio" -> null
            else -> {
                // Documents selection
                "${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/pdf' OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'text/%' OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/msword' OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/vnd.openxmlformats-officedocument.%' OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/zip'"
            }
        }

        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val accumulated = mutableListOf<FileEntry>()
        var chunkEmitted = false

        try {
            context.contentResolver.query(
                contentUri,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0) continue

                    val mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val dateModified = cursor.getLong(dateCol)
                    val itemUri = ContentUris.withAppendedId(contentUri, id)

                    // Fast canonical key directly from collection & ID: zero IPC overhead
                    val key = "ms:${category.lowercase(Locale.ROOT)}:$id"

                    val entry = FileEntry(
                        key = key,
                        displayUri = itemUri,
                        name = name,
                        size = size,
                        mimeType = mimeType,
                        dateModified = dateModified,
                        category = category
                    )
                    accumulated.add(entry)

                    // Chunking: emit the first chunk (~100 items) immediately for instant rendering
                    if (accumulated.size == CHUNK_SIZE && !chunkEmitted && isCacheEmpty) {
                        targetFlow.value = accumulated.toList()
                        setCategoryLoading(category, false)
                        chunkEmitted = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore for $category: ${e.message}")
        }

        // Include any user-imported SAF documents if loading Documents
        if (category == "Documents") {
            val safList = synchronized(importedSafDocuments) { importedSafDocuments.toList() }
            val safEntries = safList.map { toFileEntry(context, it) }
            accumulated.addAll(safEntries)
        }

        // Deduplicate and emit complete final list
        val finalDeduplicated = deduplicateEntries(accumulated)
        targetFlow.value = finalDeduplicated
        setCategoryLoading(category, false)
    }

    private fun setCategoryLoading(category: String, loading: Boolean) {
        val current = _isLoadingMap.value.toMutableMap()
        current[category] = loading
        _isLoadingMap.value = current
    }

    /**
     * Converts a LocalFileItem to a FileEntry.
     */
    fun toFileEntry(context: Context, item: LocalFileItem): FileEntry {
        val key = buildCanonicalKey(
            context = context,
            uri = item.uri,
            name = item.name,
            size = item.size,
            dateModified = item.dateAdded
        )
        return FileEntry(
            key = key,
            displayUri = item.uri,
            name = item.name,
            size = item.size,
            mimeType = item.mimeType,
            dateModified = item.dateAdded,
            category = item.category,
            isDirectory = item.category == "Directory",
            packageName = item.packageName,
            versionName = item.versionName,
            isSplitApk = item.isSplitApk,
            appIcon = item.appIcon,
            explicitTransferSource = item
        )
    }

    /**
     * Merges FileEntry records by canonical key.
     * Retains displayUri from MediaStore content URI when available.
     */
    fun deduplicateEntries(entries: List<FileEntry>): List<FileEntry> {
        val mergedMap = LinkedHashMap<String, FileEntry>()

        for (entry in entries) {
            val key = entry.key
            val existing = mergedMap[key]

            if (existing == null) {
                mergedMap[key] = entry
            } else {
                val preferredDisplayUri = if (isMediaStoreUri(entry.displayUri)) {
                    entry.displayUri
                } else {
                    existing.displayUri
                }

                val preferredIcon = existing.appIcon ?: entry.appIcon
                val preferredPackage = existing.packageName ?: entry.packageName
                val preferredVersion = existing.versionName ?: entry.versionName

                mergedMap[key] = existing.copy(
                    displayUri = preferredDisplayUri,
                    appIcon = preferredIcon,
                    packageName = preferredPackage,
                    versionName = preferredVersion
                )
            }
        }

        return mergedMap.values.toList()
    }

    /**
     * Registers a ContentObserver on MediaStore collections.
     * Debounces live updates by 500ms to avoid thrashing on rapid file writes.
     */
    private fun registerContentObserver(context: Context) {
        if (!isObserverRegistered.compareAndSet(false, true)) return

        val handler = Handler(Looper.getMainLooper())
        mediaObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                observerDebounceJob?.cancel()
                observerDebounceJob = repositoryScope.launch {
                    delay(500L) // 500ms debounce
                    Log.d(TAG, "[MEDIASTORE] Change detected at $uri, refreshing live categories")
                    val appContext = context.applicationContext
                    loadCategoryInternal(appContext, "Images", isSilent = true)
                    loadCategoryInternal(appContext, "Videos", isSilent = true)
                    loadCategoryInternal(appContext, "Audio", isSilent = true)
                    loadCategoryInternal(appContext, "Documents", isSilent = true)
                }
            }
        }

        try {
            val cr = context.contentResolver
            cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver!!)
            cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver!!)
            cr.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver!!)
            cr.registerContentObserver(MediaStore.Files.getContentUri("external"), true, mediaObserver!!)
            Log.d(TAG, "[MEDIASTORE] ContentObserver successfully registered")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering MediaStore ContentObserver: ${e.message}")
        }
    }

    fun unregisterContentObserver(context: Context) {
        if (isObserverRegistered.compareAndSet(true, false)) {
            mediaObserver?.let {
                try {
                    context.contentResolver.unregisterContentObserver(it)
                    Log.d(TAG, "[MEDIASTORE] ContentObserver unregistered")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering ContentObserver: ${e.message}")
                }
            }
            mediaObserver = null
            observerDebounceJob?.cancel()
        }
    }
}
