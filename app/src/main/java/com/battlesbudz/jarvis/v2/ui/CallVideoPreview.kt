package com.battlesbudz.jarvis.v2.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.battlesbudz.jarvis.v2.voice.CallCameraPreview
import com.battlesbudz.jarvis.v2.voice.CallVisionRegistry
import com.battlesbudz.phoneinference.Box
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** A tracked object's box mapped into preview-view pixel coordinates. */
internal data class MappedTrackBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Map a detection box onto the camera preview.
 *
 * Detection boxes live in the analyzed frame's own pixel space, and that
 * frame is the camera buffer in sensor orientation. The preview shows the
 * same feed rotated upright (by [rotationDegrees]) and scaled by
 * PreviewView's FILL_CENTER (cover the view, crop the overflow, centered).
 * The mapping therefore: normalizes by the frame size, rotates both
 * corners into the upright orientation, optionally mirrors horizontally
 * (a front lens's preview is mirrored while its analysis frames are not —
 * the call binder uses the back camera, so production passes false), then
 * applies the same cover-scale and centering the PreviewView applies.
 * Returns null for degenerate geometry instead of drawing nonsense.
 */
internal fun mapFrameBoxToView(
    box: Box,
    frameWidth: Int,
    frameHeight: Int,
    rotationDegrees: Int,
    viewWidth: Float,
    viewHeight: Float,
    mirrorHorizontally: Boolean = false,
): MappedTrackBox? {
    if (frameWidth <= 0 || frameHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) return null
    val rotation = ((rotationDegrees % 360) + 360) % 360
    fun rotate(x: Float, y: Float): Pair<Float, Float> = when (rotation) {
        90 -> Pair(1f - y, x)
        180 -> Pair(1f - x, 1f - y)
        270 -> Pair(y, 1f - x)
        else -> Pair(x, y)
    }
    val (ax, ay) = rotate(box.x1 / frameWidth, box.y1 / frameHeight)
    val (bx, by) = rotate(box.x2 / frameWidth, box.y2 / frameHeight)
    var u1 = minOf(ax, bx)
    var v1 = minOf(ay, by)
    var u2 = maxOf(ax, bx)
    var v2 = maxOf(ay, by)
    if (mirrorHorizontally) {
        val flipped = 1f - u1
        u1 = 1f - u2
        u2 = flipped
    }
    // A quarter turn swaps the upright image's axes.
    val quarterTurn = rotation == 90 || rotation == 270
    val uprightWidth = if (quarterTurn) frameHeight else frameWidth
    val uprightHeight = if (quarterTurn) frameWidth else frameHeight
    val scale = maxOf(viewWidth / uprightWidth, viewHeight / uprightHeight)
    val drawnWidth = uprightWidth * scale
    val drawnHeight = uprightHeight * scale
    val offsetX = (viewWidth - drawnWidth) / 2f
    val offsetY = (viewHeight - drawnHeight) / 2f
    return MappedTrackBox(
        left = offsetX + u1 * drawnWidth,
        top = offsetY + v1 * drawnHeight,
        right = offsetX + u2 * drawnWidth,
        bottom = offsetY + v2 * drawnHeight,
    )
}

/**
 * Video-call step 5: the live call camera preview with tracked boxes.
 *
 * The PreviewView displays the [CallCameraPreview] use case — bound by the
 * capture pipeline itself, in the same CameraX lifecycle as the analysis
 * use case, so this is the same back-camera feed the frames sent to the
 * vision server come from, never a second camera session. The use case
 * exists only while capture is bound; until it lands (or if the camera
 * degraded) the area shows a plain "starting" state instead of a stale or
 * fake image. Boxes come from the step-3 tracker's PRESENT tracks, mapped
 * with [mapFrameBoxToView] using the hub's noted frame geometry, and are
 * redrawn whenever the holder reports a new result; a slow tick also
 * re-evaluates presence so a track that stops matching ages off the
 * overlay. Nothing here gates on speaking state: the preview stays live
 * while Jarvis answers.
 */
