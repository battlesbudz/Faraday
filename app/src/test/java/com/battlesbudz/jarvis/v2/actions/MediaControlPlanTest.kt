package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the Fold 6 report (2026-10-04): typing "pause music" in
 * text chat returned "Music paused." without dispatching anything, because
 * ActionTurnPlan.parse() did not recognize media clauses and the text fell
 * through to ordinary chat. Every accepted form below must produce a Ready
 * media_control plan; every rejected form must stay NotAction.
 */
class MediaControlPlanTest {
    private fun ready(text: String): ActionTurnPlan.Ready {
        val plan = ActionTurnPlan.parse(text)
        assertTrue("'$text' must parse as an action plan, was $plan", plan is ActionTurnPlan.Ready)
        return plan as ActionTurnPlan.Ready
    }

    private fun assertMedia(text: String, verb: String) {
        val request = ready(text).steps.single().request
        assertEquals(text, "media_control", request.name)
        assertEquals(text, verb, request.arguments["action"])
    }

    @Test fun pauseFormsMapToPause() {
        for (text in listOf("pause music", "pause the music", "pause media", "stop the music", "stop playback")) {
            assertMedia(text, "pause")
        }
    }

    @Test fun playFormsMapToPlay() {
        for (text in listOf("play music", "play the song", "resume music", "resume playback")) {
            assertMedia(text, "play")
        }
    }

    @Test fun toggleNextPreviousFormsMap() {
        assertMedia("toggle music", "toggle")
        for (text in listOf("next song", "skip this track", "skip song")) {
            assertMedia(text, "next")
        }
        for (text in listOf("previous track", "last song", "go back a track")) {
            assertMedia(text, "previous")
        }
    }

    @Test fun politeLeadInsStillParse() {
        assertMedia("hey jarvis pause the music", "pause")
        assertMedia("can you pause the music", "pause")
        assertMedia("please pause music", "pause")
    }

    @Test fun negatedAndHypotheticalFormsStayNotAction() {
        for (text in listOf(
            "don't pause the music",
            "do not pause the music",
            "never pause the music",
            "how do I pause the music?",
            "For example, \"pause the music\""
        )) {
            assertTrue("'$text' must stay NotAction", ActionTurnPlan.parse(text) is ActionTurnPlan.NotAction)
        }
    }

    @Test fun finalCorrectionHonorsCorrectedVerb() {
        // "play music actually pause music" must not parse as play: the
        // final correction wins (or the clause is safely rejected).
        assertMedia("play music actually pause music", "pause")
        assertMedia("pause music actually play music", "play")
        assertMedia("play the song instead skip to the next song", "next")
        assertMedia("play music, actually, pause music", "pause")
    }

    @Test fun correctionWithoutDirectedVerbStaysNotAction() {
        // A correction marker followed by no directed media verb is a safe
        // rejection, not a guess at the first verb.
        assertTrue(ActionTurnPlan.parse("play music actually uh") is ActionTurnPlan.NotAction)
    }

    @Test fun bareVerbsWithoutMediaNounStayNotAction() {
        // Bare verbs are too ambiguous to become phone actions.
        for (text in listOf("pause", "stop", "play", "next", "toggle")) {
            assertTrue("'$text' must stay NotAction", ActionTurnPlan.parse(text) is ActionTurnPlan.NotAction)
        }
    }

    @Test fun mediaClauseCombinesWithOtherActions() {
        val plan = ready("pause the music and set volume to 40")
        assertEquals(
            listOf("media_control", "set_volume"),
            plan.steps.map { it.request.name }
        )
        assertEquals("pause", plan.steps.first().request.arguments["action"])
    }

    @Test fun strictDecoderAcceptsParserVerbs() {
        // The parser must only emit verbs the strict catalog boundary accepts.
        for (text in listOf("pause music", "play music", "toggle music", "next song", "previous track")) {
            val request = ready(text).steps.single().request
            val strict = MobileToolCatalog.decodeStrict(
                request.name,
                org.json.JSONObject(request.arguments as Map<*, *>)
            )
            assertNotNull("'$text' verb must pass strict decode", strict)
        }
    }
}
