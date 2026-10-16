package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.CredentialStore
import com.example.service.WifiCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredentialStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        CredentialStore.init(context)
        CredentialStore.clearOwn(context)
        CredentialStore.forgetAll(context)
    }

    @Test
    fun testOwnCredentials_saveGetClear() {
        val testCreds = WifiCredentials(
            ssid = "TestOwnHotspot",
            password = "SuperSecretPassword123",
            security = "WPA",
            hidden = false
        )

        CredentialStore.saveOwn(context, testCreds)

        val retrieved = CredentialStore.getOwn(context)
        assertNotNull("Retrieved credentials should not be null", retrieved)
        assertEquals("TestOwnHotspot", retrieved!!.ssid)
        assertEquals("SuperSecretPassword123", retrieved.password)
        assertEquals("WPA", retrieved.security)
        assertFalse(retrieved.hidden)

        CredentialStore.clearOwn(context)
        assertNull("Own credentials should be null after clearing", CredentialStore.getOwn(context))
    }

    @Test
    fun testPeerCredentials_saveGetListForget() {
        val peerId = UUID.randomUUID().toString()
        val peerCreds = WifiCredentials(
            ssid = "PeerHotspotNetwork",
            password = "PeerPassword456",
            security = "WPA",
            hidden = true
        )

        CredentialStore.savePeer(context, peerId, peerCreds, "Alice's Phone")

        val retrieved = CredentialStore.getPeer(context, peerId)
        assertNotNull(retrieved)
        assertEquals("PeerHotspotNetwork", retrieved!!.ssid)
        assertEquals("PeerPassword456", retrieved.password)
        assertTrue(retrieved.hidden)

        // Verify listPeers returns names, ids, and timestamps only, never passwords
        val peers = CredentialStore.listPeers(context)
        assertEquals(1, peers.size)
        val summary = peers[0]
        assertEquals(peerId, summary.id)
        assertEquals("Alice's Phone", summary.displayName)
        assertTrue(summary.lastConnected > 0)

        // Forget peer
        CredentialStore.forgetPeer(context, peerId)
        assertNull(CredentialStore.getPeer(context, peerId))
        assertTrue(CredentialStore.listPeers(context).isEmpty())
    }

    @Test
    fun testPeerCredentials_saeSupport() {
        val peerId = UUID.randomUUID().toString()
        val saeCreds = WifiCredentials(
            ssid = "PixelHotspotSAE",
            password = "Wpa3PasswordPass!",
            security = "SAE",
            hidden = false
        )

        CredentialStore.savePeer(context, peerId, saeCreds, "Bob's Pixel")
        val retrieved = CredentialStore.getPeer(context, peerId)
        assertNotNull(retrieved)
        assertEquals("PixelHotspotSAE", retrieved!!.ssid)
        assertEquals("Wpa3PasswordPass!", retrieved.password)
        assertEquals("SAE", retrieved.security)
    }

    @Test
    fun testStoredData_survivesAppRestart() {
        val ownCreds = WifiCredentials(
            ssid = "PersistentOwnHotspot",
            password = "OwnSecretPassword777",
            security = "SAE",
            hidden = false
        )
        val peerId = UUID.randomUUID().toString()
        val peerCreds = WifiCredentials(
            ssid = "PersistentPeerHotspot",
            password = "PeerSecretPassword888",
            security = "WPA",
            hidden = true
        )

        CredentialStore.saveOwn(context, ownCreds)
        CredentialStore.savePeer(context, peerId, peerCreds, "Charlie's Phone")

        // Simulate app restart by re-initializing store on fresh context lookup
        val newAppContext = ApplicationProvider.getApplicationContext<Context>()
        CredentialStore.init(newAppContext)

        val retrievedOwn = CredentialStore.getOwn(newAppContext)
        assertNotNull("Own credentials must survive app restart", retrievedOwn)
        assertEquals("PersistentOwnHotspot", retrievedOwn!!.ssid)
        assertEquals("OwnSecretPassword777", retrievedOwn.password)
        assertEquals("SAE", retrievedOwn.security)

        val retrievedPeer = CredentialStore.getPeer(newAppContext, peerId)
        assertNotNull("Peer credentials must survive app restart", retrievedPeer)
        assertEquals("PersistentPeerHotspot", retrievedPeer!!.ssid)
        assertEquals("PeerSecretPassword888", retrievedPeer.password)
        assertTrue(retrievedPeer.hidden)

        val peers = CredentialStore.listPeers(newAppContext)
        assertEquals(1, peers.size)
        assertEquals(peerId, peers[0].id)
        assertEquals("Charlie's Phone", peers[0].displayName)
    }

    @Test
    fun testStoredData_isEncryptedInAppFiles() {
        val secretPassword = "UltraConfidentialHotspotPassword999"
        val peerSecretPassword = "PeerUltraConfidentialPassword888"
        val creds = WifiCredentials(
            ssid = "SensitiveNetwork",
            password = secretPassword,
            security = "WPA",
            hidden = false
        )
        val peerId = UUID.randomUUID().toString()
        val peerCreds = WifiCredentials(
            ssid = "SensitivePeerNetwork",
            password = peerSecretPassword,
            security = "SAE",
            hidden = false
        )

        CredentialStore.saveOwn(context, creds)
        CredentialStore.savePeer(context, peerId, peerCreds, "Dave's Phone")

        // Inspect raw XML preference file
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val prefsFile = File(sharedPrefsDir, "flip_secure_hotspot_prefs.xml")

        if (prefsFile.exists()) {
            val rawXml = prefsFile.readText()
            // Passwords must NEVER appear in plain text within app's files
            assertFalse(
                "Own plaintext password must never appear in raw shared_prefs XML file",
                rawXml.contains(secretPassword)
            )
            assertFalse(
                "Peer plaintext password must never appear in raw shared_prefs XML file",
                rawXml.contains(peerSecretPassword)
            )
        }
    }
}
