package com.example.service

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            
            // ✅ FIX: Prioritize common local Wi-Fi and Wi-Fi Direct interface names
            val preferredInterfaces = listOf("wlan0", "p2p0", "swlan0", "wlan1", "eth0")
            
            for (networkInterface in interfaces) {
                if (networkInterface.isUp && !networkInterface.isLoopback && networkInterface.name in preferredInterfaces) {
                    val addresses = Collections.list(networkInterface.inetAddresses)
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            val ip = address.hostAddress
                            if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                                return ip
                            }
                        }
                    }
                }
            }

            for (networkInterface in interfaces) {
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val addresses = Collections.list(networkInterface.inetAddresses)
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            val ip = address.hostAddress
                            if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                                return ip
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }
}
