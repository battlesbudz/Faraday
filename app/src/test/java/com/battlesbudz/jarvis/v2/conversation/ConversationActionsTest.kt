package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.actions.ActionRequest
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.MobileActionExecutor
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * ConversationActions thread boundaries: admission and UI acknowledgement
 * stay on the main dispatcher; Android execution runs on the execution
 * dispatcher. A blocking executor (launch verification sleeps, screenshot
 * latch, runBlocking inference) must never execute on Main, where it would
 * stall UI/Stop/lifecycle and can ANR.
 */
class ConversationActionsTest {
    @Test fun androidExecutionRunsOffTheMainBoundary() = runBlocking {
        val mainDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-main") }
            .asCoroutineDispatcher()
        val executionDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-exec") }
            .asCoroutineDispatcher()
        try {
            val mainThreads = mutableListOf<String>()
            val execThreads = mutableListOf<String>()
            val actions = ConversationActions(
                executor = { MobileActionExecutor { ExecutionResult(true, "unused") } },
                admit = { _, _ -> mainThreads += Thread.currentThread().name; "group-1" },
                execute = { _, _, _, _ ->
                    execThreads += Thread.currentThread().name
                    ExecutionResult(true, "executed")
                },
                cancelUnfinished = {},
                conversationId = "conversation-1",
                onActionResult = { _, _, _ -> mainThreads += Thread.currentThread().name },
                mainDispatcher = mainDispatcher,
                executionDispatcher = executionDispatcher
            )
            val plan = ActionTurnPlan.Ready(
                listOf(ActionTurnPlan.Step(ActionRequest("read_battery"), "read battery"))
            )
            val benchmark = PipelineBenchmarkCapture("test", "test", 0, PipelineBenchmarkProvenance("test", 1))
            val outcome = actions.runValidated(plan, benchmark)
            assertTrue("the step must complete", outcome.completed)
            // The coroutines debug agent renames the thread while a coroutine
            // runs on it ("test-exec @coroutine#149"): compare the base thread
            // name, not the debug suffix, or this boundary check fails on
            // any classpath with the debug agent enabled.
            val baseName = { name: String -> name.substringBefore(" @coroutine") }
            assertEquals("Android execution must run on the execution dispatcher", listOf("test-exec"), execThreads.map(baseName))
            assertEquals(
                "admission and UI acknowledgement must stay on the main dispatcher",
                listOf("test-main", "test-main"), mainThreads.map(baseName)
            )
        } finally {
            mainDispatcher.close()
            executionDispatcher.close()
        }
    }
}
