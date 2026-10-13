package com.battlesbudz.jarvis.v2.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * A small native character, not a progress indicator. Its caller owns the truth
 * about work and accessible status text. Cards are illustrative, never controls.
 * The drawing uses a 168 × 108 viewport; paths are reused and no blur/offscreen
 * bitmap is required. Read animation state in Canvas, not in the screen's layout.
 */
@Composable
internal fun WispCharacter(
    state: WispPresentation,
    level: Float,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = true,
    audioActivity: WispActivity? = state.activity.takeIf { it == WispActivity.SPEAKING || it == WispActivity.LISTENING },
    receivedKey: Long? = null,
    onMotionFrame: ((Boolean) -> Unit)? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Capture one plain Float per composition/effect. A delegated state getter inside
    // withFrameNanos could observe scale zero before cancellation of the old effect.
    val frameDurationScale = rememberWispDurationScale().value
    val moving = motionEnabled && started && frameDurationScale > 0f &&
        (state.activity != WispActivity.PAUSED || state.gesture == WispGesture.WAITING || audioActivity != null)
    val clock = remember { mutableFloatStateOf(0f) }
    val eventAge = remember(state.activity, state.taskKey) { WispOneShotAge(moving) }
    val receivedAge = remember(receivedKey) { WispOneShotAge(moving) }
    val currentEventAge by rememberUpdatedState(eventAge)
    val currentReceivedAge by rememberUpdatedState(receivedAge)
    LaunchedEffect(moving, frameDurationScale) {
        if (!moving) {
            // Never replay a transient receipt or nod when motion/lifecycle resumes.
            currentEventAge.consume()
            currentReceivedAge.consume()
        } else {
            var previousFrame = 0L
            while (isActive) {
                withFrameNanos { frame ->
                    if (previousFrame != 0L) {
                        // Bound the first frame after a stall. Wrap well beyond a
                        // blink cycle to retain Float precision during long calls.
                        val elapsed = (frame - previousFrame) / 1_000_000_000f
                        val step = WispMotion.frameStep(elapsed, frameDurationScale)
                        clock.floatValue = WispMotion.advanceClock(clock.floatValue, step)
                        currentEventAge.advance(step)
                        currentReceivedAge.advance(step)
                    }
                    previousFrame = frame
                }
                // This tiny character does not need a 120 Hz animation loop.
                delay(24)
            }
        }
    }
    val reactive = audioActivity != null
    val audioLevel = if (reactive && level.isFinite()) level.coerceIn(0f, 1f) else 0f
    val envelope = key(audioActivity) {
        if (moving) {
            animateFloatAsState(audioLevel, tween(110), label = "Wisp audio envelope")
        } else {
            rememberUpdatedState(0f)
        }
    }
    val paths = remember { WispPaths() }
    Canvas(modifier.size(168.dp, 108.dp).clearAndSetSemantics { }) {
        val factor = min(size.width / 168f, size.height / 108f)
        translate((size.width - 168f * factor) / 2f, (size.height - 108f * factor) / 2f) {
            scale(factor, factor, pivot = Offset.Zero) {
                drawWisp(paths, state, audioActivity, if (moving) clock.floatValue else 0f,
                    eventAge.age, receivedKey?.takeIf {
                        state.taskKey == "activity:$it" && (state.bodyActivity ?: state.activity) == WispActivity.THINKING
                    }?.let { receivedAge.age },
                    if (reactive) envelope.value else 0f, moving)
            }
        }
        // Optional read-only render observation. Report only after the complete draw.
        onMotionFrame?.invoke(moving)
    }
}

/** Also updates when a user changes Android's animation accessibility setting. */
@Composable
internal fun rememberWispDurationScale(): State<Float> {
    val resolver = LocalContext.current.contentResolver
    fun readScale(): Float = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            .takeIf { it.isFinite() }?.coerceIn(0f, 10f) ?: 1f
    }.getOrDefault(1f)
    val scale = remember(resolver) { mutableFloatStateOf(readScale()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale.floatValue = readScale() }
        }
        val registered = runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
            )
        }.isSuccess
        onDispose { if (registered) resolver.unregisterContentObserver(observer) }
    }
    return scale
}

