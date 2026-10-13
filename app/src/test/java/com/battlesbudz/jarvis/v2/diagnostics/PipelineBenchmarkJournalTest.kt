package com.battlesbudz.jarvis.v2.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PipelineBenchmarkJournalTest {
    private val time = 1_900_000_000_000L
    private fun sample(id: String, conversation: String = "a", at: Long = time) = PipelineBenchmarkTurn(
        turnId = id, channel = "voice", capturedAtEpochMs = at,
        provenance = PipelineBenchmarkProvenance("test", 1), outcome = PipelineBenchmarkOutcome.CANCELLED,
        conversationId = conversation,
        accuracy = PipelineBenchmarkAccuracyEvaluator.evaluate("private reference", "private hypothesis", "verified", retainText = true))

    @Test fun beyondOld500LimitSurvivesReopenWithoutTranscriptAndFiltersAllOutcomes() {
        val dir = Files.createTempDirectory("journal-long").toFile()
        try {
            val journal = PipelineBenchmarkJournal(dir, now = { time })
            repeat(650) { assertTrue(journal.append(sample("turn-$it", if (it % 2 == 0) "a" else "b"))) }
            journal.writePrepared(journal.prepared())
            val reopened = PipelineBenchmarkJournal(dir, now = { time })
            assertEquals(650, reopened.samples().size)
            val selected = PipelineBenchmarkSelection.select(reopened.samples(), conversationId = "a")
            assertEquals(325, selected.size)
            val json = PipelineBenchmarkReport(selected, time).toJson().toString()
            assertFalse(json.contains("private reference")); assertFalse(json.contains("private hypothesis"))
            assertTrue(selected.all { it.outcome == PipelineBenchmarkOutcome.CANCELLED })
            assertEquals(325, PipelineBenchmarkReport.read(org.json.JSONObject(json)).turns.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun capacityRejectsNewAttemptPreservesOldAndMakesFailureVisible() {
        val dir = Files.createTempDirectory("journal-capacity").toFile()
        try {
            val journal = PipelineBenchmarkJournal(dir, now = { time }, maxRecords = 1)
            assertTrue(journal.append(sample("first")))
            assertFalse(journal.append(sample("second")))
            assertEquals("first", journal.samples().single().turnId)
            assertTrue(journal.status!!.contains("not retained"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun migrationPreservesLegacyAndResetCannotReimportIt() {
        val dir = Files.createTempDirectory("journal-migration").toFile()
        try {
            val legacyFile = File(dir, "legacy.json")
            val legacy = PipelineBenchmarkArchive(legacyFile); legacy.write(listOf(sample("old")))
            val path = File(dir, "records")
            val journal = PipelineBenchmarkJournal(path, legacy, now = { time })
            assertEquals(1, journal.samples().size)
            journal.writePrepared(journal.prepared()); journal.clear(); journal.writePrepared(journal.prepared())
            assertTrue(legacyFile.exists())
            assertTrue(PipelineBenchmarkJournal(path, legacy, now = { time }).samples().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun ninetyDayExpiryPrunesDurablyAndCorruptFileIsPreservedWithWarning() {
        val dir = Files.createTempDirectory("journal-expiry").toFile()
        try {
            val journal = PipelineBenchmarkJournal(dir, now = { time })
            assertTrue(journal.append(sample("fresh")))
            journal.writePrepared(journal.prepared())
            File(dir, "corrupt.json").writeText("private corrupt evidence")
            val expired = PipelineBenchmarkJournal(dir, now = { time + PipelineBenchmarkJournal.RETENTION_MS + 1 })
            assertTrue(expired.samples().isEmpty())
            assertEquals(1, expired.expiredRecords)
            assertNotNull(expired.status); assertFalse(expired.status!!.contains("private"))
            assertTrue(File(dir, "corrupt.json").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun partiallyCommittedWriteCannotResurrectAfterReset() {
        val dir = Files.createTempDirectory("journal-partial").toFile()
        try {
            val journal = PipelineBenchmarkJournal(dir, now = { time })
            assertTrue(journal.append(sample("first"))); assertTrue(journal.append(sample("blocked")))
            val name = java.security.MessageDigest.getInstance("SHA-256").digest("blocked".toByteArray())
                .joinToString("") { "%02x".format(it) } + ".json"
            val obstruction = File(dir, name).apply { mkdirs() }
            File(obstruction, "not-empty").writeText("block replacement")
            assertTrue(runCatching { journal.writePrepared(journal.prepared()) }.isFailure)
            journal.clear(); journal.writePrepared(journal.prepared())
            assertTrue(PipelineBenchmarkJournal(dir, now = { time }).samples().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun capacityRefusedMigrationIsRetriedAndOriginalIsPreserved() {
        val dir = Files.createTempDirectory("journal-migration-cap").toFile()
        try {
            val legacy = PipelineBenchmarkArchive(File(dir, "old.json"))
            legacy.write(listOf(sample("one"), sample("two")))
            val path = File(dir, "records")
            val journal = PipelineBenchmarkJournal(path, legacy, now = { time }, maxRecords = 1)
            assertEquals(1, journal.samples().size)
            journal.writePrepared(journal.prepared())
            assertFalse(File(path, "migration.complete").exists())
            val reopened = PipelineBenchmarkJournal(path, legacy, now = { time }, maxRecords = 2)
            assertEquals(2, reopened.samples().size)
            reopened.writePrepared(reopened.prepared())
            assertTrue(File(path, "migration.complete").exists())
            assertEquals(2, legacy.read().samples.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedStorageDoesNotLoseInMemorySamples() {
        val dir = Files.createTempDirectory("journal-failure").toFile()
        try {
            val blocked = File(dir, "file").apply { writeText("block") }
            val journal = PipelineBenchmarkJournal(blocked, now = { time })
            assertTrue(journal.append(sample("one")))
            assertTrue(runCatching { journal.writePrepared(journal.prepared()) }.isFailure)
            assertEquals(1, journal.samples().size)
        } finally { dir.deleteRecursively() }
    }
}
