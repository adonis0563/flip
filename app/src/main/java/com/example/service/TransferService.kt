package com.example.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.model.Device
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TransferService(private val context: Context) {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val deviceAdapter = moshi.adapter(Device::class.java)

    // Dedicated fast client with custom timeouts
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for large streams
        .writeTimeout(0, TimeUnit.MILLISECONDS) // Indefinite write timeout for large streams
        .build()

    // 3-second timeout client for handshake checks
    private val handshakeClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "TransferService"
    }

    /**
     * Tries to establish connection with a remote receiver's HTTP server
     */
    fun connectToDevice(remoteIp: String, remotePort: Int, localDevice: Device, onSuccess: (Device) -> Unit, onError: (String) -> Unit) {
        val url = "http://$remoteIp:$remotePort/connect"
        Log.d(TAG, "[HANDSHAKE_STAGE] Handshake started with $remoteIp:$remotePort")

        val json = deviceAdapter.toJson(localDevice)
        val body = json.toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val reason = e.message ?: "Connection failed"
                Log.e(TAG, "[HANDSHAKE_STAGE] Handshake failed with $remoteIp:$remotePort: $reason")
                onError(reason)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: ""
                        try {
                            val remoteDevice = deviceAdapter.fromJson(responseBody)
                            if (remoteDevice != null) {
                                Log.d(TAG, "[HANDSHAKE_STAGE] Handshake succeeded with $remoteIp:$remotePort (${remoteDevice.name})")
                                onSuccess(remoteDevice.copy(ip = remoteIp, port = remotePort))
                            } else {
                                val reason = "Invalid response payload from device"
                                Log.e(TAG, "[HANDSHAKE_STAGE] Handshake failed with $remoteIp:$remotePort: $reason")
                                onError(reason)
                            }
                        } catch (e: Exception) {
                            val reason = "Failed to parse device pairing response: ${e.message}"
                            Log.e(TAG, "[HANDSHAKE_STAGE] Handshake failed with $remoteIp:$remotePort: $reason")
                            onError("Failed to parse device pairing response")
                        }
                    } else {
                        val reason = "Device rejected connection (HTTP ${response.code})"
                        Log.e(TAG, "[HANDSHAKE_STAGE] Handshake failed with $remoteIp:$remotePort: $reason")
                        onError("Device rejected connection (HTTP ${response.code})")
                    }
                }
            }
        })
    }

    /**
     * Sends text payload to connected receiver
     */
    fun sendText(remoteIp: String, remotePort: Int, text: String, onResult: (Boolean, String?) -> Unit) {
        val url = "http://$remoteIp:$remotePort/text"
        val body = text.toRequestBody("text/plain".toMediaTypeOrNull())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        onResult(true, null)
                    } else {
                        onResult(false, "Server error: ${response.code}")
                    }
                }
            }
        })
    }

    /**
     * Gets the confirmed offset for a resume from the receiver
     * Rules: 3-second timeout, one retry. No silent fallback to zero.
     */
    fun getReceiverOffset(remoteIp: String, remotePort: Int, transferId: String, fileName: String): Long {
        val encodedFileName = URLEncoder.encode(fileName, "UTF-8")
        val url = "http://$remoteIp:$remotePort/offset?transferId=$transferId&fileName=$encodedFileName"
        val request = Request.Builder().url(url).get().build()

        var attempts = 0
        val maxAttempts = 2 // 1 initial + 1 retry
        var lastException: IOException? = null

        while (attempts < maxAttempts) {
            attempts++
            try {
                handshakeClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "0"
                        return body.toLongOrNull() ?: 0L
                    } else {
                        throw IOException("Server returned error code: ${response.code}")
                    }
                }
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Offset query attempt $attempts failed: ${e.message}")
                if (attempts < maxAttempts) {
                    Thread.sleep(500) // Brief delay before retrying
                }
            }
        }
        throw lastException ?: IOException("Failed to connect for offset query")
    }

    /**
     * Streaming upload implementation
     */
    fun uploadFile(
        remoteIp: String,
        remotePort: Int,
        transferId: String,
        fileName: String,
        fileUriStr: String,
        offset: Long,
        totalSize: Long,
        cancelCheck: () -> Boolean,
        onProgress: (Long) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val url = "http://$remoteIp:$remotePort/upload"
        val encodedFileName = URLEncoder.encode(fileName, "UTF-8")

        Log.d(TAG, "[FILE_TRANSFER] Selected URI: $fileUriStr, File Name: $fileName, File Size: $totalSize bytes, Offset: $offset bytes")

        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = totalSize - offset

            override fun writeTo(sink: BufferedSink) {
                val uri = Uri.parse(fileUriStr)
                val inputStream = try {
                    if (uri.scheme == "content" && uri.host == "mock") {
                        Log.d(TAG, "[FILE_TRANSFER] Opening mock stream for URI: $fileUriStr")
                        object : java.io.InputStream() {
                            private var count = 0L
                            override fun read(): Int {
                                return if (count++ < totalSize) 0 else -1
                            }
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (count >= totalSize) return -1
                                val remaining = totalSize - count
                                val toRead = java.lang.Math.min(len.toLong(), remaining).toInt()
                                java.util.Arrays.fill(b, off, off + toRead, 0.toByte())
                                count += toRead
                                return toRead
                            }
                            override fun skip(n: Long): Long {
                                val remaining = totalSize - count
                                val toSkip = java.lang.Math.min(n, remaining)
                                count += toSkip
                                return toSkip
                            }
                        }
                    } else if (uri.scheme == "file" || uri.path?.startsWith("/") == true) {
                        val filePath = if (uri.scheme == "file") uri.path else fileUriStr
                        Log.d(TAG, "[FILE_TRANSFER] Opening FileInputStream for file path: $filePath")
                        java.io.FileInputStream(java.io.File(filePath!!))
                    } else {
                        Log.d(TAG, "[FILE_TRANSFER] Opening ContentResolver stream for URI: $uri")
                        context.contentResolver.openInputStream(uri)
                            ?: throw IOException("Failed to open file stream")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[FILE_TRANSFER] Failed to open stream for URI: $fileUriStr, error: ${e.message}")
                    throw e
                }

                Log.d(TAG, "[FILE_TRANSFER] Stream opened successfully for URI: $fileUriStr")

                inputStream.use { stream ->
                    if (offset > 0) {
                        Log.d(TAG, "[FILE_TRANSFER] Skipping stream to offset: $offset")
                        var remainingToSkip = offset
                        while (remainingToSkip > 0) {
                            val skipped = stream.skip(remainingToSkip)
                            if (skipped > 0) {
                                remainingToSkip -= skipped
                            } else {
                                // skip returned 0, try reading a single byte
                                if (stream.read() == -1) {
                                    break // EOF reached prematurely
                                }
                                remainingToSkip--
                            }
                        }
                        if (remainingToSkip > 0) {
                            throw IOException("Failed to skip input stream to offset $offset. Remaining: $remainingToSkip")
                        }
                        Log.d(TAG, "[FILE_TRANSFER] Stream successfully skipped to offset: $offset")
                    }

                    val buffer = ByteArray(64 * 1024)
                    var bytesWritten = offset
                    var lastUpdate = System.currentTimeMillis()
                    var lastLogUpdate = System.currentTimeMillis()

                    while (true) {
                        if (cancelCheck()) {
                            Log.w(TAG, "[FILE_TRANSFER] Transfer cancelled by user check.")
                            throw IOException("Transfer cancelled")
                        }
                        
                        // Active blocking pause check to keep underlying connection open
                        while (TransferManager.isPaused(transferId)) {
                            if (cancelCheck()) {
                                Log.w(TAG, "[FILE_TRANSFER] Transfer cancelled during pause.")
                                throw IOException("Transfer cancelled")
                            }
                            try {
                                Thread.sleep(100)
                            } catch (e: InterruptedException) {
                                throw IOException("Transfer interrupted")
                            }
                        }

                        val read = stream.read(buffer)
                        if (read == -1) {
                            Log.d(TAG, "[FILE_TRANSFER] End of stream reached.")
                            break
                        }

                        sink.write(buffer, 0, read)
                        bytesWritten += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 150) {
                            onProgress(bytesWritten)
                            lastUpdate = now
                        }
                        // Log progress every 1 second to keep logs readable but informative
                        if (now - lastLogUpdate > 1000) {
                            Log.d(TAG, "[FILE_TRANSFER] Bytes transferred: $bytesWritten / $totalSize (${(bytesWritten * 100f / totalSize).toInt()}%)")
                            lastLogUpdate = now
                        }
                    }
                    sink.flush()
                    onProgress(bytesWritten)
                    Log.d(TAG, "[FILE_TRANSFER] Stream writing completed. Total bytes written to sink: $bytesWritten / $totalSize")
                }
            }
        }

        Log.d(TAG, "[FILE_TRANSFER] Metadata sent: URL=$url, X-Transfer-Id=$transferId, X-File-Name=$encodedFileName, X-File-Size=$totalSize, X-File-Offset=$offset")

        val request = Request.Builder()
            .url(url)
            .header("X-Transfer-Id", transferId)
            .header("X-File-Name", encodedFileName)
            .header("X-File-Size", totalSize.toString())
            .header("X-File-Offset", offset.toString())
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "[FILE_TRANSFER] Upload failure: ${e.message}")
                onError(e.message ?: "Upload failed")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        Log.d(TAG, "[FILE_TRANSFER] Receiver acknowledged: HTTP ${response.code} (Success) for file $fileName")
                        onSuccess()
                    } else {
                        Log.e(TAG, "[FILE_TRANSFER] Receiver rejected: HTTP ${response.code} (Failure) for file $fileName")
                        onError("Receiver rejected (HTTP ${response.code})")
                    }
                }
            }
        })
    }

    /**
     * Notifies the receiver that we cancelled a specific transfer
     */
    fun notifyCancellation(remoteIp: String, remotePort: Int, transferId: String) {
        val url = "http://$remoteIp:$remotePort/cancel"
        val request = Request.Builder()
            .url(url)
            .header("X-Transfer-Id", transferId)
            .post("".toRequestBody())
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Ignore failures for background cleanup notice
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    /**
     * Sends disconnect notification to remote device
     */
    fun notifyDisconnect(remoteIp: String, remotePort: Int) {
        val url = "http://$remoteIp:$remotePort/disconnect"
        val request = Request.Builder().url(url).post("".toRequestBody()).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }
}
