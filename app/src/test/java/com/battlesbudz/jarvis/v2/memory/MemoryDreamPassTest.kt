package com.battlesbudz.jarvis.v2.memory

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDreamPassTest {
    class FakeJudge(var handler: (DreamGroup) -> DreamJudgment?) : DreamJudge {
        var groupCalls = 0
        var extractCalls = 0
        override suspend fun judgeGroup(group: DreamGroup): DreamJudgment? {
            groupCalls++
            return handler(group)
        }
        override suspend fun extractFacts(cluster: DreamExtractionCluster): List<DreamExtractedFact> {
            extractCalls++
            return emptyList()
        }
    }

    class Fixture(live: Boolean = true) {
        var now = 1_700_000_000_000L
        private var eventSeq = 0
        val os = MemoryOs(
            File.createTempFile("dream-pass", ".json").apply { delete(); deleteOnExit() },
        ) { now }
        val fence = AutomaticMemoryFence(InMemoryFenceStore())
        val journal = MemoryDreamJournal(
            InMemoryDreamJournalStore(
                if (live) DreamJournalState(completedNightKeys = setOf("seed-1", "seed-2", "seed-3"))
                else DreamJournalState()
            )
        )
        val vectors = mutableMapOf<String, FloatArray>()
        var judge = FakeJudge { null }

        fun pass(): MemoryDreamPass = MemoryDreamPass(os, fence, journal, { vectors.toMap() }, judge) { now }

        fun add(
            content: String,
            category: MemoryCategory = MemoryCategory.FACT,
            type: MemoryType = MemoryType.SEMANTIC,
            eventSource: String = "auto-memory/chat",
            vector: FloatArray? = null,
            confidence: Int = 85,
        ): MemoryRecord {
            now += 1_000L
            val proposal = MemoryProposal(
                content = content,
                source = MemorySource("dream-test-${eventSeq++}", eventSource, now),
                category = category,
                type = type,
                confidence = confidence,
            )
            val created = os.propose(proposal)
            check(created.outcome == MemoryOutcome.CREATED) { "propose $content -> ${created.outcome}" }
            val approved = os.approve(created.memory!!.id)
            check(approved.outcome == MemoryOutcome.APPROVED) { "approve $content -> ${approved.outcome}" }
            val record = os.read().snapshot!!.memories.first { it.id == created.memory.id }
            if (vector != null) vectors[record.id] = vector
            return record
        }

        fun run(nightKey: String = "2026-11-03"): DreamRunEntry = runBlocking { pass().run(nightKey, "Gemma-4-E4B-it") }
        fun snapshot(): MemorySnapshot = os.read().snapshot!!
        fun dreamRecords(): List<MemoryRecord> = snapshot().memories.filter {
            it.source.eventSource.startsWith("dream_cycle/")
        }
    }

    private fun synth(content: String, confidence: Int = 95): (DreamGroup) -> DreamJudgment? = { group ->
        DreamJudgment(DreamVerb.SYNTHESIZE, content, null, group.recordIds.take(3), confidence)
    }

    private val axisA = floatArrayOf(1f, 0f, 0f)
    private val axisB = floatArrayOf(0f, 1f, 0f)

    @Test fun liveSynthesizeCommitsWithExactProvenanceAndAdvancesWatermark() {
        val fixture = Fixture()
        val a = fixture.add("I keep my kayak in the garage", vector = axisA)
        val b = fixture.add("My kayak lives in the garage", vector = axisA)
        val c = fixture.add("The kayak is stored in the garage", vector = axisA)
        fixture.judge = FakeJudge(synth("The user's kayak is kept in the garage."))
        val entry = fixture.run()

        assertEquals(DreamRunOutcome.COMPLETED, entry.outcome)
        val dream = fixture.dreamRecords().single()
        assertEquals("The user's kayak is kept in the garage.", dream.content)
        assertEquals(MemoryReviewStatus.APPROVED, dream.reviewStatus)
        assertEquals(
            listOf("dream_cycle", "source_memory", "source_memory", "source_memory"),
            dream.source.provenance.map { it.kind },
        )
        assertEquals(entry.runId, dream.source.provenance.first().id)
        assertEquals(setOf(a.id, b.id, c.id), dream.source.provenance.drop(1).map { it.id }.toSet())
        val output = entry.outputs.single()
        assertEquals(DreamOutputAction.COMMITTED, output.action)
        assertEquals(dream.id, output.newRecordId)
        // Sources stay fully active; synthesis retires nothing.
        assertTrue(fixture.snapshot().memories.filter { it.id in setOf(a.id, b.id, c.id) }
            .all { it.reviewStatus == MemoryReviewStatus.APPROVED })
        assertEquals(c.updatedAtMs, fixture.journal.state().watermarkMs)
        assertTrue(fixture.journal.diagnosticsLine().contains("synthesized=1"))
    }

    @Test fun dryRunJournalsWouldCommitAndWritesNothingToTheLedger() {
        val fixture = Fixture(live = false)
        fixture.add("I keep my kayak in the garage", vector = axisA)
        fixture.add("My kayak lives in the garage", vector = axisA)
        fixture.add("The kayak is stored in the garage", vector = axisA)
        fixture.judge = FakeJudge(synth("The user's kayak is kept in the garage."))
        val before = fixture.snapshot()
        val entry = fixture.run("2026-11-03")
        val after = fixture.snapshot()

        assertTrue(entry.dryRun)
        assertEquals(DreamOutputAction.WOULD_COMMIT, entry.outputs.single().action)
        assertEquals(DreamReasons.DRY_RUN, entry.outputs.single().reason)
        assertEquals(before, after)
        assertTrue(fixture.dreamRecords().isEmpty())
    }

    @Test fun dryRunGroupsStayEligibleForTheirLiveCommit() {
        val fixture = Fixture(live = false)
        fixture.add("I keep my kayak in the garage", vector = axisA)
        fixture.add("My kayak lives in the garage", vector = axisA)
        fixture.add("The kayak is stored in the garage", vector = axisA)
        fixture.judge = FakeJudge(synth("The user's kayak is kept in the garage."))
        fixture.run("2026-11-03") // dry night 1
        fixture.run("2026-11-04") // dry night 2
        fixture.run("2026-11-05") // dry night 3
        assertTrue(fixture.dreamRecords().isEmpty())
        val live = fixture.run("2026-11-06") // night 4: live
        assertEquals(false, live.dryRun)
        assertEquals(DreamOutputAction.COMMITTED, live.outputs.single().action)
        assertEquals(1, fixture.dreamRecords().size)
    }

    @Test fun repeatFiringOnACompletedNightIsAJournaledNoop() {
        val fixture = Fixture()
        fixture.add("I keep my kayak in the garage", vector = axisA)
        fixture.add("My kayak lives in the garage", vector = axisA)
        fixture.add("The kayak is stored in the garage", vector = axisA)
        fixture.judge = FakeJudge(synth("The user's kayak is kept in the garage."))
        fixture.run("2026-11-03")
        val judgeCallsAfterFirst = fixture.judge.groupCalls
        val second = fixture.run("2026-11-03")
        assertEquals("repeat_firing_noop", second.note)
        assertEquals(judgeCallsAfterFirst, fixture.judge.groupCalls)
        assertEquals(1, fixture.dreamRecords().size)
    }

    @Test fun aKilledRunResumesWithoutDuplicates() {
        val fixture = Fixture()
        fixture.add("Kayak fact one", vector = axisA)
        fixture.add("Kayak fact two", vector = axisA)
        fixture.add("Kayak fact three", vector = axisA)
        fixture.add("Canoe fact one", vector = axisB)
        fixture.add("Canoe fact two", vector = axisB)
        fixture.add("Canoe fact three", vector = axisB)
        var calls = 0
        fixture.judge = FakeJudge { group ->
            calls++
            if (calls == 2) throw CancellationException("worker killed")
            DreamJudgment(DreamVerb.SYNTHESIZE, "A stored pattern ${group.key.takeLast(8)}.", null, group.recordIds.take(3), 95)
        }
        var cancelled = false
        try {
            fixture.run("2026-11-03")
        } catch (expected: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(1, fixture.dreamRecords().size)
        assertNull(fixture.journal.state().lastEntry!!.finishedAtMs) // left in-progress

        val resumed = fixture.run("2026-11-03")
        assertEquals(DreamRunOutcome.COMPLETED, resumed.outcome)
        assertEquals(2, fixture.dreamRecords().size) // exactly one per group, no duplicates
        assertEquals(3, calls) // group 1 was not re-judged on resume
        assertNotNull(resumed.finishedAtMs)
    }

    @Test fun judgeFailureIsJournaledAndTheRunStillCompletes() {
        val fixture = Fixture()
        fixture.add("I keep my kayak in the garage", vector = axisA)
        fixture.add("My kayak lives in the garage", vector = axisA)
        fixture.add("The kayak is stored in the garage", vector = axisA)
        fixture.judge = FakeJudge { throw RuntimeException("engine died") }
        val entry = fixture.run()
        assertEquals(DreamRunOutcome.COMPLETED, entry.outcome)
        assertEquals(DreamReasons.JUDGE_ERROR, entry.outputs.single().reason)
        assertTrue(fixture.dreamRecords().isEmpty())
    }

    @Test fun groupCapLeavesRemainingGroupsForALaterNight() {
        val fixture = Fixture()
        // 13 disjoint pairs on orthogonal axes in a 16-dim space.
        fun axis(i: Int): FloatArray = FloatArray(16).also { it[i] = 1f }
        repeat(13) { i ->
            fixture.add("Pair $i fact one", vector = axis(i))
            fixture.add("Pair $i fact two", vector = axis(i))
            fixture.add("Pair $i fact three", vector = axis(i))
        }
        fixture.judge = FakeJudge(synth("A stored pattern."))
        val first = fixture.run("2026-11-03")
        assertEquals(MemoryDreamPass.MAX_GROUPS_PER_RUN, first.groupsExamined)
        assertEquals("group_cap_reached", first.note)
        val second = fixture.run("2026-11-04")
        assertEquals(1, second.groupsExamined)
        assertEquals(13, fixture.dreamRecords().size)
    }
}
