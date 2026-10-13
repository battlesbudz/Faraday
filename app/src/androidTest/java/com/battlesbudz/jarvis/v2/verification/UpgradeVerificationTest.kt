package com.battlesbudz.jarvis.v2.verification

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.battlesbudz.jarvis.v2.actions.*
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.memory.SQLiteMemoryStore
import com.battlesbudz.jarvis.v2.memory.MemoryReviewStatus
import com.battlesbudz.jarvis.v2.voice.SpeechCaptureProfile
import com.battlesbudz.jarvis.v2.voice.VoiceInputMode
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Candidate adapters read the previous APK's private data after a real package update. */
@RunWith(AndroidJUnit4::class)
class UpgradeVerificationTest {
    @Test fun testCandidateRetainsPreviousReleaseData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = context.getSharedPreferences("release_upgrade_fixture", Context.MODE_PRIVATE)
        assertTrue("A clear-data update would lose this marker", fixture.getLong("seeded_at", 0) > 0)
        assertEquals(fixture.getInt("installed_uid", -1), android.os.Process.myUid())
        assertEquals(SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED, SpeechCaptureProfile.selected(context))
        assertEquals("communication_noise_filtered", context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
            .getString("capture_profile", null))
        // Migration must not reset any of the previous APK's retained user data.
        val history = ConversationHistory(context.getSharedPreferences("conversations", Context.MODE_PRIVATE))
        assertEquals("upgrade-thread", history.current.value.id)
        assertEquals(listOf("Upgrade fixture: keep my conversation", "Upgrade fixture reply"), history.current.value.messages.map { it.text })
        assertEquals("Gemma-4-E2B-it", ModelStore(context).selectedModel().id)
        assertEquals(VoiceInputMode.GEMMA_AUDIO, VoiceInputMode.selected(context))
        assertNull(VoiceInputMode.captionEngine(context))
        assertFalse(VoiceInputMode.captions(context))
        val bytes = ByteArray(4097) { (it % 251).toByte() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val spec = LocalModelSpec(id = "upgrade-fixture", fileName = "upgrade-fixture.litertlm", expectedSha256 = hash, recommendedGpu = false)
        assertArrayEquals(bytes, ModelStore(context).fileFor(spec).readBytes())
        assertTrue("Verified model metadata and bytes must survive the package update", ModelStore(context).isUsable(spec))
        SQLiteMemoryStore(File(context.noBackupFilesDir, "memory-os.db"), canReadSourceText = { true }).use { store ->
            val read = store.read()
            assertNull(read.error)
            val snapshot = checkNotNull(read.snapshot)
            assertEquals(2L, snapshot.generation)
            assertEquals("Upgrade fixture prefers apricots", snapshot.memories.single().content)
            assertEquals(MemoryReviewStatus.APPROVED, snapshot.memories.single().reviewStatus)
            assertEquals("b".repeat(32), snapshot.tombstones.single().eventId)
            assertEquals("Upgrade fixture source text", store.searchExplicitHistory("Upgrade fixture").episodes.single().text)
        }
        val receipt = FileToolTaskStore(File(context.noBackupFilesDir, "phone-action-attempts.json")).read().single()
        assertEquals(ToolTaskState.SUCCEEDED, receipt.state)
        assertEquals("Battery level is 73%.", receipt.result)
        assertEquals(2L, receipt.generation)
        ReleasePhaseEvidence.capture("testCandidateRetainsPreviousReleaseData")
    }
}
