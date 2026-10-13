package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class KeywordPrimingTest {
 @Test fun historicalKeywordsAreDiscardedAndWarmupIsBounded() {
  var bytes = 0
  val logs = mutableListOf<String>()
  val detector = object : InterruptionKeywordDetector {
   override val ready get() = bytes >= 99200
   override fun accept(pcm: ByteArray): String? { bytes += pcm.size; return "Hey_Jarvis" }
   override fun close() {}
  }
  primeInterruptionKeywords(detector, ByteArray(192000), logs::add)
  assertEquals(99200, bytes); assertTrue(detector.ready)
  assertTrue(logs.single().contains("historicalHitsIgnored=31"))
 }
}
