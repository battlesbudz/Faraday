package com.battlesbudz.jarvis.v2.sandbox

import com.battlesbudz.jarvis.v2.actions.ActionRequest
import com.battlesbudz.jarvis.v2.actions.ActionRequestText
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.ActionTurnRunner
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.FileToolTaskStore
import com.battlesbudz.jarvis.v2.actions.ActionValidation
import com.battlesbudz.jarvis.v2.actions.describeForOverlay
import com.battlesbudz.jarvis.v2.actions.isDispatchEligible
import com.battlesbudz.jarvis.v2.actions.isRoutineEligible
import com.battlesbudz.jarvis.v2.actions.runNative
import com.battlesbudz.jarvis.v2.actions.MobileAction
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.actions.MobileActionValidator
import com.battlesbudz.jarvis.v2.actions.MobileToolCatalog
import com.battlesbudz.jarvis.v2.actions.NativeActionDecoder
import com.battlesbudz.jarvis.v2.actions.ToolAuthority
import com.battlesbudz.jarvis.v2.actions.ToolTaskLedger
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.voice.AudioToolGuard
import com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sandbox Phase 2: the run_in_sandbox tool binding. Pins registration and
 * visibility in the voice + chat tool sets, the approval gate (a proposal
 * never executes without Battles's approval; without a handler nothing
 * dispatches at all), the eligibility ladder (never routine, dispatch only
 * under an exact approval), result shaping, and the agent counters the
 * diagnostics block reports.
 */
class SandboxPhase2ToolTest {

    private val command = "echo phase2-ok"

    private fun markerPlan() = ActionTurnPlan.Ready(listOf(
        ActionTurnPlan.Step(ActionRequest("run_in_sandbox", mapOf("command" to "")),
            "run echo phase2-ok in the sandbox")))

    // -- Registration / visibility -----------------------------------------

    @Test fun toolIsRegisteredAndDeclaredForVoiceAndChat() {
        val entry = MobileToolCatalog.find("run_in_sandbox")
        assertTrue("run_in_sandbox must be a model-visible native tool", entry != null)
        val schema = MobileActionToolDefinitions.all()
            .map { JSONObject(it.getToolDescriptionJsonString()) }
            .firstOrNull { it.getString("name") == "run_in_sandbox" }
        assertTrue("the declaration source for both voice and chat must carry run_in_sandbox",
            schema != null)
        val parameters = schema!!.getJSONObject("parameters")
        assertEquals(listOf("command"),
            (0 until parameters.getJSONArray("required").length())
                .map { parameters.getJSONArray("required").getString(it) })
        assertEquals(1, parameters.getJSONObject("properties")
            .getJSONObject("command").getInt("minLength"))
    }

    @Test fun strictDecodeAcceptsExactCommandOnly() {
        fun strict(json: String) =
            NativeActionDecoder.decodeStrict(ToolCall("run_in_sandbox", json))
        assertEquals(ActionRequest("run_in_sandbox", mapOf("command" to command)),
            strict("{\"command\":\"$command\"}"))
        // Extra keys and non-string commands never decode. (Strict decode
        // trims string values; a whitespace-only command decodes to empty
        // and is rejected by the validator before anything can dispatch —
        // the same layering as the other free-text tools.)
        assertNull(strict("{\"command\":\"ls\",\"shell\":\"sh\"}"))
        assertNull(strict("{\"command\":42}"))
        assertNull(strict("{}"))
        val blankDecoded = strict("{\"command\":\"   \"}")
        assertEquals("", blankDecoded?.arguments?.get("command"))
        assertTrue(MobileActionValidator().validate(blankDecoded!!)
            is ActionValidation.Rejected)
    }

    // -- Validator ----------------------------------------------------------

    @Test fun validatorKeepsTheCommandVerbatimAndCapsItsLength() {
        val validator = MobileActionValidator()
        val spaced = "  echo hi  "
        val valid = validator.validate(
            ActionRequest("run_in_sandbox", mapOf("command" to spaced)))
        assertTrue(valid is ActionValidation.Valid)
        assertEquals(spaced,
            ((valid as ActionValidation.Valid).action as MobileAction.RunInSandbox).command)

        val tooLong = validator.validate(ActionRequest(
            "run_in_sandbox", mapOf("command" to "x".repeat(8_001))))
        assertTrue(tooLong is ActionValidation.Rejected)
        val nul = validator.validate(ActionRequest(
            "run_in_sandbox", mapOf("command" to "echo a\u0000b")))
        assertTrue(nul is ActionValidation.Rejected)
        val blank = validator.validate(ActionRequest(
            "run_in_sandbox", mapOf("command" to "   ")))
        assertTrue(blank is ActionValidation.Rejected)
    }

