package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import androidx.work.ListenableWorker
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MemoryDreamWorkerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun workerShapePinsUniqueNamePeriodFlexAndConstraints() {
        assertEquals("memory-dream-synthesis", MemoryDreamWorker.UNIQUE_WORK_NAME)
        val constraints = MemoryDreamWorker.requiredConstraints()
        assertTrue(constraints.requiresCharging())
        assertTrue(constraints.requiresDeviceIdle())
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresStorageNotLow())

        val spec = MemoryDreamWorker.buildRequest().workSpec
        assertEquals(TimeUnit.HOURS.toMillis(24), spec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(6), spec.flexDuration)
        assertTrue(spec.constraints.requiresCharging())
        assertTrue(spec.constraints.requiresDeviceIdle())
        assertTrue(spec.constraints.requiresBatteryNotLow())
        assertTrue(spec.constraints.requiresStorageNotLow())
    }

    @Test fun noModelInstalledJournalsSkippedNoModel() {
        val result = runBlocking { AndroidMemoryDream.runScheduledPass(context) }
        assertTrue(result is ListenableWorker.Result.Success)
        val entry = AndroidMemoryDream.journalState(context).entries.single()
        assertEquals(DreamRunOutcome.SKIPPED_NO_MODEL, entry.outcome)
        assertEquals("no_model_installed", entry.note)
    }

    @Test fun contentionJournalsSkippedContentionAndRetriesWithoutRunningThePass() {
        val store = ModelStore(context)
        val spec = ModelCatalog.gemma4E2b
        store.fileFor(spec).writeBytes(byteArrayOf(1, 2, 3)) // presence only; never loaded
        assertTrue(store.tryBeginModelOperation()) // hold the model gate, as a conversation would
        try {
            val result = runBlocking { AndroidMemoryDream.runScheduledPass(context) }
            assertTrue(result is ListenableWorker.Result.Retry)
            val entry = AndroidMemoryDream.journalState(context).entries.single()
            assertEquals(DreamRunOutcome.SKIPPED_CONTENTION, entry.outcome)
            assertEquals("model_busy", entry.note)
            assertTrue(entry.outputs.isEmpty())
        } finally {
            store.endModelOperation()
        }
    }
}
