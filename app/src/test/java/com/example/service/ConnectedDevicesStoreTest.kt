package com.example.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectedDevicesStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        // Clear prefs before each test
        context.getSharedPreferences("flip_connected_devices_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ConnectedDevicesStore.clearList(context)
    }

    @Test
    fun testOneTimeFirstTimeCleanup_deletesSavedCredentialsAndMarksDone() {
        // 1. Seed some credentials in HotspotCredentialStore and legacy prefs
        HotspotCredentialStore.saveOwnHotspot(
            context,
            OwnHotspotCredentials(ssid = "TestHotspot", password = "SecretPassword123")
        )
        HotspotCredentialStore.savePairedPeer(
            context,
            PairedPeer(
                peerDeviceId = "11112222-3333-4444-5555-666677778888",
                displayName = "PeerPhone",
                ssid = "PeerHotspot",
                password = "PeerPassword"
            )
        )
        assertNotNull("Own hotspot should exist before cleanup", HotspotCredentialStore.getOwnHotspot(context))
        assertTrue("Paired peers should exist before cleanup", HotspotCredentialStore.getAllPairedPeers(context).isNotEmpty())

        // 2. Perform one-time cleanup
        ConnectedDevicesStore.performOneTimeFirstTimeCleanup(context)

        // 3. Verify credentials and peers were deleted
        assertNull("Own hotspot must be deleted by cleanup", HotspotCredentialStore.getOwnHotspot(context))
        assertTrue("Paired peers must be deleted by cleanup", HotspotCredentialStore.getAllPairedPeers(context).isEmpty())

        // 4. Verify cleanup flag is marked done
        val prefs = context.getSharedPreferences("flip_connected_devices_prefs", Context.MODE_PRIVATE)
        assertTrue("Cleanup flag must be marked true", prefs.getBoolean("first_time_cleanup_done_v1", false))
    }

    @Test
    fun testRecordSession_capsTo10Devices_mostRecentFirst() {
        // Record 12 sessions
        for (i in 1..12) {
            val code = String.format("%04d", i)
            ConnectedDevicesStore.recordSession(
                context = context,
                displayName = "Phone $i",
                shortCode = code
            )
            // Small sleep to ensure monotonic timestamps
            Thread.sleep(5)
        }

        val devices = ConnectedDevicesStore.getPastDevices(context)
        assertEquals("List must be capped at 10 past devices", 10, devices.size)

        // Most recent should be first: Phone 12 (short code 0012)
        assertEquals("Phone 12", devices[0].displayName)
        assertEquals("0012", devices[0].shortCode)
        assertTrue(devices[0].lastConnected > 0)

        // Oldest retained should be Phone 3 (short code 0003)
        assertEquals("Phone 3", devices[9].displayName)
        assertEquals("0003", devices[9].shortCode)
    }

    @Test
    fun testRecordSession_deduplicatesByShortCode() {
        ConnectedDevicesStore.recordSession(context, "Alpha", "ABCD")
        Thread.sleep(5)
        ConnectedDevicesStore.recordSession(context, "Alpha Renamed", "ABCD")

        val devices = ConnectedDevicesStore.getPastDevices(context)
        assertEquals("Duplicate short code must be updated, not appended", 1, devices.size)
        assertEquals("Alpha Renamed", devices[0].displayName)
        assertEquals("ABCD", devices[0].shortCode)
    }

    @Test
    fun testClearList_wipesHistory() {
        ConnectedDevicesStore.recordSession(context, "Device 1", "D001")
        ConnectedDevicesStore.recordSession(context, "Device 2", "D002")
        assertEquals(2, ConnectedDevicesStore.getPastDevices(context).size)

        ConnectedDevicesStore.clearList(context)

        assertTrue("Past devices list must be empty after clearList", ConnectedDevicesStore.getPastDevices(context).isEmpty())
        assertTrue("StateFlow must be empty after clearList", ConnectedDevicesStore.pastDevicesFlow.value.isEmpty())
    }
}
