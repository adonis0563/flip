package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.Device
import com.example.model.TransferItem
import com.example.model.TransferStatus
import com.example.service.QrCodeGenerator
import com.example.service.QrSessionManager
import com.example.service.QrParseResult
import com.example.service.QrConnectionPackage
import com.example.service.WifiHotspotManager
import com.example.service.StorageService
import com.example.service.LocalFileItem
import com.example.service.FlipConnectionState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.example.ui.theme.*
import com.example.viewmodel.ConnectionState
import com.example.viewmodel.FlipViewModel
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothManager
import android.provider.Settings
import android.os.Build

// Helper to extract file details (name and size) from a URI
fun getFileDetails(context: Context, uri: Uri): Pair<String, Long> {
    var name = "unnamed_file"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return Pair(name, size)
}

@Composable
fun FlipAppContent(viewModel: FlipViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val role by viewModel.role.collectAsState()
    val localDevice by viewModel.localDevice.collectAsState()
    val remoteDevice by viewModel.remoteDevice.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val transferQueue by viewModel.transferQueue.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val bleError by viewModel.bleError.collectAsState()
    val flipConnectionState by viewModel.flipConnectionState.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()

    val context = LocalContext.current

    LaunchedEffect(themeMode) {
        com.example.ui.theme.ThemeSettings.themeMode = themeMode
    }

    var showStandaloneFilePicker by remember { mutableStateOf(false) }

    val standaloneFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val items = uris.map { uri ->
                val details = getFileDetails(context, uri)
                LocalFileItem(
                    id = java.util.UUID.randomUUID().mostSignificantBits,
                    uri = uri,
                    name = details.first,
                    size = details.second,
                    mimeType = context.contentResolver.getType(uri) ?: "*/*",
                    category = "Documents"
                )
            }
            viewModel.preSelectFiles(items)
            viewModel.startSenderMode()
            showStandaloneFilePicker = false
        }
    }

    // Observe and display toast messages cleanly
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // App state machine navigation using custom clean state routing
    val prefs = remember(context) { context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE) }
    var showSplash by remember { mutableStateOf(!prefs.getBoolean("onboarding_complete", false)) }
    var showSettings by remember { mutableStateOf(false) }

    if (showSplash) {
        SplashScreen(
            onGetStarted = { showSplash = false }
        )
    } else {
        // Safe persistent top-level BackHandler to prevent system-exit race conditions in Compose
        val canGoBack = showSettings ||
                        connectionState == ConnectionState.SEARCHING || 
                        connectionState == ConnectionState.CONNECTING ||
                        connectionState == ConnectionState.CONNECTED
        BackHandler(enabled = canGoBack) {
            if (showSettings) {
                showSettings = false
            } else if (connectionState == ConnectionState.CONNECTED) {
                viewModel.disconnect()
            } else {
                viewModel.resetToHome()
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (showSettings) {
                    SettingsScreen(
                        currentTheme = themeMode,
                        onThemeChanged = { viewModel.setThemeMode(it) },
                        localDeviceName = localDevice?.name ?: "Android Device",
                        onBack = { showSettings = false }
                    )
                } else {
                    when (connectionState) {
                        ConnectionState.IDLE -> {
                            HomeScreen(
                                localDevice = localDevice,
                                flipConnectionState = flipConnectionState,
                                onSendMode = { viewModel.startSenderMode() },
                                onReceiveMode = { viewModel.startReceiverMode() },
                                onExploreFiles = { showStandaloneFilePicker = true },
                                onOpenSettings = { showSettings = true }
                            )
                        }

                    ConnectionState.SEARCHING, ConnectionState.CONNECTING -> {
                        if (role == "SENDER") {
                            SenderWaitingScreen(
                                localDevice = localDevice,
                                activeSessionId = activeSessionId,
                                onUpdateIp = { viewModel.updateLocalIpAddress(it) },
                                onCancel = { viewModel.resetToHome() }
                            )
                        } else {
                            ReceiverScanningScreen(
                                discoveredDevices = discoveredDevices,
                                bleError = bleError,
                                localDevice = localDevice,
                                onSelectDevice = { viewModel.connectToDiscoveredDevice(it) },
                                onManualConnect = { ip, port -> viewModel.connectManually(ip, port) },
                                onCancel = { viewModel.resetToHome() }
                            )
                        }
                    }

                    ConnectionState.CONNECTED -> {
                        ConnectedScreen(
                            remoteDevice = remoteDevice,
                            transferQueue = transferQueue,
                            onSendFile = { uri, name, size ->
                                viewModel.addFileToTransferQueue(uri, name, size)
                            },
                            onSendText = { text ->
                                viewModel.sendText(text)
                            },
                            onPauseItem = { id -> viewModel.pauseTransfer(id) },
                            onResumeItem = { id -> viewModel.resumeTransfer(id) },
                            onCancelItem = { id -> viewModel.cancelTransfer(id) },
                            onDisconnect = { viewModel.disconnect() }
                        )
                    }

                    ConnectionState.DISCONNECTED -> {
                        // Automatically clean up and return to Home
                        LaunchedEffect(Unit) {
                            viewModel.resetToHome()
                        }
                    }
                }
                } // Close showSettings else block

                if (showStandaloneFilePicker) {
                    InAppFilePickerContent(
                        onDismiss = { showStandaloneFilePicker = false },
                        onSendFiles = { selectedFiles ->
                            viewModel.preSelectFiles(selectedFiles)
                            viewModel.startSenderMode()
                        },
                        onLaunchSystemPicker = {
                            standaloneFilePickerLauncher.launch(arrayOf("*/*"))
                        }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Splash Screen Composable
// -------------------------------------------------------------
@Composable
fun SplashScreen(onGetStarted: () -> Unit) {
    val context = LocalContext.current
    var dontShowAgain by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(1f))

        // Large display app logo with Material 3 depth
        Surface(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(32.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 2.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.flip_logo),
                    contentDescription = "Flip Logo",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(80.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Flip",
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "A zero-account, local-first file & text sharing app.",
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MinTextMuted,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.weight(1.2f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { dontShowAgain = !dontShowAgain }
                .padding(vertical = 8.dp)
        ) {
            Checkbox(
                checked = dontShowAgain,
                onCheckedChange = { dontShowAgain = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MinTextMuted
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Don't show this again",
                style = MaterialTheme.typography.bodyMedium.copy(color = MinTextMuted)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (dontShowAgain) {
                    context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("onboarding_complete", true)
                        .apply()
                }
                onGetStarted()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("get_started_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "Get Started",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// -------------------------------------------------------------
// Connection Setup Helper Functions & Dialog
fun checkHotspotEnabled(context: Context): Boolean {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
    try {
        val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
        method.isAccessible = true
        if (method.invoke(wifiManager) as? Boolean == true) return true
    } catch (_: Exception) {}
    try {
        val method = wifiManager.javaClass.getMethod("getWifiApState")
        val state = method.invoke(wifiManager) as? Int
        if (state == 13 || state == 12) return true
    } catch (_: Exception) {}
    if (com.example.service.WifiHotspotManager.activeHotspotSsid != null) return true
    return false
}

fun checkWifiEnabled(context: Context): Boolean {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
    return wifiManager.isWifiEnabled
}

fun checkBluetoothEnabled(context: Context): Boolean {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return bluetoothManager?.adapter?.isEnabled == true
}

fun checkLocationEnabled(context: Context): Boolean {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        locationManager.isLocationEnabled
    } else {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}

fun openHotspotSettings(context: Context) {
    try {
        val intent = Intent("android.settings.TETHER_SETTINGS").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        openSettingsFallback(context, Settings.ACTION_WIRELESS_SETTINGS)
    }
}

fun openWifiSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        openSettingsFallback(context, Settings.ACTION_SETTINGS)
    }
}

fun openBluetoothSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        openSettingsFallback(context, Settings.ACTION_SETTINGS)
    }
}

fun openLocationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        openSettingsFallback(context, Settings.ACTION_SETTINGS)
    }
}

private fun openSettingsFallback(context: Context, action: String) {
    try {
        val intent = Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(fallback)
        } catch (_: Exception) {}
    }
}

@Composable
fun ConnectionSetupDialog(
    isSendMode: Boolean,
    onDismissRequest: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    var isNetworkServiceOn by remember {
        mutableStateOf(if (isSendMode) checkHotspotEnabled(context) else checkWifiEnabled(context))
    }
    var isBluetoothOn by remember { mutableStateOf(checkBluetoothEnabled(context)) }
    var isLocationOn by remember { mutableStateOf(checkLocationEnabled(context)) }

    fun refreshStatus() {
        isNetworkServiceOn = if (isSendMode) checkHotspotEnabled(context) else checkWifiEnabled(context)
        isBluetoothOn = checkBluetoothEnabled(context)
        isLocationOn = checkLocationEnabled(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            refreshStatus()
            delay(500)
        }
    }

    val allEnabled = isNetworkServiceOn && isBluetoothOn && isLocationOn

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsInputAntenna,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = if (isSendMode) "Prepare to Send" else "Prepare to Receive",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MinTextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (isSendMode)
                        "Enable the required services below before starting Send."
                    else
                        "Enable the required services below before starting Receive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MinTextMuted
                )

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SetupRowItem(
                            icon = if (isSendMode) Icons.Default.WifiTethering else Icons.Default.Wifi,
                            title = if (isSendMode) "Hotspot" else "Wi-Fi",
                            isEnabled = isNetworkServiceOn,
                            onTurnOn = {
                                if (isSendMode) openHotspotSettings(context) else openWifiSettings(context)
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        SetupRowItem(
                            icon = Icons.Default.Bluetooth,
                            title = "Bluetooth",
                            isEnabled = isBluetoothOn,
                            onTurnOn = { openBluetoothSettings(context) }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        SetupRowItem(
                            icon = Icons.Default.LocationOn,
                            title = "Location",
                            isEnabled = isLocationOn,
                            onTurnOn = { openLocationSettings(context) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                enabled = allEnabled,
                modifier = Modifier.testTag("connection_setup_continue_btn"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Continue", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.testTag("connection_setup_cancel_btn")
            ) {
                Text("Cancel", color = MinTextSubtle, fontWeight = FontWeight.Medium)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun SetupRowItem(
    icon: ImageVector,
    title: String,
    isEnabled: Boolean,
    onTurnOn: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = if (isEnabled) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isEnabled) AccentGreen else AccentRed,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MinTextPrimary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = if (isEnabled) AccentGreen else AccentRed,
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = if (isEnabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = if (isEnabled) AccentGreen else AccentRed
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onTurnOn,
            modifier = Modifier
                .height(36.dp)
                .testTag("turn_on_${title.lowercase()}_btn"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = if (isEnabled) "Open Settings" else "Turn On",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

// Home Screen Composable
// -------------------------------------------------------------
@Composable
fun HomeScreen(
    localDevice: Device?,
    flipConnectionState: FlipConnectionState,
    onSendMode: () -> Unit,
    onReceiveMode: () -> Unit,
    onExploreFiles: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var showConnectionSetupForSend by remember { mutableStateOf(false) }
    var showConnectionSetupForReceive by remember { mutableStateOf(false) }
    var showNoNetworkDialog by remember { mutableStateOf(false) }
    var showMobileDataDialog by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHorizontalCircle,
                    contentDescription = "Flip Logo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "Flip",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Device profile card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = "My Device",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = localDevice?.name ?: "Android Device",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MinTextPrimary
                        )
                    )
                    Text(
                        text = "IP: ${localDevice?.ip ?: "Unknown"}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MinTextSubtle
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.8f))

        Text(
            text = "Ready to flip?",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                color = MinTextPrimary
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Send & Receive big CTA buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = {
                    val isHotspotOn = checkHotspotEnabled(context)
                    val isBtOn = checkBluetoothEnabled(context)
                    val isLocOn = checkLocationEnabled(context)
                    if (isHotspotOn && isBtOn && isLocOn) {
                        onSendMode()
                    } else {
                        showConnectionSetupForSend = true
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp)
                    .testTag("send_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Send Icon",
                        tint = LocalContentColor.current,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Send",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Button(
                onClick = {
                    val isWifiOn = checkWifiEnabled(context)
                    val isBtOn = checkBluetoothEnabled(context)
                    val isLocOn = checkLocationEnabled(context)
                    if (isWifiOn && isBtOn && isLocOn) {
                        onReceiveMode()
                    } else {
                        showConnectionSetupForReceive = true
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp)
                    .testTag("receive_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Receive Icon",
                        tint = LocalContentColor.current,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Receive",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onExploreFiles,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("explore_files_button"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Explore Files",
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Files",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
        }


    }

    if (showConnectionSetupForSend) {
        ConnectionSetupDialog(
            isSendMode = true,
            onDismissRequest = { showConnectionSetupForSend = false },
            onContinue = {
                showConnectionSetupForSend = false
                onSendMode()
            }
        )
    }

    if (showConnectionSetupForReceive) {
        ConnectionSetupDialog(
            isSendMode = false,
            onDismissRequest = { showConnectionSetupForReceive = false },
            onContinue = {
                showConnectionSetupForReceive = false
                onReceiveMode()
            }
        )
    }

    if (showNoNetworkDialog) {
        AlertDialog(
            onDismissRequest = { showNoNetworkDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = "No Network Warning",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text("Connect to Wi-Fi or Hotspot", fontWeight = FontWeight.Bold, color = MinTextPrimary)
                }
            },
            text = {
                Text(
                    text = "Your device has a loopback or offline IP (${localDevice?.ip ?: "127.0.0.1"}). Please connect to a Wi-Fi network or enable/connect to a personal Hotspot first.",
                    color = MinTextMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showNoNetworkDialog = false }
                ) {
                    Text("OK", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

// -------------------------------------------------------------
// SENDER WAITING SCREEN (with QR Code & Hotspot Helpers)
// -------------------------------------------------------------
@Composable
fun SenderWaitingScreen(
    localDevice: Device?,
    activeSessionId: String? = null,
    onUpdateIp: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val sessionId = remember(activeSessionId, localDevice?.id) {
        activeSessionId ?: localDevice?.id ?: java.util.UUID.randomUUID().toString().take(12)
    }

    val hotspotSsid = com.example.service.WifiHotspotManager.activeHotspotSsid ?: ""
    val hotspotPassword = com.example.service.WifiHotspotManager.activeHotspotPassword ?: ""
    val hostIp = localDevice?.ip?.takeIf { it.isNotBlank() && it != "127.0.0.1" } ?: ""

    val pairingPayload = remember(localDevice, hotspotSsid, hotspotPassword, hostIp, sessionId) {
        if (hotspotSsid.isNotBlank() && hotspotPassword.isNotBlank() && hostIp.isNotBlank()) {
            com.example.service.QrSessionManager.generateQrUri(
                sessionId = sessionId,
                deviceName = localDevice?.name ?: "Android Device",
                mode = "hotspot",
                ssid = hotspotSsid,
                pwd = hotspotPassword,
                ip = hostIp,
                port = localDevice?.port ?: 8080
            )
        } else ""
    }

    // Generate local QR Code
    val qrBitmap = remember(pairingPayload) {
        if (pairingPayload.isNotBlank()) {
            QrCodeGenerator.generateQrCode(pairingPayload)
        } else null
    }

    // Connect code: last segment of IP + port
    val connectCode = remember(localDevice) {
        val lastSegment = localDevice?.ip?.substringAfterLast(".") ?: "0"
        "$lastSegment${localDevice?.port ?: "8080"}"
    }

    var selectedQrTab by remember { mutableStateOf(0) } // 0 = App Pairing, 1 = Hotspot Wi-Fi
    var showIpEditDialog by remember { mutableStateOf(false) }
    var editingIp by remember { mutableStateOf(localDevice?.ip ?: "") }

    if (showIpEditDialog) {
        AlertDialog(
            onDismissRequest = { showIpEditDialog = false },
            title = { Text("Correct Server IP", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "If your detected IP is wrong, correct it here so receivers can connect over Wi-Fi and BLE.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MinTextMuted
                    )
                    OutlinedTextField(
                        value = editingIp,
                        onValueChange = { editingIp = it },
                        label = { Text("Local IP Address") },
                        placeholder = { Text("e.g. 192.168.43.1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MinTextPrimary,
                            unfocusedTextColor = MinTextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editingIp.isNotBlank()) {
                            onUpdateIp(editingIp.trim())
                        }
                        showIpEditDialog = false
                    }
                ) {
                    Text("Update & Restart BLE")
                }
            },
            dismissButton = {
                TextButton(onClick = { showIpEditDialog = false }) {
                    Text("Cancel", color = MinTextSubtle)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Go Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "Flipping Out...",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "Waiting for receiver...",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MinTextMuted,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        // Toggle Segment Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("App Pairing QR", "Hotspot Wi-Fi").forEachIndexed { index, title ->
                val isSelected = selectedQrTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { selectedQrTab = index }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MinTextMuted
                        )
                    )
                }
            }
        }

        // Beautiful Card presenting the QR Code and Details
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MinAccentBlueLight),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (selectedQrTab == 0) {
                    if (qrBitmap != null) {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .size(190.dp)
                                .padding(4.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Pairing QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(190.dp)
                                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("QR Error", color = AccentRed)
                        }
                    }

                    Text(
                        text = "Receiver can scan this QR code within the Flip app, or connect using the info below:",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MinAccentBlueDark.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                    )

                    // 4-Digit Connection code
                    Surface(
                        color = MinAccentBlueDark.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "CONNECT CODE",
                                style = MaterialTheme.typography.labelSmall.copy(color = MinAccentBlueDark.copy(alpha = 0.7f), letterSpacing = 2.sp)
                            )
                            Text(
                                text = connectCode,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    color = MinAccentBlueDark,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Server IP: ${localDevice?.ip}:${localDevice?.port}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MinAccentBlueDark.copy(alpha = 0.8f),
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = { 
                                editingIp = localDevice?.ip ?: ""
                                showIpEditDialog = true 
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Server IP",
                                tint = MinAccentBlueDark,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Hotspot",
                        tint = MinAccentBlueDark,
                        modifier = Modifier.size(48.dp)
                    )

                    Text(
                        text = "📶 How to connect via Hotspot",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MinAccentBlueDark
                        ),
                        textAlign = TextAlign.Center
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "On this device (sender):",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MinAccentBlueDark
                            )
                        )
                        Text(
                            text = "1. Open Settings → Mobile Hotspot\n2. Enable your hotspot\n3. Note your hotspot name & password",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MinAccentBlueDark.copy(alpha = 0.9f)
                            ),
                            lineHeight = 18.sp
                        )

                        Divider(color = MinAccentBlueDark.copy(alpha = 0.2f), thickness = 1.dp)

                        Text(
                            text = "On the other device (receiver):",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MinAccentBlueDark
                            )
                        )
                        Text(
                            text = "1. Open Settings → Wi-Fi\n2. Connect to this device's hotspot\n3. Return to Flip and tap \"Receive\"",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MinAccentBlueDark.copy(alpha = 0.9f)
                            ),
                            lineHeight = 18.sp
                        )

                        Divider(color = MinAccentBlueDark.copy(alpha = 0.2f), thickness = 1.dp)

                        Text(
                            text = "Once connected to the same hotspot, Flip will find each other automatically over BLE.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontStyle = FontStyle.Italic,
                                color = MinAccentBlueDark.copy(alpha = 0.8f)
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("cancel_advertising_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Cancel")
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ReceiverScanningScreen(
    discoveredDevices: List<Device>,
    bleError: String?,
    localDevice: Device?,
    onSelectDevice: (Device) -> Unit,
    onManualConnect: (String, Int) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var manualCode by remember { mutableStateOf("") }
    var showScanOverlay by remember { mutableStateOf(false) }

    var qrErrorDialogMessage by remember { mutableStateOf<String?>(null) }
    var scannedPackageData by remember { mutableStateOf<QrConnectionPackage?>(null) }
    var isJoiningWifi by remember { mutableStateOf(false) }

    val wifiHotspotManager = remember(context) { WifiHotspotManager(context) }
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    fun processScannedQr(rawQr: String) {
        if (!rawQr.startsWith("flip://")) return

        val parseResult = QrSessionManager.parseAndValidateQrUri(rawQr)
        when (parseResult) {
            is QrParseResult.Error -> {
                qrErrorDialogMessage = parseResult.message
            }
            is QrParseResult.Success -> {
                scannedPackageData = parseResult.packageData
            }
        }
    }

    LaunchedEffect(showScanOverlay) {
        if (showScanOverlay && !cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (qrErrorDialogMessage != null) {
        AlertDialog(
            onDismissRequest = { qrErrorDialogMessage = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text("QR Code Invalid or Expired", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = qrErrorDialogMessage ?: "Invalid QR code",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        qrErrorDialogMessage = null
                        manualCode = ""
                    },
                    modifier = Modifier.testTag("qr_error_dismiss_btn")
                ) {
                    Text("Scan Again")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (scannedPackageData != null) {
        val pkg = scannedPackageData!!
        val clipboardManager = LocalClipboardManager.current

        AlertDialog(
            onDismissRequest = { scannedPackageData = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Connect to ${pkg.deviceName}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "QR Fallback Connection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MinTextMuted
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Flip decrypted complete session credentials from the QR code. Join the sender's hotspot to complete connection automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MinTextMuted
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (pkg.ssid.isNotBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Hotspot Name:", style = MaterialTheme.typography.labelMedium, color = MinTextMuted)
                                    Text(pkg.ssid, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (pkg.password.isNotBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Password:", style = MaterialTheme.typography.labelMedium, color = MinTextMuted)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(pkg.password, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(pkg.password))
                                                Toast.makeText(context, "Password copied to clipboard", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Password", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Server IP:", style = MaterialTheme.typography.labelMedium, color = MinTextMuted)
                                Text("${pkg.ip}:${pkg.port}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (isJoiningWifi) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Joining Wi-Fi and establishing connection...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isJoiningWifi = true
                        wifiHotspotManager.joinWifi(pkg.ssid, pkg.password, pkg.ip, object : WifiHotspotManager.WifiJoinListener {
                            override fun onJoined(ip: String) {
                                isJoiningWifi = false
                                scannedPackageData = null
                                showScanOverlay = false
                                onManualConnect(pkg.ip, pkg.port)
                            }

                            override fun onFailed(error: String) {
                                isJoiningWifi = false
                                scannedPackageData = null
                                showScanOverlay = false
                                onManualConnect(pkg.ip, pkg.port)
                            }
                        })
                    },
                    modifier = Modifier.testTag("join_hotspot_confirm_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Join & Connect", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scannedPackageData = null
                    }
                ) {
                    Text("Cancel", color = MinTextSubtle)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Go Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "Scanning for Flips",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
        }

        if (bleError != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Bluetooth Error",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Bluetooth issue: $bleError. Please make sure Bluetooth and Location are enabled on your device.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (showScanOverlay) {
            // Highly immersive camera overlay / QR scanning module
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.2f)
                    .background(Color.Black, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
            ) {
                // Animated Scanning line fallback
                val infiniteTransition = rememberInfiniteTransition()
                val offsetLine by infiniteTransition.animateFloat(
                    initialValue = 0.1f,
                    targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.DarkGray)
                    ) {
                        // Show actual camera preview if permission is granted
                        if (cameraPermissionState.status.isGranted) {
                            CameraPreview(
                                modifier = Modifier.fillMaxSize(),
                                onQrCodeScanned = { qrResult ->
                                    if (qrResult.startsWith("flip://")) {
                                        processScannedQr(qrResult)
                                    }
                                }
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Camera permission needed to scan QR code.",
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }

                        // Pulsing scanning laser line on top of camera preview
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.015f)
                                .align(Alignment.TopCenter)
                                .offset(y = (offsetLine * 240).dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Point camera at Sender's QR Code",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Input scanner shortcut for emulator convenience
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { 
                            manualCode = it
                            if (it.startsWith("flip://")) {
                                processScannedQr(it)
                            }
                        },
                        placeholder = { Text("Paste pairing URL (for testing)", color = Color.Gray) },
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = Color.White
                        )
                    )
                }

                IconButton(
                    onClick = { showScanOverlay = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color.DarkGray.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close Scanner", tint = Color.White)
                }
            }
        } else {
            // BLE Discovered Senders List
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.2f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Nearby Devices",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MinTextPrimary
                            )
                        )
                        IconButton(onClick = { showScanOverlay = true }) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Open Scanner",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (discoveredDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Searching over BLE...",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MinTextMuted)
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(discoveredDevices) { device ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectDevice(device) }
                                        .testTag("device_list_item"),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PhoneAndroid,
                                                contentDescription = "Device",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = device.name,
                                                color = MinTextPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "IP: ${device.ip}",
                                                color = MinTextMuted,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Spacer(modifier = Modifier.weight(1f))
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = "Connect",
                                            tint = MinTextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Hotspot Quick Connection Assistant Block
        val localIp = localDevice?.ip ?: ""
        val calculatedGatewayIp = remember(localIp) {
            if (localIp.contains(".") && localIp.split(".").size == 4) {
                val parts = localIp.split(".")
                "${parts[0]}.${parts[1]}.${parts[2]}.1"
            } else {
                null
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsInputAntenna,
                        contentDescription = "Hotspot",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Hotspot Quick Connection",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = MinTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Text(
                    text = "If connected to the sender's mobile hotspot, click below to instantly connect to the host device (usually the .1 gateway on your subnet).",
                    style = MaterialTheme.typography.bodySmall.copy(color = MinTextMuted)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Button 1: Dynamic Subnet Gateway (Highly likely to be the correct host)
                    if (calculatedGatewayIp != null) {
                        Button(
                            onClick = { onManualConnect(calculatedGatewayIp, 8080) },
                            modifier = Modifier.weight(1.3f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "Host: $calculatedGatewayIp",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    // Button 2: Android Standard Hotspot Default (192.168.43.1)
                    if (calculatedGatewayIp != "192.168.43.1") {
                        Button(
                            onClick = { onManualConnect("192.168.43.1", 8080) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MinTextPrimary
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "Android: .43.1",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    // Button 3: Alternative Hotspot Default (192.168.49.1)
                    if (calculatedGatewayIp != "192.168.49.1") {
                        Button(
                            onClick = { onManualConnect("192.168.49.1", 8080) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MinTextPrimary
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "Alternative: .49.1",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        // Connect via code helper block
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Or enter Connect Code",
                    style = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it },
                        placeholder = { Text("Enter 4-Digit Code", color = MinTextMuted) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_code_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = MinTextPrimary,
                            unfocusedTextColor = MinTextPrimary
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Button(
                        onClick = {
                            val code = manualCode.trim()
                            if (code.length >= 4) {
                                // Connect codes map to last octet + port
                                // e.g. Connect Code "58080" maps to IP segment: .5, Port: 8080.
                                val localIpStr = localDevice?.ip ?: ""
                                val basePrefix = if (localIpStr.contains(".") && localIpStr.split(".").size == 4) {
                                    val parts = localIpStr.split(".")
                                    "${parts[0]}.${parts[1]}.${parts[2]}."
                                } else {
                                    "192.168.43." // standard Android Hotspot subnet base
                                }
                                val portPart = code.takeLast(4).toIntOrNull() ?: 8080
                                val ipOctet = code.dropLast(4)
                                onManualConnect("$basePrefix$ipOctet", portPart)
                            }
                        },
                        modifier = Modifier
                            .height(56.dp)
                            .testTag("code_connect_submit"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Connect")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("cancel_scanning_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Cancel")
        }
    }
}

// -------------------------------------------------------------
// IN-APP FILE EXPLORER COMPONENTS
// -------------------------------------------------------------
@Composable
fun CustomFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.primary else MinTextMuted
            )
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalFoundationApi::class)
@Composable
fun InAppFilePickerContent(
    onDismiss: () -> Unit,
    onSendFiles: (List<LocalFileItem>) -> Unit,
    onLaunchSystemPicker: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val prefs = remember(context) { context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE) }
    var showSystemApps by remember { mutableStateOf(false) }
    var keepExtractedApk by remember { mutableStateOf(prefs.getBoolean("keep_extracted_apk", false)) }
    
    // APK extraction progress state
    var isExtracting by remember { mutableStateOf(false) }
    var extractionProgress by remember { mutableStateOf(0f) }
    var currentExtractingAppName by remember { mutableStateOf("") }
    var totalExtractingAppsCount by remember { mutableStateOf(0) }
    var currentExtractingAppIndex by remember { mutableStateOf(0) }
    
    var selectedCategory by remember { mutableStateOf("Images") }
    val categories = listOf("Images", "Videos", "Audio", "Documents", "Apps", "Storage")
    
    // Loaded files state
    var filesList by remember { mutableStateOf<List<LocalFileItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    // Directory navigation state
    var currentDirectory by remember { mutableStateOf(android.os.Environment.getExternalStorageDirectory() ?: java.io.File("/sdcard")) }
    
    // Multi-select state
    var selectedFiles by remember { mutableStateOf<Set<LocalFileItem>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var isDeletingFiles by remember { mutableStateOf(false) }

    // Scanning status state
    var isScanning by remember { mutableStateOf(false) }
    var scanStatusText by remember { mutableStateOf("") }
    var scanProgress by remember { mutableStateOf(0f) }
    var refreshTrigger by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    val deleteIntentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            scope.launch(Dispatchers.IO) {
                val db = com.example.database.AppDatabase.getDatabase(context)
                val dao = db.indexedFileDao()
                val uris = selectedFiles.map { it.uri.toString() }
                val ids = selectedFiles.map { it.id }
                dao.deleteByUris(uris)
                dao.deleteByIds(ids)
                withContext(Dispatchers.Main) {
                    selectedFiles = emptySet()
                    isSelectionMode = false
                    refreshTrigger++
                }
            }
        }
    }

    // Proper back handler
    BackHandler(enabled = true) {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedFiles = emptySet()
        } else if (selectedCategory == "Storage" && currentDirectory != android.os.Environment.getExternalStorageDirectory()) {
            val parent = currentDirectory.parentFile
            if (parent != null) {
                currentDirectory = parent
            } else {
                onDismiss()
            }
        } else {
            onDismiss()
        }
    }

    // SAF Selected/Imported Documents
    var safDocuments by remember { mutableStateOf<List<LocalFileItem>>(emptyList()) }
    
    val safDocumentsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            val resolved = uris.map { uri ->
                val details = getFileDetails(context, uri)
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                LocalFileItem(
                    id = uri.hashCode().toLong(),
                    uri = uri,
                    name = details.first,
                    size = details.second,
                    mimeType = mimeType,
                    category = "Documents",
                    dateAdded = System.currentTimeMillis() / 1000L
                )
            }
            safDocuments = (safDocuments + resolved).distinctBy { it.uri.toString() }
            selectedFiles = (selectedFiles + resolved).toSet()
        }
    }    

    // Required permission based on category
    val permissionsToRequest = remember {
        val list = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            list.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            list.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            list.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            list.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        list.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        list
    }
    
    val multiplePermissionsState = rememberMultiplePermissionsState(permissions = permissionsToRequest)
    val isPermissionGranted = remember(multiplePermissionsState.permissions) {
        multiplePermissionsState.permissions.any { it.status.isGranted }
    }

    fun triggerScan() {
        scope.launch {
            isScanning = true
            com.example.service.FileScanner.scanDeviceFiles(context) { status, progress ->
                scope.launch {
                    scanStatusText = status
                    scanProgress = progress
                }
            }
            isScanning = false
            refreshTrigger++
        }
    }

    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                prefs.edit().putString("saf_tree_uri", uri.toString()).apply()
                triggerScan()
            } catch (e: Exception) {
                android.util.Log.e("FlipAppUi", "Failed to take persistable URI permission", e)
            }
        }
    }

    var showSafPromptDialog by remember { mutableStateOf(false) }

    LaunchedEffect(selectedCategory) {
        if (selectedCategory == "Documents") {
            val storedUri = prefs.getString("saf_tree_uri", null)
            val hasPermission = if (storedUri != null) {
                try {
                    val uri = Uri.parse(storedUri)
                    context.contentResolver.persistedUriPermissions.any { 
                        it.uri == uri && it.isReadPermission 
                    }
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
            if (!hasPermission) {
                showSafPromptDialog = true
            }
        }
    }

    fun handleSendAction(filesToPrepare: List<LocalFileItem>) {
        scope.launch {
            val appsToExtract = filesToPrepare.filter { it.category == "Apps" }
            val otherFiles = filesToPrepare.filter { it.category != "Apps" }
            
            isExtracting = true
            val preparedApps = mutableListOf<LocalFileItem>()
            val preparedOthers = mutableListOf<LocalFileItem>()
            
            // 1. Extract Apps
            if (appsToExtract.isNotEmpty()) {
                totalExtractingAppsCount = appsToExtract.size
                for ((index, appItem) in appsToExtract.withIndex()) {
                    currentExtractingAppIndex = index + 1
                    currentExtractingAppName = appItem.name.removeSuffix(".apk")
                    extractionProgress = 0f
                    
                    val extractedFile = withContext(Dispatchers.IO) {
                        StorageService.extractApk(context, appItem) { status, progress ->
                            scope.launch {
                                extractionProgress = progress
                            }
                        }
                    }
                    
                    if (extractedFile != null && extractedFile.exists()) {
                        val finalUri = Uri.fromFile(extractedFile)
                        preparedApps.add(
                            appItem.copy(
                                uri = finalUri,
                                size = extractedFile.length()
                            )
                        )
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to extract ${appItem.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            
            // 2. Prepare other files (Images, Videos, Audio, Documents, Storage)
            if (otherFiles.isNotEmpty()) {
                totalExtractingAppsCount = otherFiles.size
                for ((index, otherItem) in otherFiles.withIndex()) {
                    currentExtractingAppIndex = index + 1
                    currentExtractingAppName = otherItem.name
                    extractionProgress = 0f
                    
                    val tempFile = withContext(Dispatchers.IO) {
                        StorageService.createTempTransferFile(context, otherItem) { status, progress ->
                            scope.launch {
                                extractionProgress = progress
                            }
                        }
                    }
                    
                    if (tempFile != null && tempFile.exists()) {
                        val finalUri = Uri.fromFile(tempFile)
                        preparedOthers.add(
                            otherItem.copy(
                                uri = finalUri,
                                size = tempFile.length()
                            )
                        )
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to prepare ${otherItem.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            
            isExtracting = false
            
            val allToTransfer = preparedOthers + preparedApps
            if (allToTransfer.isNotEmpty()) {
                onSendFiles(allToTransfer)
            }
            onDismiss()
        }
    }

    var hasAutoScanned by remember { mutableStateOf(false) }

    // Observe MediaStore changes and app lifecycle for incremental scanning
    DisposableEffect(context, lifecycleOwner) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                android.util.Log.d("FlipAppUi", "MediaStore change detected: $uri. Running incremental scan.")
                scope.launch {
                    com.example.service.FileScanner.performIncrementalScan(context)
                }
            }
        }
        
        // Register ContentObservers
        try {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                observer
            )
        } catch (e: Exception) {
            android.util.Log.e("FlipAppUi", "Failed to register MediaStore ContentObservers", e)
        }

        // Also listen to Lifecycle ON_RESUME to sync on app resume
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                android.util.Log.d("FlipAppUi", "App resumed. Performing incremental scan.")
                scope.launch {
                    com.example.service.FileScanner.performIncrementalScan(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                android.util.Log.e("FlipAppUi", "Failed to unregister ContentObserver", e)
            }
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted && !hasAutoScanned) {
            hasAutoScanned = true
            val db = com.example.database.AppDatabase.getDatabase(context)
            val count = withContext(Dispatchers.IO) {
                db.indexedFileDao().getAllFiles().size
            }
            if (count == 0) {
                triggerScan()
            }
        }
    }
    
    // Load files when category, directory, permission state, safDocuments, showSystemApps, or refresh changes
    LaunchedEffect(selectedCategory, currentDirectory, isPermissionGranted, safDocuments, refreshTrigger, showSystemApps) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val db = com.example.database.AppDatabase.getDatabase(context)
            val dao = db.indexedFileDao()
            if (isPermissionGranted) {
                when (selectedCategory) {
                    "Apps" -> {
                        val apps = StorageService.queryInstalledApps(context, showSystemApps = showSystemApps)
                        withContext(Dispatchers.Main) {
                            filesList = apps
                            isLoading = false
                        }
                    }
                    "Storage" -> {
                        val storageFiles = StorageService.listDirectoryFiles(currentDirectory)
                        withContext(Dispatchers.Main) {
                            filesList = storageFiles
                            isLoading = false
                        }
                    }
                    "Documents" -> {
                        dao.getFilesByCategoryFlow("Documents").collect { dbDocsList ->
                            val dbDocs = dbDocsList.map { indexedFile ->
                                LocalFileItem(
                                    id = indexedFile.id,
                                    uri = Uri.parse(indexedFile.uriString),
                                    name = indexedFile.name,
                                    size = indexedFile.size,
                                    mimeType = indexedFile.mimeType,
                                    category = "Documents",
                                    dateAdded = indexedFile.dateAdded
                                )
                            }
                            val combined = (safDocuments + dbDocs).distinctBy { it.uri.toString() }
                            withContext(Dispatchers.Main) {
                                filesList = combined
                                isLoading = false
                            }
                        }
                    }
                    "Images", "Videos", "Audio" -> {
                        dao.getFilesByCategoryFlow(selectedCategory).collect { dbList ->
                            val mapped = dbList.map { indexedFile ->
                                LocalFileItem(
                                    id = indexedFile.id,
                                    uri = Uri.parse(indexedFile.uriString),
                                    name = indexedFile.name,
                                    size = indexedFile.size,
                                    mimeType = indexedFile.mimeType,
                                    category = selectedCategory,
                                    dateAdded = indexedFile.dateAdded
                                )
                            }
                            withContext(Dispatchers.Main) {
                                filesList = mapped
                                isLoading = false
                            }
                        }
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            filesList = emptyList()
                            isLoading = false
                        }
                    }
                }
            } else {
                val fallbackList = if (selectedCategory == "Documents") {
                    safDocuments
                } else {
                    emptyList()
                }
                withContext(Dispatchers.Main) {
                    filesList = fallbackList
                    isLoading = false
                }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        if (isSelectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = {
                        isSelectionMode = false
                        selectedFiles = emptySet()
                    }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel Selection")
                    }
                    Text(
                        text = "${selectedFiles.size} selected",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MinTextPrimary
                        )
                    )
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val selectableFiles = remember(filesList, searchQuery) {
                        val filtered = if (searchQuery.isBlank()) filesList else filesList.filter { it.name.contains(searchQuery, ignoreCase = true) }
                        filtered.filter { it.category != "Directory" }
                    }
                    val allSelected = selectableFiles.isNotEmpty() && selectedFiles.size >= selectableFiles.size
                    TextButton(
                        onClick = {
                            if (allSelected) {
                                selectedFiles = emptySet()
                            } else {
                                selectedFiles = selectableFiles.toSet()
                            }
                        }
                    ) {
                        Text(
                            text = if (allSelected) "Deselect All" else "Select All",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically, 
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close File Picker")
                    }
                    Text(
                        text = "Files",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold, 
                            color = MinTextPrimary
                        )
                    )
                    IconButton(
                        onClick = { triggerScan() },
                        enabled = !isScanning,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Rescan Files",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                IconButton(
                    onClick = onLaunchSystemPicker,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "System Picker",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        
        // Search Bar for Real-time Filename Filtering
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            placeholder = { Text("Search files by name...", style = MaterialTheme.typography.bodyMedium.copy(color = MinTextMuted)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Search",
                            tint = MinTextMuted
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MinTextPrimary,
                unfocusedTextColor = MinTextPrimary
            )
        )
        
        // Category Selection Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { category ->
                CustomFilterChip(
                    selected = selectedCategory == category,
                    onClick = { 
                        selectedCategory = category 
                        searchQuery = ""
                    },
                    label = category
                )
            }
        }

        // Storage Breadcrumb and navigation controls
        if (selectedCategory == "Storage") {
            val parent = currentDirectory.parentFile
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (parent != null) {
                        IconButton(
                            onClick = { currentDirectory = parent },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Go Up",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = currentDirectory.name.ifEmpty { "Storage" },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MinTextPrimary),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { currentDirectory = android.os.Environment.getExternalStorageDirectory() ?: java.io.File("/sdcard") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("SD Card", style = MaterialTheme.typography.labelMedium)
                    }
                    
                    FilledTonalButton(
                        onClick = { currentDirectory = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Downloads", style = MaterialTheme.typography.labelMedium)
                    }
                    
                    FilledTonalButton(
                        onClick = { currentDirectory = StorageService.getFlipDirectory() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Flip Folder", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        
        // File Display Area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (!isPermissionGranted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Permission Needed",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Permission Required",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            )
                            Text(
                                text = "Storage/Media permissions not granted. Real files from your device will be shown here once granted.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onErrorContainer)
                            )
                        }
                        Button(
                            onClick = { multiplePermissionsState.launchMultiplePermissionRequest() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Grant", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (isScanning) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = scanStatusText.ifEmpty { "Scanning files..." },
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${(scanProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                            )
                        }
                        LinearProgressIndicator(
                            progress = { scanProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    val displayFiles = remember(filesList, searchQuery) {
                        if (searchQuery.isBlank()) {
                            filesList
                        } else {
                            filesList.filter { it.name.contains(searchQuery, ignoreCase = true) }
                        }
                    }
                    
                    if (displayFiles.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "No Files",
                                tint = MinTextMuted.copy(alpha = 0.4f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) {
                                    "No files match \"$searchQuery\""
                                } else if (selectedCategory == "Storage") {
                                    "Folder is empty or unreadable"
                                } else {
                                    "No local files found on device"
                                }, 
                                style = MaterialTheme.typography.bodyMedium.copy(color = MinTextMuted)
                            )
                        }
                    } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (selectedCategory == "Apps") {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "App Sharing Preferences",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MinTextPrimary
                                            )
                                        )
                                        
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showSystemApps = !showSystemApps
                                                }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Show System Apps",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = MinTextPrimary
                                                    )
                                                )
                                                Text(
                                                    text = "Include system packages in the applications list",
                                                    style = MaterialTheme.typography.labelSmall.copy(color = MinTextMuted)
                                                )
                                            }
                                            Switch(
                                                checked = showSystemApps,
                                                onCheckedChange = { showSystemApps = it },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                                )
                                            )
                                        }

                                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    keepExtractedApk = !keepExtractedApk
                                                    prefs.edit().putBoolean("keep_extracted_apk", keepExtractedApk).apply()
                                                }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Keep Extracted APKs",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = MinTextPrimary
                                                    )
                                                )
                                                Text(
                                                    text = "Save extracted APKs in internal storage after transfer complete",
                                                    style = MaterialTheme.typography.labelSmall.copy(color = MinTextMuted)
                                                )
                                            }
                                            Switch(
                                                checked = keepExtractedApk,
                                                onCheckedChange = {
                                                    keepExtractedApk = it
                                                    prefs.edit().putBoolean("keep_extracted_apk", it).apply()
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (selectedCategory == "Storage") {
                            item {
                                val externalStorageDir = android.os.Environment.getExternalStorageDirectory()
                                val totalSpace = externalStorageDir.totalSpace
                                val freeSpace = externalStorageDir.freeSpace
                                val usedSpace = totalSpace - freeSpace
                                val totalStr = android.text.format.Formatter.formatFileSize(context, totalSpace)
                                val freeStr = android.text.format.Formatter.formatFileSize(context, freeSpace)
                                val usedStr = android.text.format.Formatter.formatFileSize(context, usedSpace)
                                val usedPercent = if (totalSpace > 0) usedSpace.toFloat() / totalSpace else 0f

                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "Storage Space Info",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = MinTextPrimary
                                                )
                                            )
                                            Text(
                                                text = "$usedStr / $totalStr used",
                                                style = MaterialTheme.typography.bodySmall.copy(color = MinTextMuted)
                                            )
                                        }
                                        LinearProgressIndicator(
                                            progress = { usedPercent },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.outlineVariant
                                        )
                                        Text(
                                            text = "$freeStr free",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        if (selectedCategory == "Documents") {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                        .clickable { safDocumentsLauncher.launch(arrayOf("*/*")) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    ),
                                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = "Browse SAF Documents",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Browse Documents via SAF",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            )
                                            Text(
                                                text = "Select from secure device downloads, files, or cloud folders.",
                                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Add Documents",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
 
                        items(displayFiles, key = { it.id }) { file ->
                            val isChecked = selectedFiles.contains(file)
                            val isDirectory = file.category == "Directory"
                            val isApp = file.category == "Apps"
                            
                            val dateString = remember(file.dateAdded) {
                                if (file.dateAdded > 0) {
                                    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                                    sdf.format(Date(file.dateAdded * 1000L))
                                } else {
                                    ""
                                }
                            }
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelectionMode) {
                                                if (!isDirectory) {
                                                    val newSelected = if (isChecked) selectedFiles - file else selectedFiles + file
                                                    selectedFiles = newSelected
                                                    if (newSelected.isEmpty()) {
                                                        isSelectionMode = false
                                                    }
                                                }
                                            } else {
                                                if (selectedCategory == "Storage" && isDirectory) {
                                                    currentDirectory = java.io.File(file.uri.path ?: "")
                                                } else {
                                                    openLocalFile(context, file)
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode && !isDirectory) {
                                                isSelectionMode = true
                                                selectedFiles = setOf(file)
                                            }
                                        }
                                    ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isChecked) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        val icon = when (file.category) {
                                            "Images" -> Icons.Default.Image
                                            "Videos" -> Icons.Default.VideoLibrary
                                            "Audio" -> Icons.Default.Audiotrack
                                            "Apps" -> Icons.Default.Android
                                            "Directory" -> Icons.Default.Folder
                                            else -> Icons.Default.InsertDriveFile
                                        }
                                        
                                        val isApp = file.category == "Apps"
                                        val isMedia = file.category == "Images" || file.category == "Videos"
                                        if (isApp && file.appIcon != null) {
                                            val appIcon = file.appIcon
                                            val iconBitmap = remember(appIcon) {
                                                val width = appIcon.intrinsicWidth.coerceAtLeast(1)
                                                val height = appIcon.intrinsicHeight.coerceAtLeast(1)
                                                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                                val canvas = android.graphics.Canvas(bitmap)
                                                appIcon.setBounds(0, 0, canvas.width, canvas.height)
                                                appIcon.draw(canvas)
                                                bitmap
                                            }
                                            Image(
                                                bitmap = iconBitmap.asImageBitmap(),
                                                contentDescription = "App Icon",
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                            )
                                        } else if (isMedia) {
                                            FileThumbnail(
                                                file = file,
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = file.category,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        
                                        Column(modifier = Modifier.weight(1f)) {
                                            val displayName = if (isApp) file.name.removeSuffix(".apk") else file.name
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = MinTextPrimary
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isApp) {
                                                val sizeStr = android.text.format.Formatter.formatFileSize(context, file.size)
                                                val versionStr = file.versionName ?: "1.0"
                                                val pkgStr = file.packageName ?: ""
                                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                    if (pkgStr.isNotEmpty()) {
                                                        Text(
                                                            text = pkgStr,
                                                            style = MaterialTheme.typography.labelSmall.copy(color = MinTextMuted),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Text(
                                                        text = "Version: $versionStr • Size: $sizeStr",
                                                        style = MaterialTheme.typography.bodySmall.copy(color = MinTextSubtle)
                                                     )
                                                }
                                            } else if (!isDirectory) {
                                                val sizeStr = android.text.format.Formatter.formatFileSize(context, file.size)
                                                val subText = if (dateString.isNotEmpty()) "$sizeStr • $dateString" else sizeStr
                                                Text(
                                                    text = subText,
                                                    style = MaterialTheme.typography.bodySmall.copy(color = MinTextMuted)
                                                )
                                            } else {
                                                Text(
                                                    text = "Folder",
                                                    style = MaterialTheme.typography.bodySmall.copy(color = MinTextSubtle)
                                                )
                                            }
                                        }
                                    }
                                    
                                    if (!isDirectory) {
                                        if (isSelectionMode) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = { checked ->
                                                    val newSelected = if (checked == true) {
                                                        selectedFiles + file
                                                    } else {
                                                        selectedFiles - file
                                                    }
                                                    selectedFiles = newSelected
                                                    if (newSelected.isEmpty()) {
                                                        isSelectionMode = false
                                                    }
                                                }
                                            )
                                        } else {
                                            FilledTonalButton(
                                                onClick = {
                                                    if (isApp) {
                                                        handleSendAction(listOf(file))
                                                    } else {
                                                        openLocalFile(context, file)
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(32.dp),
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                                    contentColor = MaterialTheme.colorScheme.primary
                                                )
                                            ) {
                                                val btnIcon = if (isApp) Icons.AutoMirrored.Filled.Send else Icons.Default.Launch
                                                val btnLabel = if (isApp) "Send" else "Open"
                                                Icon(imageVector = btnIcon, contentDescription = btnLabel, modifier = Modifier.size(12.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(btnLabel, style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
        
        // Modern File Manager Selection Bottom Toolbar (Open, Share, Delete, Send)
        if (isSelectionMode || selectedFiles.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Open (if single item selected)
                    val isSingle = selectedFiles.size == 1
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = isSingle) {
                                if (isSingle) {
                                    openLocalFile(context, selectedFiles.first())
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Launch,
                            contentDescription = "Open File",
                            tint = if (isSingle) MaterialTheme.colorScheme.primary else MinTextMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Open",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (isSingle) MinTextPrimary else MinTextMuted.copy(alpha = 0.4f)
                            )
                        )
                    }

                    // Share
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = selectedFiles.isNotEmpty()) {
                                if (selectedFiles.isNotEmpty()) {
                                    shareLocalFiles(context, selectedFiles.toList())
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Files",
                            tint = if (selectedFiles.isNotEmpty()) MaterialTheme.colorScheme.primary else MinTextMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Share",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (selectedFiles.isNotEmpty()) MinTextPrimary else MinTextMuted.copy(alpha = 0.4f)
                            )
                        )
                    }

                    // Delete
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = selectedFiles.isNotEmpty()) {
                                if (selectedFiles.isNotEmpty()) {
                                    showDeleteConfirmationDialog = true
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Files",
                            tint = if (selectedFiles.isNotEmpty()) MaterialTheme.colorScheme.error else MinTextMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Delete",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (selectedFiles.isNotEmpty()) MaterialTheme.colorScheme.error else MinTextMuted.copy(alpha = 0.4f)
                            )
                        )
                    }

                    // Send (Flip Transfer)
                    Button(
                        onClick = {
                            if (selectedFiles.isNotEmpty()) {
                                handleSendAction(selectedFiles.toList())
                            }
                        },
                        enabled = selectedFiles.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Send (${selectedFiles.size})",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeletingFiles) showDeleteConfirmationDialog = false },
            title = {
                Text(
                    text = "Delete ${selectedFiles.size} File${if (selectedFiles.size > 1) "s" else ""}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MinTextPrimary
                    )
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to permanently delete the selected file(s) from your device?",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MinTextMuted)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isDeletingFiles = true
                            val result = StorageService.deleteFiles(context, selectedFiles.toList())
                            isDeletingFiles = false
                            showDeleteConfirmationDialog = false
                            if (result.intentSender != null) {
                                try {
                                    val intentSenderRequest = IntentSenderRequest.Builder(result.intentSender).build()
                                    deleteIntentSenderLauncher.launch(intentSenderRequest)
                                } catch (e: Exception) {
                                    android.util.Log.e("FlipAppUi", "Failed to launch delete intent sender", e)
                                }
                            } else {
                                selectedFiles = emptySet()
                                isSelectionMode = false
                                refreshTrigger++
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    if (isDeletingFiles) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onError, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmationDialog = false },
                    enabled = !isDeletingFiles
                ) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        )
    }

    if (isExtracting) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            containerColor = MaterialTheme.colorScheme.background,
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            title = {
                Text(
                    text = "Preparing Files",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MinTextPrimary
                    )
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Preparing \"$currentExtractingAppName\" (${currentExtractingAppIndex}/${totalExtractingAppsCount})",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MinTextMuted)
                    )
                    
                    LinearProgressIndicator(
                        progress = extractionProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "${(extractionProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        )
    }

    if (showSafPromptDialog) {
        AlertDialog(
            onDismissRequest = { showSafPromptDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Document Access",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Permission Required", fontWeight = FontWeight.Bold, color = MinTextPrimary)
                }
            },
            text = {
                Text(
                    text = "Flip needs access to your documents to display files.",
                    color = MinTextMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSafPromptDialog = false
                        openDocumentTreeLauncher.launch(null)
                    }
                ) {
                    Text("Grant Access")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSafPromptDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// -------------------------------------------------------------
// CONNECTED SCREEN (Active Session and File Transfer Queue)
// -------------------------------------------------------------
@Composable
fun ConnectedScreen(
    remoteDevice: Device?,
    transferQueue: List<TransferItem>,
    onSendFile: (Uri, String, Long) -> Unit,
    onSendText: (String) -> Unit,
    onPauseItem: (String) -> Unit,
    onResumeItem: (String) -> Unit,
    onCancelItem: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    var shareText by remember { mutableStateOf("") }
    val context = LocalContext.current
    var showInAppFilePicker by remember { mutableStateOf(false) }

    // Launchers for picking images and generic files
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val details = getFileDetails(context, it)
            onSendFile(it, details.first, details.second)
        }
    }

    if (showInAppFilePicker) {
        InAppFilePickerContent(
            onDismiss = { showInAppFilePicker = false },
            onSendFiles = { selectedFiles ->
                selectedFiles.forEach { file ->
                    onSendFile(file.uri, file.name, file.size)
                }
            },
            onLaunchSystemPicker = {
                filePicker.launch(arrayOf("*/*"))
                showInAppFilePicker = false
            }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Connected Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Connected Session",
                    style = MaterialTheme.typography.bodySmall.copy(color = MinTextMuted)
                )
                Text(
                    text = remoteDevice?.name ?: "Remote Peer",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
            }

            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentRed.copy(alpha = 0.12f),
                    contentColor = AccentRed
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.testTag("disconnect_button")
            ) {
                Text("Disconnect", fontWeight = FontWeight.Bold)
            }
        }

        // Global Monochrome Progress Bar for Active Transfers
        val activeTransfer = transferQueue.find { it.status == TransferStatus.SENDING }
        if (activeTransfer != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (activeTransfer.isIncoming) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                contentDescription = "Transfer Direction",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (activeTransfer.isIncoming) "Receiving: ${activeTransfer.fileName}" else "Sending: ${activeTransfer.fileName}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MinTextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${(activeTransfer.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = activeTransfer.progress,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }

        // Shared Actions (Send File / Text)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Flipping Payload",
                    style = MaterialTheme.typography.titleSmall.copy(color = MinTextMuted, fontWeight = FontWeight.Bold)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { showInAppFilePicker = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .testTag("send_file_picker_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(imageVector = Icons.Default.AttachFile, contentDescription = "Pick File", tint = LocalContentColor.current)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send File", fontWeight = FontWeight.Bold)
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                // Text flip row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = shareText,
                        onValueChange = { shareText = it },
                        placeholder = { Text("Type text/link to Flip...", color = MinTextMuted) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("text_input_field"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = MinTextPrimary,
                            unfocusedTextColor = MinTextPrimary
                        )
                    )

                    Button(
                        onClick = {
                            if (shareText.isNotBlank()) {
                                onSendText(shareText)
                                shareText = ""
                            }
                        },
                        modifier = Modifier
                            .height(56.dp)
                            .testTag("send_text_submit"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send Text", tint = LocalContentColor.current)
                    }
                }
            }
        }

        // Active Queue/Transfers list
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Transfer Logs & Queues",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MinTextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (transferQueue.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Empty Queue",
                                tint = MinTextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No active transfers",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MinTextMuted)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(transferQueue, key = { it.id }) { item ->
                            TransferItemRow(
                                item = item,
                                onPause = { onPauseItem(item.id) },
                                onResume = { onResumeItem(item.id) },
                                onCancel = { onCancelItem(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
}

// -------------------------------------------------------------
// SHARED COMPONENT: TRANSFER ITEM ROW
// -------------------------------------------------------------
@Composable
fun TransferItemRow(
    item: TransferItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val progressPercent = (item.progress * 100).toInt()
    
    // Choose status color
    val progressColor = when (item.status) {
        TransferStatus.COMPLETE -> AccentGreen
        TransferStatus.PAUSED -> AccentOrange
        TransferStatus.FAILED -> AccentRed
        TransferStatus.CANCELLED -> MinTextMuted
        else -> MaterialTheme.colorScheme.primary
    }

    // Dynamic icon based on file extension
    val fileIcon = remember(item.fileName) {
        val ext = item.fileName.substringAfterLast(".").lowercase()
        when {
            ext in listOf("png", "jpg", "jpeg", "webp", "gif") -> Icons.Default.Image
            ext in listOf("mp4", "mkv", "mov", "avi") -> Icons.Default.VideoFile
            ext in listOf("mp3", "wav", "m4a", "ogg") -> Icons.Default.Audiotrack
            ext == "pdf" -> Icons.Default.PictureAsPdf
            ext in listOf("zip", "rar", "7z", "tar") -> Icons.Default.FolderZip
            else -> Icons.Default.InsertDriveFile
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp)
            .testTag("transfer_item_row"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(progressColor.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = fileIcon,
                    contentDescription = "File Type",
                    tint = progressColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.fileName,
                    color = MinTextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (item.isIncoming) "Incoming" else "Outgoing",
                        color = MinTextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "•",
                        color = MinTextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = formatSize(item.fileSize),
                        color = MinTextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action triggers based on transfer state
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (item.status) {
                    TransferStatus.QUEUED -> {
                        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel", tint = AccentRed)
                        }
                    }

                    TransferStatus.SENDING -> {
                        // For sending we can pause
                        IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Pause, contentDescription = "Pause", tint = AccentOrange)
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel", tint = AccentRed)
                        }
                    }

                    TransferStatus.PAUSED -> {
                        // Can resume (retry)
                        IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Resume", tint = AccentGreen)
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel", tint = AccentRed)
                        }
                    }

                    TransferStatus.FAILED -> {
                        // Can retry
                        IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel", tint = AccentRed)
                        }
                    }

                    TransferStatus.COMPLETE -> {
                        val context = LocalContext.current
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "Success", tint = AccentGreen, modifier = Modifier.size(20.dp))
                            
                            FilledTonalButton(
                                onClick = { openFile(context, item.fileName) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(imageVector = Icons.Default.Launch, contentDescription = "Open file", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                    }

                    TransferStatus.CANCELLED -> {
                        Text("Cancelled", color = MinTextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Inline error messaging if transfer failed
        item.errorMessage?.let {
            Text(
                text = "Error: $it",
                color = AccentRed,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (item.status == TransferStatus.SENDING || item.status == TransferStatus.PAUSED || item.status == TransferStatus.QUEUED || item.status == TransferStatus.FAILED) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LinearProgressIndicator(
                    progress = item.progress,
                    color = progressColor,
                    trackColor = progressColor.copy(alpha = 0.15f),
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "$progressPercent%",
                    color = progressColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// Byte utility formatter
fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

// Open file utility using FileProvider
fun openFile(context: Context, fileName: String) {
    try {
        val file = if (fileName.endsWith(".apk", ignoreCase = true)) {
            java.io.File(java.io.File(StorageService.getFlipDirectory(), "Received/APKs"), fileName)
        } else {
            java.io.File(StorageService.getFlipDirectory(), fileName)
        }
        if (!file.exists()) {
            Toast.makeText(context, "File not found: $fileName", Toast.LENGTH_SHORT).show()
            return
        }
        val authority = "com.example.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        
        val mime = context.contentResolver.getType(uri) ?: when (file.extension.lowercase()) {
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "png", "jpg", "jpeg", "webp", "gif" -> "image/*"
            "mp4", "mkv", "mov", "avi" -> "video/*"
            "mp3", "wav", "m4a", "ogg" -> "audio/*"
            else -> "*/*"
        }
        
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// Open local/selected file utility supporting dynamic URI and mime-type resolving
fun openLocalFile(context: Context, fileItem: LocalFileItem) {
    try {
        var uri = fileItem.uri
        val mimeType = fileItem.mimeType.ifEmpty {
            context.contentResolver.getType(uri) ?: when (fileItem.name.substringAfterLast('.', "").lowercase()) {
                "apk" -> "application/vnd.android.package-archive"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "png", "jpg", "jpeg", "webp", "gif" -> "image/*"
                "mp4", "mkv", "mov", "avi" -> "video/*"
                "mp3", "wav", "m4a", "ogg" -> "audio/*"
                else -> "*/*"
            }
        }
        
        if (uri.scheme == "file") {
            val file = java.io.File(uri.path ?: "")
            if (file.exists()) {
                val authority = "com.example.fileprovider"
                uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
            }
        }

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

fun shareLocalFiles(context: Context, files: List<LocalFileItem>) {
    if (files.isEmpty()) return
    try {
        val uris = ArrayList<Uri>()
        for (file in files) {
            val uriStr = file.uri.toString()
            if (uriStr.startsWith("content://")) {
                uris.add(file.uri)
            } else {
                val path = file.uri.path ?: uriStr.removePrefix("file://")
                val f = java.io.File(path)
                if (f.exists()) {
                    val contentUri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "com.example.fileprovider",
                        f
                    )
                    uris.add(contentUri)
                }
            }
        }
        if (uris.isEmpty()) return

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                type = files.first().mimeType.ifEmpty { "*/*" }
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                type = "*/*"
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share files via"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share files: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun FileThumbnail(file: LocalFileItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(file.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val icon = remember(file.category) {
        when (file.category) {
            "Images" -> Icons.Default.Image
            "Videos" -> Icons.Default.VideoLibrary
            "Audio" -> Icons.Default.Audiotrack
            "Apps" -> Icons.Default.Android
            "Directory" -> Icons.Default.Folder
            else -> Icons.Default.InsertDriveFile
        }
    }

    LaunchedEffect(file.uri) {
        if (file.category == "Images" || file.category == "Videos") {
            withContext(Dispatchers.IO) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val size = android.util.Size(120, 120)
                        bitmap = context.contentResolver.loadThumbnail(file.uri, size, null)
                    } else {
                        val id = android.content.ContentUris.parseId(file.uri)
                        if (file.category == "Images") {
                            bitmap = MediaStore.Images.Thumbnails.getThumbnail(
                                context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
                            )
                        } else {
                            bitmap = MediaStore.Video.Thumbnails.getThumbnail(
                                context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FileThumbnail", "Failed to load thumbnail for ${file.uri}", e)
                }
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = file.name,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Icon(
            imageVector = icon,
            contentDescription = file.category,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.padding(8.dp)
        )
    }
}

// -------------------------------------------------------------
// -------------------------------------------------------------
// Settings Screen & Helper Components
// -------------------------------------------------------------
@Composable
fun SettingsScreen(
    currentTheme: String,
    onThemeChanged: (String) -> Unit,
    localDeviceName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    if (showDiagnosticsDialog) {
        ConnectionDiagnosticsDialog(onDismiss = { showDiagnosticsDialog = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("settings_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                color = MinTextPrimary
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Section: Appearance
            item {
                SettingsSectionHeader(title = "Appearance")
                Spacer(modifier = Modifier.height(8.dp))
                SettingsItemCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "Theme Icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Theme Preference",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MinTextPrimary
                                )
                                Text(
                                    text = "Choose light, dark, or sync with your system theme.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MinTextMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Selection list
                        val options = listOf(
                            "light" to "Light",
                            "dark" to "Dark",
                            "system" to "Follow System (recommended)"
                        )
                        
                        options.forEach { (value, label) ->
                            val isSelected = currentTheme == value
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onThemeChanged(value) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = MinTextPrimary
                                )
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onThemeChanged(value) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary,
                                        unselectedColor = MaterialTheme.colorScheme.outline
                                    ),
                                    modifier = Modifier.testTag("theme_radio_${value}")
                                )
                            }
                        }
                    }
                }
            }

            // Section: General
            item {
                SettingsSectionHeader(title = "General")
                Spacer(modifier = Modifier.height(8.dp))
                SettingsItemCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsRow(
                            icon = Icons.Default.PhoneAndroid,
                            title = "Device Name",
                            subtitle = localDeviceName
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                        SettingsRow(
                            icon = Icons.Default.FolderOpen,
                            title = "Default Save Location",
                            subtitle = "/Internal Storage/Flip"
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                        // Future-proof settings (disabled visuals)
                        FutureSettingRowInline(
                            icon = Icons.Default.VerifiedUser,
                            title = "Trusted Devices",
                            subtitle = "Manage automatically accepted devices"
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                        FutureSettingRowInline(
                            icon = Icons.Default.Tune,
                            title = "Transfer Preferences",
                            subtitle = "Optimize speed or connection stability"
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                        FutureSettingRowInline(
                            icon = Icons.Default.Notifications,
                            title = "Notifications",
                            subtitle = "Manage alerts for completed transfers"
                        )
                    }
                }
            }

            // Section: Privacy (Future-proof addition)
            item {
                SettingsSectionHeader(title = "Privacy")
                Spacer(modifier = Modifier.height(8.dp))
                SettingsItemCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        FutureSettingRowInline(
                            icon = Icons.Default.Security,
                            title = "Privacy",
                            subtitle = "Review Flip's minimal metadata policy"
                        )
                    }
                }
            }

            // Section: Developer & Connection Diagnostics
            item {
                SettingsSectionHeader(title = "Developer & Diagnostics")
                Spacer(modifier = Modifier.height(8.dp))
                SettingsItemCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDiagnosticsDialog = true }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = "Diagnostics",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Connection Diagnostics",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MinTextPrimary
                                )
                                Text(
                                    text = "Run end-to-end pipeline checks (Stages 1 - 10) & view report",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MinTextMuted
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Run Diagnostics",
                                tint = MinTextMuted
                            )
                        }
                    }
                }
            }

            // Section: About
            item {
                SettingsSectionHeader(title = "About Flip")
                Spacer(modifier = Modifier.height(8.dp))
                SettingsItemCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapHorizontalCircle,
                                contentDescription = "Logo",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "Flip",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = MinTextPrimary
                        )
                        Text(
                            text = "Version 1.1.0",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MinTextMuted
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "Fast local device-to-device transfer using Bluetooth Low Energy discovery and direct Wi-Fi connections.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MinTextMuted,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )
    )
}

@Composable
fun SettingsItemCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(20.dp),
        content = { content() }
    )
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MinTextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MinTextMuted
            )
        }
    }
}

@Composable
fun FutureSettingRowInline(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MinTextSubtle.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MinTextMuted
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MinTextSubtle
            )
        }
        Text(
            text = "Soon",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MinTextSubtle.copy(alpha = 0.6f),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// -------------------------------------------------------------
// Connection Diagnostics UI Component
// -------------------------------------------------------------
@Composable
fun ConnectionDiagnosticsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val diagnosticsManager = remember { com.example.service.ConnectionDiagnosticsManager(context) }

    val currentReport by diagnosticsManager.currentReport.collectAsState()
    val isRunning by diagnosticsManager.isRunning.collectAsState()
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(0) } // 0 = Stage Results, 1 = Raw Logs

    LaunchedEffect(Unit) {
        if (currentReport == null && !isRunning) {
            diagnosticsManager.runEndToEndDiagnostics()
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = "Diagnostics",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Connection Diagnostics",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = MinTextPrimary
                            )
                            Text(
                                text = if (isRunning) "Running pipeline checks..." else "End-to-End Pipeline Verification",
                                style = MaterialTheme.typography.bodySmall,
                                color = MinTextMuted
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("diag_close_button")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab Switcher: Stage Results vs Raw Logs
                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("Stage Results", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = { Text("Raw Logs (${currentReport?.logs?.size ?: 0})", fontWeight = FontWeight.Bold) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isRunning) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            Text(
                                text = "Testing Stages 1 through 10...",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MinTextPrimary
                            )
                        }
                    }
                } else if (activeTab == 0) {
                    val report = currentReport
                    if (report != null) {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(report.stageResults) { stage ->
                                DiagnosticStageCard(stage)
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No report generated yet.")
                        }
                    }
                } else {
                    val logs = currentReport?.logs ?: emptyList()
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs) { logLine ->
                            val color = when {
                                logLine.contains("✓") || logLine.contains("PASS") -> Color(0xFF4CAF50)
                                logLine.contains("✗") || logLine.contains("FAIL") -> Color(0xFFF44336)
                                logLine.contains("⚠") || logLine.contains("SKIPPED") -> Color(0xFFFF9800)
                                logLine.contains("[BLE]") -> Color(0xFF2196F3)
                                logLine.contains("[QR]") -> Color(0xFFE91E63)
                                logLine.contains("[HOTSPOT]") -> Color(0xFF9C27B0)
                                logLine.contains("[HANDSHAKE]") -> Color(0xFF00BCD4)
                                else -> Color(0xFFE0E0E0)
                            }
                            Text(
                                text = logLine,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = color
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            currentReport?.let { report ->
                                clipboardManager.setText(
                                    androidx.compose.ui.text.AnnotatedString(report.formatFinalReport())
                                )
                                Toast.makeText(context, "Final report copied to clipboard!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = currentReport != null && !isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("diag_copy_report_btn")
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Report")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                diagnosticsManager.runEndToEndDiagnostics()
                            }
                        },
                        enabled = !isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("diag_re_run_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Run Again")
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticStageCard(stage: com.example.service.DiagnosticStageResult) {
    var isExpanded by remember { mutableStateOf(stage.status == com.example.service.DiagnosticStatus.FAIL) }

    val (badgeBg, badgeFg, symbol) = when (stage.status) {
        com.example.service.DiagnosticStatus.PASS -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), "✓ PASS")
        com.example.service.DiagnosticStatus.FAIL -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "✗ FAIL")
        com.example.service.DiagnosticStatus.SKIPPED -> Triple(Color(0xFFFFF3E0), Color(0xFFEF6C00), "⚠ SKIPPED")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Stage ${stage.stageNumber}: ${stage.stageName}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MinTextPrimary
                )

                Surface(
                    color = badgeBg,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stage.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MinTextMuted
            )

            if (isExpanded && stage.details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(8.dp))
                stage.details.forEach { detail ->
                    Text(
                        text = "• $detail",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MinTextSubtle,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