private val WispCyan = Color(0xFF83E8FF)
private val WispIce = Color(0xFFE4FCFF)
private val WispDeep = Color(0xFF06394A)
private val WispGold = Color(0xFFFFD18A)
private val WispCoral = Color(0xFFFFB0AD)

/** A curled flame with an open crest and trailing comma, rather than an orb. */
private class WispPaths {
    val particles = listOf(Offset(55f, 48f), Offset(83f, 38f), Offset(90f, 77f), Offset(60f, 79f))
    val body = Path().apply {
        moveTo(73f, 86f)
        cubicTo(54f, 86f, 39f, 75f, 42f, 58f)
        cubicTo(44f, 44f, 59f, 38f, 71f, 33f)
        cubicTo(82f, 28f, 85f, 24f, 82f, 18f)
        cubicTo(78f, 10f, 86f, 3f, 95f, 6f)
        cubicTo(103f, 8f, 105f, 15f, 100f, 18f)
        cubicTo(98f, 19f, 96f, 17f, 97f, 15f)
        cubicTo(99f, 17f, 101f, 14f, 98f, 11f)
        cubicTo(92f, 7f, 86f, 12f, 89f, 18f)
        cubicTo(92f, 25f, 101f, 28f, 99f, 38f)
        cubicTo(98f, 46f, 89f, 50f, 91f, 58f)
        cubicTo(92f, 65f, 101f, 69f, 102f, 78f)
        cubicTo(105f, 93f, 90f, 100f, 78f, 94f)
        cubicTo(89f, 97f, 93f, 89f, 83f, 87f)
        cubicTo(80f, 86f, 76f, 86f, 73f, 86f)
        close()
    }
    val ribbons = listOf(
        Path().apply {
            moveTo(90f, 8f)
            cubicTo(76f, 17f, 94f, 25f, 70f, 37f)
            cubicTo(55f, 43f, 43f, 53f, 47f, 67f)
            cubicTo(49f, 79f, 68f, 86f, 79f, 86f)
            cubicTo(96f, 88f, 95f, 96f, 83f, 96f)
        },
        Path().apply {
            moveTo(87f, 19f)
            cubicTo(102f, 38f, 80f, 41f, 80f, 55f)
            cubicTo(80f, 66f, 97f, 69f, 97f, 80f)
            cubicTo(98f, 87f, 94f, 92f, 89f, 94f)
        },
        Path().apply {
            moveTo(82f, 28f)
            cubicTo(80f, 40f, 59f, 46f, 56f, 58f)
            cubicTo(52f, 73f, 65f, 80f, 80f, 81f)
            cubicTo(94f, 82f, 100f, 87f, 92f, 93f)
        },
        Path().apply {
            moveTo(92f, 26f)
            cubicTo(97f, 38f, 89f, 40f, 84f, 48f)
            cubicTo(73f, 65f, 87f, 70f, 91f, 78f)
        },
    )
    val rim = Path().apply {
        moveTo(79f, 31f)
        cubicTo(67f, 39f, 48f, 43f, 44f, 58f)
        cubicTo(40f, 73f, 54f, 82f, 65f, 84f)
    }
    val forelock = Path().apply {
        moveTo(89f, 21f)
        cubicTo(93f, 34f, 84f, 37f, 75f, 40f)
        cubicTo(85f, 36f, 93f, 38f, 94f, 31f)
        close()
    }
}

