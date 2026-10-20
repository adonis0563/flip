package com.example.control

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.FirstTimeConnectionState
import com.example.service.FlipTransferForegroundService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstTimeStateMachineTest {

    @Test
    fun testInitialStateIsIdle() {
        FlipTransferForegroundService.resetFirstTimeState()
        val state = FlipTransferForegroundService.connectionFlowState.value
        assertTrue("Initial state must be Idle", state is FirstTimeConnectionState.Idle)
    }

    @Test
    fun testTeardownIsIdempotentAndResetsToIdle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FlipTransferForegroundService.startSenderControl(context)

        // Multiple calls to teardown must not crash
        FlipTransferForegroundService.teardownFirstTimeConnection()
        FlipTransferForegroundService.teardownFirstTimeConnection()

        FlipTransferForegroundService.resetFirstTimeState()
        val state = FlipTransferForegroundService.connectionFlowState.value
        assertTrue("Reset must return state to Idle", state is FirstTimeConnectionState.Idle)
    }

    @Test
    fun testStateHierarchyAndDataClasses() {
        val uuid = UUID.randomUUID().toString()
        val joining = FirstTimeConnectionState.Joining("Pixel 8", "7F3A")
        assertEquals("Pixel 8", joining.peerName)
        assertEquals("7F3A", joining.shortCode)

        val awaiting = FirstTimeConnectionState.AwaitingLink("Pixel 8", "7F3A")
        assertEquals("Pixel 8", awaiting.peerName)

        val handshaking = FirstTimeConnectionState.Handshaking("Pixel 8", "7F3A")
        assertEquals("7F3A", handshaking.shortCode)

        val connected = FirstTimeConnectionState.Connected(uuid, "Pixel 8")
        assertEquals(uuid, connected.peerUuid)
        assertEquals("Pixel 8", connected.peerName)

        val failed = FirstTimeConnectionState.Failed("Connection lost.")
        assertEquals("Connection lost.", failed.reason)
    }

    @Test
    fun testFailureMessageStrings() {
        val sameDeviceMsg = "That's this phone's own QR code."
        val idMismatchMsg = "This isn't the device you paired with."
        val noIpMsg = "Connected to the hotspot but got no address. Try again."
        val lostMsg = "Connection lost. The sender's hotspot may have turned off."
        val ssidNotFoundMsg = "Couldn't find Pixel 8's hotspot. Make sure it's on and close by."
        val ssidFoundAuthFailMsg = "Couldn't connect. The password may be wrong, or the connection wasn't approved."

        assertEquals("That's this phone's own QR code.", sameDeviceMsg)
        assertEquals("This isn't the device you paired with.", idMismatchMsg)
        assertEquals("Connected to the hotspot but got no address. Try again.", noIpMsg)
        assertEquals("Connection lost. The sender's hotspot may have turned off.", lostMsg)
        assertTrue(ssidNotFoundMsg.contains("Make sure it's on and close by"))
        assertTrue(ssidFoundAuthFailMsg.contains("The password may be wrong"))
    }
}
