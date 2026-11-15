package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.phoneinference.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Step 5: the preview overlay's box mapping. Detection boxes are in the
 * analyzed frame's pixel space (sensor orientation); the preview shows
 * the same feed rotated upright and FILL_CENTER-scaled. These cases pin
 * the rotation, the cover-crop offsets, and the mirror flip with exact
 * numbers, because a box drawn in the wrong place is worse than no box.
 */
class CallVideoPreviewMappingTest {
    private fun assertMapped(
        expected: MappedTrackBox,
        box: Box,
        frameWidth: Int = 640,
        frameHeight: Int = 480,
        rotationDegrees: Int = 0,
        viewWidth: Float,
        viewHeight: Float,
        mirrorHorizontally: Boolean = false,
    ) {
        val mapped = mapFrameBoxToView(
            box, frameWidth, frameHeight, rotationDegrees, viewWidth, viewHeight, mirrorHorizontally,
        ) ?: error("expected a mapped box")
        assertEquals(expected.left, mapped.left, 0.01f)
        assertEquals(expected.top, mapped.top, 0.01f)
        assertEquals(expected.right, mapped.right, 0.01f)
        assertEquals(expected.bottom, mapped.bottom, 0.01f)
    }

    @Test fun noRotationExactFitViewIsIdentityScaled() {
        assertMapped(
            MappedTrackBox(160f, 120f, 320f, 240f),
            Box(160f, 120f, 320f, 240f),
            viewWidth = 640f, viewHeight = 480f,
        )
    }

    @Test fun fillCenterCropsTheOverflowingAxisSymmetrically() {
        // Square view over a 4:3 frame: FILL_CENTER scales to the height
        // and crops 80 px of frame width on each side.
        assertMapped(
            MappedTrackBox(-80f, 0f, 560f, 480f),
            Box(0f, 0f, 640f, 480f),
            viewWidth = 480f, viewHeight = 480f,
        )
        assertMapped(
            MappedTrackBox(80f, 120f, 400f, 360f),
            Box(160f, 120f, 480f, 360f),
            viewWidth = 480f, viewHeight = 480f,
        )
    }

    @Test fun rotation90MovesSensorTopLeftToUprightTopRight() {
        // Portrait phone: the 640x480 buffer rotated 90 CW is the upright
        // 480x640 image. The sensor's top-left quadrant lands top-right.
        assertMapped(
            MappedTrackBox(240f, 0f, 480f, 320f),
            Box(0f, 0f, 320f, 240f),
            rotationDegrees = 90,
            viewWidth = 480f, viewHeight = 640f,
        )
    }

    @Test fun rotation270MovesSensorTopLeftToUprightBottomLeft() {
        assertMapped(
            MappedTrackBox(0f, 320f, 240f, 640f),
            Box(0f, 0f, 320f, 240f),
            rotationDegrees = 270,
            viewWidth = 480f, viewHeight = 640f,
        )
    }

    @Test fun rotation180FlipsBothAxes() {
        assertMapped(
            MappedTrackBox(480f, 360f, 640f, 480f),
            Box(0f, 0f, 160f, 120f),
            rotationDegrees = 180,
            viewWidth = 640f, viewHeight = 480f,
        )
    }

    @Test fun mirrorFlipsHorizontallyAfterRotation() {
        // The left quarter of the frame displays as the right quarter.
        assertMapped(
            MappedTrackBox(480f, 0f, 640f, 480f),
            Box(0f, 0f, 160f, 480f),
            viewWidth = 640f, viewHeight = 480f,
            mirrorHorizontally = true,
        )
    }

    @Test fun nonQuarterRotationFallsBackToUpright() {
        assertMapped(
            MappedTrackBox(0f, 0f, 640f, 480f),
            Box(0f, 0f, 640f, 480f),
            rotationDegrees = 45,
            viewWidth = 640f, viewHeight = 480f,
        )
    }

    @Test fun degenerateGeometryMapsNothing() {
        assertNull(mapFrameBoxToView(Box(0f, 0f, 10f, 10f), 0, 480, 0, 640f, 480f))
        assertNull(mapFrameBoxToView(Box(0f, 0f, 10f, 10f), 640, 0, 0, 640f, 480f))
        assertNull(mapFrameBoxToView(Box(0f, 0f, 10f, 10f), 640, 480, 0, 0f, 480f))
        assertNull(mapFrameBoxToView(Box(0f, 0f, 10f, 10f), 640, 480, 0, 640f, 0f))
    }
}
