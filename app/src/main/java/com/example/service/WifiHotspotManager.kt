package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

class WifiHotspotManager(private val context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private var localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    companion object {
        private const val TAG = "WifiHotspotManager"
        @Volatile var activeHotspotSsid: String? = null
            private set
        @Volatile var activeHotspotPassword: String? = null
            private set
        @Volatile var activeHotspotIp: String? = null
            private set

        fun decodeFailureReason(reason: Int): String {
            return when (reason) {
                WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
                    "ERROR_NO_CHANNEL (code 1): No Wi-Fi channel available or Location Services are disabled"
                WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
                    "ERROR_GENERIC (code 2): Internal system error or Wi-Fi hardware state invalid"
                WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
                    "ERROR_INCOMPATIBLE_MODE (code 3): Wi-Fi busy, tethering active, or existing reservation held"
                WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
                    "ERROR_TETHERING_DISALLOWED (code 4): Tethering/Hotspot disallowed by system policy or carrier"
                else ->
                    "UNKNOWN_ERROR (code $reason): Unrecognized platform error code"
            }
        }
    }

    interface HotspotListener {
        fun onHotspotStarted(ssid: String, psw: String, ip: String)
        fun onHotspotFailed(error: String)
    }

    interface WifiJoinListener {
        fun onJoined(ip: String)
        fun onFailed(error: String)
    }

    @SuppressLint("MissingPermission")
    fun startHotspot(listener: HotspotListener) {
        Log.d(TAG, "[HOTSPOT_DIAG] ==================== HOTSPOT STARTUP DIAGNOSTICS ====================")
        Log.d(TAG, "[HOTSPOT_DIAG] Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        Log.d(TAG, "[HOTSPOT_DIAG] Manufacturer: ${Build.MANUFACTURER}")
        Log.d(TAG, "[HOTSPOT_DIAG] Model: ${Build.MODEL}")

        // 1. Device SDK Version compatibility check
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val err = "LocalOnlyHotspot API requires Android 8.0 (API level 26) or higher. Current SDK: ${Build.VERSION.SDK_INT}"
            Log.e(TAG, "[HOTSPOT_DIAG] $err")
            activeHotspotSsid = null
            activeHotspotPassword = null
            activeHotspotIp = null
            listener.onHotspotFailed(err)
            return
        }

        // 2. Pre-flight Runtime Permissions Check
        val missingPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add("ACCESS_WIFI_STATE")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add("CHANGE_WIFI_STATE")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add("ACCESS_FINE_LOCATION")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("NEARBY_WIFI_DEVICES")
            }
        }

        Log.d(TAG, "[HOTSPOT_DIAG] Missing Runtime Permissions: ${if (missingPermissions.isEmpty()) "None" else missingPermissions.joinToString()}")
        if (missingPermissions.isNotEmpty()) {
            val err = "Missing required runtime permissions for Hotspot creation: ${missingPermissions.joinToString(", ")}. Please grant permissions in device settings."
            Log.e(TAG, "[HOTSPOT_DIAG] $err")
            activeHotspotSsid = null
            activeHotspotPassword = null
            activeHotspotIp = null
            listener.onHotspotFailed(err)
            return
        }

        // 3. Pre-flight Wi-Fi Enabled State Check
        val isWifiEnabled = try {
            wifiManager.isWifiEnabled
        } catch (e: Exception) {
            Log.w(TAG, "[HOTSPOT_DIAG] Could not check Wi-Fi enabled status: ${e.message}")
            false
        }
        val wifiState = try { wifiManager.wifiState } catch (_: Exception) { -1 }
        Log.d(TAG, "[HOTSPOT_DIAG] Wi-Fi Enabled: $isWifiEnabled (Wi-Fi state code: $wifiState)")
        if (!isWifiEnabled) {
            Log.w(TAG, "[HOTSPOT_DIAG] WARNING: Wi-Fi hardware is currently disabled. System startLocalOnlyHotspot may fail on this device.")
        }

        // 4. Pre-flight Location Services State Check
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isLocationEnabled = locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false
        Log.d(TAG, "[HOTSPOT_DIAG] Location Services Enabled: $isLocationEnabled")
        if (!isLocationEnabled && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            Log.w(TAG, "[HOTSPOT_DIAG] WARNING: Location Services are OFF. LocalOnlyHotspot on Android 8-11 requires Location Services to be enabled.")
        }

        // 5. Release any existing reservation before starting a new one
        if (localOnlyHotspotReservation != null) {
            Log.d(TAG, "[HOTSPOT] Active reservation exists. Releasing before creating a new hotspot...")
            stopHotspot()
        }

        // 6. Invoke System startLocalOnlyHotspot
        try {
            Log.d(TAG, "[HOTSPOT] Calling wifiManager.startLocalOnlyHotspot()...")
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(reservation)
                    Log.d(TAG, "[HOTSPOT] LocalOnlyHotspotCallback.onStarted() received from system")

                    if (reservation == null) {
                        Log.e(TAG, "[HOTSPOT] LocalOnlyHotspot reservation returned null by system")
                        activeHotspotSsid = null
                        activeHotspotPassword = null
                        activeHotspotIp = null
                        Handler(Looper.getMainLooper()).post {
                            listener.onHotspotFailed("System returned a null LocalOnlyHotspot reservation")
                        }
                        return
                    }

                    localOnlyHotspotReservation = reservation

                    var realSsid: String? = null
                    var realPassword: String? = null

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val softApConfig = reservation.softApConfiguration
                            if (softApConfig != null) {
                                realSsid = softApConfig.ssid
                                realPassword = softApConfig.passphrase
                                Log.d(TAG, "[HOTSPOT] Extracted SoftApConfiguration (API 30+): SSID=$realSsid")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[HOTSPOT] Failed to extract SoftApConfiguration: ${e.message}")
                        }
                    } else {
                        try {
                            @Suppress("DEPRECATION")
                            val config = reservation.wifiConfiguration
                            if (config != null) {
                                realSsid = config.SSID?.removeSurrounding("\"")
                                realPassword = config.preSharedKey?.removeSurrounding("\"")
                                Log.d(TAG, "[HOTSPOT] Extracted WifiConfiguration (API 26-29): SSID=$realSsid")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[HOTSPOT] Failed to extract WifiConfiguration: ${e.message}")
                        }
                    }

                    if (realSsid.isNullOrBlank() || realPassword.isNullOrBlank()) {
                        Log.e(TAG, "[HOTSPOT] Hotspot reservation created, but real SSID or WPA2 password could not be extracted")
                        stopHotspot()
                        Handler(Looper.getMainLooper()).post {
                            listener.onHotspotFailed("Failed to obtain real SSID and password from LocalOnlyHotspot reservation")
                        }
                        return
                    }

                    val finalSsid = realSsid
                    val finalPassword = realPassword

                    // Poll for assigned hotspot IPv4 address
                    Thread {
                        try {
                            var discoveredIp: String? = null
                            for (attempt in 1..15) {
                                discoveredIp = NetworkUtils.getLocalIpAddress()
                                if (discoveredIp != null && discoveredIp != "127.0.0.1") {
                                    Log.d(TAG, "[HOTSPOT] Assigned IPv4 address discovered on attempt $attempt: $discoveredIp")
                                    break
                                }
                                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                            }

                            if (discoveredIp == null || discoveredIp == "127.0.0.1") {
                                Log.e(TAG, "[HOTSPOT] Failed to discover assigned IPv4 address on active hotspot interface")
                                stopHotspot()
                                Handler(Looper.getMainLooper()).post {
                                    try {
                                        listener.onHotspotFailed("Hotspot started but active IPv4 address could not be determined")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error invoking listener.onHotspotFailed: ${e.message}")
                                    }
                                }
                                return@Thread
                            }

                            val realIp = discoveredIp
                            activeHotspotSsid = finalSsid
                            activeHotspotPassword = finalPassword
                            activeHotspotIp = realIp
                            Log.d(TAG, "[HOTSPOT] Native local-only hotspot active: SSID=$finalSsid IP=$realIp")

                            Handler(Looper.getMainLooper()).post {
                                try {
                                    listener.onHotspotStarted(finalSsid, finalPassword, realIp)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error invoking listener.onHotspotStarted: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Uncaught exception in hotspot IP polling thread: ${e.message}", e)
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    listener.onHotspotFailed("Hotspot polling thread error: ${e.message}")
                                } catch (_: Exception) {}
                            }
                        }
                    }.start()
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    val decodedReason = decodeFailureReason(reason)
                    Log.e(TAG, "[HOTSPOT] LocalOnlyHotspotCallback.onFailed() received: $decodedReason")
                    activeHotspotSsid = null
                    activeHotspotPassword = null
                    activeHotspotIp = null
                    Handler(Looper.getMainLooper()).post {
                        try {
                            listener.onHotspotFailed("LocalOnlyHotspot creation failed: $decodedReason")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in onFailed listener callback: ${e.message}")
                        }
                    }
                }

                override fun onStopped() {
                    super.onStopped()
                    Log.d(TAG, "[HOTSPOT] LocalOnlyHotspotCallback.onStopped() received from system")
                    synchronized(this@WifiHotspotManager) {
                        localOnlyHotspotReservation = null
                    }
                    activeHotspotSsid = null
                    activeHotspotPassword = null
                    activeHotspotIp = null
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            val err = "Failed to start native local-only hotspot: ${e.message}"
            Log.e(TAG, "[HOTSPOT] $err", e)
            activeHotspotSsid = null
            activeHotspotPassword = null
            activeHotspotIp = null
            listener.onHotspotFailed(err)
        }
    }

    fun stopHotspot() {
        synchronized(this) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val res = localOnlyHotspotReservation
                    localOnlyHotspotReservation = null
                    if (res != null) {
                        Log.d(TAG, "[HOTSPOT] Closing LocalOnlyHotspotReservation...")
                        res.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[HOTSPOT] Error closing native hotspot reservation: ${e.message}")
                }
            }
            activeHotspotSsid = null
            activeHotspotPassword = null
            activeHotspotIp = null
        }
    }

    @Volatile private var currentBoundNetwork: Network? = null

    fun getBoundNetwork(): Network? = currentBoundNetwork

    @SuppressLint("MissingPermission")
    fun joinWifi(ssid: String, psw: String, targetIp: String, listener: WifiJoinListener) {
        Log.d(TAG, "[WIFI] Attempting to join Wi-Fi SSID=$ssid")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Ensure previous network callback is unregistered before new request
                disconnectWifi()

                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(psw)
                    .build()

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .setNetworkSpecifier(specifier)
                    .build()

                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.d(TAG, "[WIFI] Programmatic Wi-Fi network available: $network")
                        currentBoundNetwork = network
                        try {
                            connectivityManager.bindProcessToNetwork(network)
                            Log.d(TAG, "[WIFI] Bound process to local Wi-Fi network successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "[WIFI] Failed to bind process to network: ${e.message}")
                        }
                        listener.onJoined(targetIp)
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.d(TAG, "[WIFI] Programmatic Wi-Fi network lost: $network")
                        if (currentBoundNetwork == network) {
                            currentBoundNetwork = null
                            connectivityManager.bindProcessToNetwork(null)
                        }
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        Log.e(TAG, "[WIFI] Programmatic Wi-Fi network unavailable / timed out")
                        currentBoundNetwork = null
                        listener.onFailed("Wi-Fi connection request unavailable or timed out")
                    }
                }

                connectivityManager.requestNetwork(request, networkCallback!!)
            } catch (e: Exception) {
                Log.e(TAG, "[WIFI] Failed programmatic Wi-Fi join request: ${e.message}")
                listener.onJoined(targetIp)
            }
        } else {
            listener.onJoined(targetIp)
        }
    }

    fun disconnectWifi() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                networkCallback?.let {
                    connectivityManager.unregisterNetworkCallback(it)
                }
                connectivityManager.bindProcessToNetwork(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting Wi-Fi: ${e.message}")
        } finally {
            networkCallback = null
            currentBoundNetwork = null
        }
    }
}

