package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class NativePauseEndpointPolicyTest {
    private val completeRaw = RawVadCoverage(32768, 16384, 2048, 1)
    private val eligible = NativePauseEndpointPolicy.Eligibility(true, "clean_native_pause")
    @Test fun qualifyingNativeMissingCaptionUses650AndKeepsExisting350() {
        assertEquals(650L, NativePauseEndpointPolicy.decision(AdaptiveTurnEnd.Decision(900, "no_transcript"), eligible, completeRaw, 32768).silenceMs)
        assertEquals(650L, NativePauseEndpointPolicy.decision(AdaptiveTurnEnd.Decision(1100, "transcript_settling"), eligible, completeRaw, 32768).silenceMs)
        assertEquals(350L, NativePauseEndpointPolicy.decision(AdaptiveTurnEnd.Decision(350, "complete_and_stable"), eligible, completeRaw, 32768).silenceMs)
    }
    @Test fun knownThinkingIncompleteAndUnknownSemanticsKeepTheirMargins() {
        for ((ms, cue) in listOf(1800L to "unfinished", 1500L to "uncertain", 3500L to "explicit_hesitation")) {
            val d = AdaptiveTurnEnd.Decision(ms, cue)
            assertEquals(d, NativePauseEndpointPolicy.decision(d, eligible, completeRaw, 32768))
            assertFalse(NativePauseEndpointPolicy.permitsProposal(d))
        }
        val base = AdaptiveTurnEnd.Decision(900, "no_transcript")
        assertEquals(base, NativePauseEndpointPolicy.decision(base, NativePauseEndpointPolicy.Eligibility(false, "weak"), completeRaw, 32768))
    }
    @Test fun punctuationlessCompleteQuestionsQualifyButFragmentsDoNot() {
        val end = AdaptiveTurnEnd()
        end.update("what is two plus two", 100)
        assertEquals("complete_and_stable", end.decision(400).cue)
        end.update("what is the", 500)
        assertEquals(1800L, end.decision(1000).silenceMs)
        end.update("let me think", 1500)
        assertEquals(3500L, end.decision(3000).silenceMs)
    }
    @Test fun weakOnsetBacklogAndRawUnknownCannotUseFastNativePolicy() {
        fun evaluate(strong: Long = 240, weak: Boolean = false, onset: Boolean = false,
                     backlog: Long = 0, raw: RawVadCoverage? = RawVadCoverage(16000, 7680, 2048, 3)) =
            NativePauseEndpointPolicy.eligibility(true, strong, weak, 350, raw, onset, backlog, true)
        assertTrue(evaluate().allowed)
        assertFalse(evaluate(strong = 239).allowed)
        assertFalse(evaluate(weak = true).allowed)
        assertFalse(evaluate(onset = true).allowed)
        assertFalse(evaluate(backlog = 1).allowed)
        assertFalse(evaluate(raw = null).allowed)
        assertFalse(evaluate(raw = RawVadCoverage(16000, 7680, 4096, 3)).allowed)
    }

    @Test fun lateCompletedQuestionUpdatesEndpointWithoutPretendingItCoversNewWords() {
        val end = AdaptiveTurnEnd(); val tracker = NativeCaptionEndpointCueTracker()
        end.update("what is the", 0)
        assertTrue(tracker.update(CompletedEndpointCue(1, "what is the weather today", 8000), 7500, 100, end))
        assertEquals(350L, tracker.decision(end.decision(400), 7500).silenceMs)
        // A later completion of an OLD window is not fresh evidence for new speech.
        assertFalse(tracker.update(CompletedEndpointCue(2, "what is the weather today", 8000), 9000, 500, end))
        val slowLegacy = AdaptiveTurnEnd.Decision(1800, "unfinished")
        assertEquals(slowLegacy, tracker.decision(slowLegacy, 9000, end.decision(1000)))
        assertTrue(tracker.update(CompletedEndpointCue(3, "what is the weather today and", 9500), 9000, 1100, end))
        assertEquals(1800L, tracker.decision(end.decision(1500), 9000).silenceMs)
    }

    @Test fun acceleratedEndpointCannotCutUnclassifiedOnsetEvenWhenCandidateCouldBeFrozen() {
        val base = AdaptiveTurnEnd.Decision(900, "no_transcript")
        val partial = RawVadCoverage(16000, 7680, 2048, 3)
        assertEquals(base, NativePauseEndpointPolicy.decision(base, eligible, partial, 16000))
        assertEquals(base, NativePauseEndpointPolicy.decision(base, eligible, completeRaw, 15360))
        assertEquals(650L, NativePauseEndpointPolicy.decision(base, eligible, completeRaw, 32768).silenceMs)
    }

    @Test fun firstStaleCueCannotShortenSlowLegacyOrSlowIndependentlyCompleteLegacy() {
        val end = AdaptiveTurnEnd(); val tracker = NativeCaptionEndpointCueTracker()
        end.update("what is two plus two", 0)
        assertFalse(tracker.update(CompletedEndpointCue(1, "what is two plus two", 8000), 9000, 500, end))
        assertEquals(350L, tracker.decision(end.decision(1000), 9000).silenceMs)
        val slowLegacy = AdaptiveTurnEnd.Decision(1800, "unfinished")
        assertEquals(slowLegacy, tracker.decision(slowLegacy, 9000, end.decision(1000)))
    }
    @Test fun newlyDeliveredCompleteCueCannotAccelerateEndpointWithUnclassifiedTail() {
        val legacy = AdaptiveTurnEnd().also { it.update("what is the", 0) }
        val completed = AdaptiveTurnEnd(); val tracker = NativeCaptionEndpointCueTracker()
        tracker.update(CompletedEndpointCue(1, "what is the weather today", 8000), 7500, 100, completed)
        // The capture only passes completedDecision when its exact raw collector boundary is covered.
        assertEquals(1800L, tracker.decision(legacy.decision(500), 7500).silenceMs)
        assertEquals(350L, tracker.decision(legacy.decision(500), 7500, completed.decision(500)).silenceMs)
    }

    @Test fun weakRawTailNeedsWholeNewConfirmationMarginWithoutMovingSpeechClock() {
        val legacy = AdaptiveTurnEnd.Decision(900, "no_transcript")
        val threeHundredMs = RawVadCoverage(32768, 16384, 11264, 1) // 320 ms, exact frame boundary.
        assertEquals(legacy, NativePauseEndpointPolicy.decision(legacy, eligible, threeHundredMs, 32768))
        assertEquals(650L, NativePauseEndpointPolicy.decision(legacy, eligible, completeRaw, 32768).silenceMs)
        val newComplete = AdaptiveTurnEnd.Decision(350, "complete_and_stable")
        assertEquals(legacy, NativePauseEndpointPolicy.decision(newComplete, eligible, threeHundredMs, 32768, legacy))
        val threeFiftyMs = RawVadCoverage(32768, 16384, 10752, 1) // 352 ms, no padding.
        assertEquals(newComplete, NativePauseEndpointPolicy.decision(newComplete, eligible, threeFiftyMs, 32768, legacy))
        // Independent legacy350 remains independent, even with an incomplete raw frame.
        assertEquals(newComplete, NativePauseEndpointPolicy.decision(newComplete, eligible,
            RawVadCoverage(33000, 16384, 11264, 0), 33000, newComplete))
    }
}
