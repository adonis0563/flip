package com.example.service

import android.content.Context
import com.example.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveSession(
    val isConnected: Boolean = false,
    val remoteDevice: Device? = null,
    val role: String? = null // "SENDER" or "RECEIVER"
) {
    val peerName: String get() = remoteDevice?.name ?: "Remote Peer"
    val shortCode: String get() = remoteDevice?.id?.take(4) ?: "peer"
}

/**
 * App-scoped Single Source of Truth for active connection state.
 * Owned and updated by the Foreground Service. ViewModels and Activities only observe.
 */
object ConnectionSessionManager {
    private val _session = MutableStateFlow(ActiveSession())
    val session: StateFlow<ActiveSession> = _session.asStateFlow()

    private var _autoJoinManager: AutoJoinManager? = null

    fun getAutoJoinManager(context: Context): AutoJoinManager {
        return _autoJoinManager ?: synchronized(this) {
            _autoJoinManager ?: AutoJoinManager(context.applicationContext).also {
                _autoJoinManager = it
            }
        }
    }

    fun updateConnected(remoteDevice: Device, role: String? = null) {
        _session.value = ActiveSession(
            isConnected = true,
            remoteDevice = remoteDevice,
            role = role
        )
    }

    fun updateDisconnected() {
        _session.value = ActiveSession(
            isConnected = false,
            remoteDevice = null,
            role = null
        )
        _autoJoinManager?.reset()
    }

    val isConnected: Boolean get() = _session.value.isConnected
    val remoteDevice: Device? get() = _session.value.remoteDevice
    val currentSession: ActiveSession get() = _session.value
}
