package com.example

import android.os.Build
import androidx.biometric.BiometricManager
import com.example.ui.BiometricAuthHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BiometricAuthHelperTest {

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q]) // Android 10
    fun testAuthenticatorsOnAndroid10() {
        val authenticators = BiometricAuthHelper.getAllowedAuthenticators()
        val expected = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        assertEquals(
            "Android 9-10 must use BIOMETRIC_WEAK | DEVICE_CREDENTIAL",
            expected,
            authenticators
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R]) // Android 11
    fun testAuthenticatorsOnAndroid11() {
        val authenticators = BiometricAuthHelper.getAllowedAuthenticators()
        val expected = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        assertEquals(
            "Android 11+ must use BIOMETRIC_STRONG | DEVICE_CREDENTIAL",
            expected,
            authenticators
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE]) // Android 14
    fun testAuthenticatorsOnAndroid14() {
        val authenticators = BiometricAuthHelper.getAllowedAuthenticators()
        val expected = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        assertEquals(
            "Android 14 must use BIOMETRIC_STRONG | DEVICE_CREDENTIAL",
            expected,
            authenticators
        )
    }

    @Test
    fun testPromptStrings() {
        assertEquals("Confirm it's you", BiometricAuthHelper.TITLE)
        assertEquals("Unlock to show your hotspot details", BiometricAuthHelper.SUBTITLE)
    }

    @Test
    fun testSecurityStatus_unsecureDeviceReturnsNoSecurityConfigured() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val keyguardManager = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val shadowKeyguard = org.robolectric.Shadows.shadowOf(keyguardManager)
        shadowKeyguard.setIsDeviceSecure(false)

        val status = BiometricAuthHelper.checkSecurityStatus(context)
        assertEquals(BiometricAuthHelper.SecurityStatus.NO_SECURITY_CONFIGURED, status)
    }

    @Test
    fun testSecurityStatus_secureDeviceReturnsReadyToAuthenticate() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val keyguardManager = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val shadowKeyguard = org.robolectric.Shadows.shadowOf(keyguardManager)
        shadowKeyguard.setIsDeviceSecure(true)

        val status = BiometricAuthHelper.checkSecurityStatus(context)
        assertEquals(BiometricAuthHelper.SecurityStatus.READY_TO_AUTHENTICATE, status)
    }
}
