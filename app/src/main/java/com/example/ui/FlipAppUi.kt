package com.example.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.service.AutoJoinState
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
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
import com.example.model.FileEntry
import com.example.viewmodel.IncomingFileRequest
import com.example.repository.FileRepository
import android.util.LruCache
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
    val pendingIncomingRequest by viewModel.pendingIncomingRequest.collectAsState()
    val pendingShareRequest by viewModel.pendingShareRequest.collectAsState()
    val autoJoinState by viewModel.autoJoinState.collectAsState()

    val context = LocalContext.current

    var showStandaloneFilePicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showConnectedSuccess by remember { mutableStateOf(false) }

    // Android 13+ Notification permission request rationale
    var showNotificationRationaleDialog by remember { mutableStateOf(false) }
    var hasPromptedNotificationPermission by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                context,
                "Notifications disabled. Incoming files will prompt inside the app.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            showConnectedSuccess = true
            if (!hasPromptedNotificationPermission) {
                hasPromptedNotificationPermission = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasPerm) {
                        showNotificationRationaleDialog = true
                    }
                }
            }
        } else {
            showConnectedSuccess = false
        }
    }

    val openExplorerRequested by viewModel.openExplorerEvent.collectAsState()
    LaunchedEffect(openExplorerRequested) {
        if (openExplorerRequested) {
            showStandaloneFilePicker = true
            viewModel.consumeOpenExplorer()
        }
    }

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
            if (showSplash) {
                SplashScreen(
                    onGetStarted = { showSplash = false }
                )
            } else {
                when (connectionState) {
                    ConnectionState.IDLE -> {
                        if (showSettings) {
                            SettingsScreen(
                                onBack = { showSettings = false }
                            )
                        } else {
                            HomeScreen(
                                localDevice = localDevice,
                                onSendMode = { viewModel.startSenderMode() },
                                onReceiveMode = { viewModel.startReceiverMode() },
                                onManualConnect = { ip, port -> viewModel.connectManually(ip, port) },
                                onExploreFiles = { showStandaloneFilePicker = true },
                                onRenameDevice = { viewModel.updateDeviceName(it) },
                                onOpenSettings = { showSettings = true }
                            )
                        }
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
                                autoJoinState = autoJoinState,
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

                        AnimatedVisibility(
                            visible = showConnectedSuccess,
                            enter = fadeIn(tween(180)),
                            exit = fadeOut(tween(300)) + scaleOut(targetScale = 0.96f)
                        ) {
                            ConnectedSuccessOverlay(
                                remoteDevice = remoteDevice,
                                onFinished = { showConnectedSuccess = false }
                            )
                        }
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

                // In-App Incoming File Transfer Request Prompt (when app is in foreground)
                pendingIncomingRequest?.let { req ->
                    val sizeStr = if (req.fileSize > 0) android.text.format.Formatter.formatFileSize(context, req.fileSize) else ""
                    val subtitle = if (sizeStr.isNotEmpty()) "${req.fileName} ($sizeStr)" else req.fileName
                    AlertDialog(
                        onDismissRequest = { viewModel.declineIncomingRequest(req.transferId) },
                        title = {
                            Text(
                                text = "${req.senderName} wants to send",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Accept this incoming file transfer?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.acceptIncomingRequest(req.transferId) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text("Accept", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { viewModel.declineIncomingRequest(req.transferId) },
                                colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                            ) {
                                Text("Decline", color = AccentRed)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                // Notification Permission Rationale Dialog
                if (showNotificationRationaleDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showNotificationRationaleDialog = false
                            Toast.makeText(
                                context,
                                "Notifications disabled. Incoming files will prompt inside the app.",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        title = {
                            Text(
                                text = "Enable Notifications",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        text = {
                            Text(
                                text = "Flip uses notifications to keep transfers active in the background and let you accept incoming files without keeping the app open.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showNotificationRationaleDialog = false
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text("Allow", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showNotificationRationaleDialog = false
                                    Toast.makeText(
                                        context,
                                        "Notifications disabled. Incoming files will prompt inside the app.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                            ) {
                                Text("Not Now", color = AccentRed)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                // Share Confirmation Sheet (when sharing from another app while connected)
                pendingShareRequest?.let { req ->
                    ShareConfirmationSheet(
                        request = req,
                        onConfirm = { viewModel.confirmPendingShare() },
                        onCancel = { viewModel.cancelPendingShare() }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Don't show this again",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
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
    onExploreFiles: () -> Unit,
    onRenameDevice: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    var isOnWifi by remember {
        mutableStateOf(run {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        })
    }

    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                isOnWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
            override fun onLost(network: Network) {
                val activeNetwork = cm?.activeNetwork
                val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
                isOnWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            override fun onAvailable(network: Network) {
                val caps = cm?.getNetworkCapabilities(network)
                isOnWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }
        try {
            cm?.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            // Safe fallback if network callback cannot be registered
        }
        onDispose {
            try {
                cm?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember(localDevice?.name) { mutableStateOf(localDevice?.name ?: "") }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Text(
                    text = "Rename Device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Device Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("device_rename_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameInput.trim()
                        if (trimmed.isNotEmpty()) {
                            onRenameDevice(trimmed)
                        }
                        showRenameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("device_rename_save_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRenameDialog = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = AccentRed
                    )
                ) {
                    Text("Cancel", color = AccentRed)
                }
            }
        )
    }

    var activeConnectionSheet by remember { mutableStateOf<ReadinessMode?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar: 56dp height, centered Flip title, settings button on left
        HomeTopBar(onOpenSettings = onOpenSettings)

        Spacer(modifier = Modifier.height(12.dp))

        // Single-line device card: device name, small status dot (green when on wifi), tap to rename
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    renameInput = localDevice?.name ?: ""
                    showRenameDialog = true
                }
                .testTag("device_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = localDevice?.name ?: "Android Device",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (isOnWifi) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                )

                Spacer(modifier = Modifier.weight(1f, fill = true))

                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Rename device",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device Storage card
        StorageCard(
            onViewDetails = onExploreFiles
        )

        Spacer(modifier = Modifier.weight(0.8f))

        Text(
            text = "Ready to flip?",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
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
                    if (ConnectionChecker.isReadyForMode(context, ReadinessMode.SEND)) {
                        onSendMode()
                    } else {
                        activeConnectionSheet = ReadinessMode.SEND
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
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Send",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            Button(
                onClick = {
                    if (ConnectionChecker.isReadyForMode(context, ReadinessMode.RECEIVE)) {
                        onReceiveMode()
                    } else {
                        activeConnectionSheet = ReadinessMode.RECEIVE
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp)
                    .testTag("receive_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Receive Icon",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Receive",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
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
                contentColor = MaterialTheme.colorScheme.onBackground
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Explore Files",
                tint = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "In-App File Explorer",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }

    activeConnectionSheet?.let { mode ->
        ConnectionReadinessSheet(
            mode = mode,
            onDismiss = { activeConnectionSheet = null },
            onContinue = {
                val currentMode = activeConnectionSheet
                activeConnectionSheet = null
                if (currentMode == ReadinessMode.SEND) {
                    onSendMode()
                } else if (currentMode == ReadinessMode.RECEIVE) {
                    onReceiveMode()
                }
            }
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
    val pairingPayload = "flip://ip=${localDevice?.ip}&port=${localDevice?.port}&id=${localDevice?.id}&name=${localDevice?.name}&sid=${localDevice?.sid ?: com.example.service.DeviceIdentity.currentSessionId}"

    // Generate local QR Code
    val qrBitmap = remember(pairingPayload) {
        QrCodeGenerator.generateQrCode(pairingPayload)
    }

    // Connect code: last segment of IP + port
    val connectCode = remember(localDevice) {
        val lastSegment = localDevice?.ip?.substringAfterLast(".") ?: "0"
        "$lastSegment${localDevice?.port ?: "8080"}"
    }

    var selectedQrTab by remember { mutableStateOf(0) } // 0 = App Pairing, 1 = First time
    var showQrFallback by remember { mutableStateOf(false) }
    var showIpEditDialog by remember { mutableStateOf(false) }
    var editingIp by remember { mutableStateOf(localDevice?.ip ?: "") }

    if (showIpEditDialog) {
        AlertDialog(
            onDismissRequest = { showIpEditDialog = false },
            title = { Text("Correct Server IP", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "If your detected IP is wrong, correct it here so receivers can connect over Wi-Fi and BLE.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = editingIp,
                        onValueChange = { editingIp = it },
                        label = { Text("Local IP Address") },
                        placeholder = { Text("e.g. 192.168.43.1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
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
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Update & Restart BLE", color = MaterialTheme.colorScheme.onPrimary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showIpEditDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                ) {
                    Text("Cancel", color = AccentRed)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            listOf("App Pairing", "First time").forEachIndexed { index, title ->
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
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }

        // Beautiful Card presenting Animation or QR Code fallback and Details
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
                    if (showQrFallback) {
                        // QR Code Fallback
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

                        // Text link to switch back to animation
                        TextButton(
                            onClick = { showQrFallback = false },
                            modifier = Modifier.testTag("hide_qr_fallback_link")
                        ) {
                            Text(
                                text = "Hide QR",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    } else {
                        // Pulsing Hotspot / Radio animation
                        PulsingRadioAnimation(
                            modifier = Modifier.padding(vertical = 4.dp),
                            size = 190.dp,
                            waveColor = MaterialTheme.colorScheme.primary
                        )

                        // Small "Show QR instead" text link
                        TextButton(
                            onClick = { showQrFallback = true },
                            modifier = Modifier.testTag("show_qr_fallback_link")
                        ) {
                            Text(
                                text = "Show QR instead",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }

                    Text(
                        text = if (showQrFallback) {
                            "Receiver can scan this QR code within the Flip app, or connect using the info below:"
                        } else {
                            "Broadcasting via BLE. Nearby receivers will detect this device automatically."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    )

                    // 4-Digit Connection code
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "CONNECT CODE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 2.sp
                                )
                            )
                            Text(
                                text = connectCode,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                } else {
                    // Tab 2: "First time" Gated Hotspot Tab
                    FirstTimeHotspotTab(
                        localDevice = localDevice,
                        onRequestReturnToAppPairing = { message ->
                            selectedQrTab = 0
                            if (message.isNotBlank()) {
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("cancel_advertising_button"),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AccentRed
            ),
            border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                "Cancel", 
                color = AccentRed, 
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ReceiverScanningScreen(
    discoveredDevices: List<Device>,
    bleError: String?,
    localDevice: Device?,
    autoJoinState: AutoJoinState = AutoJoinState.Idle,
    onSelectDevice: (Device) -> Unit,
    onManualConnect: (String, Int) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
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

        // Auto-join status banners
        when (autoJoinState) {
            is AutoJoinState.Joining -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                        Column {
                            Text(
                                text = "Joining ${autoJoinState.peerName}'s hotspot...",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = autoJoinState.message,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            is AutoJoinState.Handshake -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Connecting to ${autoJoinState.peerName}...",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            is AutoJoinState.Disconnected -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Disconnected",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = autoJoinState.reason ?: "Connection lost, hotspot may have turned off",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            is AutoJoinState.Failed -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Connection Failed",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = autoJoinState.reason,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (autoJoinState.needsRePair) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showScanOverlay = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Re-pair with device", color = MaterialTheme.colorScheme.onError)
                            }
                        }
                    }
                }
            }
            else -> {}
        }

        if (showScanOverlay) {
            PairingScannerDialog(
                onDismiss = { showScanOverlay = false },
                onLegacyConnect = { ip, port ->
                    onManualConnect(ip, port)
                }
            )
        }

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
                            color = MaterialTheme.colorScheme.onSurface
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
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(discoveredDevices) { device ->
                            val pairedPeer = remember(device.id) {
                                com.example.service.HotspotCredentialStore.getPairedPeer(context, device.id)
                            }

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
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (pairedPeer != null) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = if (pairedPeer.needsRePair) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                ) {
                                                    Text(
                                                        text = if (pairedPeer.needsRePair) "Needs re-pair" else "Paired · ${pairedPeer.shortCode}",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                        color = if (pairedPeer.needsRePair) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        } else {
                                            Text(
                                                text = "Tap to connect",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = "Connect",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
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
                        placeholder = { Text("Enter 4-Digit Code", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_code_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
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

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("cancel_scanning_button"),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AccentRed
            ),
            border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                "Cancel", 
                color = AccentRed, 
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
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
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
    
    // Category files observed from app-scoped FileRepository
    val currentCategoryFlow = remember(selectedCategory) { FileRepository.getCategoryFlow(selectedCategory) }
    val filesList by currentCategoryFlow.collectAsState()
    val isLoadingMap by FileRepository.isLoadingMap.collectAsState()
    val isLoading = isLoadingMap[selectedCategory] == true
    var searchQuery by remember { mutableStateOf("") }
    
    // Directory navigation state
    var currentDirectory by remember { mutableStateOf(android.os.Environment.getExternalStorageDirectory() ?: java.io.File("/sdcard")) }
    
    // Multi-select state tracked strictly by canonical key
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }

    // Deletion states and Scoped Storage consent launcher
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var entriesToDelete by remember { mutableStateOf<List<com.example.model.FileEntry>>(emptyList()) }
    var pendingConsentEntries by remember { mutableStateOf<List<com.example.model.FileEntry>?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingConsentEntries?.let { entries ->
                val keys = entries.map { it.key }.toSet()
                com.example.repository.FileRepository.purgeKeys(keys)
                selectedKeys = selectedKeys - keys
                if (selectedKeys.isEmpty()) isSelectionMode = false
                Toast.makeText(context, "Deleted ${entries.size} file(s)", Toast.LENGTH_SHORT).show()
            }
        }
        pendingConsentEntries = null
    }

    // Proper back handler
    BackHandler(enabled = true) {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedKeys = emptySet()
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

    // SAF Selected/Imported Documents launcher
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
            FileRepository.addSafDocuments(context, resolved)
            val resolvedKeys = resolved.map { FileRepository.toFileEntry(context, it).key }
            selectedKeys = selectedKeys + resolvedKeys
            isSelectionMode = true
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
    // Preload repository when permission is granted
    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted) {
            FileRepository.preload(context)
        }
    }
    
    // Load directory files when browsing Storage
    LaunchedEffect(selectedCategory, currentDirectory, isPermissionGranted) {
        if (isPermissionGranted && selectedCategory == "Storage") {
            FileRepository.loadStorageDirectory(context, currentDirectory)
        }
    }

    // Load apps with system app preference
    LaunchedEffect(selectedCategory, showSystemApps, isPermissionGranted) {
        if (isPermissionGranted && selectedCategory == "Apps") {
            FileRepository.loadApps(context, showSystemApps = showSystemApps)
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
                        selectedKeys = emptySet()
                    }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel Selection", tint = AccentRed)
                    }
                    Text(
                        text = "${selectedKeys.size} selected",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
                
                if (selectedKeys.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val selectedEntries = filesList.filter { selectedKeys.contains(it.key) }
                                entriesToDelete = selectedEntries
                                showDeleteConfirmDialog = true
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Selected",
                                tint = AccentRed,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Button(
                            onClick = {
                                val selectedEntries = filesList.filter { selectedKeys.contains(it.key) }
                                handleSendAction(selectedEntries.map { it.transferSource })
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
                            color = MaterialTheme.colorScheme.onSurface
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
            }
        }
        
        // Search Bar for Real-time Filename Filtering
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            placeholder = { Text("Search files by name...", style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) },
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
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
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
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
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                color = MaterialTheme.colorScheme.onSurface
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
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                )
                                                Text(
                                                    text = "Include system packages in the applications list",
                                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                )
                                                Text(
                                                    text = "Save extracted APKs in internal storage after transfer complete",
                                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            )
                                            Text(
                                                text = "$usedStr / $totalStr used",
                                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 
                        items(displayFiles, key = { it.key }) { file ->
                            val isChecked = selectedKeys.contains(file.key)
                            val isDirectory = file.isDirectory
                            val isApp = file.category == "Apps"
                            
                            val dateString = remember(file.dateModified) {
                                if (file.dateModified > 0) {
                                    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                                    sdf.format(Date(file.dateModified * 1000L))
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
                                                    val newSelected = if (isChecked) selectedKeys - file.key else selectedKeys + file.key
                                                    selectedKeys = newSelected
                                                    if (newSelected.isEmpty()) {
                                                        isSelectionMode = false
                                                    }
                                                }
                                            } else {
                                                if (selectedCategory == "Storage" && isDirectory) {
                                                    currentDirectory = java.io.File(file.displayUri.path ?: "")
                                                } else {
                                                    openLocalFile(context, file)
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode && !isDirectory) {
                                                isSelectionMode = true
                                                selectedKeys = setOf(file.key)
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
                                                    color = MaterialTheme.colorScheme.onSurface
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
                                                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Text(
                                                        text = "Version: $versionStr • Size: $sizeStr",
                                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                     )
                                                }
                                            } else if (!isDirectory) {
                                                val sizeStr = android.text.format.Formatter.formatFileSize(context, file.size)
                                                val subText = if (dateString.isNotEmpty()) "$sizeStr • $dateString" else sizeStr
                                                Text(
                                                    text = subText,
                                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                )
                                            } else {
                                                Text(
                                                    text = "Folder",
                                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                        selectedKeys + file.key
                                                    } else {
                                                        selectedKeys - file.key
                                                    }
                                                    selectedKeys = newSelected
                                                    if (newSelected.isEmpty()) {
                                                        isSelectionMode = false
                                                    }
                                                }
                                            )
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                if (!isApp && selectedCategory != "Apps") {
                                                    IconButton(
                                                        onClick = {
                                                            entriesToDelete = listOf(file)
                                                            showDeleteConfirmDialog = true
                                                        },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "Delete File",
                                                            tint = AccentRed.copy(alpha = 0.75f),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }

                                                FilledTonalButton(
                                                    onClick = {
                                                        if (isApp) {
                                                            handleSendAction(listOf(file.transferSource))
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
    }
        
        // Actions Footer
        if (selectedKeys.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val selectedEntries = filesList.filter { selectedKeys.contains(it.key) }
                        entriesToDelete = selectedEntries
                        showDeleteConfirmDialog = true
                    },
                    modifier = Modifier
                        .weight(0.35f)
                        .height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                    border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = AccentRed, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", color = AccentRed, style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = {
                        val selectedEntries = filesList.filter { selectedKeys.contains(it.key) }
                        handleSendAction(selectedEntries.map { it.transferSource })
                    },
                    modifier = Modifier
                        .weight(0.65f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Send (${selectedKeys.size})",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
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
                        color = MaterialTheme.colorScheme.onSurface
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
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    if (showDeleteConfirmDialog && entriesToDelete.isNotEmpty()) {
        val count = entriesToDelete.size
        val title = if (count == 1) "Delete File?" else "Delete $count Files?"
        val message = if (count == 1) {
            "Are you sure you want to permanently delete \"${entriesToDelete.first().name}\"? This action cannot be undone."
        } else {
            "Are you sure you want to permanently delete these $count selected files? This action cannot be undone."
        }

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                entriesToDelete = emptyList()
            },
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            text = {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        val targets = entriesToDelete
                        entriesToDelete = emptyList()
                        when (val result = com.example.repository.FileRepository.deleteFilesWithConsent(context, targets)) {
                            is com.example.repository.FileRepository.DeleteResult.Success -> {
                                val deleted = result.deletedCount
                                if (deleted > 0) {
                                    val deletedKeySet = targets.map { it.key }.toSet()
                                    selectedKeys = selectedKeys - deletedKeySet
                                    if (selectedKeys.isEmpty()) isSelectionMode = false
                                    Toast.makeText(context, "Deleted $deleted file(s)", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Could not delete file(s)", Toast.LENGTH_SHORT).show()
                                }
                            }
                            is com.example.repository.FileRepository.DeleteResult.RequiresConsent -> {
                                pendingConsentEntries = result.pendingEntries
                                try {
                                    consentLauncher.launch(
                                        IntentSenderRequest.Builder(result.intentSender).build()
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e("FilePicker", "Failed to launch delete consent", e)
                                    Toast.makeText(context, "Permission required to delete", Toast.LENGTH_SHORT).show()
                                }
                            }
                            is com.example.repository.FileRepository.DeleteResult.Error -> {
                                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                ) {
                    Text("Delete", color = AccentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        entriesToDelete = emptyList()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                ) {
                    Text("Cancel", color = AccentRed)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedCircularCheckmark(
                    size = 32.dp,
                    ringColor = Color(0xFF34C759),
                    checkColor = Color.White,
                    ringWidth = 2.dp,
                    checkWidth = 2.5.dp
                )
                Column {
                    Text(
                        text = "Connected Session",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Text(
                        text = remoteDevice?.name ?: "Remote Peer",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
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
                                color = MaterialTheme.colorScheme.onSurface
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
                    style = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
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
                        Icon(imageVector = Icons.Default.AttachFile, contentDescription = "Pick File", tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send File", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                // Text flip row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = shareText,
                        onValueChange = { shareText = it },
                        placeholder = { Text("Type text/link to Flip...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("text_input_field"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
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
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send Text", tint = MaterialTheme.colorScheme.onSecondary)
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
                        color = MaterialTheme.colorScheme.onSurface
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No active transfers",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        TransferStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = MaterialTheme.colorScheme.onSurface,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "•",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = formatSize(item.fileSize),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Text("Cancelled", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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

private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
private val cacheSizeKb = (maxMemoryKb / 8).coerceAtLeast(1024 * 16)

// In-memory cache for thumbnails (~1/8 of available memory) so scrolling doesn't reload them
private val thumbnailCache = object : LruCache<String, android.graphics.Bitmap>(cacheSizeKb) {
    override fun sizeOf(key: String, bitmap: android.graphics.Bitmap): Int {
        return (bitmap.byteCount / 1024).coerceAtLeast(1)
    }
}

// Open local/selected file utility for FileEntry using displayUri
fun openLocalFile(context: Context, file: FileEntry) {
    try {
        var uri = file.displayUri
        val mimeType = file.mimeType.ifEmpty {
            context.contentResolver.getType(uri) ?: when (file.name.substringAfterLast('.', "").lowercase()) {
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
            val ioFile = java.io.File(uri.path ?: "")
            if (ioFile.exists()) {
                val authority = "com.example.fileprovider"
                uri = androidx.core.content.FileProvider.getUriForFile(context, authority, ioFile)
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

// Overload for LocalFileItem backward compatibility
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
fun FileThumbnail(file: FileEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isMedia = file.category == "Images" || file.category == "Videos"
    val cacheKey = file.displayUri.toString()
    var bitmap by remember(file.displayUri) { mutableStateOf(thumbnailCache.get(cacheKey)) }

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

    if (isMedia) {
        LaunchedEffect(file.displayUri) {
            if (bitmap == null) {
                withContext(Dispatchers.IO) {
                    try {
                        val loaded = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            val size = android.util.Size(120, 120)
                            context.contentResolver.loadThumbnail(file.displayUri, size, null)
                        } else {
                            val id = android.content.ContentUris.parseId(file.displayUri)
                            if (file.category == "Images") {
                                MediaStore.Images.Thumbnails.getThumbnail(
                                    context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
                                )
                            } else {
                                MediaStore.Video.Thumbnails.getThumbnail(
                                    context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
                                )
                            }
                        }
                        if (loaded != null) {
                            thumbnailCache.put(cacheKey, loaded)
                            withContext(Dispatchers.Main) {
                                bitmap = loaded
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.v("FileThumbnail", "Failed to load thumbnail for ${file.displayUri}: ${e.message}")
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = file.category,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