@Composable
internal fun CallVideoPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val previewUseCase by CallCameraPreview.preview.collectAsState()
    val holder = CallVisionRegistry.serverVision
    val idleRevision = remember { MutableStateFlow(0L) }
    val revision by (holder?.revision ?: idleRevision).collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val geometry = CallVisionRegistry.hub?.lastFrameGeometry
    // Same live-entry gate as step 4's prompt block: retained tracks of a
    // finished capture are diagnostics history, never a current scene.
    // Reading revision in the condition is what redraws the overlay when a
    // new detect result lands in the holder.
    val presentTracks = if (holder != null && holder.latest() != null && revision >= 0L) {
        holder.trackedSnapshot(nowMs).presentObjects
    } else {
        emptyList()
    }
    val aspect = if (geometry != null) {
        val quarterTurn = geometry.rotationDegrees == 90 || geometry.rotationDegrees == 270
        if (quarterTurn) {
            geometry.height.toFloat() / geometry.width
        } else {
            geometry.width.toFloat() / geometry.height
        }
    } else {
        // Back-camera frames are landscape buffers shown upright: 3:4
        // until the first frame's real geometry lands.
        3f / 4f
    }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    DisposableEffect(previewUseCase, previewView) {
        previewUseCase?.setSurfaceProvider(previewView.surfaceProvider)
        onDispose { previewUseCase?.setSurfaceProvider(null) }
    }
    val boxColor = MaterialTheme.colorScheme.primary
    val textMeasurer = rememberTextMeasurer()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Black,
        modifier = modifier.aspectRatio(aspect).testTag("call_video_preview"),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (previewUseCase != null) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    if (geometry != null) {
                        val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp)
                        for (track in presentTracks) {
                            val mapped = mapFrameBoxToView(
                                box = track.box,
                                frameWidth = geometry.width,
                                frameHeight = geometry.height,
                                rotationDegrees = geometry.rotationDegrees,
                                viewWidth = size.width,
                                viewHeight = size.height,
                                // The binder binds the back camera only;
                                // its preview is never mirrored.
                                mirrorHorizontally = false,
                            ) ?: continue
                            drawRect(
                                color = boxColor,
                                topLeft = Offset(mapped.left, mapped.top),
                                size = Size(mapped.right - mapped.left, mapped.bottom - mapped.top),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                            val label = "#${track.id} ${track.label}"
                            val measured = textMeasurer.measure(label, labelStyle)
                            val padX = 4.dp.toPx()
                            val padY = 2.dp.toPx()
                            val chipWidth = measured.size.width + padX * 2
                            val chipHeight = measured.size.height + padY * 2
                            // Chip sits above the box, or inside its top
                            // edge when the box hugs the preview's top.
                            val chipTop = if (mapped.top - chipHeight >= 0f) {
                                mapped.top - chipHeight
                            } else {
                                mapped.top
                            }
                            drawRect(
                                color = boxColor,
                                topLeft = Offset(mapped.left, chipTop),
                                size = Size(chipWidth, chipHeight),
                            )
                            drawText(
                                textLayoutResult = measured,
                                topLeft = Offset(mapped.left + padX, chipTop + padY),
                            )
                        }
                    }
                }
            } else {
                // No preview use case: either the bind is still landing or
                // the camera degraded/denied and the call is audio-only —
                // say which, from the controller's own state.
                val controllerState = CallVisionRegistry.controller?.state
                Text(
                    if (controllerState ==
                        com.battlesbudz.jarvis.v2.voice.CallVisionController.State.DEGRADED ||
                        controllerState ==
                        com.battlesbudz.jarvis.v2.voice.CallVisionController.State.DENIED
                    ) {
                        "Camera unavailable — audio only"
                    } else {
                        "Camera starting…"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }
    }
}
