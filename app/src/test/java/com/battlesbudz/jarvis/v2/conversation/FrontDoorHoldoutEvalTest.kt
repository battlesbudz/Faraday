package com.battlesbudz.jarvis.v2.conversation

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Scores the actual shipped classifier asset against the separately held-out training fixture. */
class FrontDoorHoldoutEvalTest {
    @Test fun shippedHeadMatchesHoldoutAndMeetsRoutingFloors() {
        val asset = listOf(File("src/main/assets/frontdoor/head_v1.json"),
            File("app/src/main/assets/frontdoor/head_v1.json")).firstOrNull { it.isFile }
            ?: error("Shipped front-door asset not found from either supported Gradle working root.")
        val head = FrontDoorHead(asset.inputStream())
        val fixture = javaClass.classLoader!!.getResourceAsStream("frontdoor/holdout_v1.json")!!
            .bufferedReader().use { JSONObject(it.readText()) }
        val entries = fixture.getJSONArray("entries")
        var actCount = 0
        var actOffered = 0
        var actVetoed = 0
        var chatCount = 0
        var chatNotOffered = 0
        var discussionCount = 0
        var discussionVetoed = 0
        var mustpassCount = 0
        var mustpassPassed = 0
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val pA = head.probs(entry.getJSONArray("embA").toFloatArray())
            val embB = entry.optJSONArray("embB")
            val pB = embB?.toFloatArray()?.let(head::probs)
            val actual = head.decide(pA, pB)
            val expected = entry.getJSONObject("expect")
            assertEquals("entry $index toolsOffered", expected.getBoolean("toolsOffered"), actual.toolsOffered)
            assertEquals("entry $index actIntended", expected.getBoolean("actIntended"), actual.actIntended)
            assertEquals("entry $index discussionConfident", expected.getBoolean("discussionConfident"), actual.discussionConfident)
            assertEquals("entry $index vetoConfident", expected.getBoolean("vetoConfident"), actual.vetoConfident)
            when (entry.getString("label")) {
                "ACT" -> {
                    actCount++
                    if (actual.toolsOffered) actOffered++
                    if (actual.vetoConfident) actVetoed++
                }
                "CHAT" -> {
                    chatCount++
                    if (!actual.toolsOffered) chatNotOffered++
                }
            }
            if (entry.getString("kind") == "discussion") {
                discussionCount++
                if (actual.vetoConfident) discussionVetoed++
            }
            if (entry.getString("kind") == "mustpass") {
                mustpassCount++
                if (actual.toolsOffered && actual.actIntended) mustpassPassed++
            }
        }
        val actRecall = actOffered.toDouble() / actCount
        val chatNotOfferedRate = chatNotOffered.toDouble() / chatCount
        val discussionVetoRate = discussionVetoed.toDouble() / discussionCount
        println("Front-door holdout: ACT tools recall=$actOffered/$actCount ($actRecall), " +
            "mustpass=$mustpassPassed/$mustpassCount, CHAT not-offered=$chatNotOffered/$chatCount " +
            "($chatNotOfferedRate), discussion veto=$discussionVetoed/$discussionCount " +
            "($discussionVetoRate), ACT veto=$actVetoed")
        assertTrue("ACT toolsOffered recall: $actOffered/$actCount", actRecall >= ACT_TOOLS_RECALL_FLOOR)
        assertEquals(MUSTPASS_ENTRIES, mustpassCount)
        assertEquals(MUSTPASS_ENTRIES, mustpassPassed)
        assertTrue("CHAT not-offered rate: $chatNotOffered/$chatCount",
            chatNotOfferedRate >= CHAT_NOT_OFFERED_FLOOR)
        assertTrue("discussion veto rate: $discussionVetoed/$discussionCount",
            discussionVetoRate >= DISCUSSION_VETO_FLOOR)
        assertEquals(0, actVetoed)
    }

    private fun org.json.JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }

    companion object {
        private const val ACT_TOOLS_RECALL_FLOOR = 0.97
        private const val MUSTPASS_ENTRIES = 4
        private const val CHAT_NOT_OFFERED_FLOOR = 0.80
        private const val DISCUSSION_VETO_FLOOR = 0.80
    }
}
