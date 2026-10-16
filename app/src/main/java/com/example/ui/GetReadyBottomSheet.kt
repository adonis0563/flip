package com.example.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.AccentRed
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/**
 * Operating mode determining which connection rows are presented.
 */
enum class ConnectionReadinessMode {
    SEND,
    RECEIVE
}

/**
 * Hotspot status representing real-device detection state.
 */
enum class HotspotDetectionState {
    ON,
    OFF,
    UNKNOWN
}

/**
 * Checks whether Bluetooth is currently active and enabled.
 */
fun isBluetoothEnabled(context: Context): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBtConnect = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBtConnect) return false
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bm?.adapter?.isEnabled == true
    } catch (e: Exception) {
        false
    }
}

/**
 * Checks whether Wi-Fi is currently enabled.
 */
fun isWifiEnabled(context: Context): Boolean {
    return try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.isWifiEnabled == true
    } catch (e: Exception) {
        false
    }
}

/**
 * Checks whether system location service is active.
 */
fun isSystemLocationActive(context: Context): Boolean {
    return try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Checks whether Location is enabled (both runtime permission and system toggle).
 */
fun isLocationFullyReady(context: Context): Boolean {
    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return false
    return isSystemLocationActive(context)
}

/**
 * Inspects network interfaces for an active AP/tethering interface with an IPv4 address.
 */
private fun isTetheringInterfaceActive(): Boolean {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            val name = intf.name.lowercase(Locale.ROOT)
            val isApCandidate = name.startsWith("ap") ||
                    name.startsWith("wlan1") ||
                    name.startsWith("swlan") ||
                    name.startsWith("softap") ||
                    name.startsWith("tether") ||
                    name.startsWith("wigig0") ||
                    name.startsWith("rndis") ||
                    name.contains("ap0")
            if (isApCandidate) {
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address && !addr.isLinkLocalAddress) {
                        return true
                    }
                }
            }
        }
        false
    } catch (e: Throwable) {
        false
    }
}

/**
 * Attempts to inspect hidden getWifiApState reflection.
 */
private fun getWifiApStateReflection(context: Context): Int? {
    return try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val method = wm.javaClass.getDeclaredMethod("getWifiApState")
        method.isAccessible = true
        method.invoke(wm) as? Int
    } catch (e: Throwable) {
        null
    }
}

/**
 * Determines current Hotspot state combining active interfaces, reflection, and broadcast.
 */
fun checkHotspotState(context: Context, broadcastWifiApState: Int = -1): HotspotDetectionState {
    if (isTetheringInterfaceActive()) {
        return HotspotDetectionState.ON
    }
    if (broadcastWifiApState == 13) {
        return HotspotDetectionState.ON
    }
    if (broadcastWifiApState == 11 || broadcastWifiApState == 10) {
        return HotspotDetectionState.OFF
    }
    val reflection = getWifiApStateReflection(context)
    if (reflection != null) {
        return when (reflection) {
            13 -> HotspotDetectionState.ON
            10, 11 -> HotspotDetectionState.OFF
            else -> HotspotDetectionState.UNKNOWN
        }
    }
    return HotspotDetectionState.UNKNOWN
}

/**
 * Checks whether all required connections for the given mode are ready.
 */
fun areAllConnectionsReady(context: Context, mode: ConnectionReadinessMode): Boolean {
    val bt = isBluetoothEnabled(context)
    val loc = isLocationFullyReady(context)
    return when (mode) {
        ConnectionReadinessMode.SEND -> {
            val hotspot = checkHotspotState(context)
            (hotspot == HotspotDetectionState.ON) && bt && loc
        }
        ConnectionReadinessMode.RECEIVE -> {
            val wifi = isWifiEnabled(context)
            wifi && bt && loc
        }
    }
}

/**
 * Helper to safely open system tethering/hotspot settings.
 */
fun openHotspotSettings(context: Context) {
    try {
        val tetherIntent = Intent().apply {
            component = ComponentName("com.android.settings", "com.android.settings.TetherSettings")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(tetherIntent)
    } catch (e: Exception) {
        try {
            val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e2: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e3: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (ignored: Exception) {}
            }
        }
    }
}

