package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

class WifiDirectService(
    private val context: Context,
    private val listener: DirectListener
) {
    private val manager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = manager.initialize(context, context.mainLooper, null)
    private val receiver = DirectBroadcastReceiver()
    private var isRegistered = false

    interface DirectListener {
        fun onPeerDiscovered(device: WifiP2pDevice)
        fun onConnectionEstablished(groupOwnerIp: String)
        fun onDisconnected()
    }

    fun register() {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        isRegistered = true
    }

    fun unregister() {
        if (!isRegistered) return
        context.unregisterReceiver(receiver)
        isRegistered = false
    }

    fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d("WifiDirect", "Discovery started") }
            override fun onFailure(reason: Int) { Log.e("WifiDirect", "Discover failed: $reason") }
        })
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
                            // ✅ Dynamic IP to support all Android OEMs
                            val goIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                            listener.onConnectionEstablished(goIp)
                        } else {
                            listener.onDisconnected()
                        }
                    }
                }
            }
        }
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
