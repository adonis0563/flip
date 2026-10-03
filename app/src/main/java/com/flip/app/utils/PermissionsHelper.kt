package com.flip.app.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionsHelper {
    private const val TAG = "PermissionsHelper"

    /**
     * Returns the list of all required permissions based on device SDK version.
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Wi-Fi Direct permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        // Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        return permissions
    }

    /**
     * Checks if all required permissions are granted.
     */
    fun hasAllPermissions(context: Context): Boolean {
        val required = getRequiredPermissions()
        for (permission in required) {
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.d(TAG, "Permission missing: $permission")
                return false
            }
        }
        return true
    }

    /**
     * Requests any missing required permissions.
     */
    fun requestPermissions(activity: Activity, requestCode: Int) {
        val missing = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $missing")
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), requestCode)
        }
    }
}
