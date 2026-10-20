package com.example.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiDirectHostTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testStop_isSafeToCallMultipleTimes() {
        val host = WifiDirectHost(context)
        // Should not throw or crash when called multiple times without start
        host.stop()
        host.stop()
        host.stop()
    }

    @Test
    fun testStart_returnsUnsupportedBelowAndroid10() = runBlocking {
        val host = WifiDirectHost(context)
        val result = host.start(sdkInt = 28)
        assertTrue("Must return Unsupported below Android 10", result is HostResult.Unsupported)
        assertFalse(WifiDirectHost.hasRequiredPermissions(context, sdkInt = 28))
        host.stop()
    }

    @Test
    fun testPermissions_permissionMissingReturnedWhenNotGranted() = runBlocking {
        val host = WifiDirectHost(context)
        val result = host.start(sdkInt = 34)
        // Without granting NEARBY_WIFI_DEVICES, must return PermissionMissing instead of crashing
        assertTrue("Must return PermissionMissing when required permissions are not granted", result is HostResult.PermissionMissing)
        host.stop()
    }
}
