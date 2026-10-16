package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.model.FileEntry
import com.example.repository.FileRepository
import com.example.service.LocalFileItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileRepositoryTest {

    @Test
    fun testCanonicalKeyGenerationOrder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // 1. MediaStore URI resolves collection + ID directly without IPC
        val mediaUri = Uri.parse("content://media/external/images/media/99999")
        val keyFromMedia = FileRepository.buildCanonicalKey(context, mediaUri, "photo.jpg", 2048L, 2000L)
        assertEquals("ms:media/external/images/media/99999", keyFromMedia)

        // 2. File URI resolves normalized file path
        val tempFile = File.createTempFile("test_file", ".jpg")
        val fileUri = Uri.fromFile(tempFile)
        val keyFromFile = FileRepository.buildCanonicalKey(context, fileUri, "test.jpg", 1024L, 1000L)
        assertEquals("file:${tempFile.path.trimEnd('/').lowercase()}", keyFromFile)
        
        // 3. Non-file non-mediastore fallback
        val customUri = Uri.parse("custom://provider/item/1")
        val fallbackKey = FileRepository.buildCanonicalKey(context, customUri, "document.pdf", 4096L, 3000L)
        assertEquals("fallback:document.pdf:4096:3000", fallbackKey)
    }

    @Test
    fun testDeduplicationAndMerging() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempFile = File.createTempFile("flip_photo", ".jpg")
        val canonicalKey = "file:${tempFile.path.trimEnd('/').lowercase()}"

        // Item 1: MediaStore content Uri
        val itemMediaStore = LocalFileItem(
            id = 101,
            uri = Uri.parse("content://media/external/images/media/101"),
            name = "flip_photo.jpg",
            size = 5000L,
            mimeType = "image/jpeg",
            category = "Images",
            dateAdded = 1600000000L
        )

        // Item 2: direct filesystem entry for the exact same file
        val itemFileSystem = LocalFileItem(
            id = 202,
            uri = Uri.fromFile(tempFile),
            name = "flip_photo.jpg",
            size = 5000L,
            mimeType = "image/jpeg",
            category = "Images",
            dateAdded = 1600000000L
        )

        val entry1 = FileEntry(
            key = canonicalKey,
            displayUri = itemMediaStore.uri,
            transferSource = itemMediaStore,
            name = itemMediaStore.name,
            size = itemMediaStore.size,
            mimeType = itemMediaStore.mimeType,
            dateModified = itemMediaStore.dateAdded,
            category = "Images"
        )

        val entry2 = FileEntry(
            key = canonicalKey,
            displayUri = itemFileSystem.uri,
            transferSource = itemFileSystem,
            name = itemFileSystem.name,
            size = itemFileSystem.size,
            mimeType = itemFileSystem.mimeType,
            dateModified = itemFileSystem.dateAdded,
            category = "Images"
        )

        // Deduplicate
        val deduplicated = FileRepository.deduplicateEntries(listOf(entry1, entry2))

        // Assert that the two entries were merged into exactly one
        assertEquals(1, deduplicated.size)
        val merged = deduplicated.first()
        assertEquals(canonicalKey, merged.key)
        // Assert displayUri is kept from MediaStore source (better for thumbnails)
        assertEquals(itemMediaStore.uri, merged.displayUri)
        // Assert transferSource is present and valid
        assertNotNull(merged.transferSource)
    }

    @Test
    fun testSelectionKeyTrackingAndLazyTransferSource() {
        val entryA = FileEntry(
            key = "ms:images:1",
            displayUri = Uri.parse("content://media/external/images/media/1"),
            name = "img1.jpg",
            size = 100,
            mimeType = "image/jpeg",
            dateModified = 1000,
            category = "Images"
        )
        val entryB = FileEntry(
            key = "ms:images:2",
            displayUri = Uri.parse("content://media/external/images/media/2"),
            name = "img2.jpg",
            size = 200,
            mimeType = "image/jpeg",
            dateModified = 2000,
            category = "Images"
        )

        val filesList = listOf(entryA, entryB)
        val selectedKeys = setOf(entryA.key)

        // Resolving to transferSource at send time lazily
        val filesToSend = filesList.filter { selectedKeys.contains(it.key) }.map { it.transferSource }
        assertEquals(1, filesToSend.size)
        assertEquals("img1.jpg", filesToSend.first().name)
        assertEquals(100L, filesToSend.first().size)
    }

    @Test
    fun testOnFileReceivedInstantUpdate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receivedFile = File.createTempFile("received_photo", ".jpg")
        receivedFile.writeBytes(ByteArray(1024))

        val beforeCount = FileRepository.images.value.size
        FileRepository.onFileReceived(context, receivedFile)
        val afterCount = FileRepository.images.value.size

        assertEquals(beforeCount + 1, afterCount)
        val firstItem = FileRepository.images.value.first()
        assertEquals(receivedFile.name, firstItem.name)
        assertEquals("Images", firstItem.category)
    }
}