    // -- Approval gate ------------------------------------------------------

    @Test fun validateBatchParksSandboxProposals() {
        val plan = ActionTurnPlan.parse("Read battery") as ActionTurnPlan.Ready
        val batch = ActionTurnRunner(
            com.battlesbudz.jarvis.v2.actions.MobileActionExecutor {
                error("proposals must not reach the executor") })
            .validateBatch(plan, emptyList(),
                listOf(ToolCall("run_in_sandbox", "{\"command\":\"$command\"}")))
        assertTrue(batch is ActionTurnRunner.Batch.NeedsApproval)
        batch as ActionTurnRunner.Batch.NeedsApproval
        assertEquals(listOf(ActionRequest("run_in_sandbox", mapOf("command" to command))),
            batch.proposed)
        assertNull(batch.accepted)
    }

    @Test fun runNativeParksTheProposalAndNeverDispatchesIt() = runBlocking {
        val parked = mutableListOf<ActionRequest>()
        var dispatched = false
        val outcome = ActionTurnRunner(
            com.battlesbudz.jarvis.v2.actions.MobileActionExecutor {
                error("proposals must not reach the executor") })
            .runNative(
                markerPlan(),
                listOf(ToolCall("run_in_sandbox", "{\"command\":\"$command\"}")),
                dispatch = { dispatched = true; ExecutionResult(true, "must not dispatch") },
                nextCalls = { emptyList() },
                onNeedsApproval = { request ->
                    parked += request
                    ExecutionResult(false, "I need your approval before I run that command.")
                })
        assertFalse("a proposed command must never dispatch in the proposing turn", dispatched)
        assertEquals(listOf(ActionRequest("run_in_sandbox", mapOf("command" to command))), parked)
        assertTrue(outcome.stopped)
        assertFalse(outcome.completed)
        assertTrue(outcome.message.contains("approval"))
    }

    @Test fun runNativeWithoutApprovalHandlerStopsAndDispatchesNothing() = runBlocking {
        var dispatched = false
        val outcome = ActionTurnRunner(
            com.battlesbudz.jarvis.v2.actions.MobileActionExecutor {
                error("nothing may dispatch") })
            .runNative(
                markerPlan(),
                listOf(ToolCall("run_in_sandbox", "{\"command\":\"$command\"}")),
                dispatch = { dispatched = true; ExecutionResult(true, "must not dispatch") },
                nextCalls = { emptyList() })
        assertFalse(dispatched)
        assertTrue(outcome.stopped)
        assertFalse(outcome.completed)
    }

    // -- Eligibility ladder ---------------------------------------------------

    @Test fun sandboxIsNeverRoutineAndDispatchesOnlyUnderExactApproval() {
        val request = ActionRequest("run_in_sandbox", mapOf("command" to command))
        assertFalse(request.isRoutineEligible())
        assertFalse(request.isDispatchEligible(ToolAuthority.USER_REQUEST))
        assertFalse(request.isDispatchEligible(ToolAuthority.ROUTINE))
        assertFalse(request.isDispatchEligible(ToolAuthority.EXTERNAL_AGENT))
        assertTrue(request.isDispatchEligible(ToolAuthority.EXACT_APPROVAL))
        assertEquals("Run in the sandbox: $command", request.describeForOverlay())
    }

    @Test fun ledgerAdmitsForApprovalParkingButClaimWithoutApprovalFails() {
        // The journal store owns its file: hand it a path that does not
        // exist yet (a pre-created empty file is not a valid journal).
        val dir = java.nio.file.Files.createTempDirectory("sandbox-ledger").toFile()
            .apply { deleteOnExit() }
        val file = File(dir, "journal.json")
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val request = ActionRequest("run_in_sandbox", mapOf("command" to command))
        val group = ledger.admit(listOf(request), "conversation")
        val attempt = ledger.get(group.attemptIds.single())!!
        ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        // No approval recorded: the claim must refuse, so nothing can run.
        val parked = ledger.get(attempt.id)!!
        assertNull(ledger.claim(parked.id, parked.generation))
    }

    // -- Voice guards ---------------------------------------------------------

