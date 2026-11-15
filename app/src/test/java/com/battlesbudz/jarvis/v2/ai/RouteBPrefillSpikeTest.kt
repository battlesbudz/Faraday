package com.battlesbudz.jarvis.v2.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Route B spike harness logic, against fakes at the session seam — the
 * native library cannot load in JVM tests, so native gate acceptance and
 * true on-device parity stay device-owed (Battles runs the harness from
 * the call screen's development diagnostics). These tests pin everything
 * CI can prove: chunk planning and marker placement, slice/reassembly
 * exactness, arm sequencing (question prefill, audio, generation boundary),
 * failure capture per arm, and the report's PASS/FAIL rules.
 */
class RouteBPrefillSpikeTest {

    private class FakeSession(
        private val answer: String = "tones rising and falling",
        private val failOnChunk: Int = -1,
    ) : RouteBSpikeSession {
        val events = mutableListOf<String>()
        val chunks = mutableListOf<Triple<FloatArray, Boolean, Boolean>>()
        private var chunkIndex = 0

        override fun prefillText(text: String) {
            events += "text:$text"
        }

        override fun prefillAudioChunk(
            rows: FloatArray,
            tokenCount: Int,
            embeddingWidth: Int,
            includeBeginMarker: Boolean,
            includeEndMarker: Boolean,
        ) {
            events += "audio:$tokenCount:$includeBeginMarker:$includeEndMarker"
            if (chunkIndex++ == failOnChunk) throw IllegalStateException("native gate rejected chunk")
            chunks += Triple(rows.copyOf(), includeBeginMarker, includeEndMarker)
        }

        override suspend fun generate(boundaryText: String): RouteBPrefillSpike.Generation {
            events += "generate:$boundaryText"
            return RouteBPrefillSpike.Generation(answer, ttftMs = 7)
        }
    }

    private fun rows(tokens: Int, width: Int): FloatArray =
        FloatArray(tokens * width) { (it % 977) / 977.0f - 0.5f }

    @Test fun chunkPlanAlignsToEncoderStepsAndMarksOnlyTheEnds() {
        val plan = RouteBPrefillSpike.planAudioChunks(188)
        assertEquals(listOf(72, 72, 44), plan.map { it.rowCount })
        assertEquals(listOf(0, 72, 144), plan.map { it.startRow })
        assertEquals(listOf(true, false, false), plan.map { it.includeBeginMarker })
        assertEquals(listOf(false, false, true), plan.map { it.includeEndMarker })
    }

    @Test fun chunkPlanSingleChunkCarriesBothMarkers() {
        val plan = RouteBPrefillSpike.planAudioChunks(25)
        assertEquals(1, plan.size)
        assertTrue(plan.single().includeBeginMarker && plan.single().includeEndMarker)
    }

    @Test fun chunkPlanRejectsEmptyUtterance() {
        try {
            RouteBPrefillSpike.planAudioChunks(0)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun sealedArmSubmitsOnePayloadWithBothMarkers() = runBlocking {
        val session = FakeSession()
        val source = rows(188, 4)
        val arm = RouteBPrefillSpike.runArm(session, source, 188, 4, chunked = false)
        assertEquals(listOf(188), arm.chunkSizes)
        assertEquals(1, session.chunks.size)
        val (payload, begin, end) = session.chunks.single()
        assertTrue(begin && end)
        assertTrue(payload.contentEquals(source))
        assertEquals(
            listOf("text:<|turn>user\n" + RouteBPrefillSpike.QUESTION, "audio:188:true:true",
                "generate:<turn|>\n<|turn>model\n"),
            session.events)
    }

    @Test fun chunkedArmReassemblesExactlyTheSealedRows() = runBlocking {
        val session = FakeSession()
        val source = rows(188, 4)
        val arm = RouteBPrefillSpike.runArm(session, source, 188, 4, chunked = true)
        assertEquals(listOf(72, 72, 44), arm.chunkSizes)
        assertEquals(listOf(true, false, false), session.chunks.map { it.second })
        assertEquals(listOf(false, false, true), session.chunks.map { it.third })
        val diff = RouteBPrefillSpike.compareRows(source, arm.fedRows)
        assertEquals(0.0, diff.maxAbsDiff, 0.0)
        assertEquals(0.0, diff.meanAbsDiff, 0.0)
        assertTrue(diff.exactlyEqual)
        // Each submitted slice is the matching window of the one encode pass.
        var offset = 0
        for ((slice, _, _) in session.chunks) {
            assertTrue(slice.contentEquals(source.copyOfRange(offset, offset + slice.size)))
            offset += slice.size
        }
    }

    @Test fun rowComparisonReportsNumericDivergence() {
        val expected = floatArrayOf(1.0f, -2.0f, 0.25f)
        val actual = floatArrayOf(1.0f, -1.5f, 0.25f)
        val diff = RouteBPrefillSpike.compareRows(expected, actual)
        assertEquals(0.5, diff.maxAbsDiff, 1e-9)
        assertEquals(0.5 / 3, diff.meanAbsDiff, 1e-9)
        assertTrue(!diff.exactlyEqual)
    }

    @Test fun armFailureIsCapturedPerArm() = runBlocking {
        val session = FakeSession(failOnChunk = 1)
        val result = RouteBPrefillSpike.runArmCatching(session, rows(188, 4), 188, 4, chunked = true)
        assertTrue(result.isFailure)
        assertEquals("native gate rejected chunk", result.exceptionOrNull()?.message)
    }

    @Test fun reportPassesOnlyOnExactRowsAndIdenticalAnswers() = runBlocking {
        val source = rows(188, 4)
        val sealed = RouteBPrefillSpike.runArmCatching(FakeSession(), source, 188, 4, chunked = false)
        val chunked = RouteBPrefillSpike.runArmCatching(FakeSession(), source, 188, 4, chunked = true)
        val report = RouteBPrefillSpike.buildReport(11, 188, 4, sealed, chunked)
        assertTrue(report.contains("tokens=188 width=4"))
        assertTrue(report.contains("chunks=[72, 72, 44]"))
        assertTrue(report.contains("maxAbsDiff=0.0"))
        assertTrue(report.lines().last().startsWith("PARITY: PASS"))
    }

    @Test fun reportFailsOnAnswerMismatchEvenWithExactRows() = runBlocking {
        val source = rows(188, 4)
        val sealed = RouteBPrefillSpike.runArmCatching(FakeSession(answer = "one"), source, 188, 4, chunked = false)
        val chunked = RouteBPrefillSpike.runArmCatching(FakeSession(answer = "two"), source, 188, 4, chunked = true)
        val report = RouteBPrefillSpike.buildReport(11, 188, 4, sealed, chunked)
        assertTrue(report.lines().last().startsWith("PARITY: FAIL"))
        assertTrue(report.contains("greedy answers differ"))
    }

    @Test fun reportFailsWhenAnArmIsRejected() = runBlocking {
        val source = rows(188, 4)
        val sealed = RouteBPrefillSpike.runArmCatching(FakeSession(), source, 188, 4, chunked = false)
        val chunked = RouteBPrefillSpike.runArmCatching(FakeSession(failOnChunk = 0), source, 188, 4, chunked = true)
        val report = RouteBPrefillSpike.buildReport(11, 188, 4, sealed, chunked)
        assertTrue(report.contains("Chunked incremental: FAILED: native gate rejected chunk"))
        assertTrue(report.lines().last().startsWith("PARITY: FAIL"))
    }

    // Option A: the provisional snapshot stream of one encode pass must be
    // an exact, contiguous prefix of that pass's sealed rows.

    private fun snapshot(source: FloatArray, width: Int, firstRow: Int, totalRows: Int) =
        RouteBPrefillSpike.ProvisionalSnapshot(
            firstRow, totalRows, source.copyOfRange(firstRow * width, totalRows * width))

    @Test fun provisionalSnapshotsPassWhenExactContiguousAndOnlyTheFinalStepOutstanding() {
        val width = 4
        val source = rows(188, width)
        val snapshots = listOf(
            snapshot(source, width, 0, 24), snapshot(source, width, 24, 96),
            snapshot(source, width, 96, 180))
        val section = RouteBPrefillSpike.verifyProvisionalSnapshots(snapshots, source, 188, width)
        assertTrue(section.contains("count=3"))
        assertTrue(section.contains("Rows exposed during capture: 180 of 188"))
        assertTrue(section.lines().last().startsWith("PROVISIONAL: PASS"))
    }

    @Test fun provisionalSnapshotsFailOnAnyDivergentRow() {
        val width = 4
        val source = rows(188, width)
        val corrupted = snapshot(source, width, 0, 24)
        corrupted.rows[5] = corrupted.rows[5] + 1.0f
        val section = RouteBPrefillSpike.verifyProvisionalSnapshots(
            listOf(corrupted, snapshot(source, width, 24, 180)), source, 188, width)
        assertTrue(section.lines().last().startsWith("PROVISIONAL: FAIL"))
        assertTrue(section.contains("diverges from the sealed rows"))
    }

    @Test fun provisionalSnapshotsFailOnAChainGap() {
        val width = 4
        val source = rows(188, width)
        val section = RouteBPrefillSpike.verifyProvisionalSnapshots(
            listOf(snapshot(source, width, 0, 24), snapshot(source, width, 48, 96)),
            source, 188, width)
        assertTrue(section.lines().last().startsWith("PROVISIONAL: FAIL"))
        assertTrue(section.contains("breaks the chain"))
    }

    @Test fun provisionalSnapshotsFailWhenMoreThanTheFinalStepIsOutstanding() {
        val width = 4
        val source = rows(188, width)
        // 175 of 188 exposed: 13 rows outstanding, more than any final
        // partial step can account for.
        val section = RouteBPrefillSpike.verifyProvisionalSnapshots(
            listOf(snapshot(source, width, 0, 175)), source, 188, width)
        assertTrue(section.lines().last().startsWith("PROVISIONAL: FAIL"))
        assertTrue(section.contains("still outstanding"))
    }

    @Test fun provisionalSnapshotsFailWhenNoneArrive() {
        val width = 4
        val section = RouteBPrefillSpike.verifyProvisionalSnapshots(
            emptyList(), rows(188, width), 188, width)
        assertTrue(section.lines().last().startsWith("PROVISIONAL: FAIL"))
        assertTrue(section.contains("no provisional snapshots arrived"))
    }
}
