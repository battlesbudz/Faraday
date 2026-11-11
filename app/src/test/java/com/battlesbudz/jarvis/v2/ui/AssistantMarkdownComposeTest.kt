package com.battlesbudz.jarvis.v2.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #20 item 1 at the rendered surface: the reporter's own list format
 * must come out as formatted text, with no literal Markdown markers left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantMarkdownComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reporterListRendersFormattedWithoutMarkers() {
        compose.setContent {
            MaterialTheme {
                AssistantMarkdownText(
                    text = "* **Renewable Energy Sources:** Utilizing naturally replenishing sources\n" +
                        "* **Energy Conversion:** Converting one form of energy into another.",
                    spoken = false,
                )
            }
        }
        compose.onNodeWithText("Renewable Energy Sources:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Energy Conversion:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Utilizing naturally replenishing sources", substring = true).assertIsDisplayed()
        compose.onNodeWithText("**", substring = true).assertDoesNotExist()
    }

    @Test fun headingAndPlainTextRender() {
        compose.setContent {
            MaterialTheme {
                AssistantMarkdownText(text = "# Energy basics\n\nFree energy is not possible.", spoken = false)
            }
        }
        compose.onNodeWithText("Energy basics", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Free energy is not possible.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("#", substring = true).assertDoesNotExist()
    }
}