private fun DrawScope.drawWisp(paths: WispPaths, state: WispPresentation, audioActivity: WispActivity?,
    rawTime: Float, eventAge: Float, receivedAge: Float?, rawLevel: Float, moving: Boolean) {
    // Android's native gradient rejects NaN coordinates. Defend this boundary as
    // well as the frame producer; reduced motion must remain static for any input.
    val time = if (moving) WispMotion.clockTime(rawTime) else 0f
    val level = if (moving && rawLevel.isFinite()) rawLevel.coerceIn(0f, 1f) else 0f
    val activity = state.bodyActivity ?: state.activity
    val gesture = state.gesture
    val hasCard = when (activity) {
        WispActivity.CONNECTING, WispActivity.CHECKING, WispActivity.EDITING,
        WispActivity.APPROVAL, WispActivity.SUCCESS, WispActivity.ERROR -> true
        else -> false
    }
    val working = activity == WispActivity.CONNECTING || activity == WispActivity.CHECKING ||
        activity == WispActivity.EDITING
    val paused = activity == WispActivity.PAUSED && gesture != WispGesture.WAITING
    val opacity = if (paused) .62f else 1f
    val drift = (if (paused) 0f else sin(time * if (gesture == WispGesture.WAITING) .75f else 1.45f) * 1.35f) +
        WispMotion.bounce(activity, eventAge, moving)
    val breathe = sin(time * 1.7f) * .007f
    val xShift = if (hasCard) -17f else 9f
    val tilt = when {
        gesture == WispGesture.WAITING -> 0f
        working -> 7f + sin(time * 1.9f) * 1.5f
        activity == WispActivity.LISTENING -> 3f + sin(time * .9f) * .8f
        activity == WispActivity.THINKING -> -6f
        activity == WispActivity.ERROR -> -4f
        else -> sin(time * .9f) * 1.2f
    }

    // A broad transparent footprint keeps the character light against the UI.
    translate(xShift, 0f) {
        drawOval(Brush.radialGradient(listOf(WispCyan.copy(alpha = .17f * opacity), Color.Transparent),
            center = Offset(75f, 100f), radius = 39f), Offset(34f, 95f), Size( 80f, 10f))
    }
    if (hasCard) drawWispCard(activity, gesture, time)
    if (working) drawWispTendril(activity, gesture, time, drift)
    if (gesture == WispGesture.WAITING) drawWispWaiting()

    translate(xShift, 4f + drift) {
        rotate(tilt + WispMotion.nod(receivedAge, moving), Offset(74f,  60f)) {
            scale(1f + breathe + level * .025f, 1f - breathe + level * .045f, Offset(73f, 78f)) {
                val tint = if (paused) Color(0xFF92BDC8) else WispCyan
                drawPath(paths.body, tint, alpha = .023f * opacity, style = Stroke(13f))
                drawPath(paths.body, tint, alpha = .05f * opacity, style = Stroke(7f))
                drawPath(paths.body, tint, alpha = .16f * opacity, style = Stroke(3f))
                drawPath(paths.body, Brush.linearGradient(
                    listOf(WispIce.copy(alpha = .85f), tint.copy(alpha = .74f),
                        Color(0xFF229ABD).copy(alpha = .56f), WispIce.copy(alpha = .87f)),
                    Offset(45f, 19f), Offset(104f, 90f)), alpha = opacity)
                clipPath(paths.body) {
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFDF6DE).copy(alpha = .94f * opacity),
                        WispIce.copy(alpha = .66f * opacity), Color.Transparent),
                        center = Offset(65f, 64f), radius = 30f), 30f, Offset(65f, 64f))
                    if (activity == WispActivity.THINKING && gesture != WispGesture.WAITING) {
                        drawCircle(WispIce, 15f, Offset(66f, 64f),
                            alpha = (.07f + .05f * (sin(time * 2f) + 1f)) * opacity)
                    }
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFDE5BB).copy(alpha = .55f * opacity),
                        Color.Transparent), center = Offset(85f, 83f), radius = 18f), 18f, Offset(85f, 83f))
                    drawPath(paths.forelock, WispIce.copy(alpha = .4f * opacity))
                    paths.ribbons.forEachIndexed { index, ribbon ->
                        drawPath(ribbon, WispIce, alpha = (.095f + index * .009f) * opacity,
                            style = Stroke(5f - index * .5f, cap = StrokeCap.Round))
                        drawPath(ribbon, Brush.linearGradient(listOf(WispIce.copy(alpha = .13f),
                            WispIce.copy(alpha = .7f), tint.copy(alpha = .12f), WispIce.copy(alpha = .7f)),
                            Offset(45f, 15f + sin(time * .8f) * 5f), Offset(100f, 96f)),
                            alpha = opacity, style = Stroke(.7f + index * .1f, cap = StrokeCap.Round))
                    }
                    // Tiny embedded points read as suspended light, not confetti.
                    paths.particles.forEachIndexed { index, point ->
                        drawCircle(WispIce, .55f, point, alpha = (.45f + .15f * sin(time + index)) * opacity)
                    }
                }
                drawPath(paths.body, Brush.linearGradient(listOf(WispIce.copy(alpha = .9f),
                    tint.copy(alpha = .4f), WispIce.copy(alpha = .85f)), Offset(42f, 25f), Offset(102f, 90f)),
                    alpha = opacity, style = Stroke(.85f))
                drawPath(paths.rim, WispIce, alpha = .92f * opacity, style = Stroke(1.15f, cap = StrokeCap.Round))
                drawWispFace(activity, audioActivity, gesture, time, WispMotion.mouthLevel(audioActivity, level, moving), opacity)
            }
        }
        // Two detached droplets extend the silhouette and reinforce its fluidity.
        rotate(20f, Offset(104f,  40f)) {
            drawOval(WispCyan.copy(alpha = .2f * opacity), Offset(101f, 35f), Size(5f, 8f))
            drawOval(WispIce.copy(alpha = .75f * opacity), Offset(102.1f, 35.8f), Size(1.4f, 3.5f))
        }
        drawCircle(WispCyan.copy(alpha = .5f * opacity), 1.1f, Offset(107f, 27f + sin(time) * 1.5f))
        if (audioActivity != null) {
            drawWispVoiceRipples(level, opacity)
        }
        if (activity == WispActivity.THINKING && gesture != WispGesture.WAITING) {
            repeat(3) { index ->
                val alpha = .25f + .35f * ((sin(time * 2f - index * .9f) + 1f) / 2f)
                drawCircle(WispCyan.copy(alpha = alpha), 1.5f - index * .25f,
                    Offset(108f + index * 5f,  50f - index * 6f))
            }
        }
        if (activity == WispActivity.SUCCESS) {
            drawWispSparkle(Offset(38f, 42f), 3f, WispIce)
            drawWispSparkle(Offset(99f, 22f), 2.1f, WispCyan)
        }
    }
}

