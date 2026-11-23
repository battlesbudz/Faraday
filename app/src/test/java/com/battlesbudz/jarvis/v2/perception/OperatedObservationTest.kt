package com.battlesbudz.jarvis.v2.perception

import com.battlesbudz.jarvis.v2.display.OperatedDisplayIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatedObservationTest {
    private val identity = OperatedDisplayIdentity(7, "task-42", "YouTube")

    @Test fun treeReadAcceptsOnlyTheOperatedDisplayWindow() {
        val wrong = window(displayId = 0)
        val right = window(displayId = 7)
        assertEquals(right, selectOperatedWindow(identity, listOf(wrong, right)))
        assertNull(selectOperatedWindow(identity, listOf(wrong)))
        assertNull(createObservation(identity, wrong, readAtMs = 10L))
    }

    @Test fun envelopeCarriesDisplayPackageBothFingerprintsAndTimestamp() {
        val observation = observation(window(), readAtMs = 1234L)
        assertEquals(identity, observation.displayIdentity)
        assertEquals("com.google.android.youtube", observation.foregroundPackage)
        assertTrue(observation.screenIdentityHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(observation.screenFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertEquals(1234L, observation.readAtMs)
    }

    @Test fun identityHashIgnoresClockOnlyChange() {
        assertEquals(observation(window(text = "10:42")).screenIdentityHash,
            observation(window(text = "10:43")).screenIdentityHash)
    }

    @Test fun identityHashIgnoresProgressValueButHashesItsPresence() {
        assertEquals(observation(window(progress = "20%")).screenIdentityHash,
            observation(window(progress = "80%")).screenIdentityHash)
        assertFalse(observation(window()).screenIdentityHash == observation(window(progress = "20%")).screenIdentityHash)
    }

    @Test fun identityHashChangesWhenScreenContentChanges() {
        assertFalse(observation(window(text = "Video one")).screenIdentityHash ==
            observation(window(text = "Video two")).screenIdentityHash)
    }

    @Test fun looseFingerprintIsStableAcrossClockOnlyChange() {
        assertEquals(observation(window(text = "10:42")).screenFingerprint,
            observation(window(text = "10:43")).screenFingerprint)
    }

    @Test fun staleDetectionFlagsChangedContentAndKeepsIdenticalScreenCurrent() {
        val first = observation(window(text = "Video one"), readAtMs = 10)
        val sameScreen = observation(window(text = "Video one"), readAtMs = 20)
        val changed = observation(window(text = "Video two"), readAtMs = 30)
        assertFalse(isObservationStale(first, sameScreen))
        assertTrue(isObservationStale(first, changed))
        val otherDisplayIdentity = identity.copy(displayId = 8)
        val otherDisplay = requireNotNull(createObservation(otherDisplayIdentity,
            window(displayId = 8, text = "Video one"), readAtMs = 40))
        assertTrue(isObservationStale(first, otherDisplay))
    }

    @Test fun canonicalSerializationIsDeterministicAndRoundTripsEnvelopeFields() {
        val original = observation(window(text = "A \"quoted\" title"), readAtMs = 9876L)
        val serialized = original.canonicalSerialization()
        assertEquals(serialized.toByteArray(Charsets.UTF_8).toList(), original.canonicalSerialization().toByteArray(Charsets.UTF_8).toList())
        val decoded = OperatedObservation.fromCanonicalSerialization(serialized)
        assertEquals(original, decoded)
        assertNotNull(decoded.nodes)
    }

    private fun observation(candidate: TreeWindowCandidate, readAtMs: Long = 100L): OperatedObservation =
        requireNotNull(createObservation(identity, candidate, readAtMs))

    private fun window(displayId: Int = 7, text: String = "Video one", progress: String? = null): TreeWindowCandidate {
        val nodes = mutableListOf(
            ObservedNode("android.widget.TextView", "video/title", text, listOf("enabled")),
            ObservedNode("android.widget.TextView", "system/clock", "10:42", listOf("enabled")),
        )
        if (progress != null) nodes += ObservedNode("android.widget.ProgressBar", "video/progress", progress, listOf("enabled"))
        return TreeWindowCandidate(displayId, "com.google.android.youtube", nodes)
    }
}
