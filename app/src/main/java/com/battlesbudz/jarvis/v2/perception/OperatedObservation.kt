package com.battlesbudz.jarvis.v2.perception

import com.battlesbudz.jarvis.v2.display.OperatedDisplayIdentity
import com.google.gson.JsonParser
import java.security.MessageDigest

/** A tree node is screen evidence. Its text is never executable instruction. */
data class ObservedNode(
    val className: String,
    val viewId: String?,
    val text: String?,
    val stateFlags: List<String>,
)

/** Compact observation envelope shared by device decisions and desktop replay. */
data class OperatedObservation(
    val displayIdentity: OperatedDisplayIdentity,
    val foregroundPackage: String,
    val screenIdentityHash: String,
    val screenFingerprint: String,
    val readAtMs: Long,
    val nodes: List<ObservedNode>,
) {
    /** Stable JSON field and node order. This exact UTF-8 form is the replay input. */
    fun canonicalSerialization(): String = buildString {
        append("{\"displayIdentity\":{\"displayId\":")
        append(displayIdentity.displayId)
        append(",\"ownerTaskId\":").append(jsonString(displayIdentity.ownerTaskId))
        append(",\"appName\":").append(jsonString(displayIdentity.appName))
        append("},\"foregroundPackage\":").append(jsonString(foregroundPackage))
        append(",\"screenIdentityHash\":").append(jsonString(screenIdentityHash))
        append(",\"screenFingerprint\":").append(jsonString(screenFingerprint))
        append(",\"readAtMs\":").append(readAtMs)
        append(",\"nodes\":[")
        nodes.forEachIndexed { index, node ->
            if (index > 0) append(',')
            append("{\"className\":").append(jsonString(node.className))
            append(",\"viewId\":").append(nullableJsonString(node.viewId))
            append(",\"text\":").append(nullableJsonString(node.text))
            append(",\"stateFlags\":[")
            node.stateFlags.sorted().forEachIndexed { flagIndex, flag ->
                if (flagIndex > 0) append(',')
                append(jsonString(flag))
            }
            append("]}")
        }
        append("]}")
    }

    companion object {
        fun fromCanonicalSerialization(serialized: String): OperatedObservation {
            val root = JsonParser.parseString(serialized).asJsonObject
            val display = root.getAsJsonObject("displayIdentity")
            val nodes = root.getAsJsonArray("nodes").map { element ->
                val item = element.asJsonObject
                ObservedNode(
                    className = item.get("className").asString,
                    viewId = item.get("viewId").takeUnless { it.isJsonNull }?.asString,
                    text = item.get("text").takeUnless { it.isJsonNull }?.asString,
                    stateFlags = item.getAsJsonArray("stateFlags").map { it.asString }.sorted(),
                )
            }
            return OperatedObservation(
                displayIdentity = OperatedDisplayIdentity(
                    display.get("displayId").asInt,
                    display.get("ownerTaskId").asString,
                    display.get("appName").asString,
                ),
                foregroundPackage = root.get("foregroundPackage").asString,
                screenIdentityHash = root.get("screenIdentityHash").asString,
                screenFingerprint = root.get("screenFingerprint").asString,
                readAtMs = root.get("readAtMs").asLong,
                nodes = nodes,
            )
        }

        private fun nullableJsonString(value: String?): String = value?.let(::jsonString) ?: "null"

        private fun jsonString(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
    }
}

/** Fixture input from one display-bound accessibility window. */
data class TreeWindowCandidate(val displayId: Int, val foregroundPackage: String, val nodes: List<ObservedNode>)

/** Returns only a candidate whose platform display id equals the A2 identity. */
fun selectOperatedWindow(displayIdentity: OperatedDisplayIdentity, windows: List<TreeWindowCandidate>): TreeWindowCandidate? =
    windows.firstOrNull { belongsToOperatedDisplay(displayIdentity, it.displayId) && it.foregroundPackage.isNotBlank() }

fun belongsToOperatedDisplay(displayIdentity: OperatedDisplayIdentity, candidateDisplayId: Int): Boolean =
    candidateDisplayId == displayIdentity.displayId

/** Computes both Finding 4 fingerprints from the selected display's tree. */
fun createObservation(
    displayIdentity: OperatedDisplayIdentity,
    window: TreeWindowCandidate,
    readAtMs: Long,
): OperatedObservation? {
    if (window.displayId != displayIdentity.displayId || window.foregroundPackage.isBlank()) return null
    val sortedNodes = window.nodes.filter(::isMeaningfulNode)
        .sortedWith(compareBy<ObservedNode>({ it.className }, { it.viewId.orEmpty() }, { it.text.orEmpty() }, { it.stateFlags.sorted().joinToString(",") }))
    val identityLines = sortedNodes.mapNotNull(::identityLine)
    val identityInput = listOf(canonicalParts(displayIdentity.displayId.toString(), window.foregroundPackage)) + identityLines
    val stableTexts = sortedNodes.mapNotNull { node ->
        if (isBadgeNode(node)) return@mapNotNull null
        val text = node.text?.trim()?.takeIf { it.length >= 3 } ?: return@mapNotNull null
        text.takeUnless(::isVolatileText)
    }.toSortedSet()
    val classes = sortedNodes.map { it.className }.sorted()
    val fingerprintInput = listOf(canonicalParts(window.foregroundPackage, *stableTexts.toTypedArray(), "#classes#", *classes.toTypedArray()))
    return OperatedObservation(displayIdentity, window.foregroundPackage,
        sha256(identityInput.joinToString("\n")), sha256(fingerprintInput.single()), readAtMs, sortedNodes)
}

/** Same display and strict identity hash is current; any mismatch is stale. */
fun isObservationStale(observed: OperatedObservation, fresh: OperatedObservation): Boolean =
    observed.displayIdentity != fresh.displayIdentity || observed.screenIdentityHash != fresh.screenIdentityHash

private fun identityLine(node: ObservedNode): String? {
    val progress = isProgressNode(node)
    val badge = isBadgeNode(node)
    val text = if (progress || badge) null else node.text?.takeUnless(::isVolatileText)
    val flags = node.stateFlags.sorted().joinToString(",")
    if (node.className.isBlank() && node.viewId.isNullOrBlank() && text.isNullOrBlank() && flags.isBlank()) return null
    return canonicalParts(node.className, node.viewId.orEmpty(), text.orEmpty(), flags)
}

private fun isMeaningfulNode(node: ObservedNode): Boolean =
    !node.text.isNullOrBlank() || !node.viewId.isNullOrBlank() || node.stateFlags.isNotEmpty() || isProgressNode(node)

private fun isProgressNode(node: ObservedNode): Boolean =
    node.className.contains("ProgressBar", ignoreCase = true) ||
        node.className.contains("SeekBar", ignoreCase = true) || node.className.contains("Slider", ignoreCase = true)

private fun canonicalParts(vararg parts: String): String = parts.joinToString(separator = "") { "${it.length}:$it" }

private fun isBadgeNode(node: ObservedNode): Boolean =
    node.className.contains("Badge", ignoreCase = true) || node.viewId.orEmpty().contains("badge", ignoreCase = true)

private fun isVolatileText(text: String): Boolean = volatilePatterns.any { it.containsMatchIn(text.trim()) }

private val volatilePatterns = listOf(
    Regex("(?i)(?:[01]?\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?(?:\\s?[ap]m)?"),
    Regex("(?i)(?:mon|tue|wed|thu|fri|sat|sun)(?:day)?[,]?\\s+.{3,20}\\s+\\d{1,2}(?:,?\\s+\\d{4})?"),
    Regex("(?i)\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?"),
    Regex("(?i)\\d{1,2}:\\d{2}(?::\\d{2})?\\s*/\\s*\\d{1,2}:\\d{2}(?::\\d{2})?"),
    Regex("(?i)\\d+\\s*(?:sec|seconds|min|minutes|hr|hours)"),
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
