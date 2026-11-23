package com.battlesbudz.jarvis.v2.privilege

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class AdbIdentityRecordTest {
    @Test fun versionedIdentityRoundTripsKeysAndPeerInfoWithoutPairingCode() {
        val file = File.createTempFile("jarvis-adb-identity", ".properties").apply { delete() }
        try {
            val value = AdbIdentityRecord.Value(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), "peer-guid")
            AdbIdentityRecord.save(file, value)
            val loaded = AdbIdentityRecord.load(file, previouslyInitialized = true)!!
            assertArrayEquals(value.publicKey, loaded.publicKey)
            assertArrayEquals(value.privateKey, loaded.privateKey)
            assertEquals("peer-guid", loaded.peerInfo)
            assertEquals(false, file.readText().contains("123456"))
        } finally { file.delete() }
    }

    @Test fun missingCorruptAndUnknownVersionArePreciseBlockedIdentityStates() {
        val file = File.createTempFile("jarvis-adb-identity", ".properties")
        try {
            file.delete()
            assertNull(AdbIdentityRecord.load(file, previouslyInitialized = false))
            try { AdbIdentityRecord.load(file, previouslyInitialized = true); fail("missing identity must block") }
            catch (e: IllegalStateException) { assertEquals("stored ADB identity file is missing", e.message) }
            file.writeText("version=9\npublic=AA==\nprivate=AA==\n")
            try { AdbIdentityRecord.load(file, previouslyInitialized = true); fail("version mismatch must block") }
            catch (e: IllegalArgumentException) { assertEquals("unsupported identity format version: version=9", e.message) }
            file.writeText("version=1\npublic=not-base64\nprivate=AA==\n")
            try { AdbIdentityRecord.load(file, previouslyInitialized = true); fail("corrupt identity must block") }
            catch (_: IllegalArgumentException) { }
        } finally { file.delete() }
    }
}
