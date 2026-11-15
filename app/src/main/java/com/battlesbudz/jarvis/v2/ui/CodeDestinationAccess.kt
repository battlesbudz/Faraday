package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.code.CodeEngineAccess
import com.battlesbudz.jarvis.v2.code.CodeProjectStore
import com.battlesbudz.jarvis.v2.code.CodeStatsStore

/**
 * The Code destination's narrow dependencies (code-and-export
 * Unit 6), built at the composition boundary
 * (`WithPipelineDiagnostics`) from the process runtime and handed
 * into `JarvisApp` through its content lambda — the same injection
 * shape as the pipeline benchmark store, and the reason no UI file
 * reaches for the runtime itself. Each value resolves lazily, so
 * composing the app (including the release journeys' fixtures,
 * which never open the destination) builds nothing.
 */
internal class CodeDestinationAccess(
    storeProvider: () -> CodeProjectStore,
    statsStoreProvider: () -> CodeStatsStore,
    engineAccessProvider: () -> CodeEngineAccess,
) {
    val store: CodeProjectStore by lazy(storeProvider)
    val statsStore: CodeStatsStore by lazy(statsStoreProvider)
    val engineAccess: CodeEngineAccess by lazy(engineAccessProvider)
}
