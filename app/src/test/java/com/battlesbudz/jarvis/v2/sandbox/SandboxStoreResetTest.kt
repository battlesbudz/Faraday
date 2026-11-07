package com.battlesbudz.jarvis.v2.sandbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.sylirre.terminal.term.UserlandRootfs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Decision D2, the contract the workspace work-dir fix (Codex P2 on
 * PR #25) exists to serve: Reset removes the rootfs only, and the
 * persistent workspace — where sandbox work products live — survives
 * untouched.
 */
@RunWith(RobolectricTestRunner::class)
class SandboxStoreResetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var store: SandboxStore
    private lateinit var rootfs: File

    @Before fun setUp() {
        File(context.filesDir, "sandbox").deleteRecursively()
        store = SandboxStore(context)
        store.ensureLayout()
        rootfs = UserlandRootfs.dir(context)
        rootfs.mkdirs()
        File(rootfs, "guest-marker.txt").writeText("guest state")
        File(store.workspaceDir, "keep.txt").writeText("work product")
    }

    @Test fun resetRemovesTheRootfsAndKeepsTheWorkspace() {
        store.resetRootfs()
        assertFalse("Reset must remove the rootfs tree", rootfs.exists())
        val kept = File(store.workspaceDir, "keep.txt")
        assertTrue("the workspace must survive Reset (decision D2)", kept.exists())
        assertEquals("work product", kept.readText())
        assertEquals(1, store.resetsCount())
    }
}
