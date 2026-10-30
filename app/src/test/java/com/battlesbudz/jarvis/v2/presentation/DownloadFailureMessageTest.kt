package com.battlesbudz.jarvis.v2.presentation

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The terminal message of a stopped download must say what actually
 * happened to the user's bytes: a stop without a discard is a pause whose
 * progress can be resumed; a stop with a discard is reported by the discard
 * itself, so the download observer stays silent instead of claiming the
 * progress was saved.
 */
class DownloadFailureMessageTest {
    @Test fun cancelledWithoutDiscardReportsAResumablePause() {
        assertEquals(
            "Download paused. Saved progress can be resumed.",
            downloadFailureMessage(WorkInfo.State.CANCELLED, error = null, discardRequested = false),
        )
    }

    @Test fun cancelledWithDiscardStaysSilent() {
        assertNull(
            downloadFailureMessage(WorkInfo.State.CANCELLED, error = null, discardRequested = true),
        )
    }

    @Test fun workerErrorPassesThrough() {
        assertEquals(
            "Not enough free space.",
            downloadFailureMessage(WorkInfo.State.FAILED, error = "Not enough free space.", discardRequested = false),
        )
        assertEquals(
            "Not enough free space.",
            downloadFailureMessage(WorkInfo.State.FAILED, error = "Not enough free space.", discardRequested = true),
        )
    }

    @Test fun otherTerminalStatesUseTheGenericMessage() {
        assertEquals(
            "Jarvis model setup did not complete.",
            downloadFailureMessage(WorkInfo.State.FAILED, error = null, discardRequested = false),
        )
        assertEquals(
            "Jarvis model setup did not complete.",
            downloadFailureMessage(state = null, error = null, discardRequested = false),
        )
    }
}
