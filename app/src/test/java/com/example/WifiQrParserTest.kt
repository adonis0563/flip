package com.example

import com.example.service.WifiQrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiQrParserTest {

    @Test
    fun testParse_plain() {
        val qr = "WIFI:T:WPA;S:MyHomeWifi;P:Secret123;;"
        val creds = WifiQrParser.parse(qr)

        assertNotNull("Expected parsed credentials for valid Wi-Fi QR", creds)
        assertEquals("MyHomeWifi", creds!!.ssid)
        assertEquals("Secret123", creds.password)
        assertEquals("WPA", creds.security)
        assertFalse("Hidden should default to false", creds.hidden)
    }

    @Test
    fun testParse_saeWpa3() {
        val qrSae = "WIFI:T:SAE;S:ModernHome5G;P:UltraSecureWpa3;;"
        val credsSae = WifiQrParser.parse(qrSae)

        assertNotNull("Expected parsed credentials for SAE QR", credsSae)
        assertEquals("ModernHome5G", credsSae!!.ssid)
        assertEquals("UltraSecureWpa3", credsSae.password)
        assertEquals("SAE", credsSae.security)
        assertFalse(credsSae.hidden)

        val qrWpa3 = "WIFI:T:WPA3;S:PixelHotspot;P:Pass123456;H:false;;"
        val credsWpa3 = WifiQrParser.parse(qrWpa3)
        assertNotNull(credsWpa3)
        assertEquals("PixelHotspot", credsWpa3!!.ssid)
        assertEquals("SAE", credsWpa3.security)

        val qrWpa3Sae = "WIFI:T:WPA3-SAE;S:GalaxyHotspot;P:SecretWord;;;"
        val credsWpa3Sae = WifiQrParser.parse(qrWpa3Sae)
        assertNotNull(credsWpa3Sae)
        assertEquals("GalaxyHotspot", credsWpa3Sae!!.ssid)
        assertEquals("SAE", credsWpa3Sae.security)
    }

    @Test
    fun testParse_wep() {
        val qrWep = "WIFI:T:WEP;S:LegacyNetwork;P:1234567890;;"
        val credsWep = WifiQrParser.parse(qrWep)
        assertNotNull(credsWep)
        assertEquals("LegacyNetwork", credsWep!!.ssid)
        assertEquals("WEP", credsWep.security)
    }

    @Test
    fun testParse_escapedCharacters() {
        // Escaped backslash (\\), semicolon (\;), comma (\,), colon (\:)
        val qr = "WIFI:T:WPA;S:Home\\\\Net\\;Work\\,Test\\:1;P:p\\\\a\\;s\\,s\\:word;H:false;;"
        val creds = WifiQrParser.parse(qr)

        assertNotNull("Expected parsed credentials for escaped Wi-Fi QR", creds)
        assertEquals("Home\\Net;Work,Test:1", creds!!.ssid)
        assertEquals("p\\a;s,s:word", creds.password)
        assertEquals("WPA", creds.security)
        assertFalse(creds.hidden)
    }

    @Test
    fun testParse_hiddenNetwork() {
        val qrTrue = "WIFI:T:WPA;S:StealthNetwork;P:myPassword;H:true;;"
        val credsTrue = WifiQrParser.parse(qrTrue)

        assertNotNull(credsTrue)
        assertEquals("StealthNetwork", credsTrue!!.ssid)
        assertEquals("myPassword", credsTrue.password)
        assertTrue(credsTrue.hidden)

        val qrOne = "WIFI:T:WPA;S:StealthNetwork2;P:myPassword;H:1;;"
        val credsOne = WifiQrParser.parse(qrOne)

        assertNotNull(credsOne)
        assertEquals("StealthNetwork2", credsOne!!.ssid)
        assertTrue(credsOne.hidden)
    }

    @Test
    fun testParse_openNetwork() {
        val qrNopass = "WIFI:T:nopass;S:AirportGuest;P:;;"
        val credsNopass = WifiQrParser.parse(qrNopass)

        assertNotNull(credsNopass)
        assertEquals("AirportGuest", credsNopass!!.ssid)
        assertEquals("", credsNopass.password)
        assertEquals("OPEN", credsNopass.security)

        val qrNone = "WIFI:S:LibraryWifi;T:OPEN;;"
        val credsNone = WifiQrParser.parse(qrNone)

        assertNotNull(credsNone)
        assertEquals("LibraryWifi", credsNone!!.ssid)
        assertEquals("OPEN", credsNone.security)
    }

    @Test
    fun testParse_missingSsid() {
        val qrNoSsidField = "WIFI:T:WPA;P:somePassword;;"
        assertNull("Missing S field must be rejected", WifiQrParser.parse(qrNoSsidField))

        val qrEmptySsid = "WIFI:S:;T:WPA;P:somePassword;;"
        assertNull("Empty S field must be rejected", WifiQrParser.parse(qrEmptySsid))
    }

    @Test
    fun testParse_garbageInput() {
        assertNull(WifiQrParser.parse(null))
        assertNull(WifiQrParser.parse(""))
        assertNull(WifiQrParser.parse("   "))
        assertNull(WifiQrParser.parse("Not a wifi QR code"))
        assertNull(WifiQrParser.parse("https://google.com"))
        assertNull(WifiQrParser.parse("WIFI;S:wrong_delimiter;"))
        assertNull(WifiQrParser.parse("WIFI:;"))
    }
}
