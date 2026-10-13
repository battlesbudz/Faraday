package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class VoiceWorkSchedulerTest {
 @Test fun incrementalInputAllowsAllNonEmergencyThermalLevelsWithoutCooldown() {
  val scheduler = VoiceWorkScheduler()
  for (level in 0..4) repeat(3) { assertTrue(scheduler.admitPrefill(0, level)) }
  for (level in 5..6) assertFalse(scheduler.admitPrefill(0, level))
  assertEquals("thermal_emergency", scheduler.reason)
  assertFalse(scheduler.admitPrefill(201, 0))
  assertEquals("asr_backlog", scheduler.reason)
  assertTrue(scheduler.admitPrefill(0, 3))
 }
}
