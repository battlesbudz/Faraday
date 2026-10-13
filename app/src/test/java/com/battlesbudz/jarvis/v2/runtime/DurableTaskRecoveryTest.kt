package com.battlesbudz.jarvis.v2.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DurableTaskRecoveryTest {
    @Test fun coldAlarmCannotEnterUntilBothRecoveryPassesFinish() {
        val gate = DurableTaskRecovery()
        val phoneDone = CountDownLatch(1)
        val allowWorkflow = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        var effects = 0
        try {
            val recovery = worker.submit<Boolean> { gate.recover({ phoneDone.countDown(); true }, {
                check(allowWorkflow.await(5, TimeUnit.SECONDS)); true
            }) }
            assertTrue(phoneDone.await(5, TimeUnit.SECONDS))
            assertFalse(gate.runWhenReady { effects++ })
            assertEquals(0, effects)
            allowWorkflow.countDown()
            assertTrue(recovery.get(5, TimeUnit.SECONDS))
            assertTrue(gate.runWhenReady { effects++ })
            assertEquals(1, effects)
        } finally { allowWorkflow.countDown(); worker.shutdownNow() }
    }

    @Test fun systemChangesNeverRunRecoveryOverALiveOwnerAgain() {
        val gate = DurableTaskRecovery()
        var recoveryCalls = 0
        assertTrue(gate.recover({ recoveryCalls++; true }, { recoveryCalls++; true }))
        assertTrue(gate.runWhenReady {
            assertTrue(gate.recover({ error("live phone work must not recover") }, { error("live workflow must not recover") }))
        })
        assertEquals(2, recoveryCalls)
    }

    @Test fun failedRecoveryNeverOpensTheAlarmGateOrClaimsCompletion() {
        for (phoneWorks in listOf(false, true)) {
            val gate = DurableTaskRecovery()
            assertFalse(gate.recover({ phoneWorks }, { !phoneWorks }))
            assertFalse(gate.ready)
            assertFalse(gate.runWhenReady { error("must not dispatch") })
            assertFalse(gate.recover({ true }, { true }))
        }
    }
}
