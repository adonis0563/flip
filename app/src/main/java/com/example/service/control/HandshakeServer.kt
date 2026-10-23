package com.example.service.control

import android.util.Log
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
    private var boundTransferPort = 0
    private val writeLock = Any()

    var onPeerBye: ((peerName: String) -> Unit)? = null

    private var serverScope: CoroutineScope? = null

    fun isAlive(): Boolean {
        val socket = activeClientSocketRef.get()
        return socket != null && !socket.isClosed && socket.isConnected && isRunning.get()
    }

    fun sendBye(name: String) {
        val socket = activeClientSocketRef.get() ?: return
        try {
            socket.soTimeout = 500
            val output = socket.getOutputStream()
            synchronized(writeLock) {
                ControlMessage.writeMessage(output, ControlMessage.Bye(name))
            }
            Log.i("FlipLink", "[CONTROL] Sent BYE from server: $name")
        } catch (e: Exception) {
            Log.w("FlipLink", "Failed to send BYE from server: ${e.message}")
        }
    }

    /**
     * Starts listening for control connections on the wildcard address (0.0.0.0).
     */
    @Synchronized
    fun start(
        ownIdentity: DeviceIdentity,
        port: Int = ControlChannelConstants.PORT,
        transferPort: Int = 0,
        onPeerConnected: (peerUuid: String, peerName: String) -> Unit,
        onPeerLost: () -> Unit,
        onPeerBye: ((peerName: String) -> Unit)? = null
    ) {
        this.onPeerBye = onPeerBye
        start(
            ownIdentity = ownIdentity,
            port = port,
            transferPort = transferPort,
            onPeerConnected = { peerUuid, peerName, _, _ -> onPeerConnected(peerUuid, peerName) },
            onPeerLost = onPeerLost,
            onPeerBye = onPeerBye
        )
    }

    @Synchronized
    fun start(
        ownIdentity: DeviceIdentity,
        port: Int = ControlChannelConstants.PORT,
        transferPort: Int = 0,
        onPeerConnected: (peerUuid: String, peerName: String, peerIp: String, peerPort: Int) -> Unit,
        onPeerLost: () -> Unit,
        onPeerBye: ((peerName: String) -> Unit)? = null
    ) {
        this.onPeerBye = onPeerBye
        if (isRunning.getAndSet(true)) {
            return
        }
        boundTransferPort = transferPort

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
                            transferPort = boundTransferPort,
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
        transferPort: Int,
        onPeerConnected: (peerUuid: String, peerName: String, peerIp: String, peerPort: Int) -> Unit,
        onPeerLost: () -> Unit
    ) {
        val connStartTime = System.currentTimeMillis()
        var peerUuid: String? = null
        var isHandshakeCompleted = false
        val isByeReceived = AtomicBoolean(false)

        // Determine owner address and convert socket remote address to IPv4
        val ownerIp = ControlChannelConstants.toIpv4(socket.localAddress) ?: com.example.service.NetworkUtils.getLocalIpAddress() ?: ""
        val socketIpv4 = ControlChannelConstants.toIpv4(socket.inetAddress)

        ControlChannelConstants.logStep(
            step = "socket_accepted",
            detail = "Remote IP captured: .${ControlChannelConstants.lastOctet(socketIpv4)}"
        )

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
                synchronized(writeLock) {
                    ControlMessage.writeMessage(output, ControlMessage.Error("same_device"))
                }
                return
            }

            // Valid peer HELLO -> Reply HELLO_ACK with sender's actual httpPort
            synchronized(writeLock) {
                ControlMessage.writeMessage(
                    output,
                    ControlMessage.HelloAck(
                        uuid = ownUuid,
                        name = ownIdentity.displayName,
                        port = transferPort,
                        httpPort = transferPort
                    )
                )
            }
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
            synchronized(writeLock) {
                ControlMessage.writeMessage(output, ControlMessage.ReadyAck)
            }
            isHandshakeCompleted = true

            ControlChannelConstants.logStep(
                step = "connected",
                peerId = peerUuid,
                elapsedMs = System.currentTimeMillis() - connStartTime
            )

            // Resolve receiver IP: if remote socket address is IPv4 and not owner address, use it.
            // If not IPv4, use receiver's reported IP in HELLO only if in same /24 as group owner's address.
            val reportedIp = helloMsg.ip?.takeIf { it.isNotBlank() }
            val receiverIp = when {
                socketIpv4 != null && socketIpv4 != ownerIp && socketIpv4 != "0.0.0.0" && !socketIpv4.startsWith("127.") -> {
                    socketIpv4
                }
                reportedIp != null && ControlChannelConstants.isSameSubnet24(reportedIp, ownerIp) && reportedIp != ownerIp -> {
                    reportedIp
                }
                socketIpv4 != null && socketIpv4 != ownerIp -> socketIpv4
                else -> ""
            }

            val receiverHttpPort = if (helloMsg.httpPort > 0) helloMsg.httpPort else helloMsg.port

            Log.i(
                "FlipLink",
                "HandshakeServer connected: own=.${ControlChannelConstants.lastOctet(ownerIp)}:$transferPort, peer=.${ControlChannelConstants.lastOctet(receiverIp)}:$receiverHttpPort"
            )
            onPeerConnected(peerUuid, helloMsg.name, receiverIp, receiverHttpPort)

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
                    synchronized(writeLock) {
                        ControlMessage.writeMessage(output, ControlMessage.Pong)
                    }
                    ControlChannelConstants.logStep(
                        step = "pong_sent",
                        peerId = peerUuid
                    )
                } else if (pingMsg is ControlMessage.Bye) {
                    isByeReceived.set(true)
                    val senderName = if (pingMsg.name.isNotBlank()) pingMsg.name else (helloMsg.name.ifBlank { "Peer" })
                    Log.i("FlipLink", "[CONTROL] Server received BYE from client: $senderName")
                    onPeerBye?.invoke(senderName)
                    break
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

            if (isHandshakeCompleted && isRunning.get() && !isByeReceived.get()) {
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
