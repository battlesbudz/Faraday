package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import org.json.JSONObject

/**
 * Converts Gemma's structured tool call into the small, typed
 * action contract used by the Android executor.
 */
object NativeActionDecoder {
    fun decode(call: ToolCall): ActionRequest? {
        val json = runCatching { JSONObject(call.arguments) }.getOrNull() ?: return null
        val args = when {
            json.has("args") && json.opt("args") is JSONObject -> json.getJSONObject("args")
            json.has("args") && json.optString("args").startsWith("{") ->
                runCatching { JSONObject(json.optString("args")) }.getOrNull() ?: return null
            else -> json
        }

        return when (call.name) {
            "read_battery" -> ActionRequest(call.name)
            "open_app" -> ActionRequest(
                name = call.name,
                arguments = buildMap {
                    listOf("app", "app_name").firstNotNullOfOrNull { key ->
                        args.optString(key).takeIf { it.isNotBlank() }
                    }?.let { put("app", it) }
                    listOf("package", "package_name").firstNotNullOfOrNull { key ->
                        args.optString(key).takeIf { it.isNotBlank() }
                    }?.let { put("package", it) }
                }
            )
            "set_volume" -> ActionRequest(
                name = call.name,
                arguments = mapOf("level" to args.optString("level"))
            )
            "media_control" -> ActionRequest(
                name = call.name,
                arguments = mapOf("action" to args.optString("action"))
            )
            "open_website" -> ActionRequest(
                name = call.name,
                arguments = mapOf("url" to args.optString("url"))
            )
            "open_settings" -> ActionRequest(
                name = call.name,
                arguments = mapOf("screen" to args.optString("screen"))
            )
            "navigate" -> ActionRequest(
                name = call.name,
                arguments = mapOf("destination" to args.optString("destination"))
            )
            // M4: internal browser tasks.
            "browse_open" -> ActionRequest(
                name = call.name,
                arguments = mapOf("url" to args.optString("url"))
            )
            "browse_read" -> ActionRequest(call.name)
            "browse_click" -> ActionRequest(
                name = call.name,
                arguments = mapOf(
                    "target" to args.optString("target"),
                    "token" to args.optString("token")
                )
            )
            "browse_back" -> ActionRequest(call.name)
            "browse_forward" -> ActionRequest(call.name)
            "browse_fill" -> ActionRequest(
                name = call.name,
                arguments = mapOf(
                    "field" to args.optString("field"),
                    "text" to args.optString("text"),
                    "token" to args.optString("token")
                )
            )
            "browse_submit" -> ActionRequest(
                name = call.name,
                arguments = mapOf("token" to args.optString("token"))
            )
            "browse_handoff" -> ActionRequest(call.name)
            "browse_login" -> ActionRequest(
                name = call.name,
                arguments = mapOf("token" to args.optString("token"))
            )
            "screen_observe" -> ActionRequest(call.name)
            "screen_tap" -> ActionRequest(
                name = call.name,
                arguments = mapOf(
                    "target" to args.optString("target"),
                    "token" to args.optString("token")
                )
            )
            "screen_scroll" -> ActionRequest(
                name = call.name,
                arguments = mapOf(
                    "target" to args.optString("target"),
                    "direction" to args.optString("direction"),
                    "token" to args.optString("token")
                )
            )
            "screen_type" -> ActionRequest(
                name = call.name,
                arguments = mapOf(
                    "target" to args.optString("target"),
                    "text" to args.optString("text"),
                    "token" to args.optString("token")
                )
            )
            else -> null
        }
    }
    /** Strict boundary used before real device side effects; legacy decode stays tolerant for old fixtures. */
    fun decodeStrict(call: ToolCall): ActionRequest? {
        val json = runCatching { JSONObject(call.arguments) }.getOrNull() ?: return null
        val args = when {
            json.has("args") && json.opt("args") is JSONObject && json.length() == 1 -> json.getJSONObject("args")
            else -> json
        }
        return MobileToolCatalog.decodeStrict(call.name, args)
    }

}
