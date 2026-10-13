package com.battlesbudz.jarvis.v2.actions

import kotlin.math.round

sealed interface MobileAction {
    data object ReadBattery : MobileAction
    data class OpenApp(
        val appName: String,
        val packageNameHint: String? = null
    ) : MobileAction
    data class SetVolume(val level: Int) : MobileAction
    data class MediaControl(val action: MediaControlAction) : MobileAction
    data class OpenWebsite(val url: String) : MobileAction
    data class OpenSettings(val screen: SettingsScreen) : MobileAction
    data class Navigate(val destination: String) : MobileAction
    /** Read-only screen snapshot; needs no session grant. */
    data object ScreenObserve : MobileAction
    /** Mutations require an admitted session grant plus a fresh observation token (M1c). */
    data class ScreenTap(val targetId: String, val token: String) : MobileAction
    data class ScreenScroll(
        val targetId: String,
        val direction: ScreenScrollDirection,
        val token: String
    ) : MobileAction
    data class ScreenType(val targetId: String, val text: String, val token: String) : MobileAction
    /** Schedule a one-shot reminder; dispatch writes a real M2 ledger entry. */
    data class CreateReminder(val message: String, val atMs: Long) : MobileAction
    /** Read-only schedule view; the receipt lists what the ledger actually holds. */
    data object ShowSchedule : MobileAction
    /** Post a user-visible notification; the step a reminder occurrence runs at fire time. */
    data class PostNotification(val title: String, val text: String) : MobileAction
    /** M4: open a URL in the internal browser (not the external browser app). */
    data class BrowseOpen(val url: String) : MobileAction
    /** M4: read-only page snapshot; needs no grant, carries the page token. */
    data object BrowseRead : MobileAction
    /** M4: follow a link from the latest browse_read; the token must be fresh. */
    data class BrowseClick(val linkId: String, val token: String) : MobileAction
    /** M4: history navigation; read-only. */
    data object BrowseBack : MobileAction
    data object BrowseForward : MobileAction
    /** M4: fill a form field; filling alone never submits the form. */
    data class BrowseFill(val fieldId: String, val text: String, val token: String) : MobileAction
    /** M4: submit the filled form; needs a D11 admission bound to destination+fields. */
    data class BrowseSubmit(val token: String) : MobileAction
    /** M4: hand the current page to the native browser app. */
    data object BrowseHandoff : MobileAction
    /** M4: password-manager fill; credentials never touch the model. */
    data class BrowseLogin(val token: String) : MobileAction
}

/** Verbs accepted by the media_control tool; skip maps to next/previous track. */
enum class MediaControlAction(val verb: String, val label: String) {
    PLAY("play", "play"),
    PAUSE("pause", "pause"),
    TOGGLE("toggle", "play/pause toggle"),
    NEXT("next", "skip to next track"),
    PREVIOUS("previous", "skip to previous track");

    companion object {
        fun fromVerb(verb: String): MediaControlAction? =
            entries.firstOrNull { it.verb == verb }
    }
}

/** Verbs accepted by the screen_scroll tool. */
enum class ScreenScrollDirection(val key: String) {
    UP("up"),
    DOWN("down");

