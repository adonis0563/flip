package com.flip.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

object TransferManager {

    private const val TAG = "Flip_Transfer"
    private const val DEFAULT_PORT_CHAT = 8888
    private const val DEFAULT_PORT_FILE = 8988
    private const val BUFFER_SIZE = 8192 // 8KB buffer for better throughput
    private const val CONNECTION_TIMEOUT = 5000

    // =====================================================================
    //                          CHAT (Port 8888)
    // =====================================================================

    suspend fun startChatServer(port: Int = DEFAULT_PORT_CHAT): Pair<String, String>? = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(port)
            Log.d(TAG, "[TRANSFER] Started: Chat Server waiting on port $port...")
            val client = serverSocket.accept()
            val senderIp = client.inetAddress?.hostAddress

            val message = client.getInputStream().bufferedReader().use { it.readText() }
            client.close()
            Log.d(TAG, "[TRANSFER] Completed: Received chat message from $senderIp")
            return@withContext Pair(message, senderIp ?: "Unknown")
        } catch (e: Exception) {
            Log.e(TAG, "❌ [TRANSFER] Chat Server Error: ${e.message}")
            return@withContext null
        } finally {
            try {
                serverSocket?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    suspend fun sendMessage(hostAddress: String, message: String, port: Int = DEFAULT_PORT_CHAT) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            Log.d(TAG, "[TRANSFER] Started: Sending chat message to $hostAddress:$port")
            socket = Socket()
            socket.bind(null)
            socket.connect(InetSocketAddress(hostAddress, port), CONNECTION_TIMEOUT)

            socket.getOutputStream().use { it.write(message.toByteArray(Charsets.UTF_8)) }
            Log.d(TAG, "[TRANSFER] Completed: Sent chat message to $hostAddress:$port")
        } catch (e: Exception) {
            Log.e(TAG, "❌ [TRANSFER] Send Message Error: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    // =====================================================================
    //                          FILES (Port 8988)
    // =====================================================================

    suspend fun startFileServer(
        context: Context,
        port: Int = DEFAULT_PORT_FILE,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Uri? = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        var client: Socket? = null
        try {
            serverSocket = ServerSocket(port)
            Log.d(TAG, "[TRANSFER] Started: File Server waiting on port $port...")

            client = serverSocket.accept()
            val inputStream = DataInputStream(client.getInputStream())

            // 1. Read Header
            val fileName = inputStream.readUTF()
            val fileSize = inputStream.readLong()
            val mimeType = inputStream.readUTF()

            Log.d(TAG, "[TRANSFER] Queued: Receiving $fileName ($fileSize bytes, $mimeType)")

            // 2. Prepare Storage (Scoped Storage for Android 10+, fallback for Android 8/9)
            val resolver = context.contentResolver
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Flip")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            } else {
                @Suppress("DEPRECATION")
                val flipDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Flip"
                )
                if (!flipDir.exists()) {
                    flipDir.mkdirs()
                }
                val destFile = File(flipDir, fileName)
                Uri.fromFile(destFile)
            }

            if (uri != null) {
                val outputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.openOutputStream(uri)
                } else {
                    FileOutputStream(File(uri.path ?: ""))
                }

                outputStream?.use { fileOutput ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalBytesRead: Long = 0

                    while (totalBytesRead < fileSize) {
                        bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        fileOutput.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        onProgress?.invoke(totalBytesRead, fileSize)
                    }
                }

                // Mark file as complete in MediaStore
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val completeValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, completeValues, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(uri.path),
                        arrayOf(mimeType),
                        null
                    )
                }
            }

            try {
                inputStream.close()
            } catch (ignored: Exception) {
            }

            Log.d(TAG, "✅ [TRANSFER] Completed: File received: $fileName")
            return@withContext uri

        } catch (e: Exception) {
            Log.e(TAG, "❌ [TRANSFER] File Server Error: ${e.message}")
            return@withContext null
        } finally {
            try {
                client?.close()
            } catch (ignored: Exception) {
            }
            try {
                serverSocket?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    suspend fun sendFile(
        context: Context,
        uri: Uri,
        hostAddress: String,
        port: Int = DEFAULT_PORT_FILE,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            Log.d(TAG, "[TRANSFER] Started: Connecting to $hostAddress:$port to send file")
            socket = Socket()
            socket.bind(null)
            socket.connect(InetSocketAddress(hostAddress, port), CONNECTION_TIMEOUT)

            val outputStream = DataOutputStream(socket.getOutputStream())
            val contentResolver = context.contentResolver

            // A. Get File Info
            var fileName = "unknown_file"
            var fileSize = 0L
            var mimeType = "*/*"

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex) ?: "unknown_file"
                    }
                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                    if (mimeIndex != -1) {
                        mimeType = cursor.getString(mimeIndex) ?: "*/*"
                    }
                }
            }

            // Fallback for MIME type
            if (mimeType == "*/*" || mimeType.isEmpty()) {
                mimeType = contentResolver.getType(uri) ?: "*/*"
            }

            Log.d(TAG, "[TRANSFER] Queued: Sending $fileName ($fileSize bytes, $mimeType)")

            // B. Send Header
            outputStream.writeUTF(fileName)
            outputStream.writeLong(fileSize)
            outputStream.writeUTF(mimeType)

            // C. Send Body
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var totalBytesSent: Long = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesSent += bytesRead
                    onProgress?.invoke(totalBytesSent, fileSize)
                }
            }

            outputStream.flush()
            outputStream.close()

            Log.d(TAG, "✅ [TRANSFER] Completed: File sent: $fileName")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "❌ [TRANSFER] Send File Error: ${e.message}")
            return@withContext false
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {
            }
        }
    }
}
