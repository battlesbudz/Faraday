package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import androidx.work.ListenableWorker
import com.battlesbudz.jarvis.v2.ai.FakeModelEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The dream pass on the shared engine slot (Battles's rule, 2026-11-10):
 * with no engine resident it loads the judge model into the slot, runs,
 * and unloads, leaving nothing behind; with an engine resident — or a
 * voice session live / wake-armed — it skips the night and never touches
 * the slot. The fake slot stands in for the runtime's engine so the rule
 * is pinned without a native engine, which cannot exist on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryDreamSharedSlotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private class FakeSlot(
        var loaded: Boolean = false,
        var active: Boolean = false,
    ) : DreamEngineSlot {
        var loadCalls = 0
        var unloadCalls = 0
        private val judgeEngine = FakeModelEngine("dream-judge")
        override fun engineLoaded(): Boolean = loaded
        override fun sessionActive(): Boolean = active
        override suspend fun loadJudgeEngine(spec: LocalModelSpec, store: ModelStore): LocalModelEngine {
            loadCalls++
            loaded = true
            return judgeEngine
        }
        override suspend fun unloadJudgeEngine() {
            unloadCalls++
            loaded = false
        }
    }

    private fun withSlot(slot: FakeSlot, block: () -> Unit) {
        AndroidMemoryDream.engineSlotOverride = slot
        try { block() } finally { AndroidMemoryDream.engineSlotOverride = null }
    }

    private fun dummyModelPresent() {
        val store = ModelStore(context)
        store.fileFor(ModelCatalog.gemma4E2b).writeBytes(byteArrayOf(1, 2, 3)) // presence only; never loaded
    }

    @Test fun residentEngineSkipsTheNightAndIsNeverTouched() {
        dummyModelPresent()
        val slot = FakeSlot(loaded = true)
        withSlot(slot) {
            val result = runBlocking { AndroidMemoryDream.runScheduledPass(context) }
            assertTrue(result is ListenableWorker.Result.Retry)
            val entry = AndroidMemoryDream.journalState(context).entries.single { it.note == "engine_resident" }
            assertEquals(DreamRunOutcome.SKIPPED_CONTENTION, entry.outcome)
            assertEquals(0, slot.loadCalls)
            assertEquals(0, slot.unloadCalls)
            assertTrue("the resident engine must be left exactly as it was", slot.loaded)
        }
    }

    @Test fun wakeArmedSessionSkipsTheNightWithoutLoading() {
        dummyModelPresent()
        val slot = FakeSlot(loaded = false, active = true)
        withSlot(slot) {
            val result = runBlocking { AndroidMemoryDream.runScheduledPass(context) }
            assertTrue(result is ListenableWorker.Result.Retry)
            val entry = AndroidMemoryDream.journalState(context).entries.single { it.note == "voice_session_active" }
            assertEquals(DreamRunOutcome.SKIPPED_CONTENTION, entry.outcome)
            assertEquals(0, slot.loadCalls)
            assertEquals(0, slot.unloadCalls)
        }
    }

    @Test fun emptySlotLoadsTheJudgeRunsThePassAndLeavesNothingResident() {
        dummyModelPresent()
        // The full pass reaches AndroidMemoryOs, whose first-touch wiring
        // enqueues periodic work; Robolectric never runs the manifest's
        // WorkManager initializer, so initialize it the manual way the
        // WorkManager error message names.
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        val slot = FakeSlot()
        withSlot(slot) {
            val result = runBlocking { AndroidMemoryDream.runScheduledPass(context) }
            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(1, slot.loadCalls)
            assertEquals(1, slot.unloadCalls)
            assertFalse("the slot must be empty again after the pass", slot.loaded)
        }
    }
}
