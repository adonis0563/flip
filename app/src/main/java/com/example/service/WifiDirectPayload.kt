package com.example.service

import android.util.Log
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Data model for Wi-Fi Direct handshake payload.
 *
 * Payload format:
 * flip://wfd?v=1&id=<full device UUID>&name=<enc>&n=<group name>&p=<passphrase>&o=<owner address>&t=<session token>&e=<expiry epoch seconds>
 */
data class WifiDirectPayload(
    val version: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val groupName: String,
    val passphrase: String,
    val ownerAddress: String = "",
    val sessionToken: String,
    val expiryEpochSeconds: Long
) {
    /**
     * Checks if payload is expired, allowing 2 minutes (120s) of clock difference between phones.
     */
    fun isExpired(
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
        toleranceSeconds: Long = WifiDirectPayloadParser.CLOCK_SKEW_TOLERANCE_SECONDS
    ): Boolean {
        return nowSeconds > (expiryEpochSeconds + toleranceSeconds)
    }
}

/**
 * Detailed parsing outcome indicating exactly which step or field failed.
 */
sealed interface ParseDetailedResult {
    data class Success(val payload: WifiDirectPayload, val fieldNames: List<String>) : ParseDetailedResult
    data class Failed(val field: String) : ParseDetailedResult
    data class Expired(val expiryEpochSeconds: Long, val currentEpochSeconds: Long) : ParseDetailedResult
}

/**
 * Shared serializer and parser for Wi-Fi Direct payload URIs.
 * Never logs sensitive credentials (passphrase or session token).
 */
object WifiDirectPayloadParser {
    const val SCHEME = "flip://wfd"
    private const val TAG = "FlipDirect"
    const val DEFAULT_EXPIRY_SECONDS = 600L // 10 minutes while testing
    const val CLOCK_SKEW_TOLERANCE_SECONDS = 120L // 2 minutes clock skew tolerance
    const val TOKEN_LENGTH = 8

