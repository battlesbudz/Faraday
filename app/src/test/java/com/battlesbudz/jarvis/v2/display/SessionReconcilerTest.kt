package com.battlesbudz.jarvis.v2.display

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionReconcilerTest {
    @Test fun launchSweepsInCharterOrderAndRecordsEvenAnEmptyResult() {
        val calls = mutableListOf<String>()
        var written: SessionReconciliation? = null
        val ports = object : SessionRecoveryPorts {
            override fun releasePreviousProcessDisplays(): SweepResult { calls += "display"; return SweepResult(0, 0) }
            override fun clearLocksWithoutRunner(): SweepResult { calls += "lock"; return SweepResult(0, 0) }
            override fun closeRunnerRecordsWithoutTaskEnded(): SweepResult { calls += "runner"; return SweepResult(0, 0) }
            override fun writeLaunchDiagnostic(result: SessionReconciliation) { calls += "diagnostic"; written = result }
        }
        val result = SessionReconciler(ports).reconcileBeforeTaskAdmission()
        assertEquals(listOf("display", "lock", "runner", "diagnostic"), calls)
        assertEquals(result, written)
        assertEquals(SweepResult(0, 0), result.displays)
    }
}
