package com.example.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PcHotspotReminderPreferencesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        PcHotspotReminderPreferences.init(context)
    }

    @Test
    fun testDefaultState_isTrue() {
        assertTrue("Default reminder state must be true", PcHotspotReminderPreferences.shouldShowReminder(context))
        assertTrue("StateFlow must be true by default", PcHotspotReminderPreferences.reminderFlow.value)
    }

    @Test
    fun testSetShowReminder_updatesStateAndFlow() {
        PcHotspotReminderPreferences.setShowReminder(context, false)
        assertFalse("shouldShowReminder must return false after disabling", PcHotspotReminderPreferences.shouldShowReminder(context))
        assertFalse("reminderFlow must emit false after disabling", PcHotspotReminderPreferences.reminderFlow.value)

        // Re-enable
        PcHotspotReminderPreferences.setShowReminder(context, true)
        assertTrue("shouldShowReminder must return true after enabling", PcHotspotReminderPreferences.shouldShowReminder(context))
        assertTrue("reminderFlow must emit true after enabling", PcHotspotReminderPreferences.reminderFlow.value)
    }
}
