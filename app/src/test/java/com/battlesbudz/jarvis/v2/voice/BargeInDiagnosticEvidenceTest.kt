package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class BargeInDiagnosticEvidenceTest {
    @Test fun echoAndCorrectionEvidenceComesFromTheActualGate() {
        val gate = BargeInGate()
        val evidence = BargeInDiagnosticEvidence()
        val reference = "The garden gate is open"
        gate.update(true, true, 0, reference, reference)
        evidence.record("1", 1, 10, gate.reason, reference, reference, gate, true)
        assertTrue(evidence.entries().single().contains("echoMatchedWords=5"))
        assertTrue(evidence.entries().single().contains("reason=no_new_speech_in_mixed_transcript"))
        gate.update(true, true, 100, "$reference. Actually open settings", reference)
        assertEquals("actually open settings", gate.selectedRequest)
        assertTrue(gate.examinedFragments.any { it.startsWith("near_echo:") })
        assertEquals(1, gate.examinedFragments.count { it.startsWith("speech:") })
        assertEquals(BargeInGate.Action.CONFIRM,
            gate.update(true, true, 400, "$reference. Actually open settings", reference))
        evidence.record("2", 2, 20, gate.reason, "$reference. Actually open settings", reference, gate, true)
        assertTrue(evidence.entries().last().contains("selected=\"actually open settings\""))
    }

    @Test fun firstTwoAndLatestFourSurviveWhileOneResultCanUpdateItsDecision() {
        val evidence = BargeInDiagnosticEvidence()
        val gate = BargeInGate()
        repeat(20) { evidence.record("$it", it.toLong(), 0, "no_words", "text-$it", "", gate, false) }
        evidence.record("19", 19, 300, "confirmed_new_request", "text-19", "", gate, true)
        val entries = evidence.entries()
        assertEquals(6, entries.size)
        listOf(0, 1, 16, 17, 18, 19).forEachIndexed { index, revision ->
            assertTrue(entries[index].contains("revision=$revision "))
        }
        assertTrue(entries.last().contains("reason=confirmed_new_request"))
        assertTrue(entries.first().contains("omitted=14"))
    }

    @Test fun excerptsCannotForgeNewLogLinesOrExceedRetainedRecordLimit() {
        val evidence = BargeInDiagnosticEvidence()
        evidence.record("1", 1, 0, "no_words", "\"\\\n\r\u2028".repeat(1000),
            "r".repeat(2000), BargeInGate(), false)
        val line = evidence.entries().single()
        assertTrue(line.length < 1500)
        assertFalse(line.contains('\n')); assertFalse(line.contains('\r')); assertFalse(line.contains('\u2028'))
        assertTrue(line.contains("referenceTailOnly=true"))
        assertTrue(line.contains("…")); assertTrue(line.contains("evaluated=false"))
        assertTrue(line.contains("echoMatchedWords=-1"))
    }
}
