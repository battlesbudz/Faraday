package com.battlesbudz.jarvis.v2.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** Layered continuous wave contours: audio changes their shape, not just their opacity. */
@Composable
internal fun VoiceOrb(phase: String, level: Float, modifier: Modifier = Modifier,
    diameter: Dp = 250.dp, showPhaseLabel: Boolean = true) {
    val lavender = MaterialTheme.colorScheme.primary
    val violet = Color(0xFF956BFA)
    val blue = Color(0xFF89BFFF)
    val paused = phase == "Mic paused"
    val reactive = phase == "Speaking" || phase == "Listening" || phase == "Hey Jarvis"
    val motion = rememberInfiniteTransition(label = "flowing voice waves").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "wave flow")
    val amplitude = animateFloatAsState(if (reactive) level.coerceIn(0f, 1f) else 0f,
        spring(dampingRatio = .8f, stiffness = 300f), label = "audio envelope")
    Column(
        modifier.then(if (showPhaseLabel) Modifier.fillMaxWidth().padding(vertical = 20.dp) else Modifier.size(diameter))
            .semantics { contentDescription = "Voice call: $phase" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(diameter)) {
            // Read animated values in the draw phase to avoid recomposing the screen every frame.
            val drawingScale = size.minDimension / 250.dp.toPx()
            val time = if (paused) 0f else motion.value
            val energy = amplitude.value
            val radius = size.minDimension * .31f
            val alpha = if (paused) .35f else 1f
            drawCircle(Brush.radialGradient(listOf(
                violet.copy(alpha = (.12f + energy * .14f) * alpha),
                lavender.copy(alpha = .035f * alpha), Color.Transparent),
                center = center, radius = size.minDimension * .5f))
            repeat(5) { layer ->
                val path = Path()
                val shift = layer * .65f
                val depth = size.minDimension * (.023f + energy * .075f)
                for (point in 0..160) {
                    val angle = point * 2 * PI / 160
                    val ripple = sin(angle * 3 + time * 2 + shift) * .52 +
                        sin(angle * 5 - time * 3 + shift) * .28 +
                        sin(angle * 2 + time - shift) * .20
                    val distance = radius + layer * (1.4.dp.toPx() * drawingScale) + ripple.toFloat() * depth
                    val x = center.x + cos(angle).toFloat() * distance
                    val y = center.y + sin(angle).toFloat() * distance
                    if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                val ink = Brush.linearGradient(listOf(blue.copy(alpha = .75f * alpha),
                    lavender.copy(alpha = alpha), violet.copy(alpha = .65f * alpha)),
                    start = Offset(0f, size.height), end = Offset(size.width, 0f))
                // Broad translucent contours create a soft halo without expensive blur filters.
                drawPath(path, ink, alpha = .055f, style = Stroke((10.dp.toPx() * drawingScale)))
                drawPath(path, ink, alpha = .035f, style = Stroke((20.dp.toPx() * drawingScale)))
                drawPath(path, ink, alpha = .65f - layer * .09f,
                    style = Stroke(((1.8f - layer * .16f).dp.toPx() * drawingScale)))
            }
            // Fine, flowing ribbons cross the core; their height follows microphone/playback energy.
            repeat(3) { layer ->
                val path = Path()
                val halfWidth = radius * .82f
                for (point in 0..120) {
                    val fraction = point / 120f
                    val envelope = sin(PI * fraction).pow(2).toFloat()
                    val wave = sin(fraction * PI * 3 + time * 2 + layer * .8).toFloat()
                    val x = center.x - halfWidth + fraction * halfWidth * 2
                    val y = center.y + wave * envelope * radius * (.08f + energy * .48f) + (layer - 1) * (3.dp.toPx() * drawingScale)
                    if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Brush.horizontalGradient(listOf(Color.Transparent,
                    lavender.copy(alpha = (.6f - layer * .12f) * alpha), blue.copy(alpha = .6f * alpha),
                    Color.Transparent)), style = Stroke(1.3.dp.toPx() * drawingScale))
            }
        }
        if (showPhaseLabel) Text(phase, modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleMedium, color = lavender)
    }
}

@Composable
internal fun VoiceCaption(text: String, speaker: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 180.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (text.isNotBlank()) {
            Text(speaker, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            val split = text.lastIndexOf(' ')
            Text(buildAnnotatedString {
                if (split >= 0) append(text.substring(0, split + 1))
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                    append(text.substring(split + 1))
                }
            }, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center, maxLines = 6)
        } else Text("Your voice calls stay on this phone.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
