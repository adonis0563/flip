package com.example.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal data class representing a connected device session.
 * Stores strictly display name, 4-char short code, and last connected timestamp.
 */
data class ConnectedDeviceSession(
    val displayName: String,
    val shortCode: String,
    val lastConnected: Long
)

/**
 * Storage and cleanup manager for Connected Devices.
 *
 * Rules:
 * - Stores only: displayName, shortCode, lastConnected.
 * - Caps past sessions to the last 10 devices.
 * - On app start, executes a one-time cleanup to delete any saved hotspot credentials
 *   or peer entries left by the removed first-time feature, then marks it done.
 * - Provides "Clear list" to wipe past session history.
 */
object ConnectedDevicesStore {
    private const val TAG = "ConnectedDevicesStore"
    private const val PREFS_NAME = "flip_connected_devices_prefs"
    private const val KEY_PAST_DEVICES = "past_connected_devices_v1"
    private const val KEY_FIRST_TIME_CLEANED = "first_time_cleanup_done_v1"
    private const val MAX_PAST_DEVICES = 10

    private val _pastDevicesFlow = MutableStateFlow<List<ConnectedDeviceSession>>(emptyList())
    val pastDevicesFlow: StateFlow<List<ConnectedDeviceSession>> = _pastDevicesFlow.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        performOneTimeFirstTimeCleanup(context)
        loadPastDevices(context)
    }

    /**
     * Deletes any saved hotspot credentials and peer entries left by the first-time feature.
     * Executes once, then marks it done in SharedPreferences.
     */
    @Synchronized
    fun performOneTimeFirstTimeCleanup(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_FIRST_TIME_CLEANED, false)) {
            try {
                // Clear own hotspot credentials
                HotspotCredentialStore.clearOwnHotspot(context)
                // Clear paired peers
                HotspotCredentialStore.forgetAllPeers(context)

                // Clear encrypted prefs directly
                context.getSharedPreferences("flip_secure_hotspot_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .remove("enc_own_hotspot")
                    .remove("enc_paired_peers")
                    .remove("known_receivers")
                    .apply()

                // Clear legacy peer credentials
                context.getSharedPreferences("flip_credentials", Context.MODE_PRIVATE)
                    .edit()
                    .remove("peers_v1")
                    .apply()

                Log.i(TAG, "[CLEANUP] Deleted saved hotspot credentials and peer entries left by first-time feature")
            } catch (e: Exception) {
                Log.w(TAG, "[CLEANUP] Exception during first-time cleanup: ${e.message}")
            }
            prefs.edit().putBoolean(KEY_FIRST_TIME_CLEANED, true).apply()
        }
    }

    @Synchronized
    fun recordSession(context: Context, displayName: String, shortCode: String) {
        val currentList = getPastDevices(context).toMutableList()
        val cleanShortCode = shortCode.trim().uppercase().take(4)
        val cleanName = displayName.trim().ifEmpty { "Device" }

        // Remove any previous entry with matching short code
        currentList.removeAll { it.shortCode.equals(cleanShortCode, ignoreCase = true) }

        // Insert at top of list
        currentList.add(0, ConnectedDeviceSession(cleanName, cleanShortCode, System.currentTimeMillis()))

        // Cap to the last 10 devices
        val trimmed = currentList.take(MAX_PAST_DEVICES)
        savePastDevices(context, trimmed)
        _pastDevicesFlow.value = trimmed
        Log.i(TAG, "[CONNECTED_DEVICES] Recorded session: $cleanName · $cleanShortCode (total: ${trimmed.size})")
    }

    @Synchronized
    fun getPastDevices(context: Context): List<ConnectedDeviceSession> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PAST_DEVICES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<ConnectedDeviceSession>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ConnectedDeviceSession(
                        displayName = obj.optString("displayName", "Device"),
                        shortCode = obj.optString("shortCode", "").take(4).uppercase(),
                        lastConnected = obj.optLong("lastConnected", 0L)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clearList(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PAST_DEVICES).apply()
        _pastDevicesFlow.value = emptyList()
        Log.i(TAG, "[CONNECTED_DEVICES] Cleared past devices list")
    }

    private fun loadPastDevices(context: Context) {
        _pastDevicesFlow.value = getPastDevices(context)
    }

    private fun savePastDevices(context: Context, list: List<ConnectedDeviceSession>) {
        val array = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("displayName", item.displayName)
                put("shortCode", item.shortCode)
                put("lastConnected", item.lastConnected)
            }
            array.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PAST_DEVICES, array.toString()).apply()
    }
}
