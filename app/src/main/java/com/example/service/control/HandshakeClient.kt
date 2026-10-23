package com.example.service.control

import com.example.service.DeviceIdentity
import kotlinx.coroutines.*
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * Result of the HandshakeClient connection attempt.
 */
sealed class HandshakeResult {
    object Success : HandshakeResult()
    data class Timeout(val detail: String) : HandshakeResult()
    data class Refused(val detail: String) : HandshakeResult()
    object SameDevice : HandshakeResult()
    data class IdMismatch(val expected: String, val actual: String) : HandshakeResult()
    data class Malformed(val detail: String) : HandshakeResult()
}

/**
 * Control channel client (Receiver side).
 *
 * Connects to the sender's control server on port 8081 with a 3-second connect timeout,
 * performs mutual identity verification, sends readiness confirmation,
 * and maintains continuous heartbeat checks (PING every 5s, 3s PONG timeout, 3 misses before lost).
 */
class HandshakeClient {

    private val isClosed = AtomicBoolean(false)
    private val socketRef = AtomicReference<Socket?>(null)
    private var clientScope: CoroutineScope? = null
    private var heartbeatJob: Job? = null
    private var readerJob: Job? = null
    private val writeLock = Any()

    var onPeerBye: ((peerName: String) -> Unit)? = null
    var onPeerLost: (() -> Unit)? = null

    var peerTransferPort: Int = 0
        private set

    fun isAlive(): Boolean {
        val s = socketRef.get()
        return s != null && !s.isClosed && s.isConnected && !isClosed.get()
    }

    fun sendBye(name: String) {
        val socket = socketRef.get() ?: return
        try {
            socket.soTimeout = 500
            val output = socket.getOutputStream()
            synchronized(writeLock) {
                ControlMessage.writeMessage(output, ControlMessage.Bye(name))
            }
            android.util.Log.i("FlipLink", "[CONTROL] Sent BYE from client: $name")
        } catch (e: Exception) {
            android.util.Log.w("FlipLink", "Failed to send BYE from client: ${e.message}")
        }
    }

