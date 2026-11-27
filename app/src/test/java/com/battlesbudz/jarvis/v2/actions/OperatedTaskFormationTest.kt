package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.conversation.FrontDoorDecision
import com.battlesbudz.jarvis.v2.conversation.FrontDoorVerdict
import com.battlesbudz.jarvis.v2.conversation.operatedTaskCandidate
import com.battlesbudz.jarvis.v2.conversation.startOperatedTaskIfEligible
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Junction coverage uses the pure suspend seam consumed by route(); the existing
 * ConversationGenerationTest separately pins the A7 no-deterministic-plan proposal guard. */
class OperatedTaskFormationTest {
    @Test fun formsOnlyTheBoundedMessageGrammar() {
        val positives = listOf(
            "send a message to Andrea saying testing",
            "send message to Andrea that says testing",
            "send sms to Andrea with the message testing",
            "send text to Andrea: testing",
            "send Andrea a message saying testing",
            "send Andrea message that says testing",
            "text Andrea saying testing",
            "message Andrea: testing",
            "send a message to Andrea Michaels saying testing",
            "can you send a message to Andrea saying testing",
        )
        positives.forEach { text -> assertNotNull("$text", OperatedTaskFormation.form(text)) }
        val formed = OperatedTaskFormation.form("send a message to Andrea Michaels saying testing")!!
        assertEquals("Andrea Michaels", formed.recipient)
        assertEquals("testing", formed.body)
        assertEquals("Messages", formed.appName)
    }

    @Test fun unquotesBodyAndRejectsNegationsQuestionsAndChat() {
        assertEquals("testing now", OperatedTaskFormation.form("text Andrea saying \"testing now\"")?.body)
        listOf(
            "don't send a message to Andrea saying testing",
            "do not send a message to Andrea saying testing",
            "never text Andrea saying testing",
            "send a message to Andrea saying",
            "send a message to saying testing",
            "should I send a message to Andrea saying testing?",
            "I like messages and Andrea",
        ).forEach { assertNull("$it", OperatedTaskFormation.form(it)) }
    }

    @Test fun catalogHasNoSendSubstituteAndJunctionRequiresActNotAction() {
        val forbidden = setOf("send", "send_message", "send_email")
        assertTrue(MobileToolCatalog.all().none { it.name in forbidden })
        val sendRequest = ActionRequest("send_message", mapOf("app" to "Messages", "destination" to "Andrea", "message" to "testing"))
        assertEquals(ActionControlPolicy.ApprovalClass.EXACT_ACTION, ActionControlPolicy.approvalClass(sendRequest))
        assertNull(ActionControlPolicy.admissionBlockReason(sendRequest))
        val act = verdict("ACT", true)
        val chat = verdict("CHAT", false)
        val sentence = "send a message to Andrea saying testing"
        assertEquals("Andrea", operatedTaskCandidate(ActionTurnPlan.NotAction, act, sentence)?.recipient)
        assertNull(operatedTaskCandidate(ActionTurnPlan.NotAction, chat, sentence))
        assertNull(operatedTaskCandidate(ActionTurnPlan.NotAction, verdict("ACT", false), sentence))
        assertNull(operatedTaskCandidate(ActionTurnPlan.parse("open Settings"), act, sentence))
        assertNull(operatedTaskCandidate(ActionTurnPlan.NotAction, act, "I like messaging Andrea"))
    }

    @Test fun junctionInvokesOnlyFormedActAndNullStarterFallsThrough() = runBlocking {
        val text = "send a message to Andrea saying testing"
        val act = verdict("ACT", true)
        var calls = 0
        var gotTask: OperatedTaskRequest? = null
        var gotConversation: String? = null
        val starter: suspend (OperatedTaskRequest, String) -> OperatedTaskOutcome? = { task, conversation ->
            calls++; gotTask = task; gotConversation = conversation
            OperatedTaskOutcome.Completed("Message sent to Andrea: \"testing\"")
        }
        val completed = startOperatedTaskIfEligible(ActionTurnPlan.NotAction, act, text, "conversation-1", starter)
        assertEquals(OperatedTaskRequest.Kind.SEND_MESSAGE, gotTask?.kind)
        assertEquals("Andrea", gotTask?.recipient)
        assertEquals("conversation-1", gotConversation)
        assertEquals("Message sent to Andrea: \"testing\"", completed?.second?.message)
        assertEquals(1, calls)

        assertNull(startOperatedTaskIfEligible(ActionTurnPlan.NotAction, verdict("CHAT", false), text, "c", starter))
        assertNull(startOperatedTaskIfEligible(ActionTurnPlan.NotAction, verdict("ACT", false), text, "c", starter))
        assertNull(startOperatedTaskIfEligible(ActionTurnPlan.parse("open Settings"), act, text, "c", starter))
        assertNull(startOperatedTaskIfEligible(ActionTurnPlan.NotAction, act, "I like messaging Andrea", "c", starter))
        val beforeNull = calls
        val nullStart = startOperatedTaskIfEligible(ActionTurnPlan.NotAction, act, text, "c", starter = { _, _ -> null })
        assertNotNull(nullStart)
        assertNull(nullStart?.second)
        assertEquals(beforeNull, calls)
    }

    private fun verdict(label: String, intended: Boolean) = FrontDoorVerdict(
        FrontDoorDecision(label, .8, .1, .1, toolsOffered = intended, actIntended = intended,
            discussionConfident = false, vetoConfident = false, windowUpgraded = false),
        cold = false, source = "test")
}
