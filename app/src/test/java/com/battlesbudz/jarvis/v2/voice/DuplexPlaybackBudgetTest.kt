package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class DuplexPlaybackBudgetTest {
 @Test fun startupTransitionDoesNotRevokeAnAdmittedProbe() {
  assertTrue(DuplexPlaybackBudget.allows(null, false, false))
  assertFalse(DuplexPlaybackBudget.allows(240, false, false))
  assertTrue(DuplexPlaybackBudget.allows(240, true, false))
  assertTrue(DuplexPlaybackBudget.allows(640, false, false))
  assertFalse(DuplexPlaybackBudget.allows(0, true, false))
  assertFalse(DuplexPlaybackBudget.allows(1200, true, true))
 }
}
