package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Device
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Animated Success Checkmark drawing inside a circle ring.
 *
 * Sequence:
 * 1. Circle ring scales up with spring/bounce.
 * 2. Background pulse ring expands softly.
 * 3. Checkmark vector path draws itself progressively.
 * 4. Micro bounce/pulse on completion.
 */
@Composable
fun AnimatedCircularCheckmark(
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    ringColor: Color = Color(0xFF34C759), // Vibrant iOS/Material emerald success green
    checkColor: Color = Color.White,
    ringWidth: Dp = 4.5.dp,
    checkWidth: Dp = 5.dp
) {
    val ringScale = remember { Animatable(0.2f) }
    val ringAlpha = remember { Animatable(0f) }
    val checkProgress = remember { Animatable(0f) }
    val pulseScale = remember { Animatable(0.8f) }
    val pulseAlpha = remember { Animatable(0.6f) }
    val finalBounce = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // Step 1: Scale in the circle
        launch {
            ringAlpha.animateTo(1f, tween(200))
        }
        ringScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )

        // Step 2: Pulse ring expands and fades
        launch {
            pulseScale.animateTo(1.4f, tween(600, easing = FastOutSlowInEasing))
        }
        launch {
            pulseAlpha.animateTo(0f, tween(600, easing = FastOutSlowInEasing))
        }

        // Step 3: Draw checkmark path smoothly
        delay(80)
        checkProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
        )

        // Step 4: Subtle celebratory pulse
        finalBounce.animateTo(1.08f, tween(120, easing = FastOutSlowInEasing))
        finalBounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }

    Box(
        modifier = modifier
            .size(size)
            .scale(finalBounce.value),
        contentAlignment = Alignment.Center
    ) {
        // Outer glow/pulse ring
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = this.size.minDimension
            val radius = (canvasSize / 2f) * pulseScale.value
            drawCircle(
                color = ringColor.copy(alpha = pulseAlpha.value * 0.35f),
                radius = radius,
                center = center
            )
        }

        // Main colored circle & checkmark canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .scale(ringScale.value)
        ) {
            val canvasSize = this.size.minDimension
            val strokePx = ringWidth.toPx()
            val checkStrokePx = checkWidth.toPx()
            val radius = (canvasSize - strokePx) / 2f

            // 1. Filled circle background
            drawCircle(
                color = ringColor.copy(alpha = ringAlpha.value),
                radius = radius,
                center = center
            )

            // 2. Crisp outer boundary ring
            drawCircle(
                color = ringColor,
                radius = radius,
                center = center,
                style = Stroke(width = strokePx)
            )

            // 3. Self-drawing Checkmark Path
            val progress = checkProgress.value
            if (progress > 0f) {
                // Key points for checkmark inside the circle:
                // Start -> Vertex -> End
                val pStart = Offset(canvasSize * 0.28f, canvasSize * 0.52f)
                val pVertex = Offset(canvasSize * 0.44f, canvasSize * 0.68f)
                val pEnd = Offset(canvasSize * 0.72f, canvasSize * 0.36f)

                val path = Path()
                path.moveTo(pStart.x, pStart.y)

                // First leg takes 40% of progress, second leg takes remaining 60%
                if (progress <= 0.4f) {
                    val legProgress = progress / 0.4f
                    val currentX = pStart.x + (pVertex.x - pStart.x) * legProgress
                    val currentY = pStart.y + (pVertex.y - pStart.y) * legProgress
                    path.lineTo(currentX, currentY)
                } else {
                    path.lineTo(pVertex.x, pVertex.y)
                    val legProgress = (progress - 0.4f) / 0.6f
                    val currentX = pVertex.x + (pEnd.x - pVertex.x) * legProgress
                    val currentY = pVertex.y + (pEnd.y - pVertex.y) * legProgress
                    path.lineTo(currentX, currentY)
                }

                drawPath(
                    path = path,
                    color = checkColor,
                    style = Stroke(
                        width = checkStrokePx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

/**
 * Fullscreen celebration overlay that appears when connection is established
 */
@Composable
fun ConnectedSuccessOverlay(
    remoteDevice: Device?,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textAlpha = remember { Animatable(0f) }
    val textOffset = remember { Animatable(20f) }

    LaunchedEffect(Unit) {
        delay(250)
        launch {
            textAlpha.animateTo(1f, tween(300))
        }
        launch {
            textOffset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
        }

        // Auto-complete after celebrating for ~1.4 seconds
        delay(1200)
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // Tap to proceed immediately
                onFinished()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            AnimatedCircularCheckmark(
                size = 96.dp,
                ringColor = Color(0xFF34C759),
                checkColor = Color.White,
                ringWidth = 4.dp,
                checkWidth = 4.5.dp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .scale(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Connected!",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        letterSpacing = (-0.5).sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = remoteDevice?.name ?: "Remote Peer",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
