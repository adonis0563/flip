package com.example.service

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiDirectPayloadTest {

    @Test
    fun testBuildAndParse_validPayload() {
        val deviceId = "12345678-1234-1234-1234-123456789abc"
        val deviceName = "Pixel 8 Pro & More = Special"
        val groupName = "DIRECT-a9-Flip"
        val passphrase = "SecretPass12"
        val ownerAddress = "02:00:00:11:22:33"
        val now = 1700000000L
        val expiry = now + 600L // 10 minutes

        val builtUri = WifiDirectPayloadParser.build(
            deviceId = deviceId,
            deviceName = deviceName,
            groupName = groupName,
            passphrase = passphrase,
            ownerAddress = ownerAddress,
            expiryEpochSeconds = expiry
        )

        assertTrue(builtUri.startsWith("flip://wfd?v=1&"))
        assertTrue(builtUri.contains("n=DIRECT-a9-Flip"))
        assertTrue(builtUri.contains("p=SecretPass12"))

        val parsed = WifiDirectPayloadParser.parse(builtUri, currentEpochSeconds = now)
        assertNotNull("Payload must be parsed successfully", parsed)
        assertEquals(1, parsed?.version)
        assertEquals(DeviceIdentity.normalizeUuid(deviceId), parsed?.deviceId)
        assertEquals(deviceName, parsed?.deviceName)
        assertEquals(groupName, parsed?.groupName)
        assertEquals(passphrase, parsed?.passphrase)
        assertEquals(ownerAddress, parsed?.ownerAddress)
        assertEquals(8, parsed?.sessionToken?.length)
        assertEquals(expiry, parsed?.expiryEpochSeconds)
        assertFalse(parsed?.isExpired(now) ?: true)
    }

    @Test
    fun testParse_allowsEmptyOrPlaceholderOwnerAddress() {
        val now = 1700000000L
        val payloadWithPlaceholder = "flip://wfd?v=1&id=12345678-1234-1234-1234-123456789abc&name=Device&n=DIRECT-ab-Flip&p=Passphrase12&o=02:00:00:00:00:00&t=12345678&e=${now + 600}"
        val parsed = WifiDirectPayloadParser.parse(payloadWithPlaceholder, now)
        assertNotNull("Payload with placeholder MAC must be accepted", parsed)
        assertEquals("02:00:00:00:00:00", parsed?.ownerAddress)

        val payloadWithEmptyOwner = "flip://wfd?v=1&id=12345678-1234-1234-1234-123456789abc&name=Device&n=DIRECT-ab-Flip&p=Passphrase12&o=&t=12345678&e=${now + 600}"
        val parsed2 = WifiDirectPayloadParser.parse(payloadWithEmptyOwner, now)
        assertNotNull("Payload with empty MAC must be accepted", parsed2)
        assertEquals("", parsed2?.ownerAddress)
    }

    @Test
    fun testParse_trimsWhitespaceAndNewlines() {
        val now = 1700000000L
        val messyPayload = "\r\n  flip://wfd?v=1&id=12345678-1234-1234-1234-123456789abc&name=Device&n=DIRECT-ab-Flip&p=Passphrase12&o=&t=12345678&e=${now + 600} \n\n"
        val parsed = WifiDirectPayloadParser.parse(messyPayload, now)
        assertNotNull("Payload with leading/trailing newlines must parse successfully", parsed)
    }

    @Test
    fun testParse_clockSkewToleranceAndExpiration() {
        val now = 1700000000L
        // Expiry is 60s in past: within 120s tolerance -> should still be accepted
        val slightlyPastExpiry = now - 60L
        val builtSlightlyPast = WifiDirectPayloadParser.build(
            deviceId = "12345678-1234-1234-1234-123456789abc",
            deviceName = "Phone",
            groupName = "DIRECT-ab-Flip",
            passphrase = "password1234",
            ownerAddress = "",
            expiryEpochSeconds = slightlyPastExpiry
        )
        assertNotNull("Within 120s clock skew tolerance must still be accepted", WifiDirectPayloadParser.parse(builtSlightlyPast, now))

        // Expiry is 300s in past: beyond 120s tolerance -> must be rejected as expired
        val farPastExpiry = now - 300L
        val builtFarPast = WifiDirectPayloadParser.build(
            deviceId = "12345678-1234-1234-1234-123456789abc",
            deviceName = "Phone",
            groupName = "DIRECT-ab-Flip",
            passphrase = "password1234",
            ownerAddress = "",
            expiryEpochSeconds = farPastExpiry
        )
        assertNull("Past 120s tolerance must be rejected as expired", WifiDirectPayloadParser.parse(builtFarPast, now))

        val detailedResult = WifiDirectPayloadParser.parseDetailed(builtFarPast, now)
        assertTrue(detailedResult is ParseDetailedResult.Expired)
        assertEquals(farPastExpiry, (detailedResult as ParseDetailedResult.Expired).expiryEpochSeconds)
        assertEquals(now, detailedResult.currentEpochSeconds)
    }

    @Test
    fun testParse_rejectsMalformedPayloadsWithStepName() {
        val now = 1700000000L

        // Missing scheme
        val res1 = WifiDirectPayloadParser.parseDetailed("http://wfd?v=1", now)
        assertTrue(res1 is ParseDetailedResult.Failed)

        // Missing passphrase p
        val missingPassphrase = "flip://wfd?v=1&id=123&name=Phone&n=DIRECT-ab-Flip&o=00:11&t=12345678&e=${now + 100}"
        val res2 = WifiDirectPayloadParser.parseDetailed(missingPassphrase, now)
        assertTrue(res2 is ParseDetailedResult.Failed)
        assertEquals("p (passphrase)", (res2 as ParseDetailedResult.Failed).field)

        // Missing session token t
        val missingToken = "flip://wfd?v=1&id=123&name=Phone&n=DIRECT-ab-Flip&p=pass&o=00:11&e=${now + 100}"
        val res3 = WifiDirectPayloadParser.parseDetailed(missingToken, now)
        assertTrue(res3 is ParseDetailedResult.Failed)
        assertTrue((res3 as ParseDetailedResult.Failed).field.startsWith("t"))
    }

    @Test
    fun testGenerateSessionToken_isEightCharactersAlphanumeric() {
        val token = WifiDirectPayloadParser.generateSessionToken()
        assertEquals(8, token.length)
        assertTrue(token.all { it.isLetterOrDigit() })
    }
}