    companion object {
        fun fromKey(key: String): ScreenScrollDirection? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Android system settings screens. Intent actions are platform string literals
 * (not android.provider.Settings constants) so the validator stays JVM-testable.
 */
enum class SettingsScreen(val key: String, val label: String, val intentAction: String) {
    WIFI("wifi", "Wi-Fi", "android.settings.WIFI_SETTINGS"),
    BLUETOOTH("bluetooth", "Bluetooth", "android.settings.BLUETOOTH_SETTINGS"),
    DISPLAY("display", "Display", "android.settings.DISPLAY_SETTINGS"),
    SOUND("sound", "Sound", "android.settings.SOUND_SETTINGS"),
    APPS("apps", "Apps", "android.settings.APPLICATION_SETTINGS"),
    BATTERY("battery", "Battery", "android.settings.BATTERY_SAVER_SETTINGS"),
    LOCATION("location", "Location", "android.settings.LOCATION_SOURCE_SETTINGS"),
    STORAGE("storage", "Storage", "android.settings.INTERNAL_STORAGE_SETTINGS"),
    NETWORK("network", "Network", "android.settings.WIRELESS_SETTINGS"),
    GENERAL("general", "Settings", "android.settings.SETTINGS");

    companion object {
        fun fromKey(key: String): SettingsScreen? = entries.firstOrNull { it.key == key }
        fun keys(): String = entries.joinToString { it.key }
    }
}

data class ActionRequest(
    val name: String,
    val arguments: Map<String, String> = emptyMap()
)

sealed interface ActionValidation {
    data class Valid(val action: MobileAction) : ActionValidation
    data class Rejected(val reason: String) : ActionValidation
}

class MobileActionValidator {
    private val packageNamePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    private val screenTargetPattern = Regex("^n[0-9]{1,4}$")
    private val screenTokenPattern = Regex("^[0-9a-f]{16}$")
    private val browserLinkPattern = Regex("^l[0-9]{1,4}$")
    private val browserFieldPattern = Regex("^f[0-9]{1,4}$")
    private val browserTokenPattern = Regex("^[0-9a-f]{16}$")
    private val maxScreenTypeText = 200
    private val maxBrowseFillText = 500

    fun validate(request: ActionRequest): ActionValidation = when (request.name) {
        "read_battery" -> ActionValidation.Valid(MobileAction.ReadBattery)
        "open_app" -> {
            val appName = request.arguments["app"]?.trim().orEmpty()
            val packageHint = request.arguments["package"]?.trim().orEmpty().takeIf {
                it.matches(packageNamePattern)
            }
            when {
                appName.isBlank() && packageHint == null ->
                    ActionValidation.Rejected("An installed app name is required.")
                else -> ActionValidation.Valid(
                    MobileAction.OpenApp(
                        appName = appName.ifBlank { packageHint!! },
                        packageNameHint = packageHint
                    )
                )
            }
        }
        "set_volume" -> parseVolumeLevel(request.arguments["level"].orEmpty())
            ?.let { ActionValidation.Valid(MobileAction.SetVolume(it)) }
            ?: ActionValidation.Rejected("Volume must be a percentage from 0 to 100.")
        "media_control" -> MediaControlAction.fromVerb(request.arguments["action"]?.trim().orEmpty())
            ?.let { ActionValidation.Valid(MobileAction.MediaControl(it)) }
            ?: ActionValidation.Rejected("Media action must be one of play, pause, toggle, next, previous.")
        "open_website" -> normalizeUrl(request.arguments["url"].orEmpty())
            ?.let { ActionValidation.Valid(MobileAction.OpenWebsite(it)) }
            ?: ActionValidation.Rejected("A valid http or https website URL is required.")
        "open_settings" -> SettingsScreen.fromKey(request.arguments["screen"]?.trim().orEmpty().lowercase())
            ?.let { ActionValidation.Valid(MobileAction.OpenSettings(it)) }
            ?: ActionValidation.Rejected("Settings screen must be one of: ${SettingsScreen.keys()}.")
        "navigate" -> {
            val destination = request.arguments["destination"]?.trim().orEmpty()
            if (destination.isNotBlank()) ActionValidation.Valid(MobileAction.Navigate(destination))
            else ActionValidation.Rejected("A destination is required.")
        }
        "browse_open" -> BrowserNavigationPolicy.normalizeUrl(request.arguments["url"].orEmpty())
            ?.let { ActionValidation.Valid(MobileAction.BrowseOpen(it)) }
            ?: ActionValidation.Rejected("A valid http or https website URL is required.")
        "browse_read" -> ActionValidation.Valid(MobileAction.BrowseRead)
        "browse_click" -> {
            val targetId = request.arguments["target"]?.trim().orEmpty()
            val token = request.arguments["token"]?.trim().orEmpty()
            when {
                !targetId.matches(browserLinkPattern) ->
                    ActionValidation.Rejected("A browse click target must be a link ID from browse_read (e.g. l3).")
                !token.matches(browserTokenPattern) ->
                    ActionValidation.Rejected("A browse click needs the page token from browse_read.")
                else -> ActionValidation.Valid(MobileAction.BrowseClick(targetId, token))
            }
        }
        "browse_back" -> ActionValidation.Valid(MobileAction.BrowseBack)
        "browse_forward" -> ActionValidation.Valid(MobileAction.BrowseForward)
        "browse_fill" -> {
            val fieldId = request.arguments["field"]?.trim().orEmpty()
            val text = request.arguments["text"]?.trim().orEmpty()
            val token = request.arguments["token"]?.trim().orEmpty()
            when {
                !fieldId.matches(browserFieldPattern) ->
                    ActionValidation.Rejected("A browse fill target must be a field ID from browse_read (e.g. f2).")
                text.isEmpty() || text.length > maxBrowseFillText ->
                    ActionValidation.Rejected("Fill text must be 1 to $maxBrowseFillText characters.")
                !token.matches(browserTokenPattern) ->
                    ActionValidation.Rejected("A browse fill needs the page token from browse_read.")
                else -> ActionValidation.Valid(MobileAction.BrowseFill(fieldId, text, token))
            }
        }
        "browse_submit" -> {
            val token = request.arguments["token"]?.trim().orEmpty()
            if (!token.matches(browserTokenPattern))
                ActionValidation.Rejected("A browse submit needs the page token from browse_read.")
            else ActionValidation.Valid(MobileAction.BrowseSubmit(token))
        }
        "browse_handoff" -> ActionValidation.Valid(MobileAction.BrowseHandoff)
        "browse_login" -> {
            val token = request.arguments["token"]?.trim().orEmpty()
            if (!token.matches(browserTokenPattern))
                ActionValidation.Rejected("A browse login needs the page token from browse_read.")
            else ActionValidation.Valid(MobileAction.BrowseLogin(token))
        }
        "screen_observe" -> ActionValidation.Valid(MobileAction.ScreenObserve)
        "screen_tap" -> screenTarget(request, "tap") { targetId, token ->
            MobileAction.ScreenTap(targetId, token)
        }
        "screen_scroll" -> {
            val direction = ScreenScrollDirection.fromKey(request.arguments["direction"]?.trim().orEmpty())
                ?: return ActionValidation.Rejected("Scroll direction must be up or down.")
            screenTarget(request, "scroll ${direction.key}") { targetId, token ->
                MobileAction.ScreenScroll(targetId, direction, token)
            }
        }
        "screen_type" -> {
            val text = request.arguments["text"]?.trim().orEmpty()
            if (text.isEmpty() || text.length > maxScreenTypeText) {
                return ActionValidation.Rejected("Text to type must be 1 to $maxScreenTypeText characters.")
            }
            screenTarget(request, "type into") { targetId, token ->
                MobileAction.ScreenType(targetId, text, token)
            }
        }
        "create_reminder" -> {
            val message = request.arguments["message"]?.trim().orEmpty()
            val atMs = request.arguments["at_ms"]?.toLongOrNull()
            when {
                message.isEmpty() || message.length > MAX_REMINDER_MESSAGE ->
                    ActionValidation.Rejected("A reminder message of 1 to $MAX_REMINDER_MESSAGE characters is required.")
                atMs == null || atMs <= 0 ->
                    ActionValidation.Rejected("A reminder needs a positive trigger time in epoch milliseconds.")
                else -> ActionValidation.Valid(MobileAction.CreateReminder(message, atMs))
            }
        }
        "show_schedule" -> ActionValidation.Valid(MobileAction.ShowSchedule)
        "post_notification" -> {
            val title = request.arguments["title"]?.trim().orEmpty()
            val text = request.arguments["text"]?.trim().orEmpty()
            when {
                title.isEmpty() || title.length > 64 ->
                    ActionValidation.Rejected("A notification title of 1 to 64 characters is required.")
                text.isEmpty() || text.length > MAX_REMINDER_MESSAGE ->
                    ActionValidation.Rejected("Notification text of 1 to $MAX_REMINDER_MESSAGE characters is required.")
                else -> ActionValidation.Valid(MobileAction.PostNotification(title, text))
            }
        }
        else -> ActionValidation.Rejected("Unsupported action: ${request.name}")
    }

    /**
     * Shape validation for screen-mutation targets. Freshness (token matches the
     * latest observation, target still on screen) is enforced by
     * [ScreenControlSession] at dispatch, not here: the validator is stateless.
     */
    private inline fun screenTarget(
        request: ActionRequest,
        verb: String,
        build: (targetId: String, token: String) -> MobileAction
    ): ActionValidation {
        val targetId = request.arguments["target"]?.trim().orEmpty()
        val token = request.arguments["token"]?.trim().orEmpty()
        if (!targetId.matches(screenTargetPattern)) {
            return ActionValidation.Rejected(
                "A screen $verb target must be an element ID from screen_observe (e.g. n3)."
            )
        }
        if (!token.matches(screenTokenPattern)) {
            return ActionValidation.Rejected(
                "A screen $verb needs the observation token from screen_observe."
            )
        }
        return ActionValidation.Valid(build(targetId, token))
    }

    /** Normalize a user/model-supplied URL to an https URL, rejecting dangerous schemes. */
    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") ||
            lower.startsWith("data:") || lower.startsWith("intent:")
        ) return null
        val withScheme = if (lower.startsWith("http://") || lower.startsWith("https://")) trimmed
        else "https://$trimmed"
        val host = withScheme.substringAfter("://").substringBefore("/")
        if ('.' !in host || host.startsWith(".") || host.endsWith(".")) return null
        return withScheme
    }

    private fun parseVolumeLevel(raw: String): Int? {
        val trimmed = raw.trim()
        val hasPercentSuffix = trimmed.endsWith("%")
        val value = trimmed.removeSuffix("%").trim().toDoubleOrNull() ?: return null
        // Some model outputs scale a percentage by 100 (50% -> 5000).
        val percent = if (
            !hasPercentSuffix &&
            value >= 1000.0 &&
            value <= 10000.0 &&
            value % 100.0 == 0.0
        ) value / 100.0 else value
        return percent.takeIf { it in 0.0..100.0 }?.let { round(it).toInt() }
    }
}
