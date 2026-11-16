package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.OpenApiTool
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Text protocol used by LFM3-family models. This only declares and translates
 * model text; callers must still pass returned [ToolCall]s through the normal
 * action planning, approval, dispatch, and audit path.
 */
object Lfm3ToolProtocol {
    private const val CALL_START = "<tool_call>"
    private const val CALL_END = "</tool_call>"
    private val schemaKeyOrder = listOf("name", "description", "parameters")
    private val parameterContainerKeyOrder = listOf("type", "properties", "required", "additionalProperties")
    private val parameterSchemaKeyOrder = listOf("type", "description", "minimum", "maximum", "minLength", "pattern")

    /** Render the exact OpenApiTool schemas into the LFM3 system-prompt block. */
    fun renderDefinitions(tools: List<OpenApiTool>): String {
        val definitions = tools.map { tool ->
            val raw = tool.getToolDescriptionJsonString()
            val schema = runCatching { JSONObject(raw) }.getOrElse {
                throw IllegalArgumentException("Tool declaration is not a JSON object", it)
            }
            require(schema.optString("name").isNotBlank()) { "Tool declaration has no name" }
            require(schema.optString("description").isNotBlank()) { "Tool declaration has no description" }
            require(schema.optJSONObject("parameters") != null) { "Tool declaration has no parameters object" }
            renderJson(schema, propertyOrder = propertyOrderFromDeclaration(raw))
        }
        return "<tools>\n${definitions.joinToString("\n")}\n</tools>"
    }

