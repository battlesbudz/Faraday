package com.battlesbudz.jarvis.v2.actions

import org.json.JSONArray
import org.json.JSONObject

/**
 * M3 AppFunctions nested schema model with strict type conversion (T16).
 *
 * Schemas describe provider function parameters and results, including
 * nested objects and arrays. Conversion is strict: no coercion between
 * types (a JSON string "3" is not an integer, a 3.0 double is not an
 * integer), unknown object keys are rejected, and every failure carries the
 * exact path, the expected type and the actual value kind.
 */
sealed interface AppFunctionType {
    data object Text : AppFunctionType
    data object Integer : AppFunctionType
    data object Number : AppFunctionType
    data object Bool : AppFunctionType
    data class Enum(val values: Set<String>) : AppFunctionType {
        init { require(values.isNotEmpty()) { "Enum needs at least one value." } }
    }
    data class Obj(
        val properties: Map<String, AppFunctionProperty>,
        val additionalProperties: Boolean = false
    ) : AppFunctionType
    data class Arr(
        val items: AppFunctionType,
        val minItems: Int? = null,
        val maxItems: Int? = null
    ) : AppFunctionType {
        init {
            require((minItems ?: 0) >= 0 && (maxItems ?: Int.MAX_VALUE) >= (minItems ?: 0))
        }
    }
}

data class AppFunctionProperty(
    val type: AppFunctionType,
    val required: Boolean,
    val description: String = ""
)

sealed interface TypeConversion {
    data class Ok(val value: Any?) : TypeConversion
    data class Error(val path: String, val expected: String, val actual: String) : TypeConversion
}

object AppFunctionTypeConverter {

    /** Strictly convert one provider-call argument map against the declared schema. */
    fun convertArguments(schema: AppFunctionType.Obj, args: Map<String, Any?>): TypeConversion =
        convertObject(schema, args, "$")

    fun convert(type: AppFunctionType, value: Any?, path: String): TypeConversion = when (type) {
        AppFunctionType.Text -> if (value is String) TypeConversion.Ok(value)
            else TypeConversion.Error(path, "string", kindOf(value))
        AppFunctionType.Integer -> when (value) {
            is Int -> TypeConversion.Ok(value)
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) TypeConversion.Ok(value.toInt())
                else TypeConversion.Error(path, "integer", "long out of int range")
            else -> TypeConversion.Error(path, "integer", kindOf(value))
        }
        AppFunctionType.Number -> when (value) {
            is Int, is Long -> TypeConversion.Ok((value as Number).toDouble())
            is Double -> if (value.isFinite()) TypeConversion.Ok(value)
                else TypeConversion.Error(path, "number", "non-finite double")
            is Float -> if (value.isFinite()) TypeConversion.Ok(value.toDouble())
                else TypeConversion.Error(path, "number", "non-finite float")
            else -> TypeConversion.Error(path, "number", kindOf(value))
        }
        AppFunctionType.Bool -> if (value is Boolean) TypeConversion.Ok(value)
            else TypeConversion.Error(path, "boolean", kindOf(value))
        is AppFunctionType.Enum -> if (value is String && value in type.values) TypeConversion.Ok(value)
            else TypeConversion.Error(path, "one of ${type.values.sorted()}", kindOf(value))
        is AppFunctionType.Obj -> if (value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            convertObject(type, value as Map<String, Any?>, path)
        } else TypeConversion.Error(path, "object", kindOf(value))
        is AppFunctionType.Arr -> if (value is List<*>) convertArray(type, value, path)
            else TypeConversion.Error(path, "array", kindOf(value))
    }

    private fun convertObject(
        schema: AppFunctionType.Obj,
        args: Map<String, Any?>,
        path: String
    ): TypeConversion {
        if (!schema.additionalProperties) {
            val unknown = args.keys.firstOrNull { it !in schema.properties }
            if (unknown != null) return TypeConversion.Error("$path.$unknown", "declared property", "unknown property")
        }
        val converted = linkedMapOf<String, Any?>()
        for ((name, property) in schema.properties) {
            val present = args.containsKey(name)
            val value = args[name]
            if (!present || value == null) {
                if (property.required) return TypeConversion.Error("$path.$name", describe(property.type), "missing")
                converted[name] = null
                continue
            }
            when (val result = convert(property.type, value, "$path.$name")) {
                is TypeConversion.Error -> return result
                is TypeConversion.Ok -> converted[name] = result.value
            }
        }
        // additionalProperties=true means undeclared keys are allowed: pass
        // them through unchanged instead of silently dropping them. Strict
        // schemas (additionalProperties=false) still reject unknown keys above.
        if (schema.additionalProperties) {
            for ((key, value) in args) {
                if (key !in schema.properties) converted[key] = value
            }
        }
        return TypeConversion.Ok(converted)
    }

    private fun convertArray(type: AppFunctionType.Arr, value: List<*>, path: String): TypeConversion {
        type.minItems?.let { if (value.size < it) return TypeConversion.Error(path, "at least $it items", "${value.size} items") }
        type.maxItems?.let { if (value.size > it) return TypeConversion.Error(path, "at most $it items", "${value.size} items") }
        val converted = arrayListOf<Any?>()
        value.forEachIndexed { index, item ->
            when (val result = convert(type.items, item, "$path[$index]")) {
                is TypeConversion.Error -> return result
                is TypeConversion.Ok -> converted += result.value
            }
        }
        return TypeConversion.Ok(converted)
    }

    private fun kindOf(value: Any?): String = when (value) {
        null -> "null"
        is String -> "string"
        is Int -> "integer"
        is Long -> "long"
        is Double -> "double"
        is Float -> "float"
        is Boolean -> "boolean"
        is Map<*, *> -> "object"
        is List<*> -> "array"
        else -> value.javaClass.simpleName
    }

    private fun describe(type: AppFunctionType): String = when (type) {
        AppFunctionType.Text -> "string"
        AppFunctionType.Integer -> "integer"
        AppFunctionType.Number -> "number"
        AppFunctionType.Bool -> "boolean"
        is AppFunctionType.Enum -> "one of ${type.values.sorted()}"
        is AppFunctionType.Obj -> "object"
        is AppFunctionType.Arr -> "array"
    }

    /** Parse an org.json value tree into plain Kotlin collections (JSONObject.NULL -> null). */
    fun fromJson(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key -> fromJson(value.get(key)) }
        is JSONArray -> (0 until value.length()).map { index -> fromJson(value.get(index)) }
        else -> value
    }

    fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return fromJson(json) as Map<String, Any?>
    }

    /**
     * Canonical JSON: sorted object keys, no whitespace, stable number
     * rendering. Used for schema-change detection — two parses of the same
     * schema always hash identically.
     */
    fun canonicalJson(value: Any?): String = buildString { appendCanonical(value) }

    private fun StringBuilder.appendCanonical(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> append(JSONObject.quote(value))
            is Boolean -> append(if (value) "true" else "false")
            is Int, is Long -> append(value.toString())
            is Double -> append(if (value.isFinite()) value.toBigDecimal().toPlainString() else "null")
            is Float -> append(if (value.isFinite()) value.toBigDecimal().toPlainString() else "null")
            is Map<*, *> -> {
                append('{')
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, Any?>
                map.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    append(JSONObject.quote(key)).append(':')
                    appendCanonical(map[key])
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendCanonical(item)
                }
                append(']')
            }
            else -> append(JSONObject.quote(value.toString()))
        }
    }

    fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
