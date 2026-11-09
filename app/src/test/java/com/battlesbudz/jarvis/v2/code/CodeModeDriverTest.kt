package com.battlesbudz.jarvis.v2.code

import com.battlesbudz.jarvis.v2.ai.CodeModeEngine
import com.battlesbudz.jarvis.v2.ai.FakeModelEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Code screen's wiring (scope Unit 6), driven against the real
 * store, runner, and fix loop over [FakeModelEngine]: a user round
 * flows to the gated commit, an applied round reloads the preview
 * and runs the automatic fix pass, the manual pass runs uncapped,
 * a refused borrow runs nothing, and every operation lands in the
 * diagnostics counters with retries measured from the round log.
 */
class CodeModeDriverTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var store: CodeProjectStore
    private lateinit var stats: CodeStatsStore
    private lateinit var fake: FakeModelEngine
    private lateinit var driver: CodeModeDriver
    private lateinit var project: CodeProjectStore.CodeProject
    private lateinit var dir: File
    private lateinit var console: CodeConsoleStore
    private var reloads = 0

    /** Hands the block the fake engine, or refuses the borrow when [engine] is null. */
    private class FakeAccess(
        private val engine: CodeModeEngine?,
        private val tokens: Int? = null,
    ) : CodeEngineAccess {
        override suspend fun <T> withEngine(block: suspend (CodeModeEngine, Int?) -> T): T? =
            engine?.let { block(it, tokens) }
    }

    @Before fun setUp() {
        val workspace = temp.newFolder("workspace")
        store = CodeProjectStore(workspace)
        stats = CodeStatsStore(workspace)
        project = store.createProject("Snake", "a snake game", 1_000L)
        dir = store.projectDir(project.id)
        fake = FakeModelEngine()
        console = CodeConsoleStore()
        reloads = 0
        driver = CodeModeDriver(
            store = store,
            stats = stats,
            engineAccess = FakeAccess(fake),
            nowMillis = { 9_000L },
            loopSettle = {},
        )
    }

    private val fixedPage =
        "<!DOCTYPE html>\n<html lang=\"en\">\n<head><title>Snake</title></head>\n" +
            "<body><h1>Snake</h1><script>let speed = 1;</script></body>\n</html>"

    private fun writeAction(path: String, content: String): String =
        "<action name=\"write_file\"><path>$path</path><content>\n$content\n</content></action>"

    private fun replyAction(text: String): String = "<action name=\"reply\">$text</action>"

    /** The reload the screen would do: the first one surfaces the page's runtime error. */
    private val reloadSurfacingError: suspend () -> Unit = {
        reloads++
        if (reloads == 1) {
            console.add(
                CodeConsoleLevel.ERROR,
                "Uncaught ReferenceError: foo is not defined",
                "https://appassets.androidplatform.net/index.html",
                12,
            )
        }
    }

    private val plainReload: suspend () -> Unit = { reloads++ }

    private fun send(request: String, reload: suspend () -> Unit = plainReload): CodeOperationReport =
        runBlocking { driver.sendUserRound(project, request, console, reload) }

    // -- The round flow -----------------------------------------------------------

    @Test fun anAppliedRoundReloadsThePreviewAndRunsTheAutomaticFixPass() {
        fake.enqueueReply(writeAction("index.html", fixedPage)) // Battles's round
        fake.enqueueReply(writeAction("index.html", fixedPage)) // the fix round
        val report = send("make the snake game", reloadSurfacingError)
        assertTrue(report is CodeOperationReport.Round)
        val round = report as CodeOperationReport.Round
        assertTrue(round.outcome is CodeRoundOutcome.Applied)
        assertEquals(CodeFixLoopResult.Clean(1), round.fixResult)
        assertEquals(2, round.attempts)
        assertEquals(fixedPage, File(dir, "index.html").readText())
        // One reload after Battles's round, one after the fix round.
        assertEquals(2, reloads)
        val snapshot = stats.snapshot()
        assertEquals(1, snapshot.rounds)
        assertEquals(1, snapshot.fixRounds)
        assertEquals(0, snapshot.retries)
        assertTrue(snapshot.lastOutcome!!.contains("round applied on Snake"))
        assertTrue(snapshot.lastOutcome!!.contains("console clean after 1 fix round(s)"))
    }

    @Test fun retriesAreCountedFromTheRoundLog() {
        fake.enqueueReply("no actions here") // attempt one does not parse
        fake.enqueueReply(writeAction("index.html", fixedPage)) // the retry lands
        val report = send("make the snake game")
        val round = report as CodeOperationReport.Round
        assertTrue(round.outcome is CodeRoundOutcome.Applied)
        assertEquals(2, (round.outcome as CodeRoundOutcome.Applied).attempts)
        assertEquals(CodeFixLoopResult.Clean(0), round.fixResult)
        val snapshot = stats.snapshot()
        assertEquals(1, snapshot.rounds)
        assertEquals(1, snapshot.retries)
        assertEquals(0, snapshot.fixRounds)
    }

    @Test fun aReplyRoundWritesNothingAndRunsNoFixPass() {
        fake.enqueueReply(replyAction("It is a snake game."))
        val report = send("what is this?")
        val round = report as CodeOperationReport.Round
        assertTrue(round.outcome is CodeRoundOutcome.Replied)
        assertNull(round.fixResult)
        assertEquals(0, reloads)
        assertEquals(1, stats.snapshot().rounds)
        assertTrue(stats.snapshot().lastOutcome!!.contains("model replied on Snake"))
    }

    @Test fun theLiveRoundTracksTheRoundInFlightAndClearsAfter() {
        // The state the Code screen renders while a round runs (and
        // re-renders on re-entry): owned by the driver, set for the
        // round's whole life, cleared when it ends. Build 1771 kept
        // this in the project view, whose disposal cancelled the
        // round silently — rounds: 0 in Battles's diagnostics after a
        // generation he watched stream.
        fake.enqueueReply(writeAction("index.html", fixedPage))
        val seenInFlight = mutableListOf<String?>()
        val report = runBlocking {
            driver.sendUserRound(project, "make the snake game", console, plainReload) {
                seenInFlight += driver.liveRound.value?.projectId
            }
        }
        assertTrue(report is CodeOperationReport.Round)
        assertTrue(seenInFlight.isNotEmpty())
        assertTrue(seenInFlight.all { it == project.id })
        assertTrue(driver.liveRound.value == null)
        assertTrue(!driver.roundInFlightFor(project.id))
    }

    @Test fun aRoundThatNeverLandsRecordsTheFailureAndItsRetries() {
        repeat(CodeRoundRunner.MAX_ATTEMPTS) { fake.enqueueReply("no actions here") }
        val report = send("make the snake game")
        val round = report as CodeOperationReport.Round
        assertTrue(round.outcome is CodeRoundOutcome.Failed)
        val snapshot = stats.snapshot()
        assertEquals(1, snapshot.rounds)
        assertEquals(CodeRoundRunner.MAX_ATTEMPTS - 1, snapshot.retries)
        assertEquals("round failed on Snake", snapshot.lastOutcome)
        assertTrue(snapshot.lastError!!.isNotBlank())
    }

    @Test fun aBudgetRefusedRoundRecordsTheRefusal() {
        val smallModelDriver = CodeModeDriver(
            store = store,
            stats = stats,
            engineAccess = FakeAccess(fake, tokens = 10),
            nowMillis = { 9_000L },
            loopSettle = {},
        )
        val report = runBlocking { smallModelDriver.sendUserRound(project, "make it", console, plainReload) }
        val round = report as CodeOperationReport.Round
        assertTrue(round.outcome is CodeRoundOutcome.Refused)
        assertEquals(0, round.attempts)
        assertTrue(fake.calls.isEmpty())
        val snapshot = stats.snapshot()
        assertEquals(1, snapshot.rounds)
        assertEquals(0, snapshot.retries)
        assertTrue(snapshot.lastError!!.contains("context"))
    }

    // -- The borrow -----------------------------------------------------------------

    @Test fun aRefusedBorrowRunsNothingAndRecordsNothing() {
        val refusedDriver = CodeModeDriver(
            store = store,
            stats = stats,
            engineAccess = FakeAccess(null),
            nowMillis = { 9_000L },
            loopSettle = {},
        )
        val report = runBlocking { refusedDriver.sendUserRound(project, "make it", console, plainReload) }
        assertTrue(report is CodeOperationReport.Unavailable)
        assertTrue(fake.calls.isEmpty())
        assertEquals(CodeStatsStore.Stats(0, 0, 0, 0, null, null), stats.snapshot())
    }

    // -- The manual fix pass -----------------------------------------------------------

    @Test fun theManualFixPassRunsTheLoopAndRecordsIt() {
        console.add(
            CodeConsoleLevel.ERROR,
            "Uncaught ReferenceError: foo is not defined",
            "https://appassets.androidplatform.net/index.html",
            12,
        )
        fake.enqueueReply(writeAction("index.html", fixedPage))
        val report = runBlocking { driver.fixErrors(project, console, plainReload) }
        assertTrue(report is CodeOperationReport.Fix)
        assertEquals(CodeFixLoopResult.Clean(1), (report as CodeOperationReport.Fix).result)
        val snapshot = stats.snapshot()
        assertEquals(0, snapshot.rounds)
        assertEquals(1, snapshot.fixRounds)
        assertTrue(snapshot.lastOutcome!!.startsWith("manual fix on Snake"))
    }

    // -- The session lifecycle -----------------------------------------------------------

    @Test fun switchingProjectsReleasesTheOldSessionAndReleaseEndsTheLiveOne() {
        val other = store.createProject("Tank", "a tank game", 2_000L)
        fake.enqueueReply(replyAction("one"))
        send("first")
        assertEquals(0, fake.releaseCodeCalls)

        fake.enqueueReply(replyAction("two"))
        runBlocking { driver.sendUserRound(other, "second", console, plainReload) }
        // Building Tank's session released Snake's conversation first.
        assertEquals(1, fake.releaseCodeCalls)

        runBlocking { driver.releaseSession(fake) }
        assertEquals(2, fake.releaseCodeCalls)

        // A release naming a different engine drops the stale session
        // without touching either engine: the session's engine is gone,
        // and its conversation died with it.
        fake.enqueueReply(replyAction("three"))
        send("third")
        runBlocking { driver.releaseSession(FakeModelEngine()) }
        assertEquals(2, fake.releaseCodeCalls)
        runBlocking { driver.releaseSession(fake) }
        assertEquals(2, fake.releaseCodeCalls)
    }
}
