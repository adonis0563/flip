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
import android.provider.Settings
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.example.service.AutoJoinState
import com.example.service.PackageInstallerHelper
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.border
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SessionSheetStep {
    FULL_SCREEN,
    SEVENTY_PERCENT,
    MINIMISED
}

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

@OptIn(ExperimentalMaterial3Api::class)
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
    var showDebugScreen by remember { mutableStateOf(false) }
    var showDirectSender by remember { mutableStateOf(false) }
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
            if (connectionState == ConnectionState.CONNECTED) {
                items.forEach { file ->
                    viewModel.addFileToTransferQueue(file.uri, file.name, file.size)
                }
            } else {
                viewModel.preSelectFiles(items)
                if (connectionState == ConnectionState.IDLE) {
                    viewModel.startSenderMode()
                }
            }
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

    var sessionSheetStep by rememberSaveable { mutableStateOf(SessionSheetStep.FULL_SCREEN) }

    // Reset showDirectSender and session minimization on state changes
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.IDLE) {
            showDirectSender = false
        }
        if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.IDLE) {
            sessionSheetStep = SessionSheetStep.FULL_SCREEN
        }
    }

    val onboardingState by viewModel.onboardingState.collectAsState()
    val pendingPostOnboardingShare by viewModel.pendingPostOnboardingShare.collectAsState()
    val appSharePromptState by viewModel.appSharePromptState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (onboardingState) {
                is com.example.viewmodel.OnboardingState.Loading -> {
                    // System splash screen covers the window until loading completes
                }
                is com.example.viewmodel.OnboardingState.NotDone -> {
                    SplashScreen(
                        onGetStarted = { viewModel.setOnboardingDone() }
                    )
                }
                is com.example.viewmodel.OnboardingState.Done -> {
                    when (connectionState) {
                    ConnectionState.IDLE -> {
                        if (showSettings) {
                            SettingsScreen(
                                onBack = { showSettings = false }
                            )
                        } else if (showDebugScreen) {
                            WifiDirectDebugScreen(
                                onBack = { showDebugScreen = false }
                            )
                        } else if (showDirectSender) {
                            val directSenderPayloadUri by viewModel.directSenderPayloadUri.collectAsState()
                            WifiDirectSenderScreen(
                                localDevice = localDevice,
                                wifiDirectHost = viewModel.wifiDirectHost,
                                payloadUri = directSenderPayloadUri,
                                connectionState = connectionState,
                                remoteDevice = remoteDevice,
                                onClientConnected = { peerIp, peerId, peerName, port ->
                                    viewModel.onDirectClientConnected(peerIp, port = port, peerId = peerId, peerName = peerName)
                                    showDirectSender = false
                                },
                                onCancel = {
                                    showDirectSender = false
                                    viewModel.resetToHome()
                                }
                            )
                        } else if (showStandaloneFilePicker) {
                            InAppFilePickerContent(
                                onDismiss = { showStandaloneFilePicker = false },
                                onSendFiles = { selectedFiles ->
                                    viewModel.preSelectFiles(selectedFiles)
                                    viewModel.startSenderMode()
                                    showStandaloneFilePicker = false
                                },
                                onLaunchSystemPicker = {
                                    standaloneFilePickerLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        } else {
                            HomeScreen(
                                localDevice = localDevice,
                                pendingShareData = pendingPostOnboardingShare,
                                onSendPendingShare = {
                                    viewModel.sendPendingPostOnboardingShare()
                                },
                                onDismissPendingShare = {
                                    viewModel.dismissPendingPostOnboardingShare()
                                },
                                onDirectSendMode = {
                                    viewModel.startDirectSenderMode()
                                    showDirectSender = true
                                },
                                onSendMode = { viewModel.startSenderMode() },
                                onReceiveMode = { viewModel.startReceiverMode() },
                                onManualConnect = { ip, port -> viewModel.connectManually(ip, port) },
                                onExploreFiles = { showStandaloneFilePicker = true },
                                onOpenSettings = { showSettings = true },
                                onOpenDebug = { showDebugScreen = true }
                            )
                        }
                    }

                    ConnectionState.SEARCHING, ConnectionState.CONNECTING, ConnectionState.CONNECTED -> {
                        LaunchedEffect(connectionState) {
                            if (connectionState == ConnectionState.CONNECTED) {
                                showDirectSender = false
                            }
                        }

                        // Base content beneath the session sheet
                        if (showStandaloneFilePicker) {
                            InAppFilePickerContent(
                                onDismiss = { showStandaloneFilePicker = false },
                                onSendFiles = { selectedFiles ->
                                    if (connectionState == ConnectionState.CONNECTED) {
                                        selectedFiles.forEach { file ->
                                            viewModel.addFileToTransferQueue(file.uri, file.name, file.size)
                                        }
                                    } else {
                                        viewModel.preSelectFiles(selectedFiles)
                                    }
                                    showStandaloneFilePicker = false
                                },
                                onLaunchSystemPicker = {
                                    standaloneFilePickerLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        } else if (showSettings) {
                            SettingsScreen(
                                onBack = { showSettings = false }
                            )
                        } else {
                            HomeScreen(
                                localDevice = localDevice,
                                onDirectSendMode = {
                                    showDirectSender = true
                                },
                                onSendMode = { sessionSheetStep = SessionSheetStep.FULL_SCREEN },
                                onReceiveMode = { sessionSheetStep = SessionSheetStep.FULL_SCREEN },
                                onManualConnect = { ip, port -> viewModel.connectManually(ip, port) },
                                onExploreFiles = { showStandaloneFilePicker = true },
                                onOpenSettings = { showSettings = true },
                                onOpenDebug = { showDebugScreen = true }
                            )
                        }

                        // Dimmed scrim behind sheet when at 70%
                        if (sessionSheetStep == SessionSheetStep.SEVENTY_PERCENT) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.36f))
                                    .clickable { sessionSheetStep = SessionSheetStep.MINIMISED }
                            )
                        }

                        // 3-step Session Sheet (Full screen, 70%, or minimised - no additional buttons)
                        if (sessionSheetStep != SessionSheetStep.MINIMISED) {
                            val targetHeight = if (sessionSheetStep == SessionSheetStep.FULL_SCREEN) maxHeight else maxHeight * 0.70f
                            val animatedHeight by animateDpAsState(
                                targetValue = targetHeight,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "SessionSheetHeight"
                            )

                            BackHandler(enabled = !showStandaloneFilePicker) {
                                if (sessionSheetStep == SessionSheetStep.FULL_SCREEN) {
                                    sessionSheetStep = SessionSheetStep.SEVENTY_PERCENT
                                } else if (sessionSheetStep == SessionSheetStep.SEVENTY_PERCENT) {
                                    sessionSheetStep = SessionSheetStep.MINIMISED
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(animatedHeight)
                                    .align(Alignment.BottomCenter),
                                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 6.dp,
                                shadowElevation = 16.dp
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // Drag handle at top (drags between Full Screen, 70%, and Minimised)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .pointerInput(sessionSheetStep) {
                                                detectVerticalDragGestures { change, dragAmount ->
                                                    change.consume()
                                                    if (dragAmount > 20f) {
                                                        when (sessionSheetStep) {
                                                            SessionSheetStep.FULL_SCREEN -> sessionSheetStep = SessionSheetStep.SEVENTY_PERCENT
                                                            SessionSheetStep.SEVENTY_PERCENT -> sessionSheetStep = SessionSheetStep.MINIMISED
                                                            SessionSheetStep.MINIMISED -> {}
                                                        }
                                                    } else if (dragAmount < -20f) {
                                                        when (sessionSheetStep) {
                                                            SessionSheetStep.SEVENTY_PERCENT -> sessionSheetStep = SessionSheetStep.FULL_SCREEN
                                                            SessionSheetStep.MINIMISED -> sessionSheetStep = SessionSheetStep.SEVENTY_PERCENT
                                                            SessionSheetStep.FULL_SCREEN -> {}
                                                        }
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        BottomSheetDefaults.DragHandle()
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                    ) {
                                        if (connectionState == ConnectionState.CONNECTED) {
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
                                                onDisconnect = {
                                                    viewModel.disconnect()
                                                },
                                                onMinimize = {
                                                    sessionSheetStep = SessionSheetStep.MINIMISED
                                                }
                                            )

                                            androidx.compose.animation.AnimatedVisibility(
                                                visible = showConnectedSuccess,
                                                enter = fadeIn(tween(180)),
                                                exit = fadeOut(tween(300)) + scaleOut(targetScale = 0.96f)
                                            ) {
                                                ConnectedSuccessOverlay(
                                                    remoteDevice = remoteDevice,
                                                    onFinished = { showConnectedSuccess = false }
                                                )
                                            }
                                        } else if (role == "SENDER") {
                                            SenderWaitingScreen(
                                                localDevice = localDevice,
                                                onUpdateIp = { viewModel.updateLocalIpAddress(it) },
                                                onCancel = {
                                                    viewModel.resetToHome()
                                                }
                                            )
                                        } else {
                                            ReceiverScanningScreen(
                                                discoveredDevices = discoveredDevices,
                                                bleError = bleError,
                                                localDevice = localDevice,
                                                connectionState = connectionState,
                                                wifiDirectClient = viewModel.wifiDirectClient,
                                                autoJoinState = autoJoinState,
                                                onSelectDevice = { viewModel.connectToDiscoveredDevice(it) },
                                                onManualConnect = { ip, port -> viewModel.connectManually(ip, port) },
                                                onDirectReceiverConnected = { ownerIp, port, peerId, peerName, hsClient, onDone ->
                                                    viewModel.onDirectReceiverConnected(ownerIp, port, peerId, peerName, hsClient, onDone)
                                                },
                                                onHandshakeSuccess = { viewModel.setHandshakeClient(it) },
                                                onCancel = {
                                                    viewModel.resetToHome()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    ConnectionState.DISCONNECTED -> {
                        // Automatically clean up and return to Home
                        LaunchedEffect(Unit) {
                            sessionSheetStep = SessionSheetStep.FULL_SCREEN
                            showDirectSender = false
                            viewModel.resetToHome()
                        }
                    }
                }

                // Docked Session Mini Bar when minimized (tapping expands, close button disconnects directly without morphing)
                if (sessionSheetStep == SessionSheetStep.MINIMISED && (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.SEARCHING || connectionState == ConnectionState.CONNECTING)) {
                    val queueStatus = TransferMetricsHelper.computeQueueStatus(remoteDevice, transferQueue, connectionState, role)
                    SessionMiniBar(
                        line1Text = queueStatus.line1,
                        line2Text = queueStatus.line2,
                        activeProgress = queueStatus.progress,
                        isIncoming = queueStatus.isIncoming,
                        onExpand = { sessionSheetStep = SessionSheetStep.SEVENTY_PERCENT },
                        onDisconnect = {
                            if (connectionState == ConnectionState.CONNECTED) {
                                viewModel.disconnect()
                            } else {
                                viewModel.resetToHome()
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
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

                // App Share Option Sheet (Play Store link / bare package ID)
                appSharePromptState?.let { option ->
                    SharedAppOptionSheet(
                        option = option,
                        onSendApp = { viewModel.chooseSendSharedApp(option.appItem) },
                        onSendText = { viewModel.chooseSendSharedText(option.originalText) },
                        onDismiss = { viewModel.clearAppSharePrompt() }
                    )
                }
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

        Icon(
            painter = painterResource(R.drawable.ic_flip_logo),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(72.dp)
        )

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
                context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("onboarding_complete", true)
                    .commit()
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

fun getDocumentIconAndTint(file: FileEntry): Pair<ImageVector, Color> {
    val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val mime = file.mimeType.lowercase(Locale.ROOT)
    return when {
        ext == "pdf" || mime == "application/pdf" ->
            Icons.Default.PictureAsPdf to Color(0xFFE53935)
        ext in listOf("doc", "docx", "rtf", "odt") ||
            mime == "application/msword" ||
            mime.contains("wordprocessingml") ||
            mime.contains("opendocument.text") ->
            Icons.Default.Description to Color(0xFF1E88E5)
        ext in listOf("xls", "xlsx", "csv", "ods") ||
            mime.contains("ms-excel") ||
            mime.contains("spreadsheetml") ||
            mime.contains("opendocument.spreadsheet") ->
            Icons.Default.TableChart to Color(0xFF43A047)
        ext in listOf("ppt", "pptx", "odp") ||
            mime.contains("ms-powerpoint") ||
            mime.contains("presentationml") ||
            mime.contains("opendocument.presentation") ->
            Icons.Default.Slideshow to Color(0xFFFB8C00)
        ext in listOf("txt", "json", "xml", "html") ||
            mime.startsWith("text/") ||
            mime.contains("json") ||
            mime.contains("xml") ->
            Icons.Default.TextFields to Color(0xFF546E7A)
        ext in listOf("zip", "rar", "7z", "epub") ||
            mime.contains("zip") ||
            mime.contains("rar") ||
            mime.contains("7z") ->
            Icons.Default.FolderZip to Color(0xFF8E24AA)
        else ->
            Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFF5C6BC0)
    }
}

// -------------------------------------------------------------
// Home Screen Composable
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    localDevice: Device?,
    onSendMode: () -> Unit,
    onReceiveMode: () -> Unit,
    onManualConnect: (String, Int) -> Unit,
    onExploreFiles: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenDebug: () -> Unit = {},
    onDirectSendMode: () -> Unit = {},
    pendingShareData: com.example.viewmodel.PendingShareBannerData? = null,
    onSendPendingShare: () -> Unit = {},
    onDismissPendingShare: () -> Unit = {}
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

    var activeConnectionSheet by remember { mutableStateOf<ReadinessMode?>(null) }
    var showPcHotspotPrompt by remember { mutableStateOf(false) }
    var dontShowAgainChecked by remember { mutableStateOf(false) }

    val triggerConnectToPc = {
        if (com.example.service.PcHotspotReminderPreferences.shouldShowReminder(context)) {
            dontShowAgainChecked = false
            showPcHotspotPrompt = true
        } else {
            if (ConnectionChecker.isReadyForMode(context, ReadinessMode.HOTSPOT_PC)) {
                onSendMode()
            } else {
                activeConnectionSheet = ReadinessMode.HOTSPOT_PC
            }
        }
    }

    val triggerDirectSend = {
        if (ConnectionChecker.isReadyForMode(context, ReadinessMode.SEND)) {
            onDirectSendMode()
        } else {
            activeConnectionSheet = ReadinessMode.SEND
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section: TopBar, Device Card, Storage Card, Connect to PC
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top bar: 56dp height, centered Flip title, settings button on left
                HomeTopBar(
                    onOpenSettings = onOpenSettings,
                    onOpenDebug = onOpenDebug
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Single-line device card: device name, small status dot (green when on wifi)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
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
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Device Storage card
                StorageCard(
                    onViewDetails = onExploreFiles
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Row with two buttons under StorageCard: "Connect to PC" (primary) and "Share Flip" (disabled look; tapping shows "Coming soon")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = triggerConnectToPc,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("connect_to_pc_sub_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Laptop,
                            contentDescription = "Connect to PC",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Connect to PC",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("share_flip_button"),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Flip",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Share Flip",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Bottom CTA section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Big CTA buttons: "Send" & "Receive"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = triggerDirectSend,
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
                                imageVector = Icons.AutoMirrored.Filled.Send,
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
                                ),
                                textAlign = TextAlign.Center
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
                        text = "Files",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        pendingShareData?.let { data ->
            PostOnboardingShareBanner(
                data = data,
                onSend = {
                    onSendPendingShare()
                    if (ConnectionChecker.isReadyForMode(context, ReadinessMode.SEND)) {
                        onSendMode()
                    } else {
                        activeConnectionSheet = ReadinessMode.SEND
                    }
                },
                onDismiss = onDismissPendingShare,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }
    }

    // Modal bottom sheet prompting to connect PC and phone hotspots
    if (showPcHotspotPrompt) {
        ModalBottomSheet(
            onDismissRequest = { showPcHotspotPrompt = false },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Laptop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Text(
                    text = "Connect to PC",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Connect your PC to your phone's hotspot, or your phone to your PC's hotspot, before continuing.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { dontShowAgainChecked = !dontShowAgainChecked }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = dontShowAgainChecked,
                        onCheckedChange = { dontShowAgainChecked = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Don't show again",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showPcHotspotPrompt = false },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("pc_prompt_cancel_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            if (dontShowAgainChecked) {
                                com.example.service.PcHotspotReminderPreferences.setShowReminder(context, false)
                            }
                            showPcHotspotPrompt = false
                            if (ConnectionChecker.isReadyForMode(context, ReadinessMode.HOTSPOT_PC)) {
                                onSendMode()
                            } else {
                                activeConnectionSheet = ReadinessMode.HOTSPOT_PC
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("pc_prompt_continue_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("Continue")
                    }
                }
            }
        }
    }

    activeConnectionSheet?.let { mode ->
        ConnectionReadinessSheet(
            mode = mode,
            onDismiss = { activeConnectionSheet = null },
            onContinue = {
                val currentMode = activeConnectionSheet
                activeConnectionSheet = null
                if (currentMode == ReadinessMode.SEND) {
                    onDirectSendMode()
                } else if (currentMode == ReadinessMode.RECEIVE) {
                    onReceiveMode()
                } else if (currentMode == ReadinessMode.HOTSPOT_PC) {
                    onSendMode()
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

    var showQrFallback by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
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

                val connectionFlowState by com.example.service.FlipTransferForegroundService.connectionFlowState.collectAsState()

                DisposableEffect(Unit) {
                    com.example.service.FlipTransferForegroundService.startSenderControl(context)
                    onDispose {
                        com.example.service.FlipTransferForegroundService.teardownFirstTimeConnection()
                    }
                }

                val statusText = when (val s = connectionFlowState) {
                    is com.example.service.FirstTimeConnectionState.Idle -> "Waiting for receiver..."
                    is com.example.service.FirstTimeConnectionState.Joining -> "Receiver connecting..."
                    is com.example.service.FirstTimeConnectionState.AwaitingLink -> "Awaiting link..."
                    is com.example.service.FirstTimeConnectionState.Handshaking -> "Verifying receiver..."
                    is com.example.service.FirstTimeConnectionState.Connected -> "Connected!"
                    is com.example.service.FirstTimeConnectionState.Failed -> s.reason
                }
                val isFailed = connectionFlowState is com.example.service.FirstTimeConnectionState.Failed

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isFailed) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Failed",
                            tint = AccentRed,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (isFailed) AccentRed else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    if (isFailed) {
                        TextButton(
                            onClick = { com.example.service.FlipTransferForegroundService.startSenderControl(context) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Retry", style = MaterialTheme.typography.labelSmall)
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
                        if (showQrFallback) {
                            // QR Code Fallback
                            if (qrBitmap != null) {
                                Surface(
                                    color = Color.White,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .size(170.dp)
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
                                        .size(170.dp)
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
                                size = 170.dp,
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
                                "Receiver can scan this QR code within the Flip app, or connect using the code below:"
                            } else {
                                "Broadcasting via BLE. Nearby receivers will detect this device automatically."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        )

                        // Connection code
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
                    }
                }
            }

            // Bottom action anchored with spacing
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                                text = "Searching...",
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
                                        Text(
                                            text = "Tap to connect",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall
                                        )
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
                    text = "Connect with code",
                    style = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { newValue ->
                            manualCode = newValue.filter { it.isDigit() }
                        },
                        placeholder = { Text("Enter code", color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
                            val code = manualCode.trim().filter { it.isDigit() }
                            if (code.length >= 4) {
                                // Connect codes map to last octet + port
                                // e.g. Connect Code "58080" maps to IP segment: .5, Port: 8080.
                                val localIpStr = localDevice?.ip ?: ""
                                val basePrefix = if (localIpStr.contains(".") && localIpStr.split(".").size == 4) {
                                    val parts = localIpStr.split(".")
                                    "${parts[0]}.${parts[1]}.${parts[2]}."
                                } else {
                                    "192.168.49."
                                }
                                val portPart = code.takeLast(4).toIntOrNull() ?: 8080
                                val ipOctet = code.dropLast(4)
                                val targetIp = if (ipOctet.isNotEmpty()) "$basePrefix$ipOctet" else "${basePrefix}1"
                                onManualConnect(targetIp, portPart)
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
// FILES COMPONENTS
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

@Composable
fun DaySectionHeader(
    title: String,
    totalCount: Int,
    selectedCount: Int,
    onToggleSelectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.weight(1f)
        )

        // Select circle for date header with minimum 48dp touch target
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 22.dp),
                    onClick = onToggleSelectAll
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                // All selected -> filled check
                selectedCount == totalCount && totalCount > 0 -> {
                    Surface(
                        modifier = Modifier.size(22.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 1.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "All selected",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                // Some selected -> partly filled circle
                selectedCount in 1 until totalCount -> {
                    Surface(
                        modifier = Modifier.size(22.dp),
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
                // None selected -> empty circle
                else -> {
                    Surface(
                        modifier = Modifier.size(22.dp),
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {}
                }
            }
        }
    }
}

@Composable
fun MediaGridItem(
    file: FileEntry,
    isChecked: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideo = file.category == "Videos"

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = if (isChecked) 2.5.dp else 0.5.dp,
                color = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onOpen)
    ) {
        FileThumbnail(
            file = file,
            modifier = Modifier.fillMaxSize()
        )

        // Video indicator badge
        if (isVideo) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        // Selected item subtle dim overlay
        if (isChecked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
            )
        }

        // Small select circle in top-right corner with 48dp minimum touch target
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 20.dp),
                    onClick = onToggleSelect
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isChecked) {
                Surface(
                    modifier = Modifier.size(22.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.size(22.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.35f),
                    border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.85f)),
                    shadowElevation = 1.dp
                ) {}
            }
        }
    }
}

@Composable
fun FileListItemCard(
    file: FileEntry,
    selectedCategory: String,
    isChecked: Boolean,
    context: Context,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDirectory = file.isDirectory
    val isApp = file.category == "Apps"
    val isMedia = file.category == "Images" || file.category == "Videos"
    val isDocument = file.category == "Documents"

    val dateString = remember(file.dateModified) {
        if (file.dateModified > 0) {
            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            sdf.format(Date(file.dateModified * 1000L))
        } else {
            ""
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (isChecked) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isChecked) 2.dp else 1.dp,
            color = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
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
                } else if (isDocument) {
                    val (docIcon, docTint) = getDocumentIconAndTint(file)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = docTint.copy(alpha = 0.12f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = docIcon,
                                contentDescription = file.name,
                                tint = docTint,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
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

            // Select circle at the end of the row (or folder navigation arrow)
            if (!isDirectory) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 22.dp),
                            onClick = onToggleSelect
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isChecked) {
                        Surface(
                            modifier = Modifier.size(22.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            shadowElevation = 1.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.size(22.dp),
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        ) {}
                    }
                }
            } else {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Open Folder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalFoundationApi::class)
@Composable
fun InAppFilePickerContent(
    onDismiss: () -> Unit,
    onSendFiles: (List<LocalFileItem>) -> Unit,
    onLaunchSystemPicker: () -> Unit,
    selectedKeysState: MutableState<Set<String>> = remember { mutableStateOf(emptySet()) },
    clearSelectionOnBack: Boolean = true
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
    val currentGroupedFlow = remember(selectedCategory) { FileRepository.getGroupedCategoryFlow(selectedCategory) }
    val groupedList by (currentGroupedFlow ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val isLoadingMap by FileRepository.isLoadingMap.collectAsState()
    val isLoading = isLoadingMap[selectedCategory] == true
    var searchQuery by remember { mutableStateOf("") }
    
    // Directory navigation state
    var currentDirectory by remember { mutableStateOf(android.os.Environment.getExternalStorageDirectory() ?: java.io.File("/sdcard")) }
    
    // Multi-select state tracked strictly by canonical key
    var selectedKeys by selectedKeysState

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
                Toast.makeText(context, "Deleted ${entries.size} file${if (entries.size > 1) "s" else ""}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Deletion cancelled or permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingConsentEntries = null
    }

    // Proper back handler
    BackHandler(enabled = true) {
        if (clearSelectionOnBack && selectedKeys.isNotEmpty()) {
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
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
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
        }
    }

    // All files access state for Documents tab on Android 11+
    var hasAllFilesAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    true
                }
                if (granted != hasAllFilesAccess) {
                    hasAllFilesAccess = granted
                    if (granted) {
                        FileRepository.reloadDocuments(context)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
    
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Header
        if (selectedKeys.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = { selectedKeys = emptySet() }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Clear Selection", tint = AccentRed)
                    }
                    Text(
                        text = "${selectedKeys.size} selected",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { selectedKeys = emptySet() }) {
                        Text("Clear", style = MaterialTheme.typography.labelLarge)
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
                    val isGroupedCategory = selectedCategory in listOf("Images", "Videos", "Audio", "Documents")
                    val isGridCategory = selectedCategory in listOf("Images", "Videos")

                    val displayFiles = remember(filesList, searchQuery) {
                        if (searchQuery.isBlank()) {
                            filesList
                        } else {
                            filesList.filter { it.name.contains(searchQuery, ignoreCase = true) }
                        }
                    }

                    val displayGroups = remember(filesList, searchQuery, groupedList, selectedCategory) {
                        if (!isGroupedCategory) {
                            emptyList()
                        } else if (searchQuery.isBlank()) {
                            groupedList
                        } else {
                            FileRepository.groupFilesByDay(displayFiles)
                        }
                    }

                    val isEmpty = if (isGroupedCategory) displayGroups.isEmpty() else displayFiles.isEmpty()
                    
                    if (isEmpty) {
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
                    } else if (isGridCategory) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            displayGroups.forEach { group ->
                                val groupKeys = group.files.map { it.key }.toSet()
                                val selectedCount = group.files.count { it.key in selectedKeys }
                                item(
                                    key = group.dayKey,
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    DaySectionHeader(
                                        title = group.headerTitle,
                                        totalCount = group.files.size,
                                        selectedCount = selectedCount,
                                        onToggleSelectAll = {
                                            selectedKeys = if (selectedCount == group.files.size) {
                                                selectedKeys - groupKeys
                                            } else {
                                                selectedKeys + groupKeys
                                            }
                                        }
                                    )
                                }
                                items(group.files, key = { it.key }) { file ->
                                    val isChecked = selectedKeys.contains(file.key)
                                    MediaGridItem(
                                        file = file,
                                        isChecked = isChecked,
                                        onOpen = { openLocalFile(context, file) },
                                        onToggleSelect = {
                                            selectedKeys = if (isChecked) selectedKeys - file.key else selectedKeys + file.key
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (selectedCategory == "Apps") {
                                item(key = "apps_pref_card") {
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
                                item(key = "storage_info_card") {
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
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess) {
                                    item(key = "documents_perm_card") {
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 12.dp)
                                                .testTag("documents_permission_card"),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            ),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Description,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Text(
                                                        text = "Allow access to documents",
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    )
                                                }
                                                Text(
                                                    text = "On Android 11+, browsing PDF, Office, and text documents requires All Files Access. You can grant access or pick specific documents manually.",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                )
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Button(
                                                        onClick = {
                                                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                                data = Uri.parse("package:${context.packageName}")
                                                            }
                                                            try {
                                                                context.startActivity(intent)
                                                            } catch (_: Exception) {
                                                                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                                                try {
                                                                    context.startActivity(fallback)
                                                                } catch (e: Exception) {
                                                                    Toast.makeText(context, "Cannot open settings: ${e.message}", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f).testTag("allow_documents_button"),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ) {
                                                        Text("Allow access")
                                                    }
                                                    OutlinedButton(
                                                        onClick = { safDocumentsLauncher.launch(arrayOf("*/*")) },
                                                        modifier = Modifier.weight(1f).testTag("pick_documents_button"),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ) {
                                                        Text("Pick documents")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    item(key = "documents_pick_card") {
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
                                                    contentDescription = "Pick Documents",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Pick Documents",
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
                            }

                            if (isGroupedCategory) {
                                displayGroups.forEach { group ->
                                    val groupKeys = group.files.map { it.key }.toSet()
                                    val selectedCount = group.files.count { it.key in selectedKeys }
                                    item(key = group.dayKey) {
                                        DaySectionHeader(
                                            title = group.headerTitle,
                                            totalCount = group.files.size,
                                            selectedCount = selectedCount,
                                            onToggleSelectAll = {
                                                selectedKeys = if (selectedCount == group.files.size) {
                                                    selectedKeys - groupKeys
                                                } else {
                                                    selectedKeys + groupKeys
                                                }
                                            }
                                        )
                                    }
                                    items(group.files, key = { it.key }) { file ->
                                        val isChecked = selectedKeys.contains(file.key)
                                        FileListItemCard(
                                            file = file,
                                            selectedCategory = selectedCategory,
                                            isChecked = isChecked,
                                            context = context,
                                            onOpen = { openLocalFile(context, file) },
                                            onToggleSelect = {
                                                selectedKeys = if (isChecked) selectedKeys - file.key else selectedKeys + file.key
                                            }
                                        )
                                    }
                                }
                            } else {
                                items(displayFiles, key = { it.key }) { file ->
                                    val isChecked = selectedKeys.contains(file.key)
                                    val isDirectory = file.isDirectory
                                    FileListItemCard(
                                        file = file,
                                        selectedCategory = selectedCategory,
                                        isChecked = isChecked,
                                        context = context,
                                        onOpen = {
                                            if (selectedCategory == "Storage" && isDirectory) {
                                                currentDirectory = java.io.File(file.displayUri.path ?: "")
                                            } else {
                                                openLocalFile(context, file)
                                            }
                                        },
                                        onToggleSelect = {
                                            if (!isDirectory) {
                                                selectedKeys = if (isChecked) selectedKeys - file.key else selectedKeys + file.key
                                            }
                                        }
                                    )
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
                        val allCategoryFiles = FileRepository.images.value +
                            FileRepository.videos.value +
                            FileRepository.audio.value +
                            FileRepository.documents.value +
                            FileRepository.apps.value +
                            FileRepository.storage.value
                        val selectedEntries = if (allCategoryFiles.any { selectedKeys.contains(it.key) }) {
                            allCategoryFiles.filter { selectedKeys.contains(it.key) }.distinctBy { it.key }
                        } else {
                            filesList.filter { selectedKeys.contains(it.key) }
                        }
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
        val title = "Delete $count file${if (count > 1) "s" else ""}?"
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
                                    Toast.makeText(context, "Deleted $deleted file${if (deleted > 1) "s" else ""}", Toast.LENGTH_SHORT).show()
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
// TRANSFER METRICS HELPER (Speed & ETA calculation)
// -------------------------------------------------------------
object TransferMetricsHelper {
    private class MetricEntry(
        var lastBytes: Long = 0L,
        var lastTimeMillis: Long = 0L,
        var speedBytesPerSec: Long = 0L,
        var smoothedSpeed: Double = 0.0
    )

    private val metricsMap = java.util.concurrent.ConcurrentHashMap<String, MetricEntry>()

    // Sliding window of queue transmission samples for 5-second smoothed speed
    private data class SpeedSample(val timestamp: Long, val totalBytes: Long)
    private val queueSamples = java.util.Collections.synchronizedList(mutableListOf<SpeedSample>())
    private var queueTransferStartTime = 0L
    private var lastRecordedTransferred = -1L

    fun computeSpeedAndEta(transferId: String, currentBytes: Long, totalBytes: Long): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val entry = metricsMap.computeIfAbsent(transferId) {
            MetricEntry(currentBytes, now, 0L, 0.0)
        }

        val elapsed = now - entry.lastTimeMillis
        if (elapsed >= 500L) {
            val bytesDelta = (currentBytes - entry.lastBytes).coerceAtLeast(0L)
            val instantSpeed = (bytesDelta * 1000.0) / elapsed.toDouble()
            entry.smoothedSpeed = if (entry.smoothedSpeed == 0.0) instantSpeed else (entry.smoothedSpeed * 0.7 + instantSpeed * 0.3)
            entry.speedBytesPerSec = entry.smoothedSpeed.toLong()
            entry.lastBytes = currentBytes
            entry.lastTimeMillis = now
        }

        val speed = entry.speedBytesPerSec
        val remainingBytes = (totalBytes - currentBytes).coerceAtLeast(0L)
        val etaSeconds = if (speed > 1024L) remainingBytes / speed else 0L

        return Pair(speed, etaSeconds)
    }

    data class QueueStatus(
        val line1: String,
        val line2: String,
        val progress: Float?,
        val isIncoming: Boolean,
        val speed: Long = 0L,
        val timePart: String = ""
    )

    fun computeQueueStatus(
        remoteDevice: Device?,
        transferQueue: List<TransferItem>,
        connectionState: ConnectionState,
        role: String? = null
    ): QueueStatus {
        val peerName = remoteDevice?.name ?: "Peer"
        if (connectionState == ConnectionState.SEARCHING || connectionState == ConnectionState.CONNECTING) {
            val line1 = if (role == "SENDER") "Waiting for receiver..." else "Connecting to $peerName..."
            val line2 = if (role == "SENDER") "Wi-Fi Direct · Searching" else "Wi-Fi Direct · Connecting"
            return QueueStatus(line1, line2, null, false)
        }
        if (connectionState != ConnectionState.CONNECTED) {
            return QueueStatus("Disconnected", "Tap to reconnect", null, false)
        }

        val line1 = "Connected to $peerName"
        val totalFiles = transferQueue.size
        if (totalFiles == 0) {
            return QueueStatus(line1, "Ready", null, false)
        }

        val completedCount = transferQueue.count { it.status == TransferStatus.COMPLETE }
        val activeTransfer = transferQueue.find { it.status == TransferStatus.SENDING }

        if (activeTransfer == null) {
            queueSamples.clear()
            queueTransferStartTime = 0L
            lastRecordedTransferred = -1L

            return if (completedCount == totalFiles && totalFiles > 0) {
                QueueStatus(line1, "All $totalFiles files sent", 1f, false)
            } else {
                val pendingCount = transferQueue.count { it.status == TransferStatus.QUEUED }
                val pausedCount = transferQueue.count { it.status == TransferStatus.PAUSED }
                val line2 = when {
                    pausedCount > 0 -> "Transfer paused · $completedCount of $totalFiles complete"
                    pendingCount > 0 -> "${completedCount + 1} of $totalFiles files queued"
                    else -> "$totalFiles file${if (totalFiles > 1) "s" else ""}"
                }
                QueueStatus(line1, line2, null, false)
            }
        }

        // Actively transmitting
        val now = System.currentTimeMillis()
        if (queueTransferStartTime == 0L) {
            queueTransferStartTime = now
        }

        var transferredBytes = 0L
        var totalBytes = 0L
        for (item in transferQueue) {
            totalBytes += item.fileSize
            transferredBytes += when (item.status) {
                TransferStatus.COMPLETE -> item.fileSize
                TransferStatus.SENDING -> item.bytesTransferred
                else -> item.bytesTransferred
            }
        }

        val overallPercent = if (totalBytes > 0L) {
            ((transferredBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val overallProgress = if (totalBytes > 0L) {
            (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        val remainingBytes = (totalBytes - transferredBytes).coerceAtLeast(0L)

        // 5-second sliding window
        synchronized(queueSamples) {
            if (transferredBytes != lastRecordedTransferred || queueSamples.isEmpty() || now - queueSamples.last().timestamp >= 250L) {
                queueSamples.add(SpeedSample(now, transferredBytes))
                lastRecordedTransferred = transferredBytes
            }
            val cutoff = now - 5000L
            while (queueSamples.isNotEmpty() && queueSamples.first().timestamp < cutoff) {
                queueSamples.removeAt(0)
            }
        }

        val activeDuration = now - queueTransferStartTime
        val (speed, timePart) = if (activeDuration < 3000L) {
            Pair(0L, "Calculating...")
        } else {
            val (timeDiff, bytesDiff) = synchronized(queueSamples) {
                if (queueSamples.size >= 2) {
                    val first = queueSamples.first()
                    val last = queueSamples.last()
                    Pair(last.timestamp - first.timestamp, (last.totalBytes - first.totalBytes).coerceAtLeast(0L))
                } else {
                    Pair(0L, 0L)
                }
            }
            val spd = if (timeDiff >= 500L) (bytesDiff * 1000L) / timeDiff else 0L
            if (spd < 1024L) {
                Pair(spd, "—")
            } else {
                val etaSeconds = remainingBytes / spd
                Pair(spd, "${formatEta(etaSeconds)} left")
            }
        }

        val currentFileIndex = (completedCount + 1).coerceAtMost(totalFiles)
        val line2 = "$currentFileIndex of $totalFiles · $overallPercent% · $timePart"

        return QueueStatus(
            line1 = line1,
            line2 = line2,
            progress = overallProgress,
            isIncoming = activeTransfer.isIncoming,
            speed = speed,
            timePart = timePart
        )
    }

    fun clear(transferId: String) {
        metricsMap.remove(transferId)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec <= 0L -> "-- B/s"
            bytesPerSec < 1024L -> "$bytesPerSec B/s"
            bytesPerSec < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
            else -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }
    }

    fun formatEta(etaSeconds: Long): String {
        return when {
            etaSeconds <= 0L -> "< 1s"
            etaSeconds < 60L -> "${etaSeconds}s"
            etaSeconds < 3600L -> "${etaSeconds / 60L}m ${etaSeconds % 60L}s"
            else -> "${etaSeconds / 3600L}h ${(etaSeconds % 3600L) / 60L}m"
        }
    }
}

// -------------------------------------------------------------
// SESSION MINI BAR & STATUS FORMATTER (2-LINE TABULAR NUMERALS)
// -------------------------------------------------------------
@Composable
fun SessionMiniBar(
    line1Text: String,
    line2Text: String,
    activeProgress: Float?,
    isIncoming: Boolean = false,
    onExpand: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(onClick = onExpand)
            .testTag("session_mini_bar"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (activeProgress != null) {
                                if (isIncoming) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward
                            } else {
                                Icons.Default.Check
                            },
                            contentDescription = "Session Status",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = line1Text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = line2Text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFeatureSettings = "tnum",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("minibar_disconnect_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Disconnect Session",
                        tint = AccentRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (activeProgress != null) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { activeProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
            }
        }
    }
}

// -------------------------------------------------------------
// POST-ONBOARDING SHARE BANNER
// -------------------------------------------------------------
@Composable
fun PostOnboardingShareBanner(
    data: com.example.viewmodel.PendingShareBannerData,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val titleText = if (data.items.size == 1) {
        "${data.items.first().name} ready to send"
    } else if (data.items.size > 1) {
        "${data.items.size} files ready to send"
    } else {
        "Text link ready to send"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("post_onboarding_share_banner"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Pending Files",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Text("Dismiss")
                }
                Button(
                    onClick = onSend,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Send", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// -------------------------------------------------------------
// SHARED APP OPTION SHEET (Play Store link / package ID intake)
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedAppOptionSheet(
    option: com.example.viewmodel.AppShareOption,
    onSendApp: () -> Unit,
    onSendText: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Share Options",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )

            // Option 1: Send App
            Surface(
                onClick = onSendApp,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().testTag("send_app_option")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val app = option.appItem
                    if (app.appIcon != null) {
                        val iconBitmap = remember(app.packageName) {
                            try {
                                val d = app.appIcon
                                val b = android.graphics.Bitmap.createBitmap(
                                    d.intrinsicWidth.coerceAtLeast(48),
                                    d.intrinsicHeight.coerceAtLeast(48),
                                    android.graphics.Bitmap.Config.ARGB_8888
                                )
                                val c = android.graphics.Canvas(b)
                                d.setBounds(0, 0, c.width, c.height)
                                d.draw(c)
                                b.asImageBitmap()
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (iconBitmap != null) {
                            Image(
                                bitmap = iconBitmap,
                                contentDescription = app.name,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Android,
                                contentDescription = "App Icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.Android,
                            contentDescription = "App Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Send the app ${app.name.removeSuffix(".apk").removeSuffix(".apks")}",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatSize(app.size),
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            if (app.isSplitApk) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "Prepared as .apks",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Option 2: Send Text
            Surface(
                onClick = onSendText,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().testTag("send_text_option")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Text",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Send Text",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Text(
                            text = option.originalText,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// -------------------------------------------------------------
// CONNECTED SCREEN (Active Session and File Transfer Queue)
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedScreen(
    remoteDevice: Device?,
    transferQueue: List<TransferItem>,
    onSendFile: (Uri, String, Long) -> Unit,
    onSendText: (String) -> Unit,
    onPauseItem: (String) -> Unit,
    onResumeItem: (String) -> Unit,
    onCancelItem: (String) -> Unit,
    onDisconnect: () -> Unit,
    onMinimize: () -> Unit = {}
) {
    var shareText by remember { mutableStateOf("") }
    val context = LocalContext.current
    var showInAppFilePicker by remember { mutableStateOf(false) }
    val pickerSelectedKeysState = remember { mutableStateOf<Set<String>>(emptySet()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    BackHandler(enabled = !showInAppFilePicker) {
        onMinimize()
    }

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
        ModalBottomSheet(
            onDismissRequest = { showInAppFilePicker = false },
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            modifier = Modifier.fillMaxHeight(),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            InAppFilePickerContent(
                onDismiss = { showInAppFilePicker = false },
                onSendFiles = { selectedFiles ->
                    selectedFiles.forEach { file ->
                        onSendFile(file.uri, file.name, file.size)
                    }
                    pickerSelectedKeysState.value = emptySet()
                    showInAppFilePicker = false
                },
                onLaunchSystemPicker = {
                    filePicker.launch(arrayOf("*/*"))
                    showInAppFilePicker = false
                },
                selectedKeysState = pickerSelectedKeysState,
                clearSelectionOnBack = false
            )
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connected Header (Stable non-morphing layout, no additional minimize button)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Connected Session",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = remoteDevice?.name ?: "Remote Peer",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentRed.copy(alpha = 0.12f),
                        contentColor = AccentRed
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier
                        .height(38.dp)
                        .defaultMinSize(minWidth = 96.dp)
                        .testTag("disconnect_button")
                ) {
                    Text("Disconnect", fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }

        // Global Universal Progress & Speed Card for Active Transfers
        val activeTransfer = transferQueue.find { it.status == TransferStatus.SENDING }
        if (activeTransfer != null) {
            val queueStatus = TransferMetricsHelper.computeQueueStatus(
                remoteDevice = remoteDevice,
                transferQueue = transferQueue,
                connectionState = ConnectionState.CONNECTED
            )
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (queueStatus.speed > 0L) {
                                Text(
                                    text = TransferMetricsHelper.formatSpeed(queueStatus.speed),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "${((queueStatus.progress ?: 0f) * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (queueStatus.timePart.isNotBlank() && queueStatus.timePart != "—") {
                                Text(
                                    text = "(${queueStatus.timePart})",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { activeTransfer.progress },
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
                        Text("Send files", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
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
                .heightIn(min = 160.dp, max = 340.dp),
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
                            .heightIn(min = 120.dp),
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
    val isSending = item.status == TransferStatus.SENDING
    val (_, eta) = if (isSending) {
        TransferMetricsHelper.computeSpeedAndEta(item.id, item.bytesTransferred, item.fileSize)
    } else {
        Pair(0L, 0L)
    }
    
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                    if (isSending && eta > 0) {
                        Text(
                            text = "•",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "${TransferMetricsHelper.formatEta(eta)} left",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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
                        val isApp = item.fileName.endsWith(".apk", ignoreCase = true) || item.fileName.endsWith(".apks", ignoreCase = true)
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
                                Icon(
                                    imageVector = if (isApp) Icons.Default.GetApp else Icons.Default.Launch,
                                    contentDescription = if (isApp) "Install app" else "Open file",
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (isApp) "Install" else "Open",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
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
                    progress = { item.progress },
                    color = progressColor,
                    trackColor = progressColor.copy(alpha = 0.15f),
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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
}

// Byte utility formatter
fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

// Open file utility using FileProvider or PackageInstallerHelper for APKs
fun openFile(context: Context, fileName: String) {
    try {
        val isApp = fileName.endsWith(".apk", ignoreCase = true) || fileName.endsWith(".apks", ignoreCase = true)
        val file = if (isApp) {
            val appReceivedDir = StorageService.getAppReceivedDirectory(context)
            val inReceived = java.io.File(appReceivedDir, fileName)
            if (inReceived.exists()) {
                inReceived
            } else {
                val inApks = java.io.File(java.io.File(StorageService.getFlipDirectory(), "Received/APKs"), fileName)
                if (inApks.exists()) inApks else java.io.File(StorageService.getFlipDirectory(), fileName)
            }
        } else {
            java.io.File(StorageService.getFlipDirectory(), fileName)
        }
        if (!file.exists()) {
            Toast.makeText(context, "File not found: $fileName", Toast.LENGTH_SHORT).show()
            return
        }

        if (isApp) {
            PackageInstallerHelper.install(context, file)
            return
        }

        val authority = "com.example.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        
        val mime = context.contentResolver.getType(uri) ?: when (file.extension.lowercase()) {
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
        val isApp = file.name.endsWith(".apk", ignoreCase = true) || file.name.endsWith(".apks", ignoreCase = true)
        if (isApp) {
            val ioFile = when {
                file.displayUri.scheme == "file" -> file.displayUri.path?.let { java.io.File(it) }
                file.key.startsWith("file:") -> java.io.File(file.key.removePrefix("file:"))
                else -> {
                    val appReceived = java.io.File(StorageService.getAppReceivedDirectory(context), file.name)
                    if (appReceived.exists()) appReceived else java.io.File(StorageService.getFlipDirectory(), file.name)
                }
            }
            if (ioFile != null && ioFile.exists()) {
                PackageInstallerHelper.install(context, ioFile)
                return
            }
        }

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

// -------------------------------------------------------------
// Layout Previews across 5 screen configurations
// -------------------------------------------------------------
@Preview(name = "Home - Small Phone", widthDp = 320, heightDp = 640)
@Preview(name = "Home - Tall 20:9 Phone", widthDp = 360, heightDp = 800)
@Preview(name = "Home - 21:9 Phone", widthDp = 360, heightDp = 860)
@Preview(name = "Home - Tablet", widthDp = 800, heightDp = 1280)
@Preview(name = "Home - Font Scale 1.3", widthDp = 360, heightDp = 800, fontScale = 1.3f)
@Composable
private fun HomeScreenPreview() {
    MyApplicationTheme {
        Surface {
            HomeScreen(
                localDevice = Device(id = "1", name = "Pixel 8 · 4A", ip = "192.168.49.1", port = 8080, flags = 0x01.toByte()),
                onSendMode = {},
                onReceiveMode = {},
                onManualConnect = { _, _ -> },
                onExploreFiles = {}
            )
        }
    }
}

@Preview(name = "Sender - Small Phone", widthDp = 320, heightDp = 640)
@Preview(name = "Sender - Tall 20:9 Phone", widthDp = 360, heightDp = 800)
@Preview(name = "Sender - 21:9 Phone", widthDp = 360, heightDp = 860)
@Preview(name = "Sender - Tablet", widthDp = 800, heightDp = 1280)
@Preview(name = "Sender - Font Scale 1.3", widthDp = 360, heightDp = 800, fontScale = 1.3f)
@Composable
private fun SenderWaitingScreenPreview() {
    MyApplicationTheme {
        Surface {
            SenderWaitingScreen(
                localDevice = Device(id = "1", name = "Pixel 8 · 4A", ip = "192.168.49.1", port = 8080, flags = 0x01.toByte()),
                onUpdateIp = {},
                onCancel = {}
            )
        }
    }
}

@Preview(name = "Transfer - Small Phone", widthDp = 320, heightDp = 640)
@Preview(name = "Transfer - Tall 20:9 Phone", widthDp = 360, heightDp = 800)
@Preview(name = "Transfer - 21:9 Phone", widthDp = 360, heightDp = 860)
@Preview(name = "Transfer - Tablet", widthDp = 800, heightDp = 1280)
@Preview(name = "Transfer - Font Scale 1.3", widthDp = 360, heightDp = 800, fontScale = 1.3f)
@Composable
private fun ConnectedScreenPreview() {
    MyApplicationTheme {
        Surface {
            ConnectedScreen(
                remoteDevice = Device(id = "2", name = "Galaxy S24 · 9B", ip = "192.168.49.2", port = 8080, flags = 0x01.toByte()),
                transferQueue = listOf(
                    TransferItem(
                        id = "tx-1",
                        fileName = "photo_vacation.jpg",
                        fileSize = 4_200_000L,
                        bytesTransferred = 2_730_000L,
                        status = TransferStatus.SENDING,
                        isIncoming = false
                    )
                ),
                onSendFile = { _, _, _ -> },
                onSendText = {},
                onPauseItem = {},
                onResumeItem = {},
                onCancelItem = {},
                onDisconnect = {}
            )
        }
    }
}

@Preview(name = "MiniBar - Small Phone Font Scale 1.3", widthDp = 320, fontScale = 1.3f)
@Composable
private fun SessionMiniBarPreview() {
    MyApplicationTheme {
        Surface {
            SessionMiniBar(
                line1Text = "Connected to Galaxy S24",
                line2Text = "2 of 5 · 43% · 12s left",
                activeProgress = 0.43f,
                isIncoming = false,
                onExpand = {},
                onDisconnect = {}
            )
        }
    }
}

