package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shipping overlay, constrained to the actual compact rotation's available slot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceCallOverlayLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val width = mutableStateOf(731.dp)
    private val height = mutableStateOf(161.5.dp)
    private val paused = mutableStateOf(false)
    private var toggles = 0
    private var ends = 0
    private var stops = 0
    private val status = "Listening to a controlled layout fixture"

    private fun mount(active: Boolean = true, callInFlight: Boolean = true,
        speaking: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.size(width.value, height.value).testTag("call_slot")) {
                            VoiceCallOverlay.Bubble(
                                phase = if (speaking) "Speaking" else "Listening", status = status,
                                level = .2f, active = active, microphonePaused = paused.value,
                                canStart = !callInFlight, stopReplyAvailable = speaking,
                                onStart = {}, onStopReply = { stops++ },
                                onToggleMicrophone = { toggles++; paused.value = !paused.value },
                                onEndCall = { ends++ }, callInFlight = callInFlight)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertAction(tag: String, label: String): Rect {
        compose.onNodeWithTag(tag).assertIsDisplayed().assertContentDescriptionEquals(label)
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val action = bounds(tag)
        assertFullLabel(label, action)
        val slot = bounds("call_slot")
        assertTrue("$tag must fit the actual host width", action.left >= slot.left && action.right <= slot.right)
        assertTrue("$tag must fit above the composer clearance", action.top >= slot.top &&
            action.bottom <= slot.bottom - with(compose.density) { 104.dp.toPx() } + 1f)
        return action
    }

    private fun assertFullLabel(label: String, action: Rect) {
        val text = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$label must have a real text layout", 1, layouts.size)
        val layout = layouts.single()
        assertEquals("The complete label must reach text layout", label, layout.layoutInput.text.text)
        // BasicText's accessibility result can retain the wider paragraph
        // allocation while reporting tight text size. Check the actual lines
        // and glyphs, rather than mistaking that allocation for painted overflow.
        for (line in 0 until layout.lineCount) {
            assertTrue("$label must not be ellipsized", !layout.isLineEllipsized(line))
            assertTrue("$label line must fit its height", layout.getLineTop(line) >= 0f &&
                layout.getLineBottom(line) <= layout.size.height + 1f)
        }
        assertEquals("Every label character must be laid out", label.length,
            layout.getLineEnd(layout.lineCount - 1))
        for (index in label.indices) {
            val glyph = layout.getBoundingBox(index)
            assertTrue("$label glyph $index must fit its text box: $glyph in ${layout.size}",
                glyph.left >= -1f && glyph.top >= -1f && glyph.right <= layout.size.width + 1f &&
                    glyph.bottom <= layout.size.height + 1f)
        }
        val visible = text.fetchSemanticsNode().boundsInRoot
        assertTrue("$label must fit inside its action", visible.left >= action.left && visible.top >= action.top &&
            visible.right <= action.right && visible.bottom <= action.bottom)
    }

    private fun assertSeparate(vararg actions: Rect) {
        for (i in actions.indices) for (j in i + 1 until actions.size) {
            assertTrue("Call targets must not overlap: ${actions[i]} and ${actions[j]}",
                !actions[i].overlaps(actions[j]))
        }
        compose.onNodeWithTag("voice_call_status").assertIsDisplayed().assertContentDescriptionEquals(status)
    }

    @Test fun shortWideSlotKeepsFullMicrophoneLabelsAndCallbacks() {
        mount()
        val end = assertAction("voice_call_end", "End call")
        val pause = assertAction("voice_call_pause", "Pause microphone")
        assertEquals("Short layout places actions on one row", end.center.y, pause.center.y, 1f)
        assertSeparate(end, pause)
        compose.onNodeWithTag("voice_call_pause").performClick()
        assertAction("voice_call_pause", "Resume microphone")
        compose.onNodeWithTag("voice_call_pause").performClick()
        assertAction("voice_call_pause", "Pause microphone")
        assertEquals(2, toggles)
    }

    @Test fun shortSpeakingSlotPreservesStopReplyAndEndCallbacks() {
        assertSpeakingSlot(fontScale = 1f)
    }

    @Test fun shortSpeakingSlotAtLargeFontKeepsAllLabelsAndCallbacks() {
        assertSpeakingSlot(fontScale = 2f)
    }

    private fun assertSpeakingSlot(fontScale: Float) {
        mount(speaking = true, fontScale = fontScale)
        // Stop reply's visible text is the accessible name; it has no redundant description.
        compose.onNodeWithTag("voice_call_stop_reply").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val stop = bounds("voice_call_stop_reply")
        assertFullLabel("Stop reply", stop)
        assertTrue(stop.bottom <= bounds("call_slot").bottom - with(compose.density) { 104.dp.toPx() } + 1f)
        assertSeparate(assertAction("voice_call_end", "End call"),
            assertAction("voice_call_pause", "Pause microphone"), stop)
        compose.onNodeWithTag("voice_call_stop_reply").performClick()
        compose.onNodeWithTag("voice_call_end").performClick()
        assertEquals(1, stops)
        assertEquals(1, ends)
    }

    @Test fun shortDisarmedLiveSessionStillHasEndCall() {
        mount(active = false, callInFlight = true)
        assertAction("voice_call_end", "End call")
        compose.onNodeWithTag("voice_call_pause").assertDoesNotExist()
        compose.onNodeWithTag("voice_start").assertDoesNotExist()
        compose.onNodeWithTag("voice_call_end").performClick()
        assertEquals(1, ends)
    }

    @Test fun resizingRetainsPausedStateAndReturnsToVerticalPortraitPill() {
        width.value = 411.dp
        height.value = 520.dp
        mount()
        val end = assertAction("voice_call_end", "End call")
        val pause = assertAction("voice_call_pause", "Pause microphone")
        assertTrue("Portrait keeps the vertical pill", pause.top >= end.bottom)
        compose.onNodeWithTag("voice_call_pause").performClick()
        compose.runOnIdle { width.value = 731.dp; height.value = 161.5.dp }
        assertSeparate(assertAction("voice_call_end", "End call"),
            assertAction("voice_call_pause", "Resume microphone"))
        assertEquals(1, toggles)
        assertEquals(0, ends)
        compose.runOnIdle { width.value = 411.dp; height.value = 520.dp }
        assertTrue(assertAction("voice_call_pause", "Resume microphone").top >=
            assertAction("voice_call_end", "End call").bottom)
        compose.onNodeWithTag("voice_call_pause").performClick()
        assertAction("voice_call_pause", "Pause microphone")
        assertEquals(2, toggles)
    }

    @Test fun narrowLargeFontPortraitKeepsNamedTargets() {
        width.value = 320.dp
        height.value = 520.dp
        mount(fontScale = 2f)
        assertSeparate(assertAction("voice_call_end", "End call"),
            assertAction("voice_call_pause", "Pause microphone"))
        compose.onNodeWithTag("voice_call_pause").performClick()
        assertAction("voice_call_pause", "Resume microphone")
        assertEquals(1, toggles)
    }
}
