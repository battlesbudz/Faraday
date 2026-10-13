package com.battlesbudz.jarvis.v2.ai.storage

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
