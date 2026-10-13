package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class CaptureFirstReplyHandoffTest {
    private fun receipt(turn: String = "old") = SpeechDelivery(turn, SpeechDeliveryState.COMPLETED,
        playedFrames = 320, spans = listOf(DeliveredSpeechSpan(0, "Done.", 0, 320, 16000, sealed = true)))
    private fun playback() = requireNotNull(NormalReplyPlayback.from("old", receipt(), 100))
    private fun turn(text: String) = CapturedVoiceTurn(text, byteArrayOf(1, 2))
    private fun unusedInput() = object : AudioInput {
        override val sampleRateHz = 16000
        override val channelCount = 1
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override fun chunks() = emptyFlow<ByteArray>()
    }

    @Test fun playbackRequiresCompletedSameTurnPositiveSealedDeliveredFrames() {
        assertEquals(NormalReplyPlayback("old", 123), NormalReplyPlayback.from("old", receipt(), 123))
        assertNull(NormalReplyPlayback.from("old", null, 123))
        assertNull(NormalReplyPlayback.from("other", receipt(), 123))
        SpeechDeliveryState.entries.filter { it != SpeechDeliveryState.COMPLETED }.forEach { state ->
            assertNull("$state must not rearm capture", NormalReplyPlayback.from("old", receipt().copy(state = state), 123))
        }
        val full = receipt(); val span = full.spans.single()
        listOf(
            full.copy(playedFrames = 0),
            full.copy(spans = emptyList()),
            full.copy(spans = listOf(span.copy(sealed = false))),
            full.copy(spans = listOf(span.copy(endFrame = span.startFrame))),
            full.copy(playedFrames = span.endFrame - 1),
            full.copy(spans = full.spans + span.copy(index = 1, startFrame = 320, endFrame = 640)),
        ).forEach { assertNull(NormalReplyPlayback.from("old", it, 123)) }
    }

    @Test fun completedReplyJobAloneNeverStartsFollowupCapture() = runBlocking {
        val noPlayback = CompletableDeferred<NormalReplyPlayback>()
        val result = CaptureFirstReplyHandoff.run(
            normalPlayback = noPlayback, reply = { "silent answer" }, listen = { awaitCancellation() },
            stopReply = { fail("No interruption") },
            beginCapture = { error("Job completion is not successful playback") },
            capture = { _, _ -> error("No audio delivered") }, awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
        assertEquals(CaptureFirstReplyHandoff.Result.Finished("silent answer"), result)
        assertFalse(noPlayback.isCompleted)
    }

    @Test fun oldNativeCloseCanParkWhileRawAudioContinuesAndCaptionRemainsOwned() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        val normal = CompletableDeferred<NormalReplyPlayback>()
        val listenerReady = CompletableDeferred<Unit>(); val listenerClose = CompletableDeferred<Unit>()
        val releaseListener = CompletableDeferred<Unit>(); val rawReady = CompletableDeferred<Unit>()
        val captureReady = CompletableDeferred<Unit>(); val accepted = CompletableDeferred<Unit>()
        val captionReady = CompletableDeferred<Unit>(); val captionClose = CompletableDeferred<Unit>()
        val releaseCaption = CompletableDeferred<Unit>()
        val resetStarted = CompletableDeferred<Unit>(); val releaseReset = CompletableDeferred<Unit>()
        var nextEncoderAdmitted = false
        val events = mutableListOf<String>()
        val expected = CapturedVoiceTurn("next request", byteArrayOf(1, 2, 3, 4), utteranceId = "next")
        var captionCancelled = false
        try {
            rig.start()
            val result = async {
                CaptureFirstReplyHandoff.run(
                    normalPlayback = normal,
                    reply = {
                        PostAnswerCaptionContinuation.run(
                            isCurrent = { true }, hasNextInput = { accepted.isCompleted }, awaitNextInput = { accepted.await() },
                            generate = {
                                captionReady.complete(Unit)
                                try { awaitCancellation() }
                                finally { withContext(NonCancellable) {
                                    captionCancelled = true; captionClose.complete(Unit); releaseCaption.await()
                                    events += "caption-native-close"
                                } }
                            }, checkedReset = {
                                resetStarted.complete(Unit); releaseReset.await(); events += "checked-reset"
                            })
                        "answer"
                    },
                    listen = {
                        rig.bridge.replyInput.start(); listenerReady.complete(Unit)
                        try { awaitCancellation() }
                        finally { withContext(NonCancellable) {
                            rig.bridge.replyInput.stop()
                            listenerClose.complete(Unit); releaseListener.await(); events += "listener-native-close"
                        } }
                    }, stopReply = { fail("Normal playback must not stop the reply") },
                    beginCapture = { rig.bridge.beginFollowup().also { rawReady.complete(Unit) } },
                    capture = { input, _ ->
                        captureReady.complete(Unit); input.start()
                        val frames = input.chunks().take(2).toList()
                        assertArrayEquals(expected.wav, frames.flatMap { it.asIterable() }.toByteArray())
                        accepted.complete(Unit); expected
                    }, awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false }, observe = events::add).also { nextEncoderAdmitted = true }
            }
            withTimeout(2000) { listenerReady.await(); captionReady.await() }
            rig.push(byteArrayOf(1, 2), 100)
            normal.complete(playback())
            withTimeout(2000) { rawReady.await(); listenerClose.await() }
            assertFalse(captureReady.isCompleted)
            assertFalse(captionCancelled)
            assertFalse(result.isCompleted)
            rig.push(byteArrayOf(3, 4), 100)
            assertEquals(2L, rig.admitted)
            assertEquals(0L, rig.acknowledged)
            assertEquals(1, rig.hardware.starts)
            assertEquals(0, rig.hardware.stops)
            releaseListener.complete(Unit)
            withTimeout(2000) { captureReady.await(); captionClose.await() }
            assertFalse(result.isCompleted)
            assertFalse(events.contains("checked-reset"))
            releaseCaption.complete(Unit)
            withTimeout(2000) { resetStarted.await() }
            assertFalse(result.isCompleted)
            assertFalse(nextEncoderAdmitted)
            assertFalse(events.contains("capture_first_sealed_input_released"))
            releaseReset.complete(Unit)
            assertEquals(CaptureFirstReplyHandoff.Result.Finished("answer", expected), withTimeout(2000) { result.await() })
            assertTrue(nextEncoderAdmitted)
            assertTrue(events.indexOf("listener-native-close") < events.indexOf("capture_first_previous_listener_joined"))
            assertTrue(events.indexOf("caption-native-close") < events.indexOf("checked-reset"))
            assertTrue(events.indexOf("checked-reset") < events.indexOf("capture_first_sealed_input_released"))
        } finally { releaseListener.complete(Unit); releaseCaption.complete(Unit); releaseReset.complete(Unit); rig.close() }
        assertEquals(1, rig.hardware.stops)
    }

    @Test fun acceptedTypedInputCancelsCaptureAndWaitsForItsExactClose() = runBlocking {
        val typed = CompletableDeferred<Unit>(); val capturing = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>(); val releaseClose = CompletableDeferred<Unit>()
        var replyReleased = false
        try {
            val answer = async {
                CaptureFirstReplyHandoff.run(
                    normalPlayback = CompletableDeferred(playback()),
                    reply = { typed.await(); replyReleased = true; "done" }, listen = { awaitCancellation() },
                    stopReply = { fail("Typed followup is not interruption") }, beginCapture = { unusedInput() },
                    capture = { _, _ ->
                        capturing.complete(Unit)
                        try { awaitCancellation() }
                        finally { withContext(NonCancellable) { closeStarted.complete(Unit); releaseClose.await() } }
                    }, awaitTypedInput = { typed.await() }, hasTypedInput = { typed.isCompleted })
            }
            withTimeout(2000) { capturing.await() }; typed.complete(Unit)
            withTimeout(2000) { closeStarted.await() }
            assertTrue(replyReleased)
            assertFalse(answer.isCompleted)
            releaseClose.complete(Unit)
            assertEquals(CaptureFirstReplyHandoff.Result.Finished("done"), withTimeout(2000) { answer.await() })
        } finally { releaseClose.complete(Unit) }
    }

    @Test fun confirmedInterruptionWinsAlreadyCompletedPlaybackWithoutRearming() = runBlocking {
        val confirmed = CompletableDeferred<Unit>()
        val normal = CompletableDeferred<NormalReplyPlayback>()
        val expected = turn("stop and change it")
        var stopCount = 0
        val result = CaptureFirstReplyHandoff.run(
            normalPlayback = normal, reply = { confirmed.await(); awaitCancellation() },
            listen = { callback -> callback(); confirmed.complete(Unit); normal.complete(playback()); expected },
            stopReply = { stopCount++ }, beginCapture = { error("Confirmed interruption must win") },
            capture = { _, _ -> error("No ordinary handoff") }, awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
        assertEquals(CaptureFirstReplyHandoff.Result.Interrupted(expected), result)
        assertEquals(1, stopCount)
    }

    @Test fun failedOutputPropagatesWithoutCaptureOrSuccessfulCompletion() = runBlocking {
        supervisorScope {
            val failure = IllegalStateException("output route failed")
            val result = async {
                CaptureFirstReplyHandoff.run(
                    normalPlayback = CompletableDeferred(), reply = { throw failure }, listen = { awaitCancellation() },
                    stopReply = {}, beginCapture = { error("Failed output must not arm") },
                    capture = { _, _ -> error("Failed output must not capture") }, awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
            }
            try { result.await(); fail("Failure became Finished") }
            catch (actual: IllegalStateException) { assertEquals(failure.message, actual.message) }
        }
    }

    @Test fun nativeResetFailurePropagatesInsteadOfReleasingNextTurn() = runBlocking {
        supervisorScope {
            val captured = CompletableDeferred<Unit>()
            var nativeReady: (() -> Boolean)? = null
            val result = async {
                CaptureFirstReplyHandoff.run(
                    normalPlayback = CompletableDeferred(playback()),
                    reply = { captured.await(); error("old native owner quarantined") }, listen = { awaitCancellation() },
                    stopReply = {}, beginCapture = { unusedInput() },
                    capture = { _, ready -> nativeReady = ready; assertFalse(ready()); captured.complete(Unit); turn("must not run") },
                    awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
            }
            try { withTimeout(2000) { result.await() }; fail("Quarantined owner released a next turn") }
            catch (actual: IllegalStateException) { assertEquals("old native owner quarantined", actual.message) }
            assertFalse(requireNotNull(nativeReady).invoke())
        }
    }

    @Test fun cancellationJoinsBothOldListenerAndNewCaptureCleanup() = runBlocking {
        val listenerStarted = CompletableDeferred<Unit>(); val listenerClosing = CompletableDeferred<Unit>()
        val releaseListener = CompletableDeferred<Unit>(); val rawReady = CompletableDeferred<Unit>()
        val normal = CompletableDeferred<NormalReplyPlayback>()
        var replyClosed = false; var listenerClosed = false; var captureCalls = 0
        try {
            val task = launch {
                CaptureFirstReplyHandoff.run(
                    normalPlayback = normal,
                    reply = { try { awaitCancellation() } finally { replyClosed = true } },
                    listen = {
                        listenerStarted.complete(Unit)
                        try { awaitCancellation() }
                        finally { withContext(NonCancellable) {
                            listenerClosing.complete(Unit); releaseListener.await(); listenerClosed = true
                        } }
                    }, stopReply = {}, beginCapture = { unusedInput().also { rawReady.complete(Unit) } },
                    capture = { _, _ -> captureCalls++; awaitCancellation() },
                    awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
            }
            withTimeout(2000) { listenerStarted.await() }; normal.complete(playback())
            withTimeout(2000) { rawReady.await(); listenerClosing.await() }
            task.cancel(); yield()
            assertFalse(task.isCompleted)
            assertFalse(listenerClosed)
            releaseListener.complete(Unit); withTimeout(2000) { task.join() }
            assertTrue(replyClosed); assertTrue(listenerClosed); assertEquals(0, captureCalls)
        } finally { releaseListener.complete(Unit) }
    }
    @Test fun typedInputAlreadyAcceptedDoesNotConstructOrdinaryRecognizer() = runBlocking {
        var captures = 0; var begins = 0
        val result = CaptureFirstReplyHandoff.run(
            normalPlayback = CompletableDeferred(playback()), reply = { "done" }, listen = { awaitCancellation() },
            stopReply = { fail("Not an interruption") }, beginCapture = { begins++; unusedInput() },
            capture = { _, _ -> captures++; error("Typed input already owns next turn") },
            awaitTypedInput = {}, hasTypedInput = { true })
        assertEquals(CaptureFirstReplyHandoff.Result.Finished("done"), result)
        assertEquals(1, begins)
        assertEquals(0, captures)
    }

    @Test fun cancelledPlaybackReceiptCannotBecomeNormalSuccess() = runBlocking {
        val normal = CompletableDeferred<NormalReplyPlayback>()
        normal.cancel(CancellationException("output cancelled"))
        try {
            CaptureFirstReplyHandoff.run(
                normalPlayback = normal, reply = { awaitCancellation() }, listen = { awaitCancellation() },
                stopReply = {}, beginCapture = { error("Cancelled output must not arm") },
                capture = { _, _ -> error("Cancelled output must not capture") },
                awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
            fail("Cancelled playback returned success")
        } catch (error: CancellationException) { assertEquals("output cancelled", error.message) }
    }

    @Test fun actualCaptureRetainsNineSecondsWhileOldGemmaDrainBlocksNextEncoder() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        val normal = CompletableDeferred<NormalReplyPlayback>()
        val listenerReady = CompletableDeferred<Unit>(); val captionReady = CompletableDeferred<Unit>()
        val acceptedSpeech = CompletableDeferred<Unit>(); val gemmaDrainStarted = CompletableDeferred<Unit>()
        val releaseGemmaDrain = CompletableDeferred<Unit>(); val resetStarted = CompletableDeferred<Unit>()
        val releaseReset = CompletableDeferred<Unit>(); val captureReady = CompletableDeferred<AudioTurnCapture>()
        val sealedCapture = CompletableDeferred<CapturedVoiceTurn>()
        val asrPcm = java.io.ByteArrayOutputStream()
        val observedPcm = java.io.ByteArrayOutputStream()
        val events = mutableListOf<String>()
        val frames = (0 until 90).map { index -> ByteArray(3200) { offset ->
            val sample = 1800 + index * 7 + (offset / 2) % 31
            if (offset % 2 == 0) sample.toByte() else (sample shr 8).toByte()
        } }
        val expectedPcm = frames.flatMap { it.asIterable() }.toByteArray()
        var clockMs = 0L
        var encoderAdmitted = false
        var detectorCloses = 0
        var recognizerCloses = 0
        var ownedCapture: AudioTurnCapture? = null
        try {
            rig.start()
            val handoff = async {
                CaptureFirstReplyHandoff.run(
                    normalPlayback = normal,
                    reply = {
                        PostAnswerCaptionContinuation.run(
                            isCurrent = { true }, hasNextInput = { acceptedSpeech.isCompleted },
                            awaitNextInput = { acceptedSpeech.await() },
                            generate = {
                                captionReady.complete(Unit)
                                try { awaitCancellation() }
                                finally { withContext(NonCancellable) {
                                    gemmaDrainStarted.complete(Unit); releaseGemmaDrain.await()
                                    events += "old-gemma-drained"
                                } }
                            }, checkedReset = {
                                resetStarted.complete(Unit); releaseReset.await(); events += "old-gemma-reset"
                            })
                        "previous answer"
                    },
                    listen = {
                        rig.bridge.replyInput.start(); listenerReady.complete(Unit)
                        try { awaitCancellation() } finally { rig.bridge.replyInput.stop() }
                    }, stopReply = { fail("Normal reply playback is complete") },
                    beginCapture = { delivered ->
                        assertEquals(playback(), delivered)
                        rig.bridge.beginFollowup(playbackEndedAtMs = delivered.completedAtMs)
                    },
                    capture = { input, _ ->
                        val capture = AudioTurnCapture(input, this,
                            createDetector = {
                                // Real 512-sample framing and three-frame confirmation; only model probabilities are synthetic.
                                FrameSpeechDetector(computeProbability = { 0.99f }, releaseModel = { detectorCloses++ })
                            }, nowMs = { clockMs }, nowNs = { clockMs * 1_000_000 },
                            createTranscriber = { object : StreamingTranscriber {
                                override fun accept(pcm: ByteArray): String {
                                    asrPcm.write(pcm); return "Please keep the complete correction"
                                }
                                override fun finish() = "Please keep the complete correction."
                                override fun close() { recognizerCloses++ }
                            } }, guardFollowupSpeech = true, captureDispatcher = Dispatchers.Unconfined,
                            onAcousticDecision = { _, _, confirmed, _ ->
                                if (confirmed.isSpeech) acceptedSpeech.complete(Unit)
                            }, retainedPcmObserver = object : RetainedPcmObserver {
                                override fun onPcm(retainedPcm16: ByteArray) { observedPcm.write(retainedPcm16) }
                                override fun onCandidateDiscarded() = fail("Verified speech must not be discarded")
                                override fun onCaptureInvalidated(reason: RetainedPcmObserver.Invalidation) {
                                    if (reason != RetainedPcmObserver.Invalidation.CANCELLED) fail("Unexpected invalidation: $reason")
                                }
                            })
                        ownedCapture = capture
                        try {
                            capture.start(); captureReady.complete(capture)
                            assertTrue(capture.awaitTurnCompletion())
                            val wav = capture.stop()
                            assertTrue(capture.audioIsComplete)
                            assertNull(capture.recognitionIssue)
                            assertEquals("Please keep the complete correction.", capture.finalTranscript)
                            CapturedVoiceTurn(capture.finalTranscript, wav, utteranceId = "nine-seconds")
                                .also { sealedCapture.complete(it) }
                        } finally { capture.stop() }
                    }, awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false }, observe = events::add
                ).also { encoderAdmitted = true }
            }
            withTimeout(2000) { listenerReady.await(); captionReady.await() }
            clockMs = 100; rig.push(frames.first(), clockMs)
            normal.complete(playback())
            val capture = withTimeout(2000) { captureReady.await() }
            withTimeout(2000) { gemmaDrainStarted.await() }
            assertFalse(encoderAdmitted)
            // Drain remains parked throughout all remaining speech. Ordinary VAD/ASR owns the PCM,
            // so a nine-second request is not mistaken for a six-second raw handoff backlog.
            frames.drop(1).forEachIndexed { index, frame ->
                clockMs = (index + 2) * 100L
                if (index == 88) capture.finishNow() // Existing explicit endpoint; no gate/threshold change.
                rig.push(frame, clockMs)
                withTimeout(2000) { while (rig.acknowledged < index + 2L) yield() }
                assertFalse(encoderAdmitted)
                assertFalse(resetStarted.isCompleted)
            }
            val sealed = withTimeout(2000) { sealedCapture.await() }
            assertEquals(9000 * 32, expectedPcm.size)
            assertArrayEquals(WavEncoder.pcm16Mono(expectedPcm, 16000), sealed.wav)
            assertArrayEquals(expectedPcm, asrPcm.toByteArray())
            assertArrayEquals(expectedPcm, observedPcm.toByteArray())
            assertFalse(handoff.isCompleted)
            assertFalse(encoderAdmitted)
            assertEquals(1, rig.hardware.starts)
            assertEquals(0, rig.hardware.stops)
            assertEquals(1, recognizerCloses); assertEquals(1, detectorCloses)
            releaseGemmaDrain.complete(Unit)
            withTimeout(2000) { resetStarted.await() }
            assertFalse(handoff.isCompleted); assertFalse(encoderAdmitted)
            releaseReset.complete(Unit)
            val finished = withTimeout(2000) { handoff.await() } as CaptureFirstReplyHandoff.Result.Finished
            assertSame(sealed, finished.followup)
            assertTrue(encoderAdmitted)
            assertTrue(events.indexOf("old-gemma-drained") < events.indexOf("old-gemma-reset"))
            assertTrue(events.indexOf("old-gemma-reset") < events.indexOf("capture_first_sealed_input_released"))
        } finally {
            releaseGemmaDrain.complete(Unit); releaseReset.complete(Unit)
            ownedCapture?.stop(); rig.close()
        }
        assertEquals(1, rig.hardware.stops)
    }

}
