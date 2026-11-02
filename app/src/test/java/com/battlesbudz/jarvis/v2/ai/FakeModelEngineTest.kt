package com.battlesbudz.jarvis.v2.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the fake-engine double's own scriptable behavior (reliability epic
 * item 8, pattern 2): if the fake misbehaves in a way a script did not
 * ask for, every interleaving test built on it is meaningless, so its
 * token streams, failure injection, holds and lifecycle are tested here
 * directly, without any production consumer in the loop.
 */
class FakeModelEngineTest {

    @Test fun scriptedTokensStreamInOrderAndFormTheResultText() = runBlocking {
        val fake = FakeModelEngine()
        fake.enqueueReply("Hello, ", "sir", ".")
        val tokens = mutableListOf<String>()
        val result = fake.generate("prompt one", tokens::add)
        assertEquals(listOf("Hello, ", "sir", "."), tokens)
        assertEquals("Hello, sir.", result.text)
        assertEquals(3, result.streamEvents)
        assertEquals(listOf("prompt one"), fake.prompts)
        assertEquals(listOf("Hello, ", "sir", "."), fake.emittedTokens)
        assertEquals(FakeModelEngine.Outcome.COMPLETED, fake.calls.single().outcome)
        assertEquals("text", fake.calls.single().kind)
    }

    @Test fun queuedScriptsAreConsumedInOrderAcrossGenerateVariants() = runBlocking {
        val fake = FakeModelEngine()
        fake.enqueueReply("first")
        fake.enqueueReply("second")
        fake.enqueueReply("third")
        val audio = byteArrayOf(1, 2, 3)
        val image = byteArrayOf(4, 5)
        assertEquals("first", fake.generate("p1") {}.text)
        assertEquals("second", fake.generateAudio("p2", audio) {}.text)
        assertEquals("third", fake.generate("p3", image) {}.text)
        assertEquals(listOf("text", "audio", "image"), fake.calls.map { it.kind })
        assertEquals(listOf("p1", "p2", "p3"), fake.prompts)
        assertSame(audio, fake.calls[1].inputBytes)
        assertSame(image, fake.calls[2].inputBytes)
    }

    @Test fun fallbackScriptRunsWhenTheQueueIsEmpty() = runBlocking {
        val fake = FakeModelEngine()
        fake.fallbackScript = fakeGenerationScript { emit("fallback reply") }
        assertEquals("fallback reply", fake.generate("anything") {}.text)
        assertEquals("fallback reply", fake.generate("anything else") {}.text)
        assertEquals(2, fake.calls.size)
    }

    @Test fun scriptedToolCallsAndResultTextOverrideAreCarried() = runBlocking {
        val fake = FakeModelEngine()
        fake.enqueue(fakeGenerationScript {
            toolCalls(ToolCall("open_app", "{}"))
            resultText("")
        })
        val result = fake.generate("do it") {}
        assertEquals("", result.text)
        assertEquals(listOf(ToolCall("open_app", "{}")), result.toolCalls)
    }

    @Test fun failureAtEntryThrowsTheExactErrorAndEmitsNothing() = runBlocking {
        val fake = FakeModelEngine()
        val boom = RuntimeException("entry boom")
        fake.enqueueFailure(boom)
        val tokens = mutableListOf<String>()
        try {
            fake.generate("p", tokens::add)
            fail("the scripted failure must throw")
        } catch (error: RuntimeException) {
            assertSame(boom, error)
        }
        assertTrue(tokens.isEmpty())
        assertEquals(FakeModelEngine.Outcome.FAILED, fake.calls.single().outcome)
        assertSame(boom, fake.calls.single().failure)
    }

    @Test fun failureMidStreamEmitsThePartialTokensFirst() = runBlocking {
        val fake = FakeModelEngine()
        val boom = RuntimeException("mid-stream boom")
        fake.enqueueFailure(boom, "partial ", "reply ")
        val tokens = mutableListOf<String>()
        try {
            fake.generate("p", tokens::add)
            fail("the scripted failure must throw")
        } catch (error: RuntimeException) {
            assertSame(boom, error)
        }
        // The partial stream reached the consumer before the failure: this
        // is the shape a native crash mid-decode presents, and the one a
        // plain fail-at-entry stub cannot reproduce.
        assertEquals(listOf("partial ", "reply "), tokens)
        assertEquals(listOf("partial ", "reply "), fake.calls.single().emitted)
        assertEquals(FakeModelEngine.Outcome.FAILED, fake.calls.single().outcome)
    }

    @Test fun heldGenerationParksMidStreamUntilTheGateReleases() = runBlocking {
        val fake = FakeModelEngine()
        val gate = fake.newGate()
        fake.enqueue(fakeGenerationScript {
            emit("before ")
            holdAt(gate)
            emit("after")
        })
        val tokens = mutableListOf<String>()
        val job = async { fake.generate("p", tokens::add) }
        withTimeout(5_000) { gate.entered.await() }
        // Parked mid-stream: the first token is out, the call has not
        // returned, and nothing past the hold has been emitted.
        assertEquals(listOf("before "), tokens)
        assertTrue(job.isActive)
        assertNull(fake.calls.single().outcome)
        gate.release()
        val result = withTimeout(5_000) { job.await() }
        assertEquals("before after", result.text)
        assertEquals(listOf("before ", "after"), tokens)
        assertEquals(FakeModelEngine.Outcome.COMPLETED, fake.calls.single().outcome)
    }

