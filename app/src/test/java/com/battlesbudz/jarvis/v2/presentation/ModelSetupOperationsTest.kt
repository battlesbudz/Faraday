package com.battlesbudz.jarvis.v2.presentation

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Model switch/delete thread rule (single-engine redesign): the setup
 * commands are invoked synchronously from UI click handlers on the main
 * thread, but closing the resident engine is native lifecycle control,
 * which the engine only allows on a worker thread. Before the fix,
 * tapping Choose or Delete with an engine resident failed with
 * "Could not switch models: Native lifecycle control requires a worker
 * thread" (device-proven on build 1823). The fake session below enforces
 * the engine's exact guard, so these tests fail if the close ever runs
 * on the main thread again.
 */
@RunWith(RobolectricTestRunner::class)
class ModelSetupOperationsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private class GuardedSession : ModelSetupSession {
        @Volatile var closeCalls = 0
        @Volatile var closeThread: Thread? = null
        override fun busy(): Boolean = false
        override fun closeConversation(resetCharacters: Boolean) {
            // LiteRtLmEngine.checkNativeWorkerThread, replicated verbatim:
            // the production session's close reaches it through the engine.
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "Native lifecycle control requires a worker thread"
            }
            closeThread = Thread.currentThread()
            closeCalls++
        }
        override fun record(message: String) {}
    }

    private fun operations(
        session: ModelSetupSession,
        store: ModelStore,
        lifecycleDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = ModelSetupOperations(
        context = context,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        store = store,
        mainHandler = Handler(Looper.getMainLooper()),
        session = session,
        lifecycleDispatcher = lifecycleDispatcher,
    )

    @Test fun selectFromTheMainThreadClosesTheEngineOnTheWorkerThread() {
        val store = ModelStore(context)
        val session = GuardedSession()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "model-lifecycle-worker") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val ops = operations(session, store, dispatcher)
            // The test thread is Robolectric's main thread: exactly the
            // device situation (a Choose tap) that used to fail.
            val error = ops.select(ModelCatalog.gemma4E4b)
            assertNull(error)
            assertEquals(ModelCatalog.gemma4E4b, store.selectedModel())
            assertEquals(1, session.closeCalls)
            assertEquals("model-lifecycle-worker", session.closeThread?.name)
            assertFalse("the model-operation gate must be released", store.isModelOperationActive())
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun deleteFromTheMainThreadClosesTheEngineOnTheWorkerThread() {
        val store = ModelStore(context)
        val spec = ModelCatalog.gemma4E2b
        store.fileFor(spec).writeBytes(byteArrayOf(1, 2, 3)) // presence only; never loaded
        val session = GuardedSession()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "model-lifecycle-worker") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val ops = operations(session, store, dispatcher)
            val error = ops.delete(spec)
            assertNull(error)
            assertFalse("the model file must be removed", store.fileFor(spec).exists())
            assertEquals(1, session.closeCalls)
            assertEquals("model-lifecycle-worker", session.closeThread?.name)
            assertFalse("the model-operation gate must be released", store.isModelOperationActive())
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun selectFromAWorkerThreadClosesDirectlyWithoutDispatch() {
        val store = ModelStore(context)
        val session = GuardedSession()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "model-lifecycle-worker") }
        val dispatcher = executor.asCoroutineDispatcher()
        val caller = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "setup-caller") }
        try {
            val ops = operations(session, store, dispatcher)
            val error = caller.submit<String?> { ops.select(ModelCatalog.gemma4E2b) }
                .get(30, TimeUnit.SECONDS)
            assertNull(error)
            assertEquals(1, session.closeCalls)
            // Already off the main thread: no dispatch, the close ran on
            // the calling worker thread itself.
            assertEquals("setup-caller", session.closeThread?.name)
            assertTrue(store.selectedModel() == ModelCatalog.gemma4E2b)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
            caller.shutdownNow()
        }
    }
}
