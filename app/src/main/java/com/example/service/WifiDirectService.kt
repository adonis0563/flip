package com.example.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WifiDirectService(
    private val context: Context,
    private val listener: DirectListener
) {
    private val manager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = manager.initialize(context, context.mainLooper, null)
    private val receiver = DirectBroadcastReceiver()
    private var isRegistered = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var localHttpPort: Int = 8080

    interface DirectListener {
        fun onPeerDiscovered(device: WifiP2pDevice)
        fun onConnectionEstablished(groupOwnerIp: String, isGroupOwner: Boolean) {
            onConnectionEstablished(groupOwnerIp)
        }
        fun onConnectionEstablished(groupOwnerIp: String) {}
        fun onDisconnected()
        fun onDiscoveryFailed(message: String) // ✅ NEW: Permission/failure feedback
        fun onNonGoDeviceFound(nonGoIp: String, httpPort: Int) {} // ✅ NEW
    }

    fun register() {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        
        // ✅ FIX: Use ContextCompat to safely apply RECEIVER_NOT_EXPORTED on API 33+
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        isRegistered = true
    }

    fun unregister() {
        if (!isRegistered) return
        context.unregisterReceiver(receiver)
        isRegistered = false
        serviceScope.coroutineContext.cancelChildren()
    }

    fun discoverPeers(): Boolean {
        // ✅ UX FIX: Check permissions before attempting discovery
        val hasPermission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            }
            else -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }
        
        if (!hasPermission) {
            Log.w("WifiDirect", "Cannot discover peers: Missing required permissions")
            listener.onDiscoveryFailed("Missing Wi-Fi Direct permissions. Please grant location/Wi-Fi permissions in Settings.")
            return false
        }
        
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d("WifiDirect", "Discovery started") }
            override fun onFailure(reason: Int) { 
                Log.e("WifiDirect", "Discover failed: $reason")
                listener.onDiscoveryFailed("Wi-Fi Direct discovery failed (error: $reason). Ensure Wi-Fi is enabled.")
            }
        })
        return true
    }

    fun connectToDevice(targetDevice: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = targetDevice.deviceAddress
            wps.setup = WpsInfo.PBC  // ✅ Correct API
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d("WifiDirect", "Connecting to ${targetDevice.deviceAddress}") }
            override fun onFailure(reason: Int) { Log.e("WifiDirect", "Connect failed: $reason") }
        })
    }

    inner class DirectBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel) { peers ->
                        peers.deviceList.forEach { listener.onPeerDiscovered(it) }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager.requestConnectionInfo(channel) { info ->
                        if (info.groupFormed) {
                            val goIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                            val isGo = info.isGroupOwner
                            listener.onConnectionEstablished(goIp, isGo)

                            // ✅ If I'm the non-GO, announce my IP to the GO via UDP
                            if (!isGo) {
                                announceToGroupOwner(goIp)
                            } else {
                                // ✅ If I'm the GO, listen for the non-GO's announcement
                                listenForNonGoAnnouncement()
                            }
                        } else {
                            listener.onDisconnected()
                        }
                    }
                }
            }
        }
    }

    private fun announceToGroupOwner(goIp: String) {
        serviceScope.launch {
            var socket: DatagramSocket? = null
            try {
                val myIp = getMyWifiDirectIp() ?: run {
                    Log.w("WifiDirect", "Cannot announce to GO: unable to determine Wi-Fi Direct IP")
                    return@launch
                }
                val httpPort = localHttpPort
                val message = "$myIp:$httpPort"
                socket = DatagramSocket()
                val bytes = message.toByteArray(Charsets.UTF_8)
                val targetAddr = InetAddress.getByName(goIp)
                val packet = DatagramPacket(
                    bytes, bytes.size,
                    targetAddr,
                    54321 // Fixed UDP port for announcements
                )
                for (i in 1..3) {
                    socket.send(packet)
                    Log.d("WifiDirect", "Announced to GO ($goIp:54321, attempt $i): $message")
                    delay(300)
                }
            } catch (e: Exception) {
                Log.e("WifiDirect", "Failed to announce to GO: ${e.message}")
            } finally {
                try {
                    socket?.close()
                } catch (ex: Exception) {}
            }
        }
    }

    private fun listenForNonGoAnnouncement() {
        serviceScope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(54321).apply {
                    reuseAddress = true
                    soTimeout = 5000 // Wait for up to 5 seconds for the announcement
                }
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                socket.receive(packet)

                val message = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                val parts = message.split(":")
                if (parts.size == 2) {
                    val nonGoIp = parts[0]
                    val httpPort = parts[1].toIntOrNull() ?: return@launch
                    listener.onNonGoDeviceFound(nonGoIp, httpPort)
                    Log.d("WifiDirect", "Found non-GO device: $nonGoIp:$httpPort")
                }
            } catch (e: Exception) {
                Log.e("WifiDirect", "Failed to listen for non-GO: ${e.message}")
            } finally {
                try {
                    socket?.close()
                } catch (ex: Exception) {}
            }
        }
    }

    private fun getMyWifiDirectIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (iface.name.startsWith("p2p") || iface.displayName.contains("Wi-Fi Direct", ignoreCase = true)) {
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            }
            // Fallback: search any active interface for 192.168.49.x address
            val allInterfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in allInterfaces) {
                if (iface.isUp && !iface.isLoopback) {
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (host.startsWith("192.168.49.")) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WifiDirect", "Failed to get Wi-Fi Direct IP: ${e.message}")
        }
        return null
    }
    
    private fun WifiP2pManager.requestPeers(channel: WifiP2pManager.Channel, callback: (WifiP2pDeviceList) -> Unit) {
        this.requestPeers(channel, object : WifiP2pManager.PeerListListener {
            override fun onPeersAvailable(peers: WifiP2pDeviceList) { callback(peers) }
        })
    }
    
    private fun WifiP2pManager.requestConnectionInfo(channel: WifiP2pManager.Channel, callback: (WifiP2pInfo) -> Unit) {
        this.requestConnectionInfo(channel, object : WifiP2pManager.ConnectionInfoListener {
            override fun onConnectionInfoAvailable(info: WifiP2pInfo) { callback(info) }
        })
    }
}