    @Test fun hangingGenerationNeverCompletesUntilReleasedOrCancelled() = runBlocking {
        val fake = FakeModelEngine()
        val gate = fake.newGate()
        fake.enqueue(fakeGenerationScript {
            emit("stuck ")
            hangOn(gate)
            emit("never")
        })
        val tokens = mutableListOf<String>()
        val job = async { fake.generate("p", tokens::add) }
        withTimeout(5_000) { gate.entered.await() }
        delay(150)
        assertTrue("a hanging generation must still be in flight", job.isActive)
        assertEquals(listOf("stuck "), tokens)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(FakeModelEngine.Outcome.CANCELLED, fake.calls.single().outcome)
        assertEquals("the token past the hang must never be emitted", listOf("stuck "), tokens)
        // Releasing the gate afterwards changes nothing: the call is over.
        gate.release()
        assertEquals(listOf("stuck "), fake.emittedTokens)
    }

    @Test fun twoGatesHoldTwoGenerationsIndependently() = runBlocking {
        val fake = FakeModelEngine()
        val gateA = fake.newGate()
        val gateB = fake.newGate()
        fake.enqueue(fakeGenerationScript { emit("a1 "); holdAt(gateA); emit("a2") })
        fake.enqueue(fakeGenerationScript { emit("b1 "); holdAt(gateB); emit("b2") })
        val jobA = async { fake.generate("pa") {} }
        withTimeout(5_000) { gateA.entered.await() }
        val jobB = async { fake.generate("pb") {} }
        withTimeout(5_000) { gateB.entered.await() }
        // Release B first: B completes while A is still parked, and the
        // global emission order records the true interleaving.
        gateB.release()
        assertEquals("b1 b2", withTimeout(5_000) { jobB.await() }.text)
        assertTrue(jobA.isActive)
        gateA.release()
        assertEquals("a1 a2", withTimeout(5_000) { jobA.await() }.text)
        assertEquals(listOf("a1 ", "b1 ", "b2", "a2"), fake.emittedTokens)
        assertEquals(listOf("pa", "pb"), fake.prompts)
    }

    @Test fun initializeFailureIsInjectableAndLifecycleIsRecorded() = runBlocking {
        val fake = FakeModelEngine()
        val boom = RuntimeException("init boom")
        fake.initializeFailure = boom
        try {
            fake.initialize()
            fail("the scripted initialize failure must throw")
        } catch (error: RuntimeException) {
            assertSame(boom, error)
        }
        fake.initializeFailure = null
        fake.initialize()
        assertEquals(2, fake.initializeCalls)
        fake.resetConversation()
        fake.resetConversation()
        assertEquals(2, fake.resetCalls)
        assertFalse(fake.closed)
        fake.close()
        assertTrue(fake.closed)
        assertEquals(1, fake.closeCalls)
        try {
            fake.generate("too late") {}
            fail("a closed fake must refuse generation")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("closed"))
        }
    }

    @Test fun toolPolicyUsesRealFlipSemanticsAndRecordsRequests() = runBlocking {
        val fake = FakeModelEngine()
        assertFalse(fake.toolsEnabled)
        assertTrue("first enable is a change", fake.setToolsEnabled(true))
        assertTrue(fake.toolsEnabled)
        assertFalse("re-enabling is a no-op", fake.setToolsEnabled(true))
        assertTrue("disabling is a change", fake.setToolsEnabled(false))
        assertEquals(listOf(true, true, false), fake.toolPolicyRequests)
        val boom = RuntimeException("policy boom")
        fake.setToolsEnabledFailure = boom
        try {
            fake.setToolsEnabled(true)
            fail("the scripted policy failure must throw")
        } catch (error: RuntimeException) {
            assertSame(boom, error)
        }
        assertFalse("a failed policy change must not flip the state", fake.toolsEnabled)
    }

    @Test fun toolResultsSubmissionsAreRecordedAndScripted() = runBlocking {
        val fake = FakeModelEngine()
        fake.enqueueReply("tool answer")
        val call = ToolCall("read_battery", "{}")
        val tokens = mutableListOf<String>()
        val result = fake.sendToolResults(listOf(call to "Battery is at 80%."), tokens::add)
        assertEquals("tool answer", result.text)
        assertEquals(listOf("tool answer"), tokens)
        val recorded = fake.calls.single()
        assertEquals("tool-results", recorded.kind)
        assertEquals(listOf(call to "Battery is at 80%."), recorded.toolResults)
    }

    @Test fun backendSurfaceDefaultsMatchTheConversationContract() = runBlocking {
        val fake = FakeModelEngine("unit-model")
        assertEquals("unit-model", fake.modelId)
        assertEquals("fake:unit-model", fake.inputContextDescription())
        assertFalse(fake.toolsEnabled)
        // Callback holders are inert until a consumer installs its own.
        fake.onPromptSubmitted("p", 0)
        fake.onInferenceProgress(InferenceProgress())
    }
}
