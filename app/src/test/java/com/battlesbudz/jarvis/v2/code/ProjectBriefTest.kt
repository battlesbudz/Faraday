package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brief render/parse, the machine-owned Files section, and cap validation. */
class ProjectBriefTest {

    private fun sampleBrief(): ProjectBrief = ProjectBrief(
        goal = "A snake game\nwith a score counter",
        files = listOf(
            ProjectFileEntry("index.html", 432),
            ProjectFileEntry("css/style.css", 57)
        ),
        decisions = listOf("Green snake on a dark board", "Arrow keys and swipe both steer"),
        knownProblems = listOf("Score does not reset on restart"),
        nextSteps = listOf("Add a pause button")
    )

    @Test fun renderParseRoundTrips() {
        val brief = sampleBrief()
        assertEquals(brief, ProjectBrief.parse(brief.render()))
    }

    @Test fun emptySectionsRoundTrip() {
        val brief = ProjectBrief("", emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(brief, ProjectBrief.parse(brief.render()))
    }

    @Test fun renderShowsAllFiveSectionsInOrder() {
        val text = sampleBrief().render()
        var lastIndex = -1
        for (title in ProjectBrief.SECTION_TITLES) {
            val index = text.indexOf("## $title")
            assertTrue("section $title present and in order", index > lastIndex)
            lastIndex = index
        }
    }

    @Test fun parseRejectsAMissingSection() {
        val rendered = sampleBrief().render()
        val withoutFiles = rendered.replace("## Files\n", "")
        assertNull(ProjectBrief.parse(withoutFiles))
        val withoutGoal = rendered.replace("## Goal\n", "")
        assertNull(ProjectBrief.parse(withoutGoal))
    }

    @Test fun parseRejectsGarbage() {
        assertNull(ProjectBrief.parse(""))
        assertNull(ProjectBrief.parse("just some prose\nwith no sections"))
    }

    @Test fun seededBriefCarriesGoalAndFilesOnly() {
        val files = listOf(ProjectFileEntry("index.html", 10))
        val brief = ProjectBrief.seeded("  A snake game  ", files)
        assertEquals("A snake game", brief.goal)
        assertEquals(files, brief.files)
        assertTrue(brief.decisions.isEmpty())
        assertTrue(brief.knownProblems.isEmpty())
        assertTrue(brief.nextSteps.isEmpty())
        assertEquals(brief, ProjectBrief.parse(brief.render()))
    }

    @Test fun filesSectionIsOverwrittenWholesale() {
        val brief = sampleBrief().withFilesSection(listOf(ProjectFileEntry("ghost.txt", 1)))
        val regenerated = brief.withFilesSection(listOf(ProjectFileEntry("real.txt", 99)))
        val rendered = regenerated.render()
        assertTrue(rendered.contains("`real.txt` — 99 B"))
        assertTrue(!rendered.contains("ghost.txt"))
        assertEquals(regenerated, ProjectBrief.parse(rendered))
    }

    @Test fun validateFlagsAnOverCapBriefAndPassesAFittingOne() {
        val budget = CodeBudget.compute(null, 0)!! // brief cap is a quarter of 5,000: 1,250
        assertTrue(sampleBrief().validate(budget).isEmpty())
        val bloated = sampleBrief().copy(goal = "x".repeat(2_000))
        val problems = bloated.validate(budget)
        assertEquals(1, problems.size)
        assertTrue(problems[0].contains(budget.briefCapChars.toString()))
    }
}
