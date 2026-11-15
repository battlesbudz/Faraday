package io.github.sylirre.terminal.term

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The mount-point creation half of the workspace work-dir fix (Codex P2
 * on PR #25): pure file work, no Android surface. The end-to-end half
 * (resolution through the real command builder) is
 * [UserlandRootfsExecWorkDirTest].
 */
class UserlandRootfsBindMountPointTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File

    @Before fun setUp() {
        root = tmp.newFolder("rootfs")
    }

    @Test fun createsTheWorkspaceMountPoint() {
        UserlandRootfs.ensureBindMountPoints(
            root, listOf("/host/files/sandbox/workspace:/workspace"))
        assertTrue(File(root, "workspace").isDirectory)
    }

    @Test fun createsNestedMountPoints() {
        UserlandRootfs.ensureBindMountPoints(root, listOf("/host/x:/data/projects"))
        assertTrue(File(root, "data/projects").isDirectory)
    }

    @Test fun malformedAndEscapingSpecsCreateNothing() {
        UserlandRootfs.ensureBindMountPoints(root, listOf(
            "/host/x:/../escape",        // escapes the guest root
            "/host/x:/a/../../escape2",  // normalizes to an escape
            "/host/x:relative",          // guest path not absolute
            "/host/x:",                  // empty guest path
            "no-separator",              // no host:guest split
            "/host/x:/"))                // the rootfs root itself
        assertTrue(root.listFiles()!!.isEmpty())
        assertFalse(File(tmp.root, "escape").exists())
        assertFalse(File(tmp.root, "escape2").exists())
    }

    @Test fun existingTargetsAreLeftUntouched() {
        val asFile = File(root, "workspace")
        asFile.writeText("not a dir")
        val dir = File(root, "data")
        dir.mkdirs()
        File(dir, "keep.txt").writeText("x")
        UserlandRootfs.ensureBindMountPoints(root, listOf("/h:/workspace", "/h:/data"))
        assertTrue(asFile.isFile)
        assertEquals("not a dir", asFile.readText())
        assertEquals("x", File(dir, "keep.txt").readText())
    }

    @Test fun symlinkEscapeIsNotFollowed() {
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        UserlandRootfs.ensureBindMountPoints(root, listOf("/h:/link/ws"))
        assertFalse(File(outside, "ws").exists())
    }

    @Test fun nullAndEmptyBindListsAreNoOps() {
        UserlandRootfs.ensureBindMountPoints(root, null)
        UserlandRootfs.ensureBindMountPoints(root, emptyList())
        assertTrue(root.listFiles()!!.isEmpty())
    }
}

/**
 * End-to-end half of the workspace work-dir fix (Codex P2 on PR #25):
 * drives the real command builder against a minimal installed rootfs
 * and pins the resolved `--work-dir`. Before the fix, the exec run
 * below resolved to the identity home inside the rootfs because
 * /workspace — a host directory bound at runtime — did not exist in
 * the rootfs tree for the resolver to validate.
 */
@RunWith(RobolectricTestRunner::class)
class UserlandRootfsExecWorkDirTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var rootfs: File
    private lateinit var workspaceDir: File

    @Before fun setUp() {
        File(context.filesDir, "sandbox").deleteRecursively()
        rootfs = UserlandRootfs.dir(context)
        // Minimal installed tree: a shell, a passwd whose root entry
        // homes at /root, and that home directory.
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").createNewFile()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/sh\n")
        File(rootfs, "root").mkdirs()
        // The host side of the /workspace bind (SandboxStore's layout).
        workspaceDir = File(File(context.filesDir, "sandbox"), "workspace")
        workspaceDir.mkdirs()
    }

    private fun argAfter(argv: Array<String>, flag: String): String? {
        val idx = argv.indexOf(flag)
        return if (idx >= 0 && idx + 1 < argv.size) argv[idx + 1] else null
    }

    private fun execCommand(workDirOverride: String?): SessionCommand =
        UserlandRootfs.commandExec(
            context, UserlandOptions.defaults("/bin/sh"),
            listOf("/bin/sh", "-c", "true"),
            listOf("${workspaceDir.absolutePath}:/workspace"),
            workDirOverride, "sandbox-run")

    @Test fun execRunWorkDirResolvesToTheBoundWorkspace() {
        val command = execCommand("/workspace")
        assertEquals("/workspace", argAfter(command.argv, "--work-dir"))
        assertEquals("${workspaceDir.absolutePath}:/workspace",
            argAfter(command.argv, "--bind"))
        // The mount point now exists in the rootfs for the bind to cover,
        // and the host cwd mirrors it as before.
        assertTrue(File(rootfs, "workspace").isDirectory)
        assertEquals(File(rootfs, "workspace").absolutePath, command.cwd)
    }

    @Test fun execRunWorkDirStillFallsBackForAMissingDirectory() {
        val command = execCommand("/no/such/place")
        assertEquals("/root", argAfter(command.argv, "--work-dir"))
    }

    @Test fun loginCommandWithoutBindsKeepsHomeWorkDirAndCreatesNothing() {
        val command = UserlandRootfs.command(context, UserlandOptions.defaults("/bin/sh"))
        assertEquals("/root", argAfter(command.argv, "--work-dir"))
        assertFalse(File(rootfs, "workspace").exists())
    }
}
