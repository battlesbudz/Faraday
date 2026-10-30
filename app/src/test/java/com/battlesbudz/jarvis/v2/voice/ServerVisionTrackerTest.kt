package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.phoneinference.Box
import com.battlesbudz.phoneinference.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerVisionTrackerTest {
    private fun det(label: String, x1: Float, y1: Float, x2: Float, y2: Float) =
        Detection(label = label, classId = -1, confidence = 0.9f, box = Box(x1, y1, x2, y2))

    @Test fun steadyBoxAcrossResultsKeepsItsId() {
        val tracker = ServerVisionTracker()
        val box = Box(0f, 0f, 100f, 100f)
        tracker.onDetections(listOf(Detection("car", -1, 0.9f, box)), 1_000L)
        tracker.onDetections(listOf(Detection("car", -1, 0.9f, box)), 2_700L)
        tracker.onDetections(listOf(Detection("car", -1, 0.9f, box)), 4_400L)
        val snapshot = tracker.snapshot(4_400L)
        assertEquals(1, snapshot.objects.size)
        val tracked = snapshot.objects.single()
        assertEquals(1, tracked.id)
        assertEquals(1_000L, tracked.firstSeenMs)
        assertEquals(4_400L, tracked.lastSeenMs)
        assertTrue(tracked.presentNow)
        assertEquals(0L, tracked.lastSeenAgeMs)
    }

    @Test fun movedButOverlappingBoxKeepsItsId() {
        val tracker = ServerVisionTracker()
        tracker.onDetections(listOf(det("car", 0f, 0f, 100f, 100f)), 0L)
        // Half the box still overlaps: IoU 5000/15000 = 0.33, above the 0.3 gate.
        tracker.onDetections(listOf(det("car", 50f, 0f, 150f, 100f)), 1_700L)
        val snapshot = tracker.snapshot(1_700L)
        assertEquals(1, snapshot.objects.size)
        val tracked = snapshot.objects.single()
        assertEquals(1, tracked.id)
        assertEquals(Box(50f, 0f, 150f, 100f), tracked.box)
        assertEquals(1_700L, tracked.lastSeenMs)
    }

    @Test fun nonOverlappingSameLabelBoxMintsNewId() {
        val tracker = ServerVisionTracker()
        tracker.onDetections(listOf(det("car", 0f, 0f, 100f, 100f)), 0L)
        // Only a sliver overlaps: IoU 2000/18000 = 0.11, below the 0.3 gate.
        tracker.onDetections(listOf(det("car", 80f, 0f, 180f, 100f)), 1_700L)
        val snapshot = tracker.snapshot(1_700L)
        assertEquals(2, snapshot.objects.size)
        val first = snapshot.objects.single { it.id == 1 }
        assertEquals(Box(0f, 0f, 100f, 100f), first.box)
        assertEquals(0L, first.lastSeenMs)
        val second = snapshot.objects.single { it.id == 2 }
        assertEquals(Box(80f, 0f, 180f, 100f), second.box)
        assertEquals(1_700L, second.firstSeenMs)
    }

    @Test fun differentLabelNeverMergesEvenWithFullOverlap() {
        val tracker = ServerVisionTracker()
        tracker.onDetections(listOf(det("person", 0f, 0f, 100f, 100f)), 0L)
        tracker.onDetections(listOf(det("car", 0f, 0f, 100f, 100f)), 1_700L)
        val snapshot = tracker.snapshot(1_700L)
        assertEquals(2, snapshot.objects.size)
        val person = snapshot.objects.single { it.label == "person" }
        assertEquals(1, person.id)
        assertEquals(0L, person.lastSeenMs)
        val car = snapshot.objects.single { it.label == "car" }
        assertEquals(2, car.id)
        assertEquals(1_700L, car.firstSeenMs)
    }

    @Test fun twoSameLabelObjectsTrackedAsTwoTracks() {
        val tracker = ServerVisionTracker()
        tracker.onDetections(
            listOf(
                det("person", 0f, 0f, 100f, 100f),
                det("person", 300f, 0f, 400f, 100f),
            ),
            0L,
        )
        tracker.onDetections(
            listOf(
                det("person", 10f, 0f, 110f, 100f),
                det("person", 290f, 0f, 390f, 100f),
            ),
            1_700L,
        )
        val snapshot = tracker.snapshot(1_700L)
        assertEquals(2, snapshot.objects.size)
        assertEquals(Box(10f, 0f, 110f, 100f), snapshot.objects.single { it.id == 1 }.box)
        assertEquals(Box(290f, 0f, 390f, 100f), snapshot.objects.single { it.id == 2 }.box)
        assertTrue(snapshot.objects.all { it.lastSeenMs == 1_700L })
    }

    @Test fun trackUnmatchedPastFiveSecondsLeavesPresentButStaysInSnapshot() {
        val tracker = ServerVisionTracker()
        tracker.onDetections(listOf(det("cup", 0f, 0f, 50f, 50f)), 1_000L)
        tracker.onDetections(emptyList(), 5_999L)
        var snapshot = tracker.snapshot(5_999L)
        assertEquals(1, snapshot.objects.size)
        assertTrue(snapshot.objects.single().presentNow)
        assertEquals(4_999L, snapshot.objects.single().lastSeenAgeMs)

        tracker.onDetections(emptyList(), 6_000L)
        snapshot = tracker.snapshot(6_000L)
        assertEquals(1, snapshot.objects.size)
        val tracked = snapshot.objects.single()
        assertFalse(tracked.presentNow)
        assertEquals(5_000L, tracked.lastSeenAgeMs)
        assertEquals(1_000L, tracked.lastSeenMs)

        // Long after, the track is still in the snapshot as last-seen.
        snapshot = tracker.snapshot(20_000L)
        assertEquals(1, snapshot.objects.size)
        assertFalse(snapshot.objects.single().presentNow)
        assertEquals(19_000L, snapshot.objects.single().lastSeenAgeMs)
    }

    @Test fun greedyMatchingAssignsBestOverlapPairFirstWhenAmbiguous() {
        val tracker = ServerVisionTracker()
        tracker.onDetections(
            listOf(
                det("car", 0f, 0f, 100f, 100f),
                det("car", 40f, 0f, 140f, 100f),
            ),
            0L,
        )
        // Detection A overlaps track 1 at IoU 0.48 and track 2 at IoU 0.90;
        // detection B overlaps track 2 at IoU 0.33 and track 1 below the
        // gate. Best-first gives A to track 2, leaving track 1 unmatched
        // and B minting a new track. A track-order matcher would instead
        // give A to track 1 and B to track 2, keeping two tracks — this
        // test pins the greedy order.
        tracker.onDetections(
            listOf(
                det("car", 35f, 0f, 135f, 100f),
                det("car", 90f, 0f, 190f, 100f),
            ),
            1_700L,
        )
        val snapshot = tracker.snapshot(1_700L)
        assertEquals(3, snapshot.objects.size)
        val track2 = snapshot.objects.single { it.id == 2 }
        assertEquals(Box(35f, 0f, 135f, 100f), track2.box)
        assertEquals(1_700L, track2.lastSeenMs)
        val track1 = snapshot.objects.single { it.id == 1 }
        assertEquals(0L, track1.lastSeenMs)
        val track3 = snapshot.objects.single { it.id == 3 }
        assertEquals(Box(90f, 0f, 190f, 100f), track3.box)
        assertEquals(1_700L, track3.firstSeenMs)
    }

    @Test fun retiredTrackDoesNotMatchAgainAndReappearanceMintsNewId() {
        val tracker = ServerVisionTracker()
        tracker.onDetections(listOf(det("cup", 0f, 0f, 100f, 100f)), 0L)
        tracker.onDetections(emptyList(), 6_000L)
        // The identical box returns, but the old track has been unseen for
        // 7 s — past the presence window — so it cannot claim the match.
        tracker.onDetections(listOf(det("cup", 0f, 0f, 100f, 100f)), 7_000L)
        val snapshot = tracker.snapshot(7_000L)
        assertEquals(2, snapshot.objects.size)
        val retired = snapshot.objects.single { it.id == 1 }
        assertFalse(retired.presentNow)
        assertEquals(0L, retired.lastSeenMs)
        val fresh = snapshot.objects.single { it.id == 2 }
        assertTrue(fresh.presentNow)
        assertEquals(7_000L, fresh.firstSeenMs)
    }

    @Test fun resetClearsTracksAndRestartsIds() {
        val tracker = ServerVisionTracker()
        tracker.onDetections(listOf(det("car", 0f, 0f, 100f, 100f)), 0L)
        assertEquals(1, tracker.snapshot(0L).objects.size)
        tracker.reset()
        assertTrue(tracker.snapshot(0L).objects.isEmpty())
        tracker.onDetections(listOf(det("bus", 0f, 0f, 100f, 100f)), 100L)
        val snapshot = tracker.snapshot(100L)
        assertEquals(1, snapshot.objects.size)
        assertEquals(1, snapshot.objects.single().id)
        assertEquals("bus", snapshot.objects.single().label)
    }

    @Test fun presentCountsCoverPresentTracksOnlyInStepOneStyle() {
        val tracker = ServerVisionTracker()
        tracker.onDetections(
            listOf(
                det("person", 0f, 0f, 100f, 100f),
                det("person", 300f, 0f, 400f, 100f),
                det("chair", 0f, 200f, 100f, 300f),
                det("dog", 300f, 200f, 400f, 300f),
            ),
            0L,
        )
        // The dog is gone in the next result; the rest are seen again.
        tracker.onDetections(
            listOf(
                det("person", 0f, 0f, 100f, 100f),
                det("person", 300f, 0f, 400f, 100f),
                det("chair", 0f, 200f, 100f, 300f),
            ),
            4_000L,
        )
        val snapshot = tracker.snapshot(5_000L)
        assertEquals(4, snapshot.objects.size)
        // At 5 s the dog (last seen at 0) has left the presence window;
        // the counts describe present tracks only, step-1 style.
        assertEquals("person 2, chair 1", snapshot.presentCountsText())
        assertEquals(3, snapshot.presentObjects.size)
    }
}
