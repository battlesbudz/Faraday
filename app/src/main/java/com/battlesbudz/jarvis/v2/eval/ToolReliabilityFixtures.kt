package com.battlesbudz.jarvis.v2.eval

/**
 * Canonical benchmark fixtures for the on-device tool-call reliability suite
 * (M8 early enabler). Each fixture pairs a deterministic, unambiguous
 * utterance with the exact tool call the production strict decoder must
 * accept for it: the expected tool name plus the expected arguments in their
 * canonical decoded form (Map<String, String>; integers as decimal strings,
 * matching what NativeActionDecoder.decodeStrict produces).
 *
 * Fixtures cover every tool in MobileToolCatalog. They are model-input
 * prompts for measurement only: nothing here dispatches, approves, gates,
 * or executes anything.
 *
 * Scores measure exact agreement with these fixtures and the tool schema;
 * they do not prove a requested phone action would succeed. Some fixtures
 * are not ordinary-chat-admissible (observation tokens are stated, not
 * observed) and fixed dates can go stale.
 */
data class ReliabilityFixture(
    val id: String,
    val utterance: String,
    val expectedTool: String,
    val expectedArguments: Map<String, String> = emptyMap()
)

object ToolReliabilityFixtures {
    /**
     * Bump whenever fixtures, the catalog's strict-decode boundary, or
     * scoring semantics change. Saved scores pin this version; scores from an
     * older version are never presented as current.
     */
    const val SUITE_VERSION = 2

