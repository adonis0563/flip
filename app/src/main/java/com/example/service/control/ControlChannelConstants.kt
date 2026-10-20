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
}
