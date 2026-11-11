package com.battlesbudz.jarvis.v2.code

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fingerprint stability and sensitivity (scope section 3.5). */
class ProjectFingerprintTest {

    @Rule @JvmField val tmp = TemporaryFolder()

    private lateinit var store: CodeProjectStore
    private lateinit var project: CodeProjectStore.CodeProject
    private lateinit var dir: File

    @Before fun setUp() {
        store = CodeProjectStore(File(tmp.root, "workspace"))
        project = store.createProject("Snake", "A snake game", nowMillis = 1_000)
        dir = store.projectDir(project.id)
    }

    private fun fingerprint(): String = ProjectFingerprint.compute(dir)

    @Test fun fingerprintIsSha256Hex() {
        assertTrue(fingerprint().matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun sameBytesSameFingerprint() {
        assertEquals(fingerprint(), fingerprint())
    }

    @Test fun oneByteChangeChangesTheFingerprint() {
        val before = fingerprint()
        File(dir, "index.html").appendText(" ")
        assertNotEquals(before, fingerprint())
    }

    @Test fun briefChangeChangesTheFingerprint() {
        val before = fingerprint()
        File(dir, CodeProjectStore.BRIEF_FILE_NAME).appendText("\n")
        assertNotEquals(before, fingerprint())
    }

    @Test fun newFileChangesTheFingerprint() {
        val before = fingerprint()
        File(dir, "style.css").writeText("body {}")
        assertNotEquals(before, fingerprint())
    }

    @Test fun versionsAndProjectJsonAreExcluded() {
        val before = fingerprint()
        store.snapshotVersion(project)
        assertEquals("version snapshots are bookkeeping, not content", before, fingerprint())
        store.saveProject(project.copy(rounds = 9, updatedAtMillis = 42))
        assertEquals("project.json is machine state, not content", before, fingerprint())
    }
}
