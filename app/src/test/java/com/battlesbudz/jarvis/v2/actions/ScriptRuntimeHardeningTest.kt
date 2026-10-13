package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Script runtime hardening (slice 3, item G): bounded/cancellable parsing,
 * cancellation on the alarm execution path, and while-loop return
 * propagation.
 *
 * Deterministic; no network, no Android.
 */
class ScriptRuntimeHardeningTest {

    private fun uid() = UUID.randomUUID().toString()

    private fun assertParseFailure(source: String, reason: String): ScriptResult.Failed {
        val result = runScript(source, ScriptHost.empty())
        assertTrue("expected Failed, got $result", result is ScriptResult.Failed)
        result as ScriptResult.Failed
        assertTrue("reason: ${result.reason}", result.reason.startsWith("parse error:"))
        assertTrue("reason: ${result.reason}", result.reason.contains(reason))
        return result
    }

    // -- Bounded parsing --------

    @Test fun deeplyNestedBlocksAbortParsingInsteadOfOverflowing() {
        // Past MAX_BLOCK_DEPTH nested if-blocks: the parser must report a
        // typed failure, not exhaust the stack. MAX_EXPR_DEPTH alone does
        // not cover statement blocks (parseStmt/parseBlock recursion).
        val source = "if true { ".repeat(MAX_BLOCK_DEPTH + 50) + "1;" +
            " }".repeat(MAX_BLOCK_DEPTH + 50)
        val r = runScript(source, ScriptHost.empty())
        assertTrue("expected Failed, got $r", r is ScriptResult.Failed)
        r as ScriptResult.Failed
        assertTrue("reason: ${r.reason}", r.reason.contains("too deeply nested"))
    }

    @Test fun parseStatementBudgetBoundsFlatPrograms() {
        // A flat program past MAX_PARSE_STMTS aborts during parsing with a
        // typed failure instead of parsing unboundedly.
        val r = runScript("1;".repeat(MAX_PARSE_STMTS + 1), ScriptHost.empty())
        assertTrue("expected Failed, got $r", r is ScriptResult.Failed)
        r as ScriptResult.Failed
        assertTrue("reason: ${r.reason}", r.reason.contains("too many statements"))
    }

    @Test fun parseCancellationAbortsMidParse() {
        // Cancellation is re-checked while statements are parsed, not only
        // before parsing: a long program aborts as Cancelled once the flag
        // trips, before the statement budget is even reached.
        var calls = 0
        val r = runScript(
            "1;".repeat(MAX_PARSE_STMTS * 2),
            ScriptHost.empty(),
            isCancelled = { ++calls > 1_000 }
        )
        assertEquals(ScriptResult.Cancelled, r)
    }

    @Test fun workflowSizedPrefixAttackIsTypedParseFailure() {
        val source = "return " + "-".repeat(8_000) + "1;"
        assertEquals(8_009, source.length)
        assertTrue(source.length <= 8_192)
        assertParseFailure(source, "too many prefix operators")
    }

    @Test fun workflowSizedBinaryAttackIsTypedParseFailure() {
        val source = "return " + "1+".repeat(4_000) + "1;"
        assertEquals(8_009, source.length)
        assertTrue(source.length <= 8_192)
        assertParseFailure(source, "too deeply nested")
    }

    @Test fun prefixRunAtLimitRemainsIterativeAndCountsEveryOperation() {
        val result = runScript("return " + "-".repeat(MAX_UNARY_OPS) + "1;", ScriptHost.empty())
        assertTrue("expected Success, got $result", result is ScriptResult.Success)
        result as ScriptResult.Success
        assertEquals(ScriptValue.Num(1.0), result.value)
        assertEquals(MAX_UNARY_OPS + 2L, result.opsUsed)
        assertParseFailure("return " + "-".repeat(MAX_UNARY_OPS + 1) + "1;", "prefix operators")
    }

    @Test fun binaryTreeDepthLimitIsInclusive() {
        val result = runScript(
            "return " + "1+".repeat(MAX_EVAL_DEPTH - 1) + "1;", ScriptHost.empty())
        assertTrue("expected Success, got $result", result is ScriptResult.Success)
        result as ScriptResult.Success
        assertEquals(ScriptValue.Num(MAX_EVAL_DEPTH.toDouble()), result.value)
        assertEquals(MAX_EVAL_DEPTH * 2L, result.opsUsed)
        assertParseFailure("return " + "1+".repeat(MAX_EVAL_DEPTH) + "1;", "evaluation levels")
    }

    @Test fun mixedUnaryCallAndBinaryDepthIsBoundedTogether() {
        val host = ScriptHost(mapOf("id" to ScriptHostFunction("id", 1..1) { args, _ -> args[0] }))
        // Binary depth + the call + the iterative prefix node must share one limit.
        val allowed = "return -id(" + "1+".repeat(MAX_EVAL_DEPTH - 3) + "1);"
        val result = runScript(allowed, host)
        assertTrue("expected Success, got $result", result is ScriptResult.Success)
        assertEquals(ScriptValue.Num(-(MAX_EVAL_DEPTH - 2).toDouble()),
            (result as ScriptResult.Success).value)
        assertParseFailure("return -id(" + "1+".repeat(MAX_EVAL_DEPTH - 2) + "1);",
            "evaluation levels")
    }

