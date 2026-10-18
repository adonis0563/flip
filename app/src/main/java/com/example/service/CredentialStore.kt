package com.example.service

import android.content.Context
import android.util.Log

data class WifiCredentials(
    val ssid: String,
    val password: String = "",
    val security: String = "WPA", // WPA | WEP | OPEN | SAE
    val hidden: Boolean = false
) {
    val isHidden: Boolean get() = hidden
}

data class PeerSummary(
    val id: String,
    val displayName: String,
    val lastConnected: Long
)

/**
 * Standard Wi-Fi QR Parser for strings of the format:
 * WIFI:T:<WPA|WEP|nopass|SAE>;S:<ssid>;P:<password>;H:<true|false>;;
 *
 * Handles escaped characters: \\, \;, \,, \:
 * Rejects anything that isn't a Wi-Fi string or has no SSID.
 */
object WifiQrParser {
    fun parse(qrString: String?): WifiCredentials? {
        if (qrString.isNullOrBlank()) return null
        val trimmed = qrString.trim()
        if (!trimmed.startsWith("WIFI:", ignoreCase = true)) return null

        val content = trimmed.substring(5)
        var ssid: String? = null
        var password = ""
        var security = "WPA"
        var hidden = false

        var i = 0
        val length = content.length

        while (i < length) {
            // Find key up to ':'
            val keyBuilder = StringBuilder()
            while (i < length && content[i] != ':') {
                if (content[i] == ';') {
                    i++
                    break
                }
                keyBuilder.append(content[i])
                i++
            }

            if (i >= length || content[i] != ':') {
                break
            }
            i++ // Skip ':'

            val key = keyBuilder.toString().trim().uppercase()

            // Read value up to unescaped ';'
            val valBuilder = StringBuilder()
            var escaped = false

            while (i < length) {
                val c = content[i]
                if (escaped) {
                    when (c) {
                        ';', ',', ':', '\\', '"' -> valBuilder.append(c)
                        else -> {
                            valBuilder.append('\\')
                            valBuilder.append(c)
                        }
                    }
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == ';') {
                    i++ // Consume delimiter ';'
                    break
                } else {
                    valBuilder.append(c)
                }
                i++
            }

            val rawVal = valBuilder.toString()
            when (key) {
                "S" -> ssid = rawVal
                "P" -> password = rawVal
                "T" -> security = when (rawVal.uppercase()) {
                    "NOPASS", "NONE", "OPEN" -> "OPEN"
                    "WEP" -> "WEP"
                    "SAE", "WPA3", "WPA3-SAE" -> "SAE"
                    else -> "WPA"
                }
                "H" -> hidden = rawVal.equals("true", ignoreCase = true) || rawVal == "1"
            }
        }

        if (ssid.isNullOrEmpty()) {
            return null
        }

        return WifiCredentials(
            ssid = ssid,
            password = password,
            security = security,
            hidden = hidden
        )
    }
}

/**
 * Keystore-backed AES-256-GCM Encrypted Storage for Hotspot Credentials.
 *
 * Rules:
 * - Backed by Android Keystore AES-256-GCM encryption.
 * - Excluded from device-to-device transfers and cloud backups.
 * - Own credentials: saveOwn / getOwn / clearOwn
 * - Peer credentials keyed by full device UUID: savePeer / getPeer / listPeers / forgetPeer / forgetAll
 * - listPeers returns names, ids, and timestamps only, never passwords.
 * - Never logs credentials.
 */
object CredentialStore {
    private const val TAG = "CredentialStore"

    @Volatile
    private var globalContext: Context? = null

    fun init(context: Context) {
        globalContext = context.applicationContext
    }

    private fun resolveContext(context: Context?): Context {
        return context?.applicationContext
            ?: globalContext
            ?: throw IllegalStateException("CredentialStore context must be provided or initialized with CredentialStore.init(context)")
    }

    // --- OWN CREDENTIALS ---

