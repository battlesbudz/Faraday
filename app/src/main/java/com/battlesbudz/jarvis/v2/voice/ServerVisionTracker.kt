package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.phoneinference.Box
import com.battlesbudz.phoneinference.Detection

/**
 * Video-call step 3: tracking across frames.
 *
 * Step 2's holder keeps only the latest server answer — each frame's
 * scene replaces the previous one wholesale, so nothing downstream can
 * tell whether the "person" in this frame is the same person as a
 * second ago. This tracker consumes every successful detect result and
 * maintains a steady identity per object: detections are matched to
 * existing tracks, matched tracks keep their ID, and the snapshot says
 * which tracked objects are present now versus merely seen recently.
 * Step 4's prompt block reads its "what I am seeing now" from here.
 *
 * Matching is deliberately simple (Faraday-side box matching; the
 * /detect endpoint stays stateless and no server change was needed):
 * a detection matches a track only when the label is equal AND the box
 * overlap (IoU) is at least [MIN_IOU]. Assignment is greedy
 * best-match-first: candidate pairs are taken highest-IoU first, each
 * track and each detection used at most once per result. Unmatched
 * detections mint new tracks with monotonic IDs from 1; [reset] (a new
 * call's capture) restarts the IDs.
 *
 * Presence is a window, not the latest frame: results land roughly
 * every 1.7 s and individual frames flicker, so a track counts as
 * present until it has gone unmatched for [PRESENT_WINDOW_MS]
 * (~3 missed detect cycles), measured against result timestamps. Past
 * the window the track stays in the snapshot as last-seen with its age
 * until the call's capture ends — and it no longer matches: an object
 * that reappears after the window is a new track, because identity
 * across a long absence is a guess this layer should not make.
 *
 * Known v1 limitation: two same-label objects whose boxes cross can
 * swap IDs — greedy box matching has no appearance or motion model to
 * tell them apart. Accepted for now; the counts stay right even when
 * the IDs swap.
 *
 * All time enters as parameters (result timestamps, snapshot evaluation
 * time): the tracker owns no clock, so unit tests control time exactly.
 * Thread-safe: every mutation and read is under one lock.
 */
class ServerVisionTracker {
    /** One tracked object in a [Snapshot], presence evaluated at snapshot time. */
    data class TrackedObject(
        val id: Int,
        val label: String,
        val box: Box,
        val firstSeenMs: Long,
        val lastSeenMs: Long,
        val presentNow: Boolean,
        /** Milliseconds between the last match and the snapshot's evaluation time. */
        val lastSeenAgeMs: Long,
    )

    /** Point-in-time view of every track in the current call. */
    data class Snapshot(
        val evaluatedAtMs: Long,
        val objects: List<TrackedObject>,
    ) {
        val presentObjects: List<TrackedObject> get() = objects.filter { it.presentNow }

        /**
         * Code-made counts of the PRESENT tracks by label, in the same
         * style as the step-1 server-result counts ("person 2, chair 1"):
         * count descending, then label. Empty when nothing is present.
         */
        fun presentCountsText(): String =
            presentObjects.groupingBy { it.label }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .joinToString(", ") { "${it.key} ${it.value}" }
    }

    /** Mutable track state; [TrackedObject] is its evaluated view. */
    private data class Track(
        val id: Int,
        val label: String,
        var box: Box,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
    )

    private val lock = Any()
    private val tracks = mutableListOf<Track>()
    private var nextId = 1
    private var lastResultTimestampMs: Long? = null

    /**
     * Feed one successful server answer: [detections] is the cleaned
     * list from that answer and [resultTimestampMs] is the timestamp of
     * the frame it describes (the same wall clock the frame hub stamps).
     */
    fun onDetections(detections: List<Detection>, resultTimestampMs: Long) {
        synchronized(lock) {
            lastResultTimestampMs = resultTimestampMs
            // Candidate pairs, best overlap first. A track already past
            // the presence window at this result's timestamp is retired
            // and cannot claim a detection (see the class doc).
            val candidates = mutableListOf<Triple<Int, Int, Float>>()
            for ((trackIndex, track) in tracks.withIndex()) {
                if (resultTimestampMs - track.lastSeenMs >= PRESENT_WINDOW_MS) continue
                for ((detectionIndex, detection) in detections.withIndex()) {
                    if (detection.label != track.label) continue
                    val overlap = intersectionOverUnion(track.box, detection.box)
                    if (overlap >= MIN_IOU) candidates += Triple(trackIndex, detectionIndex, overlap)
                }
            }
            // Stable sort: equal overlaps keep track-then-detection order.
            candidates.sortByDescending { it.third }
            val usedTracks = HashSet<Int>()
            val usedDetections = HashSet<Int>()
            for ((trackIndex, detectionIndex, _) in candidates) {
                if (trackIndex in usedTracks || detectionIndex in usedDetections) continue
                usedTracks += trackIndex
                usedDetections += detectionIndex
                val detection = detections[detectionIndex]
                tracks[trackIndex].box = detection.box
                tracks[trackIndex].lastSeenMs = resultTimestampMs
            }
            for ((detectionIndex, detection) in detections.withIndex()) {
                if (detectionIndex in usedDetections) continue
                tracks += Track(
                    id = nextId++,
                    label = detection.label,
                    box = detection.box,
                    firstSeenMs = resultTimestampMs,
                    lastSeenMs = resultTimestampMs,
                )
            }
        }
    }

    /** Every track, presence evaluated at [nowMs]. */
    fun snapshot(nowMs: Long): Snapshot = synchronized(lock) {
        Snapshot(
            evaluatedAtMs = nowMs,
            objects = tracks.map { track ->
                TrackedObject(
                    id = track.id,
                    label = track.label,
                    box = track.box,
                    firstSeenMs = track.firstSeenMs,
                    lastSeenMs = track.lastSeenMs,
                    presentNow = nowMs - track.lastSeenMs < PRESENT_WINDOW_MS,
                    lastSeenAgeMs = (nowMs - track.lastSeenMs).coerceAtLeast(0L),
                )
            },
        )
    }

    /** Every track, presence evaluated at the most recent result's timestamp. */
    fun snapshot(): Snapshot {
        val evaluatedAtMs = synchronized(lock) { lastResultTimestampMs ?: 0L }
        return snapshot(evaluatedAtMs)
    }

    /** A new call's capture: all tracks gone, IDs restart at 1. */
    fun reset() {
        synchronized(lock) {
            tracks.clear()
            nextId = 1
            lastResultTimestampMs = null
        }
    }

    companion object {
        /** A track stays "present" until it has gone unmatched for this long. */
        const val PRESENT_WINDOW_MS = 5_000L

        /** Minimum box overlap (IoU) for a detection to continue a track. */
        const val MIN_IOU = 0.3f

        /** Intersection over union of two boxes; 0 when they do not overlap. */
        internal fun intersectionOverUnion(a: Box, b: Box): Float {
            val intersectionWidth = minOf(a.x2, b.x2) - maxOf(a.x1, b.x1)
            val intersectionHeight = minOf(a.y2, b.y2) - maxOf(a.y1, b.y1)
            if (intersectionWidth <= 0f || intersectionHeight <= 0f) return 0f
            val intersection = intersectionWidth * intersectionHeight
            val union = (a.x2 - a.x1) * (a.y2 - a.y1) +
                (b.x2 - b.x1) * (b.y2 - b.y1) - intersection
            return if (union <= 0f) 0f else intersection / union
        }
    }
}
