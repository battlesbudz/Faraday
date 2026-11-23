package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.memory.EmbeddingRole
import com.battlesbudz.jarvis.v2.memory.MemoryEmbeddingEngine
import com.battlesbudz.jarvis.v2.memory.MemoryEmbeddingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FrontDoorClassifierTest {
    private fun head() = FrontDoorHead(
        listOf(java.io.File("src/main/assets/frontdoor/head_v1.json"),
            java.io.File("app/src/main/assets/frontdoor/head_v1.json")).first { it.isFile }.inputStream())

    @Test fun decisionUsesWindowOnlyWhenCurrentMessageDoesNotStandOnItsOwn() {
        val head = head()
        val fragment = head.decide(doubleArrayOf(.55, .20, .25), doubleArrayOf(.10, .25, .65))
        assertTrue(fragment.toolsOffered)
        assertTrue(fragment.actIntended)
        assertTrue(fragment.windowUpgraded)
        assertEquals("ACT", fragment.label)

        val discussion = head.decide(doubleArrayOf(.85, .05, .10), doubleArrayOf(.05, .10, .85))
        assertTrue(discussion.discussionConfident)
        assertFalse(discussion.toolsOffered)
        assertFalse(discussion.windowUpgraded)

        val uncertain = head.decide(doubleArrayOf(.45, .30, .25), null)
        assertTrue(uncertain.toolsOffered)
        assertFalse(uncertain.discussionConfident)

        val confidentChat = head.decide(doubleArrayOf(.90, .05, .05), null)
        assertFalse(confidentChat.toolsOffered)
    }

    @Test fun vetoIsCurrentMessageOnlyAndNeverVetoesActionLeaningText() {
        val head = head()
        assertTrue(head.decide(doubleArrayOf(.70, .20, .10), null).vetoConfident)
        assertFalse(head.decide(doubleArrayOf(.55, .20, .25), doubleArrayOf(.10, .25, .65)).vetoConfident)
        assertFalse(head.decide(doubleArrayOf(.60, .05, .35), null).vetoConfident)
    }

    @Test fun coldClassifierFailsOpenThenScoresAndReturnsColdAfterEmbedFailure() = runBlocking {
        var fail = false
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val embeddedTexts = mutableListOf<String>()
        val engine = object : MemoryEmbeddingEngine {
            override val modelId = com.battlesbudz.jarvis.v2.memory.EmbeddingModelCatalog.default.id
            override val available = true
            override fun embed(text: String, role: EmbeddingRole): FloatArray {
                if (fail) error("synthetic embedding failure")
                assertEquals(EmbeddingRole.CLASSIFICATION, role)
                embeddedTexts += text
                return FloatArray(768).apply { this[0] = 1f }
            }
        }
        val service = MemoryEmbeddingService(engine)
        val classifier = FrontDoorClassifier({ if (ready.get()) service else null }, {
            listOf(java.io.File("src/main/assets/frontdoor/head_v1.json"),
                java.io.File("app/src/main/assets/frontdoor/head_v1.json")).first { it.isFile }.inputStream()
        })
        val chatCold = classifier.classify("Hi", null)
        val actionCold = classifier.classify("Send a message", null)
        listOf(chatCold, actionCold).forEach {
            assertTrue(it.cold)
            assertTrue(it.toolsOffered)
            assertFalse(it.actIntended)
            assertFalse(it.discussionConfident)
            assertFalse(it.vetoConfident)
        }
        delay(50)
        ready.set(true)
        classifier.warmUpAsync()
        var attempts = 0
        while (!classifier.warmed && attempts++ < 100) {
            delay(10)
        }
        assertTrue(classifier.warmed)
        val warmedChat = classifier.classify("Hi", null)
        val warmed = classifier.classify("Send a message", null)
        assertEquals("classifier", warmedChat.source)
        assertFalse(warmedChat.cold)
        assertEquals("classifier", warmed.source)
        assertFalse(warmed.cold)
        assertTrue(embeddedTexts.all { it.startsWith("task: classification | query: ") })
        fail = true
        val failed = classifier.classify("Send a message", null)
        assertTrue(failed.cold)
        assertTrue(failed.toolsOffered)
        assertFalse(classifier.warmed)
    }

    @Test fun inputWindowFormatIsPinned() {
        assertEquals("Can you send a message to Andrea?\ntesting",
            FrontDoorInput.build("Can you send a message to Andrea?", "testing"))
        assertEquals("testing", FrontDoorInput.build(null, "testing"))
    }
}
