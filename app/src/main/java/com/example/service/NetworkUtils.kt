package com.example.service

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    fun getLocalIpAddress(): String? {
        val candidates = getAllLocalIpAddresses()
        if (candidates.isEmpty()) {
            Log.w(TAG, "No active non-loopback IPv4 address discovered")
            return null
        }

        // Prioritize known hotspot / SoftAP interfaces (ap0, swlan, softap, wlan)
        val preferred = candidates.find { (ifaceName, _) ->
            ifaceName.contains("ap", ignoreCase = true) ||
            ifaceName.contains("softap", ignoreCase = true) ||
            ifaceName.contains("swlan", ignoreCase = true)
        }
        if (preferred != null) {
            Log.d(TAG, "Discovered hotspot IP ${preferred.second} on interface ${preferred.first}")
            return preferred.second
        }

        // Next prioritize wlan interfaces
        val wlanIface = candidates.find { (ifaceName, _) -> ifaceName.contains("wlan", ignoreCase = true) }
        if (wlanIface != null) {
            Log.d(TAG, "Discovered Wi-Fi IP ${wlanIface.second} on interface ${wlanIface.first}")
            return wlanIface.second
        }

        // Fallback to first non-loopback candidate
        val firstCandidate = candidates.first().second
        Log.d(TAG, "Using discovered IP $firstCandidate")
        return firstCandidate
    }

    fun getAllLocalIpAddresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = Collections.list(iface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddr = address.hostAddress ?: continue
                        if (hostAddr != "127.0.0.1" && !hostAddr.startsWith("169.254.")) {
                            result.add(Pair(iface.name, hostAddr))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing network interfaces: ${e.message}", e)
        }
        return result
    }
}