private fun DrawScope.drawWispFace(activity: WispActivity, audioActivity: WispActivity?, gesture: WispGesture,
    time: Float, level: Float, alpha: Float) {
    val happy = activity == WispActivity.SUCCESS
    val thinking = activity == WispActivity.THINKING
    val worried = activity == WispActivity.ERROR
    val sleepy = activity == WispActivity.PAUSED && gesture != WispGesture.WAITING
    val gaze = if (gesture == WispGesture.READING) 1.5f + sin(time * 1.1f) * .65f else when (activity) {
        WispActivity.CONNECTING, WispActivity.CHECKING, WispActivity.EDITING -> 2f
        WispActivity.THINKING -> -.8f
        else -> 0f
    }
    val blinkPhase = time % 5.8f
    val blink = if (blinkPhase in 4.8f..4.98f) sin((blinkPhase - 4.8f) / .18f * PI).toFloat() else 0f
    val eyeHeight = (if (sleepy) 3f else if (thinking) 5.5f else 8.4f) * (1f - blink * .94f)
    val faceInk = WispDeep.copy(alpha = alpha)
    listOf(Offset(57f + gaze,  60f), Offset(77f + gaze, 62f)).forEach { eye ->
        if (happy) {
            drawArc(faceInk, 202f, 136f, false, eye - Offset(3.3f, 2f), Size(7f, 6f),
                style = Stroke(1.65f, cap = StrokeCap.Round))
        } else {
            drawOval(Brush.linearGradient(listOf(Color(0xFF041E2C), Color(0xFF246176)),
                eye - Offset(0f, eyeHeight / 2f), eye + Offset(0f, eyeHeight / 2f)),
                eye - Offset(3.05f, eyeHeight / 2f), Size(6.1f, eyeHeight), alpha = alpha)
            if (eyeHeight > 3f) {
                drawCircle(WispIce.copy(alpha = .98f * alpha), 1.03f, eye + Offset(.85f, -eyeHeight * .24f))
                drawCircle(WispCyan.copy(alpha = .75f * alpha), .43f, eye + Offset(-.65f, eyeHeight * .27f))
            }
        }
    }
    // Low-opacity peach cheeks keep the expression warm without changing cyan identity.
    drawOval(Color(0xFFF4C5AE).copy(alpha = .34f * alpha), Offset(48f + gaze,  60f + 7f), Size(8f, 3.7f))
    drawOval(Color(0xFFF4C5AE).copy(alpha = .30f * alpha), Offset(78f + gaze, 69f), Size(7.8f, 3.6f))
    // Match the selected 2× width / 1.65× opening preview. Only the mouth grows.
    // Silence and interruption restore the resting smile; no invented syllable clock.
    if (audioActivity == WispActivity.SPEAKING && level > .01f) {
        val mouthWidth = 4.4f * WispMotion.mouthWidthScale
        val mouthHeight = (2.5f + level * 4f) * WispMotion.mouthHeightScale
        drawOval(faceInk, Offset(67.4f + gaze - mouthWidth / 2f, 69f), Size(mouthWidth, mouthHeight))
        if (mouthHeight > 4f) {
            val tongueWidth = 2.7f * WispMotion.mouthWidthScale
            val tongueHeight = 1.4f * WispMotion.mouthHeightScale
            drawOval(Color(0xFFFFD5CB), Offset(67.35f + gaze - tongueWidth / 2f,
                69f + mouthHeight * .77f - tongueHeight / 2f), Size(tongueWidth, tongueHeight))
        }
    } else {
        scale(WispMotion.mouthWidthScale, WispMotion.smileHeightScale, Offset(67f + gaze, 71f)) {
            val mouth = Path().apply {
                moveTo(64f + gaze, 70f)
                quadraticTo(67f + gaze, if (worried) 68f else 74f, 70f + gaze, 70.6f)
            }
            drawPath(mouth, faceInk, style = Stroke(1.1f, cap = StrokeCap.Round))
        }
    }
    if (thinking || worried || activity == WispActivity.LISTENING) {
        drawLine(faceInk.copy(alpha = .62f * alpha), Offset(54f + gaze, if (worried) 53f else 52f),
            Offset(59f + gaze, if (worried) 51f else 51.4f), .8f, StrokeCap.Round)
        drawLine(faceInk.copy(alpha = .62f * alpha), Offset(75f + gaze, 52.5f),
            Offset(80f + gaze, if (worried) 55f else 54f), .8f, StrokeCap.Round)
    }
}

