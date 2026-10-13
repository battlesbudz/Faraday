package com.battlesbudz.jarvis.v2.actions

import org.json.JSONObject

/**
 * The single source of truth for model-visible native actions. The same entries
 * generate LiteRT declarations and validate the strict side-effect boundary.
 */
object MobileToolCatalog {
    const val VERSION = 1

    /** The nine M4 internal-browser tools. */
    val BROWSE_TOOL_NAMES = setOf(
        "browse_open", "browse_read", "browse_click",
        "browse_back", "browse_forward", "browse_fill",
        "browse_submit", "browse_handoff", "browse_login"
    )

    /**
     * M4 wiring gate. The browser runtime path — the browser decorator, the
     * session owner, and the approval UI wired into the JarvisRuntime and
     * WorkflowCoordinator executor factories — is not installed in the
     * production factories yet, so the nine browse tools stay unavailable to
     * the model until the real path and its admission controls pass an
     * Android journey. Flipping this without that evidence would advertise
     * tools whose dispatch fails closed.
     */
    object BrowserRuntimeGate {
        @Volatile var wired: Boolean = false
    }

    fun isBrowseTool(name: String): Boolean = name in BROWSE_TOOL_NAMES

    enum class ParameterType(val schemaType: String) { STRING("string"), INTEGER("integer") }

    data class Parameter(
        val name: String,
        val type: ParameterType,
        val description: String,
        val minimum: Int? = null,
        val maximum: Int? = null,
        val minLength: Int? = null,
        val pattern: String? = null
    )

    data class Tool(
        val name: String,
        val version: Int = VERSION,
        val description: String,
        val parameters: List<Parameter> = emptyList()
    ) {
        fun schemaJson(): String = buildString {
            append("{\"name\":").append(JSONObject.quote(name))
            append(",\"description\":").append(JSONObject.quote(description))
            append(",\"parameters\":{\"type\":\"object\",\"properties\":{")
            parameters.forEachIndexed { index, parameter ->
                if (index > 0) append(',')
                append(JSONObject.quote(parameter.name)).append(":{\"type\":")
                    .append(JSONObject.quote(parameter.type.schemaType))
                    .append(",\"description\":").append(JSONObject.quote(parameter.description))
                parameter.minimum?.let { append(",\"minimum\":").append(it) }
                parameter.maximum?.let { append(",\"maximum\":").append(it) }
                parameter.minLength?.let { append(",\"minLength\":").append(it) }
                parameter.pattern?.let { append(",\"pattern\":").append(JSONObject.quote(it)) }
                append('}')
            }
            append("},\"required\":[")
            parameters.forEachIndexed { index, parameter ->
                if (index > 0) append(',')
                append(JSONObject.quote(parameter.name))
            }
            append("],\"additionalProperties\":false}}")
        }
    }

