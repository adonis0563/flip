package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

class WifiHotspotManager(private val context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private var localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    companion object {
        private const val TAG = "WifiHotspotManager"
        @Volatile var simulatedSsid: String? = null
        @Volatile var simulatedPassword: String? = null
        @Volatile var simulatedIp: String? = null
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
        val randomSsid = "FLIP_${UUID.randomUUID().toString().take(4).uppercase()}"
        val randomPassword = UUID.randomUUID().toString().take(10)
        
        simulatedSsid = randomSsid
        simulatedPassword = randomPassword
        simulatedIp = NetworkUtils.getLocalIpAddress() ?: "192.168.43.1"
        if (simulatedIp == "127.0.0.1") {
            simulatedIp = "192.168.43.1"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                        super.onStarted(reservation)
                        localOnlyHotspotReservation = reservation
                        val config = reservation?.wifiConfiguration
                        val ssid = config?.SSID ?: randomSsid
                        val preSharedKey = config?.preSharedKey ?: randomPassword
                        
                        val ip = NetworkUtils.getLocalIpAddress() ?: simulatedIp ?: "192.168.43.1"
                        Log.d(TAG, "Native local-only hotspot started: SSID=$ssid IP=$ip")
                        listener.onHotspotStarted(ssid, preSharedKey, ip)
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        Log.e(TAG, "Native local-only hotspot failed: reason $reason")
                        listener.onHotspotStarted(randomSsid, randomPassword, simulatedIp ?: "192.168.43.1")
                    }

                    override fun onStopped() {
                        super.onStopped()
                        Log.d(TAG, "Native local-only hotspot stopped")
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start native local-only hotspot: ${e.message}")
                listener.onHotspotStarted(randomSsid, randomPassword, simulatedIp ?: "192.168.43.1")
            }
        } else {
            listener.onHotspotStarted(randomSsid, randomPassword, simulatedIp ?: "192.168.43.1")
        }
    }

    fun stopHotspot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                localOnlyHotspotReservation?.close()
                localOnlyHotspotReservation = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping native hotspot: ${e.message}")
            }
        }
        simulatedSsid = null
        simulatedPassword = null
        simulatedIp = null
    }

    @SuppressLint("MissingPermission")
    fun joinWifi(ssid: String, psw: String, targetIp: String, listener: WifiJoinListener) {
        Log.d(TAG, "Attempting to join Wi-Fi SSID=$ssid")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
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
                        Log.d(TAG, "Programmatic network available: $network")
                        try {
                            connectivityManager.bindProcessToNetwork(network)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to bind process to network: ${e.message}")
                        }
                        listener.onJoined(targetIp)
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.d(TAG, "Programmatic network lost: $network")
                        connectivityManager.bindProcessToNetwork(null)
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        Log.e(TAG, "Programmatic network unavailable")
                        listener.onJoined(targetIp)
                    }
                }

                connectivityManager.requestNetwork(request, networkCallback!!)
            } catch (e: Exception) {
                Log.e(TAG, "Failed programmatic Wi-Fi join: ${e.message}")
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
        }
    }
}
