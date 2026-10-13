package com.battlesbudz.jarvis.v2.actions
import com.battlesbudz.jarvis.v2.ChatEntry
import org.junit.Assert.*
import org.junit.Test
class ActionIntentRouterTest {
    private val router = ActionIntentRouter()
    @Test fun descriptionFromBuild692DoesNotBecomeAnAction() {
        val text = "Um, So yeah, the idea would be that it would operate people's phones as they needed them to be done, like hands-free. So think like Siri back in the day when it was first invented. We thought it was going to be like Jarvis where, you know, you just tell it to do everything and you can operate your phone hands-free. But now it doesn't really work like that. It just does a couple different things. But this would operate your entire phone and let you not have to touch it. It'll open up apps and search. screenshot everything"
        assertNull(router.classifyActionIntent(text, emptyList()))
    }
    @Test fun explanationsNegationsAndQuotedExamplesRemainConversation() {
        for (text in listOf("It would open YouTube", "How do I open YouTube?", "Don't open YouTube", "Explain how to set the volume to fifty percent", "The battery technology is interesting", "For example, \"open YouTube\" is a command.")) {
            assertNull(text, router.classifyActionIntent(text, emptyList()))
        }
    }
    @Test fun directCommandsAndPoliteRequestsStillWork() {
        for (text in listOf("Open YouTube", "Could you please open YouTube?", "Jarvis, open Settings please", "I want you to launch Spotify", "Okay, please open YouTube")) {
            assertEquals(text, "open_app", router.classifyActionIntent(text, emptyList())?.name)
        }
        assertEquals("set_volume", router.classifyActionIntent("Please set volume to fifty percent", emptyList())?.name)
        assertEquals("read_battery", router.classifyActionIntent("What's my battery level?", emptyList())?.name)
    }
    @Test fun modelCannotSubstituteAnotherVolumeOrApp() {
        fun call(name: String, args: String) = com.battlesbudz.jarvis.v2.ai.ToolCall(name, args)
        assertTrue(router.toolMatchesUserIntent("Set volume to 20 percent", emptyList(), call("set_volume", "{\"level\":20}")))
        assertFalse(router.toolMatchesUserIntent("Set volume to 20 percent", emptyList(), call("set_volume", "{\"level\":100}")))
        assertFalse(router.toolMatchesUserIntent("Set volume to 20 percent", emptyList(), call("set_volume", "{\"level\":\"NaN\"}")))
        assertTrue(router.toolMatchesUserIntent("Open YouTube", emptyList(), call("open_app", "{\"app\":\"YouTube\"}")))
        assertFalse(router.toolMatchesUserIntent("Open YouTube", emptyList(), call("open_app", "{\"app\":\"Settings\"}")))
        assertFalse(router.toolMatchesUserIntent("Describe this picture", emptyList(), call("set_volume", "{\"level\":100}")))
    }

    @Test fun yesOnlyConfirmsAnImmediateSpecificOffer() {
        assertEquals("open_app", router.classifyActionIntent("Yes", listOf(ChatEntry("Jarvis", "Shall I open YouTube?")))?.name)
        assertNull(router.classifyActionIntent("Yes", listOf(ChatEntry("Jarvis", "It would open YouTube."))))
        assertNull(router.classifyActionIntent("Yes", listOf(ChatEntry("User", "Open YouTube"), ChatEntry("Jarvis", "What would you like to discuss?"))))
    }
}
