package com.battlesbudz.jarvis.v2.sandbox

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import io.github.sylirre.terminal.term.UserlandDistro
import io.github.sylirre.terminal.term.UserlandRootfs
import java.io.File
import java.io.IOException

/**
 * Owns the sandbox directory layout and the rootfs install (Phase 1 scope
 * §3, §10). Layout under `files/sandbox/`:
 *
 * - `rootfs` — the extracted Alpine tree, bound as the guest's `/`
 *   (path owned by the vendored [UserlandRootfs]).
 * - `rootfs.tmp` — install staging; the vendored installer extracts there
 *   and renames onto `rootfs` atomically.
 * - `workspace` — persistent user files, bound as the guest's `/workspace`.
 *   Survives a rootfs Reset (decision D2); emptied only by the separate
 *   Clear workspace action.
 * - `tmp` — the engine's TMPDIR, mode 0700 (vendored TmpDir).
 * - `state` — the manager's own small state files.
 *
 * There are no rootfs updates in Phase 1 (decision D1): a new rootfs ships
 * only with a new app build, and an existing install is never replaced
 * behind the user's back — Reset is the only path that removes it, and it
 * is user-triggered from settings.
 */
class SandboxStore(private val context: Context) {

    val rootDir: File = File(context.filesDir, "sandbox")
    val workspaceDir: File = File(rootDir, "workspace")
    val stateDir: File = File(rootDir, "state")

    /** The installed rootfs directory (guest `/`), per the vendored layout. */
    fun rootfsDir(): File = UserlandRootfs.dir(context)

    /** A rootfs tree is present (an install completed here at some point). */
    fun isInstalled(): Boolean = UserlandRootfs.isInstalled(context)

    /** A rootfs is present and still has a shell to run. */
    fun isUsable(): Boolean = UserlandRootfs.isUsable(context)

    /** Version stamp written at install time, or null when unstamped. */
    fun installedVersion(): String? = UserlandRootfs.installedVersion(context)

    /** Creates the directories the sandbox owns outright (not rootfs/tmp). */
    fun ensureLayout() {
        workspaceDir.mkdirs()
        stateDir.mkdirs()
    }

    sealed interface InstallResult {
        /** A usable rootfs was already installed; nothing was extracted. */
        data object AlreadyInstalled : InstallResult

        /** The bundled rootfs was extracted and stamped. */
        data object Installed : InstallResult

        /** Install did not happen; [message] is user-presentable. */
        data class Failed(val message: String) : InstallResult
    }

    /**
     * Installs the bundled Alpine rootfs if none is installed. The space
     * pre-check runs before the first byte is extracted (scope §3): the
     * install needs room for the staging tree plus working headroom, and a
     * shortfall is a typed failure here, never a half-extracted tree.
     * Blocking — call off the main thread.
     */
    fun installIfNeeded(
        networkingEnabled: Boolean,
        onProgress: (bytesExtracted: Long, compressedRead: Long, compressedTotal: Long) -> Unit = { _, _, _ -> }
    ): InstallResult {
        if (isInstalled()) return InstallResult.AlreadyInstalled
        val distro = UserlandDistro.bundled(context).firstOrNull()
            ?: return InstallResult.Failed("This build bundles no sandbox rootfs.")
        val available = availableBytes()
        if (available in 0 until REQUIRED_FREE_BYTES) {
            return InstallResult.Failed(
                "Not enough free space to install the sandbox: it needs about " +
                    "${REQUIRED_FREE_BYTES / (1024 * 1024)} MB free and the phone has " +
                    "${available / (1024 * 1024)} MB available."
            )
        }
        return try {
            UserlandRootfs.install(context, distro.assetName, { extracted, read, total ->
                onProgress(extracted, read, total)
            }, networkingEnabled)
            if (isUsable()) InstallResult.Installed
            else InstallResult.Failed("The sandbox rootfs installed but has no usable shell.")
        } catch (e: IOException) {
            InstallResult.Failed("Sandbox install failed: ${e.message ?: "unknown error"}")
        }
    }

    /** Bytes the system can still allocate on the app's files volume. */
    fun availableBytes(): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (e: Exception) {
        -1L
    }

    /**
     * Reset (decision D2): removes the rootfs and any install staging only.
     * The workspace is deliberately untouched — it survives and is re-bound
     * into the fresh rootfs on the next install.
     */
    fun resetRootfs() {
        deleteTree(rootfsDir())
        deleteTree(File(rootDir, "rootfs.tmp"))
    }

    /** Clear workspace (decision D2): empties /workspace, keeps the rootfs. */
    fun clearWorkspace() {
        deleteTree(workspaceDir)
        workspaceDir.mkdirs()
    }

    /** Total bytes under files/sandbox — the settings storage line. */
    fun storageBytes(): Long = treeBytes(rootDir)

    private fun treeBytes(file: File): Long {
        if (isSymlink(file)) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) total += treeBytes(child)
        return total
    }

    /**
     * Recursive delete that never follows symlinks (the rootfs is full of
     * them; the link is deleted, never its target) — the same rule the
     * vendored installer's delete applies.
     */
    private fun deleteTree(file: File) {
        if (isSymlink(file) || file.isFile) {
            file.delete()
            return
        }
        val children = file.listFiles()
        if (children != null) {
            for (child in children) deleteTree(child)
        }
        file.delete()
    }

    private fun isSymlink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    } catch (e: Exception) {
        false
    }

    companion object {
        /**
         * Free space demanded before an install starts: the bundled asset
         * is ~3 MB compressed / ~15 MB extracted into staging, and the
         * guest needs working room (package cache, /tmp) from its first
         * run. 96 MB covers the extraction several times over with
         * headroom, and failing early beats a half-written tree.
         */
        const val REQUIRED_FREE_BYTES: Long = 96L * 1024 * 1024
    }
}
