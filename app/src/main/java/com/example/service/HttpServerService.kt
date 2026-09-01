package com.example.service

import android.util.Log
import com.example.model.Device
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors

class HttpServerService(private val context: android.content.Context, private val listener: ServerListener) {
    private var serverSocket: ServerSocket? = null
    // ✅ FIX: Use a bounded thread pool to prevent OutOfMemoryError (DoS) from excessive connections.
    // Max 10 threads is more than enough for local P2P transfers (realistically only 1-2 active).
    private val executor = Executors.newFixedThreadPool(10)
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val deviceAdapter = moshi.adapter(Device::class.java)

    var boundPort: Int = -1
        private set

    @Volatile
    private var isRunning = false

    interface ServerListener {
        fun onDeviceConnected(device: Device)
        fun onDeviceDisconnected()
        fun onTextReceived(text: String)
        fun onFileTransferStarted(transferId: String, fileName: String, fileSize: Long, isIncoming: Boolean)
        fun onFileTransferProgress(transferId: String, bytesTransferred: Long)
        fun onFileTransferCompleted(transferId: String, filePath: String)
        fun onFileTransferFailed(transferId: String, errorMessage: String)
        fun onRemoteCancelled(transferId: String)
    }

    @Volatile
    private var activeIncomingTransferId: String? = null

    @Volatile
    private var isCancelled = false

