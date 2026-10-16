package com.example.service

import android.content.Context
import android.os.Build
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/**
 * Manages the stable, per-installation Device Identity for Flip.
 *
 * Rules:
 * - Generates a random UUID once on first launch and stores it in Keystore-backed
 *   encrypted storage (excluded from cloud backups and device-to-device transfers).
 * - Reused forever across all sessions until app data is cleared or reinstalled.
 * - Never uses IMEI, serial, MAC, or ANDROID_ID.
 * - DeviceIdentity { uuid, id8, shortCode, displayName }:
 *     - id8 = first 8 hex characters of SHA-256(uuid)
 *     - shortCode = first 4 characters of id8 uppercased
 *     - displayName defaults to the phone model and can be renamed
 * - Provides a per-session `sid` (random 8 hex characters, refreshed on process restart)
 *   for session-level uniqueness.
 */
data class DeviceIdentity(
    val uuid: String,
    val id8: String,
    val shortCode: String,
    val displayName: String
) {
    companion object {
        private const val TAG = "DeviceIdentity"
        private const val KEY_INSTALL_UUID = "device_install_uuid_v1"
        private const val KEY_DISPLAY_NAME = "device_display_name_v1"

        // Volatile session ID generated once per application process launch
        val currentSessionId: String by lazy {
            UUID.randomUUID().toString().replace("-", "").take(8)
        }

        @Volatile
        private var cachedIdentity: DeviceIdentity? = null

        /**
         * Resolves the DeviceIdentity instance. Reuses existing UUID and display name from storage.
         */
        @Synchronized
        fun get(context: Context): DeviceIdentity {
            cachedIdentity?.let { return it }

            val uuid = getInstallUuid(context)
            val id8 = deriveHexId(uuid)
            val shortCode = id8.take(4).uppercase()
            val displayName = getDisplayName(context)

            val identity = DeviceIdentity(
                uuid = uuid,
                id8 = id8,
                shortCode = shortCode,
                displayName = displayName
            )
            cachedIdentity = identity
            return identity
        }

        /**
         * Gets or generates the installation UUID stored in encrypted preferences.
         * Never uses IMEI, serial, MAC, or ANDROID_ID.
         */
        @Synchronized
        fun getInstallUuid(context: Context): String {
            val existing = HotspotCredentialStore.getEncryptedString(context, KEY_INSTALL_UUID)
            if (!existing.isNullOrBlank()) {
                return existing
            }

            // Generate random UUID once on first launch
            val newUuid = UUID.randomUUID().toString()
            HotspotCredentialStore.saveEncryptedString(context, KEY_INSTALL_UUID, newUuid)
            Log.i(TAG, "[IDENTITY] New install UUID initialized in encrypted storage")
            return newUuid
        }

        /**
         * Returns the stable 8-hex-character device ID derived from SHA-256(install_uuid).
         */
        @Synchronized
        fun getStableDeviceId(context: Context): String {
            return get(context).id8
        }

        /**
         * Derives the first 8 hex characters of SHA-256(input).
         */
        fun deriveHexId(input: String): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
                val hexString = hashBytes.joinToString("") { "%02x".format(it) }
                hexString.take(8)
            } catch (e: Exception) {
                Log.e(TAG, "[IDENTITY] SHA-256 derivation failed: ${e.message}")
                "%08x".format(input.hashCode())
            }
        }

        /**
         * Returns 4-character short code from device ID (uppercased).
         */
        @Synchronized
        fun getShortCode(context: Context): String {
            return get(context).shortCode
        }

        /**
         * Returns the device display name, defaulting to Build.MODEL.
         */
        @Synchronized
        fun getDisplayName(context: Context): String {
            val custom = HotspotCredentialStore.getEncryptedString(context, KEY_DISPLAY_NAME)
            if (!custom.isNullOrBlank()) {
                return custom
            }
            val legacy = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
                .getString("device_name", null)
            if (!legacy.isNullOrBlank()) {
                return legacy
            }
            return if (!Build.MODEL.isNullOrBlank()) Build.MODEL else "Android Device"
        }

        /**
         * Renames the device display name and persists in encrypted storage.
         */
        @Synchronized
        fun setDisplayName(context: Context, newName: String) {
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) return
            HotspotCredentialStore.saveEncryptedString(context, KEY_DISPLAY_NAME, trimmed)
            context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("device_name", trimmed)
                .apply()
            cachedIdentity = null
            Log.i(TAG, "[IDENTITY] Device display name updated")
        }

        /**
         * For testing: resets in-memory cache so fresh reads hit storage.
         */
        @Synchronized
        fun resetMemoryCacheForTesting() {
            cachedIdentity = null
        }
    }
}
