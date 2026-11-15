package com.battlesbudz.jarvis.v2.code

import com.battlesbudz.jarvis.v2.ai.FakeModelEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The full round, driven against [FakeModelEngine] and a real store in
 * a temp folder (scope Unit 3): normalizers, the edit applier, the
 * completeness gate, the 4-attempt retry loop with session resets, and
 * the atomic apply — including the byte-identical-after-failure test
 * that is this unit's reason to exist.
 */
class CodeRoundRunnerTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var store: CodeProjectStore
    private lateinit var fake: FakeModelEngine
    private lateinit var controller: CodeSessionController
    private lateinit var runner: CodeRoundRunner
    private lateinit var project: CodeProjectStore.CodeProject
    private lateinit var dir: File

    @Before fun setUp() {
        store = CodeProjectStore(temp.newFolder("workspace"))
        project = store.createProject("Snake", "a snake game", 1_000L)
        dir = store.projectDir(project.id)
        fake = FakeModelEngine()
        controller = CodeSessionController(store, fake, contextTokens = null) { 2_000L }
        runner = CodeRoundRunner(store, controller, contextTokens = null) { 3_000L }
    }

    // -- Fixtures ---------------------------------------------------------------

    private val wholePageV2 =
        "<!DOCTYPE html>\n<html lang=\"en\">\n<head><title>Snake</title></head>\n" +
            "<body><h1>Snake</h1><script>let speed = 1;</script></body>\n</html>"

    /** A page that fails the gate (unclosed script) without tripping continuation. */
    private val pageWithUnclosedScript =
        "<!DOCTYPE html>\n<html><body><script>let speed = 1;</body></html>"

    private fun writeAction(path: String, content: String): String =
        "<action name=\"write_file\"><path>$path</path><content>\n$content\n</content></action>"

    private fun editAction(path: String, vararg pairs: Pair<String, String>): String = buildString {
        append("<action name=\"edit_file\"><path>").append(path).append("</path>")
        for ((find, replace) in pairs) {
            append("<find>\n").append(find).append("\n</find>")
            append("<replace>\n").append(replace).append("\n</replace>")
        }
        append("</action>")
    }

    private fun briefAction(content: String): String =
        "<action name=\"update_brief\"><content>\n$content\n</content></action>"

    private fun replyAction(text: String): String = "<action name=\"reply\">$text</action>"

    private fun run(request: String): CodeRoundOutcome =
        runBlocking { runner.runRound(project, request) }

    private fun applied(outcome: CodeRoundOutcome): CodeRoundOutcome.Applied {
        assertTrue("expected Applied, was $outcome", outcome is CodeRoundOutcome.Applied)
        return outcome as CodeRoundOutcome.Applied
    }

    /** Every file under the project, relative path to bytes (the whole folder, versions included). */
    private fun treeBytes(): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                out[file.relativeTo(dir).invariantSeparatorsPath] = file.readBytes()
            }
        }
        return out
    }

    // -- Happy paths --------------------------------------------------------------

    @Test fun passingWriteRoundAppliesSnapshotsAndRegeneratesTheBriefFiles() {
        fake.enqueueReply(writeAction("index.html", wholePageV2))
        val outcome = applied(run("make a snake game"))
        assertEquals(listOf("index.html"), outcome.writtenPaths)
        assertFalse(outcome.briefUpdated)
        assertEquals(1, outcome.version)
        assertEquals(1, outcome.attempts)
        assertEquals(wholePageV2, File(dir, "index.html").readText())
        // The version snapshot holds the pre-round starter page.
        assertEquals(listOf(1), store.listVersions(project.id))
        val snapshot = File(dir, "versions/v001/index.html")
        assertEquals(CodeProjectStore.STARTER_HTML, snapshot.readText())
        // The round counter moved and the brief's Files section names the new size.
        assertEquals(1, store.loadProject(project.id)?.rounds)
        val brief = store.readBrief(project)
        assertEquals(
            listOf(ProjectFileEntry("index.html", wholePageV2.toByteArray().size.toLong())),
            brief?.files?.filter { it.path == "index.html" }
        )
    }

    @Test fun exactEditApplies() {
        fake.enqueueReply(editAction("index.html", "<h1>New app</h1>" to "<h1>Snake</h1>"))
        val outcome = applied(run("rename the heading"))
        assertEquals(listOf("index.html"), outcome.writtenPaths)
        assertTrue(File(dir, "index.html").readText().contains("<h1>Snake</h1>"))
    }

    @Test fun differentlyIndentedEditAppliesViaTheLineMatcher() {
        // The find block quotes the starter page's style block as it
        // stands on main (the complete fix appended the dark-theme
        // colors, wrapping the body rule across two lines) at a
        // different indentation than the file carries it.
        val find = "<style>\n" +
            "body { font-family: sans-serif; margin: 2em auto; max-width: 32em; padding: 0 1em; text-align: center;\n" +
            "background: #1c1b1f; color: #e6e1e5; }\n" +
            "</style>"
        val replace = "<style>\nbody { font-family: monospace; }\n</style>"
        fake.enqueueReply(editAction("index.html", find to replace))
        applied(run("restyle it"))
        val text = File(dir, "index.html").readText()
        assertTrue(text.contains("font-family: monospace"))
        assertFalse(text.contains("sans-serif"))
    }

    @Test fun secondRoundSnapshotsTheFirstRoundsResult() {
        val pageA = wholePageV2.replace("<h1>Snake</h1>", "<h1>Snake A</h1>")
        val pageB = wholePageV2.replace("<h1>Snake</h1>", "<h1>Snake B</h1>")
        fake.enqueueReply(writeAction("index.html", pageA))
        fake.enqueueReply(writeAction("index.html", pageB))
        applied(run("build it"))
        applied(run("change the title"))
        assertEquals(listOf(1, 2), store.listVersions(project.id))
        assertEquals(pageA, File(dir, "versions/v002/index.html").readText())
        assertEquals(pageB, File(dir, "index.html").readText())
        assertEquals(2, store.loadProject(project.id)?.rounds)
    }

    @Test fun replyOnlyRoundChangesNothing() {
        val before = treeBytes()
        fake.enqueueReply(replyAction("It is a snake game."))
        val outcome = run("what is this app?")
        assertTrue(outcome is CodeRoundOutcome.Replied)
        assertEquals("It is a snake game.", (outcome as CodeRoundOutcome.Replied).text)
        assertTrue(store.listVersions(project.id).isEmpty())
        assertEquals(0, store.loadProject(project.id)?.rounds)
        val after = treeBytes()
        assertEquals(before.keys, after.keys - CodeProjectStore.ROUND_LOG_FILE_NAME)
        for ((path, bytes) in before) assertArrayEquals(bytes, after[path])
    }

    // -- Repairs --------------------------------------------------------------------

    @Test fun unrequestedSplitIsConsolidatedIntoIndex() {
        val index = "<!DOCTYPE html>\n<html><head><link rel=\"stylesheet\" href=\"style.css\"></head>" +
            "<body><h1>Snake</h1><script src=\"app.js\"></script></body></html>"
        fake.enqueueReply(
            writeAction("index.html", index) +
                writeAction("style.css", "body { color: red; }") +
                writeAction("app.js", "let a = 1;")
        )
        val outcome = applied(run("build a snake game"))
        assertEquals(listOf("index.html"), outcome.writtenPaths)
        assertFalse(File(dir, "style.css").exists())
        assertFalse(File(dir, "app.js").exists())
        val written = File(dir, "index.html").readText()
        assertTrue(written.contains("<style>\nbody { color: red; }\n</style>"))
        assertTrue(written.contains("<script>\nlet a = 1;\n</script>"))
        assertFalse(written.contains("href=\"style.css\""))
        assertFalse(written.contains("src=\"app.js\""))
    }

    @Test fun requestedSplitStaysSplit() {
        val index = "<!DOCTYPE html>\n<html><head><link rel=\"stylesheet\" href=\"style.css\"></head>" +
            "<body><h1>Snake</h1><script src=\"app.js\"></script></body></html>"
        fake.enqueueReply(
            writeAction("index.html", index) +
                writeAction("style.css", "body { color: red; }") +
                writeAction("app.js", "let a = 1;")
        )
        val outcome = applied(run("build it with the styles in style.css and the code in app.js"))
        assertEquals(listOf("app.js", "index.html", "style.css"), outcome.writtenPaths)
        assertEquals("body { color: red; }", File(dir, "style.css").readText())
        assertEquals("let a = 1;", File(dir, "app.js").readText())
        assertTrue(File(dir, "index.html").readText().contains("href=\"style.css\""))
    }

    @Test fun javaScriptInsideCssIsUnmixedIntoAppJs() {
        val index = "<!DOCTYPE html>\n<html><head><link rel=\"stylesheet\" href=\"style.css\"></head>" +
            "<body><h1>Snake</h1></body></html>"
        val mixedCss = listOf(
            "body { margin: 0; }",
            "const board = document.getElementById('board');",
            "board.addEventListener('click', () => {",
            "  board.textContent = 'go';",
            "});"
        ).joinToString("\n")
        fake.enqueueReply(writeAction("index.html", index) + writeAction("style.css", mixedCss))
        val outcome = applied(run("build it with a separate style.css file"))
        assertEquals(listOf("app.js", "index.html", "style.css"), outcome.writtenPaths)
        val css = File(dir, "style.css").readText()
        assertTrue(css.contains("margin: 0"))
        assertFalse(css.contains("addEventListener"))
        val js = File(dir, "app.js").readText()
        assertTrue(js.contains("const board = document.getElementById('board');"))
        assertTrue(js.contains("addEventListener"))
        assertTrue(File(dir, "index.html").readText().contains("<script src=\"app.js\"></script>"))
    }

    @Test fun fencedBodyIsStoredWithoutTheFence() {
        fake.enqueueReply(writeAction("index.html", "```html\n$wholePageV2\n```"))
        applied(run("build it"))
        assertEquals(wholePageV2, File(dir, "index.html").readText())
    }

    @Test fun cdataBodyIsStoredWithoutTheWrapper() {
        fake.enqueueReply(writeAction("index.html", "<![CDATA[$wholePageV2]]>"))
        applied(run("build it"))
        assertEquals(wholePageV2, File(dir, "index.html").readText())
    }

    // -- Retries ----------------------------------------------------------------------

    @Test fun unmatchedFindRetriesWithTargetedFeedbackThenTheWriteApplies() {
        fake.enqueueReply(editAction("index.html", "this text does not exist" to "replacement"))
        fake.enqueueReply(writeAction("index.html", wholePageV2))
        val outcome = applied(run("change the heading"))
        assertEquals(2, outcome.attempts)
        // Each retry ran on a reset conversation: two preparations total.
        assertEquals(2, fake.prepareCodeCalls)
        val retryPrompt = fake.prompts[1]
        assertTrue(retryPrompt.contains("was not found in index.html"))
        assertTrue(retryPrompt.contains("this text does not exist"))
        assertTrue(retryPrompt.contains("Return write_file with the complete updated index.html instead."))
        assertEquals(wholePageV2, File(dir, "index.html").readText())
        // Only the successful attempt committed: exactly one version.
        assertEquals(listOf(1), store.listVersions(project.id))
    }

    @Test fun incompletePageRetriesWithTheSpecificProblemThenPasses() {
        fake.enqueueReply(writeAction("index.html", pageWithUnclosedScript))
        fake.enqueueReply(writeAction("index.html", wholePageV2))
        val outcome = applied(run("build it"))
        assertEquals(2, outcome.attempts)
        assertTrue(fake.prompts[1].contains("has an unclosed <script> tag"))
        assertEquals(wholePageV2, File(dir, "index.html").readText())
        assertEquals(listOf(1), store.listVersions(project.id))
    }

    @Test fun escapingPathIsRefusedAndTheRetryStaysInsideTheProject() {
        fake.enqueueReply(writeAction("../evil.html", wholePageV2))
        fake.enqueueReply(writeAction("index.html", wholePageV2))
        val outcome = applied(run("build it"))
        assertEquals(2, outcome.attempts)
        assertTrue(fake.prompts[1].contains("is not allowed"))
        assertFalse(File(store.projectsDir, "evil.html").exists())
        assertEquals(wholePageV2, File(dir, "index.html").readText())
    }

    @Test fun allFourAttemptsFailAndTheProjectIsByteIdentical() {
        val before = treeBytes()
        repeat(CodeRoundRunner.MAX_ATTEMPTS) {
            fake.enqueueReply(writeAction("index.html", pageWithUnclosedScript))
        }
        val outcome = run("build a snake game")
        assertTrue("expected Failed, was $outcome", outcome is CodeRoundOutcome.Failed)
        val failed = outcome as CodeRoundOutcome.Failed
        assertEquals(CodeRoundRunner.MAX_ATTEMPTS, failed.attempts)
        assertTrue(failed.reason.contains("unclosed <script>"))
        // Nothing the gate protects moved: every pre-existing file is
        // byte-identical, no version was taken, the counter never moved.
        val after = treeBytes()
        assertEquals(before.keys, after.keys - CodeProjectStore.ROUND_LOG_FILE_NAME)
        for ((path, bytes) in before) {
            assertArrayEquals("bytes changed for $path", bytes, after[path])
        }
        assertTrue(store.listVersions(project.id).isEmpty())
        assertEquals(0, store.loadProject(project.id)?.rounds)
        // The one file that grows is the round log, the designated
        // record of failed rounds: exactly one entry per attempt.
        assertEquals(CodeRoundRunner.MAX_ATTEMPTS, store.readRoundLog(project).size)
        assertEquals(CodeRoundRunner.MAX_ATTEMPTS, fake.calls.size)
        assertEquals(CodeRoundRunner.MAX_ATTEMPTS, fake.prepareCodeCalls)
    }

    @Test fun unbalancedJavaScriptFailsTheGateAndRetries() {
        fake.enqueueReply(writeAction("app.js", "function f() { return 1;"))
        fake.enqueueReply(writeAction("app.js", "function f() { return 1; }"))
        val outcome = applied(run("write the logic in app.js"))
        assertEquals(2, outcome.attempts)
        assertTrue(fake.prompts[1].contains("unbalanced braces"))
        assertEquals("function f() { return 1; }", File(dir, "app.js").readText())
    }

    @Test fun anEditedPreExistingFileIsExemptFromTheGate() {
        // The page on disk is already broken (no </html>); a surgical
        // edit to it is not re-judged for the file's pre-existing state.
        File(dir, "index.html").writeText("<!DOCTYPE html>\n<html><body><h1>Old</h1></body>")
        fake.enqueueReply(editAction("index.html", "<h1>Old</h1>" to "<h1>New</h1>"))
        applied(run("rename the heading"))
        assertTrue(File(dir, "index.html").readText().contains("<h1>New</h1>"))
    }

    // -- The brief ----------------------------------------------------------------------

    @Test fun briefUpdateAppliesAndItsFilesSectionIsRegenerated() {
        val briefText = listOf(
            "# Project brief",
            "",
            "## Goal",
            "A snake game with levels",
            "",
            "## Files",
            "- `ghost.txt` — 1 B",
            "",
            "## Decisions",
            "- Use a dark board",
            "",
            "## Known problems",
            "",
            "## Next steps",
            "- Add a pause button"
        ).joinToString("\n")
        fake.enqueueReply(briefAction(briefText))
        val outcome = applied(run("note the plan"))
        assertTrue(outcome.briefUpdated)
        assertEquals(emptyList<String>(), outcome.writtenPaths)
        assertEquals(1, outcome.version)
        val brief = store.readBrief(project)
        assertEquals("A snake game with levels", brief?.goal)
        assertEquals(listOf("Use a dark board"), brief?.decisions)
        // The model's Files section was overwritten with the real map.
        assertTrue(brief?.files?.any { it.path == "index.html" } == true)
        assertTrue(brief?.files?.none { it.path == "ghost.txt" } == true)
        // The pre-round brief is in the version snapshot.
        val snapshotBrief = File(dir, "versions/v001/BRIEF.md").readText()
        assertTrue(snapshotBrief.contains("a snake game"))
        assertEquals(1, store.loadProject(project.id)?.rounds)
    }

    @Test fun briefMissingASectionFailsWithTheSectionNamed() {
        val briefText = listOf(
            "# Project brief", "", "## Goal", "A snake game", "",
            "## Files", "", "## Decisions", "", "## Known problems"
        ).joinToString("\n")
        fake.enqueueReply(briefAction(briefText))
        fake.enqueueReply(replyAction("Understood."))
        val outcome = run("note the plan")
        assertTrue(outcome is CodeRoundOutcome.Replied)
        assertTrue(fake.prompts[1].contains("missing sections: Next steps"))
        // The seeded brief is untouched.
        assertEquals("a snake game", store.readBrief(project)?.goal)
        assertTrue(store.listVersions(project.id).isEmpty())
    }

    @Test fun overCapBriefFailsWithTheCapNamed() {
        val briefText = listOf(
            "# Project brief", "", "## Goal", "x".repeat(1_500), "",
            "## Files", "", "## Decisions", "", "## Known problems", "", "## Next steps"
        ).joinToString("\n")
        fake.enqueueReply(briefAction(briefText))
        fake.enqueueReply(replyAction("Understood."))
        val outcome = run("note the plan")
        assertTrue(outcome is CodeRoundOutcome.Replied)
        assertTrue(fake.prompts[1].contains("cap"))
        assertEquals("a snake game", store.readBrief(project)?.goal)
    }

    @Test fun writingTheBriefFileDirectlyIsRefusedWithTheChannelNamed() {
        fake.enqueueReply(writeAction("BRIEF.md", "# Project brief"))
        fake.enqueueReply(replyAction("Understood."))
        val before = File(dir, CodeProjectStore.BRIEF_FILE_NAME).readBytes()
        val outcome = run("rewrite the brief")
        assertTrue(outcome is CodeRoundOutcome.Replied)
        assertTrue(fake.prompts[1].contains("use update_brief"))
        assertArrayEquals(before, File(dir, CodeProjectStore.BRIEF_FILE_NAME).readBytes())
    }
}
