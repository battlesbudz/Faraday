package com.battlesbudz.jarvis.v2.memory

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drift countermeasures from the Unit 3 scope (§5), named so they fail
 * loudly. Each test pins one safeguard of the dream pass against a seeded
 * store and a scripted judge.
 */
class MemoryDreamCountermeasuresTest {
    private val axisA = floatArrayOf(1f, 0f, 0f)
    private val axisB = floatArrayOf(0f, 1f, 0f)
    private val axisC = floatArrayOf(0f, 0f, 1f)
    private val axisD = floatArrayOf(1f, 1f, 0f)

    private fun synth(content: String, confidence: Int = 95, sources: (DreamGroup) -> List<String> = { it.recordIds.take(3) }):
        (DreamGroup) -> DreamJudgment? = { group ->
        DreamJudgment(DreamVerb.SYNTHESIZE, content, null, sources(group), confidence)
    }

    @Test fun ProvenanceCompletenessTest() {
        val fixture = MemoryDreamPassTest.Fixture()
        val a = fixture.add("I keep my kayak in the garage", vector = axisA)
        val b = fixture.add("My kayak lives in the garage", vector = axisA)
        val c = fixture.add("The kayak is stored in the garage", vector = axisA)
        fixture.judge = MemoryDreamPassTest.FakeJudge(synth("The user's kayak is kept in the garage."))
        val entry = fixture.run()

        val dream = fixture.dreamRecords().single()
        assertTrue(dream.source.provenance.size <= MemoryPolicy.MAX_PROVENANCE)
        assertEquals(4, dream.source.provenance.size) // 1 dream_cycle + 3 source_memory
        assertEquals(MemoryProvenance("dream_cycle", entry.runId, "Nightly dream synthesis"), dream.source.provenance.first())
        assertEquals(
            setOf(a.id, b.id, c.id),
            dream.source.provenance.drop(1).map { it.id }.toSet(),
        )
        assertTrue(dream.source.provenance.drop(1).all { it.kind == "source_memory" })
    }

    @Test fun SynthesisReadsCanonicalOnlyTest() {
        val fixture = MemoryDreamPassTest.Fixture()
        fixture.add("Canonical anchor fact", vector = axisA)
        val derivedSource = "dream_cycle/dream-older-1"
        fixture.add("Derived claim one", eventSource = derivedSource, vector = axisA)
        fixture.add("Derived claim two", eventSource = derivedSource, vector = axisA)
        fixture.add("Derived claim three", eventSource = derivedSource, vector = axisA)
        // The judge tries to synthesize from the three derived records alone.
        fixture.judge = MemoryDreamPassTest.FakeJudge { group ->
            val derivedOnly = group.records.filter { MemoryDreamGrouping.isDreamDerived(it) }.map { it.id }
            DreamJudgment(DreamVerb.SYNTHESIZE, "A pattern built only on derived records.", null, derivedOnly, 97)
        }
        val entry = fixture.run()
        assertEquals(DreamReasons.DERIVED_SOLE_SOURCE, entry.outputs.single().reason)
        assertEquals(DreamOutputAction.JOURNALED, entry.outputs.single().action)
        assertTrue(fixture.dreamRecords().none { it.content == "A pattern built only on derived records." })
    }

