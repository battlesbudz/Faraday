package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * M1b destinations slice: open_website, open_settings, navigate.
 * Covers catalog/schema parity, strict decode, validator, tolerant decode,
 * and text-parser routing. Voice stays denied by FinalVoiceToolGuard (by design).
 */
class M1bDestinationsTest {
    private val validator = MobileActionValidator()

    private fun strict(name: String, args: Map<String, String>) =
        MobileToolCatalog.decodeStrict(name, JSONObject(args as Map<*, *>))

    // Catalog

    @Test fun catalogDeclaresAllThreeDestinationTools() {
        val names = MobileToolCatalog.all().map { it.name }
        assertTrue(names.contains("open_website"))
        assertTrue(names.contains("open_settings"))
        assertTrue(names.contains("navigate"))
    }

    @Test fun settingsScreenPatternIsStrict() {
        assertNotNull(strict("open_settings", mapOf("screen" to "wifi")))
        assertNull(strict("open_settings", mapOf("screen" to "nfc")))
        assertNull(strict("open_settings", mapOf("screen" to "")))
    }

    // Validator

    @Test fun validatesWebsiteAndNormalizesBareDomains() {
        assertEquals(
            ActionValidation.Valid(MobileAction.OpenWebsite("https://example.com")),
            validator.validate(ActionRequest("open_website", mapOf("url" to "https://example.com")))
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.OpenWebsite("https://youtube.com")),
            validator.validate(ActionRequest("open_website", mapOf("url" to "youtube.com")))
        )
    }

    @Test fun rejectsDangerousAndMalformedUrls() {
        for (url in listOf("", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,hi", "not a url", ".com")) {
            val result = validator.validate(ActionRequest("open_website", mapOf("url" to url)))
            assertTrue("'$url' must be rejected", result is ActionValidation.Rejected)
        }
    }

    @Test fun validatesEverySettingsScreen() {
        for (screen in SettingsScreen.entries) {
            assertEquals(
                ActionValidation.Valid(MobileAction.OpenSettings(screen)),
                validator.validate(ActionRequest("open_settings", mapOf("screen" to screen.key)))
            )
        }
        assertTrue(
            validator.validate(ActionRequest("open_settings", mapOf("screen" to "nfc")))
                is ActionValidation.Rejected
        )
    }

    @Test fun validatesNavigationDestination() {
        assertEquals(
            ActionValidation.Valid(MobileAction.Navigate("the airport")),
            validator.validate(ActionRequest("navigate", mapOf("destination" to "the airport")))
        )
        assertTrue(
            validator.validate(ActionRequest("navigate", mapOf("destination" to "  ")))
                is ActionValidation.Rejected
        )
    }

    // Tolerant decoder

    @Test fun tolerantDecodeMapsDestinationArgs() {
        assertEquals(
            ActionRequest("open_website", mapOf("url" to "example.com")),
            NativeActionDecoder.decode(ToolCall("open_website", """{"url":"example.com"}"""))
        )
        assertEquals(
            ActionRequest("open_settings", mapOf("screen" to "wifi")),
            NativeActionDecoder.decode(ToolCall("open_settings", """{"screen":"wifi"}"""))
        )
        assertEquals(
            ActionRequest("navigate", mapOf("destination" to "the airport")),
            NativeActionDecoder.decode(ToolCall("navigate", """{"destination":"the airport"}"""))
        )
    }

    // Text parser routing

    private fun ready(text: String): ActionTurnPlan.Ready {
        val plan = ActionTurnPlan.parse(text)
        assertTrue("'$text' must parse as an action plan, was $plan", plan is ActionTurnPlan.Ready)
        return plan as ActionTurnPlan.Ready
    }

    @Test fun websiteFormsRouteToOpenWebsite() {
        assertEquals(
            ActionRequest("open_website", mapOf("url" to "youtube.com")),
            ready("open youtube.com").steps.single().request
        )
        assertEquals(
            ActionRequest("open_website", mapOf("url" to "https://example.com/path")),
            ready("open https://example.com/path").steps.single().request
        )
    }

    @Test fun appNamesWithoutDotsStillRouteToOpenApp() {
        // No regression: "open Chrome" and "open Settings" are app launches, not websites.
        assertEquals("open_app", ready("open Chrome").steps.single().request.name)
        assertEquals("open_app", ready("open Settings").steps.single().request.name)
    }

    @Test fun settingsFormsRouteToOpenSettings() {
        assertEquals(
            ActionRequest("open_settings", mapOf("screen" to "wifi")),
            ready("open wifi settings").steps.single().request
        )
        assertEquals(
            ActionRequest("open_settings", mapOf("screen" to "bluetooth")),
            ready("open bluetooth settings").steps.single().request
        )
        assertEquals(
            ActionRequest("open_settings", mapOf("screen" to "display")),
            ready("show display settings").steps.single().request
        )
    }

    @Test fun navigationFormsRouteToNavigate() {
        assertEquals(
            ActionRequest("navigate", mapOf("destination" to "the airport")),
            ready("navigate to the airport").steps.single().request
        )
        assertEquals(
            ActionRequest("navigate", mapOf("destination" to "123 Main St")),
            ready("get directions to 123 Main St").steps.single().request
        )
    }

    @Test fun destinationClausesCombineWithOtherActions() {
        val plan = ready("open wifi settings and pause the music")
        assertEquals(
            listOf("open_settings", "media_control"),
            plan.steps.map { it.request.name }
        )
    }

    @Test fun strictDecoderAcceptsParserOutput() {
        for (text in listOf("open youtube.com", "open wifi settings", "navigate to the airport")) {
            val request = ready(text).steps.single().request
            assertNotNull(
                "'$text' must pass strict decode",
                strict(request.name, request.arguments)
            )
        }
    }
}