    /**
     * Executes the control channel handshake synchronously on IO thread.
     */
    suspend fun connect(
        socketFactory: SocketFactory? = null,
        host: String,
        expectedPeerUuid: String,
        ownIdentity: DeviceIdentity,
        port: Int = ControlChannelConstants.PORT,
        localTransferPort: Int = 0,
        localIp: String? = null,
        onPeerLost: (() -> Unit)? = null,
        onPeerBye: ((peerName: String) -> Unit)? = null
    ): HandshakeResult = withContext(Dispatchers.IO) {
        if (onPeerLost != null) this@HandshakeClient.onPeerLost = onPeerLost
        if (onPeerBye != null) this@HandshakeClient.onPeerBye = onPeerBye
        val startTime = System.currentTimeMillis()
        val expectedNorm = ControlChannelConstants.normalizeUuid(expectedPeerUuid)
        val ownNorm = ControlChannelConstants.normalizeUuid(ownIdentity.uuid)

        val factory = socketFactory ?: SocketFactory.getDefault()
        val socket = factory.createSocket() ?: return@withContext HandshakeResult.Refused("Unable to create socket")
        socketRef.set(socket)

        try {
            socket.tcpNoDelay = true

            // 1. Connect timeout: 3s
            try {
                socket.connect(InetSocketAddress(host, port), ControlChannelConstants.CONNECT_TIMEOUT_MS)
            } catch (e: SocketTimeoutException) {
                ControlChannelConstants.logStep(
                    step = "connect_timeout",
                    elapsedMs = System.currentTimeMillis() - startTime,
                    expectedId = expectedNorm
                )
                close()
                return@withContext HandshakeResult.Timeout("Connection timed out after 3s")
            } catch (e: ConnectException) {
                ControlChannelConstants.logStep(
                    step = "connect_refused",
                    elapsedMs = System.currentTimeMillis() - startTime,
                    expectedId = expectedNorm
                )
                close()
                return@withContext HandshakeResult.Refused("Connection refused by peer")
            } catch (e: IOException) {
                close()
                return@withContext HandshakeResult.Refused("Network connection failed: ${e.message}")
            }

            ControlChannelConstants.logStep(
                step = "socket_connected",
                elapsedMs = System.currentTimeMillis() - startTime,
                expectedId = expectedNorm
            )

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 2. Send HELLO with own identity and actual httpPort
            val ownIp = localIp ?: ControlChannelConstants.toIpv4(socket.localAddress)
            val hello = ControlMessage.Hello(
                app = "flip",
                v = 1,
                uuid = ownNorm,
                name = ownIdentity.displayName,
                port = localTransferPort,
                httpPort = localTransferPort,
                ip = ownIp
            )
            ControlMessage.writeMessage(output, hello)
            ControlChannelConstants.logStep(
                step = "hello_sent",
                elapsedMs = System.currentTimeMillis() - startTime,
                expectedId = expectedNorm
            )

            // 3. Read HELLO_ACK with 3s timeout
            socket.soTimeout = ControlChannelConstants.CONNECT_TIMEOUT_MS
            val ackMsg = try {
                ControlMessage.readMessage(input)
            } catch (e: SocketTimeoutException) {
                close()
                return@withContext HandshakeResult.Timeout("Timed out waiting for HELLO_ACK")
            }

            if (ackMsg == null) {
                close()
                return@withContext HandshakeResult.Malformed("Empty or oversized response from peer")
            }

            // Check if server rejected as same device
            if (ackMsg is ControlMessage.Error && ackMsg.error.equals("same_device", ignoreCase = true)) {
                ControlChannelConstants.logStep(
                    step = "same_device_received",
                    elapsedMs = System.currentTimeMillis() - startTime,
                    expectedId = expectedNorm
                )
                close()
                return@withContext HandshakeResult.SameDevice
            }

            if (ackMsg !is ControlMessage.HelloAck) {
                ControlChannelConstants.logStep(
                    step = "malformed_ack",
                    elapsedMs = System.currentTimeMillis() - startTime,
                    expectedId = expectedNorm
                )
                close()
                return@withContext HandshakeResult.Malformed("Expected HELLO_ACK, received ${ackMsg.javaClass.simpleName}")
            }

            peerTransferPort = if (ackMsg.httpPort > 0) ackMsg.httpPort else ackMsg.port

            // 4. Verify received UUID matches expectedPeerUuid
            val receivedNorm = ControlChannelConstants.normalizeUuid(ackMsg.uuid)
            if (!receivedNorm.equals(expectedNorm, ignoreCase = true)) {
                ControlChannelConstants.logStep(
                    step = "id_mismatch",
                    expectedId = expectedNorm,
                    actualId = receivedNorm,
                    elapsedMs = System.currentTimeMillis() - startTime
                )
                close()
                return@withContext HandshakeResult.IdMismatch(
                    expected = expectedNorm,
                    actual = receivedNorm
                )
            }

            ControlChannelConstants.logStep(
                step = "peer_verified",
                peerId = receivedNorm,
                elapsedMs = System.currentTimeMillis() - startTime
            )

            // 5. Send READY
            ControlMessage.writeMessage(output, ControlMessage.Ready)
            ControlChannelConstants.logStep(
                step = "ready_sent",
                peerId = receivedNorm,
                elapsedMs = System.currentTimeMillis() - startTime
            )

            // 6. Wait for READY_ACK (5s timeout)
            socket.soTimeout = ControlChannelConstants.READY_TIMEOUT_MS
            val readyAck = try {
                ControlMessage.readMessage(input)
            } catch (e: SocketTimeoutException) {
                close()
                return@withContext HandshakeResult.Timeout("Timed out waiting for READY_ACK")
            }

            if (readyAck !is ControlMessage.ReadyAck) {
                ControlChannelConstants.logStep(
                    step = "malformed_ready_ack",
                    peerId = receivedNorm,
                    elapsedMs = System.currentTimeMillis() - startTime
                )
                close()
                return@withContext HandshakeResult.Malformed("Expected READY_ACK")
            }

            ControlChannelConstants.logStep(
                step = "ready_ack_received",
                peerId = receivedNorm,
                elapsedMs = System.currentTimeMillis() - startTime
            )

            // Handshake succeeded! Start heartbeat routine
            startHeartbeat(
                socket = socket,
                peerUuid = receivedNorm,
                peerName = ackMsg.name
            )

            android.util.Log.i(
                "FlipLink",
                "HandshakeClient verified: own=.${ControlChannelConstants.lastOctet(ownIp)}:$localTransferPort, peer=.${ControlChannelConstants.lastOctet(host)}:$peerTransferPort"
            )

            return@withContext HandshakeResult.Success
        } catch (e: Exception) {
            close()
            return@withContext HandshakeResult.Malformed("Unexpected error during handshake: ${e.message}")
        }
    }

