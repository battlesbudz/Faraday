package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceNavigationPolicyTest {
    @Test fun onlyExplicitEndDispatchesTheTerminationCallback() {
        var ends = 0
        VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.SHOW_CHAT) { ends++ }
        VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.SHOW_VOICE) { ends++ }
        assertEquals(0, ends)
        VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.EXPLICIT_END) { ends++ }
        assertEquals(1, ends)
    }
}
