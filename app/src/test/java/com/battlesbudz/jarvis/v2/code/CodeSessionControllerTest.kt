package com.battlesbudz.jarvis.v2.code

import com.battlesbudz.jarvis.v2.ai.FakeModelEngine
import com.battlesbudz.jarvis.v2.ai.fakeGenerationScript
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Session policy against [FakeModelEngine] (scope Unit 2): a scripted
 * fake-engine drive produces correct parsed action sets from canned
 * outputs — including a truncated-then-continued one — and the reuse
 * rule of section 3.5 holds exactly: reuse only when the fingerprint
 * and the conversation generation both match and the room estimate
 * covers the turn; any drift forces a reset and a full snapshot.
 */
class CodeSessionControllerTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var store: CodeProjectStore
    private lateinit var fake: FakeModelEngine
    private lateinit var controller: CodeSessionController
    private lateinit var project: CodeProjectStore.CodeProject

    @Before fun setUp() {
        store = CodeProjectStore(temp.newFolder("workspace"))
        project = store.createProject("Snake", "a snake game", 1_000L)
        fake = FakeModelEngine()
        controller = CodeSessionController(store, fake, contextTokens = null) { 2_000L }
    }

    private fun parsed(result: CodeRoundResult): CodeRoundResult.Parsed {
        assertTrue("expected Parsed, was $result", result is CodeRoundResult.Parsed)
        return result as CodeRoundResult.Parsed
    }

    private fun replyRound(request: String): CodeRoundResult =
        runBlocking { controller.runRound(project, request) }

    @Test fun firstRoundSendsTheFullSnapshotAndReturnsParsedActions() {
        fake.enqueueReply(
            "<action name=\"write_file\"><path>index.html</path><content>" +
                "<!DOCTYPE html>\n<html><body>snake</body></html></content></action>"
        )
        val result = parsed(replyRound("make a snake game"))
        assertFalse(result.reusedSession)
        assertEquals(0, result.continuationsUsed)
        assertEquals(
            listOf(CodeAction.WriteFile("index.html", "<!DOCTYPE html>\n<html><body>snake</body></html>")),
            result.actions
        )
        assertEquals(1, fake.prepareCodeCalls)
        assertEquals(1, fake.calls.size)
        val prompt = fake.prompts.single()
        assertTrue(prompt.contains("## Project brief"))
        assertTrue(prompt.contains("## The user's request"))
        assertTrue(prompt.contains("make a snake game"))
        val log = store.readRoundLog(project)
        assertEquals(1, log.size)
        assertTrue(log.single().outcome.startsWith("parsed: write index.html"))
    }

    @Test fun unchangedProjectReusesTheLiveConversation() {
        fake.enqueueReply("<action name=\"reply\">It is a snake game.</action>")
        fake.enqueueReply("<action name=\"reply\">It moves with arrows.</action>")
        val first = parsed(replyRound("what is this app?"))
        val second = parsed(replyRound("how does it move?"))
        assertFalse(first.reusedSession)
        assertTrue(second.reusedSession)
        assertEquals(1, fake.prepareCodeCalls)
        assertEquals(2, fake.calls.size)
        val shortPrompt = fake.prompts[1]
        assertFalse(shortPrompt.contains("## Project brief"))
        assertTrue(shortPrompt.startsWith("how does it move?"))
        assertTrue(shortPrompt.contains(CodeSystemPrompt.TURN_REMINDER))
    }

    @Test fun writeRoundForcesAFreshSnapshotNextRound() {
        fake.enqueueReply(
            "<action name=\"write_file\"><path>index.html</path><content>" +
                "<!DOCTYPE html>\n<html><body>v2</body></html></content></action>"
        )
        fake.enqueueReply("<action name=\"reply\">Done.</action>")
        parsed(replyRound("build it"))
        // Nothing was applied (Unit 3's job) — the invalidation is the
        // controller's own rule: a write round changes the fingerprint
        // by definition, so the next turn re-snapshots (section 3.5).
        val second = parsed(replyRound("thanks"))
        assertFalse(second.reusedSession)
        assertEquals(2, fake.prepareCodeCalls)
        assertTrue(fake.prompts[1].contains("## Project brief"))
    }

    @Test fun externalFileChangeForcesAFreshSnapshot() {
        fake.enqueueReply("<action name=\"reply\">Hi.</action>")
        fake.enqueueReply("<action name=\"reply\">Hi again.</action>")
        parsed(replyRound("hello"))
        File(store.projectDir(project.id), "notes.txt").writeText("changed outside the session")
        val second = parsed(replyRound("hello again"))
        assertFalse(second.reusedSession)
        assertEquals(2, fake.prepareCodeCalls)
        assertTrue(fake.prompts[1].contains("## Project brief"))
    }

    @Test fun engineResetBetweenRoundsForcesAFreshSnapshot() = runBlocking {
        fake.enqueueReply("<action name=\"reply\">Hi.</action>")
        fake.enqueueReply("<action name=\"reply\">Hi again.</action>")
        parsed(replyRound("hello"))
        // An intervening chat or voice turn resets the conversation:
        // the generation moves and code mode drops.
        fake.resetConversation()
        val second = parsed(replyRound("hello again"))
        assertFalse(second.reusedSession)
        assertEquals(2, fake.prepareCodeCalls)
        assertTrue(fake.prompts[1].contains("## Project brief"))
    }

    @Test fun switchingProjectsForcesAFreshSnapshot() {
        val other = store.createProject("Other", "another app", 3_000L)
        fake.enqueueReply("<action name=\"reply\">One.</action>")
        fake.enqueueReply("<action name=\"reply\">Two.</action>")
        fake.enqueueReply("<action name=\"reply\">Three.</action>")
        runBlocking {
            parsed(controller.runRound(project, "first"))
            parsed(controller.runRound(other, "second"))
            val third = parsed(controller.runRound(project, "third"))
            assertFalse(third.reusedSession)
        }
        assertEquals(3, fake.prepareCodeCalls)
    }

    @Test fun truncatedResponseIsContinuedAndStitchedIntoOneActionStream() {
        fake.enqueueReply(
            "<action name=\"write_file\"><path>index.html</path><content>" +
                "<!DOCTYPE html>\n<html><body><h1>Sna"
        )
        fake.enqueueReply("ke</h1></body></html></content></action>")
        val result = parsed(replyRound("make a snake game"))
        assertEquals(1, result.continuationsUsed)
        assertEquals(2, fake.calls.size)
        assertTrue(fake.prompts[1].startsWith("Continue from the exact next character"))
        val write = result.actions.single() as CodeAction.WriteFile
        assertEquals("<!DOCTYPE html>\n<html><body><h1>Snake</h1></body></html>", write.content)
    }

    @Test fun continuationStopsAtTheCap() {
        fake.fallbackScript = fakeGenerationScript {
            emit("<action name=\"write_file\"><path>index.html</path><content>partial ")
        }
        val result = replyRound("make a snake game")
        assertTrue("expected Failed, was $result", result is CodeRoundResult.Failed)
        val failedResult = result as CodeRoundResult.Failed
        assertEquals(CodeContinuation.MAX_CONTINUATIONS, failedResult.continuationsUsed)
        assertEquals(1 + CodeContinuation.MAX_CONTINUATIONS, fake.calls.size)
    }

    @Test fun roomExhaustionForcesAFreshSnapshot() {
        val longReply = "<action name=\"reply\">" + "x".repeat(4_000) + "</action>"
        fake.enqueueReply(longReply)
        fake.enqueueReply("<action name=\"reply\">Short.</action>")
        parsed(replyRound("tell me a long story"))
        val second = parsed(replyRound("and then?"))
        assertFalse(second.reusedSession)
        assertEquals(2, fake.prepareCodeCalls)
    }

    @Test fun budgetRefusalSendsNothing() = runBlocking {
        val refusing = CodeSessionController(store, fake, contextTokens = 100) { 2_000L }
        val result = refusing.runRound(project, "make a snake game")
        assertTrue(result is CodeRoundResult.Refused)
        assertTrue(fake.calls.isEmpty())
        assertEquals(0, fake.prepareCodeCalls)
    }

    @Test fun generationFailureFailsTheRoundAndTheNextRoundReprepares() {
        fake.enqueueFailure(RuntimeException("boom"))
        val first = replyRound("make a snake game")
        assertTrue("expected Failed, was $first", first is CodeRoundResult.Failed)
        val failedResult = first as CodeRoundResult.Failed
        assertTrue(failedResult.reason.contains("Generation failed: boom"))
        fake.enqueueReply("<action name=\"reply\">Recovered.</action>")
        val second = parsed(replyRound("try again"))
        assertFalse(second.reusedSession)
        assertEquals(2, fake.prepareCodeCalls)
    }

    @Test fun releaseForgetsTheSession() = runBlocking {
        fake.enqueueReply("<action name=\"reply\">Hi.</action>")
        fake.enqueueReply("<action name=\"reply\">Hi again.</action>")
        parsed(replyRound("hello"))
        controller.release()
        assertEquals(1, fake.releaseCodeCalls)
        assertFalse(fake.codeModeActive)
        val second = parsed(replyRound("hello again"))
        assertFalse(second.reusedSession)
        assertEquals(2, fake.prepareCodeCalls)
    }

    @Test fun roundLogRecordsParsedAndFailedRounds() {
        fake.enqueueReply("<action name=\"reply\">Hi.</action>")
        fake.enqueueReply("I cannot do that today.")
        parsed(replyRound("hello"))
        val second = replyRound("do the thing")
        assertTrue(second is CodeRoundResult.Failed)
        val log = store.readRoundLog(project)
        assertEquals(2, log.size)
        assertEquals("hello", log[0].request)
        assertTrue(log[0].outcome.startsWith("parsed: reply"))
        assertEquals("do the thing", log[1].request)
        assertTrue(log[1].outcome.startsWith("failed:"))
    }

    @Test fun progressReportsTheFileBeingWritten() = runBlocking {
        fake.enqueue(fakeGenerationScript {
            emit("<action name=\"write_file\"><path>inde")
            emit("x.html</path><content><!DOCTYPE html><html><body>abc")
            emit("</body></html></content></action>")
        })
        val seen = mutableListOf<String?>()
        val result = controller.runRound(project, "build it") { progress ->
            seen += progress.writingFilePath
        }
        assertTrue(result is CodeRoundResult.Parsed)
        assertTrue("progress must name the file mid-write", seen.contains("index.html"))
    }
}
