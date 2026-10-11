package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class EarlyGemmaPreparationGateTest {
    @Test fun encoderCannotStartUntilPreparationCompletes() {
        val gate = EarlyGemmaPreparationGate { true }
        val entered = CountDownLatch(1); val computed = AtomicBoolean(false)
        val worker = thread { entered.countDown(); gate.awaitReady(); computed.set(true) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertFalse(computed.get()); assertFalse(gate.ready)
        gate.complete(); worker.join(2000)
        assertFalse(worker.isAlive); assertTrue(computed.get())
    }
    @Test fun cancellationWakesWorkerAndLateCompletionCannotReviveIt() {
        val gate = EarlyGemmaPreparationGate { true }
        val error = AtomicReference<Throwable?>()
        val worker = thread { try { gate.awaitReady() } catch (failure: Throwable) { error.set(failure) } }
        gate.cancel(); worker.join(2000)
        assertFalse(worker.isAlive); assertNotNull(error.get())
        gate.complete(); assertFalse(gate.ready)
    }
    @Test fun replacedOwnerCannotUseCompletedPreparation() {
        val current = AtomicBoolean(true)
        val gate = EarlyGemmaPreparationGate(current::get)
        gate.complete(); current.set(false)
        assertFalse(gate.ready); assertTrue(runCatching { gate.awaitReady() }.isFailure)
    }
}
