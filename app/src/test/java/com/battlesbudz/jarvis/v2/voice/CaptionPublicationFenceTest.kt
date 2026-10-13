package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptionPublicationFenceTest {
    private class Fixture {
        val fence = CaptionPublicationFence()
        val queue = CallInputQueue()
        val checkpoints = java.util.concurrent.atomic.AtomicInteger()
        val controller = VoiceSessionController(object : VoiceCallStore {
            override fun list() = emptyList<VoiceCallRecord>()
            override fun save(call: VoiceCallRecord) { checkpoints.incrementAndGet() }
            override fun delete(callId: String) {}
        })
        val call = controller.beginCall("conversation")
        val boundary = queue.captionBoundary(call.id)
        init {
            controller.appendTranscript("You", GemmaAudioInputPolicy.PENDING_TRANSCRIPT)
            controller.beginReply(call.id, "answer")
            controller.updateReplyText(call.id, "answer", "Already delivered answer", finished = true)
        }
        val ticket = requireNotNull(controller.captionTicket(call.id, "answer", GemmaAudioInputPolicy.PENDING_TRANSCRIPT))
        var farewellEffects = 0
        suspend fun oldFarewell(beforeCommit: () -> Unit = {}) = fence.publishWhenResolved({ controller.currentCallId() == call.id }) { claim ->
            beforeCommit()
            var publication: VoiceSessionController.CaptionCommit? = null
            val committed = queue.publishWithoutNextInput(boundary) {
                publication = controller.commitCaption(ticket, "Goodbye Jarvis", { true }, true,
                    authorize = { fence.commit(claim, true) })
                (publication != null).also { if (it) queue.end(call.id) }
            }
            publication?.let { controller.checkpointCaption(it); farewellEffects++ }
            committed
        }
    }

    @Test fun completedFarewellWaitsForPendingPlaybackTailThenEchoReleasesBackstop() = runBlocking {
        val f = Fixture()
        f.fence.replyCandidate(true)
        val completedCaption = async(start = CoroutineStart.UNDISPATCHED) { f.oldFarewell() }
        assertFalse(completedCaption.isCompleted)
        assertEquals(0, f.farewellEffects)
        // Transfer itself is synchronous: old native cleanup/caption publication can stay parked.
        f.fence.transferToCapture()
        f.fence.replyCandidate(false) // Old listener's finally cannot release transferred uncertainty.
        yield()
        assertFalse(completedCaption.isCompleted)
        val admitted = CaptionInputAdmission({ true }, onAccepted = { error("echo admitted") },
            onPending = f.fence::candidatePending, onRejected = f.fence::candidateRejected)
        admitted.onPcm(ByteArray(3200))
        admitted.onFinalCandidate(true, "Goodbye Jarvis", true, rejectedEcho = true)
        assertTrue(withTimeout(1000) { completedCaption.await() })
        assertEquals(1, f.farewellEffects)
        assertEquals("Already delivered answer", f.controller.currentTranscript().last().text)
    }

    @Test fun completedFarewellCannotEndCallWhenPlaybackTailResolvesAsNewSpeech() = runBlocking {
        val f = Fixture(); f.fence.replyCandidate(true)
        val completedCaption = async(start = CoroutineStart.UNDISPATCHED) { f.oldFarewell() }
        f.fence.transferToCapture()
        val admitted = CaptionInputAdmission({ true }, onAccepted = {
            f.fence.revoke { assertTrue(f.controller.revokeCaptionForInput(f.call.id)) }
        }, onPending = f.fence::candidatePending, onRejected = f.fence::candidateRejected)
        admitted.onPcm(ByteArray(3200))
        assertFalse(completedCaption.isCompleted)
        admitted.onFinalCandidate(true, "please continue with the next part", true, false)
        assertFalse(withTimeout(1000) { completedCaption.await() })
        assertEquals(0, f.farewellEffects)
        assertEquals(f.call.id, f.controller.currentCallId())
        admitted.onCandidateDiscarded(); f.fence.candidateRejected()
        assertFalse(f.oldFarewell()) // Revocation is irreversible.
    }

    @Test fun immediatelyClaimedTypedInputWakesParkedCaptionAndLeavesFutureDispatchAvailable() = runBlocking {
        val f = Fixture(); f.fence.replyCandidate(true)
        val completedCaption = async(start = CoroutineStart.UNDISPATCHED) { f.oldFarewell() }
        val typedWatch = launch(start = CoroutineStart.UNDISPATCHED) {
            f.queue.awaitInputAfter(f.boundary); f.fence.revoke { }
        }
        val dispatch = async(start = CoroutineStart.UNDISPATCHED) { f.queue.awaitAvailable(f.call.id) }
        val typed = CallFinalInput("typed", f.call.id, "conversation", "continue", 1)
        assertEquals(CallInputAdmission.Queued, f.queue.offer(typed, f.call.id))
        assertEquals(typed, f.queue.claim(f.call.id)); assertTrue(f.queue.promote(typed) {})
        withTimeout(1000) { typedWatch.join() }
        // A claimed item need not leave availability true. The independent observer
        // still must not steal the dispatcher's next genuine availability wake.
        val later = CallFinalInput("later", f.call.id, "conversation", "one more", 2)
        assertEquals(CallInputAdmission.Queued, f.queue.offer(later, f.call.id))
        withTimeout(1000) { dispatch.await() }
        assertFalse(withTimeout(1000) { completedCaption.await() })
        assertEquals(0, f.farewellEffects)
    }

    @Test fun resumedCandidateBeforePublicationWinsAndKeepsEffectsParked() = runBlocking {
        val f = Fixture(); f.fence.replyCandidate(true); f.fence.transferToCapture()
        val completedCaption = async(start = CoroutineStart.UNDISPATCHED) { f.oldFarewell() }
        f.fence.candidateRejected()
        f.fence.candidatePending() // New onset before the publication coroutine gets its lock.
        yield()
        assertFalse(completedCaption.isCompleted)
        f.fence.revoke { f.controller.revokeCaptionForInput(f.call.id) }
        assertFalse(withTimeout(1000) { completedCaption.await() })
        assertEquals(0, f.farewellEffects)
    }

    @Test fun quietInputKeepsLateFarewellEligibleAndNewCallRejectsStaleEffects() = runBlocking {
        val quiet = Fixture(); quiet.fence.transferToCapture()
        assertTrue(quiet.oldFarewell()); assertEquals(1, quiet.farewellEffects)
        val replaced = Fixture(); replaced.fence.replyCandidate(true)
        val parked = async(start = CoroutineStart.UNDISPATCHED) { replaced.oldFarewell() }
        replaced.controller.end(); val newCall = replaced.controller.beginCall("new")
        replaced.fence.candidateRejected()
        assertFalse(withTimeout(1000) { parked.await() })
        assertEquals(newCall.id, replaced.controller.currentCallId())
        assertEquals(0, replaced.farewellEffects)
    }

    @Test fun cancellationReleasesTheParkedOptionalPublication() = runBlocking {
        val f = Fixture(); f.fence.replyCandidate(true)
        val parked = launch(start = CoroutineStart.UNDISPATCHED) { f.oldFarewell() }
        withTimeout(1000) { parked.cancelAndJoin() }
        assertEquals(0, f.farewellEffects)
    }
    private fun decision(probability: Float, bytes: Long, complete: Boolean = true) = SpeechDecision(
        probability >= .5f, probability, rawCoverage = RawVadCoverage(bytes,
            if (complete) bytes / 2 else bytes / 2 - 1, if (probability < .15f) 0 else bytes / 2, 1))

    @Test fun rawOnsetBetweenClaimAndFarewellCommitRevokesWithoutWaitingForStorage() = runBlocking {
        val f = Fixture(); f.fence.transferToCapture()
        val claimSeen = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val old = async(Dispatchers.Default) { f.oldFarewell { claimSeen.countDown(); release.await() } }
        try {
            assertTrue(claimSeen.await(2, java.util.concurrent.TimeUnit.SECONDS))
            f.fence.rawOffered(1)
            f.fence.rawClassified(1, decision(.8f, 1024), decision(.8f, 1024))
            f.fence.revoke { } // The verified onset wins before final call-state mutation.
            release.countDown()
            assertFalse(withTimeout(2000) { old.await() })
            assertEquals(0, f.farewellEffects)
            assertEquals(f.call.id, f.controller.currentCallId())
        } finally { release.countDown(); old.cancelAndJoin() }
    }

    @Test fun typedArrivalAfterClaimPreventsTranscriptMemoryAndCheckpointEffects() = runBlocking {
        val f = Fixture(); f.fence.transferToCapture()
        var attempted = false
        val savedBeforeClaim = f.checkpoints.get()
        assertFalse(f.oldFarewell {
            if (!attempted) {
                attempted = true
                val typed = CallFinalInput("typed", f.call.id, "conversation", "new input", 1)
                assertEquals(CallInputAdmission.Queued, f.queue.offer(typed, f.call.id))
                assertEquals(typed, f.queue.claim(f.call.id)); assertTrue(f.queue.promote(typed) {})
            }
        })
        assertEquals(0, f.farewellEffects)
        assertEquals(GemmaAudioInputPolicy.PENDING_TRANSCRIPT, f.controller.currentTranscript().first().text)
        assertEquals(savedBeforeClaim, f.checkpoints.get())
    }

    @Test fun unclassifiedAndPartialCoverageRawFramesHoldCompletedFarewell() = runBlocking {
        val f = Fixture(); f.fence.transferToCapture(1, 1)
        val old = async(start = CoroutineStart.UNDISPATCHED) { f.oldFarewell() }
        assertFalse(old.isCompleted)
        f.fence.rawClassified(1, decision(0f, 1024, false), decision(0f, 1024, false))
        f.fence.candidateRejected(); yield()
        assertFalse(old.isCompleted)
        f.fence.rawClassified(1, decision(0f, 1024), decision(0f, 1024))
        assertTrue(withTimeout(1000) { old.await() })
    }

    @Test fun oldCandidateRejectionCannotClearNewerRawRiskOrUnprocessedAsr() = runBlocking {
        val f = Fixture(); f.fence.transferToCapture()
        f.fence.candidatePending(); f.fence.rawOffered(1)
        f.fence.rawClassified(1, decision(.2f, 1024), decision(.2f, 1024))
        val old = async(start = CoroutineStart.UNDISPATCHED) { f.oldFarewell() }
        f.fence.candidateRejected(); yield()
        assertFalse(old.isCompleted)
        f.fence.rawOffered(2)
        f.fence.rawClassified(2, decision(0f, 2048), decision(0f, 2048))
        yield(); assertFalse(old.isCompleted) // Quiet VAD ahead of the ASR collector is insufficient.
        f.fence.rawConsumed(2) // Prior risk frame's ordered ASR/retention work has finished.
        assertTrue(withTimeout(1000) { old.await() })
    }

    @Test fun retainedConfirmedRiskSurvivesQuietProducerAndOlderRejectionCannotClearNewRawFrame() = runBlocking {
        val f = Fixture(); f.fence.transferToCapture()
        f.fence.rawOffered(1); f.fence.rawClassified(1, decision(.8f, 1024), decision(.8f, 1024))
        f.fence.candidatePending()
        f.fence.rawOffered(2); f.fence.rawClassified(2, decision(0f, 2048), decision(0f, 2048)); f.fence.rawConsumed(2)
        val old = async(start = CoroutineStart.UNDISPATCHED) { f.oldFarewell() }
        assertFalse(old.isCompleted)
        f.fence.rawOffered(3); f.fence.rawClassified(3, decision(.2f, 3072), decision(.2f, 3072))
        f.fence.candidateRejected(); yield(); assertFalse(old.isCompleted)
        f.fence.revoke { }
        assertFalse(withTimeout(1000) { old.await() })
    }

    @Test fun completeNativeAudioWithoutAsrTextRevokesOldFarewellBeforeSealedHandoff() = runBlocking {
        val f = Fixture(); f.fence.transferToCapture(); f.fence.candidatePending()
        val old = async(start = CoroutineStart.UNDISPATCHED) { f.oldFarewell() }
        val admission = CaptionInputAdmission({ true }, onAccepted = { f.fence.revoke { } },
            onPending = f.fence::candidatePending, onRejected = f.fence::candidateRejected)
        admission.onFinalCandidate(true, "", false, false, nativeAudioAccepted = true)
        assertFalse(withTimeout(1000) { old.await() })
        assertEquals(0, f.farewellEffects)
    }

    @Test fun slowCheckpointNeverLocksRawTransferOrAdmissionAndCannotOverwriteNewerInput() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        var block = false
        val saved = java.util.concurrent.atomic.AtomicReference<VoiceCallRecord>()
        val controller = VoiceSessionController(object : VoiceCallStore {
            override fun list() = emptyList<VoiceCallRecord>()
            override fun save(call: VoiceCallRecord) {
                if (block) { entered.countDown(); release.await() }
                saved.set(call)
            }
            override fun delete(callId: String) {}
        })
        val call = controller.beginCall("conversation")
        controller.appendTranscript("You", GemmaAudioInputPolicy.PENDING_TRANSCRIPT); controller.beginReply(call.id, "reply")
        val ticket = requireNotNull(controller.captionTicket(call.id, "reply", GemmaAudioInputPolicy.PENDING_TRANSCRIPT))
        val fence = CaptionPublicationFence()
        var commit: VoiceSessionController.CaptionCommit? = null
        assertTrue(fence.publishWhenResolved({ true }) { claim ->
            commit = controller.commitCaption(ticket, "accepted prior words", { true }, authorize = { fence.commit(claim) })
            commit != null
        })
        block = true
        val checkpoint = async(Dispatchers.Default) { controller.checkpointCaption(requireNotNull(commit)) }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            withTimeout(1000) {
                async(Dispatchers.Default) {
                    fence.transferToCapture(1, 1); fence.rawOffered(2)
                    assertTrue(fence.revoke { })
                }.await()
            }
        } finally { block = false; release.countDown(); checkpoint.await() }
        controller.appendTranscript("You", "newer input")
        controller.checkpointCaption(requireNotNull(commit))
        assertEquals("newer input", saved.get().transcript.last().text)
        controller.end(); val newerCall = controller.beginCall("newer conversation")
        controller.checkpointCaption(requireNotNull(commit))
        assertEquals(newerCall.id, saved.get().id)
    }

}
