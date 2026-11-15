package com.battlesbudz.jarvis.v2.voice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Step 5 overlay telemetry: the diagnostics witness added after build
 * 1608's "preview showed, no boxes drew" test, whose paste carried no
 * overlay facts at all. Pins the text format the Copy diagnostics export
 * (and a human reading it) relies on.
 */
class PreviewOverlayTelemetryTest {
    @Before fun setUp() = PreviewOverlayTelemetry.reset()

    @After fun tearDown() = PreviewOverlayTelemetry.reset()

    @Test fun freshStateReportsNoSurfaceAndNoDraw() {
        val text = PreviewOverlayTelemetry.text()
        assertTrue(text.contains("previewSurface=never-bound"))
        assertTrue(text.contains("previewMode=unknown"))
        assertTrue(text.contains("overlayDraws=0"))
        assertTrue(text.contains("lastDraw=none"))
    }

    @Test fun recordedDrawCarriesBoxesTracksGeometryAgeAndView() {
        PreviewOverlayTelemetry.recordSurfaceBound("COMPATIBLE")
        PreviewOverlayTelemetry.recordDraw(
            PreviewOverlayTelemetry.DrawRecord(
                atMs = 123_456L,
                boxesDrawn = 2,
                presentTracks = 3,
                geometryWidth = 640,
                geometryHeight = 480,
                geometryRotationDegrees = 90,
                latestResultAgeMs = 812L,
                viewWidthPx = 707,
                viewHeightPx = 943,
                holderRevision = 41L,
            )
        )
        val text = PreviewOverlayTelemetry.text()
        assertTrue(text.contains("previewSurface=bound"))
        assertTrue(text.contains("previewMode=COMPATIBLE"))
        assertTrue(text.contains("overlayDraws=1"))
        assertTrue(text.contains("lastDrawBoxes=2"))
        assertTrue(text.contains("lastDrawPresentTracks=3"))
        assertTrue(text.contains("lastDrawGeometry=640x480"))
        assertTrue(text.contains("rot=90"))
        assertTrue(text.contains("lastDrawResultAgeMs=812"))
        assertTrue(text.contains("lastDrawViewPx=707x943"))
        assertTrue(text.contains("lastDrawRevision=41"))
        assertTrue(text.contains("lastDrawAtMs=123456"))
    }

    @Test fun drawWithoutLiveResultOrGeometrySaysSo() {
        PreviewOverlayTelemetry.recordDraw(
            PreviewOverlayTelemetry.DrawRecord(
                atMs = 7L,
                boxesDrawn = 0,
                presentTracks = 0,
                geometryWidth = null,
                geometryHeight = null,
                geometryRotationDegrees = null,
                latestResultAgeMs = null,
                viewWidthPx = 100,
                viewHeightPx = 100,
                holderRevision = 0L,
            )
        )
        val text = PreviewOverlayTelemetry.text()
        assertTrue(text.contains("lastDrawBoxes=0"))
        assertTrue(text.contains("lastDrawGeometry=none"))
        assertTrue(text.contains("lastDrawResultAgeMs=none"))
    }

    @Test fun resultAgeNeverGoesNegative() {
        // The build-1612 paste printed lastDrawResultAgeMs=-682: the
        // draw's 1-second tick clock lagged a just-arrived result's
        // receive stamp. An age is clamped at zero.
        assertEquals(0L, PreviewOverlayTelemetry.resultAgeMs(nowMs = 1_000L, receivedAtMs = 1_682L))
        assertEquals(0L, PreviewOverlayTelemetry.resultAgeMs(nowMs = 1_682L, receivedAtMs = 1_682L))
        assertEquals(812L, PreviewOverlayTelemetry.resultAgeMs(nowMs = 2_000L, receivedAtMs = 1_188L))
    }

    @Test fun resetClearsSurfaceDrawsAndLastDraw() {
        PreviewOverlayTelemetry.recordSurfaceBound("COMPATIBLE")
        PreviewOverlayTelemetry.recordDraw(
            PreviewOverlayTelemetry.DrawRecord(
                atMs = 1L, boxesDrawn = 1, presentTracks = 1,
                geometryWidth = 640, geometryHeight = 480, geometryRotationDegrees = 90,
                latestResultAgeMs = 5L, viewWidthPx = 10, viewHeightPx = 10, holderRevision = 3L,
            )
        )
        PreviewOverlayTelemetry.reset()
        val text = PreviewOverlayTelemetry.text()
        assertTrue(text.contains("previewSurface=never-bound"))
        assertTrue(text.contains("overlayDraws=0"))
        assertTrue(text.contains("lastDraw=none"))
    }

    @Test fun holderDiagnosticsCarryTheOverlaySection() {
        val holder = LatestServerVisionResult()
        assertTrue(holder.diagnosticsText().contains("PREVIEW OVERLAY"))
        assertTrue(holder.diagnosticsText().contains("previewSurface="))
    }
}
