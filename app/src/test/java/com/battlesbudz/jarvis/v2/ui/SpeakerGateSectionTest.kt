package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import com.battlesbudz.jarvis.v2.voice.speaker.*
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeakerGateSectionTest {
    @get:Rule val compose = createComposeRule()

    private var requestedPermission: String? = null
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) { requestedPermission = input as? String }
        }
    }

    private fun controller(): SpeakerEnrollmentController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SpeakerEnrollmentController(context, recorder = EnrollmentRecorder { floatArrayOf(0f) },
            embedderFactory = { SpeakerEmbedder { _, _ -> floatArrayOf(1f) } }).also { it.deleteProfile() }
    }

    @Test fun notEnrolledAndEnrolledStatesRenderStatusAndControls() {
        val enrollment = controller()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                MaterialTheme { SpeakerGateSection(enabled = true, controller = enrollment) }
            }
        }
        compose.onNodeWithTag("speaker_gate_status").assertExists().assertTextContains("Not enrolled")
        compose.onNodeWithTag("speaker_gate_enroll").assertExists()
        compose.onNodeWithTag("speaker_gate_enroll").performClick()
        compose.onNodeWithTag("speaker_gate_record_phrase").assertExists()
        compose.onNodeWithTag("speaker_gate_record_phrase").performClick()
        assertEquals(android.Manifest.permission.RECORD_AUDIO, requestedPermission)
        compose.runOnIdle {
            enrollment.setStateForTests(SpeakerEnrollmentState(
                SpeakerEnrollmentStatus.Enrolled(123L, 3)))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("speaker_gate_status").assertExists().assertTextContains("Enrolled · 3 phrases")
        compose.onNodeWithTag("speaker_gate_enroll").assertExists()
        compose.onNodeWithTag("speaker_gate_delete").assertExists()
    }
}
