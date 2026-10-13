package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceActionControlTest {
    @Test fun speechRequestsNeverAliasActionCancellation() {
        assertEquals(VoiceActionControl.SpeechOnly, VoiceActionControl.parse("stop speaking", true))
        assertEquals(VoiceActionControl.SpeechOnly, VoiceActionControl.parse("wait", true))
        assertEquals(VoiceActionControl.SpeechOnly, VoiceActionControl.parse("cancel", false))
    }

    @Test fun onlyWholeExplicitFinalControlsCancelAcceptedActions() {
        assertEquals(VoiceActionControl.CancelNewest, VoiceActionControl.parse("cancel that action", true))
        assertEquals(VoiceActionControl.CancelCurrent, VoiceActionControl.parse("cancel the current request", true))
        assertEquals(VoiceActionControl.CancelQueued, VoiceActionControl.parse("cancel all queued actions", true))
        assertEquals(VoiceActionControl.CancelAll, VoiceActionControl.parse("cancel all actions", true))
        assertEquals(VoiceActionControl.None, VoiceActionControl.parse("I said cancel that action", true))
        assertEquals(VoiceActionControl.None, VoiceActionControl.parse("\"cancel all actions\"", true))
        assertEquals(VoiceActionControl.None, VoiceActionControl.parse("don't cancel all actions", true))
    }
}
