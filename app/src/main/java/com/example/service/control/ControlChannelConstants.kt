package com.example.service.control

import android.util.Log
import com.example.service.DeviceIdentity

/**
 * Constants, UUID normalization, and non-sensitive logging utilities
 * for the Flip control channel.
 */
object ControlChannelConstants {
    const val PORT: Int = 8081
    const val MAX_MESSAGE_BYTES: Int = 512
    const val CONNECT_TIMEOUT_MS: Int = 3000
    const val READY_TIMEOUT_MS: Int = 5000
    const val HEARTBEAT_INTERVAL_MS: Long = 5000L
    const val PONG_TIMEOUT_MS: Int = 3000
    const val SERVER_HEARTBEAT_TIMEOUT_MS: Int = 15000
    const val MAX_HEARTBEAT_MISSES: Int = 3

    const val TAG: String = "FlipLink"

    /**
     * Shared function that normalizes any UUID string to lowercase with standard hyphens (8-4-4-4-12).
     * Used for every UUID comparison across FlipLink.
     */
    fun normalizeUuid(raw: String): String {
        return DeviceIdentity.normalizeUuid(raw)
    }

    /**
     * Returns the 4-character uppercase short code for any UUID.
     */
    fun getShortCode(uuid: String): String {
        return DeviceIdentity.getShortCodeFromUuid(uuid)
    }

    /**
     * Diagnostic logger for FlipLink:
     * - Only step names, elapsed milliseconds, and 4-character short codes for IDs.
     * - NEVER logs names, passwords, or IP addresses.
     */
    fun logStep(
        step: String,
        elapsedMs: Long? = null,
        peerId: String? = null,
        expectedId: String? = null,
        actualId: String? = null,
        detail: String? = null
    ) {
        val sb = StringBuilder("step=").append(step)
        if (peerId != null) {
            sb.append(", peer=").append(getShortCode(peerId))
        }
        if (expectedId != null) {
            sb.append(", expected=").append(getShortCode(expectedId))
        }
        if (actualId != null) {
            sb.append(", actual=").append(getShortCode(actualId))
        }
        if (elapsedMs != null) {
            sb.append(", elapsed=").append(elapsedMs).append("ms")
        }
        if (detail != null) {
            sb.append(", detail=").append(detail)
        }
        Log.i(TAG, sb.toString())
    }

    /**
     * Extracts the last octet or last segment of an IP address for safe logging.
     * Never logs full addresses.
     */
    fun lastOctet(ip: String?): String {
        if (ip.isNullOrBlank()) return "?"
        return ip.trim().substringAfterLast('.').substringAfterLast(':').ifEmpty { "?" }
    }

    /**
     * Converts an InetAddress or IPv4-mapped IPv6 address to a clean IPv4 address string.
     * Returns null if not an IPv4 address.
     */
    fun toIpv4(address: java.net.InetAddress?): String? {
        if (address == null) return null
        if (address is java.net.Inet4Address) {
            val host = address.hostAddress?.removePrefix("/") ?: return null
            if (host.isNotBlank() && host != "0.0.0.0") return host
        }
        val raw = address.hostAddress?.removePrefix("/") ?: return null
        if (raw.contains("::ffff:", ignoreCase = true)) {
            val extracted = raw.substringAfterLast(":")
            if (extracted.count { it == '.' } == 3 && !extracted.contains(":")) {
                return extracted
            }
        }
        val clean = raw.removePrefix("/")
        if (clean.count { it == '.' } == 3 && !clean.contains(":")) {
            return clean
        }
        return null
    }

    /**
     * Returns true if both IPv4 addresses belong to the same /24 subnet.
     */
    fun isSameSubnet24(ip1: String?, ip2: String?): Boolean {
        if (ip1.isNullOrBlank() || ip2.isNullOrBlank()) return false
        val parts1 = ip1.split(".")
        val parts2 = ip2.split(".")
        if (parts1.size == 4 && parts2.size == 4) {
            return parts1[0] == parts2[0] && parts1[1] == parts2[1] && parts1[2] == parts2[2]
        }
        return false
    }

    /**
     * Reads ARP table to find connected client IP on the same subnet as ownerIp.
     */
    fun getReceiverIpFromArp(ownerIp: String? = null): String? {
        try {
            val subnetPrefix = if (!ownerIp.isNullOrBlank() && ownerIp.contains(".")) {
                ownerIp.substringBeforeLast(".") + "."
            } else {
                null
            }
            val reader = java.io.BufferedReader(java.io.FileReader("/proc/net/arp"))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val tokens = line?.split("\\s+".toRegex())
                if (tokens != null && tokens.size >= 4) {
                    val ip = tokens[0]
                    val matchesSubnet = if (subnetPrefix != null) ip.startsWith(subnetPrefix) else (ip.contains(".") && !ip.startsWith("127."))
                    if (matchesSubnet && ip != ownerIp && ip != "0.0.0.0" && !ip.startsWith("127.")) {
                        reader.close()
                        return ip
                    }
                }
            }
            reader.close()
        } catch (_: Exception) {}
        return null
    }
}
