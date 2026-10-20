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

    /**
     * Executes the control channel handshake synchronously on IO thread.
     */
    suspend fun connect(
        socketFactory: SocketFactory? = null,
        host: String,
        expectedPeerUuid: String,
        ownIdentity: DeviceIdentity,
        port: Int = ControlChannelConstants.PORT,
        onPeerLost: (() -> Unit)? = null
    ): HandshakeResult = withContext(Dispatchers.IO) {
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

            // 2. Send HELLO with own identity
            val hello = ControlMessage.Hello(
                app = "flip",
                v = 1,
                uuid = ownNorm,
                name = ownIdentity.displayName
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
                onPeerLost = onPeerLost
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
        onPeerLost: (() -> Unit)?
    ) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        clientScope = scope

        val consecutiveMisses = AtomicInteger(0)

        heartbeatJob = scope.launch {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            while (isActive && !isClosed.get() && !socket.isClosed) {
                delay(ControlChannelConstants.HEARTBEAT_INTERVAL_MS)
                if (!isActive || isClosed.get() || socket.isClosed) break

                var pongReceived = false
                try {
                    // Send PING
                    ControlMessage.writeMessage(output, ControlMessage.Ping)
                    ControlChannelConstants.logStep(
                        step = "ping_sent",
                        peerId = peerUuid
                    )

                    // Expect PONG within 3s
                    socket.soTimeout = ControlChannelConstants.PONG_TIMEOUT_MS
                    val response = ControlMessage.readMessage(input)
                    if (response is ControlMessage.Pong) {
                        pongReceived = true
                        consecutiveMisses.set(0)
                        ControlChannelConstants.logStep(
                            step = "pong_received",
                            peerId = peerUuid
                        )
                    }
                } catch (_: Exception) {
                    pongReceived = false
                }

                if (!pongReceived) {
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
                        onPeerLost?.invoke()
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

        clientScope?.cancel()
        clientScope = null

        try {
            socketRef.getAndSet(null)?.close()
        } catch (_: Exception) {}

        ControlChannelConstants.logStep(step = "client_closed")
    }
}
