package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.ui.FlipAppContent
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemeManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.annotation.SuppressLint
import com.example.viewmodel.FlipViewModel

@SuppressLint("InvalidFragmentVersionForActivityResult")
class MainActivity : FragmentActivity() {

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
        val hasStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] == true ||
            permissions[Manifest.permission.READ_MEDIA_VIDEO] == true ||
            permissions[Manifest.permission.READ_MEDIA_AUDIO] == true
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
        if (hasStorage) {
            com.example.repository.FileRepository.preload(this)
        }
        if (!allGranted) {
            Log.w("MainActivity", "Some critical permissions were denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure status bar is always visible with dark icons against light background
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                Color.TRANSPARENT,
                Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                Color.TRANSPARENT,
                Color.TRANSPARENT
            )
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars())
        }
        
        // Initialize storage directory safely
        com.example.service.StorageService.appFilesDir = getExternalFilesDir(null)
        com.example.service.StorageService.appCacheDir = cacheDir

        // Initialize foreground tracking and notification channels
        com.example.service.AppForegroundTracker.init(application)
        com.example.service.TransferNotificationManager.createNotificationChannels(this)

        // Gather all required permission parameters based on target Android platform version
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Storage permissions branching by Android API version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            // Wi-Fi Direct and Notification permissions for Android 13+ (API 33+)
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

        ThemeManager.init(this)
        com.example.service.ConnectedDevicesStore.init(this)
        com.example.service.PcHotspotReminderPreferences.init(this)

        val hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (hasStoragePermission) {
            com.example.repository.FileRepository.preload(this)
        }

        setContent {
            val themeMode by com.example.ui.theme.ThemeManager.themeMode.collectAsState()
            MyApplicationTheme(themeMode = themeMode) {
                FlipAppContent(viewModel = viewModel)
            }
        }

        // Handle intent if files were shared to the app
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        com.example.service.PackageInstallerHelper.checkPendingInstall(this)
        val hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (hasStoragePermission) {
            com.example.repository.FileRepository.refreshSilently(this)
        } else {
            com.example.repository.FileRepository.onPermissionRevoked()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.example.repository.FileRepository.unregisterContentObserver(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("open_explorer", false)) {
            viewModel.triggerOpenExplorer()
        }
        val installPath = intent.getStringExtra("install_file_path")
        if (!installPath.isNullOrEmpty()) {
            val file = java.io.File(installPath)
            if (file.exists()) {
                com.example.service.PackageInstallerHelper.install(this, file)
            }
        }
        val action = intent.action
        val type = intent.type

        if (android.content.Intent.ACTION_SEND == action || android.content.Intent.ACTION_SEND_MULTIPLE == action) {
            val uris = LinkedHashSet<android.net.Uri>()

            // 1. Check ClipData first (modern standard used by Photos and file managers)
            intent.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i)?.uri?.let { uris.add(it) }
                }
            }

            // 2. Check EXTRA_STREAM for single item share
            if (android.content.Intent.ACTION_SEND == action) {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
                }
                streamUri?.let { uris.add(it) }
            }

            // 3. Check EXTRA_STREAM for multiple items share
            if (android.content.Intent.ACTION_SEND_MULTIPLE == action) {
                val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM)
                }
                streamUris?.filterNotNull()?.let { uris.addAll(it) }
            }

            // 4. Check EXTRA_TEXT for text shares
            val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString()

            // 5. Take persistable URI read permission if granted
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Safe to ignore for transient read-permission grants
                }
            }

            if (uris.isNotEmpty() || !sharedText.isNullOrBlank()) {
                viewModel.handleIncomingShare(uris.toList(), sharedText)
            }

            // 6. Clear intent extras so rotation or returning to the app does not re-trigger
            try {
                intent.removeExtra(android.content.Intent.EXTRA_STREAM)
                intent.removeExtra(android.content.Intent.EXTRA_TEXT)
                intent.removeExtra("open_explorer")
                val cleanIntent = android.content.Intent(intent).apply {
                    this.action = android.content.Intent.ACTION_MAIN
                }
                setIntent(cleanIntent)
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to clear share intent extras: ${e.message}")
            }
        }
    }
}