    @Test fun FenceSupremacyTest() {
        // File-backed stores, rebuilt between phases: a simulated restart.
        val ledger = File.createTempFile("dream-fence", ".json").apply { delete(); deleteOnExit() }
        val fenceFile = File.createTempFile("dream-fence", ".tsv").apply { delete(); deleteOnExit() }
        var now = 1_700_000_000_000L
        val deletedContent = "I park my truck behind the barn"
        val firstOs = MemoryOs(ledger) { now }
        val firstFence = AutomaticMemoryFence(FileFenceStore(fenceFile))
        now += 1_000L
        val doomed = firstOs.propose(
            MemoryProposal(deletedContent, MemorySource("fence-0", "auto-memory/chat", now))
        ).memory!!
        firstOs.approve(doomed.id)
        firstFence.recordCommitted(AutomaticMemory.contentFingerprint(deletedContent), doomed.id)
        assertEquals(MemoryOutcome.DELETED, firstOs.delete(doomed.id).outcome)

        // Restart: fresh service objects over the same files.
        val os = MemoryOs(ledger) { now }
        val fence = AutomaticMemoryFence(FileFenceStore(fenceFile))
        val journal = MemoryDreamJournal(
            InMemoryDreamJournalStore(DreamJournalState(completedNightKeys = setOf("s1", "s2", "s3")))
        )
        val vectors = mutableMapOf<String, FloatArray>()
        var seq = 1
        listOf("Truck note one", "Truck note two", "Truck note three").forEach { content ->
            now += 1_000L
            val created = os.propose(MemoryProposal(content, MemorySource("fence-${seq++}", "auto-memory/chat", now))).memory!!
            os.approve(created.id)
            vectors[created.id] = axisA
        }
        val judge = MemoryDreamPassTest.FakeJudge(synth(deletedContent))
        val pass = MemoryDreamPass(os, fence, journal, { vectors.toMap() }, judge) { now }
        val entry = runBlocking { pass.run("2026-11-03", "Gemma-4-E4B-it") }

        assertEquals(DreamReasons.FENCED_CONTENT, entry.outputs.single().reason)
        assertEquals(DreamOutputAction.JOURNALED, entry.outputs.single().action)
        assertTrue(os.read().snapshot!!.memories.none { it.content == deletedContent })
    }

    @Test fun InvalidateDontDeleteTest() {
        // OD-4's form of the scope test: a merge proposal must change NOTHING —
        // sources stay APPROVED and byte-identical, nothing is superseded,
        // nothing is deleted, and the proposal is journaled instead.
        val fixture = MemoryDreamPassTest.Fixture()
        val a = fixture.add("I keep my kayak in the garage", vector = axisA)
        val b = fixture.add("My kayak lives in the garage", vector = axisA)
        fixture.judge = MemoryDreamPassTest.FakeJudge { group ->
            DreamJudgment(DreamVerb.MERGE, "The user's kayak is kept in the garage.", null, group.recordIds, 99)
        }
        val before = fixture.snapshot()
        val entry = fixture.run()
        val after = fixture.snapshot()

        assertEquals(before, after)
        val output = entry.outputs.single()
        assertEquals(DreamVerb.MERGE, output.verb)
        assertEquals(DreamOutputAction.JOURNALED, output.action)
        assertEquals(DreamReasons.AUTONOMOUS_SUPERSEDE_DISABLED, output.reason)
        assertTrue(after.memories.filter { it.id in setOf(a.id, b.id) }
            .all { it.reviewStatus == MemoryReviewStatus.APPROVED })
        assertTrue(fixture.dreamRecords().isEmpty())
    }

    @Test fun VetoGateTest() {
        val fixture = MemoryDreamPassTest.Fixture()
        val axes = listOf(axisA, axisB, axisC, axisD)
        val plans = listOf(
            "hedge" to DreamJudgment(DreamVerb.SYNTHESIZE, "The user might keep tools in the shed.", null, emptyList(), 95),
            "sensitive" to DreamJudgment(DreamVerb.SYNTHESIZE, "The user's wife prefers tea in the morning.", null, emptyList(), 95),
            "contradiction" to DreamJudgment(DreamVerb.SYNTHESIZE, "The user no longer keeps the kayak in the garage.", null, emptyList(), 95),
            "confidence" to DreamJudgment(DreamVerb.SYNTHESIZE, "The user stores the kayak in the garage.", null, emptyList(), 80),
        )
        val groupLeaders = mutableMapOf<String, String>()
        plans.forEachIndexed { index, (name, _) ->
            val axis = axes[index]
            val first = fixture.add("Group $name record one", vector = axis)
            fixture.add("Group $name record two", vector = axis)
            fixture.add("Group $name record three", vector = axis)
            groupLeaders[first.id] = name
        }
        fixture.judge = MemoryDreamPassTest.FakeJudge { group ->
            val plan = plans.first { it.first == groupLeaders[group.recordIds.firstOrNull { id -> id in groupLeaders }] }
            plan.second.copy(sourceRecordIds = group.recordIds.take(3))
        }
        val entry = fixture.run()
        val reasons = entry.outputs.map { it.reason }.toSet()
        assertEquals(
            setOf(
                DreamReasons.VETO_HEDGE,
                DreamReasons.VETO_SENSITIVE,
                DreamReasons.VETO_CONTRADICTION,
                DreamReasons.CONFIDENCE_FLOOR,
            ),
            reasons,
        )
        assertTrue(entry.outputs.all { it.action == DreamOutputAction.JOURNALED })
        assertTrue(fixture.dreamRecords().isEmpty())
    }

