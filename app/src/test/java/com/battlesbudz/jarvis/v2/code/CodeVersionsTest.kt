package com.battlesbudz.jarvis.v2.code

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Version snapshots, pruning, and byte-exact rollback (scope section 3.4). */
class CodeVersionsTest {

    @Rule @JvmField val tmp = TemporaryFolder()

    private lateinit var store: CodeProjectStore
    private lateinit var project: CodeProjectStore.CodeProject
    private lateinit var dir: File

    @Before fun setUp() {
        store = CodeProjectStore(File(tmp.root, "workspace"))
        project = store.createProject("Snake", "A snake game", nowMillis = 1_000)
        dir = store.projectDir(project.id)
    }

    private fun versionFile(version: Int, name: String): File =
        File(File(File(dir, CodeProjectStore.VERSIONS_DIR_NAME), CodeProjectStore.versionDirName(version)), name)

    @Test fun snapshotCopiesContentAndBriefExactly() {
        File(dir, "sub").mkdirs()
        File(dir, "sub/inner.txt").writeText("inner")
        val indexBytes = File(dir, "index.html").readBytes()
        val briefBytes = File(dir, CodeProjectStore.BRIEF_FILE_NAME).readBytes()

        val version = store.snapshotVersion(project)

        assertEquals(1, version)
        assertEquals(listOf(1), store.listVersions(project.id))
        assertArrayEquals(indexBytes, versionFile(1, "index.html").readBytes())
        assertArrayEquals(briefBytes, versionFile(1, CodeProjectStore.BRIEF_FILE_NAME).readBytes())
        assertEquals("inner", versionFile(1, "sub/inner.txt").readText())
        assertFalse("versions never nest inside a version",
            versionFile(1, CodeProjectStore.VERSIONS_DIR_NAME).exists())
    }

    @Test fun rollbackRestoresBytesExactlyAndSnapshotsFirst() {
        val indexBytes = File(dir, "index.html").readBytes()
        val briefBytes = File(dir, CodeProjectStore.BRIEF_FILE_NAME).readBytes()
        store.snapshotVersion(project)

        File(dir, "index.html").writeText("CHANGED")
        File(dir, CodeProjectStore.BRIEF_FILE_NAME).writeText("TAMPERED")
        File(dir, "new.txt").writeText("added later")

        val restored = store.rollback(project, 1, nowMillis = 5_000)

        assertArrayEquals(indexBytes, File(dir, "index.html").readBytes())
        assertArrayEquals(briefBytes, File(dir, CodeProjectStore.BRIEF_FILE_NAME).readBytes())
        assertFalse("files added after the snapshot are removed", File(dir, "new.txt").exists())
        // The rollback snapshotted what it replaced, so it can be undone.
        assertEquals(listOf(1, 2), store.listVersions(project.id))
        assertEquals("CHANGED", versionFile(2, "index.html").readText())
        assertEquals(5_000L, restored.updatedAtMillis)
        assertEquals(1_000L, restored.createdAtMillis)
        assertEquals(restored, store.loadProject(project.id))
    }

    @Test fun pruneKeepsTheLastTenVersions() {
        repeat(12) { store.snapshotVersion(project) }
        assertEquals((3..12).toList(), store.listVersions(project.id))
    }

    @Test fun rollbackToAMissingVersionThrowsAndChangesNothing() {
        val indexBytes = File(dir, "index.html").readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            store.rollback(project, 7, nowMillis = 5_000)
        }
        assertTrue(store.listVersions(project.id).isEmpty())
        assertArrayEquals(indexBytes, File(dir, "index.html").readBytes())
    }
}