private fun DrawScope.drawWispVoiceRipples(level: Float, opacity: Float) {
    // No nonzero fabricated audio envelope. Static short arcs still denote listening.
    repeat(2) { index ->
        val radius = 38f + index * 7f + level * 5f
        val alpha = (.12f + level * .44f) / (index + 1f) * opacity
        drawArc(WispCyan.copy(alpha = alpha), 154f, 35f, false,
            Offset(73f - radius, 64f - radius * .55f), Size(radius * 2f, radius * 1.1f),
            style = Stroke(1.1f, cap = StrokeCap.Round))
        drawArc(WispCyan.copy(alpha = alpha), -24f, 35f, false,
            Offset(73f - radius, 64f - radius * .55f), Size(radius * 2f, radius * 1.1f),
            style = Stroke(1.1f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawWispTendril(activity: WispActivity, gesture: WispGesture, time: Float, drift: Float) {
    val adjusting = activity == WispActivity.EDITING
    val reachX = when (gesture) {
        WispGesture.TAP -> 128f
        WispGesture.SCROLL -> 134f
        WispGesture.VOLUME -> 128f + sin(time * 2.4f) * 3f
        else -> if (adjusting) 124f else 108f
    }
    val reachY = when (gesture) {
        WispGesture.TAP -> 58f + sin(time * 2.4f) * 1.5f
        WispGesture.SCROLL -> 58f + sin(time * 1.4f) * 7f
        else -> if (adjusting) 58f else 50f + sin(time * 1.4f) * 2f
    }
    val tendril = Path().apply {
        moveTo( 70f, 68f + drift)
        cubicTo(87f, 67f, 94f, reachY + 4f, reachX, reachY)
    }
    drawPath(tendril, WispCyan, alpha = .07f, style = Stroke(10f, cap = StrokeCap.Round))
    drawPath(tendril, WispCyan, alpha = .22f, style = Stroke(5f, cap = StrokeCap.Round))
    drawPath(tendril, Brush.horizontalGradient(listOf(WispCyan.copy(alpha = .22f), WispIce.copy(alpha = .9f))),
        style = Stroke(1.7f, cap = StrokeCap.Round))
    drawCircle(WispCyan.copy(alpha = .18f), 5.2f, Offset(reachX, reachY))
    drawCircle(WispIce.copy(alpha = .9f), 2f, Offset(reachX, reachY))
}

private fun DrawScope.drawWispCard(activity: WispActivity, gesture: WispGesture, time: Float) {
    val accent = when (activity) {
        WispActivity.APPROVAL -> WispGold
        WispActivity.ERROR -> WispCoral
        else -> WispCyan
    }
    if (gesture == WispGesture.RESEARCH) {
        drawRoundRect(accent.copy(alpha = .07f), Offset(110f, 25f), Size(43f, 55f), CornerRadius(7f))
        drawRoundRect(accent.copy(alpha = .32f), Offset(110f, 25f), Size(43f, 55f),
            CornerRadius(7f), style = Stroke(.7f))
    }
    val topLeft = Offset(105f, 29f)
    val cardSize = Size( 40f + 4f, 55f)
    val corner = CornerRadius(7f)
    drawRoundRect(accent.copy(alpha = .025f), topLeft - Offset(2f, 2f), Size(48f, 59f), CornerRadius(9f))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF294550).copy(alpha = .93f), Color(0xFF172833).copy(alpha = .95f)),
        topLeft, topLeft + Offset(44f, 55f)), topLeft, cardSize, corner)
    drawRoundRect(accent.copy(alpha = .45f), topLeft, cardSize, corner, style = Stroke(.7f))
    repeat(3) { drawCircle(accent.copy(alpha = .42f), 1f, Offset(112f + it * 4f, 35f)) }
    drawLine(accent.copy(alpha = .14f), Offset(110f,  40f), Offset(144f, 40f), .7f)
    if (gesture == WispGesture.READING) {
        drawWispPage(accent)
        return
    }
    when (activity) {
        WispActivity.CONNECTING -> {
            // A generic app tile does not imply web navigation or a remote site.
            drawRoundRect(accent.copy(alpha = .08f), Offset(116f, 47f), Size(22f, 22f), CornerRadius(3f))
            drawRoundRect(accent.copy(alpha = .65f), Offset(116f, 47f), Size(22f, 22f),
                CornerRadius(3f), style = Stroke(.9f))
            drawLine(accent.copy(alpha = .5f), Offset(116f, 53f), Offset(138f, 53f), .8f)
            drawCircle(accent.copy(alpha = .7f), .85f, Offset(120f, 50f))
            drawRoundRect(accent.copy(alpha = .55f), Offset(122f, 57f), Size(10f, 7f), CornerRadius(1.6f))
            drawLine(accent.copy(alpha = .33f), Offset(117f, 76f), Offset(137f, 76f), 1.5f, StrokeCap.Round)
        }
        WispActivity.CHECKING -> {
            repeat(3) { row ->
                drawLine(accent.copy(alpha = if (row == 1) .6f else .23f),
                    Offset(114f, 49f + row * 10f), Offset(139f - row * 3f, 49f + row * 10f), 2f, StrokeCap.Round)
            }
            val lens = Offset(125f, 57f + sin(time * 1.4f) * 3f)
            drawCircle(Color(0xFF183947), 6f, lens)
            drawCircle(accent.copy(alpha = .12f), 6f, lens)
            drawCircle(WispIce.copy(alpha = .9f), 6f, lens, style = Stroke(1.25f))
            drawLine(WispIce.copy(alpha = .9f), lens + Offset(4.5f, 4.5f), lens + Offset(8f, 8f), 1.8f, StrokeCap.Round)
        }
        WispActivity.EDITING -> when (gesture) {
            WispGesture.VOLUME -> {
                // A slider is reserved for an observed volume operation.
                drawLine(accent.copy(alpha = .27f), Offset(114f, 58f), Offset(140f, 58f), 2.5f, StrokeCap.Round)
                val handleX = 128f + sin(time * 2.4f) * 3f
                drawLine(accent.copy(alpha = .75f), Offset(114f, 58f), Offset(handleX, 58f), 2.5f, StrokeCap.Round)
                drawCircle(WispIce, 3f, Offset(handleX, 58f))
                val speaker = Path().apply {
                    moveTo(116f, 46f); lineTo(120f, 46f); lineTo(124f, 43f)
                    lineTo(124f, 53f); lineTo(120f, 50f); lineTo(116f, 50f); close()
                }
                drawPath(speaker, accent.copy(alpha = .7f))
                drawArc(accent.copy(alpha = .6f), -55f, 110f, false,
                    Offset(122f, 44f), Size(8f, 8f), style = Stroke(.9f))
            }
            WispGesture.TAP -> {
                drawRoundRect(accent.copy(alpha = .13f), Offset(116f, 48f), Size(23f, 20f), CornerRadius(4f))
                drawRoundRect(accent.copy(alpha = .65f), Offset(116f, 48f), Size(23f, 20f), CornerRadius(4f), style = Stroke(.9f))
                drawCircle(accent.copy(alpha = .28f), 5f + sin(time * 2.4f) * 1.5f, Offset(128f, 58f), style = Stroke(.8f))
            }
            WispGesture.SCROLL -> {
                repeat(4) { row -> drawLine(accent.copy(alpha = .35f), Offset(114f, 47f + row * 8f),
                    Offset(123f, 47f + row * 8f), 1.2f, StrokeCap.Round) }
                drawLine(accent.copy(alpha = .4f), Offset(134f, 47f), Offset(134f, 71f), 1f, StrokeCap.Round)
                drawCircle(WispIce.copy(alpha = .8f), 2f, Offset(134f, 58f + sin(time * 1.4f) * 7f))
            }
            else -> {
                // Other real tools get a neutral activity tile, not a fictitious slider.
                repeat(3) { row -> drawLine(accent.copy(alpha = .4f), Offset(115f, 49f + row * 10f),
                    Offset(138f, 49f + row * 10f), 1.4f, StrokeCap.Round) }
            }
        }
        WispActivity.APPROVAL -> {
            // Decorative open palm. Only the existing explicit approval controls can act.
            val palm = Path().apply {
                moveTo(120f, 65f); lineTo(117f, 61f); lineTo(117f, 55f)
                quadraticTo(118f, 53f, 121f, 57f); lineTo(121f, 47f)
                quadraticTo(123f, 44f, 124f, 47f); lineTo(124f, 54f)
                lineTo(124f, 44f); quadraticTo(126f, 41f, 127f, 44f); lineTo(127f, 54f)
                lineTo(127f, 46f); quadraticTo(129f, 43f, 130f, 46f); lineTo(130f, 55f)
                lineTo(130f, 50f); quadraticTo(132f, 47f, 133f, 50f); lineTo(133f, 60f)
                quadraticTo(133f, 71f, 120f, 65f); close()
            }
            drawPath(palm, accent.copy(alpha = .12f))
            drawPath(palm, accent.copy(alpha = .9f), style = Stroke(1f, cap = StrokeCap.Round))
            drawLine(accent.copy(alpha = .45f), Offset(117f, 75f), Offset(137f, 75f), 1.5f, StrokeCap.Round)
        }
        WispActivity.SUCCESS -> {
            drawCircle(accent.copy(alpha = .13f), 12f, Offset(127f, 58f))
            drawCircle(accent.copy(alpha = .7f), 11f, Offset(127f, 58f), style = Stroke(.9f))
            val check = Path().apply { moveTo(121f, 58f); lineTo(125f, 62f); lineTo(133f, 53f) }
            drawPath(check, WispIce, style = Stroke(2.3f, cap = StrokeCap.Round))
            drawLine(accent.copy(alpha = .4f), Offset(117f, 76f), Offset(137f, 76f), 1.5f, StrokeCap.Round)
        }
        WispActivity.ERROR -> {
            drawCircle(accent.copy(alpha = .1f), 11f, Offset(127f, 57f))
            drawCircle(accent.copy(alpha = .65f), 10f, Offset(127f, 57f), style = Stroke(1f))
            drawLine(accent, Offset(127f, 51f), Offset(127f, 58f), 1.8f, StrokeCap.Round)
            drawCircle(accent, 1.1f, Offset(127f, 62f))
            drawLine(accent.copy(alpha = .35f), Offset(117f, 74f), Offset(137f, 74f), 1.5f, StrokeCap.Round)
        }
        else -> Unit
    }
}

