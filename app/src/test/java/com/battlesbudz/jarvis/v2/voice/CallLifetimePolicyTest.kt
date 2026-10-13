package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class CallLifetimePolicyTest {
 @Test fun silenceContinuesButFinalGoodbyeEnds() { assertNull(CallLifetimePolicy.initialSilenceTimeoutMs()); assertTrue(CallLifetimePolicy.endsSession("goodbye Jarvis", true)); assertFalse(CallLifetimePolicy.endsSession("stop speaking", true)); assertFalse(CallLifetimePolicy.endsSession("goodbye", false)) }
}
