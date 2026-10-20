package com.example.service.control

import com.example.service.DeviceIdentity
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Control channel server (Sender side).
 *
 * Runs on port 8081, listens on wildcard address 0.0.0.0, accepts one connection at a time.
 * Handles mutual identity handshake, rejects same-device loops, verifies readiness,
 * and maintains connection liveness via heartbeats.
 */
class HandshakeServer {

    private val isRunning = AtomicBoolean(false)
    private val serverSocketRef = AtomicReference<ServerSocket?>(null)
    private val activeClientSocketRef = AtomicReference<Socket?>(null)

    private var serverScope: CoroutineScope? = null

    /**
     * Starts listening for control connections on the wildcard address (0.0.0.0).
     */
    @Synchronized
    fun start(
        ownIdentity: DeviceIdentity,
        port: Int = ControlChannelConstants.PORT,
        onPeerConnected: (peerUuid: String, peerName: String) -> Unit,
        onPeerLost: () -> Unit
    ) {
        if (isRunning.getAndSet(true)) {
            return
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        serverScope = scope

        scope.launch {
            val startTime = System.currentTimeMillis()
            var serverSocket: ServerSocket? = null
            try {
                // Wildcard 0.0.0.0 binding
                serverSocket = ServerSocket(port, 2, InetAddress.getByName("0.0.0.0"))
                serverSocketRef.set(serverSocket)
                ControlChannelConstants.logStep(
                    step = "server_started",
                    elapsedMs = System.currentTimeMillis() - startTime,
                    peerId = ownIdentity.uuid
                )

                while (isRunning.get() && !serverSocket.isClosed) {
                    val socket: Socket = try {
                        serverSocket.accept()
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        continue
                    }

                    // Enforce one active control connection at a time
                    if (!activeClientSocketRef.compareAndSet(null, socket)) {
                        ControlChannelConstants.logStep(
                            step = "busy_reject",
                            elapsedMs = System.currentTimeMillis() - startTime
                        )
                        try {
                            socket.close()
                        } catch (_: Exception) {}
                        continue
                    }

                    // Handle connection in background
                    launch {
                        handleClientConnection(
                            socket = socket,
                            ownIdentity = ownIdentity,
                            onPeerConnected = onPeerConnected,
                            onPeerLost = onPeerLost
                        )
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    ControlChannelConstants.logStep(
                        step = "server_error",
                        detail = e.javaClass.simpleName
                    )
                }
            } finally {
                try {
                    serverSocket?.close()
                } catch (_: Exception) {}
                serverSocketRef.set(null)
            }
        }
    }

    private fun handleClientConnection(
        socket: Socket,
        ownIdentity: DeviceIdentity,
        onPeerConnected: (peerUuid: String, peerName: String) -> Unit,
        onPeerLost: () -> Unit
    ) {
        val connStartTime = System.currentTimeMillis()
        var peerUuid: String? = null
        var isHandshakeCompleted = false

        try {
            socket.tcpNoDelay = true
            // Read HELLO with 5-second initial timeout
            socket.soTimeout = ControlChannelConstants.READY_TIMEOUT_MS

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val helloMsg = ControlMessage.readMessage(input)
            if (helloMsg !is ControlMessage.Hello) {
                ControlChannelConstants.logStep(
                    step = "invalid_hello",
                    elapsedMs = System.currentTimeMillis() - connStartTime
                )
                return
            }

            peerUuid = ControlChannelConstants.normalizeUuid(helloMsg.uuid)
            val ownUuid = ControlChannelConstants.normalizeUuid(ownIdentity.uuid)

            // Self-connection detection: reject same device
            if (peerUuid.equals(ownUuid, ignoreCase = true)) {
                ControlChannelConstants.logStep(
                    step = "same_device_detected",
                    peerId = peerUuid,
                    elapsedMs = System.currentTimeMillis() - connStartTime
                )
                ControlMessage.writeMessage(output, ControlMessage.Error("same_device"))
                return
            }

            // Valid peer HELLO -> Reply HELLO_ACK
            ControlMessage.writeMessage(
                output,
                ControlMessage.HelloAck(
                    uuid = ownUuid,
                    name = ownIdentity.displayName
                )
            )
            ControlChannelConstants.logStep(
                step = "hello_ack_sent",
                peerId = peerUuid,
                elapsedMs = System.currentTimeMillis() - connStartTime
            )

            // Wait for READY with 5-second timeout
            socket.soTimeout = ControlChannelConstants.READY_TIMEOUT_MS
            val readyMsg = ControlMessage.readMessage(input)
            if (readyMsg !is ControlMessage.Ready) {
                ControlChannelConstants.logStep(
                    step = "ready_missing_or_timeout",
                    peerId = peerUuid,
                    elapsedMs = System.currentTimeMillis() - connStartTime
                )
                return
            }

            // Reply READY_ACK
            ControlMessage.writeMessage(output, ControlMessage.ReadyAck)
            isHandshakeCompleted = true

            ControlChannelConstants.logStep(
                step = "connected",
                peerId = peerUuid,
                elapsedMs = System.currentTimeMillis() - connStartTime
            )
            onPeerConnected(peerUuid, helloMsg.name)

            // Heartbeat phase: keep socket open, reply PONG to each PING
            // If no PING arrives within 15 seconds, timeout and consider peer lost
            socket.soTimeout = ControlChannelConstants.SERVER_HEARTBEAT_TIMEOUT_MS
            while (isRunning.get() && !socket.isClosed) {
                val pingMsg = try {
                    ControlMessage.readMessage(input)
                } catch (te: SocketTimeoutException) {
                    ControlChannelConstants.logStep(
                        step = "heartbeat_timeout",
                        peerId = peerUuid,
                        elapsedMs = System.currentTimeMillis() - connStartTime
                    )
                    break
                } catch (ioe: IOException) {
                    break
                }

                if (pingMsg == null) {
                    // Socket closed or malformed/EOF
                    break
                }

                if (pingMsg is ControlMessage.Ping) {
                    ControlMessage.writeMessage(output, ControlMessage.Pong)
                    ControlChannelConstants.logStep(
                        step = "pong_sent",
                        peerId = peerUuid
                    )
                }
            }
        } catch (e: Exception) {
            ControlChannelConstants.logStep(
                step = "connection_error",
                peerId = peerUuid,
                detail = e.javaClass.simpleName,
                elapsedMs = System.currentTimeMillis() - connStartTime
            )
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
            activeClientSocketRef.compareAndSet(socket, null)

            if (isHandshakeCompleted && isRunning.get()) {
                ControlChannelConstants.logStep(
                    step = "peer_lost",
                    peerId = peerUuid,
                    elapsedMs = System.currentTimeMillis() - connStartTime
                )
                onPeerLost()
            }
        }
    }

    /**
     * Stops the control server, closes all sockets, and cancels background tasks.
     * Safe to call multiple times.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        try {
            activeClientSocketRef.getAndSet(null)?.close()
        } catch (_: Exception) {}

        try {
            serverSocketRef.getAndSet(null)?.close()
        } catch (_: Exception) {}

        serverScope?.cancel()
        serverScope = null

        ControlChannelConstants.logStep(step = "server_stopped")
    }
}
