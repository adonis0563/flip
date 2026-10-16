package com.example.ui

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pulsing hotspot / radio animation in theme colors.
 * Runs on a smooth ~1.5s loop.
 * Renders a static icon if system reduce-motion is enabled.
 */
@Composable
fun PulsingRadioAnimation(
    modifier: Modifier = Modifier,
    size: Dp = 190.dp,
    waveColor: Color = MaterialTheme.colorScheme.primary
) {
    val context = LocalContext.current

    // Check system animation scale for reduce-motion
    val isReduceMotionEnabled = remember {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f
            )
            scale == 0f
        } catch (_: Exception) {
            false
        }
    }

    if (isReduceMotionEnabled) {
        // Static icon fallback for accessibility
        Box(
            modifier = modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.WifiTethering,
                contentDescription = "Hotspot active",
                tint = waveColor,
                modifier = Modifier.size(64.dp)
            )
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "RadioPulse")

    val pulse1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave1"
    )

    val pulse2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, delayMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave2"
    )

    val pulse3 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, delayMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave3"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxRadius = this.size.minDimension / 2f

            val pulses = listOf(pulse1, pulse2, pulse3)
            pulses.forEach { p ->
                val radius = maxRadius * (0.25f + 0.75f * p)
                val alpha = (1f - p).coerceIn(0f, 1f) * 0.7f
                drawCircle(
                    color = waveColor.copy(alpha = alpha),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
            }

            // Central solid core
            drawCircle(
                color = waveColor.copy(alpha = 0.15f),
                radius = maxRadius * 0.28f,
                center = center
            )
        }

        Icon(
            imageVector = Icons.Default.WifiTethering,
            contentDescription = "Broadcasting Hotspot",
            tint = waveColor,
            modifier = Modifier.size(44.dp)
        )
    }
}
