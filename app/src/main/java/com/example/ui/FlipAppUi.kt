package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.service.StorageService
import com.example.service.LocalFileItem
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

    val context = LocalContext.current

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

    if (showSplash) {
        SplashScreen(
            onGetStarted = { showSplash = false }
        )
    } else {
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
                when (connectionState) {
                    ConnectionState.IDLE -> {
                        HomeScreen(
                            localDevice = localDevice,
                            onSendMode = { viewModel.startSenderMode() },
                            onReceiveMode = { viewModel.startReceiverMode() },
                            onManualConnect = { ip, port -> viewModel.connectManually(ip, port) },
                            onExploreFiles = { showStandaloneFilePicker = true }
                        )
                    }

                    ConnectionState.SEARCHING, ConnectionState.CONNECTING -> {
                        BackHandler {
                            viewModel.resetToHome()
                        }
                        if (role == "SENDER") {
                            SenderWaitingScreen(
                                localDevice = localDevice,
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
                        BackHandler {
                            viewModel.disconnect()
                        }
                        ConnectedScreen(
                            viewModel = viewModel,
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

                if (showStandaloneFilePicker) {
                    InAppFilePickerContent(
                        viewModel = viewModel,
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
                .size(160.dp)
                .clip(RoundedCornerShape(32.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 2.dp
        ) {
            Image(
                painter = painterResource(id = R.drawable.flip_logo),
                contentDescription = "Flip Logo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
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
                contentColor = Color.White
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
// Home Screen Composable
// -------------------------------------------------------------
@Composable
fun HomeScreen(
    localDevice: Device?,
    onSendMode: () -> Unit,
    onReceiveMode: () -> Unit,
    onManualConnect: (String, Int) -> Unit,
    onExploreFiles: () -> Unit
) {
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8080") }
    var showPrecheckDialogForSend by remember { mutableStateOf(false) }
    var showPrecheckDialogForReceive by remember { mutableStateOf(false) }
    var showNoNetworkDialog by remember { mutableStateOf(false) }
    var showMobileDataDialog by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                onClick = { showPrecheckDialogForSend = true },
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp)
                    .testTag("send_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Send Icon",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Send",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }
            }

            Button(
                onClick = { showPrecheckDialogForReceive = true },
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp)
                    .testTag("receive_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Receive Icon",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Receive",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
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
                text = "In-App File Explorer",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Direct Connect manual card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {
                Text(
                    text = "Direct Connection Fallback",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        placeholder = { Text("Receiver IP (e.g. 192.168.1.5)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_ip_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = MinTextPrimary,
                            unfocusedTextColor = MinTextPrimary
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )

                    Button(
                        onClick = {
                            val port = manualPort.toIntOrNull() ?: 8080
                            if (manualIp.isNotBlank()) {
                                onManualConnect(manualIp.trim(), port)
                            }
                        },
                        modifier = Modifier
                            .height(56.dp)
                            .testTag("direct_connect_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Connect",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    if (showPrecheckDialogForSend) {
        AlertDialog(
            onDismissRequest = { showPrecheckDialogForSend = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsInputAntenna,
                        contentDescription = "Connection Check",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Enable Connections", fontWeight = FontWeight.Bold, color = MinTextPrimary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "For maximum speed and efficiency, let this phone create a local Wi-Fi network for the transfer.",
                        color = MinTextMuted
                    )
                    Text(
                        text = "1. Turn on Mobile Hotspot on this phone.\n2. Have the receiver connect to your Hotspot's Wi-Fi.\n3. Keep Bluetooth on so they can pair automatically.",
                        color = MinTextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Current IP: ${localDevice?.ip ?: "Unknown"}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPrecheckDialogForSend = false
                        val ip = localDevice?.ip ?: "127.0.0.1"
                        if (ip == "127.0.0.1" || ip.startsWith("127.0.0") || ip.isBlank()) {
                            showNoNetworkDialog = true
                        } else {
                            onSendMode()
                        }
                    }
                ) {
                    Text("My Hotspot is Active", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrecheckDialogForSend = false }) {
                    Text("Cancel", color = MinTextSubtle)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showPrecheckDialogForReceive) {
        AlertDialog(
            onDismissRequest = { showPrecheckDialogForReceive = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsInputAntenna,
                        contentDescription = "Connection Check",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Enable Connections", fontWeight = FontWeight.Bold, color = MinTextPrimary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Please connect this phone to the sender's local Wi-Fi or Mobile Hotspot before proceeding.",
                        color = MinTextMuted
                    )
                    Text(
                        text = "1. Turn on Wi-Fi on this phone.\n2. Connect to the sender's Mobile Hotspot network.\n3. Make sure Bluetooth is enabled.",
                        color = MinTextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Current IP: ${localDevice?.ip ?: "Unknown"}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPrecheckDialogForReceive = false
                        val ip = localDevice?.ip ?: "127.0.0.1"
                        if (ip == "127.0.0.1" || ip.startsWith("127.0.0") || ip.isBlank()) {
                            showNoNetworkDialog = true
                        } else {
                            onReceiveMode()
                        }
                    }
                ) {
                    Text("I'm Connected to Hotspot", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrecheckDialogForReceive = false }) {
                    Text("Cancel", color = MinTextSubtle)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
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
    onUpdateIp: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val pairingPayload = "flip://ip=${localDevice?.ip}&port=${localDevice?.port}&id=${localDevice?.id}&name=${localDevice?.name}"

    // Generate local QR Code
    val qrBitmap = remember(pairingPayload) {
        QrCodeGenerator.generateQrCode(pairingPayload)
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
                            color = if (isSelected) Color.White else MinTextMuted
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
    var manualCode by remember { mutableStateOf("") }
    var showScanOverlay by remember { mutableStateOf(false) }

    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    LaunchedEffect(showScanOverlay) {
        if (showScanOverlay && !cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
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
                                        try {
                                            val ip = qrResult.substringAfter("ip=").substringBefore("&")
                                            val port = qrResult.substringAfter("port=").substringBefore("&").toIntOrNull() ?: 8080
                                            onManualConnect(ip, port)
                                            showScanOverlay = false
                                        } catch (e: Exception) {
                                            // Ignore
                                        }
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
                            // Auto-extract URI connections
                            if (it.startsWith("flip://")) {
                                try {
                                    val ip = it.substringAfter("ip=").substringBefore("&")
                                    val port = it.substringAfter("port=").substringBefore("&").toIntOrNull() ?: 8080
                                    onManualConnect(ip, port)
                                } catch (e: Exception) {
                                    // Ignore
                                }
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
    viewModel: FlipViewModel,
    onDismiss: () -> Unit,
    onSendFiles: (List<LocalFileItem>) -> Unit,
    onLaunchSystemPicker: () -> Unit
) {
    val context = LocalContext.current
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            listOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }
    
    val multiplePermissionsState = rememberMultiplePermissionsState(permissions = permissionsToRequest)
    val isPermissionGranted = remember(multiplePermissionsState.permissions) {
        multiplePermissionsState.permissions.any { it.status.isGranted }
    }
    
    // Scanning status state
    var isScanning by remember { mutableStateOf(false) }
    var scanStatusText by remember { mutableStateOf("") }
    var scanProgress by remember { mutableStateOf(0f) }
    var refreshTrigger by remember { mutableStateOf(0) }
    
    // ✅ FIX: Observe ViewModel's refresh trigger for instant auto-update
    val viewModelRefreshTrigger by viewModel.refreshTrigger.collectAsState(initial = Unit)
    LaunchedEffect(viewModelRefreshTrigger) {
        refreshTrigger++
    }
    
    val scope = rememberCoroutineScope()

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
            val queried = if (isPermissionGranted) {
                when (selectedCategory) {
                    "Apps" -> StorageService.queryInstalledApps(context, showSystemApps = showSystemApps)
                    "Storage" -> StorageService.listDirectoryFiles(currentDirectory)
                    // ✅ FIX: Use StorageService.queryMediaFiles directly to fix duplicates and missing documents
                    "Images", "Videos", "Audio", "Documents" -> {
                        val mediaFiles = StorageService.queryMediaFiles(context, selectedCategory)
                        if (selectedCategory == "Documents") {
                            (safDocuments + mediaFiles).distinctBy { it.uri.toString() }
                        } else {
                            mediaFiles
                        }
                    }
                    else -> emptyList()
                }
            } else {
                if (selectedCategory == "Documents") safDocuments else emptyList()
            }
            
            withContext(Dispatchers.Main) {
                filesList = queried
                isLoading = false
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
                
                if (selectedFiles.isNotEmpty()) {
                    Button(
                        onClick = {
                            handleSendAction(selectedFiles.toList())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Send", style = MaterialTheme.typography.labelMedium)
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
                        text = "In-App Explorer",
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
                
                TextButton(
                    onClick = onLaunchSystemPicker,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "System")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("System Picker")
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
        
        // Actions Footer
        if (selectedFiles.isNotEmpty()) {
            Button(
                onClick = {
                    handleSendAction(selectedFiles.toList())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Send ${selectedFiles.size} Selected Files",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
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
}

// -------------------------------------------------------------
// CONNECTED SCREEN (Active Session and File Transfer Queue)
// -------------------------------------------------------------
@Composable
fun ConnectedScreen(
    viewModel: FlipViewModel,
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
            viewModel = viewModel,
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
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(imageVector = Icons.Default.AttachFile, contentDescription = "Pick File", tint = Color.White)
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
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send Text", tint = Color.White)
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
