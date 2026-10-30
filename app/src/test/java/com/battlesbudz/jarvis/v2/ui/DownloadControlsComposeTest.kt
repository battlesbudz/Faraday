package com.battlesbudz.jarvis.v2.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The download controls Battles asked for, pinned at the surfaces that show
 * a download: while a transfer runs, Pause (keeps the saved progress) and
 * Cancel (removes it, behind a confirmation) are reachable; while paused,
 * Resume and Cancel are; while idle, neither is. Before this, the first-run
 * screen showed a progress bar and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadControlsComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun oneTap(
        downloading: Boolean = false,
        paused: Boolean = false,
        onPause: () -> Unit = {},
        onResume: () -> Unit = {},
        onDiscard: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                OneTapInstall(
                    downloading = downloading, testing = false, importing = false,
                    downloadBytes = if (downloading) 262_144_000L else 0L,
                    downloadTotalBytes = 2_684_354_560L,
                    status = if (downloading) "Downloading the selected AI model…" else "",
                    paused = paused,
                    partialBytes = if (paused) 524_288_000L else 0L,
                    onInstall = {}, onChooseDifferentModel = {},
                    onPause = onPause, onResume = onResume, onDiscard = onDiscard,
                )
            }
        }
    }

    @Test fun downloadingShowsPauseAndCancelButNotInstall() {
        oneTap(downloading = true)
        compose.onNodeWithTag("one_tap_pause").assertIsDisplayed()
        compose.onNodeWithTag("one_tap_cancel_download").assertIsDisplayed()
        compose.onNodeWithTag("one_tap_install").assertDoesNotExist()
        compose.onNodeWithTag("one_tap_resume").assertDoesNotExist()
    }

    @Test fun pauseButtonInvokesPause() {
        val pauses = AtomicInteger(0)
        oneTap(downloading = true, onPause = { pauses.incrementAndGet() })
        compose.onNodeWithTag("one_tap_pause").performClick()
        assertEquals(1, pauses.get())
    }

    @Test fun cancelAsksForConfirmationBeforeDiscarding() {
        val discards = AtomicInteger(0)
        oneTap(downloading = true, onDiscard = { discards.incrementAndGet() })
        compose.onNodeWithTag("one_tap_cancel_download").performClick()
        // The confirmation explains the destructive part before anything runs.
        compose.onNodeWithText("Cancel this download?").assertIsDisplayed()
        compose.onNodeWithText("This stops the download and removes the 250.0 MB already saved on this phone. " +
            "Pausing instead keeps that progress, so the download can resume where it left off.").assertIsDisplayed()
        assertEquals("nothing is discarded before the user confirms", 0, discards.get())
        compose.onNodeWithTag("one_tap_confirm_discard").performClick()
        assertEquals(1, discards.get())
    }

    @Test fun pausedShowsResumeCancelAndTheSavedProgress() {
        val resumes = AtomicInteger(0)
        oneTap(paused = true, onResume = { resumes.incrementAndGet() })
        compose.onNodeWithTag("one_tap_resume").assertIsDisplayed()
        compose.onNodeWithTag("one_tap_cancel_download").assertIsDisplayed()
        compose.onNodeWithText("500.0 MB is saved on this phone. " +
            "Resume to pick up where the download left off, or cancel to remove it.").assertIsDisplayed()
        compose.onNodeWithTag("one_tap_install").assertDoesNotExist()
        compose.onNodeWithTag("one_tap_pause").assertDoesNotExist()
        compose.onNodeWithTag("one_tap_resume").performClick()
        assertEquals(1, resumes.get())
    }

    @Test fun idleShowsInstallAndNoTransferControls() {
        oneTap()
        compose.onNodeWithTag("one_tap_install").assertIsDisplayed()
        compose.onNodeWithTag("one_tap_pause").assertDoesNotExist()
        compose.onNodeWithTag("one_tap_cancel_download").assertDoesNotExist()
        compose.onNodeWithTag("one_tap_resume").assertDoesNotExist()
    }

    private fun modelSetup(downloading: Boolean, paused: Boolean) {
        compose.setContent {
            MaterialTheme {
                ModelSetup(
                    modelSelector = { _ -> },
                    ready = false, gemmaReady = false, downloadAvailable = true,
                    testing = false, importing = false,
                    downloading = downloading,
                    downloadBytes = if (downloading) 262_144_000L else 0L,
                    downloadTotalBytes = 2_684_354_560L,
                    status = if (downloading) "Downloading the selected AI model…" else "",
                    elapsedSeconds = 3L,
                    paused = paused,
                    partialBytes = if (paused) 524_288_000L else 0L,
                    onDownload = {}, onPickGemma = {}, onTest = {},
                    onPauseDownload = {}, onDiscardDownload = {},
                )
            }
        }
    }

    @Test fun setupScreenShowsPauseAndCancelWhileDownloading() {
        modelSetup(downloading = true, paused = false)
        compose.onNodeWithTag("model_setup_pause").assertExists()
        compose.onNodeWithTag("model_setup_cancel_download").assertExists()
    }

    @Test fun setupScreenOffersResumeAndCancelWhilePaused() {
        modelSetup(downloading = false, paused = true)
        compose.onNodeWithText("Resume download").assertExists()
        compose.onNodeWithText("Download paused · 500.0 MB saved on this phone. " +
            "Resume picks up where it left off.").assertExists()
        compose.onNodeWithTag("model_setup_cancel_download").assertExists()
        compose.onNodeWithTag("model_setup_pause").assertDoesNotExist()
    }
}
