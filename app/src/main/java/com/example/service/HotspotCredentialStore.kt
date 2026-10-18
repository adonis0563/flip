package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class OwnHotspotCredentials(
    val ssid: String,
    val password: String,
    val security: String = "WPA",
    val isHidden: Boolean = false
)

data class PairedPeer(
    val peerDeviceId: String,
    val displayName: String,
    val ssid: String,
    val password: String,
    val security: String = "WPA",
    val isHidden: Boolean = false,
    val lastConnected: Long = System.currentTimeMillis(),
    val needsRePair: Boolean = false
) {
    val shortCode: String get() = DeviceIdentity.getShortCodeFromUuid(peerDeviceId)
}

/**
 * Keystore-backed AES-256-GCM Encrypted Storage for Hotspot Credentials and Paired Peers.
 * Excluded from backups to ensure sensitive credentials never leave the device.
 */
object HotspotCredentialStore {
    private const val TAG = "HotspotCredentialStore"
    private const val PREFS_NAME = "flip_secure_hotspot_prefs"
    private const val KEY_ALIAS = "flip_hotspot_aes_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private const val KEY_OWN_HOTSPOT = "enc_own_hotspot"
    private const val KEY_PAIRED_PEERS = "enc_paired_peers"
    private const val KEY_KNOWN_RECEIVERS = "known_receivers"

    private val _pairedPeersFlow = kotlinx.coroutines.flow.MutableStateFlow<List<PairedPeer>>(emptyList())
    val pairedPeersFlow: kotlinx.coroutines.flow.StateFlow<List<PairedPeer>> = _pairedPeersFlow

    @Synchronized
    fun initFlow(context: Context) {
        _pairedPeersFlow.value = getAllPairedPeers(context)
    }

    @Synchronized
    fun notifyPeersChanged(context: Context) {
        _pairedPeersFlow.value = getAllPairedPeers(context)
    }

    @Volatile
    private var fallbackKey: SecretKey? = null

