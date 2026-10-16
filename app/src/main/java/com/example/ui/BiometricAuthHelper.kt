package com.example.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Diagnostic and execution helper for Biometric & Device Credential authentication gate.
 */
object BiometricAuthHelper {
    private const val TAG = "BiometricAuthHelper"

    const val TITLE = "Confirm it's you"
    const val SUBTITLE = "Unlock to show your hotspot details"

    enum class SecurityStatus {
        READY_TO_AUTHENTICATE,
        NO_SECURITY_CONFIGURED
    }

    /**
     * Determines allowable authenticators strictly adhering to Android platform constraints:
     * - Android 9–10 (API 28–29): BIOMETRIC_WEAK | DEVICE_CREDENTIAL
     * - Android 11+ (API 30+): BIOMETRIC_STRONG | DEVICE_CREDENTIAL
     */
    fun getAllowedAuthenticators(): Int {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
    }

    /**
     * Checks if the device has a secure lock (PIN/pattern/password) or enrolled biometrics.
     * If neither exists, returns NO_SECURITY_CONFIGURED so user is guided to settings.
     */
    fun checkSecurityStatus(context: Context): SecurityStatus {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isDeviceSecure = keyguardManager?.isDeviceSecure == true
        if (!isDeviceSecure) {
            Log.d(TAG, "[BIOMETRIC] Device is not secure (no PIN/pattern/password lock configured)")
            return SecurityStatus.NO_SECURITY_CONFIGURED
        }

        val biometricManager = BiometricManager.from(context)
        val authenticators = getAllowedAuthenticators()
        val canAuth = biometricManager.canAuthenticate(authenticators)

        Log.d(TAG, "[BIOMETRIC] Check status: isDeviceSecure=$isDeviceSecure, canAuth=$canAuth, api=${Build.VERSION.SDK_INT}")

        return if (canAuth == BiometricManager.BIOMETRIC_SUCCESS || isDeviceSecure) {
            SecurityStatus.READY_TO_AUTHENTICATE
        } else {
            SecurityStatus.NO_SECURITY_CONFIGURED
        }
    }

    /**
     * Opens system security settings for the user to configure a screen lock.
     */
    fun openSecuritySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "[BIOMETRIC] Failed to open security settings: ${e.message}")
            try {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "[BIOMETRIC] Failed to open fallback settings: ${e2.message}")
            }
        }
    }

    /**
     * Launches the system BiometricPrompt with DEVICE_CREDENTIAL fallback.
     */
    fun launchBiometricPrompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: CharSequence) -> Unit,
        onCancel: (errString: CharSequence) -> Unit
    ) {
        val authenticators = getAllowedAuthenticators()
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(TITLE)
            .setSubtitle(SUBTITLE)
            .setAllowedAuthenticators(authenticators)
            .build()

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Log.d(TAG, "[BIOMETRIC] Authentication succeeded (type: ${result.authenticationType})")
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.w(TAG, "[BIOMETRIC] Authentication error code=$errorCode, msg=$errString")
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> {
                            onCancel(errString)
                        }
                        else -> {
                            // System lockout, timeout, or failure
                            onError(errorCode, errString)
                        }
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.d(TAG, "[BIOMETRIC] Authentication failed (retry allowed by prompt dialog)")
                }
            }
        )

        Log.d(TAG, "[BIOMETRIC] Launching prompt with authenticators=$authenticators")
        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Resolves FragmentActivity from Compose LocalContext hierarchy.
     */
    fun findFragmentActivity(context: Context): FragmentActivity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is FragmentActivity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
