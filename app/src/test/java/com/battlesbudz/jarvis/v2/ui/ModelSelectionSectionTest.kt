package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reliability check's target list must refresh when a download or import
 * completes; otherwise newly installed models are silently skipped.
 */
@RunWith(RobolectricTestRunner::class)
class ModelSelectionSectionTest {

    @get:Rule val rule = createComposeRule()

    private fun spec(id: String, tools: Boolean) = LocalModelSpec(
        id = id,
        fileName = "$id.litertlm",
        recommendedGpu = false,
        supportsTools = tools
    )

    @Test fun installedListIsPureFunctionOfInstallationState() {
        val a = spec("A", tools = true)
        val b = spec("B", tools = true)
        val c = spec("C", tools = false)
        val installed = mutableSetOf("A")
        assertEquals(listOf(a), toolCapableInstalledSpecs(listOf(a, b, c)) { it.id in installed })
        // A download/import completion event flips installation state.
        installed += "B"
        assertEquals(listOf(a, b), toolCapableInstalledSpecs(listOf(a, b, c)) { it.id in installed })
    }

    @Test fun installedListRefreshesOnDownloadCompletion() {
        refreshesOnCompletionEvent(
            beginEvent = { state -> state.downloadingModelId = "downloading" },
            endEvent = { state -> state.downloadingModelId = null }
        )
    }

    @Test fun installedListRefreshesOnImportCompletion() {
        refreshesOnCompletionEvent(
            beginEvent = { state -> state.modelImportRunning = true },
            endEvent = { state -> state.modelImportRunning = false }
        )
    }

    /**
     * Fires the same observable transitions the production WorkManager/import
     * observers fire on completion, and asserts the remembered installed list
     * picks up the newly installed model. With the old remember keys (no
     * download/import observables) the list stays stale and this fails.
     */
    private fun refreshesOnCompletionEvent(
        beginEvent: (ModelSetupState) -> Unit,
        endEvent: (ModelSetupState) -> Unit
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ModelStore(context)
        val target = ModelCatalog.all.first { it.supportsTools }
        val state = ModelSetupState(
            store,
            mutableStateOf(false),
            mutableStateOf("")
        ) { error("no actions in test") }
        var listed by mutableStateOf<List<LocalModelSpec>?>(null)
        rule.setContent {
            listed = rememberToolCapableInstalled(state, storageRevision = 0, reliabilityRevision = 0)
        }
        rule.waitForIdle()
        beginEvent(state)
        rule.waitForIdle()
        // The model file appears as a completed download/import would leave it.
        val file = store.fileFor(target)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1))
        try {
            endEvent(state)
            rule.waitForIdle()
            assertTrue(
                "newly installed model must appear after the completion event",
                listed.orEmpty().any { it.id == target.id }
            )
        } finally {
            file.delete()
        }
    }
}
