package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class ModelOperationGateTest {
    @Test fun otherDownloadsDoNotOwnOrReleaseTheVoiceRuntime() {
        val gate = ModelOperationGate()
        assertTrue(gate.tryBeginRuntime("current"))
        assertTrue(gate.tryBeginDownload("other", "current", true))
        assertFalse(gate.tryBeginDownload("current", "current", true))
        assertFalse(gate.tryBeginDownload("other", "current", true))
        gate.endDownload("other")
        assertTrue(gate.runtimeActive())
        gate.endRuntime()
        assertFalse(gate.runtimeActive())
    }
    @Test fun chatCanUseCurrentWhileAnotherModelDownloads() {
        val gate = ModelOperationGate()
        assertTrue(gate.tryBeginDownload("other", "current", false))
        assertFalse(gate.runtimeActive())
        assertTrue(gate.tryBeginRuntime("current"))
        assertFalse(gate.tryBeginRuntime("other"))
        gate.endRuntime()
        assertFalse(gate.tryBeginRuntime("other"))
        gate.endDownload("other")
        assertTrue(gate.tryBeginRuntime("other"))
    }
    @Test fun canSelectInstalledModelWhilePreviousSelectionDownloads() {
        val gate = ModelOperationGate()
        assertTrue(gate.tryBeginDownload("pending", "pending", false))
        assertTrue(gate.tryBeginRuntime("installed"))
        assertFalse(gate.tryBeginDownload("installed", "pending", false))
    }
    @Test fun activeChatProtectsSelectedFileAndCancelledTransferCanResume() {
        val gate = ModelOperationGate()
        assertFalse(gate.tryBeginDownload("selected", "selected", true))
        assertTrue(gate.tryBeginDownload("other", "selected", true))
        gate.endDownload("other")
        assertTrue(gate.tryBeginDownload("other", "selected", true))
    }
}