    private fun getSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
            } else {
                (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
                    ?: (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                    ?: getOrCreateFallbackKey()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[KEYSTORE] AndroidKeyStore provider unavailable in this environment (${e.message}), using AES-256 fallback")
            getOrCreateFallbackKey()
        }
    }

    @Synchronized
    private fun getOrCreateFallbackKey(): SecretKey {
        fallbackKey?.let { return it }
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val key = keyGen.generateKey()
        fallbackKey = key
        return key
    }

    private fun encrypt(plainText: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "[ENCRYPT] Failed to encrypt data: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    private fun decrypt(encryptedBase64: String): String? {
        if (encryptedBase64.isBlank()) return null
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return null
            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherText = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "[DECRYPT] Failed to decrypt data: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- SENDER: OWN HOTSPOT CREDENTIALS ---

    fun saveOwnHotspot(context: Context, credentials: OwnHotspotCredentials) {
        val json = JSONObject().apply {
            put("ssid", credentials.ssid)
            put("password", credentials.password)
            put("security", credentials.security)
            put("hidden", credentials.isHidden)
        }.toString()
        val enc = encrypt(json)
        getPrefs(context).edit().putString(KEY_OWN_HOTSPOT, enc).apply()
        Log.i(TAG, "[HOTSPOT] Own hotspot credentials saved securely")
    }

    fun getOwnHotspot(context: Context): OwnHotspotCredentials? {
        val enc = getPrefs(context).getString(KEY_OWN_HOTSPOT, null) ?: return null
        val jsonStr = decrypt(enc) ?: return null
        return try {
            val obj = JSONObject(jsonStr)
            OwnHotspotCredentials(
                ssid = obj.getString("ssid"),
                password = obj.getString("password"),
                security = obj.optString("security", "WPA"),
                isHidden = obj.optBoolean("hidden", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "[HOTSPOT] Failed to parse own credentials: ${e.message}")
            null
        }
    }

    fun hasOwnHotspot(context: Context): Boolean {
        return getOwnHotspot(context) != null
    }

    fun clearOwnHotspot(context: Context) {
        getPrefs(context).edit().remove(KEY_OWN_HOTSPOT).apply()
    }

    // --- GENERIC SECURE STRING STORAGE ---

    @Synchronized
    fun saveEncryptedString(context: Context, key: String, value: String) {
        val enc = encrypt(value)
        getPrefs(context).edit().putString(key, enc).apply()
    }

    @Synchronized
    fun getEncryptedString(context: Context, key: String): String? {
        val enc = getPrefs(context).getString(key, null) ?: return null
        return decrypt(enc)
    }

    @Synchronized
    fun removeEncryptedString(context: Context, key: String) {
        getPrefs(context).edit().remove(key).apply()
    }

    // --- RECEIVER: PAIRED PEERS ---

    @Synchronized
    fun savePairedPeer(context: Context, peer: PairedPeer) {
        val cleanPeer = peer.copy(peerDeviceId = DeviceIdentity.normalizeUuid(peer.peerDeviceId))
        val peers = getAllPairedPeers(context).toMutableList()
        peers.removeAll { DeviceIdentity.normalizeUuid(it.peerDeviceId) == cleanPeer.peerDeviceId }
        peers.add(cleanPeer)
        saveAllPairedPeers(context, peers)
        notifyPeersChanged(context)
        Log.i(TAG, "[PAIRING] Paired peer stored: ${cleanPeer.displayName} (${cleanPeer.shortCode})")
    }

    @Synchronized
    fun getPairedPeer(context: Context, peerDeviceId: String): PairedPeer? {
        val clean = DeviceIdentity.normalizeUuid(peerDeviceId)
        return getAllPairedPeers(context).firstOrNull { peer ->
            DeviceIdentity.normalizeUuid(peer.peerDeviceId).equals(clean, ignoreCase = true)
        }
    }

    @Synchronized
    fun getAllPairedPeers(context: Context): List<PairedPeer> {
        val enc = getPrefs(context).getString(KEY_PAIRED_PEERS, null) ?: return emptyList()
        val jsonStr = decrypt(enc) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val result = mutableListOf<PairedPeer>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    PairedPeer(
                        peerDeviceId = DeviceIdentity.normalizeUuid(obj.getString("id")),
                        displayName = obj.getString("name"),
                        ssid = obj.getString("ssid"),
                        password = obj.getString("password"),
                        security = obj.optString("security", "WPA"),
                        isHidden = obj.optBoolean("hidden", false),
                        lastConnected = obj.optLong("lastConnected", System.currentTimeMillis()),
                        needsRePair = obj.optBoolean("needsRePair", false)
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "[PAIRING] Failed to parse paired peers: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    fun markNeedsRePair(context: Context, peerDeviceId: String, needsRePair: Boolean) {
        val clean = DeviceIdentity.normalizeUuid(peerDeviceId)
        val peers = getAllPairedPeers(context).map {
            if (DeviceIdentity.normalizeUuid(it.peerDeviceId) == clean) it.copy(needsRePair = needsRePair) else it
        }
        saveAllPairedPeers(context, peers)
        notifyPeersChanged(context)
        Log.w(TAG, "[PAIRING] Peer marked needsRePair=$needsRePair: ${DeviceIdentity.getShortCodeFromUuid(clean)}")
    }

    @Synchronized
    fun updateLastConnected(context: Context, peerDeviceId: String) {
        val clean = DeviceIdentity.normalizeUuid(peerDeviceId)
        val peers = getAllPairedPeers(context).map {
            if (DeviceIdentity.normalizeUuid(it.peerDeviceId) == clean) it.copy(lastConnected = System.currentTimeMillis(), needsRePair = false) else it
        }
        saveAllPairedPeers(context, peers)
        notifyPeersChanged(context)
    }

    @Synchronized
    fun forgetPeer(context: Context, peerDeviceId: String) {
        val clean = DeviceIdentity.normalizeUuid(peerDeviceId)
        val peers = getAllPairedPeers(context).filterNot { DeviceIdentity.normalizeUuid(it.peerDeviceId) == clean }
        saveAllPairedPeers(context, peers)
        notifyPeersChanged(context)
        Log.i(TAG, "[PAIRING] Peer removed: ${DeviceIdentity.getShortCodeFromUuid(clean)}")
    }

    @Synchronized
    fun forgetAllPeers(context: Context) {
        getPrefs(context).edit().remove(KEY_PAIRED_PEERS).apply()
        notifyPeersChanged(context)
        Log.i(TAG, "[PAIRING] All paired peers removed")
    }

    private fun saveAllPairedPeers(context: Context, peers: List<PairedPeer>) {
        val array = JSONArray()
        peers.forEach { peer ->
            val cleanId = DeviceIdentity.normalizeUuid(peer.peerDeviceId)
            val obj = JSONObject().apply {
                put("id", cleanId)
                put("name", peer.displayName)
                put("ssid", peer.ssid)
                put("password", peer.password)
                put("security", peer.security)
                put("hidden", peer.isHidden)
                put("lastConnected", peer.lastConnected)
                put("needsRePair", peer.needsRePair)
            }
            array.put(obj)
        }
        val enc = encrypt(array.toString())
        getPrefs(context).edit().putString(KEY_PAIRED_PEERS, enc).apply()
    }

    // --- SENDER: KNOWN RECEIVER PEERS (ID and display name only, no credentials) ---

    @Synchronized
    fun saveKnownReceiver(context: Context, receiverId: String, displayName: String) {
        val cleanId = DeviceIdentity.normalizeUuid(receiverId)
        val prefs = getPrefs(context)
        val raw = prefs.getString(KEY_KNOWN_RECEIVERS, "{}") ?: "{}"
        try {
            val json = JSONObject(raw)
            val devObj = JSONObject().apply {
                put("name", displayName)
                put("lastSeen", System.currentTimeMillis())
            }
            json.put(cleanId, devObj)
            prefs.edit().putString(KEY_KNOWN_RECEIVERS, json.toString()).apply()
            notifyPeersChanged(context)
            Log.i(TAG, "[SENDER] Known receiver recorded: $displayName (${DeviceIdentity.getShortCodeFromUuid(cleanId)})")
        } catch (e: Exception) {
            Log.w(TAG, "[SENDER] Failed to record known receiver: ${e.message}")
        }
    }
}
