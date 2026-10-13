package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class FinalWhisperFarewellSnapshotTest {
    private val owner = FinalWhisperFarewellSnapshot.Owner("call-a", "turn-a", 7L)

    private data class Evidence(
        val isWhisper: Boolean = true,
        val transcript: String = "Goodbye, Jarvis.",
        val status: String = "finalized",
        val reason: String = "worker_busy_or_unsupported",
        val issue: String? = null,
        val echoRejected: Boolean = false
    )

    /** Every policy invocation uses copied values, then checks the source was untouched. */
    private fun snapshot(
        evidence: Evidence = Evidence(),
        currentOwner: FinalWhisperFarewellSnapshot.Owner? = owner
    ): FinalWhisperFarewellSnapshot {
        val before = evidence.copy()
        val result = FinalWhisperFarewellSnapshot.capture(
            owner, currentOwner, evidence.isWhisper, evidence.transcript, evidence.status,
            evidence.reason, evidence.issue, evidence.echoRejected
        )
        assertEquals(before, evidence)
        assertEquals(owner, result.owner)
        return result
    }

    @Test fun alreadyFinalWhisperFarewellRetainsExactOwnerAndOriginalText() {
        for (text in listOf("Goodbye, Jarvis.", "Uh, no thank you. Goodbye.", "Jarvis stop listening", "That's all, goodbye!")) {
            val result = snapshot(Evidence(transcript = text))
            assertEquals(text, result.farewellIfCurrent(owner))
        }
    }

    @Test fun emptyFinalEvidenceMakesNoDecision() {
        for (text in listOf("", " ", "\n\t")) {
            assertNull(snapshot(Evidence(transcript = text)).farewellIfCurrent(owner))
        }
    }

    @Test fun missingProvisionalRetiredOrFailedStatusNeverCountsAsFinal() {
        for (status in listOf("", "pending", "partial", "provisional", "failed", "unavailable", "skipped_idle_caption")) {
            assertNull(status, snapshot(Evidence(status = status)).farewellIfCurrent(owner))
        }
    }

    @Test fun retiredIdleCaptionCannotUseRetainedDisplayWordsEvenWithFinalizedStatus() {
        for (reason in listOf("clean_native_idle", "clean_native_idle_pending_drain")) {
            assertNull(reason, snapshot(Evidence(reason = reason)).farewellIfCurrent(owner))
        }
    }

    @Test fun missingFinalizationEvidenceCannotAuthorizeFarewell() {
        for (reason in listOf("", " ", "not_attempted")) {
            assertNull(reason, snapshot(Evidence(reason = reason)).farewellIfCurrent(owner))
        }
    }

    @Test fun finalRecognitionFailuresCannotBeUpgradedByNativeAudioEligibility() {
        for (issue in listOf("unrecognized_segment", "segment_boundary_uncertain", "missing_long_transcript", "utterance_capacity", "")) {
            val originalCaption = Evidence(issue = issue)
            assertNull(issue, snapshot(originalCaption).farewellIfCurrent(owner))
            assertEquals(issue, originalCaption.issue)
        }
    }

    @Test fun anotherRecognizerCannotBecomeWhisperEvidence() {
        assertNull(snapshot(Evidence(isWhisper = false)).farewellIfCurrent(owner))
    }

    @Test fun nonFinalAndUnavailableChecksStayMissesWhenLaterFinalTextArrives() {
        for (atCheck in listOf(Evidence(status = "pending", transcript = ""), Evidence(status = "provisional"), Evidence(transcript = ""))) {
            var liveEvidence = atCheck
            val result = snapshot(liveEvidence)
            liveEvidence = Evidence()
            assertEquals("finalized", liveEvidence.status)
            assertNull(result.farewellIfCurrent(owner))
        }
    }

    @Test fun laterSourceChangesCannotReplaceTheSnapshotOrRefreshItsEchoVerdict() {
        var liveEvidence = Evidence()
        val result = snapshot(liveEvidence)
        liveEvidence = Evidence(transcript = "Please keep listening", status = "pending", issue = "new_failure")
        assertEquals("Goodbye, Jarvis.", result.farewellIfCurrent(owner))
        assertEquals("Please keep listening", liveEvidence.transcript)
        val echoRejected = snapshot(Evidence(echoRejected = true))
        assertNull(echoRejected.farewellIfCurrent(owner))
    }

    @Test fun sourceIsReadExactlyOnceAndNeverRefreshedOnOwnershipChecks() {
        var reads = 0
        var source = Evidence(status = "pending")
        fun copyAtCheck(): Evidence { reads++; return source.copy() }
        val result = snapshot(copyAtCheck())
        source = Evidence()
        repeat(3) { assertNull(result.farewellIfCurrent(owner)) }
        assertEquals("finalized", source.status)
        assertEquals(1, reads)
    }

    @Test fun wrongCallTurnInputRevisionOrEndedCallAtCheckCannotBecomeCurrentLater() {
        for (current in listOf(null, owner.copy(callId = "call-b"), owner.copy(turnId = "turn-b"), owner.copy(inputRevision = 8L))) {
            val result = snapshot(currentOwner = current)
            assertNull(result.farewellIfCurrent(owner))
        }
    }

    @Test fun successfulSnapshotCannotEndWrongCallTurnOrInputRevisionAtPublication() {
        val result = snapshot()
        for (current in listOf(null, owner.copy(callId = "call-b"), owner.copy(turnId = "turn-b"), owner.copy(inputRevision = 8L))) {
            assertNull(result.farewellIfCurrent(current))
        }
    }

    @Test fun missingCallOrTurnIdentityFailsClosed() {
        for (missingOwner in listOf(owner.copy(callId = ""), owner.copy(turnId = " "))) {
            val evidence = Evidence()
            val before = evidence.copy()
            val result = FinalWhisperFarewellSnapshot.capture(missingOwner, missingOwner,
                evidence.isWhisper, evidence.transcript, evidence.status, evidence.reason, evidence.issue, evidence.echoRejected)
            assertNull(result.farewellIfCurrent(missingOwner))
            assertEquals(before, evidence)
        }
    }

    @Test fun usesExistingPlaybackTailEchoGuardWithoutNewTimingOrConfidenceThresholds() {
        val echo = FollowupPlaybackEcho().also {
            it.remember("Goodbye Jarvis please")
            it.ended(1000L)
        }
        val text = "Goodbye Jarvis please"
        val inTail = snapshot(Evidence(transcript = text, echoRejected = echo.rejects(text, 1100L)))
        val freshUtterance = snapshot(Evidence(transcript = text, echoRejected = echo.rejects(text, 1351L)))
        assertNull(inTail.farewellIfCurrent(owner))
        assertEquals(text, freshUtterance.farewellIfCurrent(owner))
        echo.clear()
        assertNull(inTail.farewellIfCurrent(owner))
    }

    @Test fun anchoredMatcherRejectsMentionsNegationsQuotedTextAndAdditionalRequests() {
        for (text in listOf("Tell me about goodbye", "Don't stop listening", "No goodbye yet", "He said goodbye",
            "Say goodbye", "Goodbye and open settings", "\"goodbye\"", "'goodbye'", "‘stop listening’")) {
            assertNull(text, snapshot(Evidence(transcript = text)).farewellIfCurrent(owner))
        }
    }

    @Test fun apiCannotReceiveWorkCallbacksOrMutableRecognitionOwners() {
        val allowed = setOf(String::class.java, java.lang.Boolean.TYPE, FinalWhisperFarewellSnapshot.Owner::class.java)
        val capture = FinalWhisperFarewellSnapshot.Companion::class.java.declaredMethods.single { it.name == "capture" }
        assertTrue(capture.parameterTypes.all { it in allowed })
        val publication = FinalWhisperFarewellSnapshot::class.java.declaredMethods.single { it.name == "farewellIfCurrent" }
        assertArrayEquals(arrayOf<Class<*>>(FinalWhisperFarewellSnapshot.Owner::class.java), publication.parameterTypes)
        assertEquals(String::class.java, publication.returnType)
        assertEquals("Goodbye, Jarvis.", snapshot().farewellIfCurrent(owner))
    }
}
