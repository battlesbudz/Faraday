package com.battlesbudz.jarvis.v2.actions

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Personal screen content and adaptive goals stay local at every graph depth. */
class WorkflowPersonalContentExportTest {
    private val text = "hunter2"
    private val otherText = "Call Alice about the test results"
    private val goal = "Prepare Alice's private medical note"
    private val otherGoal = "Finish the private account recovery"
    private val token = "0123456789abcdef"
    private fun uid() = UUID.randomUUID().toString()
    private fun screenRequest(value: String) = ActionRequest("screen_type",
        mapOf("target" to "n1", "text" to value, "token" to token))
    private fun screenStep(value: String) = WorkflowStep.Tool(uid(), screenRequest(value))
    private fun adaptive(value: String, purpose: String) = WorkflowStep.Adaptive(
        uid(), purpose, listOf(screenRequest(value)), EffortBudget(2, 30_000L, 2))

    private fun definition(): WorkflowDefinition {
        val batteryId = uid()
        val condition = WorkflowCondition.GreaterThan(WorkflowBinding(batteryId, "battery_percent"), 20.0)
        return WorkflowDefinition(uid(), "Screen routine", "",
            listOf(
                WorkflowStep.Tool(batteryId, ActionRequest("read_battery")),
                screenStep(text),
                adaptive(text, goal),
                WorkflowStep.Branch(uid(), condition,
                    thenSteps = listOf(screenStep(otherText), adaptive(otherText, otherGoal)),
                    elseSteps = listOf(WorkflowStep.Branch(uid(), condition,
                        thenSteps = listOf(screenStep(text)),
                        elseSteps = listOf(adaptive(text, goal)))))
            ), listOf(WorkflowTrigger.Manual), WorkflowOrigin.CONVERSATION,
            createdAtMs = 1_700_000_000_000L, updatedAtMs = 1_700_000_000_000L)
    }

    private fun values() = mapOf(
        "text" to text, "text_2" to otherText, "token" to token,
        "adaptive_goal" to goal, "adaptive_goal_2" to otherGoal)

    @Test fun screenTypingTextIsRedactedRegardlessOfItsShape() {
        for (value in listOf("hello", text, otherText, "a@b.example", "https://private.example")) {
            assertNotNull("screen text must be redacted: $value",
                redactToolArgument("screen_type", "text", value))
        }
        assertNull(redactToolArgument("set_volume", "level", "20"))
        assertNull(redactToolArgument("screen_type", "target", "n1"))
    }

    @Test fun nestedToolsCandidatesAndGoalsNeverExposePersonalValuesInManifestOrPreview() {
        val export = exportWorkflow(definition(), author = "tester", nowMs = 1_700_000_000_000L)
        val shared = export.preview.shared.joinToString("\n")
        for (value in values().values) {
            assertFalse("manifest must redact $value", export.manifestJson.contains(value))
            assertFalse("preview must redact $value", shared.contains(value))
        }
        val manifest = parseWorkflowManifest(export.manifestJson)
        assertEquals(values().keys, manifest.setupBindings.map { it.name }.toSet())
        assertTrue(export.preview.redacted.any { it.field == "steps[1].arguments[text]" })
        assertTrue(export.preview.redacted.any { it.field == "steps[2].candidates[0].arguments[text]" })
        assertTrue(export.preview.redacted.any { it.field == "steps[3].then[1].goal" })
        assertTrue(export.preview.redacted.any { it.field == "steps[3].else[0].else[0].goal" })
        assertTrue(shared.contains("{{setup:text}}"))
        assertTrue(shared.contains("{{setup:adaptive_goal}}"))
        assertFalse(manifest.workflow.enabled)
    }

    @Test fun setupRestoresExactScreenArgumentsAndGoalsIncludingNestedBranches() {
        val original = definition()
        val manifest = parseWorkflowManifest(exportWorkflow(original, "tester").manifestJson)
        val restored = resolveSetupBindings(manifest.workflow, values())
        assertEquals(original.steps, restored.steps)
        assertEquals(original.triggers, restored.triggers)
        assertFalse("setup does not enable a workflow", restored.enabled)
    }

    @Test fun unresolvedPersonalValuesBlockSetupInsteadOfLeavingAnExecutablePlaceholder() {
        val manifest = parseWorkflowManifest(exportWorkflow(definition(), "tester").manifestJson)
        for (missing in listOf("text", "text_2", "adaptive_goal", "adaptive_goal_2")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                resolveSetupBindings(manifest.workflow, values() - missing)
            }
            assertTrue(failure.message.orEmpty().contains(missing))
        }
    }
}
