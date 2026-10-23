package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.Device
import com.example.service.*
import com.example.service.control.ControlChannelConstants
import com.example.ui.theme.AccentRed
import com.example.viewmodel.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "FlipDirect"

/**
 * Sender screen for Wi-Fi Direct:
 * 1. Starts WifiDirectHost.
 * 2. On GroupReady:
 *    - Generates flip://wfd payload URI and renders QR code.
 *    - Hides QR when app moves to background; restores when returning to foreground.
 *    - Starts BLE advertising with flags 0x03 (bit 0=ready, bit 1=wfd) and zeroed IP ("0.0.0.0").
 *    - Runs control channel handshake server if available.
 * 3. Shows "Waiting for receiver..." animation and Cancel button.
 * 4. Connects client into the transfer flow when joined.
 * 5. Tears down on cancel, success, 2-minute timeout, or leaving the screen.
 */
@Composable
fun WifiDirectSenderScreen(
    localDevice: Device?,
    onClientConnected: (peerIp: String, peerId: String, peerName: String, port: Int) -> Unit,
    onCancel: () -> Unit,
    wifiDirectHost: WifiDirectHost,
    connectionState: ConnectionState = ConnectionState.IDLE,
    remoteDevice: Device? = null,
    payloadUri: String? = null,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hostStatusText by remember { mutableStateOf("Starting Wi-Fi Direct...") }
    var hostErrorText by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isGroupReady by remember { mutableStateOf(false) }
    var isAppForeground by remember { mutableStateOf(true) }
    var establishedRemoteIp by remember { mutableStateOf<String?>(null) }
    var establishedRemotePort by remember { mutableIntStateOf(0) }

    fun handoffConnected(peerIp: String, peerId: String, peerName: String, port: Int) {
        if (establishedRemoteIp != null) {
            val initialIp = establishedRemoteIp!!
            val initialPort = establishedRemotePort
            if (peerIp.isNotBlank() && (peerIp != initialIp || port != initialPort)) {
                Log.w(
                    "FlipLink",
                    "Later connection differs: remote address last number: .${com.example.service.control.ControlChannelConstants.lastOctet(peerIp)}, port: $port (initial was .${com.example.service.control.ControlChannelConstants.lastOctet(initialIp)}, port: $initialPort)"
                )
            }
            return
        }
        establishedRemoteIp = peerIp
        establishedRemotePort = port
        Log.i("FlipLink", "Remote address last number: .${com.example.service.control.ControlChannelConstants.lastOctet(peerIp)}, port: $port")
        Log.i(TAG, "[WFD_SENDER] Handoff to transfer flow (peerIp=.${com.example.service.control.ControlChannelConstants.lastOctet(peerIp)}, peerId=$peerId, peerName=$peerName, port=$port)")
        onClientConnected(peerIp, peerId, peerName, port)
    }

    // Lifecycle observer to hide QR when app goes to background and restore on return
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    Log.d(TAG, "[WFD_SENDER] App moved to background: hiding QR code")
                    isAppForeground = false
                }
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    Log.d(TAG, "[WFD_SENDER] App returned to foreground: restoring QR code")
                    isAppForeground = true
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Render QR Code bitmap when payloadUri is available
    LaunchedEffect(payloadUri) {
        if (!payloadUri.isNullOrBlank()) {
            Log.i(TAG, "[WFD_SENDER] Received payloadUri from FlipViewModel; rendering QR code")
            isGroupReady = true
            hostStatusText = "Waiting for receiver..."
            qrBitmap = withContext(Dispatchers.Default) {
                QrCodeGenerator.generateQrCode(payloadUri)
            }
        } else {
            isGroupReady = false
            qrBitmap = null
        }
    }

    // 1. Monitor control channel state for incoming connections
    val connectionFlowState by FlipTransferForegroundService.connectionFlowState.collectAsState()
    LaunchedEffect(connectionFlowState) {
        when (val s = connectionFlowState) {
            is FirstTimeConnectionState.Connected -> {
                Log.i(TAG, "[WFD_SENDER] Control channel handshake succeeded with ${s.peerName} (${s.peerUuid})")
                hostStatusText = "Connected to ${s.peerName}! Starting transfer..."
                val peerIp = s.peerIp.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                    ?: remoteDevice?.ip?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                    ?: s.peerIp
                val peerPort = if (s.peerPort > 0) s.peerPort else (remoteDevice?.port ?: 0)
                handoffConnected(peerIp, s.peerUuid, s.peerName, peerPort)
            }
            is FirstTimeConnectionState.Handshaking -> {
                hostStatusText = "Verifying receiver..."
            }
            is FirstTimeConnectionState.Joining -> {
                hostStatusText = "Receiver connecting..."
            }
            is FirstTimeConnectionState.Failed -> {
                Log.w(TAG, "[WFD_SENDER] Control channel state failed: ${s.reason}")
            }
            else -> {}
        }
    }

    // 2. Monitor receiver's HTTP /connect arrival (whichever comes first)
    LaunchedEffect(connectionState, remoteDevice) {
        if (connectionState == ConnectionState.CONNECTED) {
            val peer = remoteDevice
            val peerId = peer?.id ?: ""
            val peerName = peer?.name ?: "Receiver"
            val requestRemoteIp = peer?.ip?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                ?: (connectionFlowState as? FirstTimeConnectionState.Connected)?.peerIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                ?: (peer?.ip ?: "")
            val peerPort = peer?.port?.takeIf { it > 0 } ?: (connectionFlowState as? FirstTimeConnectionState.Connected)?.peerPort ?: 0
            Log.i(TAG, "[WFD_SENDER] Receiver HTTP /connect arrived ($peerName at last_number=.${com.example.service.control.ControlChannelConstants.lastOctet(requestRemoteIp)}:$peerPort)")
            hostStatusText = "Connected to $peerName! Starting transfer..."
            handoffConnected(requestRemoteIp, peerId, peerName, peerPort)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top row with back button and screen title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.testTag("sender_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Send via Wi-Fi Direct",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
        }

        // Status indicator row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            if (hostErrorText != null) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = AccentRed,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = hostErrorText ?: "",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = AccentRed,
                        fontWeight = FontWeight.Bold
                    ),
                    textAlign = TextAlign.Center
                )
            } else {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = hostStatusText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("sender_status_text")
                )
            }
        }

        // Central Card: QR Code & Waiting Animation
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isGroupReady && qrBitmap != null) {
                    // QR Code container - hides when app goes to background
                    if (isAppForeground) {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .size(220.dp)
                                .padding(4.dp)
                                .testTag("sender_qr_code")
                        ) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "Wi-Fi Direct QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        // Secure placeholder when app is backgrounded
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "QR code hidden while app is in background",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                    }

                    Text(
                        text = "Scan this QR code with the receiver device or wait for automatic BLE discovery.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    )
                }

                // Pulsing animation for waiting
                PulsingRadioAnimation(
                    modifier = Modifier.padding(vertical = 8.dp),
                    size = 140.dp,
                    waveColor = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Waiting for receiver...",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.testTag("waiting_for_receiver_text")
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Cancel Button
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("cancel_direct_sender_button"),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
            border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "Cancel",
                color = AccentRed,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}
