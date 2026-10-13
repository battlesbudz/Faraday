package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Branch condition export privacy (slice 3, item H): condition literals and
 * regexes are personal content, so export redacts them into resolvable
 * setup bindings — the established Tool/Adaptive argument pattern (shared
 * redaction, distinct bindings per distinct value) — the preview shows the
 * complete condition content that will be shared, and export→import
 * round-trips preserve behavior and validation.
 *
 * Deterministic; no network, no Android.
 */
class BranchConditionExportPrivacyTest {

    private val nowMs = 1_700_000_000_000L
    private fun uid() = UUID.randomUUID().toString()

    private val secretLiteral = "s3cr3t-phr@se-hunter2"
    private val secretRegex = ".*hunter2-internal.*"

    private fun batteryStep(id: String = uid()) =
        WorkflowStep.Tool(id, ActionRequest("read_battery"))

    private fun volumeStep(level: String, id: String = uid()) =
        WorkflowStep.Tool(id, ActionRequest("set_volume", mapOf("level" to level)))

    /** Two branches over the battery message: Equals on a secret literal, Matches on a secret regex. */
    private fun branchDefinition(): WorkflowDefinition {
        val batteryId = uid()
        return WorkflowDefinition(uid(), "Condition privacy", "A test routine.",
            listOf(
                batteryStep(batteryId),
                WorkflowStep.Branch(uid(),
                    WorkflowCondition.Equals(WorkflowBinding(batteryId, "message"), secretLiteral),
                    thenSteps = listOf(volumeStep("20")),
                    elseSteps = listOf(volumeStep("80"))),
                WorkflowStep.Branch(uid(),
                    WorkflowCondition.Matches(WorkflowBinding(batteryId, "message"), secretRegex),
                    thenSteps = listOf(volumeStep("20")),
                    elseSteps = listOf(volumeStep("80")))
            ),
            listOf(WorkflowTrigger.Manual), WorkflowOrigin.CONVERSATION,
            createdAtMs = nowMs, updatedAtMs = nowMs)
    }

    @Test fun exportRedactsSecretsInBranchConditionLiterals() {
        val export = exportWorkflow(branchDefinition(), author = "tester", nowMs = nowMs)
        assertFalse("secret literal must not leave the device",
            export.manifestJson.contains(secretLiteral))
        assertTrue(export.manifestJson.contains("{{setup:condition_literal}}"))
        val names = parseWorkflowManifest(export.manifestJson).setupBindings.map { it.name }
        assertTrue(names.contains("condition_literal"))
        assertTrue("the redaction record names the condition field",
            export.preview.redacted.any { it.field == "steps[1].condition.literal" })
    }

    @Test fun exportRedactsSecretsInBranchConditionRegex() {
        val export = exportWorkflow(branchDefinition(), author = "tester", nowMs = nowMs)
        assertFalse("secret regex must not leave the device",
            export.manifestJson.contains(secretRegex))
        assertTrue(export.manifestJson.contains("{{setup:condition_regex}}"))
        val names = parseWorkflowManifest(export.manifestJson).setupBindings.map { it.name }
        assertTrue(names.contains("condition_regex"))
        assertTrue("the redaction record names the condition field",
            export.preview.redacted.any { it.field == "steps[2].condition.regex" })
    }

    @Test fun redactedRegexPlaceholderIsValidAndRestoresExactly() {
        // A bare {{setup:condition_regex}} is not a valid regex (Java
        // Pattern rejects the braces with "Illegal repetition"), so the
        // redacted value must be stored in a form that passes the manifest's
        // regex validation at import review — and setup must restore the
        // EXACT original regex, not a quoted variant that would change what
        // the condition matches.
        val export = exportWorkflow(branchDefinition(), author = "tester", nowMs = nowMs)
        val parsed = parseWorkflowManifest(export.manifestJson) // must not throw: validator intact
        val stored = parsed.workflow.steps.filterIsInstance<WorkflowStep.Branch>()[1]
            .condition as WorkflowCondition.Matches
        assertEquals("condition_regex", redactedRegexBindingName(stored.regex))
        Regex(stored.regex) // the stored placeholder is itself a valid regex
        val resolved = resolveSetupBindings(parsed.workflow, mapOf(
            "description" to "Restored",
            "condition_literal" to secretLiteral,
            "condition_regex" to secretRegex
        ))
        val restored = resolved.steps.filterIsInstance<WorkflowStep.Branch>()[1]
            .condition as WorkflowCondition.Matches
        assertEquals("the exact original regex must come back, not a quoted form",
            secretRegex, restored.regex)
    }

    @Test fun exportPreviewCoversFullConditionContent() {
        val export = exportWorkflow(branchDefinition(), author = "tester", nowMs = nowMs)
        val shared = export.preview.shared.joinToString("\n")
        // The preview shows the complete condition content that will be
        // shared — not just the word "branch" — with personal strings
        // already replaced by their setup placeholders.
        assertTrue(shared.contains("branch (if"))
        assertTrue(shared.contains("{{setup:condition_literal}}"))
        assertTrue(shared.contains("{{setup:condition_regex}}"))
        assertFalse(shared.contains(secretLiteral))
        assertFalse(shared.contains(secretRegex))
    }

    @Test fun conditionRoundTripPreservesBehaviorAndValidation() {
        val original = branchDefinition()
        val export = exportWorkflow(original, author = "tester", nowMs = nowMs)
        val parsed = parseWorkflowManifest(export.manifestJson)
        val resolved = resolveSetupBindings(parsed.workflow, mapOf(
            "description" to "Restored",
            "condition_literal" to secretLiteral,
            "condition_regex" to secretRegex
        ))
        // The resolved definition re-runs validation in its init, and the
        // restored conditions are the originals.
        val branches = resolved.steps.filterIsInstance<WorkflowStep.Branch>()
        assertEquals(2, branches.size)
        assertEquals(secretLiteral,
            (branches[0].condition as WorkflowCondition.Equals).literal)
        assertEquals(secretRegex,
            (branches[1].condition as WorkflowCondition.Matches).regex)
        // Behavior: the resolved definition dispatches exactly like the
        // original for matching and non-matching outputs.
        for (message in listOf(secretLiteral, "something else entirely")) {
            assertEquals(
                "dispatch trace differs for message “$message”",
                traceDispatches(original, message),
                traceDispatches(resolved, message)
            )
        }
        // And the taken paths are the meaningful ones: the secret literal
        // matches Equals (then) but not the hunter2-internal regex (else).
        assertEquals(
            listOf("read_battery", "set_volume:20", "set_volume:80"),
            traceDispatches(resolved, secretLiteral))
        assertEquals(
            listOf("read_battery", "set_volume:80", "set_volume:80"),
            traceDispatches(resolved, "something else entirely"))
    }

    /** Runs [definition] with a fixed tool message; records dispatched tools in order. */
    private fun traceDispatches(definition: WorkflowDefinition, message: String): List<String> {
        val dispatched = mutableListOf<String>()
        val outcome = WorkflowEngine().run(
            definition,
            dispatch = { request ->
                dispatched += if (request.name == "set_volume")
                    "set_volume:${request.arguments["level"]}" else request.name
                ExecutionResult(true, message)
            },
            runScript = { ScriptExecution.Failed("no scripts in this test") }
        )
        assertTrue("expected Completed, got $outcome", outcome is WorkflowRunOutcome.Completed)
        return dispatched
    }
}