    @Synchronized
    fun saveOwn(creds: WifiCredentials, context: Context? = null) {
        val ctx = resolveContext(context)
        HotspotCredentialStore.saveOwnHotspot(
            ctx,
            OwnHotspotCredentials(
                ssid = creds.ssid,
                password = creds.password,
                security = creds.security,
                isHidden = creds.hidden
            )
        )
        Log.i(TAG, "[HOTSPOT] Own credentials saved securely")
    }

    @Synchronized
    fun saveOwn(context: Context, creds: WifiCredentials) {
        saveOwn(creds, context)
    }

    @Synchronized
    fun getOwn(context: Context? = null): WifiCredentials? {
        val ctx = resolveContext(context)
        val own = HotspotCredentialStore.getOwnHotspot(ctx) ?: return null
        return WifiCredentials(
            ssid = own.ssid,
            password = own.password,
            security = own.security,
            hidden = own.isHidden
        )
    }

    @Synchronized
    fun clearOwn(context: Context? = null) {
        val ctx = resolveContext(context)
        HotspotCredentialStore.clearOwnHotspot(ctx)
        Log.i(TAG, "[HOTSPOT] Own credentials cleared")
    }

    // --- PEER CREDENTIALS ---

    @Synchronized
    fun savePeer(id: String, creds: WifiCredentials, displayName: String, context: Context? = null) {
        val ctx = resolveContext(context)
        val cleanId = DeviceIdentity.normalizeUuid(id)
        HotspotCredentialStore.savePairedPeer(
            ctx,
            PairedPeer(
                peerDeviceId = cleanId,
                displayName = displayName,
                ssid = creds.ssid,
                password = creds.password,
                security = creds.security,
                isHidden = creds.hidden,
                lastConnected = System.currentTimeMillis()
            )
        )
        Log.i(TAG, "[HOTSPOT] Peer credentials saved for peer id: ${DeviceIdentity.getShortCodeFromUuid(cleanId)} (name: $displayName)")
    }

    @Synchronized
    fun savePeer(context: Context, id: String, creds: WifiCredentials, displayName: String) {
        savePeer(id, creds, displayName, context)
    }

    @Synchronized
    fun getPeer(id: String, context: Context? = null): WifiCredentials? {
        val ctx = resolveContext(context)
        val cleanId = DeviceIdentity.normalizeUuid(id)
        val peer = HotspotCredentialStore.getPairedPeer(ctx, cleanId) ?: return null
        return WifiCredentials(
            ssid = peer.ssid,
            password = peer.password,
            security = peer.security,
            hidden = peer.isHidden
        )
    }

    @Synchronized
    fun getPeer(context: Context, id: String): WifiCredentials? {
        return getPeer(id, context)
    }

    @Synchronized
    fun listPeers(context: Context? = null): List<PeerSummary> {
        val ctx = resolveContext(context)
        // Returns names, ids and timestamps only, never passwords
        return HotspotCredentialStore.getAllPairedPeers(ctx).map { peer ->
            PeerSummary(
                id = DeviceIdentity.normalizeUuid(peer.peerDeviceId),
                displayName = peer.displayName,
                lastConnected = peer.lastConnected
            )
        }
    }

    @Synchronized
    fun forgetPeer(id: String, context: Context? = null) {
        val ctx = resolveContext(context)
        val cleanId = DeviceIdentity.normalizeUuid(id)
        HotspotCredentialStore.forgetPeer(ctx, cleanId)
        Log.i(TAG, "[HOTSPOT] Peer forgotten: ${DeviceIdentity.getShortCodeFromUuid(cleanId)}")
    }

    @Synchronized
    fun forgetPeer(context: Context, id: String) {
        forgetPeer(id, context)
    }

    @Synchronized
    fun forgetAll(context: Context? = null) {
        val ctx = resolveContext(context)
        HotspotCredentialStore.forgetAllPeers(ctx)
        Log.i(TAG, "[HOTSPOT] All peers forgotten")
    }
}
