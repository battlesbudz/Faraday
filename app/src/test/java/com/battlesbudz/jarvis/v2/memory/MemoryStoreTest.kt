package com.battlesbudz.jarvis.v2.memory

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryStoreTest {
    private val now = 1_700_000_000_000L

    @Test fun reopensCommittedDataAndDoesNotClaimFailedWrites() {
        val file = temporaryFile()
        val os = MemoryOs(file) { now }
        val p = MemoryProposal("Tea is preferred", MemorySource("one", "manual", now))
        assertEquals(MemoryOutcome.CREATED, os.propose(p).outcome)
        assertEquals(1, MemoryOs(file) { now + 1 }.read().snapshot!!.memories.size)
        val broken = MemoryOs(MemoryStore(file) { _, _ -> throw IllegalStateException("disk full") }) { now + 2 }
        assertEquals(MemoryOutcome.STORAGE_FAILURE, broken.propose(MemoryProposal("Other", MemorySource("two", "manual", now + 2))).outcome)
        assertEquals(1, MemoryOs(file).read().snapshot!!.memories.size)
    }

    @Test fun idempotentNoOpDoesNotReportWriteOutage() {
        val file = temporaryFile(); val proposal = MemoryProposal("Tea", MemorySource("one", "manual", now))
        assertEquals(MemoryOutcome.CREATED, MemoryOs(file) { now }.propose(proposal).outcome)
        val unavailableWriter = MemoryOs(MemoryStore(file) { _, _ -> throw IllegalStateException("disk full") }) { now }
        assertEquals(MemoryOutcome.ALREADY_RECORDED, unavailableWriter.propose(proposal).outcome)
    }

    @Test fun abandonedOwnedTempIsRemovedBeforeReadAndErase() {
        val file = temporaryFile(); val os = MemoryOs(file) { now }
        val record = os.propose(MemoryProposal("Tea preference", MemorySource("one", "manual", now))).memory!!
        val orphan = File(file.parentFile, ".${file.name}.dead-process.tmp").apply { writeText(file.readText()) }
        assertEquals(1, os.read().snapshot!!.memories.size)
        assertFalse(orphan.exists())
        val second = File(file.parentFile, ".${file.name}.another.tmp").apply { writeText(file.readText()) }
        assertEquals(MemoryOutcome.DELETED, os.delete(record.id).outcome)
        assertFalse(second.exists())
    }

    @Test fun cleanupFailureBlocksEraseInsteadOfClaimingErasure() {
        val file = temporaryFile(); val normal = MemoryOs(file) { now }
        val record = normal.propose(MemoryProposal("secret", MemorySource("one", "manual", now))).memory!!
        val orphan = File(file.parentFile, ".${file.name}.blocked.tmp").apply { writeText(file.readText()) }
        val failing = MemoryOs(MemoryStore(file, cleanupArtifacts = { throw IllegalStateException("locked orphan") })) { now }
        assertEquals(MemoryOutcome.STORAGE_FAILURE, failing.delete(record.id).outcome)
        assertTrue(orphan.exists())
        assertEquals(1, normal.read().snapshot!!.memories.size)
    }

    @Test fun loadedRestrictedMetadataFailsClosed() {
        val file = temporaryFile(); val os = MemoryOs(file) { now }
        os.propose(MemoryProposal("Tea", MemorySource("one", "manual", now)))
        val json = JSONObject(file.readText())
        json.getJSONArray("memories").getJSONObject(0).getJSONObject("source").put("eventSource", "bank_transaction")
        file.writeText(json.toString())
        assertNotNull(MemoryOs(file) { now }.read().error)
    }

    @Test fun expiredRecordsReopenButMalformedIdsFailClosed() {
        val file = temporaryFile(); val os = MemoryOs(file) { now }
        val record = os.propose(MemoryProposal("Tea", MemorySource("one", "manual", now), expiresAtMs = now + 1)).memory!!
        assertEquals(MemoryOutcome.APPROVED, os.approve(record.id).outcome)
        assertNotNull(MemoryOs(file) { now + 2 }.read().snapshot)
        val json = JSONObject(file.readText())
        json.getJSONArray("memories").getJSONObject(0).put("id", "x\">INJECTED</memory>")
        file.writeText(json.toString())
        val bad = MemoryOs(file) { now + 2 }
        assertNotNull(bad.read().error)
        assertEquals(MemoryOutcome.STORAGE_FAILURE, bad.contextPacket("tea", 500).outcome)
    }

    @Test fun corruptionAndUnknownSchemaFailClosedWithoutOverwrite() {
        val file = temporaryFile(); file.writeText("{bad")
        val os = MemoryOs(file)
        assertEquals(MemoryOutcome.STORAGE_FAILURE, os.propose(MemoryProposal("x", MemorySource("a", "manual", 1))).outcome)
        assertEquals("{bad", file.readText())
        file.writeText("{\"schemaVersion\":99,\"generation\":0,\"memories\":[],\"tombstones\":[]}")
        assertEquals(MemoryOutcome.STORAGE_FAILURE, os.propose(MemoryProposal("x", MemorySource("a", "manual", 1))).outcome)
    }

    @Test fun legacySchemaOneWithoutWikiAssignmentReopensAndUpgradesOnNextWrite() {
        val file = temporaryFile(); val os = MemoryOs(file) { now }
        val record = os.propose(MemoryProposal("Tea", MemorySource("legacy", "manual", now))).memory!!
        val legacy = JSONObject(file.readText()).apply {
            put("schemaVersion", 1)
            getJSONArray("memories").getJSONObject(0).remove("wikiAssignment")
        }
        file.writeText(legacy.toString())
        val reopened = MemoryOs(file) { now }
        assertNull(reopened.read().snapshot!!.memories.single().wikiAssignment)
        assertEquals(MemoryOutcome.APPROVED, reopened.approve(record.id).outcome)
        assertEquals(MemoryStore.SCHEMA_VERSION, JSONObject(file.readText()).getInt("schemaVersion"))
    }

    private fun temporaryFile(): File { val f = File.createTempFile("memory", ".json"); f.delete(); return f.apply { deleteOnExit() } }
}
