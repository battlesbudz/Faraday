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
import org.robolectric.shadows.ShadowStatFs

/**
 * Decision D2, the contract the workspace work-dir fix (Codex P2 on
 * PR #25) exists to serve: Reset removes the rootfs only, and the
 * persistent workspace — where sandbox work products live — survives
 * untouched. The second test pins the preservation fix's composition
 * (what SandboxManager.reset performs): the removal plus an immediate
 * fresh install of the bundled rootfs, in place, so the sandbox is
 * usable again with no separate reinstall step.
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

    @Test fun resetAndReinstallRestoresAFreshUsableRootfsAndKeepsTheWorkspace() {
        // Robolectric reports zero free space unless stats are registered;
        // the install pre-check must see room (about 2 GB here).
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 500_000, 500_000)
        // The composition SandboxManager.reset performs: remove the rootfs
        // layer, then install the bundled rootfs fresh, in place.
        store.resetRootfs()
        val result = store.installIfNeeded(networkingEnabled = false)
        assertTrue(
            "the fresh install after Reset must succeed, was: $result",
            result is SandboxStore.InstallResult.Installed
        )
        // The rootfs layer really was reset: the old tree's guest state is
        // gone and the fresh tree carries the bundled version stamp.
        assertFalse(File(rootfs, "guest-marker.txt").exists())
        assertEquals(
            UserlandRootfs.BUNDLED_VERSION,
            store.installedVersion()?.lines()?.firstOrNull()
        )
        // The persistent store survived, still readable after Reset.
        val kept = File(store.workspaceDir, "keep.txt")
        assertTrue("the workspace must survive Reset (decision D2)", kept.exists())
        assertEquals("work product", kept.readText())
        // The runner's only install gate passes again (SandboxRunner
        // refuses a run when the store is not usable): a fresh store, as
        // the next run constructs one, sees an installed, usable sandbox —
        // commands run after Reset with no separate reinstall step.
        val nextRunStore = SandboxStore(context)
        assertTrue(nextRunStore.isInstalled())
        assertTrue(nextRunStore.isUsable())
    }
}
