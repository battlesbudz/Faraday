package com.battlesbudz.jarvis.v2.voice

/**
 * Video-call step 5: proof surface for the call preview's box overlay.
 *
 * The step-5 device test (build 1608) proved the data side end to end —
 * frames flowed, detects succeeded, the tracker held present tracks, and
 * step 4's prompt block read them from the same holder at the same
 * moments — yet no box was visible on the preview, and the diagnostics
 * paste carried nothing at all about the overlay, so the failure could
 * not be localized from evidence. This object is the missing witness:
 * the overlay records every draw pass (how many boxes it actually drew,
 * how many present tracks it saw, which frame geometry it mapped with,
 * how old the latest result was, and the view size it drew into), plus
 * whether a preview surface was ever bound. [LatestServerVisionResult]'s
 * diagnostics text appends [text], so the next Copy diagnostics paste can
 * separate "the draw never had data" from "the draw painted boxes the
 * user could not see".
 *
 * Written from the overlay's draw thread and the UI thread, read from
 * whichever thread exports diagnostics: all state is behind one lock and
 * swapped wholesale, never mutated in place. Reset when a capture starts
 * (via [LatestServerVisionResult.reset]), so a paste describes the call
 * it was copied from, not a previous one.
 */
object PreviewOverlayTelemetry {
    /** One overlay draw pass, as recorded by the draw itself. */
    data class DrawRecord(
        val atMs: Long,
        val boxesDrawn: Int,
        val presentTracks: Int,
        val geometryWidth: Int?,
        val geometryHeight: Int?,
        val geometryRotationDegrees: Int?,
        /** Age of the holder's latest result at draw time; null = no live result. */
        val latestResultAgeMs: Long?,
        val viewWidthPx: Int,
        val viewHeightPx: Int,
        /** The holder revision the draw subscribed to; it moves with every
         * detect result, so a paste can tell whether draws track arrivals. */
        val holderRevision: Long,
    )

    private val lock = Any()
    private var draws = 0L
    private var lastDraw: DrawRecord? = null
    private var surfaceBound = false
    private var implementationMode: String? = null

    /** The overlay's PreviewView was given a live preview use case. */
    fun recordSurfaceBound(mode: String) {
        synchronized(lock) {
            surfaceBound = true
            implementationMode = mode
        }
    }

    /** One draw pass of the box overlay (called even when nothing drew). */
    fun recordDraw(record: DrawRecord) {
        synchronized(lock) {
            draws++
            lastDraw = record
        }
    }

    /** A new capture starts: the next paste must describe only that call. */
    fun reset() {
        synchronized(lock) {
            draws = 0L
            lastDraw = null
            surfaceBound = false
            implementationMode = null
        }
    }

    /** The diagnostics block: surface state plus the latest draw's facts. */
    fun text(): String = synchronized(lock) {
        buildString {
            append("previewSurface=${if (surfaceBound) "bound" else "never-bound"} ")
            append("previewMode=${implementationMode ?: "unknown"} ")
            append("overlayDraws=$draws ")
            val draw = lastDraw
            if (draw == null) {
                append("lastDraw=none")
            } else {
                append("lastDrawBoxes=${draw.boxesDrawn} ")
                append("lastDrawPresentTracks=${draw.presentTracks} ")
                if (draw.geometryWidth != null && draw.geometryHeight != null) {
                    append(
                        "lastDrawGeometry=${draw.geometryWidth}x${draw.geometryHeight} " +
                            "rot=${draw.geometryRotationDegrees ?: "?"} "
                    )
                } else {
                    append("lastDrawGeometry=none ")
                }
                append("lastDrawResultAgeMs=${draw.latestResultAgeMs ?: "none"} ")
                append("lastDrawViewPx=${draw.viewWidthPx}x${draw.viewHeightPx} ")
                append("lastDrawRevision=${draw.holderRevision} ")
                append("lastDrawAtMs=${draw.atMs}")
            }
        }
    }
}