private fun DrawScope.drawWispSparkle(center: Offset, radius: Float, color: Color) {
    drawLine(color.copy(alpha = .7f), center - Offset(radius, 0f), center + Offset(radius, 0f), .8f, StrokeCap.Round)
    drawLine(color.copy(alpha = .7f), center - Offset(0f, radius), center + Offset(0f, radius), .8f, StrokeCap.Round)
}

private fun DrawScope.drawWispPage(accent: Color) {
    val page = Path().apply {
        moveTo(116f, 45f); lineTo(132f, 45f); lineTo(140f, 53f)
        lineTo(140f, 76f); lineTo(116f, 76f); close()
        moveTo(132f, 45f); lineTo(132f, 53f); lineTo(140f, 53f)
    }
    drawPath(page, accent.copy(alpha = .65f), style = Stroke(.9f))
    repeat(4) { row -> drawLine(accent.copy(alpha = .35f), Offset(120f, 57f + row * 4f),
        Offset(136f - row % 2 * 3f, 57f + row * 4f), .8f, StrokeCap.Round) }
}

private fun DrawScope.drawWispWaiting() {
    // A static clock symbol means waiting, never a countdown or promised finish time.
    drawCircle(WispCyan.copy(alpha = .4f), 7f, Offset(121f, 57f), style = Stroke(.9f))
    drawLine(WispCyan.copy(alpha = .6f), Offset(121f, 57f), Offset(121f, 52f), .9f, StrokeCap.Round)
    drawLine(WispCyan.copy(alpha = .6f), Offset(121f, 57f), Offset(125f, 59f), .9f, StrokeCap.Round)
}
