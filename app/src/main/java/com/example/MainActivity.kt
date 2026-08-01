package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.ui.FlipAppContent
import com.example.ui.theme.MyApplicationTheme
import android.annotation.SuppressLint
import com.example.viewmodel.FlipViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@SuppressLint("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {

    private val viewModel: FlipViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.forEach { (permission, isGranted) ->
            Log.d("MainActivity", "Permission $permission isGranted=$isGranted")
            if (!isGranted) {
                // Location is optional for BLE on Android 12+, but mandatory on 11 and below.
                // We shouldn't block the app if some optional permissions fail, but let's notify the user if essential ones are denied.
                if (permission == Manifest.permission.CAMERA || permission == Manifest.permission.READ_EXTERNAL_STORAGE) {
                    allGranted = false
                }
            }
        }
        if (!allGranted) {
            Log.w("MainActivity", "Some critical permissions were denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initialize Theme settings
        com.example.ui.theme.ThemeSettings.initialize(this)
        
        // Initialize storage directory safely
        com.example.service.StorageService.appFilesDir = getExternalFilesDir(null)

        // Gather all required permission parameters based on target Android platform version
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )

        // Storage & Wi-Fi & Notification permissions branching by Android API version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        // Bluetooth permissions branching for Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Filter out already-granted permissions
        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            requestPermissionLauncher.launch(ungranted.toTypedArray())
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            val context = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(darkTheme) {
                val activity = context as? androidx.activity.ComponentActivity
                activity?.enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (darkTheme) {
                        androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                )
            }

            MyApplicationTheme(darkTheme = darkTheme) {
                FlipAppContent(viewModel = viewModel)
            }
        }

        // Handle intent if files were shared to the app
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent == null) return
        try {
            Log.d("MainActivity", "Incoming intent.data: ${intent.data}")
            val action = intent.action
            val type = intent.type
            val data = intent.data

            if (android.content.Intent.ACTION_VIEW == action && data != null) {
                val uriString = data.toString()
                Log.d("MainActivity", "Received ACTION_VIEW with intent.data=$data")
                if (uriString.startsWith("flip://") || data.scheme == "flip") {
                    Log.d("MainActivity", "Processing flip:// deep link URI: $uriString")
                    viewModel.processScannedQr(uriString)
                }
            } else if (android.content.Intent.ACTION_SEND == action && type != null) {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
                }
                streamUri?.let { uri ->
                    viewModel.importSharedFiles(listOf(uri))
                }
            } else if (android.content.Intent.ACTION_SEND_MULTIPLE == action && type != null) {
                val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM)
                }
                streamUris?.let { uris ->
                    viewModel.importSharedFiles(uris.filterNotNull())
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error handling incoming intent: ${e.message}", e)
        }
    }
}
