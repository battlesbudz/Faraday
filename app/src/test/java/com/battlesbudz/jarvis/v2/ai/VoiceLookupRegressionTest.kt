package com.battlesbudz.jarvis.v2.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

/** The noisy restaurant transcript through real planning/filtering, with fake HTTP only. */
class VoiceLookupRegressionTest {
    @Test fun correctedSubjectAndSourceCommandRetainTheLocationQuestion() = runBlocking {
        val searches = mutableListOf<String>()
        val pages = linkedMapOf(
            "Georgie & Mandy's First Marriage" to "Mandy's father founded the family auto service business at the end of the first season.",
            "Edward Harold Bell" to "Edward Harold Bell was born in Texas and was convicted of murder.",
            "Wikipedia" to "Wikipedia is a free online encyclopedia founded in 2001.",
            "The One Where No One's Ready" to "The first episode where friends take too long getting ready.",
            "The League" to "Taco uses the password Taco, and the episode is The Fear Boners.",
            "Taco Bell" to "Taco Bell is a restaurant chain. The first Taco Bell was opened in Downey, California, in 1962 by Glen Bell."
        )
        val client = ReferenceGroundingClient(readBytes = { url ->
            val params = url.query.split('&').associate { pair ->
                val value = pair.split('=', limit = 2)
                value[0] to URLDecoder.decode(value.getOrElse(1) { "" }, "UTF-8")
            }
            val result = when {
                params["list"] == "search" -> {
                    searches += params["srsearch"].orEmpty()
                    val titles = if (params["srsearch"].orEmpty().contains("Taco Bell", ignoreCase = true))
                        listOf("Edward Harold Bell", "The League", "Taco Bell")
                    else listOf("Georgie & Mandy's First Marriage", "Edward Harold Bell", "The League")
                    JSONObject().put("query", JSONObject().put("search", JSONArray(titles.map { title ->
                        JSONObject().put("title", title).put("snippet", pages.getValue(title))
                    })))
                }
                params["prop"] == "extracts" -> JSONObject().put("query", JSONObject().put("pages",
                    JSONObject().put("1", JSONObject().put("extract", pages[params["titles"]].orEmpty()))))
                else -> JSONObject().put("search", JSONArray().put(JSONObject().put("id", "Q999")
                    .put("label", "Edward Harold Bell").put("description", "American criminal")))
            }
            result.toString().toByteArray()
        })
        val turns = TurnOrchestrator(client)
        val history = mutableListOf<Pair<String, String>>()
        suspend fun next(prompt: String): Pair<TurnPlan, ReferenceGrounding?> {
            val plan = turns.plan(prompt, history)
            // Intake and final dispatch both plan in production. The result must agree.
            assertEquals(plan, turns.plan(prompt, history + ("You" to prompt)))
            val evidence = plan.lookupQuery?.let { client.fetchIfRequested(it) }
            history += "You" to prompt
            history += "Jarvis" to if (evidence == null) "I couldn't find relevant reference evidence." else "A fixture answer."
            turns.recordResponse(prompt, history.last().second, plan)
            return plan to evidence
        }
        val original = next("Where was the first trucker bell founded?")
        assertNull(original.second)
        val corrected = next("I said Taco Bell.")
        assertEquals("Where was the first Taco Bell founded?", corrected.first.resolvedQuestion)
        assertEquals("Taco Bell", corrected.first.activeSubject)
        assertTrue(corrected.second!!.context.contains("Downey"))
        assertFalse(corrected.second!!.context.contains("Edward Harold"))
        val followup = next("Where was the first one?")
        assertTrue(followup.first.lookupQuery!!.contains("Taco Bell"))
        assertTrue(followup.second!!.context.contains("Downey"))
        val sourceCommand = next("Use Wikipedia.")
        assertEquals(TurnKind.EXPLICIT_LOOKUP, sourceCommand.first.kind)
        assertEquals(followup.first.lookupQuery, sourceCommand.first.lookupQuery)
        assertTrue(sourceCommand.second!!.context.contains("Downey"))
        assertFalse(sourceCommand.second!!.context.contains("free online encyclopedia"))
        val noisyPrefix = next("1990 to see where the first Taco Bell was opened.")
        assertEquals(TurnKind.FACTUAL_LOCAL_FIRST, noisyPrefix.first.kind)
        assertTrue(noisyPrefix.second!!.context.contains("1962"))
        assertTrue(noisyPrefix.second!!.context.contains("do not establish dates"))
        val disputedYear = next("I didn't say anything about a year.")
        assertEquals(TurnKind.FACTUAL_LOCAL_FIRST, disputedYear.first.kind)
        assertTrue(disputedYear.second!!.context.contains("1962"))
        assertFalse(disputedYear.first.lookupQuery!!.contains("1990"))
        val noisyQuestion = next("Fear was Taco Bell founded. Fear.")
        assertEquals(TurnKind.FACTUAL_LOCAL_FIRST, noisyQuestion.first.kind)
        // Neither a comedy snippet nor the chain's article verifies a person
        // called "Fear". Refuse the malformed question instead of laundering it.
        assertNull(noisyQuestion.second)
        assertFalse(searches.any { it == "Use Wikipedia." })
    }

    @Test fun correctionResolutionIsGenericAndACompleteNewQuestionReplacesTheOldOne() {
        val turns = TurnOrchestrator(ReferenceGroundingClient())
        val history = listOf("You" to "Where was the first blue train founded?", "Jarvis" to "An unsupported answer.")
        val correction = turns.plan("I meant Blue Origin.", history)
        assertEquals("Where was the first Blue Origin founded?", correction.resolvedQuestion)
        assertEquals("Blue Origin", correction.activeSubject)
        val newQuestion = turns.plan("No, where was SpaceX founded?", history)
        assertTrue(newQuestion.lookupQuery!!.contains("SpaceX"))
        assertFalse(newQuestion.lookupQuery!!.contains("blue train"))
    }

    @Test fun explicitLookupWithNewQuestionAndTopicChangeDoesNotReuseStaleSubject() {
        val turns = TurnOrchestrator(ReferenceGroundingClient())
        turns.plan("Where was Taco Bell founded?")
        val newQuestion = turns.plan("Use Wikipedia to find where SpaceX was founded.")
        assertTrue(newQuestion.lookupQuery!!.contains("SpaceX"))
        assertFalse(newQuestion.lookupQuery!!.contains("Taco Bell"))
        val changed = turns.plan("Use Wikipedia.", listOf("You" to "Where was Taco Bell founded?", "You" to "Let's switch topics to music."))
        assertFalse(changed.lookupQuery!!.contains("Taco Bell"))
    }
    @Test fun literalSubjectCorrectionStillWorksWhenThePriorQuestionWasOnlyAReference() {
        val turns = TurnOrchestrator(ReferenceGroundingClient())
        val correction = turns.plan("I said Blue Origin.", listOf("You" to "Where was the first one?"))
        assertEquals("Blue Origin", correction.activeSubject)
        assertTrue(correction.lookupQuery!!.contains("Where was"))
        assertTrue(correction.lookupQuery!!.contains("Blue Origin"))
        assertTrue(ReferenceGroundingClient().shouldAutomaticallyLookup("1990 to see where the first Taco Bell was opened."))
        assertFalse(ReferenceGroundingClient().shouldAutomaticallyLookup("I opened my lunch."))
    }

}
