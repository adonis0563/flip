package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceIdentityTest {

    @Test
    fun testNormalizeUuid_handlesVariousFormats() {
        val uppercaseWithHyphens = "F47AC10B-58CC-4372-A567-0E02B2C3D479"
        val expected = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        assertEquals(expected, DeviceIdentity.normalizeUuid(uppercaseWithHyphens))

        val withoutHyphens = "f47ac10b58cc4372a5670e02b2c3d479"
        assertEquals(expected, DeviceIdentity.normalizeUuid(withoutHyphens))

        val withUrnPrefix = "urn:uuid:f47ac10b-58cc-4372-a567-0e02b2c3d479"
        assertEquals(expected, DeviceIdentity.normalizeUuid(withUrnPrefix))
    }

    @Test
    fun testGetShortCodeFromUuid_deterministicAndMatchesIdentity() {
        val uuid = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        val code1 = DeviceIdentity.getShortCodeFromUuid(uuid)
        val code2 = DeviceIdentity.getShortCodeFromUuid("F47AC10B58CC4372A5670E02B2C3D479")

        assertEquals(4, code1.length)
        assertEquals(code1, code2)
        assertTrue(code1.matches(Regex("^[0-9A-F]{4}$")))
    }

    @Test
    fun testDeriveHexId_formatAndLength() {
        val testUuid = UUID.randomUUID().toString()
        val hexId = DeviceIdentity.deriveHexId(testUuid)

        assertEquals("Device ID must be exactly 8 characters", 8, hexId.length)
        assertTrue("Device ID must be lowercase hex characters only: $hexId", hexId.matches(Regex("^[0-9a-f]{8}$")))
    }

    @Test
    fun testDeriveHexId_isDeterministicAndStable() {
        val fixedUuid = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        val expectedSha256 = MessageDigest.getInstance("SHA-256")
        .digest(fixedUuid.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(8)

        val id1 = DeviceIdentity.deriveHexId(fixedUuid)
        val id2 = DeviceIdentity.deriveHexId(fixedUuid)

        assertEquals("Derivation must match expected SHA-256 first 8 hex characters", expectedSha256, id1)
        assertEquals("Multiple derivations from same UUID must be strictly identical", id1, id2)
    }

    @Test
    fun testDeriveHexId_distinctForDifferentPhones() {
        val phoneAUuid = UUID.randomUUID().toString()
        val phoneBUuid = UUID.randomUUID().toString()

        val idA = DeviceIdentity.deriveHexId(phoneAUuid)
        val idB = DeviceIdentity.deriveHexId(phoneBUuid)

        assertNotEquals("Two different phones must have different device IDs", idA, idB)
    }

    @Test
    fun testCurrentSessionId_formatAndNonEmpty() {
        val sid = DeviceIdentity.currentSessionId
        assertNotNull(sid)
        assertEquals(8, sid.length)
        assertTrue(sid.matches(Regex("^[0-9a-f]{8}$")))
    }

    @Test
    fun testDeviceIdentity_structureAndUppercaseShortCode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceIdentity.resetMemoryCacheForTesting()

        val identity = DeviceIdentity.get(context)
        assertNotNull(identity.uuid)
        assertEquals(36, identity.uuid.length)
        assertEquals(8, identity.id8.length)
        assertTrue(identity.id8.matches(Regex("^[0-9a-f]{8}$")))

        // shortCode = first 4 characters of id8 uppercased
        assertEquals(4, identity.shortCode.length)
        assertEquals(identity.id8.take(4).uppercase(), identity.shortCode)
        assertTrue(identity.shortCode.matches(Regex("^[0-9A-F]{4}$")))

        // displayName defaults to phone model / non-empty
        assertTrue(identity.displayName.isNotBlank())
    }

    @Test
    fun testDeviceIdentity_survivesRestartSimulationAndHasConsistentModelShortCodeName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceIdentity.resetMemoryCacheForTesting()

        val firstRunIdentity = DeviceIdentity.get(context)
        val originalUuid = firstRunIdentity.uuid
        val originalId8 = firstRunIdentity.id8
        val originalDisplayName = firstRunIdentity.displayName

        assertTrue(originalDisplayName.contains(firstRunIdentity.shortCode))

        // Simulate app restart: clear memory cache
        DeviceIdentity.resetMemoryCacheForTesting()

        val restartIdentity = DeviceIdentity.get(context)
        assertEquals("UUID must survive app restart", originalUuid, restartIdentity.uuid)
        assertEquals("id8 must survive app restart", originalId8, restartIdentity.id8)
        assertEquals("shortCode must survive app restart", originalId8.take(4).uppercase(), restartIdentity.shortCode)
        assertEquals("Display name must survive app restart and remain model plus short code", originalDisplayName, restartIdentity.displayName)
    }
}
