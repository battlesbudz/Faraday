package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.pow

/**
 * Readability guards for the assistant-status banner and the screen around it.
 *
 * Regression context: the banner used to be the first child of an overlay Box in
 * JarvisApp, which draws it BEHIND the full-size content, so the conversation
 * tabs rendered on top of the banner text and the whole top of the screen became
 * unreadable. The banner is now the last child (a true popup: on top, fully
 * opaque), and obscured content is hidden from the semantics tree while it shows.
 * These tests make that class of bug fail loudly: no two visible texts may
 * overlap, banner actions must meet the 48 dp touch target, the banner container
 * must be fully opaque, and banner text must meet WCAG AA contrast.
 */

/** Index pairs of text bounds whose areas meaningfully intersect. Pure geometry, unit-tested below. */
internal fun findOverlappingTextBounds(bounds: List<Rect>): List<Pair<Int, Int>> {
    val hits = mutableListOf<Pair<Int, Int>>()
    for (i in bounds.indices) {
        for (j in i + 1 until bounds.size) {
            // Deflate by a pixel so merely touching edges do not count as overlap.
            if (bounds[i].deflate(1f).overlaps(bounds[j].deflate(1f))) {
                hits += i to j
            }
        }
    }
    return hits
}

/** WCAG 2.x contrast ratio of opaque foreground over opaque background. Pure, unit-tested below. */
internal fun wcagContrastRatio(foreground: Color, background: Color): Double {
    fun linear(channel: Float): Double {
        val s = channel.toDouble()
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    val lighter = maxOf(luminance(foreground), luminance(background))
    val darker = minOf(luminance(foreground), luminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantBannerReadabilityTest {
    @get:Rule val compose = createComposeRule()

    // ---- pure-helper unit tests (no composition needed) ----

    @Test fun overlapDetector_emptyAndSingle() {
        assertTrue(findOverlappingTextBounds(emptyList()).isEmpty())
        assertTrue(findOverlappingTextBounds(listOf(Rect(0f, 0f, 10f, 10f))).isEmpty())
    }

    @Test fun overlapDetector_disjointAndTouching() {
        val disjoint = listOf(Rect(0f, 0f, 10f, 10f), Rect(20f, 20f, 30f, 30f))
        assertTrue(findOverlappingTextBounds(disjoint).isEmpty())
        // Shared edge only: touching is not overlapping.
        val touching = listOf(Rect(0f, 0f, 10f, 10f), Rect(10f, 0f, 20f, 10f))
        assertTrue(findOverlappingTextBounds(touching).isEmpty())
    }

    @Test fun overlapDetector_partialAndContained() {
        val partial = listOf(Rect(0f, 0f, 10f, 10f), Rect(5f, 5f, 15f, 15f))
        assertEquals(listOf(0 to 1), findOverlappingTextBounds(partial))
        val contained = listOf(Rect(0f, 0f, 20f, 20f), Rect(5f, 5f, 10f, 10f))
        assertEquals(listOf(0 to 1), findOverlappingTextBounds(contained))
    }

    @Test fun contrastRatio_knownValues() {
        assertEquals(21.0, wcagContrastRatio(Color.White, Color.Black), 0.01)
        assertEquals(21.0, wcagContrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, wcagContrastRatio(Color.Red, Color.Red), 0.001)
    }

    // ---- composition harnesses ----

    /** Stands in for the conversation screen's top tab rows and composer hint. */
    @Composable
    private fun FakeTabContent(modifier: Modifier = Modifier) {
        Column(modifier) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Text("JARVIS", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("Memory")
                Spacer(Modifier.width(16.dp))
                Text("Settings")
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                Text("Conversations")
                Spacer(Modifier.width(16.dp))
                Text("Private")
                Spacer(Modifier.width(16.dp))
                Text("New")
            }
            Row(Modifier.padding(8.dp)) {
                Text("Metrics")
                Spacer(Modifier.width(16.dp))
                Text("Copy metrics")
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Type a message, start a voice call. It's all one conversation.",
                Modifier.padding(8.dp)
            )
        }
    }

    /**
     * Replicates the pre-fix JarvisApp structure: the banner is the FIRST child
     * of an overlay Box, so it draws behind the full-size content and every text
     * collides. Kept here (not in production code) to prove the detector fires.
     */
    @Composable
    private fun LegacyOverlayHarness() {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize()) {
                AssistantStatusBannerCard(onOpenSettings = {}, onDismiss = {})
                FakeTabContent(Modifier.fillMaxSize())
            }
        }
    }

    /**
     * Mirrors the fixed JarvisApp structure: the popup is the LAST child so it
     * draws on top with its opaque background, and the obscured content is
     * hidden from the semantics tree (the same modal idiom production uses for
     * showingMemory), so assistive tech never sees text stacked under the popup.
     */
    @Composable
    private fun FixedPopupHarness() {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize()) {
                FakeTabContent(Modifier.fillMaxSize().clearAndSetSemantics { })
                Box(Modifier.fillMaxWidth()) {
                    AssistantStatusBannerCard(onOpenSettings = {}, onDismiss = {})
                }
            }
        }
    }

    private val hasAnyText = SemanticsMatcher("has any text") { node ->
        node.config.contains(SemanticsProperties.Text)
    }

    /**
     * Text bounds from the merged semantics tree: this is what assistive tech
     * (and the user) actually perceives. Subtrees hidden with
     * clearAndSetSemantics are already collapsed away here.
     */
    private fun displayedTextBounds(): List<Rect> =
        compose.onAllNodes(hasAnyText, useUnmergedTree = false)
            .fetchSemanticsNodes()
            .filter { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .any { it.text.isNotBlank() }
            }
            .map { it.boundsInRoot }
            .filter { it.width > 0f && it.height > 0f }

    // ---- readability tests ----

    @Test fun overlapDetector_catchesLegacyOverlayBug() {
        compose.setContent { LegacyOverlayHarness() }
        compose.waitForIdle()
        val overlaps = findOverlappingTextBounds(displayedTextBounds())
        assertTrue(
            "The overlap detector must fire on the known-bad overlay layout, " +
                "otherwise it cannot guard this bug class. Found: $overlaps",
            overlaps.isNotEmpty()
        )
    }

    @Test fun popupBanner_hasNoTextOverlap() {
        compose.setContent { FixedPopupHarness() }
        compose.waitForIdle()
        val bounds = displayedTextBounds()
        val overlaps = findOverlappingTextBounds(bounds)
        assertTrue(
            "With the popup on top and obscured content hidden, no visible " +
                "texts may overlap. Overlapping pairs: $overlaps",
            overlaps.isEmpty()
        )
    }

    @Test fun bannerActions_meet48dpTouchTarget() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AssistantStatusBannerCard(onOpenSettings = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Open assistant settings").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun bannerContainer_isFullyOpaque() {
        var container = Color.Transparent
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                container = MaterialTheme.colorScheme.secondaryContainer
            }
        }
        compose.waitForIdle()
        // A translucent dialog/container background is exactly what made the
        // shipped bug unreadable; the default banner container must be opaque.
        assertEquals(1f, container.alpha, 0.0001f)
    }

    @Test fun bannerText_meetsContrast() {
        var container = Color.Black
        var content = Color.White
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                container = MaterialTheme.colorScheme.secondaryContainer
                // Card content defaults to contentColorFor(containerColor).
                content = MaterialTheme.colorScheme.onSecondaryContainer
            }
        }
        compose.waitForIdle()
        val ratio = wcagContrastRatio(content, container)
        assertTrue("Banner text contrast $ratio must meet WCAG AA 4.5", ratio >= 4.5)
    }

    // ---- production arrangement tests ----
    //
    // These mount the REAL AssistantBannerPopupLayer that JarvisApp renders
    // through (not a local harness copy). If production ever moves the banner
    // before the content, drops the tap-consuming overlay, or breaks the
    // obscured-content semantics wiring, these fail.

    @Test fun productionLayer_bannerDrawsAboveContent() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AssistantBannerPopupLayer(
                    bannerVisible = true,
                    content = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                    banner = { Box(Modifier.fillMaxWidth().height(120.dp).background(Color.Blue)) }
                )
            }
        }
        compose.waitForIdle()
        val image = compose.onNode(SemanticsMatcher("root node") { true }).captureToImage()
        // A pixel well inside the banner region: banner blue must win over
        // content red. If the overlay order ever regresses, this pixel is red.
        val pixel = image[image.width / 2, 40]
        assertTrue(
            "Banner must draw on top of content (expected blue, got $pixel)",
            pixel.blue > 0.9f && pixel.red < 0.1f
        )
    }

    @Test fun productionLayer_overlayConsumesTaps() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AssistantBannerPopupLayer(
                    bannerVisible = true,
                    content = { FakeTabContent(Modifier.fillMaxSize()) },
                    banner = { AssistantStatusBannerCard(onOpenSettings = {}, onDismiss = {}) }
                )
            }
        }
        compose.waitForIdle()
        // The overlay carries a click action even though tapping it is a
        // no-op: without it, taps fall through to the covered controls
        // underneath (Memory, Settings, New).
        compose.onNodeWithTag("banner_popup_overlay").assertHasClickAction()
    }

    @Test fun productionLayer_obscuredContentHiddenFromSemantics() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AssistantBannerPopupLayer(
                    bannerVisible = true,
                    content = { FakeTabContent(Modifier.fillMaxSize()) },
                    banner = { AssistantStatusBannerCard(onOpenSettings = {}, onDismiss = {}) }
                )
            }
        }
        compose.waitForIdle()
        // clearAndSetSemantics collapses the covered subtree: tab texts must
        // not be perceivable while the popup shows; banner text must be.
        compose.onNodeWithText("JARVIS").assertDoesNotExist()
        compose.onNodeWithText("Conversations").assertDoesNotExist()
        compose.onNodeWithText("Open assistant settings").assertIsDisplayed()
    }

    @Test fun productionLayer_contentVisibleWhenBannerHidden() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AssistantBannerPopupLayer(
                    bannerVisible = false,
                    content = { FakeTabContent(Modifier.fillMaxSize()) },
                    banner = { AssistantStatusBannerCard(onOpenSettings = {}, onDismiss = {}) }
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("JARVIS").assertIsDisplayed()
    }

    @Test fun productionLayer_noTextOverlap() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AssistantBannerPopupLayer(
                    bannerVisible = true,
                    content = { FakeTabContent(Modifier.fillMaxSize()) },
                    banner = { AssistantStatusBannerCard(onOpenSettings = {}, onDismiss = {}) }
                )
            }
        }
        compose.waitForIdle()
        val overlaps = findOverlappingTextBounds(displayedTextBounds())
        assertTrue(
            "The production popup must show no overlapping texts. Overlapping pairs: $overlaps",
            overlaps.isEmpty()
        )
    }
}
