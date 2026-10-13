package com.battlesbudz.jarvis.v2.ai

/** Native ownership is separate from transfers; only the same model's file conflicts. */
internal class ModelOperationGate {
    private var runtimeModel: String? = null
    private val downloads = mutableSetOf<String>()

    @Synchronized fun tryBeginRuntime(modelId: String): Boolean {
        if (runtimeModel != null || modelId in downloads) return false
        runtimeModel = modelId
        return true
    }
    @Synchronized fun endRuntime() { runtimeModel = null }
    @Synchronized fun runtimeActive(): Boolean = runtimeModel != null
    @Synchronized fun downloading(modelId: String): Boolean = modelId in downloads
    @Synchronized fun tryBeginDownload(modelId: String, selectedId: String, chatActive: Boolean): Boolean {
        if (modelId in downloads || runtimeModel == modelId || (modelId == selectedId && chatActive)) return false
        downloads.add(modelId)
        return true
    }
    @Synchronized fun endDownload(modelId: String) { downloads.remove(modelId) }
}
