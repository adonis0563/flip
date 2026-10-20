package com.example.ui

import android.Manifest
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

private const val TAG = "PairingScanner"

/**
 * QR scanner dialog for Flip App Pairing.
 * Scans standard Flip pairing codes (flip://ip=...) to directly connect to sender devices.
 * First-time Wi-Fi credential pairing, fingerprint gates, and credential persistence are removed.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PairingScannerDialog(
    onDismiss: () -> Unit,
    title: String = "Scan Sender's QR Code",
    onQrScanned: ((String) -> Unit)? = null,
    onPairedSuccess: ((name: String, shortCode: String) -> Unit)? = null,
    onLegacyConnect: ((ip: String, port: Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    var manualInput by remember { mutableStateOf("") }
    var isProcessingScan by remember { mutableStateOf(false) }

    // Request camera permission when scanner opens
    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    fun processScannedQr(rawText: String) {
        if (isProcessingScan) return
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return

        if (onQrScanned != null) {
            isProcessingScan = true
            onQrScanned(trimmed)
            onDismiss()
            return
        }

        if (trimmed.startsWith("flip://ip=")) {
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
                Log.e(TAG, "Malformed pairing QR code: ${e.message}")
                Toast.makeText(context, "Malformed pairing QR code.", Toast.LENGTH_SHORT).show()
            }
        } else if (trimmed.contains(".") && !trimmed.contains("://")) {
            // Raw IP or IP:PORT input
            try {
                val parts = trimmed.split(":")
                val ip = parts[0].trim()
                val port = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: 8080 else 8080
                if (ip.isNotBlank()) {
                    isProcessingScan = true
                    onLegacyConnect?.invoke(ip, port)
                    onDismiss()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Invalid IP format.", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Reject random, non-Flip, or retired first-time QR codes
            Toast.makeText(context, "Invalid QR code. Please scan a Flip pairing code.", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = {
            isProcessingScan = false
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Camera / manual scanning interface
            if (cameraPermissionState.status.isGranted) {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    onQrCodeScanned = { rawText ->
                        processScannedQr(rawText)
                    }
                )

                // Scanner Target Frame Overlay
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "scan_laser")
                    val laserOffset by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2200, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "laser"
                    )

                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .align(Alignment.TopCenter)
                                .offset(y = (260.dp * laserOffset))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            } else {
                // Permission rationale UI
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Camera Permission",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Camera Permission Needed",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Flip uses your camera to scan pairing QR codes and establish zero-cloud connections.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White.copy(alpha = 0.8f)
                        ),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { cameraPermissionState.launchPermissionRequest() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Grant Permission")
                    }
                }
            }

            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    IconButton(onClick = {
                        isProcessingScan = false
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.size(48.dp))
            }

            // Bottom Manual Entry Fallback
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(20.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualInput,
                            onValueChange = { manualInput = it },
                            placeholder = { Text("Paste flip:// QR code or IP", color = Color.Gray, fontSize = 13.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.DarkGray
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (manualInput.isNotBlank()) {
                                    processScannedQr(manualInput.trim())
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}
