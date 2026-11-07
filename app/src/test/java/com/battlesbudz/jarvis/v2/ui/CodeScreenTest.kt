package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.battlesbudz.jarvis.v2.ai.CodeModeEngine
import com.battlesbudz.jarvis.v2.code.CodeEngineAccess
import com.battlesbudz.jarvis.v2.code.CodeProjectStore
import com.battlesbudz.jarvis.v2.code.CodeStatsStore
import java.io.File
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
 * The Code destination at screen level (scope Unit 6): the project
 * list renders the store's contents, the create dialog starts a
 * project and opens it, the project view mounts the Preview,
 * Console, Files, and Brief panes, and rename and delete go through
 * their dialogs. The engine borrow always refuses here — the round
 * flow itself is pinned by CodeModeDriverTest — and the preview
 * pane is a stub, so no WebView is in the room.
 *
 * The two tests that type into a dialog field run the dialog
 * through [inDialog]: under Robolectric a text field inside a
 * dialog window never lets the paused looper idle (AppNotIdle
 * after 60 s with the clock auto-advancing), while the dialog's
 * entry is itself clock-gated (frozen from the start, it never
 * composes). So the clock is frozen, frames are pumped by hand
 * until the dialog is in the tree, and the interactions run
 * frozen; the button-only delete dialog needs none of this. The
 * dialogs themselves are plain Material3, the same shape as
 * MemoryScreen's shipped editor dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodeScreenTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var workspace: File
    private lateinit var store: CodeProjectStore
    private lateinit var stats: CodeStatsStore

    private val refusingAccess = object : CodeEngineAccess {
        override suspend fun <T> withEngine(block: suspend (CodeModeEngine, Int?) -> T): T? = null
    }

    @Before fun setUp() {
        workspace = temp.newFolder("workspace")
        store = CodeProjectStore(workspace)
        stats = CodeStatsStore(workspace)
    }

    private fun setScreen() {
        compose.setContent {
            MaterialTheme {
                CodeScreen(
                    store = store,
                    statsStore = stats,
                    engineAccess = refusingAccess,
                    roundsEnabled = true,
                    onBack = {},
                    previewContent = { project, _, _ ->
                        Box(Modifier.testTag("code_preview_stub")) { Text("preview of ${project.name}") }
                    },
                )
            }
        }
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

    /**
     * Opens a text-field dialog and runs [block] inside it; see the
     * class KDoc for why. The clock is frozen before [open] runs,
     * frames are pumped by hand until the node tagged [dialogTag]
     * is in the semantics tree, [block] runs frozen (clicks and
     * text input are looper-driven and still land), and the clock
     * restarts before the caller asserts the dialog's effects.
     */
    private fun inDialog(dialogTag: String, open: () -> Unit, block: () -> Unit) {
        compose.mainClock.autoAdvance = false
        try {
            open()
            var frames = 0
            while (!nodeExists(dialogTag) && frames < 60) {
                compose.mainClock.advanceTimeByFrame()
                frames++
            }
            assertTrue("dialog node $dialogTag composed after $frames frames", nodeExists(dialogTag))
            block()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun theListRendersTheStoreContents() {
        val snake = store.createProject("Snake", "a snake game", 1_000L)
        val tank = store.createProject("Tank Game", "a tank game", 2_000L)
        setScreen()
        compose.onNodeWithTag("code_project_${snake.id}").assertIsDisplayed()
        compose.onNodeWithTag("code_project_${tank.id}").assertIsDisplayed()
        compose.onNodeWithText("Snake").assertIsDisplayed()
        compose.onNodeWithText("Tank Game").assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("0 rounds", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test fun theEmptyStateTellsBattlesHowToStart() {
        setScreen()
        compose.onNodeWithTag("code_empty").assertIsDisplayed()
        compose.onNodeWithTag("code_new_project").assertIsDisplayed()
    }

    @Test fun creatingAProjectOpensItWithThePreviewMounted() {
        setScreen()
        inDialog("code_create_name", open = { compose.onNodeWithTag("code_new_project").performClick() }) {
            compose.onNodeWithTag("code_create_name").performTextInput("Snake")
            compose.onNodeWithTag("code_create_request").performTextInput("a snake game")
            compose.onNodeWithTag("code_create_confirm").performClick()
        }
        await("the project to be created in the store") { store.listProjects().size == 1 }
        compose.waitUntil(10_000) { nodeExists("code_preview_stub") }
        compose.onNodeWithText("preview of Snake").assertIsDisplayed()
        compose.onNodeWithTag("code_export").assertIsDisplayed()
        assertEquals("a snake game", store.listProjects().single().let { store.readBrief(it)?.goal })
    }

    @Test fun theProjectViewMountsEveryPane() {
        val snake = store.createProject("Snake", "a snake game", 1_000L)
        setScreen()
        compose.onNodeWithTag("code_open_${snake.id}").performClick()
        compose.onNodeWithText("preview of Snake").assertIsDisplayed()

        compose.onNodeWithTag("code_tab_console").performClick()
        compose.onNodeWithTag("code_pane_console").assertIsDisplayed()
        compose.onNodeWithTag("code_fix_errors").assertIsDisplayed()

        compose.onNodeWithTag("code_tab_files").performClick()
        compose.onNodeWithTag("code_pane_files").assertIsDisplayed()
        compose.onNodeWithText("index.html", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("code_tab_brief").performClick()
        compose.onNodeWithTag("code_pane_brief").assertIsDisplayed()
        compose.onNodeWithText("a snake game").assertIsDisplayed()

        compose.onNodeWithTag("code_tab_preview").performClick()
        compose.onNodeWithText("preview of Snake").assertIsDisplayed()
    }

    @Test fun renamingAProjectChangesOnlyItsListedName() {
        val snake = store.createProject("Snake", "a snake game", 1_000L)
        setScreen()
        inDialog("code_rename_input", open = { compose.onNodeWithText("Rename").performClick() }) {
            compose.onNodeWithTag("code_rename_input").performTextClearance()
            compose.onNodeWithTag("code_rename_input").performTextInput("Snake II")
            compose.onNodeWithTag("code_rename_confirm").performClick()
        }
        await("the rename to reach the store") { store.loadProject(snake.id)?.name == "Snake II" }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Snake II").fetchSemanticsNodes().isNotEmpty()
        }
        // The id — and with it the folder and exported package name — never moves.
        assertEquals(snake.id, store.listProjects().single().id)
        assertEquals(snake.exportPackageName, store.listProjects().single().exportPackageName)
    }

    @Test fun deletingAProjectAsksFirst() {
        val snake = store.createProject("Snake", "a snake game", 1_000L)
        setScreen()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete Snake?").assertIsDisplayed()
        assertTrue("nothing is deleted before the confirmation", store.loadProject(snake.id) != null)
        compose.onNodeWithTag("code_delete_confirm").performClick()
        await("the delete to reach the store") { store.listProjects().isEmpty() }
        compose.waitUntil(10_000) { nodeExists("code_empty") }
    }
}
