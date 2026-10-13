package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class GemmaSessionTextTest {
    @Test fun hiddenChannelsAndTurnMarkersAreRemovedAcrossEveryPossibleSplit() {
        val raw = "<|channel>thought\nInternal scratch work.<channel|>Rome is in Italy.<turn|><|turn>user\nBogus next turn"
        for (split in 0..raw.length) {
            val output = StringBuilder()
            val filter = GemmaSessionText(output::append)
            filter.accept(raw.take(split)); filter.accept(raw.drop(split)); filter.finish()
            assertEquals("split=$split", "Rome is in Italy.", output.toString())
        }
    }
    @Test fun characterCallbacksPreserveProseAndLiteralLessThan() {
        val output = StringBuilder()
        val filter = GemmaSessionText(output::append)
        "Yes, 2 < 3.<eos>".forEach { filter.accept(it.toString()) }
        filter.finish()
        assertEquals("Yes, 2 < 3.", output.toString())
    }
    @Test fun unfinishedThoughtNeverBecomesSpeech() {
        val output = StringBuilder()
        val filter = GemmaSessionText(output::append)
        filter.accept("<|channel>thought\nStill thinking"); filter.finish()
        assertEquals("", output.toString())
    }
}
