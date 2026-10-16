package com.example.ui

import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

data class StorageInfo(
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long,
    val usedPercent: Int,
    val usedFormatted: String,
    val totalFormatted: String
)

/**
 * Reads total and free space from the device's internal storage via StatFs.
 */
fun getDeviceStorageInfo(): StorageInfo {
    return try {
        val statFs = StatFs(Environment.getDataDirectory().path)
        val blockSize = statFs.blockSizeLong
        val totalBlocks = statFs.blockCountLong
        val availableBlocks = statFs.availableBlocksLong

        val total = totalBlocks * blockSize
        val free = availableBlocks * blockSize
        val used = (total - free).coerceAtLeast(0L)

        val percent = if (total > 0L) {
            ((used.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        } else {
            0
        }

        val bytesInGb = 1024.0 * 1024.0 * 1024.0
        val usedGb = used / bytesInGb
        val totalGb = total / bytesInGb

        val usedFormatted = String.format(Locale.US, "%.1f GB", usedGb)
        val totalFormatted = String.format(Locale.US, "%.1f GB", totalGb)

        StorageInfo(
            totalBytes = total,
            freeBytes = free,
            usedBytes = used,
            usedPercent = percent,
            usedFormatted = usedFormatted,
            totalFormatted = totalFormatted
        )
    } catch (e: Exception) {
        StorageInfo(
            totalBytes = 0L,
            freeBytes = 0L,
            usedBytes = 0L,
            usedPercent = 0,
            usedFormatted = "0.0 GB",
            totalFormatted = "0.0 GB"
        )
    }
}

/**
 * Minimalist monochrome storage card for the Flip home screen.
 */
@Composable
fun StorageCard(
    onViewDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    var storageInfo by remember { mutableStateOf(getDeviceStorageInfo()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Real-time updates while the screen is in the foreground
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                val latest = withContext(Dispatchers.IO) {
                    getDeviceStorageInfo()
                }
                storageInfo = latest
                delay(2000L) // Poll every 2 seconds for live storage changes
            }
        }
    }

    var animationPlayed by remember { mutableStateOf(false) }
    val targetProgress = (storageInfo.usedPercent.coerceIn(0, 100)) / 100f
    val animatedProgress by animateFloatAsState(
        targetValue = if (animationPlayed) targetProgress else 0f,
        animationSpec = tween(
            durationMillis = 600,
            easing = LinearOutSlowInEasing
        ),
        label = "storage_ring_progress"
    )

    LaunchedEffect(Unit) {
        animationPlayed = true
    }

    val warningRed = Color(0xFFD32F2F)
    val ringColor = if (storageInfo.usedPercent > 90) warningRed else MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val accessibilityLabel = "Storage ${storageInfo.usedPercent} percent used, ${storageInfo.usedFormatted} of ${storageInfo.totalFormatted}"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("storage_card")
            .semantics { contentDescription = accessibilityLabel },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Circular progress ring, 64dp, 6dp stroke, rounded caps
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidthPx = 6.dp.toPx()
                    val arcSize = size.minDimension - strokeWidthPx
                    val offset = strokeWidthPx / 2f

                    // Track
                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(offset, offset),
                        size = Size(arcSize, arcSize),
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                    )

                    // Progress arc (starts from top 12 o'clock: -90 degrees)
                    if (animatedProgress > 0f) {
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = animatedProgress * 360f,
                            useCenter = false,
                            topLeft = Offset(offset, offset),
                            size = Size(arcSize, arcSize),
                            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                        )
                    }
                }

                Text(
                    text = "${storageInfo.usedPercent}%",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Right: stacked and vertically centered
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Device Storage",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "${storageInfo.usedFormatted} of ${storageInfo.totalFormatted} used",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                // TODO: Open future dedicated storage breakdown screen
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(onClick = onViewDetails)
                        .testTag("storage_view_details_button")
                ) {
                    Text(
                        text = "View Details",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
