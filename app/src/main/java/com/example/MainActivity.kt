package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ui.FlipAppContent
import com.example.ui.theme.MyApplicationTheme
import android.annotation.SuppressLint
import com.example.viewmodel.FlipViewModel
import com.flip.app.data.ConnectionCoordinator
import com.flip.app.utils.PermissionsHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@SuppressLint("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {

    private val viewModel: FlipViewModel by viewModels()
    lateinit var coordinator: ConnectionCoordinator
    lateinit var tvStatus: TextView
    private val permissionRequestCode = 1001
    lateinit var btnSend: Button
    lateinit var btnReceive: Button
    lateinit var btnDisconnect: Button

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
        
        // Initialize storage directory safely
        com.example.service.StorageService.appFilesDir = getExternalFilesDir(null)

        // Gather all required permission parameters based on target Android platform version
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Storage & Nearby Wi-Fi permissions branching by Android API version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
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

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        tvStatus = TextView(this).apply {
            text = "Flip - Connection PoC\n"
            textSize = 16f
        }

        btnSend = Button(this).apply { text = "Start as SENDER" }
        btnReceive = Button(this).apply { text = "Start as RECEIVER" }
        btnDisconnect = Button(this).apply { text = "Disconnect" }

        layout.addView(tvStatus)
        layout.addView(btnSend)
        layout.addView(btnReceive)
        layout.addView(btnDisconnect)
        scroll.addView(layout)

        coordinator = ConnectionCoordinator(this)
        coordinator.initialize()

        // Observe BLE state
        lifecycleScope.launch {
            coordinator.bleManager.bleState.collectLatest { state ->
                tvStatus.append("\n[BLE] $state")
            }
        }

        // Observe Wi-Fi Direct state
        lifecycleScope.launch {
            coordinator.wifiRepo.connectionStatus.collectLatest { status ->
                tvStatus.append("\n[WiFi] $status")

                // BRIDGE: When group is created on Sender, send credentials over BLE
                if (status.contains("Host (Connected)")) {
                    val (ssid, pass) = coordinator.wifiRepo.getGroupCredentials()
                    if (ssid != null && pass != null) {
                        coordinator.onGroupCreated(ssid, pass)
                    }
                }

                // When Wi-Fi Direct is fully connected
                if (status.contains("Connected")) {
                    coordinator.onWifiDirectConnected(
                        coordinator.wifiRepo.isHost,
                        coordinator.wifiRepo.groupOwnerAddress
                    )
                }
            }
        }

        // Observe overall phase
        lifecycleScope.launch {
            coordinator.connectionPhase.collectLatest { phase ->
                tvStatus.append("\n[PHASE] $phase")
            }
        }

        btnSend.setOnClickListener {
            if (checkPermissions()) {
                coordinator.startAsSender()
            }
        }

        btnReceive.setOnClickListener {
            if (checkPermissions()) {
                coordinator.startAsReceiver()
            }
        }

        btnDisconnect.setOnClickListener {
            coordinator.disconnect()
            tvStatus.append("\n--- Disconnected ---")
        }

        setContent {
            MyApplicationTheme {
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
        val action = intent.action
        val type = intent.type
        
        if (android.content.Intent.ACTION_SEND == action) {
            // ✅ FIX: Handle shared text
            val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                viewModel.sendText(sharedText)
                return
            }
            
            // Handle shared files
            if (type != null) {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
                }
                streamUri?.let { uri ->
                    viewModel.importSharedFiles(listOf(uri))
                }
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
    }

    private fun checkPermissions(): Boolean {
        return if (PermissionsHelper.hasAllPermissions(this)) {
            true
        } else {
            PermissionsHelper.requestPermissions(this, permissionRequestCode)
            false
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator.disconnect()
    }
}