    @Test fun excessiveDepthRejectsWholeProgramBeforeAnyHostEffect() {
        var calls = 0
        val host = ScriptHost(mapOf("effect" to ScriptHostFunction("effect", 0..0) { _, _ ->
            calls++
            ScriptValue.Num(1.0)
        }))
        for (expression in listOf("-".repeat(8_000) + "1", "1+".repeat(4_000) + "1")) {
            val result = runScript("effect(); return $expression;", host)
            assertTrue("expected Failed, got $result", result is ScriptResult.Failed)
            assertTrue((result as ScriptResult.Failed).reason.startsWith("parse error:"))
        }
        assertEquals(0, calls)
    }

    @Test fun prefixRunStillChecksBudgetAndCancellationBeforeItsHostOperand() {
        var hostCalls = 0
        val host = ScriptHost(mapOf("effect" to ScriptHostFunction("effect", 0..0) { _, _ ->
            hostCalls++
            ScriptValue.Num(1.0)
        }))
        val source = "return " + "-".repeat(3_000) + "effect();"
        val budgetResult = runScript(source, host, ScriptLimits(maxOps = 100))
        assertTrue("expected Killed, got $budgetResult", budgetResult is ScriptResult.Killed)
        var cancelChecks = 0
        assertEquals(ScriptResult.Cancelled, runScript(source, host,
            isCancelled = { ++cancelChecks > 500 }))
        assertEquals(0, hostCalls)
    }

    @Test fun prefixRunPreservesOperatorOrderAndDeclaredHostAllowlist() {
        val host = ScriptHost(mapOf("one" to ScriptHostFunction("one", 0..0) { _, _ ->
            ScriptValue.Num(1.0)
        }))
        assertEquals(ScriptExecution.Succeeded("-1"), WorkflowStep.Script(
            uid(), "return " + "-".repeat(3_001) + "one();", listOf("one")
        ).runWithInterpreter(host))
        val denied = WorkflowStep.Script(uid(), "return -one();", emptyList()).runWithInterpreter(host)
        assertTrue("expected denied failure, got $denied", denied is ScriptExecution.Failed)
        assertTrue((denied as ScriptExecution.Failed).reason.startsWith("denied:"))
        val mixed = runScript("return !-true;", ScriptHost.empty())
        assertTrue(mixed is ScriptResult.Failed)
        assertTrue((mixed as ScriptResult.Failed).reason.contains("unary '-' needs a number"))
    }

    @Test fun boundedExpressionsPreserveLogicalShortCircuiting() {
        val result = runScript("return (false && forbidden()) || true;", ScriptHost.empty())
        assertTrue("expected Success, got $result", result is ScriptResult.Success)
        assertEquals(ScriptValue.Bool(true), (result as ScriptResult.Success).value)
    }

    @Test fun missingValueAtEndOfInputIsTypedParseFailure() {
        for (source in listOf("return", "let a =", "return -", "return (", "return f(",
            "return f(1,", "return 1+", "if true { return")) {
            val result = assertParseFailure(source, "expected a value")
            assertTrue("source: $source, reason: ${result.reason}",
                result.reason.endsWith("offset ${source.length}"))
            val execution = WorkflowStep.Script(uid(), source, emptyList())
                .runWithInterpreter(ScriptHost.empty())
            assertEquals(ScriptExecution.Failed(result.reason), execution)
        }
    }

    // -- While-loop return propagation --------

    @Test fun whileReturnPropagatesImmediately() {
        // A function-free `while true { return 1; }` must return 1
        // promptly — not keep looping until the operation budget is
        // exhausted.
        val r = runScript("while (true) { return 1; }", ScriptHost.empty())
        assertTrue("expected Success, got $r", r is ScriptResult.Success)
        r as ScriptResult.Success
        assertEquals(ScriptValue.Num(1.0), r.value)
        assertTrue("return must not run to the budget: ${r.opsUsed} ops", r.opsUsed < 100)
    }

    @Test fun whileReturnInsideNestedIfPropagates() {
        val r = runScript(
            "let i = 0; while (true) { if (i > 2) { return 42; } i = i + 1; }",
            ScriptHost.empty()
        )
        assertTrue("expected Success, got $r", r is ScriptResult.Success)
        r as ScriptResult.Success
        assertEquals(ScriptValue.Num(42.0), r.value)
    }

    // -- Alarm execution path --------

    @Test fun alarmPathCancellationReachesTheInterpreter() {
        // The coordinator's alarm path used to wire isCancelled = { false },
        // making alarm-run scripts uninterruptible; it now passes the
        // coordinator scope's cancellation. This pins the receiving
        // contract: the exact runWithInterpreter call shape the alarm path
        // uses must honor a mid-run cancellation.
        val step = WorkflowStep.Script(
            uid(), "let i = 0; while (i < 1000000) { i = i + 1; }", emptyList())
        var calls = 0
        val execution = step.runWithInterpreter(
            ScriptHost.empty(),
            ScriptLimits(maxOps = 10_000_000L),
            isCancelled = { ++calls > 500 }
        )
        assertEquals(ScriptExecution.Cancelled, execution)
    }
}
