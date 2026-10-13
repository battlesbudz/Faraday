package com.battlesbudz.jarvis.v2.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PipelineBenchmarkArchiveTest {
    private fun sample(id: String) = PipelineBenchmarkTurn(
        turnId = id, channel = "voice", capturedAtEpochMs = 1,
        provenance = PipelineBenchmarkProvenance("test", 1),
        outcome = PipelineBenchmarkOutcome.COMPLETE,
        accuracy = PipelineBenchmarkAccuracyEvaluator.evaluate("private expected words", "private actual words", "user_verified", retainText = true)
    )

    @Test fun boundedArchiveSurvivesReopenAndNeverRetainsAccuracyText() {
        val directory = Files.createTempDirectory("benchmark-archive").toFile()
        try {
            val file = File(directory, "archive.json")
            val archive = PipelineBenchmarkArchive(file, maxRecords = 3)
            val bounded = archive.bound((1..5).map { sample("turn-$it") })
            assertEquals(listOf("turn-3", "turn-4", "turn-5"), bounded.map { it.turnId })
            archive.write(bounded)
            val bytes = file.readText()
            assertFalse(bytes.contains("private expected words"))
            assertFalse(bytes.contains("private actual words"))
            val reopened = PipelineBenchmarkArchive(file, maxRecords = 3).read()
            assertNull(reopened.error)
            assertEquals(bounded, reopened.samples)
            assertNull(reopened.samples.last().accuracy?.reference)
            assertEquals(1, reopened.samples.last().accuracy?.wordErrors)
        } finally { directory.deleteRecursively() }
    }

    @Test fun byteBoundMeasuresUtf8AndEvictsOldestSamples() {
        val directory = Files.createTempDirectory("benchmark-archive-bytes").toFile()
        try {
            val archive = PipelineBenchmarkArchive(File(directory, "archive.json"), maxBytes = 4096)
            val samples = (1..5).map { sample("turn-$it").copy(provenance = PipelineBenchmarkProvenance("test", 1,
                configuration = mapOf("fixture" to "é".repeat(300)))) }
            val bounded = archive.bound(samples)
            assertTrue(bounded.isNotEmpty())
            assertTrue(bounded.size < samples.size)
            assertEquals("turn-5", bounded.last().turnId)
            archive.write(bounded)
            assertTrue(File(directory, "archive.json").length() <= 4096)
        } finally { directory.deleteRecursively() }
    }

    @Test fun resetIsPersistedAndAStalePendingFileCannotResurrectSamples() {
        val directory = Files.createTempDirectory("benchmark-archive-reset").toFile()
        try {
            val file = File(directory, "archive.json")
            val archive = PipelineBenchmarkArchive(file)
            archive.write(archive.bound(listOf(sample("one"))))
            File(directory, "archive.json.pending").writeText("stale unfinished data")
            assertEquals("one", archive.read().samples.single().turnId)
            archive.write(emptyList())
            assertTrue(archive.read().samples.isEmpty())
            assertFalse(File(directory, "archive.json.pending").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptArchiveIsReportedWithoutLeakingItsContent() {
        val directory = Files.createTempDirectory("benchmark-archive-corrupt").toFile()
        try {
            val file = File(directory, "archive.json")
            file.writeText("private invalid content")
            val loaded = PipelineBenchmarkArchive(file).read()
            assertTrue(loaded.samples.isEmpty())
            assertNotNull(loaded.error)
            assertFalse(loaded.error!!.contains("private invalid content"))
            assertTrue(File(directory, "archive.json.corrupt").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun incrementalReplacementAndAnnotationKeepExactUtf8Accounting() {
        val directory = Files.createTempDirectory("benchmark-buffer-accounting").toFile()
        try {
            val file = File(directory, "archive.json")
            val archive = PipelineBenchmarkArchive(file, maxRecords = 3, maxBytes = 8192)
            val buffer = PipelineBenchmarkBuffer(archive)
            for (i in 1..20) assertTrue(buffer.append(sample("turn-$i")))
            assertEquals(listOf("turn-18", "turn-19", "turn-20"), buffer.samples().map { it.turnId })
            assertTrue(buffer.append(sample("turn-19").copy(provenance = PipelineBenchmarkProvenance("test", 1,
                configuration = mapOf("unicode" to "é🍀".repeat(80))))))
            assertTrue(buffer.update("turn-19") { it.copy(environment = PipelineBenchmarkEnvironment.NOISY) })
            assertEquals(3, buffer.samples().size)
            assertEquals(PipelineBenchmarkEnvironment.NOISY, buffer.samples().single { it.turnId == "turn-19" }.environment)
            archive.writePrepared(buffer.prepared())
            assertEquals(file.length(), buffer.encodedBytes)
            assertEquals(buffer.samples(), archive.read().samples)
            buffer.clear()
            archive.writePrepared(buffer.prepared())
            assertEquals(file.length(), buffer.encodedBytes)
        } finally { directory.deleteRecursively() }
    }

    @Test fun oversizedNewRecordCannotDiscardValidRetainedHistory() {
        val directory = Files.createTempDirectory("benchmark-buffer-oversized").toFile()
        try {
            val archive = PipelineBenchmarkArchive(File(directory, "archive.json"), maxBytes = 4096)
            val valid = sample("valid")
            val buffer = PipelineBenchmarkBuffer(archive, listOf(valid))
            val oversized = sample("oversized").copy(provenance = PipelineBenchmarkProvenance("test", 1,
                configuration = mapOf("fixture" to "é".repeat(4000))))
            assertFalse(buffer.append(oversized))
            assertEquals(listOf("valid"), buffer.samples().map { it.turnId })
            assertTrue(buffer.encodedBytes <= archive.maxBytes)
            assertFalse(buffer.update("valid") { oversized.copy(turnId = "valid") })
            assertEquals(listOf("valid"), buffer.samples().map { it.turnId })
            archive.writePrepared(buffer.prepared())
            assertEquals(buffer.samples(), archive.read().samples)
        } finally { directory.deleteRecursively() }
    }
}
