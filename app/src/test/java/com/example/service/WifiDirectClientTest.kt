package com.example.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiDirectClientTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testLeave_isSafeToCallMultipleTimes() {
        val client = WifiDirectClient(context)
        client.leave()
        client.leave()
        client.leave()
    }

    @Test
    fun testMacValidation_handlesPlaceholdersAndInvalidMacs() {
        assertFalse(WifiDirectClient.isValidMacAddress(null))
        assertFalse(WifiDirectClient.isValidMacAddress(""))
        assertFalse(WifiDirectClient.isValidMacAddress("   "))
        assertFalse(WifiDirectClient.isValidMacAddress("02:00:00:00:00:00"))
        assertFalse(WifiDirectClient.isValidMacAddress("00:00:00:00:00:00"))
        assertFalse(WifiDirectClient.isValidMacAddress("FF:FF:FF:FF:FF:FF"))
        assertFalse(WifiDirectClient.isValidMacAddress("not:a:valid:mac:address"))
        assertFalse(WifiDirectClient.isValidMacAddress("12345"))

        assertTrue(WifiDirectClient.isValidMacAddress("02:1A:2B:3C:4D:5E"))
        assertTrue(WifiDirectClient.isValidMacAddress("a0:b1:c2:d3:e4:f5"))
    }

    @Test
    fun testJoin_rejectsExpiredPayloadWithStep() = runBlocking {
        val client = WifiDirectClient(context)
        val now = System.currentTimeMillis() / 1000L
        val expiredPayload = WifiDirectPayload(
            version = 1,
            deviceId = "12345678-1234-1234-1234-123456789abc",
            deviceName = "Phone A",
            groupName = "DIRECT-ab-Flip",
            passphrase = "password1234",
            ownerAddress = "",
            sessionToken = "12345678",
            expiryEpochSeconds = now - 300L // 300s in past (past 120s tolerance)
        )

        val result = client.join(expiredPayload, sdkInt = 34)
        assertTrue("Must reject expired payload", result is ClientResult.Failed)
        val reason = (result as ClientResult.Failed).reason
        assertTrue("Reason must be Expired", reason is ClientFailureReason.Expired)
        assertEquals(now - 300L, (reason as ClientFailureReason.Expired).expiryEpochSeconds)
        client.leave()
    }

    @Test
    fun testJoin_reportsParseFailedStepOnMalformedString() = runBlocking {
        val client = WifiDirectClient(context)
        val malformedString = "flip://wfd?v=1&id=123&name=Phone&n=DIRECT-ab-Flip&o=&t=12345678&e=1700000000" // missing passphrase 'p'
        val result = client.join(malformedString, sdkInt = 34)
        assertTrue("Must fail on missing passphrase", result is ClientResult.Failed)
        val reason = (result as ClientResult.Failed).reason
        assertTrue("Reason must be ParseFailed", reason is ClientFailureReason.ParseFailed)
        assertEquals("p (passphrase)", (reason as ClientFailureReason.ParseFailed).field)
        client.leave()
    }

    @Test
    fun testJoin_returnsUnsupportedBelowAndroid10() = runBlocking {
        val client = WifiDirectClient(context)
        val now = System.currentTimeMillis() / 1000L
        val payload = WifiDirectPayload(
            version = 1,
            deviceId = "12345678-1234-1234-1234-123456789abc",
            deviceName = "Phone A",
            groupName = "DIRECT-ab-Flip",
            passphrase = "password1234",
            ownerAddress = "",
            sessionToken = "12345678",
            expiryEpochSeconds = now + 600L
        )

        val result = client.join(payload, sdkInt = 28)
        assertTrue("Must return Unsupported below Android 10", result is ClientResult.Failed)
        assertEquals(ClientFailureReason.Unsupported, (result as ClientResult.Failed).reason)
        client.leave()
    }

    @Test
    fun testJoin_returnsPermissionMissingWithExactPermissions() = runBlocking {
        val client = WifiDirectClient(context)
        val now = System.currentTimeMillis() / 1000L
        val payload = WifiDirectPayload(
            version = 1,
            deviceId = "12345678-1234-1234-1234-123456789abc",
            deviceName = "Phone A",
            groupName = "DIRECT-ab-Flip",
            passphrase = "password1234",
            ownerAddress = "",
            sessionToken = "12345678",
            expiryEpochSeconds = now + 600L
        )

        val result = client.join(payload, sdkInt = 34)
        assertTrue("Must return PermissionMissing when required permissions are not granted", result is ClientResult.Failed)
        val reason = (result as ClientResult.Failed).reason
        assertTrue("Reason must be PermissionMissing", reason is ClientFailureReason.PermissionMissing)
        val missing = (reason as ClientFailureReason.PermissionMissing).permissions
        assertTrue("Must name missing permissions", missing.isNotEmpty())
        client.leave()
    }
}
