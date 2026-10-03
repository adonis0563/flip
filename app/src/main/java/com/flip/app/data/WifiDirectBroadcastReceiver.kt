package com.flip.app.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.flip.app.utils.FlipConnectionListener

@SuppressLint("MissingPermission")
class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val listener: FlipConnectionListener
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "Flip_Receiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val isEnabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                Log.d(TAG, "Wi-Fi P2P State: ${if (isEnabled) "Enabled" else "Disabled"}")
                listener.onWifiDirectEnabled(isEnabled)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                // Request peers immediately and pass to listener
                manager.requestPeers(channel) { peers: WifiP2pDeviceList? ->
                    if (peers != null) {
                        val deviceList = peers.deviceList.toList()
                        Log.d(TAG, "Peers found: ${deviceList.size}")
                        listener.onPeersDiscovered(deviceList)
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val wifiP2pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                }

                val groupInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                }

                if (wifiP2pInfo?.groupFormed == true) {
                    val isGO = wifiP2pInfo.isGroupOwner
                    val goAddress = wifiP2pInfo.groupOwnerAddress?.hostAddress
                    Log.d(TAG, "✅ Connected! Is GO: $isGO, GO IP: $goAddress")
                    listener.onConnectionSuccess(isGO, goAddress)
                } else {
                    Log.d(TAG, "Connection lost or group dissolved.")
                    listener.onConnectionFailed("Connection lost")
                }

                listener.onGroupInfoChanged(groupInfo)
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                // Optional: Handle local device name changes
            }
        }
    }
}
