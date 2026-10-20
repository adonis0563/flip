package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDirectDebugScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Phone A (Host), 1: Phone B (Join)

    // Host state (Phone A)
    var isHosting by remember { mutableStateOf(false) }
    var hostStatus by remember { mutableStateOf("Idle") }
    var hostPayloadText by remember { mutableStateOf("") }
    var hostSocketStatus by remember { mutableStateOf("Server socket inactive") }
    val hostHelper = remember { WifiDirectHost(context) }
    var serverSocketJob by remember { mutableStateOf<Job?>(null) }
    var serverSocketInstance by remember { mutableStateOf<ServerSocket?>(null) }

    // Client state (Phone B)
    var isJoining by remember { mutableStateOf(false) }
    var clientPayloadInput by remember { mutableStateOf("") }
    var clientStatus by remember { mutableStateOf("Idle") }
    var clientResultSummary by remember { mutableStateOf<String?>(null) }
    var clientSuccess by remember { mutableStateOf<Boolean?>(null) }
    var missingPermissionsList by remember { mutableStateOf<List<String>>(emptyList()) }
    val clientHelper = remember { WifiDirectClient(context) }

    val requiredDebugPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    val debugPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val anyDenied = results.values.any { !it }
        if (anyDenied) {
            Toast.makeText(context, "Permissions denied. Wi-Fi Direct may not function.", Toast.LENGTH_SHORT).show()
        } else {
            missingPermissionsList = emptyList()
            Toast.makeText(context, "Permissions granted! Retrying...", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val missing = requiredDebugPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            debugPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    // Clean up on screen exit
    DisposableEffect(Unit) {
        onDispose {
            serverSocketJob?.cancel()
            try {
                serverSocketInstance?.close()
            } catch (_: Exception) {}
            hostHelper.stop()
            clientHelper.leave()
        }
    }

    fun formatFailureReason(reason: ClientFailureReason): String = when (reason) {
        is ClientFailureReason.ParseFailed -> "ParseFailed (${reason.field})"
        is ClientFailureReason.Expired -> "Expired (Payload expiry: ${reason.expiryEpochSeconds}s, Phone time: ${reason.currentEpochSeconds}s, diff: ${reason.currentEpochSeconds - reason.expiryEpochSeconds}s)"
        is ClientFailureReason.PermissionMissing -> "PermissionMissing: [${reason.permissions.joinToString(", ")}]"
        is ClientFailureReason.ConfigBuildFailed -> "ConfigBuildFailed: ${reason.message}"
        is ClientFailureReason.ConnectRejected -> "ConnectRejected(${reason.name})"
        is ClientFailureReason.Timeout -> "Timeout (Waited 20s for connection changed event)"
        is ClientFailureReason.Unsupported -> "Unsupported (Requires Android 10+ / API 29+)"
        is ClientFailureReason.SpecifierFailed -> "SpecifierFailed: ${reason.message}"
        is ClientFailureReason.Error -> "Error(${reason.code}): ${reason.message}"
    }

    val runJoinFlow: () -> Unit = {
        val rawInput = clientPayloadInput
        if (rawInput.isBlank()) {
            Toast.makeText(context, "Please paste a payload first", Toast.LENGTH_SHORT).show()
        } else {
            val missing = requiredDebugPermissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                missingPermissionsList = missing
                debugPermissionLauncher.launch(missing.toTypedArray())
            } else {
                scope.launch {
                    isJoining = true
                    clientStatus = "Joining Wi-Fi Direct..."
                    clientResultSummary = null
                    clientSuccess = null
                    missingPermissionsList = emptyList()

                    val startTime = System.currentTimeMillis()
                    // Rule 3: Trim whitespace and line breaks before parsing
                    val sanitized = WifiDirectPayloadParser.sanitize(rawInput)
                    val joinResult = clientHelper.join(sanitized)

                    val elapsedMs = System.currentTimeMillis() - startTime
                    when (joinResult) {
                        is ClientResult.Joined -> {
                            val pathName = if (joinResult.path == ClientJoinPath.WIFI_DIRECT_P2P) {
                                "Wi-Fi Direct P2P"
                            } else {
                                "WifiNetworkSpecifier (fallback)"
                            }

                            val p2pNote = if (joinResult.p2pAttemptFailed != null) {
                                "\n[Step 1 - P2P]: ${formatFailureReason(joinResult.p2pAttemptFailed)}\n[Step 2 - Fallback]: WifiNetworkSpecifier succeeded!"
                            } else {
                                "\n[Step 1 - P2P]: Connected directly"
                            }

                            clientStatus = "Joined via $pathName. Testing socket connection to ${joinResult.ownerAddress}:8080..."

                            // Open socket to owner address on port 8080
                            val socketSuccess = withContext(Dispatchers.IO) {
                                try {
                                    val socket = Socket()
                                    socket.connect(InetSocketAddress(joinResult.ownerAddress, 8080), 5000)
                                    socket.close()
                                    true
                                } catch (e: Exception) {
                                    false
                                }
                            }

                            if (socketSuccess) {
                                clientSuccess = true
                                clientStatus = "Connected (${elapsedMs}ms)"
                                clientResultSummary = "Connected in ${elapsedMs}ms!\nPath: $pathName$p2pNote\nSocket to ${joinResult.ownerAddress}:8080 verified."
                            } else {
                                clientSuccess = false
                                clientStatus = "Socket failed"
                                clientResultSummary = "Joined via $pathName in ${elapsedMs}ms$p2pNote, but socket connection to ${joinResult.ownerAddress}:8080 failed (host not listening on 8080?)."
                            }
                        }
                        is ClientResult.Failed -> {
                            clientSuccess = false
                            clientStatus = "Failed: ${formatFailureReason(joinResult.reason)}"

                            val p2pNote = if (joinResult.p2pReason != null) {
                                "\n[Step 1 - P2P]: ${formatFailureReason(joinResult.p2pReason)}"
                            } else ""
                            val specifierNote = if (joinResult.specifierReason != null) {
                                "\n[Step 2 - Fallback Specifier]: ${formatFailureReason(joinResult.specifierReason)}"
                            } else ""

                            clientResultSummary = "Failed step: ${formatFailureReason(joinResult.reason)}$p2pNote$specifierNote (${elapsedMs}ms)"

                            if (joinResult.reason is ClientFailureReason.PermissionMissing) {
                                missingPermissionsList = joinResult.reason.permissions
                            }
                        }
                    }
                    isJoining = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Wi-Fi Direct Debug",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("debug_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mode selector tabs
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Phone A: Host") },
                    modifier = Modifier.testTag("tab_phone_a")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Phone B: Join") },
                    modifier = Modifier.testTag("tab_phone_b")
                )
            }

            if (selectedTab == 0) {
                // PHONE A (HOST) VIEW
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Wi-Fi Direct Host (Phone A)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        Text(
                            text = "Status: $hostStatus",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (isHosting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.testTag("host_status_text")
                        )

                        Text(
                            text = "Socket (8080): $hostSocketStatus",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )

                        // Action button
                        Button(
                            onClick = {
                                if (isHosting) {
                                    serverSocketJob?.cancel()
                                    try {
                                        serverSocketInstance?.close()
                                    } catch (_: Exception) {}
                                    hostHelper.stop()
                                    isHosting = false
                                    hostStatus = "Stopped"
                                    hostPayloadText = ""
                                    hostSocketStatus = "Server socket closed"
                                } else {
                                    val missing = requiredDebugPermissions.filter {
                                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                                    }
                                    if (missing.isNotEmpty()) {
                                        debugPermissionLauncher.launch(missing.toTypedArray())
                                        return@Button
                                    }

                                    scope.launch {
                                        hostStatus = "Starting host..."
                                        isHosting = true
                                        val result = hostHelper.start(clientJoinedCallback = { clientIp ->
                                            hostStatus = "Client joined group (Owner IP: $clientIp)"
                                        })

                                        when (result) {
                                            is HostResult.GroupReady -> {
                                                hostStatus = "Group Ready: ${result.networkName}"
                                                val identity = DeviceIdentity.get(context)
                                                val deviceId = identity.uuid
                                                val deviceName = identity.displayName

                                                val payload = WifiDirectPayloadParser.build(
                                                    deviceId = deviceId,
                                                    deviceName = deviceName,
                                                    groupName = result.networkName,
                                                    passphrase = result.passphrase,
                                                    ownerAddress = result.ownerDeviceAddress
                                                )
                                                hostPayloadText = payload

                                                // Launch server socket on port 8080 to verify connection
                                                serverSocketJob?.cancel()
                                                serverSocketJob = scope.launch(Dispatchers.IO) {
                                                    try {
                                                        hostSocketStatus = "Listening on port 8080..."
                                                        val server = ServerSocket(8080)
                                                        serverSocketInstance = server
                                                        val incoming = server.accept()
                                                        withContext(Dispatchers.Main) {
                                                            hostSocketStatus = "Connected! Client socket from ${incoming.inetAddress.hostAddress}:8080"
                                                            Toast.makeText(context, "Socket connected from ${incoming.inetAddress.hostAddress}", Toast.LENGTH_SHORT).show()
                                                        }
                                                        incoming.close()
                                                        server.close()
                                                    } catch (e: Exception) {
                                                        withContext(Dispatchers.Main) {
                                                            hostSocketStatus = "Server socket closed: ${e.message}"
                                                        }
                                                    }
                                                }
                                            }
                                            is HostResult.Unsupported -> {
                                                hostStatus = "Error: Unsupported (Requires Android 10+)"
                                                isHosting = false
                                            }
                                            is HostResult.PermissionMissing -> {
                                                hostStatus = "Error: Required Wi-Fi / Location permission missing"
                                                isHosting = false
                                            }
                                            is HostResult.Busy -> {
                                                hostStatus = "Error: Wi-Fi P2P system is BUSY"
                                                isHosting = false
                                            }
                                            is HostResult.Error -> {
                                                hostStatus = "Error: Wi-Fi P2P failed code ${result.code}"
                                                isHosting = false
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("host_toggle_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isHosting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (isHosting) "Stop Host" else "Host")
                        }

                        // Payload Text display
                        if (hostPayloadText.isNotBlank()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = "Payload Text (10-minute test window):",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = hostPayloadText,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    ),
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .testTag("host_payload_text")
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Flip Payload", hostPayloadText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Payload copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("copy_payload_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Copy Payload")
                            }
                        }
                    }
                }
            } else {
                // PHONE B (JOIN) VIEW
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Wi-Fi Direct Client (Phone B)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )

                        // Rule 3: "Add a 'Paste from clipboard' button"
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0)?.text?.toString() ?: ""
                                    clientPayloadInput = text
                                    Toast.makeText(context, "Pasted ${text.length} characters from clipboard", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("paste_from_clipboard_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Paste from clipboard")
                        }

                        // Payload input text field
                        OutlinedTextField(
                            value = clientPayloadInput,
                            onValueChange = { clientPayloadInput = it },
                            label = { Text("Paste flip://wfd payload") },
                            placeholder = { Text("flip://wfd?v=1&...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("client_payload_input"),
                            maxLines = 4,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val item = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!item.isNullOrBlank()) {
                                            clientPayloadInput = item
                                        }
                                    },
                                    modifier = Modifier.testTag("paste_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste from clipboard"
                                    )
                                }
                            }
                        )

                        // Rule 3: "Show the pasted length and the parsed field names (not the values)"
                        if (clientPayloadInput.isNotBlank()) {
                            val parsedParams = remember(clientPayloadInput) {
                                WifiDirectPayloadParser.extractParams(clientPayloadInput)
                            }
                            val fieldNames = parsedParams?.keys?.toList() ?: emptyList()

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Pasted length: ${clientPayloadInput.length} characters",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        modifier = Modifier.testTag("pasted_length_text")
                                    )
                                    Text(
                                        text = "Parsed field names: ${if (fieldNames.isEmpty()) "(malformed/none)" else fieldNames.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        modifier = Modifier.testTag("parsed_field_names_text")
                                    )
                                }
                            }
                        }

                        // Join / Leave buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = runJoinFlow,
                                enabled = !isJoining,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("join_button")
                            ) {
                                if (isJoining) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text("Join")
                            }

                            OutlinedButton(
                                onClick = {
                                    clientHelper.leave()
                                    isJoining = false
                                    clientStatus = "Idle"
                                    clientResultSummary = null
                                    clientSuccess = null
                                    missingPermissionsList = emptyList()
                                },
                                modifier = Modifier
                                    .weight(0.8f)
                                    .height(48.dp)
                                    .testTag("leave_button")
                            ) {
                                Text("Leave")
                            }
                        }

                        // Rule 2: "From the debug screen, request any missing runtime permission, then retry."
                        if (missingPermissionsList.isNotEmpty()) {
                            Button(
                                onClick = {
                                    debugPermissionLauncher.launch(missingPermissionsList.toTypedArray())
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("grant_missing_permissions_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Grant Missing Permissions & Retry")
                            }
                        }

                        // Status & Result display
                        Text(
                            text = "Status: $clientStatus",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.testTag("client_status_text")
                        )

                        clientResultSummary?.let { summary ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (clientSuccess == true) Color(0xFF1B5E20).copy(alpha = 0.15f) else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                border = BorderStroke(
                                    1.dp,
                                    if (clientSuccess == true) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = if (clientSuccess == true) Icons.Default.CheckCircle else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = if (clientSuccess == true) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = summary,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = 20.sp
                                        ),
                                        modifier = Modifier.testTag("client_result_summary")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
