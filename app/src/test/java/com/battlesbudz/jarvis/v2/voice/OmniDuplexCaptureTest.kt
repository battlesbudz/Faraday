package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class OmniDuplexCaptureTest {
    @Test fun vadNeedsLongerConfirmationDuringPlaybackAndResetsOnEcho() {
        val gate = OmniDuplexBargeInGate(playbackConfirmationMs = 240, idleConfirmationMs = 96)
        assertEquals(OmniDuplexBargeInGate.Decision.WAIT, gate.update(true, true, false, 0))
        assertEquals(OmniDuplexBargeInGate.Decision.WAIT, gate.update(true, true, false, 120))
        assertEquals(OmniDuplexBargeInGate.Decision.WAIT, gate.update(true, true, true, 180))
        assertEquals(OmniDuplexBargeInGate.Decision.WAIT, gate.update(true, true, false, 200))
        assertEquals(OmniDuplexBargeInGate.Decision.CONFIRMED, gate.update(true, true, false, 440))
        assertEquals(OmniDuplexBargeInGate.Decision.CONFIRMED, gate.update(false, true, false, 441))
    }

    @Test fun idleConfirmationUsesShorterVADWindow() {
        val gate = OmniDuplexBargeInGate()
        assertEquals(OmniDuplexBargeInGate.Decision.WAIT, gate.update(true, false, false, 100))
        assertEquals(OmniDuplexBargeInGate.Decision.WAIT, gate.update(true, false, false, 195))
        assertEquals(OmniDuplexBargeInGate.Decision.CONFIRMED, gate.update(true, false, false, 196))
    }

    @Test fun recordedPlaybackReferenceRejectsItsDelayedSpeakerEcho() {
        val guard = OmniPlaybackEchoGuard(maxDelayMs = 300)
        val spoken = RecordedSpeechFixture.pcm
        guard.rememberPlayback(spoken)
        // A 100 ms slice from the pinned human-speech recording, delayed 275 ms.
        val delayedEcho = spoken.copyOfRange(spoken.size - 12_000, spoken.size - 8_800)
        val isEcho = guard.isPlaybackEcho(delayedEcho)
        assertTrue(isEcho)
        val gate = OmniDuplexBargeInGate()
        repeat(20) { frame ->
            assertEquals(OmniDuplexBargeInGate.Decision.WAIT,
                gate.update(speech = true, playing = true, playbackEcho = isEcho, nowMs = frame * 20L))
        }
        assertFalse(guard.isPlaybackEcho(pcm(1600, seed = 91)))
    }

    @Test fun userSpeechMixedWithPlaybackLowersCorrelationEnoughToPass() {
        val guard = OmniPlaybackEchoGuard(maxDelayMs = 300)
        val playback = pcm(6400, seed = 17)
        guard.rememberPlayback(playback)
        val user = pcm(1600, seed = 91)
        val mixed = playback.copyOfRange(1600, 3200)
        for (i in 0 until mixed.size / 2) {
            val p = sample(mixed, i).toInt()
            val u = sample(user, i).toInt()
            putSample(mixed, i, ((p / 5) + u).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        assertFalse(guard.isPlaybackEcho(mixed))
    }

    @Test fun turnIncludesSixSecondPrerollAndDeadlineFirstEndpoint() {
        val capture = OmniDuplexTurnBuffer(preRollMs = 6000, endpointSilenceMs = 300)
        val sixSeconds = ByteArray(16_000 * 2 * 6) { 3 }
        val speech = ByteArray(3200) { 9 }
        capture.observe(sixSeconds, speech = false, nowMs = 0)
        capture.observe(speech, speech = true, nowMs = 100)
        assertTrue(capture.begin(nowMs = 100))
        assertEquals(sixSeconds.size + speech.size, capture.currentPcm().size)
        assertNull(capture.endpoint(399))
        val endpoint = capture.endpoint(400)
        assertEquals(OmniDuplexTurnBuffer.Reason.SILENCE, endpoint?.reason)
        assertEquals(sixSeconds.size + speech.size, endpoint?.pcm?.size)
    }

    @Test fun smartTurnCanHoldOnlyOneDeadlineWindowAndAudioCapWins() {
        val capture = OmniDuplexTurnBuffer(preRollMs = 10, maxTurnMs = 50, endpointSilenceMs = 20)
        capture.observe(ByteArray(100), speech = false, nowMs = 0)
        capture.observe(ByteArray(200), speech = true, nowMs = 10)
        capture.begin(nowMs = 10)
        assertNull(capture.endpoint(30, smartTurnWantsMore = true))
        assertNull(capture.endpoint(49, smartTurnWantsMore = true))
        assertEquals(OmniDuplexTurnBuffer.Reason.SILENCE, capture.endpoint(50, smartTurnWantsMore = true)?.reason)

        capture.observe(ByteArray(100), speech = false, nowMs = 100)
        capture.observe(ByteArray(200), speech = true, nowMs = 110)
        capture.begin(nowMs = 110)
        capture.observe(ByteArray(1500), speech = false, nowMs = 120)
        assertEquals(OmniDuplexTurnBuffer.Reason.AUDIO_CAP, capture.endpoint(121, smartTurnWantsMore = true)?.reason)
    }

    private fun pcm(samples: Int, seed: Int): ByteArray {
        var state = seed
        val result = ByteArray(samples * 2)
        repeat(samples) { index ->
            state = state * 1103515245 + 12345
            putSample(result, index, (state shr 16).toShort())
        }
        return result
    }

    private fun sample(pcm: ByteArray, index: Int): Short =
        ((pcm[index * 2].toInt() and 0xff) or (pcm[index * 2 + 1].toInt() shl 8)).toShort()

    private fun putSample(pcm: ByteArray, index: Int, value: Short) {
        pcm[index * 2] = value.toByte()
        pcm[index * 2 + 1] = (value.toInt() shr 8).toByte()
    }
}
