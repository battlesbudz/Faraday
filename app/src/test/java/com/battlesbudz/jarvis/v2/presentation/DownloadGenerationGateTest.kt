package com.battlesbudz.jarvis.v2.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadGenerationGateTest {
    @Test fun onlyTheNewestGenerationIsCurrent() {
        val gate = DownloadGenerationGate()
        val first = gate.begin()
        assertTrue(gate.isCurrent(first))
        val second = gate.begin()
        assertFalse("a superseded invocation must go silent", gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test fun generationsIncreaseMonotonically() {
        val gate = DownloadGenerationGate()
        val generations = List(5) { gate.begin() }
        assertTrue(generations.zipWithNext().all { (a, b) -> b > a })
        assertTrue(gate.isCurrent(generations.last()))
    }
}
