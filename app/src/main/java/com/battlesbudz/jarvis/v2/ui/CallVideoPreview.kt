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
import com.battlesbudz.jarvis.v2.voice.PreviewOverlayTelemetry
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
 * with [mapFrameBoxToView] using the hub's noted frame geometry. Nothing
 * here gates on speaking state: the preview stays live while Jarvis
 * answers.
 *
 * Two structural choices are the build-1608 fix. That build's device test
 * showed the preview but no boxes, while the same call's diagnostics
 * proved the holder held present tracks the whole time (step 4's prompt
 * block read them from the same holder, with the same snapshot call, at
 * the same moments) — so the failure lived in this paint layer, and this
 * composable removes both of its candidate mechanisms:
 *
 * 1. The draw reads live state IN THE DRAW PHASE. The old overlay
 *    computed its track list during composition and captured it into the
 *    Canvas lambda; here the Canvas reads the holder, the hub geometry,
 *    and the tracker at draw time, and reads the revision + tick snapshot
 *    states so every new detect result (and a 1 s tick, which also ages
 *    tracks off) invalidates the draw. A draw can therefore never paint
 *    a stale composition-time snapshot.
 * 2. The PreviewView runs in COMPATIBLE (TextureView) mode, not the
 *    default PERFORMANCE (SurfaceView) mode. A SurfaceView composites in
 *    its own window layer and can paint over app-layer siblings; a
 *    TextureView renders in the normal view hierarchy, so the overlay
 *    Canvas — a later sibling in the same Box — paints above it.
 *
 * Every draw also records itself in [PreviewOverlayTelemetry], which the
 * call diagnostics export, so a paste can prove what the overlay did.
 */
@Composable
internal fun CallVideoPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val previewUseCase by CallCameraPreview.preview.collectAsState()
    val holder = CallVisionRegistry.serverVision
    val idleRevision = remember { MutableStateFlow(0L) }
    val revisionState = (holder?.revision ?: idleRevision).collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val geometry = CallVisionRegistry.hub?.lastFrameGeometry
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
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(previewUseCase, previewView) {
        previewUseCase?.setSurfaceProvider(previewView.surfaceProvider)
        if (previewUseCase != null) {
            PreviewOverlayTelemetry.recordSurfaceBound("COMPATIBLE")
        }
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
                    // Snapshot-state reads in the draw phase: subscribing
                    // this draw to the holder's revision (bumped on every
                    // detect result) and to the 1 s tick is what re-runs
                    // the block; the scene itself is then read live, below.
                    val drawRevision = revisionState.value
                    val drawNowMs = nowMs
                    val liveHolder = CallVisionRegistry.serverVision
                    val liveGeometry = CallVisionRegistry.hub?.lastFrameGeometry
                    val liveEntry = liveHolder?.latest()
                    // Same live-entry gate as step 4's prompt block:
                    // retained tracks of a finished capture are
                    // diagnostics history, never a current scene.
                    val presentTracks = if (liveHolder != null && liveEntry != null) {
                        liveHolder.trackedSnapshot(drawNowMs).presentObjects
                    } else {
                        emptyList()
                    }
                    var boxesDrawn = 0
                    if (liveGeometry != null) {
                        val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp)
                        for (track in presentTracks) {
                            val mapped = mapFrameBoxToView(
                                box = track.box,
                                frameWidth = liveGeometry.width,
                                frameHeight = liveGeometry.height,
                                rotationDegrees = liveGeometry.rotationDegrees,
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
                            boxesDrawn++
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
                    PreviewOverlayTelemetry.recordDraw(
                        PreviewOverlayTelemetry.DrawRecord(
                            atMs = drawNowMs,
                            boxesDrawn = boxesDrawn,
                            presentTracks = presentTracks.size,
                            geometryWidth = liveGeometry?.width,
                            geometryHeight = liveGeometry?.height,
                            geometryRotationDegrees = liveGeometry?.rotationDegrees,
                            latestResultAgeMs = liveEntry?.let { drawNowMs - it.receivedAtMs },
                            viewWidthPx = size.width.toInt(),
                            viewHeightPx = size.height.toInt(),
                            holderRevision = drawRevision,
                        )
                    )
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
