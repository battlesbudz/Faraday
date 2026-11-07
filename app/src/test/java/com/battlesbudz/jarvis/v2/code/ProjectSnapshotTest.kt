package com.battlesbudz.jarvis.v2.code

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Snapshot ordering, budget behavior, and the head-and-tail rule. */
class ProjectSnapshotTest {

    @Rule @JvmField val tmp = TemporaryFolder()

    private lateinit var store: CodeProjectStore
    private lateinit var project: CodeProjectStore.CodeProject
    private lateinit var dir: File
    private val budget = CodeBudget.compute(null, 0)!! // snapshot 5,000; index cap 2,500; file cap 1,250

    @Before fun setUp() {
        store = CodeProjectStore(File(tmp.root, "workspace"))
        project = store.createProject("Snake", "A snake game", nowMillis = 1_000)
        dir = store.projectDir(project.id)
    }

    private fun build(b: CodeBudget.Budget = budget): String =
        ProjectSnapshotBuilder.build(store, project, b)

    @Test fun sectionsAppearInScopeOrder() {
        val brief = store.readBrief(project)!!
        store.writeBrief(project, brief.copy(decisions = listOf("Green snake")))
        store.appendRoundLog(project, "make it", "written", nowMillis = 2_000)

        val text = build()
        val briefAt = text.indexOf("## Project brief")
        val mapAt = text.indexOf("## Project files")
        val indexAt = text.indexOf("## index.html")
        val logAt = text.indexOf("## Recent rounds")
        assertEquals(0, briefAt)
        assertTrue(mapAt > briefAt)
        assertTrue(indexAt > mapAt)
        assertTrue(logAt > indexAt)
        assertTrue(text.contains("A snake game"))
        assertTrue(text.contains("Green snake"))
        val starterSize = File(dir, "index.html").length()
        assertTrue(text.contains("`index.html` — $starterSize B"))
        assertTrue(text.contains("make it"))
        assertTrue(text.contains("written"))
    }

    @Test fun snapshotStaysWithinItsBudget() {
        store.appendRoundLog(project, "make it", "written", nowMillis = 2_000)
        val text = build()
        assertTrue(
            "snapshot is ${text.length} chars, budget is ${budget.snapshotBudgetChars}",
            text.length <= budget.snapshotBudgetChars
        )
    }

    @Test fun oversizedIndexKeepsHeadAndTailWithANote() {
        val content = "<html>" + "H".repeat(300) + "M".repeat(6_000) + "T".repeat(300) + "</html>"
        File(dir, "index.html").writeText(content)
        val text = build()
        assertTrue(text.contains("H".repeat(300)))
        assertTrue(text.contains("T".repeat(300)))
        assertTrue(text.contains("middle of the file omitted"))
        assertFalse("the middle is dropped, not carried", text.contains("M".repeat(1_500)))
        assertTrue(text.length <= budget.snapshotBudgetChars)
    }

    @Test fun otherFilesAreCappedPerFile() {
        File(dir, "notes.txt").writeText("a".repeat(1_000) + "b".repeat(1_000))
        val text = build()
        val notesAt = text.indexOf("## notes.txt")
        assertTrue(notesAt > 0)
        val notesSection = text.substring(notesAt)
        assertTrue(notesSection.contains("a".repeat(500)))
        assertTrue(notesSection.contains("b".repeat(500)))
        assertTrue(notesSection.contains("middle of the file omitted"))
    }

    @Test fun filesThatDoNotFitAreListedOnly() {
        val tight = CodeBudget.Budget(
            limitChars = 10_000, replyReserveChars = 5_000,
            snapshotBudgetChars = 880, briefCapChars = 275,
            indexCapChars = 550, fileCapChars = 275
        )
        File(dir, "big.txt").writeText("x".repeat(2_000) + "ENDMARKER")
        val text = build(tight)
        assertTrue(text.contains("## Not shown (snapshot budget spent)"))
        assertTrue(text.contains("`big.txt` — 2009 B (listed only)"))
        assertFalse(text.contains("ENDMARKER"))
        assertTrue(text.length <= tight.snapshotBudgetChars + 64)
    }

    @Test fun roundLogCarriesTheLastFiveEntriesClipped() {
        for (n in 1..6) {
            store.appendRoundLog(project, "request-$n", "outcome-$n", nowMillis = n * 1_000L)
        }
        store.appendRoundLog(project, "request-7 " + "z".repeat(300), "outcome-7", nowMillis = 7_000)
        val text = build()
        assertFalse(text.contains("request-1"))
        assertFalse(text.contains("request-2"))
        for (n in 3..7) {
            assertTrue("request-$n present", text.contains("request-$n"))
            assertTrue("outcome-$n present", text.contains("outcome-$n"))
        }
        assertTrue(text.indexOf("request-3") < text.indexOf("request-7"))
        assertFalse("the long request is clipped, not carried whole", text.contains("z".repeat(300)))
        assertTrue(text.contains("…"))
    }

    @Test fun capHeadTailKeepsWholeTextWhenItFits() {
        assertEquals("hello", ProjectSnapshotBuilder.capHeadTail("hello", 10))
        assertEquals("", ProjectSnapshotBuilder.capHeadTail("hello", 0))
        val capped = ProjectSnapshotBuilder.capHeadTail("x".repeat(100), 80)
        assertEquals(80, capped.length)
        assertTrue(capped.contains("middle of the file omitted"))
    }
}