    /** JSONObject key iteration order is not a wire-format guarantee. */
    private fun renderJson(
        value: Any?,
        preferredKeys: List<String> = schemaKeyOrder,
        propertyDefinitions: Boolean = false,
        propertyOrder: List<String> = emptyList()
    ): String = when (value) {
        is JSONObject -> {
            val keys = mutableListOf<String>()
            val iterator = value.keys()
            while (iterator.hasNext()) keys += iterator.next()
            val keyRanks = preferredKeys.withIndex().associate { it.value to it.index }
            // Property order is part of MobileToolCatalog's raw declaration
            // string (for example, create_reminder declares message before
            // at_ms). Preserve that order while ordering schema containers
            // explicitly for stable prompt rendering.
            val orderedKeys = if (propertyDefinitions) {
                propertyOrder.filter { it in keys } + keys.filter { it !in propertyOrder }.sorted()
            } else keys.sortedWith(compareBy<String> { keyRanks[it] ?: Int.MAX_VALUE }.thenBy { it })
            orderedKeys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                val childOrder = when {
                    propertyDefinitions -> parameterSchemaKeyOrder
                    key == "parameters" -> parameterContainerKeyOrder
                    key == "properties" -> emptyList()
                    else -> emptyList()
                }
                val childIsPropertyMap = key == "properties"
                "${JSONObject.quote(key)}:${renderJson(
                    value.get(key), childOrder, childIsPropertyMap, propertyOrder
                )}"
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") {
            renderJson(value.get(it))
        }
        is String -> JSONObject.quote(value)
        JSONObject.NULL -> "null"
        is Number, is Boolean -> value.toString()
        else -> throw IllegalArgumentException("Unsupported JSON value in tool declaration")
    }

    /** Read property names from the source declaration, whose order JSONObject may discard. */
    private fun propertyOrderFromDeclaration(raw: String): List<String> {
        val properties = raw.substringAfter("\"properties\":{", missingDelimiterValue = "")
            .substringBefore("},\"required\"")
        if (properties.isEmpty()) return emptyList()
        return Regex("(?:^|,)\"([A-Za-z0-9_]+)\":\\{\"type\":")
            .findAll(properties)
            .map { it.groupValues[1] }
            .toList()
    }

    /**
     * Parse complete Liquid XML-style calls in emission order. Unknown tools,
     * duplicate parameters, malformed tags and malformed argument fragments
     * are discarded as whole calls; no partial call can reach dispatch.
     * JSON scalar values retain their JSON type, while unquoted text remains a
     * string as in the model card's example parser.
     */
    fun parseCalls(output: String, knownTools: Set<String>): List<ToolCall> {
        return parseCalls(output, knownTools, emptyMap())
    }

    /** Parse using declaration types so numeric-looking string arguments stay strings. */
    fun parseCalls(output: String, tools: List<OpenApiTool>): List<ToolCall> {
        val parameterTypes = linkedMapOf<String, Map<String, String>>()
        for (tool in tools) {
            val schema = JSONObject(tool.getToolDescriptionJsonString())
            val name = schema.getString("name")
            val properties = schema.getJSONObject("parameters").optJSONObject("properties") ?: JSONObject()
            parameterTypes[name] = buildMap {
                val keys = properties.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, properties.getJSONObject(key).optString("type"))
                }
            }
        }
        return parseCalls(output, parameterTypes.keys, parameterTypes)
    }

    private fun parseCalls(
        output: String,
        knownTools: Set<String>,
        parameterTypes: Map<String, Map<String, String>>
    ): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        var cursor = 0
        while (true) {
            val start = output.indexOf(CALL_START, cursor)
            if (start < 0) break
            val bodyStart = start + CALL_START.length
            val end = output.indexOf(CALL_END, bodyStart)
            if (end < 0) break
            parseCall(output.substring(bodyStart, end), knownTools, parameterTypes)?.let(calls::add)
            cursor = end + CALL_END.length
        }
        return calls
    }

    /** Format one result using the model's documented tool-role message shape. */
    fun renderToolResultMessage(result: String): String {
        // JSON string encoding preserves arbitrary executor text and prevents
        // result text from injecting ChatML or tool protocol boundary tokens.
        val safeJson = JSONObject.quote(result).replace("<", "\\u003c")
        return "<|im_start|>tool\n<tool_response>\n$safeJson\n</tool_response><|im_end|>"
    }

    private fun parseCall(
        body: String,
        knownTools: Set<String>,
        parameterTypes: Map<String, Map<String, String>>
    ): ToolCall? {
        var index = skipWhitespace(body, 0)
        val functionOpen = readHeader(body, index, "<function=") ?: return null
        val name = functionOpen.value
        if (!isName(name) || name !in knownTools) return null
        // Parameters are children of function; its close tag is valid only
        // after all complete parameter blocks have been consumed.
        index = skipWhitespace(body, functionOpen.end)
        val arguments = JSONObject()
        while (index < body.length && !body.startsWith("</function>", index)) {
            val parameter = readHeader(body, index, "<parameter=") ?: return null
            val key = parameter.value
            if (!isName(key) || arguments.has(key)) return null
            val close = body.indexOf("</parameter>", parameter.end)
            if (close < 0) return null
            val rawValue = body.substring(parameter.end, close).trim()
            if (rawValue.isEmpty()) return null
            val parsed = parseValue(rawValue, parameterTypes[name]?.get(key)) ?: return null
            arguments.put(key, parsed)
            index = skipWhitespace(body, close + "</parameter>".length)
        }
        if (!body.startsWith("</function>", index)) return null
        index = skipWhitespace(body, index + "</function>".length)
        if (index != body.length) return null
        return ToolCall(name, arguments.toString())
    }

    private data class Header(val value: String, val end: Int)

    private fun readHeader(text: String, from: Int, prefix: String): Header? {
        val index = skipWhitespace(text, from)
        if (!text.startsWith(prefix, index)) return null
        val valueStart = index + prefix.length
        val close = text.indexOf('>', valueStart)
        if (close < 0) return null
        val value = text.substring(valueStart, close).trim()
        if (value.isEmpty() || value.any { it == '\n' || it == '\r' || it == '<' }) return null
        return Header(value, close + 1)
    }

    private fun parseValue(raw: String, expectedType: String?): Any? {
        // Liquid's template emits non-string values as JSON and string values
        // as their literal text. Decode only a complete JSON value; otherwise
        // preserve the original text as a string for downstream strict schema
        // validation (which remains the dispatcher's responsibility).
        return try {
            val tokener = JSONTokener(raw)
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') return raw
            if (expectedType == "string") return if (value is String) value else raw
            if (expectedType == "integer" && value !is Number) return raw
            when (value) {
                is JSONObject, is JSONArray, is String, is Number, is Boolean, JSONObject.NULL -> value
                else -> null
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun isName(value: String): Boolean =
        value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '_' }

    private fun skipWhitespace(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }
}
