package com.example.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.Device
import com.example.service.HotspotCredentialStore
import com.example.service.HotspotPairingProtocol
import com.example.service.OwnHotspotCredentials
import com.example.service.QrCodeGenerator
import com.example.ui.theme.AccentRed
import kotlinx.coroutines.delay

/**
 * First-Time Pairing Tab gated behind biometric or device credential authentication.
 * 
 * Rules:
 * - Unlocked only in-memory for 60s.
 * - Leaving the tab, backgrounding the app, or timer expiration relocks immediately.
 * - Never shows import options, saved credentials, or QR code before successful authentication.
 * - Guided message & button to security settings if device has neither biometrics nor screen lock.
 */
@Composable
fun FirstTimeHotspotTab(
    localDevice: Device?,
    onRequestReturnToAppPairing: (message: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // In-memory unlock state only (never saved across process death)
    var isUnlocked by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableIntStateOf(60) }
    var securityStatus by remember { mutableStateOf(BiometricAuthHelper.checkSecurityStatus(context)) }
    var ownHotspot by remember { mutableStateOf(HotspotCredentialStore.getOwnHotspot(context)) }
    var showPassword by remember { mutableStateOf(false) }

    fun authenticate() {
        val currentStatus = BiometricAuthHelper.checkSecurityStatus(context)
        securityStatus = currentStatus
        if (currentStatus == BiometricAuthHelper.SecurityStatus.NO_SECURITY_CONFIGURED) {
            return
        }

        val activity = BiometricAuthHelper.findFragmentActivity(context)
        if (activity == null) {
            onRequestReturnToAppPairing("Authentication activity unavailable")
            return
        }

        BiometricAuthHelper.launchBiometricPrompt(
            activity = activity,
            onSuccess = {
                isUnlocked = true
                remainingSeconds = 60
                ownHotspot = HotspotCredentialStore.getOwnHotspot(context)
            },
            onError = { errorCode, errString ->
                isUnlocked = false
                onRequestReturnToAppPairing(errString.toString().ifBlank { "Authentication failed" })
            },
            onCancel = { errString ->
                isUnlocked = false
                onRequestReturnToAppPairing(errString.toString().ifBlank { "Authentication cancelled" })
            }
        )
    }

    // Auto-trigger confirmation when tab is first opened
    LaunchedEffect(Unit) {
        if (!isUnlocked) {
            val status = BiometricAuthHelper.checkSecurityStatus(context)
            securityStatus = status
            if (status == BiometricAuthHelper.SecurityStatus.READY_TO_AUTHENTICATE) {
                authenticate()
            }
        }
    }

    // Relock immediately when backgrounding (ON_STOP or ON_PAUSE)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE) {
                isUnlocked = false
                remainingSeconds = 0
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Relock when leaving the tab
            isUnlocked = false
            remainingSeconds = 0
        }
    }

    // 60-Second Window Timer
    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            remainingSeconds = 60
            while (remainingSeconds > 0) {
                delay(1000L)
                remainingSeconds--
            }
            isUnlocked = false
            Toast.makeText(context, "Session expired. Tap Show to unlock again.", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (securityStatus == BiometricAuthHelper.SecurityStatus.NO_SECURITY_CONFIGURED && !isUnlocked) {
            // Case 1: Phone has no screen lock or enrolled biometric
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Screen lock required",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Text(
                text = "Set a screen lock to use this",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                textAlign = TextAlign.Center
            )

            Text(
                text = "To protect your Wi-Fi hotspot credentials, Android requires setting up a fingerprint, face, PIN, pattern, or password lock on your device.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                ),
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Button(
                onClick = { BiometricAuthHelper.openSecuritySettings(context) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_security_settings_button")
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Security Settings")
            }

            OutlinedButton(
                onClick = { onRequestReturnToAppPairing("Screen lock setup required") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back to App Pairing")
            }

        } else if (!isUnlocked) {
            // Case 2: Locked state with "Show" button
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "Locked",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Text(
                text = "Confirm it's you",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                textAlign = TextAlign.Center
            )

            Text(
                text = "Unlock to show your hotspot details and first-time pairing code. Fingerprint, face, or screen lock all work.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                ),
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Button(
                onClick = { authenticate() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("show_first_time_details_button")
            ) {
                Icon(Icons.Default.LockReset, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Show", fontWeight = FontWeight.Bold)
            }

        } else {
            // Case 3: Successfully Unlocked (60-second window active)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Timer",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Unlocked (${remainingSeconds}s)",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                TextButton(
                    onClick = {
                        isUnlocked = false
                        remainingSeconds = 0
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Lock now",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            if (ownHotspot == null) {
                // Show import options if not configured yet
                ImportHotspotSection(
                    onImportSuccess = {
                        ownHotspot = HotspotCredentialStore.getOwnHotspot(context)
                    }
                )
            } else {
                // Render Saved Details & First Time QR code
                val creds = ownHotspot!!
                val fullDeviceUuid = remember { com.example.service.DeviceIdentity.getInstallUuid(context) }
                val qrPayload = remember(creds, localDevice, fullDeviceUuid) {
                    HotspotPairingProtocol.buildFirstTimeQrPayload(
                        deviceId = fullDeviceUuid,
                        deviceName = localDevice?.name ?: "Flip Sender",
                        credentials = creds
                    )
                }
                val qrBitmap: Bitmap? = remember(qrPayload) {
                    QrCodeGenerator.generateQrCode(qrPayload)
                }

                // One-line security warning above the QR
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Hotspot Security",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Anyone you pair with can use your hotspot while in Flip",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            ),
                            maxLines = 2
                        )
                    }
                }

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
                            contentDescription = "First-time Pairing QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Text(
                    text = "Scan on receiver to pair permanently",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // Hotspot credentials card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(14.dp)
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
                            Text(
                                text = "Hotspot SSID",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = creds.ssid,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Password",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (showPassword) creds.password else "••••••••",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                IconButton(
                                    onClick = { showPassword = !showPassword },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle password visibility",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                TextButton(
                    onClick = {
                        HotspotCredentialStore.clearOwnHotspot(context)
                        ownHotspot = null
                    }
                ) {
                    Text(
                        text = "Clear / Re-import credentials",
                        style = MaterialTheme.typography.labelSmall.copy(color = AccentRed)
                    )
                }
            }
        }
    }
}
