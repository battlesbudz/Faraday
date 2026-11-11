package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.battlesbudz.jarvis.v2.ai.CodeModeEngine
import com.battlesbudz.jarvis.v2.ai.FakeModelEngine
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.code.CodeEngineAccess
import com.battlesbudz.jarvis.v2.code.CodeModeDriver
import com.battlesbudz.jarvis.v2.code.CodeProjectStore
import com.battlesbudz.jarvis.v2.code.CodeStatsStore
import com.battlesbudz.jarvis.v2.code.ConversationCodeController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Code mode in the conversation (Battles, 2026-11-09: the separate
 * destination is reversed): the picker lists the store's projects,
 * picking one makes it the conversation's active project, the bar
 * above the composer carries the pick state with Preview and Export,
 * and a direction sent through the controller lands in the thread as
 * Battles's message with the round's outcome as the reply. The round
 * flow itself stays pinned by CodeModeDriverTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodeConversationUiTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var workspace: File
    private lateinit var store: CodeProjectStore
    private lateinit var stats: CodeStatsStore
    private lateinit var history: ConversationHistory
    private lateinit var fake: FakeModelEngine

    private class FakeAccess(private val engine: CodeModeEngine?) : CodeEngineAccess {
        override suspend fun <T> withEngine(block: suspend (CodeModeEngine, Int?) -> T): T? =
            engine?.let { block(it, null) }
    }

    private fun controller(engine: CodeModeEngine?): ConversationCodeController {
        val access = FakeAccess(engine)
        return ConversationCodeController(
            store = store,
            statsStore = stats,
            driver = CodeModeDriver(store, stats, access),
            engineAccess = access,
            history = history,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    @Before fun setUp() {
        workspace = temp.newFolder("workspace")
        store = CodeProjectStore(workspace)
        stats = CodeStatsStore(workspace)
        fake = FakeModelEngine()
        val context = ApplicationProvider.getApplicationContext<Context>()
        history = ConversationHistory(context.getSharedPreferences("code-ui-test", Context.MODE_PRIVATE))
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for: $description")
    }

    private fun nodeExists(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun writeAction(path: String, content: String): String =
        "<action name=\"write_file\"><path>$path</path><content>\n$content\n</content></action>"

    private val page =
        "<!DOCTYPE html>\n<html lang=\"en\">\n<head><title>Snake</title></head>\n" +
            "<body><h1>Snake</h1><script>let speed = 1;</script></body>\n</html>"

    // -- The picker ---------------------------------------------------------------

    @Test fun thePickerListsTheStoreContents() {
        val snake = store.createProject("Snake", "a snake game", 1_000L)
        val tank = store.createProject("Tank", "a tank game", 2_000L)
        compose.setContent {
            MaterialTheme { CodeProjectsPicker(controller = controller(null), onDismiss = {}) }
        }
        compose.onNodeWithTag("code_project_${snake.id}").assertIsDisplayed()
        compose.onNodeWithTag("code_project_${tank.id}").assertIsDisplayed()
    }

    @Test fun thePickerEmptyStateTellsBattlesHowToStart() {
        compose.setContent {
            MaterialTheme { CodeProjectsPicker(controller = controller(null), onDismiss = {}) }
        }
        compose.onNodeWithTag("code_empty").assertIsDisplayed()
        compose.onNodeWithTag("code_new_project").assertIsDisplayed()
    }

    @Test fun pickingAProjectSetsItActiveAndClosesThePicker() {
        val snake = store.createProject("Snake", "a snake game", 1_000L)
        val code = controller(null)
        var dismissed = false
        compose.setContent {
            MaterialTheme { CodeProjectsPicker(controller = code, onDismiss = { dismissed = true }) }
        }
        compose.onNodeWithTag("code_open_${snake.id}").performClick()
        assertEquals(snake.id, code.activeProjectId.value)
        assertTrue(dismissed)
    }

    @Test fun creatingAProjectStartsItsFirstRoundInTheConversation() {
        val code = controller(null) // the borrow refuses: the round reports it could not run
        compose.setContent {
            MaterialTheme { CodeProjectsPicker(controller = code, onDismiss = {}) }
        }
        compose.onNodeWithTag("code_new_project").performClick()
        compose.onNodeWithTag("code_create_name").performTextInput("Snake")
        compose.onNodeWithTag("code_create_request").performTextInput("a snake game")
        compose.onNodeWithTag("code_create_confirm").performClick()
        // His goal is a turn in the thread, and the refused round's
        // outcome lands as the reply — nothing silently vanishes.
        await("the first round's reply") {
            val messages = history.current.value.messages
            messages.any { it.role == "You" && it.text == "a snake game" } &&
                messages.any { it.role == "Jarvis" && it.complete && it.text.startsWith("Nothing ran") }
        }
        assertEquals(1, store.listProjects().size)
        assertEquals(store.listProjects().single().id, code.activeProjectId.value)
    }

    // -- The bar -------------------------------------------------------------------

    @Test fun theBarOffersPickingWithNoProject() {
        compose.setContent {
            MaterialTheme {
                CodeConversationBar(controller = controller(null), onOpenPicker = {}, onOpenPreview = {})
            }
        }
        compose.onNodeWithTag("code_pick_project").assertIsDisplayed()
    }

    @Test fun theBarShowsTheActiveProjectWithPreviewAndExport() {
        val snake = store.createProject("Snake", "a snake game", 1_000L)
        val code = controller(null)
        code.pickProject(snake.id)
        compose.setContent {
            MaterialTheme {
                CodeConversationBar(controller = code, onOpenPicker = {}, onOpenPreview = {})
            }
        }
        compose.onNodeWithText("Snake").assertIsDisplayed()
        compose.onNodeWithTag("code_export").assertIsDisplayed()
        compose.onNodeWithTag("code_preview_open").assertIsDisplayed()
        compose.onNodeWithTag("code_leave_project").assertIsDisplayed()
    }

    // -- Directions in the conversation ----------------------------------------------

    @Test fun aDirectionRunsARoundAndTheOutcomeLandsAsTheReply() {
        val snake = store.createProject("Snake", "a snake game", 1_000L)
        fake.enqueueReply(writeAction("index.html", page))
        val code = controller(fake)
        code.pickProject(snake.id)
        val error = code.sendDirection("make the snake game")
        assertEquals(null, error)
        await("the round's reply") {
            val messages = history.current.value.messages
            messages.any { it.role == "You" && it.text == "make the snake game" } &&
                messages.any { it.role == "Jarvis" && it.complete && it.text.contains("wrote index.html") }
        }
        assertEquals(page, File(store.projectDir(snake.id), "index.html").readText())
    }

    @Test fun aDirectionWithNoProjectIsRefusedAndAppendsNothing() {
        val code = controller(fake)
        val error = code.sendDirection("make the snake game")
        assertEquals("Pick a code project first.", error)
        assertTrue(history.current.value.messages.isEmpty())
    }
}
