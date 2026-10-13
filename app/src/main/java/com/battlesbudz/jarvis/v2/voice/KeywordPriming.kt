package com.battlesbudz.jarvis.v2.voice

/** Discard historical hits. The detector only controls playback on fresh capture afterward. */
fun primeInterruptionKeywords(detector: InterruptionKeywordDetector, prior: ByteArray, log: (String) -> Unit = {}) {
    if (prior.isEmpty()) return
    val bounded = prior.takeLast(99_200).toByteArray()
    require(bounded.size % 2 == 0)
    var ignored = 0
    for (offset in bounded.indices step 3200) {
        if (detector.accept(bounded.copyOfRange(offset, minOf(offset + 3200, bounded.size))) != null) ignored++
    }
    log("barge_keyword_primed historyMs=${bounded.size / 32} ready=${detector.ready} historicalHitsIgnored=$ignored")
}
