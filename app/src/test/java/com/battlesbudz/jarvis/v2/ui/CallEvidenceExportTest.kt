package com.battlesbudz.jarvis.v2.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallEvidenceExportTest {
    @get:Rule val compose = createComposeRule()
    private var launches = 0
    private var exported: CallEvidenceSnapshot? = null
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) { launches++ }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    @Before fun mount() {
        LiveCallAudioEvidence.clear()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        CallEvidenceExport(CallEvidenceActions(
                            armAudio = LiveCallAudioEvidence::arm,
                            clearAudio = LiveCallAudioEvidence::clear,
                            snapshot = { CallEvidenceSnapshot("Call report", LiveCallAudioEvidence.snapshot()).also { exported = it } }
                        ), enabled = true)
                    }
                }
            }
        }
    }
    @After fun cleanup() { LiveCallAudioEvidence.clear() }

    @Test fun audioNeedsExplicitOptInAndTheClearControlErasesAndDisarms() {
        assertFalse(LiveCallAudioEvidence.armed)
        assertNull(LiveCallAudioEvidence.snapshot())
        compose.onNodeWithText("Record next call audio for diagnosis").performScrollTo().performClick()
        assertTrue(LiveCallAudioEvidence.armed)
        assertFalse(LiveCallAudioEvidence.active)
        LiveCallAudioEvidence.begin("test-call")
        LiveCallAudioEvidence.record("microphone", byteArrayOf(1, 2), 16000)
        LiveCallAudioEvidence.finish()
        assertNotNull(LiveCallAudioEvidence.snapshot())
        compose.onNodeWithText("Clear diagnostic audio").performScrollTo().performClick()
        assertFalse(LiveCallAudioEvidence.armed)
        assertFalse(LiveCallAudioEvidence.active)
        assertNull(LiveCallAudioEvidence.snapshot())
        assertEquals(0, launches)
    }

    @Test fun exportingWithoutOptInKeepsAReportOnlyAndDoesNotStartRecording() {
        LiveCallAudioEvidence.begin("unrecorded-call")
        LiveCallAudioEvidence.record("microphone", byteArrayOf(1, 2), 16000)
        LiveCallAudioEvidence.finish()
        compose.onNodeWithText("Save latest call test ZIP").performScrollTo().performClick()
        assertEquals(1, launches)
        assertEquals("Call report", exported!!.report)
        assertNull(exported!!.audio)
        assertFalse(LiveCallAudioEvidence.armed)
        assertFalse(LiveCallAudioEvidence.active)
    }
}
