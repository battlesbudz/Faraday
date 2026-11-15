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
        // App storage persists across methods in this class; make sure the
        // contention test's dummy model file cannot leak into this test.
        val store = ModelStore(context)
        store.fileFor(ModelCatalog.gemma4E2b).delete()
        store.fileFor(ModelCatalog.gemma4E4b).delete()
        val result = runBlocking { AndroidMemoryDream.runScheduledPass(context) }
        assertTrue(result is ListenableWorker.Result.Success)
        // Select by outcome: the journal file is app storage and Robolectric
        // does not reset it between methods of this class.
        val entry = AndroidMemoryDream.journalState(context).entries.single { it.outcome == DreamRunOutcome.SKIPPED_NO_MODEL }
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
            // Filter by the note as well as the outcome: the journal is
            // shared app storage, and other dream tests journal their own
            // contention skips (engine_resident / voice_session_active)
            // into the same file when they share a Robolectric sandbox.
            val entry = AndroidMemoryDream.journalState(context).entries.single {
                it.outcome == DreamRunOutcome.SKIPPED_CONTENTION && it.note == "model_busy"
            }
            assertEquals("model_busy", entry.note)
            assertTrue(entry.outputs.isEmpty())
        } finally {
            store.endModelOperation()
        }
    }
}
