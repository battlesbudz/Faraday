package com.battlesbudz.jarvis.v2.ai.storage

import com.battlesbudz.jarvis.v2.ai.ModelCompanionFile
import java.io.File

/** Only the selected model's app-owned files; never the user's Downloads folder. */
internal fun modelFiles(directory: File, fileName: String): List<File> =
    directory.listFiles().orEmpty().filter {
        it.isFile && (it.name == fileName ||
            (it.name.startsWith("$fileName.") && it.name.endsWith(".part")))
    }

internal fun removeModelFiles(directory: File, fileName: String, cache: File) {
    modelFiles(directory, fileName).forEach {
        check(it.delete()) { "Could not delete ${it.name}. Please try again." }
    }
    val chunks = File(directory, "$fileName.part.chunks")
    check(!chunks.exists() || chunks.deleteRecursively()) {
        "Could not remove the model download checkpoints. Please try again."
    }
    check(!cache.exists() || cache.deleteRecursively()) {
        "Could not remove the model cache. Please try again."
    }
}

/**
 * Only the resumable state of an unfinished download: the partial file and
 * its chunk checkpoints. An installed model file, its compiled cache, and
 * every other model's files stay exactly where they are. Idempotent — a
 * discard after a successful one, or with nothing saved, is harmless.
 */
internal fun removePartialDownloadFiles(directory: File, fileName: String) {
    val partial = File(directory, "$fileName.part")
    check(!partial.exists() || partial.delete()) {
        "Could not delete ${partial.name}. Please try again."
    }
    val chunks = File(directory, "$fileName.part.chunks")
    check(!chunks.exists() || chunks.deleteRecursively()) {
        "Could not remove the model download checkpoints. Please try again."
    }
}

/**
 * A companion file counts as present only as the exact pinned artifact:
 * the file exists with exactly the catalogued byte count. A missing or
 * truncated companion reads as absent, so a partial bundle is never
 * reported installed. The hash itself is [companionFileVerified]'s job;
 * presence is the cheap check install state is built on.
 */
internal fun companionFilePresent(file: File, companion: ModelCompanionFile): Boolean =
    file.isFile && file.length() == companion.downloadBytes

/**
 * Full integrity of one companion: present as the exact pinned artifact
 * and hashing to the catalogued SHA-256. Companions are download-only —
 * no import path could legitimately substitute different bytes — so the
 * catalog hash is always enforced here.
 */
internal fun companionFileVerified(file: File, companion: ModelCompanionFile): Boolean =
    companionFilePresent(file, companion) &&
        file.sha256().equals(companion.expectedSha256, ignoreCase = true)
