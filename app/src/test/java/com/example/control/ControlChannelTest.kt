package com.example.control

import com.example.service.DeviceIdentity
import com.example.service.control.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlChannelTest {

    private fun findAvailablePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    @Test
    fun testHelloMessageSerializationAndParsing() {
        val uuid = UUID.randomUUID().toString()
        val hello = ControlMessage.Hello(
            app = "flip",
            v = 1,
            uuid = uuid,
            name = "Pixel 8"
        )
        val json = hello.toJsonString()
        val parsed = ControlMessage.parse(json)

        assertNotNull(parsed)
        assertTrue(parsed is ControlMessage.Hello)
        val parsedHello = parsed as ControlMessage.Hello
        assertEquals("flip", parsedHello.app)
        assertEquals(1, parsedHello.v)
        assertEquals(ControlChannelConstants.normalizeUuid(uuid), parsedHello.uuid)
        assertEquals("Pixel 8", parsedHello.name)
    }

    @Test
    fun testHelloAckMessageSerializationAndParsing() {
        val uuid = UUID.randomUUID().toString()
        val ack = ControlMessage.HelloAck(uuid = uuid, name = "Galaxy S24")
        val json = ack.toJsonString()
        val parsed = ControlMessage.parse(json)

        assertNotNull(parsed)
        assertTrue(parsed is ControlMessage.HelloAck)
        val parsedAck = parsed as ControlMessage.HelloAck
        assertEquals(ControlChannelConstants.normalizeUuid(uuid), parsedAck.uuid)
        assertEquals("Galaxy S24", parsedAck.name)
    }

    @Test
    fun testReadyAndAckSerializationAndParsing() {
        val ready = ControlMessage.Ready
        val readyParsed = ControlMessage.parse(ready.toJsonString())
        assertNotNull(readyParsed)
        assertTrue(readyParsed is ControlMessage.Ready)

        val readyAck = ControlMessage.ReadyAck
        val readyAckParsed = ControlMessage.parse(readyAck.toJsonString())
        assertNotNull(readyAckParsed)
        assertTrue(readyAckParsed is ControlMessage.ReadyAck)
    }

    @Test
    fun testPingAndPongSerializationAndParsing() {
        val ping = ControlMessage.Ping
        val pingParsed = ControlMessage.parse(ping.toJsonString())
        assertNotNull(pingParsed)
        assertTrue(pingParsed is ControlMessage.Ping)

        val pong = ControlMessage.Pong
        val pongParsed = ControlMessage.parse(pong.toJsonString())
        assertNotNull(pongParsed)
        assertTrue(pongParsed is ControlMessage.Pong)
    }

    @Test
    fun testSameDeviceErrorMessageParsing() {
        val err = ControlMessage.Error(error = "same_device")
        val parsed = ControlMessage.parse(err.toJsonString())
        assertNotNull(parsed)
        assertTrue(parsed is ControlMessage.Error)
        assertEquals("same_device", (parsed as ControlMessage.Error).error)
    }

    @Test
    fun testOversizedMessageDiscardedByReader() {
        // Build message exceeding 512 bytes limit
        val oversizedPadding = "a".repeat(600)
        val oversizedLine = "{\"type\":\"PING\",\"pad\":\"$oversizedPadding\"}\n"
        val validLine = "{\"type\":\"PING\"}\n"

        val stream = ByteArrayInputStream((oversizedLine + validLine).toByteArray(Charsets.UTF_8))

        // First message should be discarded as null because it exceeds 512 bytes
        val first = ControlMessage.readMessage(stream, maxBytes = 512)
        assertNull("Oversized message must be discarded and return null", first)

        // Second message following the newline should be parsed normally
        val second = ControlMessage.readMessage(stream, maxBytes = 512)
        assertNotNull("Subsequent valid message within 512 bytes must be read", second)
        assertTrue(second is ControlMessage.Ping)
    }

    @Test
    fun testMalformedJsonReturnsNull() {
        assertNull(ControlMessage.parse("{not valid json}"))
        assertNull(ControlMessage.parse(""))
        assertNull(ControlMessage.parse("random string"))
    }

    @Test
    fun testSuccessfulHandshakeBetweenServerAndClient() = runBlocking {
        val port = findAvailablePort()
        val serverUuid = UUID.randomUUID().toString()
        val clientUuid = UUID.randomUUID().toString()

        val serverIdentity = DeviceIdentity(
            uuid = serverUuid,
            id8 = DeviceIdentity.deriveHexId(serverUuid),
            shortCode = DeviceIdentity.deriveHexId(serverUuid).take(4).uppercase(),
            displayName = "Server Phone"
        )
        val clientIdentity = DeviceIdentity(
            uuid = clientUuid,
            id8 = DeviceIdentity.deriveHexId(clientUuid),
            shortCode = DeviceIdentity.deriveHexId(clientUuid).take(4).uppercase(),
            displayName = "Client Phone"
        )

        val connectedLatch = CountDownLatch(1)
        var connectedPeerUuid: String? = null

        val server = HandshakeServer()
        server.start(
            ownIdentity = serverIdentity,
            port = port,
            onPeerConnected = { peerUuid, _ ->
                connectedPeerUuid = peerUuid
                connectedLatch.countDown()
            },
            onPeerLost = {}
        )

        try {
            val client = HandshakeClient()
            val result = client.connect(
                host = "127.0.0.1",
                expectedPeerUuid = serverUuid,
                ownIdentity = clientIdentity,
                port = port
            )

            assertTrue("Handshake must succeed", result is HandshakeResult.Success)
            assertTrue(connectedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(ControlChannelConstants.normalizeUuid(clientUuid), connectedPeerUuid)

            client.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun testSameDeviceRejectedDuringHandshake() = runBlocking {
        val port = findAvailablePort()
        val commonUuid = UUID.randomUUID().toString()

        val identity = DeviceIdentity(
            uuid = commonUuid,
            id8 = DeviceIdentity.deriveHexId(commonUuid),
            shortCode = DeviceIdentity.deriveHexId(commonUuid).take(4).uppercase(),
            displayName = "Self Phone"
        )

        var peerConnectedCalled = false

        val server = HandshakeServer()
        server.start(
            ownIdentity = identity,
            port = port,
            onPeerConnected = { _, _ -> peerConnectedCalled = true },
            onPeerLost = {}
        )

        try {
            val client = HandshakeClient()
            val result = client.connect(
                host = "127.0.0.1",
                expectedPeerUuid = commonUuid,
                ownIdentity = identity, // Same identity as server!
                port = port
            )

            assertTrue("Same device connection must return SameDevice result", result is HandshakeResult.SameDevice)
            assertFalse("Server must not fire onPeerConnected for same-device attempt", peerConnectedCalled)

            client.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun testPeerIdMismatchDetectedByClient() = runBlocking {
        val port = findAvailablePort()
        val serverUuid = UUID.randomUUID().toString()
        val clientUuid = UUID.randomUUID().toString()
        val impostorExpectedUuid = UUID.randomUUID().toString()

        val serverIdentity = DeviceIdentity(
            uuid = serverUuid,
            id8 = DeviceIdentity.deriveHexId(serverUuid),
            shortCode = DeviceIdentity.deriveHexId(serverUuid).take(4).uppercase(),
            displayName = "Server Phone"
        )
        val clientIdentity = DeviceIdentity(
            uuid = clientUuid,
            id8 = DeviceIdentity.deriveHexId(clientUuid),
            shortCode = DeviceIdentity.deriveHexId(clientUuid).take(4).uppercase(),
            displayName = "Client Phone"
        )

        val server = HandshakeServer()
        server.start(
            ownIdentity = serverIdentity,
            port = port,
            onPeerConnected = { _, _ -> },
            onPeerLost = {}
        )

        try {
            val client = HandshakeClient()
            val result = client.connect(
                host = "127.0.0.1",
                expectedPeerUuid = impostorExpectedUuid, // Client expects impostor ID, but server has serverUuid
                ownIdentity = clientIdentity,
                port = port
            )

            assertTrue("Client must detect UUID mismatch", result is HandshakeResult.IdMismatch)
            val mismatch = result as HandshakeResult.IdMismatch
            assertEquals(ControlChannelConstants.normalizeUuid(impostorExpectedUuid), mismatch.expected)
            assertEquals(ControlChannelConstants.normalizeUuid(serverUuid), mismatch.actual)

            client.close()
        } finally {
            server.stop()
        }
    }
}