    private val entries = listOf(
        Tool(
            name = "read_battery",
            description = "Read the phone battery percentage, charge level, and current battery status. Use this when the user asks how much battery the phone has or what the battery percentage is."
        ),
        Tool(
            name = "open_app",
            description = "Open an installed Android application.",
            parameters = listOf(Parameter(
                name = "app",
                type = ParameterType.STRING,
                description = "The installed app's human-readable name, such as Facebook or YouTube.",
                minLength = 1,
                pattern = ".*\\S.*"
            ))
        ),
        Tool(
            name = "set_volume",
            description = "Set the phone media volume percentage from 0 to 100.",
            parameters = listOf(Parameter(
                name = "level",
                type = ParameterType.INTEGER,
                description = "The desired media volume percentage.",
                minimum = 0,
                maximum = 100
            ))
        ),
        Tool(
            name = "media_control",
            description = "Control media playback on the phone: play, pause, toggle play/pause, or skip to the next or previous track.",
            parameters = listOf(Parameter(
                name = "action",
                type = ParameterType.STRING,
                description = "The media command: one of play, pause, toggle, next, previous.",
                minLength = 1,
                pattern = "^(play|pause|toggle|next|previous)$"
            ))
        ),
        Tool(
            name = "open_website",
            description = "Open a website URL in the phone browser.",
            parameters = listOf(Parameter(
                name = "url",
                type = ParameterType.STRING,
                description = "The website URL, e.g. https://example.com or example.com.",
                minLength = 1
            ))
        ),
        Tool(
            name = "open_settings",
            description = "Open an Android system settings screen.",
            parameters = listOf(Parameter(
                name = "screen",
                type = ParameterType.STRING,
                description = "The settings screen: one of wifi, bluetooth, display, sound, apps, battery, location, storage, network, general.",
                minLength = 1,
                pattern = "^(wifi|bluetooth|display|sound|apps|battery|location|storage|network|general)$"
            ))
        ),
        Tool(
            name = "navigate",
            description = "Show map directions to a destination address or place name.",
            parameters = listOf(Parameter(
                name = "destination",
                type = ParameterType.STRING,
                description = "The destination address or place name.",
                minLength = 1
            ))
        ),
        // M4: internal browser tasks. Reads are open; mutations ride page tokens
        // from browse_read; submission needs an approval bound to destination.
        Tool(
            name = "browse_open",
            description = "Open a URL in Jarvis's internal browser. The page can then be read, clicked, and filled with the other browse tools.",
            parameters = listOf(Parameter(
                name = "url",
                type = ParameterType.STRING,
                description = "The website URL, e.g. https://example.com or example.com.",
                minLength = 1
            ))
        ),
        Tool(
            name = "browse_read",
            description = "Read the current internal-browser page: title, text, links, and form fields with IDs and a page token. Call this after browse_open and before browse_click, browse_fill, browse_submit, or browse_login; IDs and tokens expire when the page changes."
        ),
        Tool(
            name = "browse_click",
            description = "Click a link or button from the latest browse_read result. The link ID and page token must come from that read; stale targets are rejected and never followed.",
            parameters = listOf(
                Parameter(
                    name = "target",
                    type = ParameterType.STRING,
                    description = "The link ID from browse_read, e.g. l3.",
                    minLength = 1,
                    pattern = "^l[0-9]{1,4}$"
                ),
                Parameter(
                    name = "token",
                    type = ParameterType.STRING,
                    description = "The page token from browse_read.",
                    minLength = 1,
                    pattern = "^[0-9a-f]{16}$"
                )
            )
        ),
        Tool(
            name = "browse_back",
            description = "Go back one page in the internal browser history."
        ),
        Tool(
            name = "browse_forward",
            description = "Go forward one page in the internal browser history."
        ),
        Tool(
            name = "browse_fill",
            description = "Fill a form field from the latest browse_read result. The field ID and page token must come from that read; stale targets are rejected. Filling alone never submits the form.",
            parameters = listOf(
                Parameter(
                    name = "field",
                    type = ParameterType.STRING,
                    description = "The field ID from browse_read, e.g. f2.",
                    minLength = 1,
                    pattern = "^f[0-9]{1,4}$"
                ),
                Parameter(
                    name = "text",
                    type = ParameterType.STRING,
                    description = "The text to fill in, 1 to 500 characters.",
                    minLength = 1
                ),
                Parameter(
                    name = "token",
                    type = ParameterType.STRING,
                    description = "The page token from browse_read.",
                    minLength = 1,
                    pattern = "^[0-9a-f]{16}$"
                )
            )
        ),
        Tool(
            name = "browse_submit",
            description = "Submit the filled form on the current internal-browser page. The page token must come from the latest browse_read. Submissions need the user's approval bound to the final destination and form content; without it this reports what would be submitted and asks for approval instead of sending anything.",
            parameters = listOf(Parameter(
                name = "token",
                type = ParameterType.STRING,
                description = "The page token from browse_read.",
                minLength = 1,
                pattern = "^[0-9a-f]{16}$"
            ))
        ),
        Tool(
            name = "browse_handoff",
            description = "Open the current internal-browser page in the phone's own browser app."
        ),
        Tool(
            name = "browse_login",
            description = "Fill the current page's login form with the password manager. Credentials are never shown to the model or written anywhere; only the outcome is reported. The page token must come from the latest browse_read.",
            parameters = listOf(Parameter(
                name = "token",
                type = ParameterType.STRING,
                description = "The page token from browse_read.",
                minLength = 1,
                pattern = "^[0-9a-f]{16}$"
            ))
        ),
        Tool(
            name = "screen_observe",
            description = "Look at the current phone screen and return a compact list of the visible interactive elements with IDs and an observation token. Call this before screen_tap, screen_scroll, or screen_type; element IDs and tokens expire when the screen changes."
        ),
        Tool(
            name = "screen_tap",
            description = "Tap a screen element from the latest screen_observe result. The target ID and token must come from that observation; stale or mismatched targets are rejected and never tapped.",
            parameters = listOf(
                Parameter(
                    name = "target",
                    type = ParameterType.STRING,
                    description = "The element ID from screen_observe, e.g. n3.",
                    minLength = 1,
                    pattern = "^n[0-9]{1,4}$"
                ),
                Parameter(
                    name = "token",
                    type = ParameterType.STRING,
                    description = "The observation token from screen_observe.",
                    minLength = 1,
                    pattern = "^[0-9a-f]{16}$"
                )
            )
        ),
        Tool(
            name = "screen_scroll",
            description = "Scroll a scrollable element from the latest screen_observe result up or down. The target ID and token must come from that observation; stale targets are rejected.",
            parameters = listOf(
                Parameter(
                    name = "target",
                    type = ParameterType.STRING,
                    description = "The element ID from screen_observe, e.g. n3.",
                    minLength = 1,
                    pattern = "^n[0-9]{1,4}$"
                ),
                Parameter(
                    name = "direction",
                    type = ParameterType.STRING,
                    description = "The scroll direction: up or down.",
                    minLength = 1,
                    pattern = "^(up|down)$"
                ),
                Parameter(
                    name = "token",
                    type = ParameterType.STRING,
                    description = "The observation token from screen_observe.",
                    minLength = 1,
                    pattern = "^[0-9a-f]{16}$"
                )
            )
        ),
        Tool(
            name = "screen_type",
            description = "Type text into an editable field from the latest screen_observe result. The target ID and token must come from that observation; stale targets are rejected.",
            parameters = listOf(
                Parameter(
                    name = "target",
                    type = ParameterType.STRING,
                    description = "The element ID from screen_observe, e.g. n3.",
                    minLength = 1,
                    pattern = "^n[0-9]{1,4}$"
                ),
                Parameter(
                    name = "text",
                    type = ParameterType.STRING,
                    description = "The text to type, 1 to 200 characters.",
                    minLength = 1
                ),
                Parameter(
                    name = "token",
                    type = ParameterType.STRING,
                    description = "The observation token from screen_observe.",
                    minLength = 1,
                    pattern = "^[0-9a-f]{16}$"
                )
            )
        ),
        Tool(
            name = "create_reminder",
            description = "Schedule a one-shot reminder that alerts the user at the requested time with the given message. Use when the user asks to be reminded of something at a specific time. The reminder is written to the workflow ledger and fires an Android alarm; the reply confirms only what was actually scheduled.",
            parameters = listOf(
                Parameter(
                    name = "message",
                    type = ParameterType.STRING,
                    description = "The reminder message, 1 to 256 characters.",
                    minLength = 1
                ),
                Parameter(
                    name = "at_ms",
                    type = ParameterType.STRING,
                    description = "Absolute trigger time as epoch milliseconds, e.g. 1791230400000. Must be in the future.",
                    minLength = 1,
                    pattern = "^[0-9]+$"
                )
            )
        ),
        Tool(
            name = "show_schedule",
            description = "List the user's scheduled reminders and upcoming routine runs, or report honestly that nothing is scheduled. Use when the user asks what is scheduled or where a reminder went."
        ),
        Tool(
            name = "post_notification",
            description = "Post a notification to the user with a title and text. Used by scheduled reminders when they fire; not for ordinary chat.",
            parameters = listOf(
                Parameter(
                    name = "title",
                    type = ParameterType.STRING,
                    description = "Notification title, 1 to 64 characters.",
                    minLength = 1
                ),
                Parameter(
                    name = "text",
                    type = ParameterType.STRING,
                    description = "Notification text, 1 to 256 characters.",
                    minLength = 1
                )
            )
        )
    )

