package com.myra.assistant.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.myra.assistant.model.OrbState
import com.myra.assistant.ui.theme.MyraCyan
import com.myra.assistant.ui.theme.MyraPurple
import com.myra.assistant.ui.theme.MyraPurpleSoft
import com.myra.assistant.ui.theme.MyraRed
import com.myra.assistant.ui.theme.MyraRedDim
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MyraOrbCanvas(
    orbState: OrbState,
    amplitude: Float,
    modifier: Modifier = Modifier,
    orbSize: Dp = 230.dp,
) {
    val infinite = rememberInfiniteTransition(label = "myra_orb")

    val pulseScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.13f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val glowAlpha by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    val rotationAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    val thinkingAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1050, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "thinking",
    )

    val smoothAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = tween(90),
        label = "amp",
    )

    val (primary, secondary) = when (orbState) {
        OrbState.IDLE -> MyraRedDim to Color(0xFF880E4F)
        OrbState.LISTENING -> MyraRed to MyraPurple
        OrbState.SPEAKING -> MyraPurpleSoft to MyraRed
        OrbState.THINKING -> MyraCyan to Color(0xFF00B0FF)
    }

    Canvas(modifier = modifier.size(orbSize)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseRadius = (minOf(size.width, size.height) * 0.27f) * (pulseScale + smoothAmplitude * 0.14f)

        // 1. Radial outer glow (1.6x)
        val glowRadius = baseRadius * 1.65f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = glowAlpha * 0.55f), Color.Transparent),
                center = Offset(cx, cy),
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = Offset(cx, cy),
        )

        // 2. Core 3D sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary, secondary, Color(0xFF160008)),
                center = Offset(cx - baseRadius * 0.22f, cy - baseRadius * 0.22f),
                radius = baseRadius * 1.15f,
            ),
            radius = baseRadius,
            center = Offset(cx, cy),
        )

        // 3. Three rotating dashed rings
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 14f), 0f)
        for (i in 1..3) {
            val r = baseRadius * (1.12f + i * 0.14f)
            val dir = if (i % 2 == 0) -1f else 1f
            val ringColor = if (i % 2 == 0) secondary else primary
            rotate(degrees = rotationAngle * dir * (0.7f + i * 0.25f), pivot = Offset(cx, cy)) {
                drawCircle(
                    color = ringColor.copy(alpha = (0.72f - i * 0.16f).coerceAtLeast(0.2f)),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.6f, pathEffect = dashEffect),
                )
            }
        }

        // 4. Wave rings (amplitude-reactive sine modulation)
        val wavePhase = (rotationAngle / 360f) * (2f * Math.PI.toFloat()) *
            if (orbState == OrbState.SPEAKING) 2f else 1f
        for (ring in 0..1) {
            val waveR = baseRadius * (1.06f + ring * 0.15f + smoothAmplitude * 0.2f)
            val alpha = (0.55f - ring * 0.2f).coerceAtLeast(0.18f)
            var prev: Offset? = null
            for (deg in 0..360 step 12) {
                val rad = Math.toRadians(deg.toDouble()).toFloat()
                val mod = sin(rad * 6f + wavePhase + ring) * (3f + smoothAmplitude * 14f)
                val pt = Offset(cx + (waveR + mod) * cos(rad), cy + (waveR + mod) * sin(rad))
                prev?.let { p ->
                    drawLine(
                        color = primary.copy(alpha = alpha),
                        start = p,
                        end = pt,
                        strokeWidth = 2f,
                    )
                }
                prev = pt
            }
        }

        // 5. Thinking arcs
        if (orbState == OrbState.THINKING) {
            val tr = baseRadius * 1.28f
            val topLeft = Offset(cx - tr, cy - tr)
            val arcSize = Size(tr * 2f, tr * 2f)
            drawArc(
                color = MyraCyan,
                startAngle = thinkingAngle,
                sweepAngle = 78f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 5f, cap = StrokeCap.Round),
            )
            drawArc(
                color = MyraCyan,
                startAngle = thinkingAngle + 180f,
                sweepAngle = 78f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 5f, cap = StrokeCap.Round),
            )
        }

        // 6. Orbiting particles (12 dots when listening or speaking)
        if (orbState == OrbState.LISTENING || orbState == OrbState.SPEAKING) {
            val orbitR = baseRadius * (1.36f + smoothAmplitude * 0.18f)
            for (p in 0 until 12) {
                val angle = Math.toRadians((rotationAngle + p * 30f).toDouble())
                val px = cx + (orbitR * cos(angle)).toFloat()
                val py = cy + (orbitR * sin(angle)).toFloat()
                drawCircle(
                    color = (if (p % 2 == 0) primary else secondary).copy(alpha = 0.8f),
                    radius = 3.2f + smoothAmplitude * 3.2f,
                    center = Offset(px, py),
                )
            }
        }

        // 7. Inner specular highlight
        val hlCenter = Offset(cx - baseRadius * 0.28f, cy - baseRadius * 0.28f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.42f), Color.Transparent),
                center = hlCenter,
                radius = baseRadius * 0.48f,
            ),
            radius = baseRadius * 0.48f,
            center = hlCenter,
        )
    }
}

@Composable
fun WaveformVisualizer(
    amplitude: Float,
    isActive: Boolean,
    accentColor: Color = MyraRed,
    modifier: Modifier = Modifier,
) {
    val barCount = 20
    val bars = remember { mutableStateListOf<Float>().apply { repeat(barCount) { add(0.08f) } } }

    LaunchedEffect(amplitude, isActive) {
        val base = if (isActive) amplitude.coerceIn(0.06f, 1f) else 0.06f
        for (i in 0 until barCount) {
            val centerDist = kotlin.math.abs(i - (barCount - 1) / 2f) / (barCount / 2f)
            val envelope = (1f - centerDist * 0.65f).coerceAtLeast(0.25f)
            val jitter = if (isActive) (0.7f + ((i * 37 + (amplitude * 100).toInt()) % 5) * 0.1f) else 0.2f
            val target = (base * envelope * jitter).coerceIn(0.08f, 1f)
            bars[i] = bars[i] + (target - bars[i]) * 0.35f
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        val totalW = size.width
        val slotW = totalW / barCount
        val barW = (slotW * 0.52f).coerceAtLeast(3f)
        val maxH = size.height

        for (i in 0 until barCount) {
            val level = bars[i]
            val h = (maxH * level).coerceAtLeast(4f)
            val x = i * slotW + (slotW - barW) / 2f
            val y = (maxH - h) / 2f
            val alpha = (0.55f + level * 0.45f).coerceIn(0.55f, 1f)
            drawRoundRect(
                color = accentColor.copy(alpha = alpha),
                topLeft = Offset(x, y),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}