    fun startServer(): Int {
        try {
            // ✅ FIX: Pass 0 to let the OS assign a guaranteed available ephemeral port.
            // This completely eliminates port collision crashes.
            val sSocket = ServerSocket(0)
            serverSocket = sSocket
            boundPort = sSocket.localPort
            isRunning = true

            // Start a background thread to accept connections
            executor.submit {
                while (isRunning) {
                    try {
                        val clientSocket = sSocket.accept()
                        executor.submit {
                            handleClientSocket(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                        Log.e(TAG, "Error accepting socket connection: ${e.message}")
                    }
                }
            }

            Log.d(TAG, "Custom HTTP Server started successfully on port $boundPort")
            return boundPort
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to an available port: ${e.message}")
            throw IllegalStateException("Unable to start HTTP server: ${e.message}")
        }
    }

    fun stopServer() {
        try {
            isRunning = false
            serverSocket?.close()
            serverSocket = null
            boundPort = -1
            executor.shutdownNow()
            Log.d(TAG, "Custom HTTP Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping HTTP Server: ${e.message}")
        }
    }

    fun cancelActiveTransfer() {
        isCancelled = true
    }

    private fun handleClientSocket(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1. Read HTTP Request Headers line by line
            val headerLines = mutableListOf<String>()
            val lineBuffer = ByteArrayOutputStream()
            while (isRunning) {
                val b = input.read()
                if (b == -1) break
                if (b == '\n'.code) {
                    val line = lineBuffer.toString("UTF-8").trim()
                    if (line.isEmpty()) {
                        break // Headers finished
                    }
                    headerLines.add(line)
                    lineBuffer.reset()
                } else if (b != '\r'.code) {
                    lineBuffer.write(b)
                }
            }

            if (headerLines.isEmpty()) {
                socket.close()
                return
            }

            val requestLine = headerLines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendResponse(output, 400, "Bad Request", "text/plain", "Bad Request".toByteArray())
                socket.close()
                return
            }

            val method = parts[0].uppercase()
            val fullPath = parts[1]

            // Parse headers into a case-insensitive map (keys lowercased)
            val headers = mutableMapOf<String, String>()
            for (i in 1 until headerLines.size) {
                val h = headerLines[i]
                val colonIndex = h.indexOf(':')
                if (colonIndex != -1) {
                    val name = h.substring(0, colonIndex).trim().lowercase()
                    val value = h.substring(colonIndex + 1).trim()
                    headers[name] = value
                }
            }

            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

            // Match route
            val uri = URI(fullPath)
            val path = uri.path

            when {
                path == "/connect" && method == "POST" -> {
                    val body = readBodyAsString(input, contentLength)
                    val device = deviceAdapter.fromJson(body)
                    if (device != null) {
                        listener.onDeviceConnected(device)
                        val responseJson = deviceAdapter.toJson(
                            Device(
                                id = "local_device",
                                name = android.os.Build.MODEL,
                                ip = socket.localAddress.hostAddress ?: "127.0.0.1",
                                port = boundPort
                            )
                        )
                        sendResponse(output, 200, "OK", "application/json", responseJson.toByteArray())
                    } else {
                        sendResponse(output, 400, "Bad Request", "text/plain", "Invalid Payload".toByteArray())
                    }
                }
                path == "/disconnect" -> {
                    listener.onDeviceDisconnected()
                    sendResponse(output, 200, "OK", "text/plain", "Disconnected".toByteArray())
                }
                path == "/text" && method == "POST" -> {
                    val text = readBodyAsString(input, contentLength)
                    listener.onTextReceived(text)
                    sendResponse(output, 200, "OK", "text/plain", "OK".toByteArray())
                }
                path == "/upload" && method == "POST" -> {
                    handleUpload(input, output, headers, contentLength)
                }
                path == "/offset" && method == "GET" -> {
                    val query = uri.query ?: ""
                    var transferId = ""
                    var fileName = ""
                    query.split("&").forEach { param ->
                        val pair = param.split("=")
                        if (pair.size == 2) {
                            val key = pair[0]
                            val value = URLDecoder.decode(pair[1], "UTF-8")
                            if (key == "transferId") transferId = value
                            if (key == "fileName") fileName = value
                        }
                    }
                    if (transferId.isEmpty() || fileName.isEmpty()) {
                        sendResponse(output, 400, "Bad Request", "text/plain", "Missing transferId or fileName".toByteArray())
                    } else {
                        val offset = StorageService.getConfirmedOffset(transferId, fileName)
                        sendResponse(output, 200, "OK", "text/plain", offset.toString().toByteArray())
                    }
                }
                path == "/cancel" && method == "POST" -> {
                    val transferId = headers["x-transfer-id"] ?: ""
                    if (transferId.isNotEmpty()) {
                        listener.onRemoteCancelled(transferId)
                        sendResponse(output, 200, "OK", "text/plain", "OK".toByteArray())
                    } else {
                        sendResponse(output, 400, "Bad Request", "text/plain", "Missing X-Transfer-Id header".toByteArray())
                    }
                }
                else -> {
                    sendResponse(output, 404, "Not Found", "text/plain", "Not Found".toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client socket: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (ex: Exception) {}
        }
    }

    private fun readBodyAsString(input: InputStream, contentLength: Long): String {
        if (contentLength <= 0) return ""
        val bytes = ByteArray(contentLength.toInt())
        var totalRead = 0
        while (totalRead < contentLength) {
            val read = input.read(bytes, totalRead, (contentLength - totalRead).toInt())
            if (read == -1) break
            totalRead += read
        }
        return String(bytes, 0, totalRead, Charsets.UTF_8)
    }

    private fun handleUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        contentLength: Long
    ) {
        val transferId = headers["x-transfer-id"] ?: ""
        val rawFileName = headers["x-file-name"] ?: "unnamed_file"
        val fileName = try { URLDecoder.decode(rawFileName, "UTF-8") } catch (e: Exception) { rawFileName }
        val fileSizeStr = headers["x-file-size"] ?: "0"
        val fileSize = fileSizeStr.toLongOrNull() ?: 0L
        val offsetStr = headers["x-file-offset"] ?: "0"
        val offset = offsetStr.toLongOrNull() ?: 0L

        if (transferId.isEmpty()) {
            sendResponse(output, 400, "Bad Request", "text/plain", "X-Transfer-Id header missing".toByteArray())
            return
        }

        activeIncomingTransferId = transferId
        isCancelled = false
        Log.d(TAG, "[FILE_TRANSFER_RECEIVER] Metadata received - File Name: $fileName, Size: $fileSize, Offset: $offset, ID: $transferId, Content-Length: $contentLength")

        val partialFile = StorageService.getPartialFile(transferId, fileName)
        val offsetFile = StorageService.getOffsetFile(transferId, fileName)

        listener.onFileTransferStarted(transferId, fileName, fileSize, isIncoming = true)

        var outputStream: FileOutputStream? = null
        var success = false
        var bytesWritten = offset

        try {
            val append = offset > 0 && partialFile.exists()
            outputStream = FileOutputStream(partialFile, append)
            Log.d(TAG, "[FILE_TRANSFER_RECEIVER] Output stream opened successfully for: ${partialFile.absolutePath}")

            val buffer = ByteArray(64 * 1024) // 64KB buffer
            var remaining = contentLength
            if (remaining <= 0) {
                remaining = Long.MAX_VALUE
            }
            var lastProgressUpdate = System.currentTimeMillis()
            var lastLogUpdate = System.currentTimeMillis()
            var lastOffsetUpdate = System.currentTimeMillis()
            val OFFSET_WRITE_INTERVAL_MS = 2000L // FIX: Throttle disk writes to every 2 seconds

            while (remaining > 0) {
                val limit = minOf(buffer.size.toLong(), remaining).toInt()
                val bytesRead = input.read(buffer, 0, limit)
                if (bytesRead == -1) {
                    Log.d(TAG, "[FILE_TRANSFER_RECEIVER] Input stream reached end of stream.")
                    break
                }
                if (isCancelled) {
                    Log.w(TAG, "[FILE_TRANSFER_RECEIVER] Upload cancelled locally")
                    throw InterruptedException("Cancelled")
                }
                outputStream.write(buffer, 0, bytesRead)
                bytesWritten += bytesRead
                remaining -= bytesRead

                val now = System.currentTimeMillis()

                // ✅ FIX: Throttle offset file writes to prevent flash storage thrashing
                if (now - lastOffsetUpdate > OFFSET_WRITE_INTERVAL_MS) {
                    offsetFile.writeText(bytesWritten.toString())
                    lastOffsetUpdate = now
                }

                if (now - lastProgressUpdate > 150) { // Throttle UI updates to ~150ms for performance
                    listener.onFileTransferProgress(transferId, bytesWritten)
                    lastProgressUpdate = now
                }
                
                // Log progress every 1 second
                if (now - lastLogUpdate > 1000) {
                    Log.d(TAG, "[FILE_TRANSFER_RECEIVER] Bytes received and written: $bytesWritten / $fileSize (${if (fileSize > 0) (bytesWritten * 100f / fileSize).toInt() else 0}%)")
                    lastLogUpdate = now
                }
            }

            // ✅ FIX: Safety net. Ensure the absolute latest offset is saved if the loop exits 
            // (e.g., due to cancellation, error, or sudden EOF) before the 2-second window elapsed.
            offsetFile.writeText(bytesWritten.toString())

            outputStream.flush()
            success = true
            Log.d(TAG, "[FILE_TRANSFER_RECEIVER] File receipt/writing completed. Total bytes: $bytesWritten / $fileSize")
        } catch (e: InterruptedException) {
            Log.w(TAG, "[FILE_TRANSFER_RECEIVER] Transfer cancelled by local or remote action.")
            listener.onFileTransferFailed(transferId, "Transfer Cancelled")
            sendResponse(output, 499, "Client Closed Request", "text/plain", "Client Closed Request".toByteArray())
            return
        } catch (e: Exception) {
            Log.e(TAG, "[FILE_TRANSFER_RECEIVER] Error writing file: ${e.message}")
            listener.onFileTransferFailed(transferId, e.message ?: "Error")
            sendResponse(output, 500, "Internal Server Error", "text/plain", "Error: ${e.message}".toByteArray())
            return
        } finally {
            try {
                outputStream?.close()
            } catch (ex: Exception) {
                // Ignore
            }
            activeIncomingTransferId = null
        }

        if (success) {
            val finalizedFile = StorageService.finalizeTransfer(context, transferId, fileName)
            if (finalizedFile != null) {
                listener.onFileTransferCompleted(transferId, finalizedFile.absolutePath)
                sendResponse(output, 200, "OK", "text/plain", "OK".toByteArray())
            } else {
                listener.onFileTransferFailed(transferId, "Failed to finalize file rename")
                sendResponse(output, 500, "Internal Server Error", "text/plain", "Finalization failed".toByteArray())
            }
        }
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray = ByteArray(0)
    ) {
        try {
            val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.UTF_8))
            if (body.isNotEmpty()) {
                output.write(body)
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HTTP response: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HttpServerService"
    }
}
