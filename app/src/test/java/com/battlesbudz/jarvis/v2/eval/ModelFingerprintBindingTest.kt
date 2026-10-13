package com.battlesbudz.jarvis.v2.eval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for the score-to-file binding Jerry's round-6 review
 * requires: [ModelStore.modelFingerprint] must reflect the CURRENT model
 * file's validity — never a stale cached digest — and the UI's score lookup
 * ([ReliabilityReportStore.loadCurrent] with that fingerprint, the exact call
 * the model catalog and model details make) must hide the score when the
 * weights are removed, replaced, or fail integrity verification.
 */
@RunWith(RobolectricTestRunner::class)
class ModelFingerprintBindingTest {

    private fun spec(id: String) = LocalModelSpec(
        id = id,
        fileName = "$id.litertlm",
        recommendedGpu = false,
        supportsTools = true
    )

    private fun freshStore(): ModelStore =
        ModelStore(ApplicationProvider.getApplicationContext<Context>())

    /** Writes [bytes] as the installed model file and pins its integrity. */
    private fun installAndPin(store: ModelStore, spec: LocalModelSpec, bytes: ByteArray): String {
        val file = store.fileFor(spec)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        assertTrue("test fixture must verify", store.verifyIntegrity(spec))
        return checkNotNull(store.modelFingerprint(spec)) { "pinned file must have a fingerprint" }
    }

    /** The exact lookup the model catalog / model details UI performs. */
    private fun uiLookup(
        reportStore: ReliabilityReportStore,
        modelStore: ModelStore,
        spec: LocalModelSpec
    ): StoredReliabilityReport? = reportStore.loadCurrent(
        spec.id,
        modelStore.modelFingerprint(spec),
        ToolReliabilityFixtures.SUITE_VERSION
    )

    private fun saveBoundScore(
        reportStore: ReliabilityReportStore,
        spec: LocalModelSpec,
        fingerprint: String
    ) {
        reportStore.save(
            ModelReport(
                modelId = spec.id,
                caseResults = emptyList(),
                toolScores = emptyList(),
                modelFingerprint = fingerprint
            )
        )
    }

    @Test fun fingerprintReturnedForValidFile() {
        val store = freshStore()
        val spec = spec("fp-valid")
        try {
            val pinned = installAndPin(store, spec, byteArrayOf(1, 2, 3))
            assertNotNull(pinned)
        } finally {
            store.fileFor(spec).delete()
        }
    }

    @Test fun fingerprintNullAfterFileDeleted() {
        val store = freshStore()
        val spec = spec("fp-deleted")
        installAndPin(store, spec, byteArrayOf(1, 2, 3))
        assertTrue(store.fileFor(spec).delete())
        assertNull("deleted weights must not keep a fingerprint", store.modelFingerprint(spec))
    }

    @Test fun fingerprintNullAfterFileReplaced() {
        val store = freshStore()
        val spec = spec("fp-replaced")
        try {
            installAndPin(store, spec, byteArrayOf(1, 2, 3))
            // Replacement: different bytes and length, as a re-download would leave.
            store.fileFor(spec).writeBytes(byteArrayOf(9, 8, 7, 6, 5))
            assertNull("replaced weights must not keep the old fingerprint", store.modelFingerprint(spec))
        } finally {
            store.fileFor(spec).delete()
        }
    }

    @Test fun fingerprintNullAfterIntegrityFailure() {
        val store = freshStore()
        val spec = spec("fp-invalid")
        try {
            installAndPin(store, spec, byteArrayOf(1, 2, 3))
            store.fileFor(spec).writeBytes(byteArrayOf(9, 9, 9))
            assertFalse("tampered file must fail verification", store.verifyIntegrity(spec))
            assertNull(
                "integrity failure must clear the visible fingerprint",
                store.modelFingerprint(spec)
            )
        } finally {
            store.fileFor(spec).delete()
        }
    }

    @Test fun uiLookupShowsScoreWhileFileValid() {
        val modelStore = freshStore()
        val reportStore = InMemoryReliabilityReportStore()
        val spec = spec("fp-ui-valid")
        try {
            val pinned = installAndPin(modelStore, spec, byteArrayOf(1, 2, 3))
            saveBoundScore(reportStore, spec, pinned)
            assertNotNull("valid file keeps its bound score visible", uiLookup(reportStore, modelStore, spec))
        } finally {
            modelStore.fileFor(spec).delete()
        }
    }

    @Test fun uiLookupHidesScoreAfterRemoval() {
        val modelStore = freshStore()
        val reportStore = InMemoryReliabilityReportStore()
        val spec = spec("fp-ui-removed")
        val pinned = installAndPin(modelStore, spec, byteArrayOf(1, 2, 3))
        saveBoundScore(reportStore, spec, pinned)
        assertTrue(modelStore.fileFor(spec).delete())
        assertNull("removed weights must hide the score in the UI", uiLookup(reportStore, modelStore, spec))
    }

    @Test fun uiLookupHidesScoreAfterReplacement() {
        val modelStore = freshStore()
        val reportStore = InMemoryReliabilityReportStore()
        val spec = spec("fp-ui-replaced")
        try {
            val pinned = installAndPin(modelStore, spec, byteArrayOf(1, 2, 3))
            saveBoundScore(reportStore, spec, pinned)
            modelStore.fileFor(spec).writeBytes(byteArrayOf(7, 7, 7, 7))
            assertNull(
                "replaced weights must hide the old score in the UI",
                uiLookup(reportStore, modelStore, spec)
            )
        } finally {
            modelStore.fileFor(spec).delete()
        }
    }

    @Test fun uiLookupHidesScoreAfterIntegrityFailure() {
        val modelStore = freshStore()
        val reportStore = InMemoryReliabilityReportStore()
        val spec = spec("fp-ui-invalid")
        try {
            val pinned = installAndPin(modelStore, spec, byteArrayOf(1, 2, 3))
            saveBoundScore(reportStore, spec, pinned)
            modelStore.fileFor(spec).writeBytes(byteArrayOf(5, 5, 5))
            assertFalse(modelStore.verifyIntegrity(spec))
            assertNull(
                "integrity failure must hide the score in the UI",
                uiLookup(reportStore, modelStore, spec)
            )
        } finally {
            modelStore.fileFor(spec).delete()
        }
    }
}