    @Test fun CapHeadroomTest() {
        val fixture = MemoryDreamPassTest.Fixture()
        repeat(MemoryPolicy.MAX_MEMORIES - 3) { i -> fixture.add("Filler record number $i") }
        val a = fixture.add("I keep my kayak in the garage", vector = axisA)
        fixture.add("My kayak lives in the garage", vector = axisA)
        fixture.add("The kayak is stored in the garage", vector = axisA)
        assertEquals(MemoryPolicy.MAX_MEMORIES, fixture.snapshot().memories.size)
        fixture.judge = MemoryDreamPassTest.FakeJudge(synth("The user's kayak is kept in the garage."))
        val entry = fixture.run()

        assertEquals(DreamReasons.CAP_HEADROOM, entry.outputs.single().reason)
        assertEquals(MemoryPolicy.MAX_MEMORIES, fixture.snapshot().memories.size)
        assertTrue(fixture.snapshot().memories.any { it.id == a.id }) // nothing evicted
        assertTrue(fixture.dreamRecords().isEmpty())
    }

    @Test fun IdempotentRerunTest() {
        val fixture = MemoryDreamPassTest.Fixture()
        fixture.add("I keep my kayak in the garage", vector = axisA)
        fixture.add("My kayak lives in the garage", vector = axisA)
        fixture.add("The kayak is stored in the garage", vector = axisA)
        fixture.judge = MemoryDreamPassTest.FakeJudge(synth("The user's kayak is kept in the garage."))
        fixture.run("2026-11-03")
        assertEquals(1, fixture.dreamRecords().size)
        val calls = fixture.judge.groupCalls
        val secondNight = fixture.run("2026-11-04")
        assertEquals(0, secondNight.groupsExamined)
        assertEquals(calls, fixture.judge.groupCalls)
        assertEquals(1, fixture.dreamRecords().size)
        assertEquals(4, fixture.snapshot().memories.size) // 3 sources + 1 dream record, nothing more
    }

    @Test fun DryRunPurityTest() {
        val fixture = MemoryDreamPassTest.Fixture(live = false)
        fixture.add("I keep my kayak in the garage", vector = axisA)
        fixture.add("My kayak lives in the garage", vector = axisA)
        fixture.add("The kayak is stored in the garage", vector = axisA)
        fixture.judge = MemoryDreamPassTest.FakeJudge(synth("The user's kayak is kept in the garage."))
        val before = fixture.snapshot()
        val entry = fixture.run()
        val after = fixture.snapshot()
        assertEquals(before.generation, after.generation)
        assertEquals(before, after)
        assertEquals(1, fixture.journal.state().entries.size)
        assertEquals(DreamOutputAction.WOULD_COMMIT, entry.outputs.single().action)
    }

