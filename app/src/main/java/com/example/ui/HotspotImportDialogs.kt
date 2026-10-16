package com.example.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.HotspotCredentialStore
import com.example.service.HotspotPairingProtocol
import com.example.service.OwnHotspotCredentials
import com.example.ui.theme.AccentRed

/**
 * Import options and dialogs for sender hotspot credentials.
 */
@Composable
fun ImportHotspotSection(
    onImportSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showManualDialog by remember { mutableStateOf(false) }
    var screenshotUriToDelete by remember { mutableStateOf<Uri?>(null) }

    // Android Photo Picker (zero broad storage permissions)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val qrText = HotspotPairingProtocol.decodeQrFromImageUri(context, uri)
            if (qrText != null) {
                val details = HotspotPairingProtocol.parseWifiQr(qrText)
                if (details != null) {
                    HotspotCredentialStore.saveOwnHotspot(
                        context,
                        OwnHotspotCredentials(
                            ssid = details.ssid,
                            password = details.password,
                            security = details.security,
                            isHidden = details.isHidden
                        )
                    )
                    screenshotUriToDelete = uri
                    onImportSuccess()
                } else {
                    Toast.makeText(context, "QR code in image is not a valid Wi-Fi code.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "No readable QR code found in the selected screenshot.", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (screenshotUriToDelete != null) {
        val uri = screenshotUriToDelete!!
        AlertDialog(
            onDismissRequest = { screenshotUriToDelete = null },
            title = {
                Text("Delete Screenshot?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            },
            text = {
                Text(
                    "Hotspot details saved securely. To protect your password, we suggest deleting the screenshot from your gallery.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            context.contentResolver.delete(uri, null, null)
                            Toast.makeText(context, "Screenshot deleted.", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {
                            Toast.makeText(context, "Please delete the screenshot manually from Photos.", Toast.LENGTH_LONG).show()
                        }
                        screenshotUriToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                ) {
                    Text("Delete Screenshot", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { screenshotUriToDelete = null }) {
                    Text("Keep Screenshot", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showManualDialog) {
        ManualHotspotEntryDialog(
            onDismiss = { showManualDialog = false },
            onSave = { creds ->
                HotspotCredentialStore.saveOwnHotspot(context, creds)
                showManualDialog = false
                onImportSuccess()
            }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Import your hotspot details",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )
        Text(
            text = "Choose an option to save your hotspot so receivers can connect in one tap:",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Option A: Pick screenshot
        Button(
            onClick = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Pick Hotspot QR Screenshot", fontWeight = FontWeight.SemiBold)
        }

        // Option B: Manual Entry
        OutlinedButton(
            onClick = { showManualDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enter Details Manually", fontWeight = FontWeight.SemiBold)
        }

        // Option C: Paste from Clipboard
        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                val details = HotspotPairingProtocol.parseWifiQr(clipText)
                if (details != null) {
                    HotspotCredentialStore.saveOwnHotspot(
                        context,
                        OwnHotspotCredentials(
                            ssid = details.ssid,
                            password = details.password,
                            security = details.security,
                            isHidden = details.isHidden
                        )
                    )
                    Toast.makeText(context, "Hotspot details imported from clipboard!", Toast.LENGTH_SHORT).show()
                    onImportSuccess()
                } else {
                    Toast.makeText(context, "Clipboard does not contain a valid Wi-Fi code (WIFI:...).", Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Paste from Clipboard", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ManualHotspotEntryDialog(
    onDismiss: () -> Unit,
    onSave: (OwnHotspotCredentials) -> Unit
) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var security by remember { mutableStateOf("WPA") }
    var isHidden by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Hotspot Details", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("Hotspot Name (SSID)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Security", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("WPA", "SAE", "OPEN").forEach { sec ->
                            FilterChip(
                                selected = security == sec,
                                onClick = { security = sec },
                                label = { Text(sec) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (ssid.isNotBlank()) {
                        onSave(
                            OwnHotspotCredentials(
                                ssid = ssid.trim(),
                                password = password.trim(),
                                security = security,
                                isHidden = isHidden
                            )
                        )
                    }
                },
                enabled = ssid.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Save Credentials")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AccentRed)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp)
    )
}
