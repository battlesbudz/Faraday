package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.conversation.ConversationFinalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskOutcomeContractsTest {
    @Test
    fun outcomeContractExposesAllFourStableClasses() {
        val records = TaskOutcomeClass.values().mapIndexed { index, outcome ->
            TaskOutcomeClassification(
                taskId = "task-$index",
                outcome = outcome,
                classifiedAtMs = index.toLong(),
                evidenceIds = listOf("evidence-$index"),
                judgeRole = TaskOutcomeJudgeRole.DETERMINISTIC
            )
        }

        assertEquals(
            listOf(TaskOutcomeClass.DONE, TaskOutcomeClass.PARTIAL,
                TaskOutcomeClass.NOT_POSSIBLE, TaskOutcomeClass.UNKNOWN),
            records.map { it.outcome }
        )
        assertEquals(listOf("evidence-0", "evidence-1", "evidence-2", "evidence-3"),
            records.map { it.evidenceIds.single() })
    }

    @Test
    fun classificationCanBeQueuedAfterAnswerAndDoesNotChangeOrDelayDelivery() {
        val queued = mutableListOf<Pair<String, () -> Unit>>()
        var classificationsRun = false
        val sink = object : TaskOutcomeClassificationSink {
            override fun enqueueAfterTask(taskId: String) {
                queued += taskId to { classificationsRun = true }
            }
        }
        val answer = ConversationFinalizer.resolve(
            cleanedResponse = "The volume is now 30%.",
            previousAssistant = null,
            actionResultMessage = "The volume is now 30%.",
            actionIntent = true,
            actionName = "set_volume"
        ).text

        sink.enqueueAfterTask("task-1")
        val deliveredBeforeClassification = answer
        assertEquals("task-1", queued.single().first)
        assertFalse(classificationsRun)
        assertEquals("The volume is now 30%.", deliveredBeforeClassification)

        // A controlled async queue runs only after the caller has delivered the answer.
        queued.removeAt(0).second.invoke()
        assertTrue(classificationsRun)
        assertEquals("The volume is now 30%.", deliveredBeforeClassification)
    }

    @Test
    fun evidenceContractAcceptsOnlyProducedRunEvidenceShapes() {
        val produced: List<ProducedTaskEvidence> = listOf(
            ProducedTaskEvidence.ToolResult("task-1", "tool-1", 1,
                ExecutionResult.Outcome.SUCCEEDED, "Volume set to 30%"),
            ProducedTaskEvidence.ScreenObservation("task-1", "screen-1", 2,
                "Volume slider shows 30%"),
            ProducedTaskEvidence.FileArtifact("task-1", "file-1", 3,
                "private://artifact/123"),
            ProducedTaskEvidence.RuntimeObservation("task-1", "runtime-1", 4,
                "Action completed")
        )

        val saved = mutableListOf<ProducedTaskEvidence>()
        val ledger = object : TaskEvidenceLedger {
            override fun recordProduced(evidence: ProducedTaskEvidence) {
                saved += evidence
            }

            override fun evidenceFor(taskId: String): List<ProducedTaskEvidence> =
                saved.filter { it.taskId == taskId }
        }
        produced.forEach(ledger::recordProduced)

        val taskEvidence = ledger.evidenceFor("task-1")
        assertEquals(setOf("tool-1", "screen-1", "file-1", "runtime-1"),
            taskEvidence.map { it.evidenceId }.toSet())
        assertTrue(taskEvidence.all { it.taskId == "task-1" })
        assertFalse(ledger.evidenceFor("other-task").isNotEmpty())
    }
}
