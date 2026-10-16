package com.example.service

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.net.URLDecoder
import java.net.URLEncoder

data class WifiHotspotDetails(
    val ssid: String,
    val password: String,
    val security: String = "WPA",
    val isHidden: Boolean = false
)

data class FirstTimePayload(
    val version: Int,
    val deviceId: String,
    val deviceName: String,
    val ssid: String,
    val password: String,
    val security: String,
    val isHidden: Boolean
) {
    val shortCode: String get() = deviceId.take(4).uppercase()
}

/**
 * Shared protocol encoder/decoder for Flip hotspot pairing.
 * All QR payload serialization and parsing lives here so any future scrambles/ciphers
 * can be plugged in without modifying any UI or networking callers.
 */
object HotspotPairingProtocol {
    private const val TAG = "HotspotPairingProtocol"
    const val SCHEME_FIRST = "flip://first"
    const val SCHEME_LEGACY = "flip://ip="

    /**
     * Builds the "First time" QR payload:
     * flip://first?v=1&id=<full device UUID>&name=<url-encoded>&ssid=<enc>&pw=<enc>&sec=<WPA|WEP|OPEN|SAE>&hidden=<0|1>
     */
    fun buildFirstTimeQrPayload(
        deviceId: String,
        deviceName: String,
        credentials: OwnHotspotCredentials
    ): String {
        return buildFirstTimeQrPayload(
            deviceId = deviceId,
            deviceName = deviceName,
            ssid = credentials.ssid,
            password = credentials.password,
            security = credentials.security,
            isHidden = credentials.isHidden
        )
    }

    fun buildFirstTimeQrPayload(
        deviceId: String,
        deviceName: String,
        credentials: WifiCredentials
    ): String {
        return buildFirstTimeQrPayload(
            deviceId = deviceId,
            deviceName = deviceName,
            ssid = credentials.ssid,
            password = credentials.password,
            security = credentials.security,
            isHidden = credentials.hidden
        )
    }

    fun buildFirstTimeQrPayload(
        deviceId: String,
        deviceName: String,
        ssid: String,
        password: String,
        security: String,
        isHidden: Boolean
    ): String {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        return StringBuilder(SCHEME_FIRST)
            .append("?v=1")
            .append("&id=").append(enc(deviceId))
            .append("&name=").append(enc(deviceName))
            .append("&ssid=").append(enc(ssid))
            .append("&pw=").append(enc(password))
            .append("&sec=").append(enc(security))
            .append("&hidden=").append(if (isHidden) "1" else "0")
            .toString()
    }

    /**
     * Parses a flip://first URI payload into FirstTimePayload.
     */
    fun parseFirstTimeQrPayload(uriString: String): FirstTimePayload? {
        if (!uriString.startsWith(SCHEME_FIRST)) return null
        return try {
            val uri = Uri.parse(uriString)
            val v = uri.getQueryParameter("v")?.toIntOrNull() ?: 1
            val id = uri.getQueryParameter("id") ?: return null
            val name = uri.getQueryParameter("name") ?: "Flip Peer"
            val ssid = uri.getQueryParameter("ssid") ?: return null
            val pw = uri.getQueryParameter("pw") ?: ""
            val sec = uri.getQueryParameter("sec") ?: "WPA"
            val hidden = uri.getQueryParameter("hidden") == "1"

            FirstTimePayload(
                version = v,
                deviceId = id,
                deviceName = name,
                ssid = ssid,
                password = pw,
                security = sec,
                isHidden = hidden
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse first time payload: ${e.message}")
            null
        }
    }

    /**
     * Parses standard Wi-Fi QR strings:
     * WIFI:T:<WPA|WEP|nopass>;S:<ssid>;P:<password>;H:<true|false>;;
     * Handles escaped characters (\\, \;, \,, \:).
     * Rejects anything that is not a Wi-Fi code or has no SSID.
     */
    fun parseWifiQr(qrString: String): WifiHotspotDetails? {
        val creds = WifiQrParser.parse(qrString) ?: return null
        return WifiHotspotDetails(
            ssid = creds.ssid,
            password = creds.password,
            security = creds.security,
            isHidden = creds.hidden
        )
    }

    /**
     * Decodes a QR code from a picked image Uri without requiring any broad storage permissions.
     */
    fun decodeQrFromImageUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val source = RGBLuminanceSource(width, height, pixels)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE))
                val result = MultiFormatReader().decode(binaryBitmap, hints)
                result.text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding QR from image uri: ${e.message}")
            null
        }
    }
}
