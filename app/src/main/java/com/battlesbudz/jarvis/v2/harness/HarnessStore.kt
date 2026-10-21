package com.battlesbudz.jarvis.v2.harness

import java.io.File

/**
 * Loads the effective [HarnessConfig] from versioned harness files
 * (self-improving harness project, Phase 1).
 *
 * Layout under [rootDir] (app-private storage, `filesDir/harness`):
 *  - `v<N>/` — one immutable directory per harness version, holding the
 *    files named by [HarnessConfigParser.FILES];
 *  - `CURRENT` — a small pointer file naming the live version ("v1").
 *
 * Promotion and rollback in later phases are pointer swaps; Phase 1 only
 * reads. With no pointer (the normal case until the loop exists), the
 * shipped bundled files are used — they ship in assets as
 * `assets/harness/v1/` and are byte-identical to the compiled-in defaults,
 * so a fresh install behaves exactly like the pre-harness app. Any file
 * that fails validation falls back per component (see
 * [HarnessConfigParser.resolve]). Nothing in here ever throws to a caller.
 */
class HarnessStore(
    private val rootDir: File,
    /** Reads one shipped bundled harness file by name, or null when absent. */
    private val bundled: (String) -> String?
) {
    @Volatile private var cached: HarnessConfig? = null
    @Volatile private var cachedPointer: String? = null
    @Volatile private var cacheLoaded = false

    fun current(): HarnessConfig {
        val pointer = readPointer()
        val hit = cached
        if (cacheLoaded && pointer == cachedPointer && hit != null) return hit
        val loaded = load(pointer)
        cached = loaded
        cachedPointer = pointer
        cacheLoaded = true
        return loaded
    }

    private fun readPointer(): String? = runCatching {
        val file = File(rootDir, POINTER_FILE)
        if (!file.isFile) null
        else file.readText().trim().takeIf { POINTER_NAME.matches(it) }
    }.getOrNull()

    private fun load(pointer: String?): HarnessConfig {
        val bundledFiles = HarnessConfigParser.FILES.associateWith { name ->
            runCatching { bundled(name) }.getOrNull()
        }
        val liveFiles = if (pointer == null) emptyMap() else HarnessConfigParser.FILES.associateWith { name ->
            runCatching {
                val file = File(File(rootDir, pointer), name)
                if (file.isFile) file.readText() else null
            }.getOrNull()
        }
        val version = pointer?.removePrefix("v")?.toIntOrNull() ?: BUNDLED_VERSION
        return HarnessConfigParser.resolve(version, liveFiles, bundledFiles)
    }

    companion object {
        const val POINTER_FILE = "CURRENT"
        const val BUNDLED_VERSION = 1
        private val POINTER_NAME = Regex("v[0-9]+")
    }
}
