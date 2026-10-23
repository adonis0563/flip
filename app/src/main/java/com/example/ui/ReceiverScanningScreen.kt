package com.example.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Device
import com.example.service.AutoJoinState
import com.example.service.ClientFailureReason
import com.example.service.ClientResult
import com.example.service.DeviceIdentity
import com.example.service.ParseDetailedResult
import com.example.service.WifiDirectClient
import com.example.service.WifiDirectPayload
import com.example.service.WifiDirectPayloadParser
import com.example.service.control.ControlChannelConstants
import com.example.service.control.HandshakeClient
import com.example.service.control.HandshakeResult
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ReceiverScan"

private enum class ReceiverScreenMode {
    DEVICE_LIST,
    CONNECTING,
    ERROR
}

/**
 * Receiver screen managing available devices list, Wi-Fi Direct connection,
 * QR scanner, and error states according to Flip Engineering Rules.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ReceiverScanningScreen(
    discoveredDevices: List<Device>,
    bleError: String?,
    localDevice: Device?,
    connectionState: com.example.viewmodel.ConnectionState = com.example.viewmodel.ConnectionState.SEARCHING,
    wifiDirectClient: WifiDirectClient,
    autoJoinState: AutoJoinState = AutoJoinState.Idle,
    onSelectDevice: (Device) -> Unit,
    onManualConnect: (String, Int) -> Unit,
    onDirectReceiverConnected: ((ownerIp: String, port: Int, peerId: String, peerName: String, handshakeClient: HandshakeClient?, onDone: (Boolean, String?) -> Unit) -> Unit)? = null,
    onHandshakeSuccess: ((HandshakeClient?) -> Unit)? = null,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Screen State
    var screenMode by remember { mutableStateOf(ReceiverScreenMode.DEVICE_LIST) }
    var selectedSender by remember { mutableStateOf<Device?>(null) }
    var activePayload by remember { mutableStateOf<WifiDirectPayload?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var showScanOverlay by remember { mutableStateOf(false) }
    var scannerTitle by remember { mutableStateOf("Scan Sender's QR Code") }
    var manualCode by remember { mutableStateOf("") }
    var joinJob by remember { mutableStateOf<Job?>(null) }

    // 1-second ticker for dropping devices older than 10s
    var currentTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000L)
            currentTimeMs = System.currentTimeMillis()
        }
    }

    // Filter available senders: only those with ready bit (bit 0 = 1) and seen within 10s
    val activeSenders by remember(discoveredDevices, currentTimeMs) {
        derivedStateOf {
            discoveredDevices.filter { device ->
                val isReady = (device.flags.toInt() and 0x01) != 0
                val notExpired = (currentTimeMs - device.lastSeen) <= 10_000L
                isReady && notExpired
            }
        }
    }

    // Permission launcher for missing Wi-Fi Direct permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.i(TAG, "[PERMISSIONS] Permission request results: $results")
    }

    // Camera permission for general scanner
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    LaunchedEffect(showScanOverlay) {
        if (showScanOverlay && !cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    // Formatter: name · short code
    fun formatSenderTitle(device: Device): String {
        val shortCode = when {
            device.id.length <= 4 -> device.id.uppercase()
            device.id.length == 8 -> device.id.take(4).uppercase()
            else -> DeviceIdentity.getShortCodeFromUuid(device.id)
        }
        return if (device.name.contains("·")) {
            device.name
        } else {
            "${device.name} · $shortCode"
        }
    }

    fun formatFailureReason(reason: ClientFailureReason): String = when (reason) {
        is ClientFailureReason.ParseFailed -> "ParseFailed (${reason.field})"
        is ClientFailureReason.Expired -> "Expired (expiry: ${reason.expiryEpochSeconds}, current: ${reason.currentEpochSeconds})"
        is ClientFailureReason.PermissionMissing -> "PermissionMissing: ${reason.permissions.joinToString()}"
        is ClientFailureReason.ConfigBuildFailed -> "ConfigBuildFailed: ${reason.message}"
        is ClientFailureReason.ConnectRejected -> "ConnectRejected(${reason.code}): ${reason.name}"
        is ClientFailureReason.Timeout -> "Timeout: Connection timed out"
        is ClientFailureReason.Unsupported -> "Unsupported: Wi-Fi Direct not supported on this device"
        is ClientFailureReason.SpecifierFailed -> "SpecifierFailed: ${reason.message}"
        is ClientFailureReason.Error -> "Error(${reason.code}): ${reason.message}"
    }

    // Join Flow Implementation
    fun initiateJoin(payload: WifiDirectPayload, sender: Device?) {
        val missingPermissions = WifiDirectClient.getMissingPermissions(context)
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "[WFD_CLIENT] Missing permissions before join: $missingPermissions")
            permissionLauncher.launch(missingPermissions.toTypedArray())
            errorMessage = "PermissionMissing: ${missingPermissions.joinToString()}"
            screenMode = ReceiverScreenMode.ERROR
            return
        }

        activePayload = payload
        selectedSender = sender
        screenMode = ReceiverScreenMode.CONNECTING

        joinJob?.cancel()
        joinJob = scope.launch {
            Log.i(TAG, "[WFD_CLIENT] Calling WifiDirectClient.join for group: ${payload.groupName}")
            val result = withContext(Dispatchers.IO) {
                wifiDirectClient.join(payload)
            }

            if (!isActive) {
                Log.i(TAG, "[WFD_CLIENT] joinJob cancelled after join call; ignoring result")
                return@launch
            }

            when (result) {
                is ClientResult.Joined -> {
                    val ownerAddress = result.ownerAddress
                    Log.i(TAG, "[WFD_CLIENT] Joined Wi-Fi Direct group with ownerAddress: $ownerAddress")

                    var handshakeSuccessClient: HandshakeClient? = null
                    var lastHandshakeResult: HandshakeResult? = null
                    val maxAttempts = 3

                    for (attempt in 1..maxAttempts) {
                        if (!isActive) break
                        val client = HandshakeClient()
                        val ownIdentity = DeviceIdentity.get(context)

                        val hsResult = withContext(Dispatchers.IO) {
                            try {
                                client.connect(
                                    host = ownerAddress,
                                    expectedPeerUuid = payload.deviceId,
                                    ownIdentity = ownIdentity,
                                    port = ControlChannelConstants.PORT,
                                    localTransferPort = localDevice?.port ?: 0,
                                    localIp = localDevice?.ip
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "[WFD_CLIENT] Control channel handshake exception (attempt $attempt/$maxAttempts): ${e.message}")
                                null
                            }
                        }

                        if (!isActive) {
                            try { client.close() } catch (_: Exception) {}
                            break
                        }

                        lastHandshakeResult = hsResult

                        when (hsResult) {
                            is HandshakeResult.Success -> {
                                Log.i(TAG, "[WFD_CLIENT] Path taken: Handshake verified successfully with ${payload.deviceId} on attempt $attempt/$maxAttempts")
                                handshakeSuccessClient = client
                                break
                            }

                            is HandshakeResult.IdMismatch -> {
                                Log.e(TAG, "[WFD_CLIENT] Path taken: Handshake rejected - ID mismatch (expected ${payload.deviceId}, actual ${hsResult.actual})")
                                try { client.close() } catch (_: Exception) {}
                                wifiDirectClient.leave()
                                if (isActive) {
                                    errorMessage = "This isn't the device you paired with."
                                    screenMode = ReceiverScreenMode.ERROR
                                }
                                return@launch
                            }

                            is HandshakeResult.SameDevice -> {
                                Log.e(TAG, "[WFD_CLIENT] Path taken: Handshake rejected - same device")
                                try { client.close() } catch (_: Exception) {}
                                wifiDirectClient.leave()
                                if (isActive) {
                                    errorMessage = "That's this phone's own QR code."
                                    screenMode = ReceiverScreenMode.ERROR
                                }
                                return@launch
                            }

                            is HandshakeResult.Malformed -> {
                                Log.e(TAG, "[WFD_CLIENT] Path taken: Handshake rejected - handshake malformed (${hsResult.detail})")
                                try { client.close() } catch (_: Exception) {}
                                wifiDirectClient.leave()
                                if (isActive) {
                                    errorMessage = "Handshake failed: malformed response from sender."
                                    screenMode = ReceiverScreenMode.ERROR
                                }
                                return@launch
                            }

                            is HandshakeResult.Refused, is HandshakeResult.Timeout -> {
                                val detail = if (hsResult is HandshakeResult.Refused) hsResult.detail else (hsResult as HandshakeResult.Timeout).detail
                                Log.w(TAG, "[WFD_CLIENT] Path taken: Handshake retryable failure on attempt $attempt/$maxAttempts (${hsResult.javaClass.simpleName}: $detail)")
                                try { client.close() } catch (_: Exception) {}
                                if (attempt < maxAttempts) {
                                    delay(1500L)
                                }
                            }

                            null -> {
                                Log.e(TAG, "[WFD_CLIENT] Path taken: Handshake rejected - exception during handshake")
                                try { client.close() } catch (_: Exception) {}
                                wifiDirectClient.leave()
                                if (isActive) {
                                    errorMessage = "Handshake error: failed to verify sender."
                                    screenMode = ReceiverScreenMode.ERROR
                                }
                                return@launch
                            }
                        }
                    }

                    if (!isActive) return@launch

                    val verifiedClient = handshakeSuccessClient
                    if (verifiedClient == null) {
                        Log.e(TAG, "[WFD_CLIENT] Path taken: Handshake failed after $maxAttempts attempts (${lastHandshakeResult?.javaClass?.simpleName ?: "Failed"})")
                        wifiDirectClient.leave()
                        if (isActive) {
                            errorMessage = "Failed to verify sender after $maxAttempts attempts."
                            screenMode = ReceiverScreenMode.ERROR
                        }
                        return@launch
                    }

                    onHandshakeSuccess?.invoke(verifiedClient)

                    // Continue into transfer flow using owner address
                    if (onDirectReceiverConnected != null) {
                        onDirectReceiverConnected(
                            ownerAddress,
                            selectedSender?.port?.takeIf { it > 0 } ?: verifiedClient.peerTransferPort,
                            payload.deviceId,
                            payload.deviceName,
                            verifiedClient
                        ) { success, err ->
                            if (!success) {
                                Log.e(TAG, "[WFD_CLIENT] Transfer flow connection failed: $err")
                                try { verifiedClient.close() } catch (_: Exception) {}
                                onHandshakeSuccess?.invoke(null)
                                wifiDirectClient.leave()
                                if (isActive) {
                                    errorMessage = "Failed to connect to sender. Check that both phones are on the same network."
                                    screenMode = ReceiverScreenMode.ERROR
                                }
                            }
                        }
                    } else {
                        // Fallback: connect directly
                        val remoteDev = Device(
                            id = payload.deviceId,
                            name = payload.deviceName,
                            ip = ownerAddress,
                            port = selectedSender?.port?.takeIf { it > 0 } ?: verifiedClient.peerTransferPort
                        )
                        onSelectDevice(remoteDev)
                    }
                }

                is ClientResult.Failed -> {
                    if (!isActive) {
                        Log.i(TAG, "[WFD_CLIENT] joinJob was cancelled; ignoring ClientResult.Failed")
                        return@launch
                    }
                    Log.e(TAG, "[WFD_CLIENT] Join failed: ${result.reason}")
                    wifiDirectClient.leave()
                    errorMessage = formatFailureReason(result.reason)
                    screenMode = ReceiverScreenMode.ERROR
                }
            }
        }
    }

    // Process scanned QR payload for a tapped sender
    fun handleScannedQrForSender(rawQr: String, sender: Device) {
        val sanitized = WifiDirectPayloadParser.sanitize(rawQr)
        val parseResult = WifiDirectPayloadParser.parseDetailed(sanitized)

        when (parseResult) {
            is ParseDetailedResult.Failed -> {
                Log.w(TAG, "[WFD_CLIENT] QR parse failed: ${parseResult.field}")
                errorMessage = "ParseFailed (${parseResult.field})"
                screenMode = ReceiverScreenMode.ERROR
            }
            is ParseDetailedResult.Expired -> {
                Log.w(TAG, "[WFD_CLIENT] QR expired: ${parseResult.expiryEpochSeconds}")
                errorMessage = "Expired (expiry: ${parseResult.expiryEpochSeconds}, current: ${parseResult.currentEpochSeconds})"
                screenMode = ReceiverScreenMode.ERROR
            }
            is ParseDetailedResult.Success -> {
                val payload = parseResult.payload
                // Compare embedded device ID with tapped sender BLE ID
                val normUuid = DeviceIdentity.normalizeUuid(payload.deviceId)
                val payloadId8 = DeviceIdentity.deriveHexId(normUuid).lowercase()
                val bleId = sender.id.trim().lowercase()

                val matches = bleId.isNotEmpty() && (
                    payloadId8 == bleId ||
                    payloadId8.startsWith(bleId) ||
                    bleId.startsWith(payloadId8) ||
                    bleId.equals(normUuid, ignoreCase = true) ||
                    payloadId8.take(4).equals(bleId.take(4), ignoreCase = true)
                )

                if (!matches) {
                    Log.w(TAG, "[WFD_CLIENT] Device mismatch! payloadId8=$payloadId8, bleId=$bleId")
                    Toast.makeText(context, "That QR belongs to a different device.", Toast.LENGTH_LONG).show()
                    errorMessage = "That QR belongs to a different device."
                    screenMode = ReceiverScreenMode.ERROR
                } else {
                    Log.i(TAG, "[WFD_CLIENT] Device ID matched ($payloadId8). Initiating join.")
                    initiateJoin(payload, sender)
                }
            }
        }
    }

    // Back handler
    BackHandler {
        when (screenMode) {
            ReceiverScreenMode.DEVICE_LIST -> {
                wifiDirectClient.leave()
                onCancel()
            }
            ReceiverScreenMode.CONNECTING -> {
                Log.i(TAG, "[WFD_CLIENT] User cancelled connection via BackHandler")
                joinJob?.cancel()
                joinJob = null
                wifiDirectClient.leave()
                screenMode = ReceiverScreenMode.DEVICE_LIST
            }
            ReceiverScreenMode.ERROR -> {
                activePayload = null
                errorMessage = ""
                screenMode = ReceiverScreenMode.DEVICE_LIST
            }
        }
    }

    // QR Scanner Dialog Overlay
    if (showScanOverlay) {
        val targetSender = selectedSender
        PairingScannerDialog(
            title = scannerTitle,
            onDismiss = { showScanOverlay = false },
            onQrScanned = { scannedQr ->
                showScanOverlay = false
                if (targetSender != null) {
                    handleScannedQrForSender(scannedQr, targetSender)
                } else {
                    // Generic QR scan fallback
                    val parsed = WifiDirectPayloadParser.parse(scannedQr)
                    if (parsed != null) {
                        initiateJoin(parsed, null)
                    } else if (scannedQr.startsWith("flip://ip=")) {
                        val ip = scannedQr.substringAfter("ip=").substringBefore("&")
                        val port = scannedQr.substringAfter("port=").substringBefore("&").toIntOrNull() ?: 8080
                        if (ip.isNotBlank()) onManualConnect(ip, port)
                    } else {
                        Toast.makeText(context, "Invalid Flip QR code", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onLegacyConnect = { ip, port ->
                showScanOverlay = false
                onManualConnect(ip, port)
            }
        )
    }

    // Screen Render
    when (screenMode) {
        ReceiverScreenMode.CONNECTING -> {
            ConnectingStateView(
                targetSender = selectedSender,
                onCancel = {
                    Log.i(TAG, "[WFD_CLIENT] Cancel tapped during connecting")
                    joinJob?.cancel()
                    joinJob = null
                    wifiDirectClient.leave()
                    screenMode = ReceiverScreenMode.DEVICE_LIST
                }
            )
        }

        ReceiverScreenMode.ERROR -> {
            ReceiverErrorView(
                errorMessage = errorMessage,
                onTryAgain = {
                    val payload = activePayload
                    if (payload != null) {
                        if (payload.isExpired()) {
                            Log.w(TAG, "[WFD_CLIENT] Try again: QR payload has expired")
                            Toast.makeText(context, "That QR has expired. Ask the sender to show a new one.", Toast.LENGTH_LONG).show()
                            activePayload = null
                            errorMessage = ""
                            val sender = selectedSender
                            if (sender != null) {
                                scannerTitle = "Scan the QR on ${formatSenderTitle(sender)}'s screen"
                            } else {
                                scannerTitle = "Scan Sender's QR Code"
                            }
                            showScanOverlay = true
                            screenMode = ReceiverScreenMode.DEVICE_LIST
                        } else {
                            initiateJoin(payload, selectedSender)
                        }
                    } else {
                        val sender = selectedSender
                        if (sender != null) {
                            scannerTitle = "Scan the QR on ${formatSenderTitle(sender)}'s screen"
                            showScanOverlay = true
                        } else {
                            screenMode = ReceiverScreenMode.DEVICE_LIST
                        }
                    }
                },
                onScanAgain = {
                    val sender = selectedSender
                    if (sender != null) {
                        scannerTitle = "Scan the QR on ${formatSenderTitle(sender)}'s screen"
                    } else {
                        scannerTitle = "Scan Sender's QR Code"
                    }
                    showScanOverlay = true
                    screenMode = ReceiverScreenMode.DEVICE_LIST
                },
                onUseCodeOrIp = {
                    activePayload = null
                    errorMessage = ""
                    screenMode = ReceiverScreenMode.DEVICE_LIST
                },
                onBack = {
                    activePayload = null
                    errorMessage = ""
                    screenMode = ReceiverScreenMode.DEVICE_LIST
                }
            )
        }

        ReceiverScreenMode.DEVICE_LIST -> {
            DeviceListView(
                activeSenders = activeSenders,
                bleError = bleError,
                autoJoinState = autoJoinState,
                localDevice = localDevice,
                manualCode = manualCode,
                onManualCodeChange = { manualCode = it },
                onSenderTapped = { sender ->
                    val isWfd = (sender.flags.toInt() and 0x02) != 0
                    if (isWfd) {
                        // Wi-Fi Direct: switch to scanner with targeted prompt
                        selectedSender = sender
                        scannerTitle = "Scan the QR on ${formatSenderTitle(sender)}'s screen"
                        showScanOverlay = true
                    } else {
                        // Hotspot transport: existing behavior
                        onSelectDevice(sender)
                    }
                },
                onOpenGeneralScanner = {
                    selectedSender = null
                    scannerTitle = "Scan Sender's QR Code"
                    showScanOverlay = true
                },
                onManualConnect = onManualConnect,
                onCancel = {
                    wifiDirectClient.leave()
                    onCancel()
                },
                formatSenderTitle = ::formatSenderTitle
            )
        }
    }
}

/**
 * Connecting View showing "Connecting..." and "Approve the connection if Android asks."
 */