    @Test fun GoldenSpotCheckTest() {
        val world = MemoryGoldenCorpus.buildWorld()
        val targetByText = world.byText.mapValues { it.value.id }
        // Seed the duplicate-heavy condition: two more copies of the tea fact.
        val teaIds = mutableSetOf(targetByText.getValue(MemoryGoldenCorpus.Text.TEA))
        repeat(2) {
            val duplicate = world.add(MemoryGoldenCorpus.Text.TEA, MemoryCategory.PREFERENCE)
            teaIds += duplicate.id
        }
        world.indexProjection()

        fun recallAt5(): Double {
            val cases: List<Pair<String, Set<String>>> = listOf(
                MemoryGoldenCorpus.Queries.NOTEBOOK to setOf(targetByText.getValue(MemoryGoldenCorpus.Text.NOTEBOOK)),
                MemoryGoldenCorpus.Queries.TEA_PARAPHRASE to teaIds,
                MemoryGoldenCorpus.Queries.VEHICLE_PARAPHRASE to setOf(targetByText.getValue(MemoryGoldenCorpus.Text.CAR)),
                MemoryGoldenCorpus.Queries.SPOUSE_COREFERENCE to setOf(targetByText.getValue(MemoryGoldenCorpus.Text.WIFE)),
                MemoryGoldenCorpus.Queries.BROTHER_COREFERENCE to setOf(targetByText.getValue(MemoryGoldenCorpus.Text.BROTHER_HOME)),
                MemoryGoldenCorpus.Queries.GOAL_PARAPHRASE to setOf(targetByText.getValue(MemoryGoldenCorpus.Text.CABIN)),
                MemoryGoldenCorpus.Queries.SEAT_PARAPHRASE to setOf(targetByText.getValue(MemoryGoldenCorpus.Text.SEAT)),
                MemoryGoldenCorpus.Queries.DENTIST_DIRECT to setOf(targetByText.getValue(MemoryGoldenCorpus.Text.DENTIST)),
                MemoryGoldenCorpus.Queries.SKATE_PARAPHRASE to setOf(targetByText.getValue(MemoryGoldenCorpus.Text.SKATE)),
                MemoryGoldenCorpus.Queries.HOME_PARAPHRASE to setOf(targetByText.getValue(MemoryGoldenCorpus.Text.HOME)),
            )
            return cases.sumOf { (query, targets) ->
                val result = world.os.contextPacket(query, 4_000, limit = 5, nowMs = world.now)
                val ids = result.packet!!.memories.map { it.memory.id }
                if (ids.any { it in targets }) 1.0 else 0.0
            } / cases.size
        }

        val before = recallAt5()
        assertTrue("golden baseline must start at the release bar: $before", before >= 0.9)

        val vectors = world.vectors.read(EmbeddingModelCatalog.default.id).entries.orEmpty()
            .filter { entry ->
                val record = world.snapshot().memories.firstOrNull { it.id == entry.memoryId }
                record != null && entry.contentFingerprint == AutomaticMemory.contentFingerprint(record.content)
            }
            .associate { it.memoryId to it.vector }
        val journal = MemoryDreamJournal(
            InMemoryDreamJournalStore(DreamJournalState(completedNightKeys = setOf("s1", "s2", "s3")))
        )
        val judge = MemoryDreamPassTest.FakeJudge(synth(MemoryGoldenCorpus.Text.TEA))
        val pass = MemoryDreamPass(world.os, AutomaticMemoryFence(InMemoryFenceStore()), journal, { vectors }, judge) { world.now }
        val entry = runBlocking { pass.run("2026-11-03", "Gemma-4-E4B-it") }
        assertEquals(DreamRunOutcome.COMPLETED, entry.outcome)
        assertEquals(1, entry.outputs.count { it.action == DreamOutputAction.COMMITTED })

        val after = recallAt5()
        // The safeguard's intent: consolidation never degrades recall. (Exact
        // equality is not pinned because the new record legitimately joins
        // the preference profile slice; a drop is the failure mode.)
        assertTrue("Recall@5 degraded by a dream pass: before=$before after=$after", after >= before)
        assertTrue("Recall@5 below the release bar after a dream pass: $after", after >= 0.9)
    }
}
