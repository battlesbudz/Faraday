package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelCompatibility
import com.battlesbudz.jarvis.v2.ai.ModelGuidance
import com.battlesbudz.jarvis.v2.ai.PhoneProfile
import com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore
import com.battlesbudz.jarvis.v2.eval.InMemoryReliabilityReportStore
import com.battlesbudz.jarvis.v2.eval.ModelReport
import com.battlesbudz.jarvis.v2.eval.ReliabilityReportStore
import com.battlesbudz.jarvis.v2.eval.StoredReliabilityReport
import com.battlesbudz.jarvis.v2.eval.ToolReliabilityFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Real details/browser composables and benchmark StateFlow; no inference or speed benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelDetailsComposeTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val files = TemporaryFolder()
    private val phone = PhoneProfile("Fixture phone", "fixture", "fixture", 12_000_000_000,
        8_000_000_000, 32_000_000_000, true)
    private lateinit var store: AndroidPipelineBenchmarkStore

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = files.newFolder("benchmarks")
        store = AndroidPipelineBenchmarkStore(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        })
    }

    @After fun flush() = runBlocking { store.flush() }

    @Test fun emptyThenPopulatedThenClearedSamplesRefreshWhileDetailsStayOpen() {
        compose.setContent { MaterialTheme { ModelDetails(ModelDetailsFixtures.model, phone, store, true, InMemoryReliabilityReportStore()) {} } }
        compose.onNodeWithTag("model_details_speed").assertTextEquals("No completed speed samples for this model yet.")
        compose.onNodeWithText("Installed · Download ${ModelGuidance.gb(ModelDetailsFixtures.model.downloadBytes!!)}").assertExists()
        compose.onNodeWithText(ModelGuidance.assess(ModelDetailsFixtures.model, phone).quickMemoryLabel).assertExists()
        compose.onNodeWithText("Audio input: yes").assertExists()
        compose.onNodeWithText("Tool calling: yes").assertExists()
        compose.runOnIdle { store.append(ModelDetailsFixtures.turn(ModelDetailsFixtures.submission(ttft = 80, decode = 10.5))) }
        assertEquals("The fixture must be accepted by the real retention policy", 1, store.samples.value.size)
        compose.onNodeWithTag("model_details_speed").assertTextEquals(
            "Observed TTFT median: 80 ms (1 sample)\nEstimated decode median: 10.5 tok/s (1 sample)")
        runBlocking { store.clear() }
        compose.waitForIdle()
        compose.onNodeWithTag("model_details_speed").assertTextEquals("No completed speed samples for this model yet.")
        compose.onNodeWithTag("model_details_close").assertIsDisplayed()
    }

    @Test fun changingModelAndInstallationDoesNotReuseAnotherModelsSpeed() {
        val model = mutableStateOf(ModelDetailsFixtures.model)
        val installed = mutableStateOf(false)
        store.append(ModelDetailsFixtures.turn(ModelDetailsFixtures.submission(ttft = 40)))
        compose.setContent { MaterialTheme { ModelDetails(model.value, phone, store, installed.value, InMemoryReliabilityReportStore()) {} } }
        compose.onNodeWithText("Not installed · Download ${ModelGuidance.gb(model.value.downloadBytes!!)}").assertExists()
        compose.onNodeWithTag("model_details_speed").assertTextEquals("Observed TTFT median: 40 ms (1 sample)")
        compose.runOnIdle { model.value = ModelCatalog.gemma4E4b; installed.value = true }
        compose.onNodeWithTag("model_details_speed").assertTextEquals("No completed speed samples for this model yet.")
        compose.onNodeWithText("Installed · Download ${ModelGuidance.gb(model.value.downloadBytes!!)}").assertExists()
    }

    @Test fun detailsCombineObservedSpeedWithOnlyCurrentToolFixtureEvidence() {
        val model = ModelDetailsFixtures.model
        val fingerprint = mutableStateOf("verified-model-fixture")
        val stored = StoredReliabilityReport(model.id, 80, 4, 5, "Controlled tool fixture result", 1L,
            modelFingerprint = fingerprint.value, suiteVersion = ToolReliabilityFixtures.SUITE_VERSION)
        val reliability = object : ReliabilityReportStore {
            override fun save(report: ModelReport, ranAtMs: Long) = error("Read-only UI fixture")
            override fun load(modelId: String) = stored.takeIf { it.modelId == modelId }
            override fun clear(modelId: String) = error("Read-only UI fixture")
        }
        store.append(ModelDetailsFixtures.turn(ModelDetailsFixtures.submission(ttft = 55)))
        compose.setContent { MaterialTheme {
            ModelDetails(model, phone, store, true, reliability,
                reliabilityFingerprint = { fingerprint.value }) {}
        } }
        compose.onNodeWithTag("model_details_speed")
            .assertTextEquals("Observed TTFT median: 55 ms (1 sample)")
        compose.onNodeWithText("Tool-call fixture agreement: 80% (4/5)").assertExists()
        compose.onNodeWithText("Exact agreement with the fixture set and tool schema. This does not prove " +
            "a requested phone action would succeed.").assertExists()
        compose.runOnIdle { fingerprint.value = "replacement-model-fixture" }
        compose.onNodeWithText("Tool-call fixture agreement: 80% (4/5)").assertDoesNotExist()
        compose.onNodeWithText("Tool reliability: not yet measured.").assertExists()
        compose.onNodeWithTag("model_details_speed")
            .assertTextEquals("Observed TTFT median: 55 ms (1 sample)")
        compose.onNodeWithTag("model_details_evidence").performScrollTo().performClick()
        compose.onNodeWithTag("model_details_publisher").performScrollTo().assertIsDisplayed()
    }

    @Test fun compactDetailsRetainExpandableCompatibilityAndResourceProvenance() {
        val model = ModelCatalog.all.first { it.id == "Zamba2-2.7B-instruct" }
        val evidence = ModelCompatibility.assess(model)
        compose.setContent { MaterialTheme { ModelDetails(model, phone, store, false, InMemoryReliabilityReportStore()) {} } }
        compose.onNodeWithText(evidence.summary).assertExists()
        compose.onNodeWithText(evidence.details).assertDoesNotExist()
        compose.onNodeWithTag("model_details_evidence").performScrollTo().performClick()
        compose.onNodeWithText(evidence.details).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("An Android test is not a speed or reliability guarantee for your phone.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Download size is not measured memory use. These estimates do not change when you install a model. Phone checks stay on your device.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("model_details_publisher").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("model_details_close").assertIsDisplayed()
        compose.onNodeWithTag("model_details_evidence").performScrollTo().performClick()
        compose.onNodeWithText(evidence.details).assertDoesNotExist()
    }

    @Test fun closingAndReopeningDetailsNeverSelectsOrDismissesTheBrowser() {
        var selected = ModelCatalog.gemma4E4b.id
        var selections = 0
        var dismissals = 0
        compose.setContent { MaterialTheme { ModelBrowser(phone, selected, { false }, store,
            onSelect = { selections++; selected = it.id; null }, onDismiss = { dismissals++ }) } }
        compose.onNodeWithTag("model_search").performTextInput(ModelDetailsFixtures.model.id)
        compose.onNodeWithTag("model_family_Gemma").performClick()
        repeat(2) {
            compose.onNodeWithTag("model_details_${ModelDetailsFixtures.model.id}").performScrollTo().performClick()
            compose.onNodeWithTag("model_details_close").performClick()
            compose.onNodeWithTag("model_details_dialog").assertDoesNotExist()
            compose.onNodeWithTag("model_list").assertExists()
        }
        assertEquals(ModelCatalog.gemma4E4b.id, selected)
        assertEquals(0, selections)
        assertEquals(0, dismissals)
    }

    @Suppress("DEPRECATION")
    @Test fun backDismissesOnlyDetailsAndKnownIssueStillRequiresExplicitSelection() {
        val model = ModelCatalog.all.first { it.id == "Zamba2-2.7B-instruct" }
        var selections = 0
        var dismissals = 0
        compose.setContent { MaterialTheme { ModelBrowser(phone, ModelDetailsFixtures.model.id, { false }, store,
            onSelect = { selections++; null }, onDismiss = { dismissals++ }) } }
        compose.onNodeWithTag("model_search").performTextInput(model.id)
        compose.onNodeWithTag("model_family_Zamba").performClick()
        compose.onNodeWithTag("model_details_${model.id}").performScrollTo().performClick()
        compose.onNodeWithTag("model_details_dialog").assertExists()
        compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("model_details_dialog").assertDoesNotExist()
        compose.onNodeWithTag("model_list").assertExists()
        assertEquals(0, selections)
        assertEquals(0, dismissals)
        compose.onNodeWithTag("model_choose_${model.id}").performScrollTo().performClick()
        compose.onNodeWithTag("model_issue_continue").assertExists()
        assertEquals(0, selections)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, selections)
        compose.onNodeWithTag("model_choose_${model.id}").performScrollTo().performClick()
        compose.onNodeWithTag("model_issue_continue").performClick()
        assertEquals(1, selections)
        assertEquals(1, dismissals)
    }
}