    fun all(): List<ReliabilityFixture> = listOf(
        // read_battery (no parameters)
        ReliabilityFixture(
            id = "read_battery_1",
            utterance = "How much battery does my phone have?",
            expectedTool = "read_battery"
        ),
        ReliabilityFixture(
            id = "read_battery_2",
            utterance = "What is my current battery percentage?",
            expectedTool = "read_battery"
        ),

        // open_app
        ReliabilityFixture(
            id = "open_app_1",
            utterance = "Open YouTube",
            expectedTool = "open_app",
            expectedArguments = mapOf("app" to "YouTube")
        ),
        ReliabilityFixture(
            id = "open_app_2",
            utterance = "Launch Spotify",
            expectedTool = "open_app",
            expectedArguments = mapOf("app" to "Spotify")
        ),
        ReliabilityFixture(
            id = "open_app_3",
            utterance = "Open the Settings app",
            expectedTool = "open_app",
            expectedArguments = mapOf("app" to "Settings")
        ),

        // set_volume (level: integer 0-100)
        ReliabilityFixture(
            id = "set_volume_1",
            utterance = "Set the volume to 50",
            expectedTool = "set_volume",
            expectedArguments = mapOf("level" to "50")
        ),
        ReliabilityFixture(
            id = "set_volume_2",
            utterance = "Turn the media volume down to 20 percent",
            expectedTool = "set_volume",
            expectedArguments = mapOf("level" to "20")
        ),
        ReliabilityFixture(
            id = "set_volume_3",
            utterance = "Mute the phone volume completely",
            expectedTool = "set_volume",
            expectedArguments = mapOf("level" to "0")
        ),

        // media_control (action: play|pause|toggle|next|previous)
        ReliabilityFixture(
            id = "media_control_1",
            utterance = "Pause the music",
            expectedTool = "media_control",
            expectedArguments = mapOf("action" to "pause")
        ),
        ReliabilityFixture(
            id = "media_control_2",
            utterance = "Skip to the next track",
            expectedTool = "media_control",
            expectedArguments = mapOf("action" to "next")
        ),
        ReliabilityFixture(
            id = "media_control_3",
            utterance = "Play the music",
            expectedTool = "media_control",
            expectedArguments = mapOf("action" to "play")
        ),

        // open_website
        ReliabilityFixture(
            id = "open_website_1",
            utterance = "Open youtube.com in the browser",
            expectedTool = "open_website",
            expectedArguments = mapOf("url" to "youtube.com")
        ),
        ReliabilityFixture(
            id = "open_website_2",
            utterance = "Go to https://example.com",
            expectedTool = "open_website",
            expectedArguments = mapOf("url" to "https://example.com")
        ),

        // open_settings (screen: wifi|bluetooth|display|sound|apps|battery|location|storage|network|general)
        ReliabilityFixture(
            id = "open_settings_1",
            utterance = "Open the Wi-Fi settings",
            expectedTool = "open_settings",
            expectedArguments = mapOf("screen" to "wifi")
        ),
        ReliabilityFixture(
            id = "open_settings_2",
            utterance = "Show me the battery settings screen",
            expectedTool = "open_settings",
            expectedArguments = mapOf("screen" to "battery")
        ),
        ReliabilityFixture(
            id = "open_settings_3",
            utterance = "Open display settings",
            expectedTool = "open_settings",
            expectedArguments = mapOf("screen" to "display")
        ),

        // navigate
        ReliabilityFixture(
            id = "navigate_1",
            utterance = "Navigate to 123 Main Street",
            expectedTool = "navigate",
            expectedArguments = mapOf("destination" to "123 Main Street")
        ),
        ReliabilityFixture(
            id = "navigate_2",
            utterance = "Give me directions to the airport",
            expectedTool = "navigate",
            expectedArguments = mapOf("destination" to "the airport")
        ),

        // screen_observe (no parameters)
        ReliabilityFixture(
            id = "screen_observe_1",
            utterance = "What is on my screen right now?",
            expectedTool = "screen_observe"
        ),
        ReliabilityFixture(
            id = "screen_observe_2",
            utterance = "Look at my phone screen and describe what you see",
            expectedTool = "screen_observe"
        ),

        // screen_tap — element IDs and observation tokens come from a prior
        // screen_observe in production; the fixture states them explicitly so
        // the expected call is unambiguous.
        ReliabilityFixture(
            id = "screen_tap_1",
            utterance = "Tap element n3 using observation token abcdef1234567890",
            expectedTool = "screen_tap",
            expectedArguments = mapOf("target" to "n3", "token" to "abcdef1234567890")
        ),
        ReliabilityFixture(
            id = "screen_tap_2",
            utterance = "Tap on element n12 with observation token 0011223344556677",
            expectedTool = "screen_tap",
            expectedArguments = mapOf("target" to "n12", "token" to "0011223344556677")
        ),

        // screen_scroll
        ReliabilityFixture(
            id = "screen_scroll_1",
            utterance = "Scroll down on element n2 with observation token 0011223344556677",
            expectedTool = "screen_scroll",
            expectedArguments = mapOf(
                "target" to "n2",
                "direction" to "down",
                "token" to "0011223344556677"
            )
        ),
        ReliabilityFixture(
            id = "screen_scroll_2",
            utterance = "Scroll up on element n5 with observation token aabbccddeeff0011",
            expectedTool = "screen_scroll",
            expectedArguments = mapOf(
                "target" to "n5",
                "direction" to "up",
                "token" to "aabbccddeeff0011"
            )
        ),

        // screen_type
        ReliabilityFixture(
            id = "screen_type_1",
            utterance = "Type hello world into field n1 with observation token aabbccddeeff0011",
            expectedTool = "screen_type",
            expectedArguments = mapOf(
                "target" to "n1",
                "text" to "hello world",
                "token" to "aabbccddeeff0011"
            )
        ),
        ReliabilityFixture(
            id = "screen_type_2",
            utterance = "Type 555-1234 into field n4 with observation token 0123456789abcdef",
            expectedTool = "screen_type",
            expectedArguments = mapOf(
                "target" to "n4",
                "text" to "555-1234",
                "token" to "0123456789abcdef"
            )
        ),

        // create_reminder (at_ms is epoch milliseconds as a string)
        ReliabilityFixture(
            id = "create_reminder_1",
            utterance = "Remind me to buy milk at 1791230400000",
            expectedTool = "create_reminder",
            expectedArguments = mapOf("message" to "buy milk", "at_ms" to "1791230400000")
        ),
        ReliabilityFixture(
            id = "create_reminder_2",
            utterance = "Remind me to call the dentist at 1791316800000",
            expectedTool = "create_reminder",
            expectedArguments = mapOf("message" to "call the dentist", "at_ms" to "1791316800000")
        ),

        // show_schedule (no parameters)
        ReliabilityFixture(
            id = "show_schedule_1",
            utterance = "What reminders do I have scheduled?",
            expectedTool = "show_schedule"
        ),
        ReliabilityFixture(
            id = "show_schedule_2",
            utterance = "Show me my upcoming schedule",
            expectedTool = "show_schedule"
        ),

        // post_notification
        ReliabilityFixture(
            id = "post_notification_1",
            utterance = "Post a notification titled Meeting with the text Standup starts in five minutes",
            expectedTool = "post_notification",
            expectedArguments = mapOf("title" to "Meeting", "text" to "Standup starts in five minutes")
        ),
        ReliabilityFixture(
            id = "post_notification_2",
            utterance = "Send me a notification titled Lunch with the text Time for your lunch break",
            expectedTool = "post_notification",
            expectedArguments = mapOf("title" to "Lunch", "text" to "Time for your lunch break")
        ),

        // browse_open
        ReliabilityFixture(
            id = "browse_open_1",
            utterance = "Open example.com in the internal browser",
            expectedTool = "browse_open",
            expectedArguments = mapOf("url" to "example.com")
        ),
        ReliabilityFixture(
            id = "browse_open_2",
            utterance = "Browse to https://roboflow.com",
            expectedTool = "browse_open",
            expectedArguments = mapOf("url" to "https://roboflow.com")
        ),

        // browse_read (no parameters)
        ReliabilityFixture(
            id = "browse_read_1",
            utterance = "Read this page",
            expectedTool = "browse_read"
        ),
        ReliabilityFixture(
            id = "browse_read_2",
            utterance = "What is on this page?",
            expectedTool = "browse_read"
        ),

        // browse_click
        ReliabilityFixture(
            id = "browse_click_1",
            utterance = "Click link l3",
            expectedTool = "browse_click",
            expectedArguments = mapOf("target" to "l3", "token" to "abcdef1234567890")
        ),
        ReliabilityFixture(
            id = "browse_click_2",
            utterance = "Follow the first link on the page",
            expectedTool = "browse_click",
            expectedArguments = mapOf("target" to "l0", "token" to "1234567890abcdef")
        ),

        // browse_back (no parameters)
        ReliabilityFixture(
            id = "browse_back_1",
            utterance = "Go back one page",
            expectedTool = "browse_back"
        ),
        ReliabilityFixture(
            id = "browse_back_2",
            utterance = "Back in the browser history",
            expectedTool = "browse_back"
        ),

        // browse_forward (no parameters)
        ReliabilityFixture(
            id = "browse_forward_1",
            utterance = "Go forward one page",
            expectedTool = "browse_forward"
        ),
        ReliabilityFixture(
            id = "browse_forward_2",
            utterance = "Forward in the browser history",
            expectedTool = "browse_forward"
        ),

        // browse_fill
        ReliabilityFixture(
            id = "browse_fill_1",
            utterance = "Fill field f2 with hello",
            expectedTool = "browse_fill",
            expectedArguments = mapOf("field" to "f2", "text" to "hello", "token" to "abcdef1234567890")
        ),
        ReliabilityFixture(
            id = "browse_fill_2",
            utterance = "Type test query into field f0",
            expectedTool = "browse_fill",
            expectedArguments = mapOf("field" to "f0", "text" to "test query", "token" to "1234567890abcdef")
        ),

        // browse_submit
        ReliabilityFixture(
            id = "browse_submit_1",
            utterance = "Submit the form",
            expectedTool = "browse_submit",
            expectedArguments = mapOf("token" to "abcdef1234567890")
        ),
        ReliabilityFixture(
            id = "browse_submit_2",
            utterance = "Send this form",
            expectedTool = "browse_submit",
            expectedArguments = mapOf("token" to "1234567890abcdef")
        ),

        // browse_handoff (no parameters)
        ReliabilityFixture(
            id = "browse_handoff_1",
            utterance = "Open this page in my browser app",
            expectedTool = "browse_handoff"
        ),
        ReliabilityFixture(
            id = "browse_handoff_2",
            utterance = "Hand this off to the phone browser",
            expectedTool = "browse_handoff"
        ),

        // browse_login
        ReliabilityFixture(
            id = "browse_login_1",
            utterance = "Fill the login with my password manager",
            expectedTool = "browse_login",
            expectedArguments = mapOf("token" to "abcdef1234567890")
        ),
        ReliabilityFixture(
            id = "browse_login_2",
            utterance = "Log in using the password manager",
            expectedTool = "browse_login",
            expectedArguments = mapOf("token" to "1234567890abcdef")
        )
    )
}