    /**
     * The model-visible tools. Browse tools are withheld while
     * [BrowserRuntimeGate] is closed (see its KDoc): the model must never
     * see tools the runtime cannot execute.
     */
    fun all(): List<Tool> =
        if (BrowserRuntimeGate.wired) entries
        else entries.filter { it.name !in BROWSE_TOOL_NAMES }

    /** Storage/decode lookup: still resolves every catalog tool, including gated browse tools. */
    fun find(name: String): Tool? = entries.firstOrNull { it.name == name }

    /** Strict decoder contract: exact keys and JSON types only, with no coercion. */
    fun decodeStrict(name: String, arguments: JSONObject): ActionRequest? {
        val tool = find(name) ?: return null
        if (arguments.length() != tool.parameters.size || tool.parameters.any { !arguments.has(it.name) }) return null
        val decoded = buildMap {
            for (parameter in tool.parameters) {
                val value = arguments.opt(parameter.name)
                val canonical = when (parameter.type) {
                    ParameterType.STRING -> (value as? String)?.takeIf { string ->
                        (parameter.minLength == null || string.length >= parameter.minLength) &&
                            (parameter.pattern == null || Regex(parameter.pattern).matches(string))
                    }?.trim()
                    ParameterType.INTEGER -> (value as? Int)?.takeIf { integer ->
                        (parameter.minimum == null || integer >= parameter.minimum) &&
                            (parameter.maximum == null || integer <= parameter.maximum)
                    }?.toString()
                } ?: return null
                put(parameter.name, canonical)
            }
        }
        return ActionRequest(name, decoded)
    }
}