/**
 * Helper to turn on Wi-Fi or open system Wi-Fi panel/settings.
 */
fun enableOrOpenWifi(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(panelIntent)
        } catch (e: Exception) {
            openWifiSettings(context)
        }
    } else {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val success = wm?.setWifiEnabled(true) ?: false
            if (!success) {
                openWifiSettings(context)
            }
        } catch (e: Exception) {
            openWifiSettings(context)
        }
    }
}

/**
 * Helper to open Wi-Fi settings.
 */
fun openWifiSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (ignored: Exception) {}
    }
}

/**
 * Helper to open system location settings.
 */
fun openLocationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (ignored: Exception) {}
    }
}

/**
 * Helper to open application details settings.
 */
fun openAppSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (ignored: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GetReadyBottomSheet(
    mode: ConnectionReadinessMode,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Live states
    var isBluetoothOn by remember { mutableStateOf(isBluetoothEnabled(context)) }
    var isWifiOn by remember { mutableStateOf(isWifiEnabled(context)) }
    var isLocationOn by remember { mutableStateOf(isLocationFullyReady(context)) }
    var broadcastApState by remember { mutableIntStateOf(-1) }
    var hotspotState by remember { mutableStateOf(checkHotspotState(context, broadcastApState)) }
    var hotspotUserConfirmed by remember { mutableStateOf(false) }

    fun refreshAllStates() {
        isBluetoothOn = isBluetoothEnabled(context)
        isWifiOn = isWifiEnabled(context)
        isLocationOn = isLocationFullyReady(context)
        hotspotState = checkHotspotState(context, broadcastApState)
    }

    // Refresh on ON_RESUME
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAllStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Live BroadcastReceivers
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        isBluetoothOn = isBluetoothEnabled(context)
                    }
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        isWifiOn = isWifiEnabled(context)
                    }
                    LocationManager.MODE_CHANGED_ACTION,
                    LocationManager.PROVIDERS_CHANGED_ACTION -> {
                        isLocationOn = isLocationFullyReady(context)
                    }
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                        val state = intent.getIntExtra("wifi_state", -1)
                        broadcastApState = state
                        hotspotState = checkHotspotState(context, state)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            try {
                context.registerReceiver(receiver, filter)
            } catch (ignored: Exception) {}
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (ignored: Exception) {}
        }
    }

    // Activity result launchers for Bluetooth and Location
    val btEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isBluetoothOn = isBluetoothEnabled(context)
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (ignored: Exception) {}
            }
        } else {
            openAppSettings(context)
        }
        isBluetoothOn = isBluetoothEnabled(context)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val hasFine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val hasCoarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasFine || hasCoarse) {
            if (!isSystemLocationActive(context)) {
                openLocationSettings(context)
            }
        } else {
            openAppSettings(context)
        }
        isLocationOn = isLocationFullyReady(context)
    }

    // Completion condition
    val isHotspotSatisfied = (hotspotState == HotspotDetectionState.ON) ||
            (hotspotState == HotspotDetectionState.UNKNOWN) ||
            hotspotUserConfirmed

    val allReady = when (mode) {
        ConnectionReadinessMode.SEND -> isHotspotSatisfied && isBluetoothOn && isLocationOn
        ConnectionReadinessMode.RECEIVE -> isWifiOn && isBluetoothOn && isLocationOn
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentWindowInsets = { WindowInsets.navigationBars }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Get ready",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Turn these on to connect.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (mode == ConnectionReadinessMode.SEND) {
                    // Hotspot row
                    val hotspotPillLabel = when {
                        hotspotState == HotspotDetectionState.ON -> "On"
                        hotspotUserConfirmed -> "I've turned it on"
                        hotspotState == HotspotDetectionState.UNKNOWN -> "Unknown"
                        else -> "Off"
                    }
                    val isHotspotActive = hotspotState == HotspotDetectionState.ON || hotspotUserConfirmed

                    StatusRow(
                        icon = Icons.Default.WifiTethering,
                        label = "Hotspot",
                        pillLabel = hotspotPillLabel,
                        isOn = isHotspotActive,
                        testTagPrefix = "hotspot",
                        onClick = {
                            if (hotspotState == HotspotDetectionState.UNKNOWN) {
                                hotspotUserConfirmed = true
                            }
                            openHotspotSettings(context)
                        }
                    )

                    // Bluetooth row
                    StatusRow(
                        icon = Icons.Default.Bluetooth,
                        label = "Bluetooth",
                        pillLabel = if (isBluetoothOn) "On" else "Off",
                        isOn = isBluetoothOn,
                        testTagPrefix = "bluetooth",
                        onClick = {
                            if (isBluetoothOn) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                } catch (ignored: Exception) {}
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val hasBtConnect = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!hasBtConnect) {
                                        btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                    } else {
                                        try {
                                            btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                        } catch (e: Exception) {
                                            try {
                                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                            } catch (ignored: Exception) {}
                                        }
                                    }
                                } else {
                                    try {
                                        btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                    } catch (e: Exception) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                        } catch (ignored: Exception) {}
                                    }
                                }
                            }
                        }
                    )

                    // Location row
                    StatusRow(
                        icon = Icons.Default.LocationOn,
                        label = "Location",
                        pillLabel = if (isLocationOn) "On" else "Off",
                        isOn = isLocationOn,
                        testTagPrefix = "location",
                        onClick = {
                            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (!hasFine && !hasCoarse) {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            } else if (!isSystemLocationActive(context)) {
                                openLocationSettings(context)
                            } else {
                                openLocationSettings(context)
                            }
                        }
                    )
                } else {
                    // Receive mode: Wi-Fi, Bluetooth, Location
                    // Wi-Fi row
                    StatusRow(
                        icon = Icons.Default.Wifi,
                        label = "Wi-Fi",
                        pillLabel = if (isWifiOn) "On" else "Off",
                        isOn = isWifiOn,
                        testTagPrefix = "wifi",
                        onClick = {
                            if (isWifiOn) {
                                openWifiSettings(context)
                            } else {
                                enableOrOpenWifi(context)
                            }
                        }
                    )

                    // Bluetooth row
                    StatusRow(
                        icon = Icons.Default.Bluetooth,
                        label = "Bluetooth",
                        pillLabel = if (isBluetoothOn) "On" else "Off",
                        isOn = isBluetoothOn,
                        testTagPrefix = "bluetooth",
                        onClick = {
                            if (isBluetoothOn) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                } catch (ignored: Exception) {}
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val hasBtConnect = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!hasBtConnect) {
                                        btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                    } else {
                                        try {
                                            btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                        } catch (e: Exception) {
                                            try {
                                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                            } catch (ignored: Exception) {}
                                        }
                                    }
                                } else {
                                    try {
                                        btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                    } catch (e: Exception) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                        } catch (ignored: Exception) {}
                                    }
                                }
                            }
                        }
                    )

                    // Location row
                    StatusRow(
                        icon = Icons.Default.LocationOn,
                        label = "Location",
                        pillLabel = if (isLocationOn) "On" else "Off",
                        isOn = isLocationOn,
                        testTagPrefix = "location",
                        onClick = {
                            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (!hasFine && !hasCoarse) {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            } else if (!isSystemLocationActive(context)) {
                                openLocationSettings(context)
                            } else {
                                openLocationSettings(context)
                            }
                        }
                    )
                }
            }

            // Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            sheetState.hide()
                            onContinue()
                        }
                    },
                    enabled = allReady,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("get_ready_continue_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(26.dp)
                ) {
                    Text(
                        text = "Continue",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("get_ready_cancel_button"),
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                ) {
                    Text(
                        text = "Cancel",
                        color = AccentRed,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    label: String,
    pillLabel: String,
    isOn: Boolean,
    testTagPrefix: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable { onClick() }
            .testTag("row_$testTagPrefix"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        // Status pill button with short crossfade animation
        AnimatedContent(
            targetState = Pair(pillLabel, isOn),
            transitionSpec = {
                fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
            },
            label = "pill_${testTagPrefix}_anim"
        ) { (text, active) ->
            if (active) {
                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier
                        .height(36.dp)
                        .testTag("pill_$testTagPrefix")
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onClick,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier
                        .height(36.dp)
                        .testTag("pill_$testTagPrefix")
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
                    )
                }
            }
        }
    }
}
