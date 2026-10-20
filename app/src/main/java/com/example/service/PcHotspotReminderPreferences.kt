package com.example.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferences manager for the "Connect to PC" hotspot connection reminder prompt.
 * Remembers if the user checked "Don't show again", and provides a toggle in Settings
 * to bring it back.
 */
object PcHotspotReminderPreferences {
    private const val PREFS_NAME = "flip_prefs"
    private const val KEY_SHOW_REMINDER = "show_pc_hotspot_reminder"

    private val _reminderFlow = MutableStateFlow(true)
    val reminderFlow: StateFlow<Boolean> = _reminderFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shouldShow = prefs.getBoolean(KEY_SHOW_REMINDER, true)
        _reminderFlow.value = shouldShow
        initialized = true
    }

    @Synchronized
    fun shouldShowReminder(context: Context): Boolean {
        if (!initialized) {
            init(context)
        }
        return _reminderFlow.value
    }

    @Synchronized
    fun setShowReminder(context: Context, show: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SHOW_REMINDER, show).apply()
        _reminderFlow.value = show
        initialized = true
    }
}