    @Test fun audioGuardAdmitsOnlySaneCommands() {
        assertTrue(AudioToolGuard.allows(
            ActionRequest("run_in_sandbox", mapOf("command" to command))))
        assertFalse(AudioToolGuard.allows(
            ActionRequest("run_in_sandbox", mapOf("command" to ""))))
        assertFalse(AudioToolGuard.allows(ActionRequest(
            "run_in_sandbox", mapOf("command" to "x".repeat(8_001)))))
        // A non-sandbox tool stays rejected by the guard's allowlist rules.
        assertFalse(AudioToolGuard.allows(ActionRequest("definitely_not_a_tool")))
    }

    @Test fun finalVoiceGuardAdmitsOnlyTheEmptyCommandMarker() {
        val text = "run echo phase2-ok in the sandbox"
        assertTrue(FinalVoiceToolGuard.allows(text, "run_in_sandbox", mapOf("command" to "")))
        assertFalse("a real command in a plan step must never pass the final guard",
            FinalVoiceToolGuard.allows(text, "run_in_sandbox", mapOf("command" to command)))
    }

    @Test fun intentDetectorArmsOnlyExplicitSandboxRuns() {
        assertTrue(ActionRequestText.sandboxRunRequest("run ls in the sandbox"))
        assertTrue(ActionRequestText.sandboxRunRequest("Use the sandbox to add 2 and 3"))
        assertFalse(ActionRequestText.sandboxRunRequest("what is the sandbox?"))
        assertFalse(ActionRequestText.sandboxRunRequest("is the sandbox installed?"))
        assertFalse(ActionRequestText.sandboxRunRequest("don't run anything in the sandbox"))
        assertFalse(ActionRequestText.sandboxRunRequest("run a mile for me"))
    }

    // -- Receipt shaping ------------------------------------------------------

    private fun runResult(output: String = "", exitCode: Int? = 0, timedOut: Boolean = false,
        truncated: Boolean = false, durationMs: Long = 12, error: String? = null) =
        SandboxRunner.RunResult(output, exitCode, timedOut, truncated, durationMs, error)

    @Test fun receiptReportsExitCodeAndOutput() {
        val ok = SandboxRunReceipt.format(runResult(output = "phase2-ok\n"))
        assertTrue(ok.succeeded)
        assertTrue(ok.message.contains("exit code 0"))
        assertTrue(ok.message.contains("phase2-ok"))
        assertFalse(ok.message.contains("truncated"))

        val failed = SandboxRunReceipt.format(runResult(output = "boom", exitCode = 3))
        assertFalse(failed.succeeded)
        assertTrue(failed.message.contains("code 3"))
        assertTrue(failed.message.contains("boom"))
    }

    @Test fun receiptMarksTruncationTimeoutAndStartErrorsHonestly() {
        val truncated = SandboxRunReceipt.format(
            runResult(output = "x".repeat(8_000), truncated = true))
        assertTrue(truncated.message.contains("(output truncated)"))

        val timeout = SandboxRunReceipt.format(
            runResult(output = "partial", timedOut = true, exitCode = null,
                durationMs = 120_000))
        assertFalse(timeout.succeeded)
        assertTrue(timeout.message.contains("timed out"))
        assertTrue(timeout.message.contains("partial"))

        val notInstalled = SandboxRunReceipt.format(runResult(
            exitCode = null, error = "Sandbox is not installed. Install it from the sandbox settings first."))
        assertFalse(notInstalled.succeeded)
        assertEquals("Sandbox is not installed. Install it from the sandbox settings first.",
            notInstalled.message)
    }

    // -- Agent counters ---------------------------------------------------------

    @Test fun agentCountersCountAndPersistAcrossInstances() {
        val file = File.createTempFile("sandbox-counters", ".txt").apply { deleteOnExit() }
        val counters = SandboxAgentCounters(file)
        counters.recordProposal()
        counters.recordProposal()
        counters.recordDecision(approved = true)
        counters.recordDecision(approved = false)
        counters.recordRun(exitCode = 0, timedOut = false, failed = false)
        counters.recordRun(exitCode = null, timedOut = true, failed = true)
        val snapshot = SandboxAgentCounters(file).snapshot()
        assertEquals(2, snapshot.proposed)
        assertEquals(1, snapshot.approved)
        assertEquals(1, snapshot.denied)
        assertEquals(2, snapshot.runs)
        assertEquals(1, snapshot.failed)
        assertEquals(1, snapshot.timedOut)
        assertNull(snapshot.lastExitCode)
    }
}