    private val secureRandom = SecureRandom()
    private const val TOKEN_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /**
     * Trims leading/trailing whitespace and removes line breaks.
     */
    fun sanitize(raw: String): String {
        return raw.trim()
            .replace("\r\n", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
    }

    /**
     * Generates a cryptographically random 8-character session token.
     */
    fun generateSessionToken(): String {
        val sb = StringBuilder(TOKEN_LENGTH)
        repeat(TOKEN_LENGTH) {
            sb.append(TOKEN_CHARS[secureRandom.nextInt(TOKEN_CHARS.length)])
        }
        return sb.toString()
    }

    /**
     * Builds a flip://wfd QR/BLE payload URI string.
     * Every parameter value is URL-encoded.
     * Uses a 10-minute expiry for testing and an 8-character token if not explicitly provided.
     */
    fun build(
        deviceId: String,
        deviceName: String,
        groupName: String,
        passphrase: String,
        ownerAddress: String = "",
        sessionToken: String = generateSessionToken(),
        expiryEpochSeconds: Long = (System.currentTimeMillis() / 1000L) + DEFAULT_EXPIRY_SECONDS
    ): String {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val cleanId = DeviceIdentity.normalizeUuid(deviceId)

        Log.d(TAG, "buildPayload: generating payload for group=$groupName, expiryEpochSeconds=$expiryEpochSeconds (10m test window)")

        return StringBuilder(SCHEME)
            .append("?v=1")
            .append("&id=").append(enc(cleanId))
            .append("&name=").append(enc(deviceName))
            .append("&n=").append(enc(groupName))
            .append("&p=").append(enc(passphrase))
            .append("&o=").append(enc(ownerAddress))
            .append("&t=").append(enc(sessionToken))
            .append("&e=").append(enc(expiryEpochSeconds.toString()))
            .toString()
    }

    /**
     * Extracts query parameter map from URI after sanitizing whitespace.
     */
    fun extractParams(rawPayload: String): Map<String, String>? {
        val sanitized = sanitize(rawPayload)
        if (!sanitized.startsWith(SCHEME) || !sanitized.contains("?")) {
            return null
        }
        val queryPart = sanitized.substringAfter("?")
        return queryPart.split("&").mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx > 0) {
                val key = entry.substring(0, idx)
                val rawVal = entry.substring(idx + 1)
                val decodedVal = try {
                    URLDecoder.decode(rawVal, "UTF-8")
                } catch (_: Exception) {
                    rawVal
                }
                key to decodedVal
            } else null
        }.toMap()
    }

    /**
     * Detailed parse naming the exact failing step or field.
     */
    fun parseDetailed(
        rawPayload: String,
        currentEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): ParseDetailedResult {
        val sanitized = sanitize(rawPayload)
        if (!sanitized.startsWith(SCHEME)) {
            Log.w(TAG, "parseDetailed: invalid scheme (must start with $SCHEME)")
            return ParseDetailedResult.Failed("scheme (expected '$SCHEME')")
        }
        if (!sanitized.contains("?")) {
            Log.w(TAG, "parseDetailed: missing query parameters ('?')")
            return ParseDetailedResult.Failed("query string (missing '?')")
        }

        return try {
            val params = extractParams(sanitized)
                ?: return ParseDetailedResult.Failed("query parameters malformed")

            val fieldNames = params.keys.toList()

            val v = params["v"]?.toIntOrNull()
            if (v == null || v < 1) {
                Log.w(TAG, "parseDetailed: missing or invalid version 'v'")
                return ParseDetailedResult.Failed("v (version)")
            }

            val id = params["id"]?.takeIf { it.isNotBlank() }
            if (id == null) {
                Log.w(TAG, "parseDetailed: missing 'id'")
                return ParseDetailedResult.Failed("id (device id)")
            }

            val name = params["name"]?.takeIf { it.isNotBlank() }
            if (name == null) {
                Log.w(TAG, "parseDetailed: missing 'name'")
                return ParseDetailedResult.Failed("name (device name)")
            }

            val groupName = params["n"]?.takeIf { it.isNotBlank() }
            if (groupName == null) {
                Log.w(TAG, "parseDetailed: missing 'n' (group name)")
                return ParseDetailedResult.Failed("n (group name)")
            }

            val passphrase = params["p"]?.takeIf { it.isNotBlank() }
            if (passphrase == null) {
                Log.w(TAG, "parseDetailed: missing 'p' (passphrase)")
                return ParseDetailedResult.Failed("p (passphrase)")
            }

            // 'o' is the owner device MAC: allowed to be empty or placeholder
            val ownerAddress = params["o"]?.trim() ?: ""

            val sessionToken = params["t"]?.takeIf { it.isNotBlank() }
            if (sessionToken == null || sessionToken.length != TOKEN_LENGTH) {
                Log.w(TAG, "parseDetailed: invalid 't' (session token length must be $TOKEN_LENGTH)")
                return ParseDetailedResult.Failed("t (session token, length ${sessionToken?.length ?: 0} != $TOKEN_LENGTH)")
            }

            val expiry = params["e"]?.toLongOrNull()
            if (expiry == null) {
                Log.w(TAG, "parseDetailed: missing or non-numeric 'e' (expiry)")
                return ParseDetailedResult.Failed("e (non-numeric expiry)")
            }

            // Compare epoch seconds with epoch seconds, allowing 2 minutes of clock skew
            if (currentEpochSeconds > (expiry + CLOCK_SKEW_TOLERANCE_SECONDS)) {
                Log.w(TAG, "parseDetailed: payload expired (expiry=$expiry, current=$currentEpochSeconds, tolerance=${CLOCK_SKEW_TOLERANCE_SECONDS}s)")
                return ParseDetailedResult.Expired(expiryEpochSeconds = expiry, currentEpochSeconds = currentEpochSeconds)
            }

            Log.d(TAG, "parseDetailed: success for group=$groupName, fields=$fieldNames")

            ParseDetailedResult.Success(
                payload = WifiDirectPayload(
                    version = v,
                    deviceId = DeviceIdentity.normalizeUuid(id),
                    deviceName = name,
                    groupName = groupName,
                    passphrase = passphrase,
                    ownerAddress = ownerAddress,
                    sessionToken = sessionToken,
                    expiryEpochSeconds = expiry
                ),
                fieldNames = fieldNames
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseDetailed: exception while parsing", e)
            ParseDetailedResult.Failed("exception: ${e.message}")
        }
    }

    /**
     * Parses a flip://wfd payload URI string.
     * Rejects malformed or expired payloads.
     */
    fun parse(
        rawPayload: String,
        currentEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): WifiDirectPayload? {
        val result = parseDetailed(rawPayload, currentEpochSeconds)
        return (result as? ParseDetailedResult.Success)?.payload
    }
}