@Composable
private fun ConnectingStateView(
    targetSender: Device?,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("connecting_spinner"),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp
                )

                Text(
                    text = "Connecting...",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("connecting_title")
                )

                if (targetSender != null) {
                    Text(
                        text = "To ${targetSender.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Approve the connection if Android asks.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("approve_connection_notice")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("cancel_connecting_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Error Screen showing specific reason and "Try again", "Scan again", and "Use code/IP instead".
 */
@Composable
private fun ReceiverErrorView(
    errorMessage: String,
    onTryAgain: () -> Unit,
    onScanAgain: () -> Unit,
    onUseCodeOrIp: () -> Unit,
    onBack: () -> Unit
) {
    val cleanError = remember(errorMessage) {
        val withoutIp = errorMessage.replace(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b"""), "").trim()
        if (withoutIp.isBlank() || withoutIp.contains("failed", ignoreCase = true) || withoutIp.contains("error", ignoreCase = true)) {
            "Connection failed. Check that both phones are on the same network."
        } else {
            withoutIp
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Connection Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Text(
                        text = "Connection Failed",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("error_title")
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = cleanError,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(14.dp)
                                .testTag("error_reason_text")
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 1. Try again
                    Button(
                        onClick = onTryAgain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("try_again_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Try again", fontWeight = FontWeight.SemiBold)
                    }

                    // 2. Scan again
                    OutlinedButton(
                        onClick = onScanAgain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("scan_again_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan again", fontWeight = FontWeight.SemiBold)
                    }

                    // 3. Enter code instead
                    OutlinedButton(
                        onClick = onUseCodeOrIp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("use_code_ip_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Dialpad, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enter code instead", fontWeight = FontWeight.SemiBold)
                    }

                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("back_to_list_button")
                    ) {
                        Text("Back to Available Devices", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Device list view displaying senders as "name · short code", auto-refreshing,
 * with manual code input.
 */
@Composable
private fun DeviceListView(
    activeSenders: List<Device>,
    bleError: String?,
    autoJoinState: AutoJoinState,
    localDevice: Device?,
    manualCode: String,
    onManualCodeChange: (String) -> Unit,
    onSenderTapped: (Device) -> Unit,
    onOpenGeneralScanner: () -> Unit,
    onManualConnect: (String, Int) -> Unit,
    onCancel: () -> Unit,
    formatSenderTitle: (Device) -> String
) {
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

                // Auto-join status banners for hotspot transport
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
                            Row(
                                modifier = Modifier.padding(12.dp),
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
                    else -> {}
                }

                // Available Senders Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 340.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                                text = "Available Senders",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            IconButton(onClick = onOpenGeneralScanner) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = "Open Scanner",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (activeSenders.isEmpty()) {
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
                                        text = "Searching for nearby senders...",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(activeSenders, key = { it.id }) { sender ->
                                    val isWfd = (sender.flags.toInt() and 0x02) != 0
                                    val title = formatSenderTitle(sender)
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSenderTapped(sender) }
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
                                                    .size(40.dp)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = if (isWfd) Icons.Default.Wifi else Icons.Default.WifiTethering,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = title,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyLarge
                                                )
                                                Text(
                                                    text = if (isWfd) "Wi-Fi Direct · Tap to scan QR" else "Hotspot · Tap to connect",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            Icon(
                                                imageVector = if (isWfd) Icons.Default.QrCodeScanner else Icons.Default.ChevronRight,
                                                contentDescription = "Connect",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom section anchored with spacing and keyboard resilience
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Connect with code helper block
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                                    val digitsOnly = newValue.filter { it.isDigit() }
                                    onManualCodeChange(digitsOnly)
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
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Connect")
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("cancel_scanning_button"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "Cancel",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Previews for Device List View across 5 screen configurations
// -------------------------------------------------------------
@Preview(name = "Small Phone", widthDp = 320, heightDp = 640)
@Preview(name = "Tall 20:9 Phone", widthDp = 360, heightDp = 800)
@Preview(name = "21:9 Phone", widthDp = 360, heightDp = 860)
@Preview(name = "Tablet", widthDp = 800, heightDp = 1280)
@Preview(name = "Font Scale 1.3", widthDp = 360, heightDp = 800, fontScale = 1.3f)
@Composable
private fun ReceiverListPreview() {
    MyApplicationTheme {
        Surface {
            DeviceListView(
                activeSenders = listOf(
                    Device(id = "1", name = "Pixel 8 · 4A", ip = "192.168.49.1", port = 8080, flags = 0x01.toByte())
                ),
                bleError = null,
                autoJoinState = AutoJoinState.Idle,
                localDevice = Device(id = "2", name = "Galaxy S24 · 9B", ip = "192.168.49.2", port = 8080, flags = 0x01.toByte()),
                manualCode = "",
                onManualCodeChange = {},
                onSenderTapped = {},
                onOpenGeneralScanner = {},
                onManualConnect = { _, _ -> },
                onCancel = {},
                formatSenderTitle = { it.name }
            )
        }
    }
}
