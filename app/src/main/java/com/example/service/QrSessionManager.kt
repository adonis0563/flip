package com.example.service

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class QrConnectionPackage(
    val version: Int = 1,
    val sessionId: String,
    val deviceName: String,
    val mode: String = "hotspot",
    val ssid: String,
    val password: String,
    val ip: String,
    val port: Int,
    val timestamp: Long,
    val expiration: Long,
    val extraCapabilities: Map<String, String> = emptyMap()
)

sealed class QrParseResult {
    data class Success(val packageData: QrConnectionPackage) : QrParseResult()
    data class Error(val message: String) : QrParseResult()
}

object QrSessionManager {
    private const val TAG = "QrSessionManager"
    private const val CURRENT_PROTOCOL_VERSION = 1
    private const val DEFAULT_EXPIRATION_MS = 10 * 60 * 1000L // 10 minutes

    // Set of invalidated/completed session IDs to prevent QR reuse
    private val invalidatedSessions = Collections.synchronizedSet(mutableSetOf<String>())

    fun invalidateSession(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) {
            invalidatedSessions.add(sessionId)
            Log.d(TAG, "Session invalidated: $sessionId")
        }
    }

    fun isSessionInvalidated(sessionId: String): Boolean {
        return invalidatedSessions.contains(sessionId)
    }

    fun clearInvalidatedSessions() {
        invalidatedSessions.clear()
    }

    /**
     * Generates a key for AES encryption using SHA-256 derived from sessionId
     */
    private fun deriveKey(sessionId: String): SecretKeySpec {
        val input = "FlipSecureQR:$sessionId"
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts the payload map into a Base64 URL-safe token
     */
    fun encryptPayload(jsonPayload: String, sessionId: String): String? {
        return try {
            val keySpec = deriveKey(sessionId)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(jsonPayload.toByteArray(StandardCharsets.UTF_8))

            // Prefix IV (16 bytes) to ciphertext
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

            Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption error: ${e.message}", e)
            null
        }
    }

    /**
     * Decrypts the Base64 token using sessionId
     */
    fun decryptPayload(encryptedToken: String?, sessionId: String?): String? {
        if (encryptedToken.isNullOrBlank() || sessionId.isNullOrBlank()) return null
        return try {
            val cleanToken = try {
                URLDecoder.decode(encryptedToken, "UTF-8")
            } catch (_: Exception) {
                encryptedToken
            }

            val combined = try {
                Base64.decode(cleanToken, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            } catch (_: Exception) {
                try {
                    Base64.decode(cleanToken, Base64.DEFAULT)
                } catch (_: Exception) {
                    Base64.decode(encryptedToken, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                }
            }

            if (combined.size <= 16) {
                Log.e(TAG, "[QR] Token payload size invalid (${combined.size} bytes <= 16)")
                return null
            }

            val iv = ByteArray(16)
            System.arraycopy(combined, 0, iv, 0, 16)
            val ciphertext = ByteArray(combined.size - 16)
            System.arraycopy(combined, 16, ciphertext, 0, ciphertext.size)

            val keySpec = deriveKey(sessionId)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "[QR] Decryption error for sessionId=$sessionId: ${e.message}")
            null
        }
    }

    /**
     * Generates complete flip:// QR URI
     */
    fun generateQrUri(
        sessionId: String,
        deviceName: String,
        mode: String = "hotspot",
        ssid: String,
        pwd: String,
        ip: String,
        port: Int,
        durationMs: Long = DEFAULT_EXPIRATION_MS
    ): String {
        val now = System.currentTimeMillis()
        val exp = now + durationMs

        val payloadJson = JSONObject().apply {
            put("ssid", ssid)
            put("pwd", pwd)
            put("ip", ip)
            put("port", port)
            put("ts", now)
            put("exp", exp)
        }.toString()

        val token = encryptPayload(payloadJson, sessionId) ?: ""
        val encodedName = try {
            URLEncoder.encode(deviceName, "UTF-8")
        } catch (_: Exception) {
            deviceName
        }

        return "flip://v=$CURRENT_PROTOCOL_VERSION&id=$sessionId&name=$encodedName&mode=$mode&token=$token"
    }

    /**
     * Parses and validates a QR string
     */
    fun parseAndValidateQrUri(qrString: String): QrParseResult {
        Log.d(TAG, "[QR_STAGE] QR scanned: $qrString")

        if (!qrString.startsWith("flip://")) {
            val err = "Invalid QR code: Format does not match Flip protocol."
            Log.e(TAG, "[QR_STAGE] QR validation failed: $err")
            return QrParseResult.Error(err)
        }

        val uriBody = qrString.substringAfter("flip://")
        val params = parseQueryParams(uriBody)

        val version = params["v"]?.toIntOrNull() ?: 1
        if (version > CURRENT_PROTOCOL_VERSION) {
            val err = "Unsupported protocol version (v$version). Please update the Flip app."
            Log.e(TAG, "[QR_STAGE] QR validation failed: $err")
            return QrParseResult.Error(err)
        }

        val sessionId = params["id"] ?: ""
        if (sessionId.isBlank()) {
            // Legacy fallback check: ip and port directly in query
            val legacyIp = params["ip"] ?: ""
            val legacyPort = params["port"]?.toIntOrNull() ?: 8080
            if (legacyIp.isNotBlank()) {
                val legacyName = params["name"] ?: "Sender Device"
                Log.d(TAG, "[QR_STAGE] QR parsed (legacy direct format): ip=$legacyIp, port=$legacyPort")
                Log.d(TAG, "[HANDSHAKE_STAGE] Connection information extracted: ip=$legacyIp, port=$legacyPort")
                return QrParseResult.Success(
                    QrConnectionPackage(
                        version = 1,
                        sessionId = "legacy_${System.currentTimeMillis()}",
                        deviceName = legacyName,
                        mode = "direct",
                        ssid = "",
                        password = "",
                        ip = legacyIp,
                        port = legacyPort,
                        timestamp = System.currentTimeMillis(),
                        expiration = System.currentTimeMillis() + DEFAULT_EXPIRATION_MS
                    )
                )
            }
            val err = "Invalid QR code: Missing session ID."
            Log.e(TAG, "[QR_STAGE] QR validation failed: $err")
            return QrParseResult.Error(err)
        }

        // Check if session has been invalidated or finished
        if (isSessionInvalidated(sessionId)) {
            val err = "This QR code is no longer valid or the session has finished."
            Log.e(TAG, "[QR_STAGE] QR validation failed: $err")
            return QrParseResult.Error(err)
        }

        Log.d(TAG, "[QR_STAGE] QR parsed successfully: sessionId=$sessionId, version=$version")

        val deviceName = try {
            URLDecoder.decode(params["name"] ?: "Sender Device", "UTF-8")
        } catch (_: Exception) {
            params["name"] ?: "Sender Device"
        }
        val mode = params["mode"] ?: "hotspot"
        val token = params["token"]

        var ssid = ""
        var pwd = ""
        var ip = ""
        var port = 8080
        var ts = 0L
        var exp = 0L

        if (!token.isNullOrBlank()) {
            val decryptedJson = decryptPayload(token, sessionId)
            if (decryptedJson == null) {
                val err = "Decryption failed. QR code may be corrupted or invalid."
                Log.e(TAG, "[QR_STAGE] Token decryption failed for sessionId=$sessionId")
                return QrParseResult.Error(err)
            }
            Log.d(TAG, "[QR_STAGE] Token decrypted successfully: $decryptedJson")

            try {
                val json = JSONObject(decryptedJson)
                ssid = json.optString("ssid", "")
                pwd = json.optString("pwd", json.optString("password", ""))
                ip = json.optString("ip", "")
                port = json.optInt("port", 8080)
                ts = json.optLong("ts", 0L)
                exp = json.optLong("exp", 0L)
            } catch (e: Exception) {
                val err = "Invalid encrypted payload structure."
                Log.e(TAG, "[QR_STAGE] Token JSON parse error: ${e.message}")
                return QrParseResult.Error(err)
            }
        } else {
            // Unencrypted fallback check if token is missing
            ssid = params["ssid"] ?: ""
            pwd = params["pwd"] ?: params["password"] ?: ""
            ip = params["ip"] ?: ""
            port = params["port"]?.toIntOrNull() ?: 8080
            ts = params["ts"]?.toLongOrNull() ?: 0L
            exp = params["exp"]?.toLongOrNull() ?: 0L
            Log.d(TAG, "[QR_STAGE] Token is missing, using unencrypted query fallback")
        }

        if (ip.isBlank()) {
            val err = "Invalid QR code: Missing target IP address."
            Log.e(TAG, "[QR_STAGE] QR validation failed: $err")
            return QrParseResult.Error(err)
        }

        // Expiration check
        val now = System.currentTimeMillis()
        if (exp > 0 && now > exp) {
            val err = "This QR code has expired. Please ask the sender to generate a fresh QR code."
            Log.e(TAG, "[QR_STAGE] QR validation failed: $err")
            return QrParseResult.Error(err)
        }

        Log.d(TAG, "[HANDSHAKE_STAGE] Connection information extracted: ip=$ip, port=$port, ssid=$ssid, mode=$mode")

        return QrParseResult.Success(
            QrConnectionPackage(
                version = version,
                sessionId = sessionId,
                deviceName = deviceName,
                mode = mode,
                ssid = ssid,
                password = pwd,
                ip = ip,
                port = port,
                timestamp = ts,
                expiration = exp
            )
        )
    }

    private fun parseQueryParams(queryString: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val cleanQuery = queryString.substringAfter("?").trim()
        val pairs = cleanQuery.split("&")
        for (pair in pairs) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].removePrefix("?").trim()
                val value = parts[1].trim()
                if (key.isNotEmpty()) {
                    map[key] = value
                }
            } else if (parts.size == 1 && parts[0].isNotEmpty()) {
                val key = parts[0].removePrefix("?").trim()
                if (key.isNotEmpty()) {
                    map[key] = ""
                }
            }
        }
        return map
    }
}
