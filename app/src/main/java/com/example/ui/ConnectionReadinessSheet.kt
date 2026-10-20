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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.AccentRed
import java.net.Inet4Address
import java.net.NetworkInterface

enum class ReadinessMode {
    SEND,
    RECEIVE,
    HOTSPOT_PC
}

enum class ReadinessStatus {
    ON,
    OFF,
    UNKNOWN
}

object ConnectionChecker {

    fun isBluetoothOn(context: Context): Boolean {
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    fun isWifiOn(context: Context): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.isWifiEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    fun isLocationOn(context: Context): Boolean {
        return try {
            val hasPerm = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPerm) return false

            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            LocationManagerCompat.isLocationEnabled(lm)
        } catch (_: Exception) {
            false
        }
    }

    fun isNearbyOrLocationOn(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            isLocationOn(context)
        }
    }

    fun checkHotspot(context: Context, broadcastState: Int?): ReadinessStatus {
        // 1. Check broadcast state if received
        // WIFI_AP_STATE_ENABLED = 13, WIFI_AP_STATE_DISABLED = 11
        if (broadcastState != null && broadcastState != -1) {
            if (broadcastState == 13) return ReadinessStatus.ON
            if (broadcastState == 11) return ReadinessStatus.OFF
        }

        // 2. Reflection on WifiManager.isWifiApEnabled()
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
                method.isAccessible = true
                val isEnabled = method.invoke(wm) as? Boolean
                if (isEnabled == true) return ReadinessStatus.ON
                if (isEnabled == false) return ReadinessStatus.OFF
            }
        } catch (_: Exception) {
            // reflection blocked or method not present on device
        }

        // 3. Active tethering interface check (ap0, wlan1, swlan0, softap0, tether, etc.)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (intf in interfaces) {
                    val name = intf.name.lowercase()
                    val isTether = name.startsWith("ap") ||
                        name.startsWith("wlan1") ||
                        name.startsWith("wlan2") ||
                        name.startsWith("swlan") ||
                        name.startsWith("softap") ||
                        name.startsWith("tether")
                    if (isTether && intf.isUp) {
                        val addrs = intf.inetAddresses
                        for (addr in addrs) {
                            if (!addr.isLoopbackAddress && addr is Inet4Address) {
                                // Active tethering interface with IPv4 detected
                                return ReadinessStatus.ON
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // best-effort
        }

        return ReadinessStatus.UNKNOWN
    }

    fun isReadyForMode(context: Context, mode: ReadinessMode): Boolean {
        val bt = isBluetoothOn(context)
        if (!bt) return false

        return when (mode) {
            ReadinessMode.SEND -> {
                isWifiOn(context) && isNearbyOrLocationOn(context)
            }
            ReadinessMode.RECEIVE -> {
                isWifiOn(context) && isLocationOn(context)
            }
            ReadinessMode.HOTSPOT_PC -> {
                val hotspot = checkHotspot(context, null)
                (hotspot == ReadinessStatus.ON) && isLocationOn(context)
            }
        }
    }

    fun openHotspotSettings(context: Context) {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent("android.settings.WIFI_TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // try next candidate
            }
        }
    }

    fun openWifiSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // fall through to standard Wi-Fi settings
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val success = wm?.setWifiEnabled(true) ?: false
                if (success) return
            } catch (_: Exception) {
                // fall through
            }
        }

        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun openLocationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionReadinessSheet(
    mode: ReadinessMode,
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Live states
    var isBluetoothOn by remember { mutableStateOf(ConnectionChecker.isBluetoothOn(context)) }
    var isWifiOn by remember { mutableStateOf(ConnectionChecker.isWifiOn(context)) }
    var isLocationOn by remember { mutableStateOf(ConnectionChecker.isLocationOn(context)) }
    var isNearbyDevicesOn by remember { mutableStateOf(ConnectionChecker.isNearbyOrLocationOn(context)) }
    var hotspotBroadcastState by remember { mutableIntStateOf(-1) }
    var hotspotStatus by remember { mutableStateOf(ConnectionChecker.checkHotspot(context, null)) }
    var userAcknowledgedHotspot by remember { mutableStateOf(false) }

    fun refreshAllStates() {
        isBluetoothOn = ConnectionChecker.isBluetoothOn(context)
        isWifiOn = ConnectionChecker.isWifiOn(context)
        isLocationOn = ConnectionChecker.isLocationOn(context)
        isNearbyDevicesOn = ConnectionChecker.isNearbyOrLocationOn(context)
        hotspotStatus = ConnectionChecker.checkHotspot(context, if (hotspotBroadcastState != -1) hotspotBroadcastState else null)
    }

    // Refresh initially and on every ON_RESUME
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

    // Register broadcast receivers while sheet is open
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        isBluetoothOn = ConnectionChecker.isBluetoothOn(c)
                    }
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        isWifiOn = ConnectionChecker.isWifiOn(c)
                    }
                    LocationManager.PROVIDERS_CHANGED_ACTION,
                    "android.location.MODE_CHANGED" -> {
                        isLocationOn = ConnectionChecker.isLocationOn(c)
                    }
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                        val state = intent.getIntExtra("wifi_state", -1)
                        hotspotBroadcastState = state
                        hotspotStatus = ConnectionChecker.checkHotspot(c, state)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction("android.location.MODE_CHANGED")
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
        }

        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
            try {
                context.registerReceiver(receiver, filter)
            } catch (_: Exception) {}
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    // Activity Result Launchers
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isBluetoothOn = ConnectionChecker.isBluetoothOn(context)
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
                ConnectionChecker.openAppSettings(context)
            }
        } else {
            ConnectionChecker.openAppSettings(context)
        }
        isBluetoothOn = ConnectionChecker.isBluetoothOn(context)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) {
                ConnectionChecker.openLocationSettings(context)
            }
        } else {
            ConnectionChecker.openAppSettings(context)
        }
        isLocationOn = ConnectionChecker.isLocationOn(context)
    }

    val nearbyDevicesPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        isNearbyDevicesOn = ConnectionChecker.isNearbyOrLocationOn(context)
    }

    // Readiness calculation
    val effectiveHotspotStatus = when {
        hotspotStatus == ReadinessStatus.ON -> ReadinessStatus.ON
        hotspotStatus == ReadinessStatus.OFF -> ReadinessStatus.OFF
        userAcknowledgedHotspot -> ReadinessStatus.ON
        else -> ReadinessStatus.UNKNOWN
    }

    val allReady = when (mode) {
        ReadinessMode.SEND -> {
            isWifiOn && isBluetoothOn && isNearbyDevicesOn
        }
        ReadinessMode.RECEIVE -> {
            isWifiOn && isBluetoothOn && isLocationOn
        }
        ReadinessMode.HOTSPOT_PC -> {
            (effectiveHotspotStatus == ReadinessStatus.ON) && isBluetoothOn && isLocationOn
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title and Subtitle
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Get ready",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = "Turn these on to connect.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            // Live status rows
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (mode) {
                    ReadinessMode.SEND -> {
                        ReadinessRow(
                            icon = Icons.Default.Wifi,
                            label = "Wi-Fi",
                            status = if (isWifiOn) ReadinessStatus.ON else ReadinessStatus.OFF,
                            onRowTap = {
                                ConnectionChecker.openWifiSettings(context)
                            }
                        )

                        ReadinessRow(
                            icon = Icons.Default.Bluetooth,
                            label = "Bluetooth",
                            status = if (isBluetoothOn) ReadinessStatus.ON else ReadinessStatus.OFF,
                            onRowTap = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                        return@ReadinessRow
                                    }
                                }
                                try {
                                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                } catch (_: Exception) {
                                    ConnectionChecker.openAppSettings(context)
                                }
                            }
                        )

                        val isAndroid13Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ReadinessRow(
                            icon = if (isAndroid13Plus) Icons.Default.Wifi else Icons.Default.LocationOn,
                            label = if (isAndroid13Plus) "Nearby devices" else "Location",
                            status = if (isNearbyDevicesOn) ReadinessStatus.ON else ReadinessStatus.OFF,
                            onRowTap = {
                                if (isAndroid13Plus) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                                        nearbyDevicesPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                                    } else {
                                        ConnectionChecker.openAppSettings(context)
                                    }
                                } else {
                                    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    if (!hasFine && !hasCoarse) {
                                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                    } else {
                                        ConnectionChecker.openLocationSettings(context)
                                    }
                                }
                            }
                        )
                    }

                    ReadinessMode.RECEIVE -> {
                        ReadinessRow(
                            icon = Icons.Default.Wifi,
                            label = "Wi-Fi",
                            status = if (isWifiOn) ReadinessStatus.ON else ReadinessStatus.OFF,
                            onRowTap = {
                                ConnectionChecker.openWifiSettings(context)
                            }
                        )

                        ReadinessRow(
                            icon = Icons.Default.Bluetooth,
                            label = "Bluetooth",
                            status = if (isBluetoothOn) ReadinessStatus.ON else ReadinessStatus.OFF,
                            onRowTap = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                        return@ReadinessRow
                                    }
                                }
                                try {
                                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                } catch (_: Exception) {
                                    ConnectionChecker.openAppSettings(context)
                                }
                            }
                        )

                        ReadinessRow(
                            icon = Icons.Default.LocationOn,
                            label = "Location",
                            status = if (isLocationOn) ReadinessStatus.ON else ReadinessStatus.OFF,
                            onRowTap = {
                                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                if (!hasFine && !hasCoarse) {
                                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                } else {
                                    ConnectionChecker.openLocationSettings(context)
                                }
                            }
                        )
                    }

                    ReadinessMode.HOTSPOT_PC -> {
                        ReadinessRow(
                            icon = Icons.Default.WifiTethering,
                            label = "Hotspot",
                            status = effectiveHotspotStatus,
                            onRowTap = {
                                ConnectionChecker.openHotspotSettings(context)
                            },
                            onPillTap = {
                                if (effectiveHotspotStatus == ReadinessStatus.UNKNOWN) {
                                    userAcknowledgedHotspot = true
                                } else {
                                    ConnectionChecker.openHotspotSettings(context)
                                }
                            }
                        )

                        ReadinessRow(
                            icon = Icons.Default.Bluetooth,
                            label = "Bluetooth",
                            status = if (isBluetoothOn) ReadinessStatus.ON else ReadinessStatus.OFF,
                            onRowTap = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                        return@ReadinessRow
                                    }
                                }
                                try {
                                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                } catch (_: Exception) {
                                    ConnectionChecker.openAppSettings(context)
                                }
                            }
                        )

                        ReadinessRow(
                            icon = Icons.Default.LocationOn,
                            label = "Location",
                            status = if (isLocationOn) ReadinessStatus.ON else ReadinessStatus.OFF,
                            onRowTap = {
                                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                if (!hasFine && !hasCoarse) {
                                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                } else {
                                    ConnectionChecker.openLocationSettings(context)
                                }
                            }
                        )
                    }
                }
            }

            // Footer: Cancel and Continue
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Cancel", color = AccentRed, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onContinue,
                    enabled = allReady,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        text = "Continue",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    icon: ImageVector,
    label: String,
    status: ReadinessStatus,
    onRowTap: () -> Unit,
    onPillTap: () -> Unit = onRowTap
) {
    val isOn = status == ReadinessStatus.ON

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onRowTap() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }

        AnimatedContent(
            targetState = status,
            transitionSpec = {
                fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
            },
            label = "pill_crossfade"
        ) { targetStatus ->
            when (targetStatus) {
                ReadinessStatus.ON -> {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable { onPillTap() }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 18.dp)
                        ) {
                            Text(
                                text = "On",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
                ReadinessStatus.OFF -> {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable { onPillTap() }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 18.dp)
                        ) {
                            Text(
                                text = "Off",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
                            )
                        }
                    }
                }
                ReadinessStatus.UNKNOWN -> {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable { onPillTap() }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        ) {
                            Text(
                                text = "Unknown",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        }
    }
}