    private fun startHeartbeat(
        socket: Socket,
        peerUuid: String,
        peerName: String
    ) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        clientScope = scope

        val consecutiveMisses = AtomicInteger(0)
        val isByeReceived = AtomicBoolean(false)

        // Continuous reader coroutine for immediate BYE and PONG reception
        readerJob = scope.launch {
            val input = socket.getInputStream()
            while (isActive && !isClosed.get() && !socket.isClosed) {
                val msg = try {
                    socket.soTimeout = ControlChannelConstants.SERVER_HEARTBEAT_TIMEOUT_MS
                    ControlMessage.readMessage(input)
                } catch (te: SocketTimeoutException) {
                    null
                } catch (ioe: IOException) {
                    null
                } catch (e: Exception) {
                    null
                }

                if (msg == null) {
                    break
                }

                when (msg) {
                    is ControlMessage.Pong -> {
                        consecutiveMisses.set(0)
                        ControlChannelConstants.logStep(
                            step = "pong_received",
                            peerId = peerUuid
                        )
                    }
                    is ControlMessage.Bye -> {
                        isByeReceived.set(true)
                        val name = if (msg.name.isNotBlank()) msg.name else peerName
                        android.util.Log.i("FlipLink", "[CONTROL] Client received BYE from server: $name")
                        close()
                        onPeerBye?.invoke(name)
                        break
                    }
                    else -> {}
                }
            }

            if (!isClosed.get() && !isByeReceived.get()) {
                ControlChannelConstants.logStep(
                    step = "heartbeat_lost",
                    peerId = peerUuid
                )
                close()
                onPeerLost?.invoke()
            }
        }

        heartbeatJob = scope.launch {
            val output = socket.getOutputStream()

            while (isActive && !isClosed.get() && !socket.isClosed) {
                delay(ControlChannelConstants.HEARTBEAT_INTERVAL_MS)
                if (!isActive || isClosed.get() || socket.isClosed) break

                try {
                    synchronized(writeLock) {
                        ControlMessage.writeMessage(output, ControlMessage.Ping)
                    }
                    ControlChannelConstants.logStep(
                        step = "ping_sent",
                        peerId = peerUuid
                    )
                } catch (e: Exception) {
                    val misses = consecutiveMisses.incrementAndGet()
                    ControlChannelConstants.logStep(
                        step = "ping_miss",
                        peerId = peerUuid,
                        detail = "misses=$misses"
                    )

                    if (misses >= ControlChannelConstants.MAX_HEARTBEAT_MISSES) {
                        ControlChannelConstants.logStep(
                            step = "heartbeat_lost",
                            peerId = peerUuid
                        )
                        close()
                        if (!isByeReceived.get()) {
                            onPeerLost?.invoke()
                        }
                        break
                    }
                }
            }
        }
    }

    /**
     * Closes the control channel socket and cancels the heartbeat job.
     * Safe to call multiple times.
     */
    @Synchronized
    fun close() {
        if (isClosed.getAndSet(true)) {
            return
        }

        heartbeatJob?.cancel()
        heartbeatJob = null

        readerJob?.cancel()
        readerJob = null

        clientScope?.cancel()
        clientScope = null

        try {
            socketRef.getAndSet(null)?.close()
        } catch (_: Exception) {}

        ControlChannelConstants.logStep(step = "client_closed")
    }
}
