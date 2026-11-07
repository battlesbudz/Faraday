package com.battlesbudz.jarvis.v2.code

import com.battlesbudz.jarvis.v2.ai.FakeModelEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The console-error loop (scope Unit 4), driven against the real round
 * runner over [FakeModelEngine] with a scripted console source and a
 * scripted preview reload: errors produce a fix round whose prompt is
 * the error text, the 2-round automatic cap holds and resets on
 * Battles's message, a clean console produces nothing, the manual flow
 * runs the same path once through without the cap, and a fix round
 * that does not land stops the loop.
 */
class CodePreviewFixLoopTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var store: CodeProjectStore
    private lateinit var fake: FakeModelEngine
    private lateinit var runner: CodeRoundRunner
    private lateinit var project: CodeProjectStore.CodeProject
    private lateinit var dir: File
    private lateinit var console: ScriptedConsole
    private lateinit var loop: CodePreviewFixLoop
    private var reloads = 0

    /** What the reloaded page will report at the next check. */
    private var afterReload: List<CodeConsoleMessage> = emptyList()

    /** The loop's console feed: hands over [pending], then nothing until a reload refills it. */
    private class ScriptedConsole(var pending: List<CodeConsoleMessage>) : CodeConsoleErrors {
        override fun takeNewErrors(): List<CodeConsoleMessage> {
            val taken = pending
            pending = emptyList()
            return taken
        }
    }

    @Before fun setUp() {
        store = CodeProjectStore(temp.newFolder("workspace"))
        project = store.createProject("Snake", "a snake game", 1_000L)
        dir = store.projectDir(project.id)
        fake = FakeModelEngine()
        val controller = CodeSessionController(store, fake, contextTokens = null) { 2_000L }
        runner = CodeRoundRunner(store, controller, contextTokens = null) { 3_000L }
        console = ScriptedConsole(emptyList())
        reloads = 0
        afterReload = emptyList()
        loop = CodePreviewFixLoop(
            runner = runner,
            consoleErrors = console,
            reloadPreview = {
                reloads++
                console.pending = afterReload
            },
            settle = {}
        )
    }

    // -- Fixtures ---------------------------------------------------------------

    private val boom = CodeConsoleMessage(
        CodeConsoleLevel.ERROR,
        "Uncaught ReferenceError: foo is not defined",
        "https://appassets.androidplatform.net/index.html",
        12
    )

    private val fixedPage =
        "<!DOCTYPE html>\n<html lang=\"en\">\n<head><title>Snake</title></head>\n" +
            "<body><h1>Snake</h1><script>let speed = 1;</script></body>\n</html>"

    private fun writeAction(path: String, content: String): String =
        "<action name=\"write_file\"><path>$path</path><content>\n$content\n</content></action>"

    private fun afterRound(): CodeFixLoopResult = runBlocking { loop.afterRound(project) }

    private fun fixNow(): CodeFixLoopResult = runBlocking { loop.fixNow(project) }

    // -- The automatic path -------------------------------------------------------

    @Test fun cleanConsoleProducesNoFixRound() {
        val result = afterRound()
        assertEquals(CodeFixLoopResult.Clean(0), result)
        assertTrue(fake.calls.isEmpty())
        assertEquals(0, reloads)
        assertEquals(0, loop.autoFixRoundsUsed)
    }

    @Test fun errorsProduceOneFixRoundWhosePromptIsTheErrorText() {
        console.pending = listOf(boom)
        fake.enqueueReply(writeAction("index.html", fixedPage))
        val result = afterRound()
        assertEquals(CodeFixLoopResult.Clean(1), result)
        assertEquals(fixedPage, File(dir, "index.html").readText())
        assertEquals(1, fake.calls.size)
        assertTrue(fake.prompts.single().contains("foo is not defined"))
        assertEquals(1, loop.autoFixRoundsUsed)
        assertEquals(1, reloads)
    }

    @Test fun automaticCapStopsAtTwoRoundsAndReportsWhatRemains() {
        console.pending = listOf(boom)
        afterReload = listOf(boom) // the reloaded page keeps throwing
        fake.enqueueReply(writeAction("index.html", fixedPage))
        fake.enqueueReply(writeAction("index.html", fixedPage))
        val result = afterRound()
        assertTrue(result is CodeFixLoopResult.CapReached)
        val capped = result as CodeFixLoopResult.CapReached
        assertEquals(2, capped.fixRounds)
        assertEquals(listOf(boom), capped.remainingErrors)
        assertEquals(2, fake.calls.size)
        assertEquals(2, loop.autoFixRoundsUsed)
    }

    @Test fun battlesOwnMessageResetsTheAutomaticCap() {
        console.pending = listOf(boom)
        afterReload = listOf(boom)
        fake.enqueueReply(writeAction("index.html", fixedPage))
        fake.enqueueReply(writeAction("index.html", fixedPage))
        assertTrue(afterRound() is CodeFixLoopResult.CapReached)
        assertEquals(2, loop.autoFixRoundsUsed)

        loop.onUserMessage()
        assertEquals(0, loop.autoFixRoundsUsed)
        console.pending = listOf(boom) // his own round reloaded the preview; it still throws
        afterReload = emptyList() // the next fix finally holds
        fake.enqueueReply(writeAction("index.html", fixedPage))
        assertEquals(CodeFixLoopResult.Clean(1), afterRound())
        assertEquals(3, fake.calls.size)
    }

    @Test fun aFixRoundThatNeverLandsStopsTheLoop() {
        console.pending = listOf(boom)
        repeat(CodeRoundRunner.MAX_ATTEMPTS) { fake.enqueueReply("no actions here") }
        val result = afterRound()
        assertTrue(result is CodeFixLoopResult.Stopped)
        val stopped = result as CodeFixLoopResult.Stopped
        assertTrue(stopped.outcome is CodeRoundOutcome.Failed)
        assertEquals(1, stopped.fixRounds)
        // The runner spent its own 4 attempts inside the one fix round.
        assertEquals(CodeRoundRunner.MAX_ATTEMPTS, fake.calls.size)
        assertEquals(1, loop.autoFixRoundsUsed)
    }

    // -- The manual path ------------------------------------------------------------

    @Test fun manualFixRunsTheSamePathWithoutSpendingTheAutomaticBudget() {
        // Spend the automatic budget first.
        console.pending = listOf(boom)
        afterReload = listOf(boom)
        fake.enqueueReply(writeAction("index.html", fixedPage))
        fake.enqueueReply(writeAction("index.html", fixedPage))
        assertTrue(afterRound() is CodeFixLoopResult.CapReached)
        assertEquals(2, loop.autoFixRoundsUsed)

        // The button still runs, and the automatic budget is untouched by it.
        afterReload = emptyList()
        console.pending = listOf(boom)
        fake.enqueueReply(writeAction("index.html", fixedPage))
        assertEquals(CodeFixLoopResult.Clean(1), fixNow())
        assertEquals(2, loop.autoFixRoundsUsed)
    }

    @Test fun manualFixStopsWhenAnAppliedRoundChangesNothingObservable() {
        console.pending = listOf(boom)
        afterReload = listOf(boom) // the reload reports the identical throw
        fake.enqueueReply(writeAction("index.html", fixedPage))
        fake.enqueueReply(writeAction("index.html", fixedPage))
        val result = fixNow()
        assertTrue(result is CodeFixLoopResult.NoProgress)
        val noProgress = result as CodeFixLoopResult.NoProgress
        assertEquals(1, noProgress.fixRounds)
        assertEquals(listOf(boom), noProgress.errors)
        assertEquals(1, fake.calls.size) // the second reply was never consumed
    }
}
