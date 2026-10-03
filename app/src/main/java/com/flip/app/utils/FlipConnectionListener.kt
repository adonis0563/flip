package com.flip.app.utils

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup

interface FlipConnectionListener {

    // Wi-Fi Direct State
    fun onWifiDirectEnabled(enabled: Boolean) {}

    // Discovery
    fun onPeersDiscovered(peers: List<WifiP2pDevice>) {}

    // Connection
    fun onConnectionSuccess(isGroupOwner: Boolean, groupOwnerAddress: String?) {}
    fun onConnectionFailed(reason: String) {}
    fun onGroupInfoChanged(group: WifiP2pGroup?) {}

    // Transfer
    fun onTransferProgress(bytesTransferred: Long, totalBytes: Long) {}
    fun onTransferCompleted(success: Boolean, filePath: String?) {}
}
