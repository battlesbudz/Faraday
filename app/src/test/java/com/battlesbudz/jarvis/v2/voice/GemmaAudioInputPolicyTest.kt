package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class GemmaAudioInputPolicyTest {
    @Test fun captionsOffDisablesCaptionsButNotTheReplyProbe() {
        // Direct audio with captions off: no caption recognizer, but the reply
        // interruption probe stays on so natural speech can stop playback.
        assertFalse(GemmaAudioInputPolicy.usesRecognizer(directAudio = true, captions = false))
        assertTrue(GemmaAudioInputPolicy.replyProbeEnabled())
        assertTrue(GemmaAudioInputPolicy.usesRecognizer(directAudio = true, captions = true))
    }
    @Test fun transcribedRouteKeepsRecognizerAndReplyProbe() {
        assertTrue(GemmaAudioInputPolicy.usesRecognizer(directAudio = false, captions = false))
        assertTrue(GemmaAudioInputPolicy.replyProbeEnabled())
    }
    @Test fun asrFreeComparisonDisablesRecognizerAndReplyProbe() {
        assertFalse(GemmaAudioInputPolicy.usesRecognizer(directAudio = true, captions = true, nativeAudioComparison = true))
        assertFalse(GemmaAudioInputPolicy.replyProbeEnabled(nativeAudioComparison = true))
    }
    @Test fun completeShortAudioUsesRecordingNotCaption() {
        assertNull(GemmaAudioInputPolicy.rejection(true, true, 16044))
        assertTrue(GemmaAudioInputPolicy.REQUEST.contains("current request is the audio"))
        assertTrue(GemmaAudioInputPolicy.MAX_CAPTURE_MS < 30000)
    }
    @Test fun currentWhisperCaptionNeverEntersAudioPromptHistory() {
        val wrongCaption = "What do you think of thinking?"
        // The coordinator stores a marker until Gemma transcribes; Whisper remains UI-only.
        val history = listOf("You" to "Prior question", "Jarvis" to "Prior answer", "You" to GemmaAudioInputPolicy.PENDING_TRANSCRIPT)
        val promptHistory = history.filterNot { GemmaAudioInputPolicy.isPendingTranscript(it.first, it.second) }
        assertEquals(2, promptHistory.size)
        assertFalse(promptHistory.joinToString().contains(wrongCaption))
        assertFalse(promptHistory.joinToString().contains(GemmaAudioInputPolicy.PENDING_TRANSCRIPT))
        assertFalse(GemmaAudioInputPolicy.isPendingTranscript("Jarvis", GemmaAudioInputPolicy.PENDING_TRANSCRIPT))
    }
    @Test fun captionFailuresDoNotRejectACompleteRecording() {
        for (captionFailure in listOf("unrecognized_segment", "segment_boundary_uncertain", "asr_recovery_empty", null)) {
            assertNull(GemmaAudioInputPolicy.retainedAudioIssue(captionFailure, true, true, 64044))
        }
        assertEquals("gemma_audio_request_exceeds_limit", GemmaAudioInputPolicy.retainedAudioIssue("gemma_audio_request_exceeds_limit", true, true, 896044))
    }
    @Test fun incompleteAudioIsNeverSubmittedAsRollingTail() {
        assertEquals("gemma_audio_request_exceeds_limit", GemmaAudioInputPolicy.rejection(true, false, 800044))
    }
    @Test fun unsupportedAndEmptyRequestsFailExplicitly() {
        assertEquals("selected_model_has_no_audio_input", GemmaAudioInputPolicy.rejection(false, true, 64044))
        assertEquals("gemma_audio_empty", GemmaAudioInputPolicy.rejection(true, true, 44))
    }
}
