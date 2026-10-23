package com.example.model

import android.graphics.drawable.Drawable
import android.net.Uri
import com.example.service.LocalFileItem

/**
 * Single canonical record for an in-app explorer file entry.
 *
 * - [key]: Canonical identity used exclusively for deduplication and selection tracking.
 * - [displayUri]: Used ONLY for thumbnails, previews, and list display.
 * - [transferSource]: Evaluated lazily on access at send time, avoiding eager overhead on list load.
 * - [name], [size], [mimeType], [dateModified]: File attributes for UI display.
 */
data class FileEntry(
    val key: String,
    val displayUri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val dateModified: Long,
    val category: String = "Files",
    val isDirectory: Boolean = false,
    val packageName: String? = null,
    val versionName: String? = null,
    val isSplitApk: Boolean = false,
    val appIcon: Drawable? = null,
    val dateTaken: Long? = null,
    val dateAdded: Long = 0L,
    private val explicitTransferSource: LocalFileItem? = null
) {
    /**
     * Resolves the sort timestamp in milliseconds: DATE_TAKEN if present (> 0), otherwise DATE_ADDED (* 1000L), fallback to DATE_MODIFIED (* 1000L).
     */
    val sortTimeMillis: Long
        get() {
            if (dateTaken != null && dateTaken > 0L) return dateTaken
            if (dateAdded > 0L) return dateAdded * 1000L
            if (dateModified > 0L) return dateModified * 1000L
            return 0L
        }

    /**
     * Backward-compatible constructor for code providing an explicit LocalFileItem.
     */
    constructor(
        key: String,
        displayUri: Uri,
        transferSource: LocalFileItem,
        name: String,
        size: Long,
        mimeType: String,
        dateModified: Long,
        category: String = "Files",
        isDirectory: Boolean = false,
        packageName: String? = null,
        versionName: String? = null,
        isSplitApk: Boolean = false,
        appIcon: Drawable? = null,
        dateTaken: Long? = null,
        dateAdded: Long = 0L
    ) : this(
        key = key,
        displayUri = displayUri,
        name = name,
        size = size,
        mimeType = mimeType,
        dateModified = dateModified,
        category = category,
        isDirectory = isDirectory,
        packageName = packageName,
        versionName = versionName,
        isSplitApk = isSplitApk,
        appIcon = appIcon,
        dateTaken = dateTaken,
        dateAdded = dateAdded,
        explicitTransferSource = transferSource
    )

    /**
     * Lazily resolves transferSource when sending files.
     */
    val transferSource: LocalFileItem
        get() = explicitTransferSource ?: LocalFileItem(
            id = (displayUri.hashCode().toLong() xor size),
            uri = displayUri,
            name = name,
            size = size,
            mimeType = mimeType,
            category = category,
            dateAdded = dateModified,
            packageName = packageName,
            versionName = versionName,
            isSplitApk = isSplitApk,
            appIcon = appIcon
        )
}
