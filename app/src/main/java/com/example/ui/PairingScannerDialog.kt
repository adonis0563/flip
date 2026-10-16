package com.example.ui

import android.Manifest
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.CredentialStore
import com.example.service.FirstTimePayload
import com.example.service.HotspotCredentialStore
import com.example.service.HotspotPairingProtocol
import com.example.service.WifiCredentials
import com.example.ui.theme.AccentRed
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/**
 * Reusable QR scanner dialog for First-Time Pairing and App Pairing.
 *
 * Rules:
 * - Handles flip://first payload for permanent encrypted peer storage.
 * - Validates payload and rejects malformed ones with a simple message.
 * - Confirms overwrite if peer ID already exists: "Replace saved details for <name · short code>?".
 * - Confirms successful pairing: "Paired with <name · short code>" without showing credentials.
 * - Requests camera permission on launch with a clear explanation.
 * - Supports legacy flip://ip= payload for direct Wi-Fi transfers.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PairingScannerDialog(
    onDismiss: () -> Unit,
    onPairedSuccess: ((name: String, shortCode: String) -> Unit)? = null,
    onLegacyConnect: ((ip: String, port: Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    var manualInput by remember { mutableStateOf("") }
    var pendingOverwritePayload by remember { mutableStateOf<FirstTimePayload?>(null) }
    var isProcessingScan by remember { mutableStateOf(false) }

    // Request camera permission only when scanner opens
    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    fun handleSavePeer(payload: FirstTimePayload) {
        val creds = WifiCredentials(
            ssid = payload.ssid,
            password = payload.password,
            security = payload.security,
            hidden = payload.isHidden
        )
        CredentialStore.savePeer(
            id = payload.deviceId,
            creds = creds,
            displayName = payload.deviceName,
            context = context
        )
        Toast.makeText(
            context,
            "Paired with ${payload.deviceName} · ${payload.shortCode}",
            Toast.LENGTH_LONG
        ).show()
        onPairedSuccess?.invoke(payload.deviceName, payload.shortCode)
        onDismiss()
    }

    fun processScannedQr(rawText: String) {
        if (isProcessingScan) return
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.startsWith(HotspotPairingProtocol.SCHEME_FIRST)) {
            val payload = HotspotPairingProtocol.parseFirstTimeQrPayload(trimmed)
            if (payload == null) {
                Toast.makeText(context, "Invalid pairing QR code.", Toast.LENGTH_SHORT).show()
                return
            }

            // Check if that full device UUID already has a paired entry
            val existingPeer = HotspotCredentialStore.getPairedPeer(context, payload.deviceId)
            if (existingPeer != null) {
                pendingOverwritePayload = payload
            } else {
                isProcessingScan = true
                handleSavePeer(payload)
            }
        } else if (trimmed.startsWith("flip://ip=")) {
            // Legacy connection QR
            try {
                val ip = trimmed.substringAfter("ip=").substringBefore("&")
                val port = trimmed.substringAfter("port=").substringBefore("&").toIntOrNull() ?: 8080
                if (ip.isNotBlank()) {
                    isProcessingScan = true
                    onLegacyConnect?.invoke(ip, port)
                    onDismiss()
                } else {
                    Toast.makeText(context, "Invalid IP address in QR code.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Malformed legacy pairing QR code.", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Reject random / non-Flip QR codes
            Toast.makeText(context, "Invalid QR code. Please scan a Flip pairing code.", Toast.LENGTH_SHORT).show()
        }
    }

    // Overwrite Confirmation Dialog
    if (pendingOverwritePayload != null) {
        val p = pendingOverwritePayload!!
        AlertDialog(
            onDismissRequest = { pendingOverwritePayload = null },
            title = {
                Text(
                    text = "Replace saved details?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Replace saved details for ${p.deviceName} · ${p.shortCode}?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val payloadToSave = p
                        pendingOverwritePayload = null
                        isProcessingScan = true
                        handleSavePeer(payloadToSave)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Replace")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingOverwritePayload = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                ) {
                    Text("Cancel", color = AccentRed)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val infiniteTransition = rememberInfiniteTransition(label = "ScannerLaser")
                val offsetLine by infiniteTransition.animateFloat(
                    initialValue = 0.1f,
                    targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "LaserOffset"
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan Pairing QR Code",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Point camera at the sender's QR code",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Camera Viewport
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.DarkGray)
                    ) {
                        if (cameraPermissionState.status.isGranted) {
                            CameraPreview(
                                modifier = Modifier.fillMaxSize(),
                                onQrCodeScanned = { rawResult ->
                                    processScannedQr(rawResult)
                                }
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Camera permission is required to scan Flip pairing QR codes.",
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = { cameraPermissionState.launchPermissionRequest() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Grant Permission", fontSize = 12.sp)
                                }
                            }
                        }

                        // Scanning laser line overlay
                        if (cameraPermissionState.status.isGranted) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.015f)
                                    .align(Alignment.TopCenter)
                                    .offset(y = (offsetLine * 260).dp)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Text Input Alternative for testing/emulators
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = {
                            manualInput = it
                            if (it.startsWith("flip://")) {
                                processScannedQr(it)
                            }
                        },
                        placeholder = { Text("Or paste pairing URL for testing", color = Color.Gray, fontSize = 13.sp) },
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .testTag("manual_pairing_qr_input"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.DarkGray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }

                // Close Button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .background(Color.DarkGray.copy(alpha = 0.7f), CircleShape)
                        .testTag("close_qr_scanner_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Scanner",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
