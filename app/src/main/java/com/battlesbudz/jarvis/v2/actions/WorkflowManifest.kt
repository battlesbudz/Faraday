package com.battlesbudz.jarvis.v2.actions

/**
 * M5 community workflows: versioned import/export manifests (D41–D44, T19).
 *
 * A manifest is the portable form of a workflow: the full step graph plus
 * the contracts an importer needs to decide safely — required tools (with
 * versions), permitted scopes, script runtime requirements, setup bindings
 * for redacted values, and provenance.
 *
 * Format rules:
 * - [WORKFLOW_MANIFEST_VERSION] is 1. Readers reject anything newer
 *   ("needs a newer app version") and anything below 1.
 * - Unknown FIELDS are ignored on read (forward-compatible). Unknown step,
 *   trigger, condition or wait KINDS are rejected — silently dropping a
 *   step would change what the workflow does, so fail closed.
 * - JSON is parsed and written by the small codec below, not by a
 *   platform library, so manifest bytes are identical on the JVM and on
 *   Android. (The codebase already hand-rolls parsing where platform
 *   behavior differs; see findBindingPlaceholders.)
 * - Serialization is canonical (object keys sorted), so two manifests
 *   with the same meaning produce the same bytes. The update policy
 *   relies on this for its documentation-only comparison.
 */

const val WORKFLOW_MANIFEST_VERSION = 1

/** A tool the workflow needs, with the minimum catalog version it was built against. */
data class ToolContract(val name: String, val minVersion: Long)

/** What the workflow's scripts need from the on-phone script runtime. */
data class ScriptRuntimeRequirements(
    val engine: String,
    val maxTimeMs: Long,
    val maxMemoryKb: Long,
    val maxOutputChars: Int,
    val requiredHostFunctions: List<String> = emptyList()
)

/**
 * A value the exporter redacted: the importer must supply it before the
 * workflow can be enabled. Placeholders look like `{{setup:api_key}}`.
 */
data class SetupBinding(val name: String, val description: String, val sensitive: Boolean)

/** Who published the manifest and when. */
data class ManifestProvenance(val author: String, val source: String, val createdAtMs: Long)

data class WorkflowManifest(
    val manifestVersion: Int = WORKFLOW_MANIFEST_VERSION,
    val workflow: WorkflowDefinition,
    val requiredTools: List<ToolContract> = emptyList(),
    val permittedScopes: List<String> = emptyList(),
    val scriptRuntime: ScriptRuntimeRequirements? = null,
    val setupBindings: List<SetupBinding> = emptyList(),
    val provenance: ManifestProvenance,
    val documentation: String = ""
)

/** Serialize to canonical JSON. */
fun WorkflowManifest.toJson(): String = ManifestJson.stringify(manifestToNode(this))

/**
 * Parse canonical (or hand-written) JSON into a manifest.
 * Throws IllegalArgumentException on malformed JSON, unsupported versions,
 * unknown step/trigger kinds, or a workflow that fails [validateWorkflowDefinition].
 */
fun parseWorkflowManifest(json: String): WorkflowManifest {
    val root = ManifestJson.parse(json).asObj("manifest")
    val version = root.reqInt("manifestVersion", "manifest")
    require(version >= 1) { "manifest: unsupported manifestVersion $version" }
    require(version <= WORKFLOW_MANIFEST_VERSION) {
        "manifest: manifestVersion $version needs a newer app version (this app reads up to $WORKFLOW_MANIFEST_VERSION)"
    }
    return WorkflowManifest(
        manifestVersion = version,
        workflow = parseManifestWorkflow(root.reqObj("workflow", "manifest"), "manifest.workflow"),
        requiredTools = root.optArr("requiredTools", "manifest").map {
            val o = it.asObj("manifest.requiredTools[]")
            ToolContract(o.reqString("name", "manifest.requiredTools[]"), o.reqLong("minVersion", "manifest.requiredTools[]"))
        },
        permittedScopes = root.optArr("permittedScopes", "manifest").map {
            (it as? MNode.Str)?.value ?: throw IllegalArgumentException("manifest.permittedScopes[]: expected string")
        },
        scriptRuntime = root.optObj("scriptRuntime", "manifest")?.let {
            ScriptRuntimeRequirements(
                engine = it.reqString("engine", "manifest.scriptRuntime"),
                maxTimeMs = it.reqLong("maxTimeMs", "manifest.scriptRuntime"),
                maxMemoryKb = it.reqLong("maxMemoryKb", "manifest.scriptRuntime"),
                maxOutputChars = it.reqInt("maxOutputChars", "manifest.scriptRuntime"),
                requiredHostFunctions = it.optArr("requiredHostFunctions", "manifest.scriptRuntime").map { fn ->
                    (fn as? MNode.Str)?.value
                        ?: throw IllegalArgumentException("manifest.scriptRuntime.requiredHostFunctions[]: expected string")
                }
            )
        },
        setupBindings = root.optArr("setupBindings", "manifest").map {
            val o = it.asObj("manifest.setupBindings[]")
            SetupBinding(
                o.reqString("name", "manifest.setupBindings[]"),
                o.optString("description", "manifest.setupBindings[]").orEmpty(),
                o.optBool("sensitive", "manifest.setupBindings[]") ?: false
            )
        },
        provenance = root.reqObj("provenance", "manifest").let {
            ManifestProvenance(
                it.reqString("author", "manifest.provenance"),
                it.reqString("source", "manifest.provenance"),
                it.reqLong("createdAtMs", "manifest.provenance")
            )
        },
        documentation = root.optString("documentation", "manifest").orEmpty()
    )
}

// ---------------------------------------------------------------------------
// Serialization
// ---------------------------------------------------------------------------

internal fun manifestToNode(manifest: WorkflowManifest): MNode = MNode.Obj(linkedMapOf(
    "manifestVersion" to MNode.Num(manifest.manifestVersion.toDouble()),
    "workflow" to workflowToNode(manifest.workflow),
    "requiredTools" to MNode.Arr(manifest.requiredTools.map {
        MNode.Obj(linkedMapOf("name" to MNode.Str(it.name), "minVersion" to MNode.Num(it.minVersion.toDouble())))
    }),
    "permittedScopes" to MNode.Arr(manifest.permittedScopes.map { MNode.Str(it) }),
    "scriptRuntime" to (manifest.scriptRuntime?.let {
        MNode.Obj(linkedMapOf(
            "engine" to MNode.Str(it.engine),
            "maxTimeMs" to MNode.Num(it.maxTimeMs.toDouble()),
            "maxMemoryKb" to MNode.Num(it.maxMemoryKb.toDouble()),
            "maxOutputChars" to MNode.Num(it.maxOutputChars.toDouble()),
            "requiredHostFunctions" to MNode.Arr(it.requiredHostFunctions.map { fn -> MNode.Str(fn) })
        ))
    } ?: MNode.Null),
    "setupBindings" to MNode.Arr(manifest.setupBindings.map {
        MNode.Obj(linkedMapOf(
            "name" to MNode.Str(it.name),
            "description" to MNode.Str(it.description),
            "sensitive" to MNode.Bool(it.sensitive)
        ))
    }),
    "provenance" to MNode.Obj(linkedMapOf(
        "author" to MNode.Str(manifest.provenance.author),
        "source" to MNode.Str(manifest.provenance.source),
        "createdAtMs" to MNode.Num(manifest.provenance.createdAtMs.toDouble())
    )),
    "documentation" to MNode.Str(manifest.documentation)
))

private fun workflowToNode(def: WorkflowDefinition): MNode = MNode.Obj(linkedMapOf(
    "id" to MNode.Str(def.id),
    "name" to MNode.Str(def.name),
    "description" to MNode.Str(def.description),
    "version" to MNode.Num(def.version.toDouble()),
    "maxStepsPerRun" to MNode.Num(def.maxStepsPerRun.toDouble()),
    "origin" to MNode.Str(if (def.origin == WorkflowOrigin.CONVERSATION) "conversation" else "captured"),
    "createdAtMs" to MNode.Num(def.createdAtMs.toDouble()),
    "updatedAtMs" to MNode.Num(def.updatedAtMs.toDouble()),
    "steps" to MNode.Arr(def.steps.map { it.toNode() }),
    "triggers" to MNode.Arr(def.triggers.map { it.toNode() })
))

internal fun WorkflowStep.toNode(): MNode = when (this) {
    is WorkflowStep.Tool -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("tool"),
        "id" to MNode.Str(id),
        "request" to MNode.Obj(linkedMapOf(
            "name" to MNode.Str(request.name),
            "arguments" to MNode.Obj(LinkedHashMap(request.arguments.mapValues { MNode.Str(it.value) }))
        )),
        "bindings" to MNode.Obj(LinkedHashMap(bindings.mapValues { (_, b) ->
            MNode.Obj(linkedMapOf("stepId" to MNode.Str(b.stepId), "outputName" to MNode.Str(b.outputName)))
        })),
        "outputs" to MNode.Obj(LinkedHashMap(outputs.mapValues { MNode.Str(it.value.name) }))
    ))
    is WorkflowStep.Branch -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("branch"),
        "id" to MNode.Str(id),
        "condition" to condition.toNode(),
        "thenSteps" to MNode.Arr(thenSteps.map { it.toNode() }),
        "elseSteps" to MNode.Arr(elseSteps.map { it.toNode() })
    ))
    is WorkflowStep.Wait -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("wait"),
        "id" to MNode.Str(id),
        "wait" to wait.toNode()
    ))
    is WorkflowStep.Adaptive -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("adaptive"),
        "id" to MNode.Str(id),
        "goal" to MNode.Str(goal),
        "candidates" to MNode.Arr(candidates.map {
            MNode.Obj(linkedMapOf(
                "name" to MNode.Str(it.name),
                "arguments" to MNode.Obj(LinkedHashMap(it.arguments.mapValues { arg -> MNode.Str(arg.value) }))
            ))
        }),
        "budget" to MNode.Obj(linkedMapOf(
            "maxAttempts" to MNode.Num(budget.maxAttempts.toDouble()),
            "maxWallMs" to MNode.Num(budget.maxWallMs.toDouble()),
            "noProgressLimit" to MNode.Num(budget.noProgressLimit.toDouble())
        ))
    ))
    is WorkflowStep.Script -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("script"),
        "id" to MNode.Str(id),
        "source" to MNode.Str(source),
        "requiredHostFunctions" to MNode.Arr(requiredHostFunctions.map { MNode.Str(it) }),
        "outputs" to MNode.Obj(LinkedHashMap(outputs.mapValues { MNode.Str(it.value.name) }))
    ))
}

private fun WorkflowCondition.toNode(): MNode {
    fun bindingNode(b: WorkflowBinding) =
        MNode.Obj(linkedMapOf("stepId" to MNode.Str(b.stepId), "outputName" to MNode.Str(b.outputName)))
    return when (this) {
        is WorkflowCondition.Equals -> MNode.Obj(linkedMapOf(
            "kind" to MNode.Str("equals"), "binding" to bindingNode(binding), "literal" to MNode.Str(literal)))
        is WorkflowCondition.NotEquals -> MNode.Obj(linkedMapOf(
            "kind" to MNode.Str("not_equals"), "binding" to bindingNode(binding), "literal" to MNode.Str(literal)))
        is WorkflowCondition.GreaterThan -> MNode.Obj(linkedMapOf(
            "kind" to MNode.Str("greater_than"), "binding" to bindingNode(binding), "number" to MNode.Num(number)))
        is WorkflowCondition.LessThan -> MNode.Obj(linkedMapOf(
            "kind" to MNode.Str("less_than"), "binding" to bindingNode(binding), "number" to MNode.Num(number)))
        is WorkflowCondition.Matches -> MNode.Obj(linkedMapOf(
            "kind" to MNode.Str("matches"), "binding" to bindingNode(binding), "regex" to MNode.Str(regex)))
    }
}

private fun WorkflowWait.toNode(): MNode = when (this) {
    is WorkflowWait.Timer -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("timer"), "durationMs" to MNode.Num(durationMs.toDouble())))
    is WorkflowWait.UntilTime -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("until_time"), "epochMs" to MNode.Num(epochMs.toDouble())))
    is WorkflowWait.Event -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("event"),
        "eventKind" to MNode.Str(if (kind == WorkflowEventKind.NOTIFICATION) "notification" else "location"),
        "appKey" to (appKey?.let { MNode.Str(it) } ?: MNode.Null),
        "latitude" to (latitude?.let { MNode.Num(it) } ?: MNode.Null),
        "longitude" to (longitude?.let { MNode.Num(it) } ?: MNode.Null),
        "radiusMeters" to (radiusMeters?.let { MNode.Num(it) } ?: MNode.Null)
    ))
}

internal fun WorkflowTrigger.toNode(): MNode = when (this) {
    is WorkflowTrigger.Manual -> MNode.Obj(linkedMapOf("kind" to MNode.Str("manual")))
    is WorkflowTrigger.Reminder -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("reminder"), "atMs" to MNode.Num(atMs.toDouble())))
    is WorkflowTrigger.Daily -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("daily"),
        "hour" to MNode.Num(hour.toDouble()), "minute" to MNode.Num(minute.toDouble())))
    is WorkflowTrigger.Window -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("window"),
        "earliestMs" to MNode.Num(earliestMs.toDouble()),
        "latestMs" to MNode.Num(latestMs.toDouble())))
    is WorkflowTrigger.OnNotification -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("on_notification"), "appKey" to MNode.Str(appKey)))
    is WorkflowTrigger.OnLocation -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("on_location"),
        "latitude" to MNode.Num(latitude), "longitude" to MNode.Num(longitude),
        "radiusMeters" to MNode.Num(radiusMeters)))
    is WorkflowTrigger.Deadline -> MNode.Obj(linkedMapOf(
        "kind" to MNode.Str("deadline"),
        "atMs" to MNode.Num(atMs.toDouble()), "title" to MNode.Str(title)))
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

private fun parseManifestWorkflow(o: Map<String, MNode>, path: String): WorkflowDefinition {
    val origin = when (o.optString("origin", path)?.lowercase()) {
        null, "conversation" -> WorkflowOrigin.CONVERSATION
        "captured" -> WorkflowOrigin.CAPTURED
        else -> throw IllegalArgumentException("$path.origin: unknown origin")
    }
    return WorkflowDefinition(
        id = o.reqString("id", path),
        name = o.reqString("name", path),
        description = o.optString("description", path).orEmpty(),
        steps = o.reqArr("steps", path).mapIndexed { i, s -> parseStep(s, "$path.steps[$i]") },
        triggers = o.reqArr("triggers", path).mapIndexed { i, t -> parseTrigger(t, "$path.triggers[$i]") },
        origin = origin,
        version = o.optLong("version", path) ?: 1L,
        enabled = false,
        createdAtMs = o.optLong("createdAtMs", path) ?: 0L,
        updatedAtMs = o.optLong("updatedAtMs", path) ?: 0L,
        maxStepsPerRun = o.optInt("maxStepsPerRun", path) ?: 16
    )
}

private fun parseStep(node: MNode, path: String): WorkflowStep {
    val o = node.asObj(path)
    val id = o.reqString("id", path)
    return when (o.reqString("kind", path)) {
        "tool" -> {
            val r = o.reqObj("request", path)
            WorkflowStep.Tool(
                id = id,
                request = ActionRequest(
                    r.reqString("name", "$path.request"),
                    r.optObj("arguments", "$path.request")
                        ?.mapValues { (k, v) ->
                            (v as? MNode.Str)?.value
                                ?: throw IllegalArgumentException("$path.request.arguments.$k: expected string")
                        } ?: emptyMap()
                ),
                bindings = o.optObj("bindings", path)
                    ?.mapValues { (k, v) ->
                        val b = v.asObj("$path.bindings.$k")
                        k to WorkflowBinding(
                            b.reqString("stepId", "$path.bindings.$k"),
                            b.reqString("outputName", "$path.bindings.$k")
                        )
                    }?.values?.toMap() ?: emptyMap(),
                outputs = o.optObj("outputs", path)
                    ?.mapValues { (k, v) ->
                        when ((v as? MNode.Str)?.value) {
                            "TEXT" -> WorkflowValueType.TEXT
                            "NUMBER" -> WorkflowValueType.NUMBER
                            "BOOLEAN" -> WorkflowValueType.BOOLEAN
                            else -> throw IllegalArgumentException("$path.outputs.$k: unknown value type")
                        }
                    } ?: emptyMap()
            )
        }
        "branch" -> WorkflowStep.Branch(
            id = id,
            condition = parseCondition(o.reqObj("condition", path), "$path.condition"),
            thenSteps = o.reqArr("thenSteps", path).mapIndexed { i, s -> parseStep(s, "$path.thenSteps[$i]") },
            elseSteps = o.optArr("elseSteps", path).mapIndexed { i, s -> parseStep(s, "$path.elseSteps[$i]") }
        )
        "wait" -> WorkflowStep.Wait(id, parseWait(o.reqObj("wait", path), "$path.wait"))
        "adaptive" -> {
            val b = o.reqObj("budget", path)
            WorkflowStep.Adaptive(
                id = id,
                goal = o.reqString("goal", path),
                candidates = o.reqArr("candidates", path).mapIndexed { i, c ->
                    val co = c.asObj("$path.candidates[$i]")
                    ActionRequest(
                        co.reqString("name", "$path.candidates[$i]"),
                        co.optObj("arguments", "$path.candidates[$i]")
                            ?.mapValues { (k, v) ->
                                (v as? MNode.Str)?.value
                                    ?: throw IllegalArgumentException("$path.candidates[$i].arguments.$k: expected string")
                            } ?: emptyMap()
                    )
                },
                budget = EffortBudget(
                    b.reqInt("maxAttempts", "$path.budget"),
                    b.reqLong("maxWallMs", "$path.budget"),
                    b.reqInt("noProgressLimit", "$path.budget")
                )
            )
        }
        "script" -> WorkflowStep.Script(
            id = id,
            source = o.reqString("source", path),
            requiredHostFunctions = o.optArr("requiredHostFunctions", path).map {
                (it as? MNode.Str)?.value
                    ?: throw IllegalArgumentException("$path.requiredHostFunctions[]: expected string")
            },
            outputs = o.optObj("outputs", path)
                ?.mapValues { (k, v) ->
                    when ((v as? MNode.Str)?.value) {
                        "TEXT" -> WorkflowValueType.TEXT
                        "NUMBER" -> WorkflowValueType.NUMBER
                        "BOOLEAN" -> WorkflowValueType.BOOLEAN
                        else -> throw IllegalArgumentException("$path.outputs.$k: unknown value type")
                    }
                } ?: emptyMap()
        )
        else -> throw IllegalArgumentException("$path: unknown step kind '${o["kind"]}'")
    }
}

private fun parseCondition(o: Map<String, MNode>, path: String): WorkflowCondition {
    val b = o.reqObj("binding", path)
    val binding = WorkflowBinding(b.reqString("stepId", "$path.binding"), b.reqString("outputName", "$path.binding"))
    return when (o.reqString("kind", path)) {
        "equals" -> WorkflowCondition.Equals(binding, o.reqString("literal", path))
        "not_equals" -> WorkflowCondition.NotEquals(binding, o.reqString("literal", path))
        "greater_than" -> WorkflowCondition.GreaterThan(binding, o.reqDouble("number", path))
        "less_than" -> WorkflowCondition.LessThan(binding, o.reqDouble("number", path))
        "matches" -> WorkflowCondition.Matches(binding, o.reqString("regex", path))
        else -> throw IllegalArgumentException("$path: unknown condition kind")
    }
}

private fun parseWait(o: Map<String, MNode>, path: String): WorkflowWait = when (o.reqString("kind", path)) {
    "timer" -> WorkflowWait.Timer(o.reqLong("durationMs", path))
    "until_time" -> WorkflowWait.UntilTime(o.reqLong("epochMs", path))
    "event" -> WorkflowWait.Event(
        kind = when (o.reqString("eventKind", path)) {
            "notification" -> WorkflowEventKind.NOTIFICATION
            "location" -> WorkflowEventKind.LOCATION
            else -> throw IllegalArgumentException("$path: unknown event kind")
        },
        appKey = o.optString("appKey", path),
        latitude = o.optDouble("latitude", path),
        longitude = o.optDouble("longitude", path),
        radiusMeters = o.optDouble("radiusMeters", path)
    )
    else -> throw IllegalArgumentException("$path: unknown wait kind")
}

private fun parseTrigger(node: MNode, path: String): WorkflowTrigger {
    val o = node.asObj(path)
    return when (o.reqString("kind", path)) {
        "manual" -> WorkflowTrigger.Manual
        "reminder" -> WorkflowTrigger.Reminder(o.reqLong("atMs", path))
        "daily" -> WorkflowTrigger.Daily(o.reqInt("hour", path), o.reqInt("minute", path))
        "window" -> WorkflowTrigger.Window(o.reqLong("earliestMs", path), o.reqLong("latestMs", path))
        "on_notification" -> WorkflowTrigger.OnNotification(o.reqString("appKey", path))
        "on_location" -> WorkflowTrigger.OnLocation(
            o.reqDouble("latitude", path), o.reqDouble("longitude", path), o.reqDouble("radiusMeters", path))
        "deadline" -> WorkflowTrigger.Deadline(o.reqLong("atMs", path), o.reqString("title", path))
        else -> throw IllegalArgumentException("$path: unknown trigger kind")
    }
}

// ---------------------------------------------------------------------------
// Minimal JSON codec (deterministic; no platform dependency)
// ---------------------------------------------------------------------------

internal sealed interface MNode {
    data class Obj(val fields: Map<String, MNode>) : MNode
    data class Arr(val items: List<MNode>) : MNode
    data class Str(val value: String) : MNode
    data class Num(val value: Double) : MNode
    data class Bool(val value: Boolean) : MNode
    data object Null : MNode
}

internal object ManifestJson {
    fun parse(text: String): MNode = JsonParser(text).parse()
    fun stringify(node: MNode): String = buildString { appendNode(node) }

    private fun StringBuilder.appendNode(n: MNode) {
        when (n) {
            is MNode.Str -> { append('"'); appendEscaped(n.value); append('"') }
            is MNode.Num -> appendNumber(n.value)
            is MNode.Bool -> append(if (n.value) "true" else "false")
            is MNode.Null -> append("null")
            is MNode.Arr -> {
                append('[')
                n.items.forEachIndexed { i, x -> if (i > 0) append(','); appendNode(x) }
                append(']')
            }
            is MNode.Obj -> {
                append('{')
                n.fields.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) append(',')
                    append('"'); appendEscaped(k); append('"'); append(':'); appendNode(n.fields.getValue(k))
                }
                append('}')
            }
        }
    }

    private fun StringBuilder.appendNumber(v: Double) {
        require(v.isFinite()) { "non-finite number cannot appear in a manifest" }
        val l = v.toLong()
        if (l.toDouble() == v && kotlin.math.abs(v) < 9.007199254740992E15) append(l.toString())
        else append(v.toString())
    }

    private fun StringBuilder.appendEscaped(s: String) {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

private class JsonParser(val text: String) {
    var pos = 0

    fun parse(): MNode {
        skipWs()
        val v = parseValue()
        skipWs()
        if (pos != text.length) fail("trailing characters after JSON value")
        return v
    }

    private fun fail(msg: String): Nothing =
        throw IllegalArgumentException("malformed manifest JSON at offset $pos: $msg")

    private fun skipWs() {
        while (pos < text.length && text[pos] in " \t\n\r") pos++
    }

    private fun parseValue(): MNode {
        if (pos >= text.length) fail("unexpected end of input")
        return when (val c = text[pos]) {
            '{' -> parseObj()
            '[' -> parseArr()
            '"' -> MNode.Str(parseString())
            't' -> expectLiteral("true", MNode.Bool(true))
            'f' -> expectLiteral("false", MNode.Bool(false))
            'n' -> expectLiteral("null", MNode.Null)
            '-', in '0'..'9' -> parseNumber()
            else -> fail("unexpected character '$c'")
        }
    }

    private fun expectLiteral(lit: String, value: MNode): MNode {
        if (!text.startsWith(lit, pos)) fail("expected '$lit'")
        pos += lit.length
        return value
    }

    private fun parseObj(): MNode {
        pos++ // {
        val fields = linkedMapOf<String, MNode>()
        skipWs()
        if (pos < text.length && text[pos] == '}') { pos++; return MNode.Obj(fields) }
        while (true) {
            skipWs()
            if (pos >= text.length || text[pos] != '"') fail("expected string key")
            val key = parseString()
            skipWs()
            if (pos >= text.length || text[pos] != ':') fail("expected ':'")
            pos++
            skipWs()
            fields[key] = parseValue()
            skipWs()
            if (pos >= text.length) fail("unterminated object")
            when (text[pos]) {
                ',' -> { pos++; continue }
                '}' -> { pos++; return MNode.Obj(fields) }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun parseArr(): MNode {
        pos++ // [
        val items = mutableListOf<MNode>()
        skipWs()
        if (pos < text.length && text[pos] == ']') { pos++; return MNode.Arr(items) }
        while (true) {
            skipWs()
            items += parseValue()
            skipWs()
            if (pos >= text.length) fail("unterminated array")
            when (text[pos]) {
                ',' -> { pos++; continue }
                ']' -> { pos++; return MNode.Arr(items) }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        pos++ // opening quote
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            val c = text[pos++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) fail("unterminated escape")
                    when (val e = text[pos++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> parseUnicodeEscape(out)
                        else -> fail("bad escape '\\$e'")
                    }
                }
                else -> {
                    if (c < ' ') fail("unescaped control character")
                    out.append(c)
                }
            }
        }
    }

    private fun parseUnicodeEscape(out: StringBuilder) {
        if (pos + 4 > text.length) fail("bad unicode escape")
        val hex = text.substring(pos, pos + 4)
        val code = hex.toIntOrNull(16) ?: fail("bad unicode escape")
        pos += 4
        // Surrogate pair: \uD83D\uDE00 -> single code point.
        if (code in 0xD800..0xDBFF && pos + 6 <= text.length &&
            text[pos] == '\\' && text[pos + 1] == 'u'
        ) {
            val low = text.substring(pos + 2, pos + 6).toIntOrNull(16)
            if (low != null && low in 0xDC00..0xDFFF) {
                pos += 6
                val full = 0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
                out.append(String(Character.toChars(full)))
                return
            }
        }
        out.append(code.toChar())
    }

    private fun parseNumber(): MNode {
        val start = pos
        if (pos < text.length && text[pos] == '-') pos++
        if (pos >= text.length) fail("bad number")
        if (text[pos] == '0') pos++
        else if (text[pos] in '1'..'9') { while (pos < text.length && text[pos] in '0'..'9') pos++ }
        else fail("bad number")
        if (pos < text.length && text[pos] == '.') {
            pos++
            if (pos >= text.length || text[pos] !in '0'..'9') fail("bad number")
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            if (pos >= text.length || text[pos] !in '0'..'9') fail("bad number")
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }
        val num = text.substring(start, pos).toDoubleOrNull() ?: fail("bad number")
        return MNode.Num(num)
    }
}

// ---------------------------------------------------------------------------
// Typed accessors (missing/wrong-typed fields fail with a path)
// ---------------------------------------------------------------------------

internal fun MNode.asObj(path: String): Map<String, MNode> =
    (this as? MNode.Obj)?.fields ?: throw IllegalArgumentException("$path: expected object")

internal fun Map<String, MNode>.reqString(field: String, path: String): String {
    val v = this[field] ?: throw IllegalArgumentException("$path: missing field '$field'")
    return (v as? MNode.Str)?.value ?: throw IllegalArgumentException("$path.$field: expected string")
}

internal fun Map<String, MNode>.optString(field: String, path: String): String? {
    val v = this[field] ?: return null
    if (v is MNode.Null) return null
    return (v as? MNode.Str)?.value ?: throw IllegalArgumentException("$path.$field: expected string")
}

internal fun Map<String, MNode>.reqLong(field: String, path: String): Long {
    val v = this[field] ?: throw IllegalArgumentException("$path: missing field '$field'")
    val n = (v as? MNode.Num)?.value ?: throw IllegalArgumentException("$path.$field: expected number")
    require(n.isFinite() && n == kotlin.math.floor(n) && kotlin.math.abs(n) < 9.007199254740992E15) {
        "$path.$field: expected integer"
    }
    return n.toLong()
}

internal fun Map<String, MNode>.optLong(field: String, path: String): Long? {
    val v = this[field] ?: return null
    if (v is MNode.Null) return null
    return (v as? MNode.Num)?.value?.let {
        require(it.isFinite() && it == kotlin.math.floor(it) && kotlin.math.abs(it) < 9.007199254740992E15) {
            "$path.$field: expected integer"
        }
        it.toLong()
    } ?: throw IllegalArgumentException("$path.$field: expected number")
}

internal fun Map<String, MNode>.reqInt(field: String, path: String): Int {
    val l = reqLong(field, path)
    require(l in Int.MIN_VALUE..Int.MAX_VALUE) { "$path.$field: integer out of range" }
    return l.toInt()
}

internal fun Map<String, MNode>.optInt(field: String, path: String): Int? =
    optLong(field, path)?.let {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "$path.$field: integer out of range" }
        it.toInt()
    }

internal fun Map<String, MNode>.reqDouble(field: String, path: String): Double {
    val v = this[field] ?: throw IllegalArgumentException("$path: missing field '$field'")
    return (v as? MNode.Num)?.value ?: throw IllegalArgumentException("$path.$field: expected number")
}

internal fun Map<String, MNode>.optDouble(field: String, path: String): Double? {
    val v = this[field] ?: return null
    if (v is MNode.Null) return null
    return (v as? MNode.Num)?.value ?: throw IllegalArgumentException("$path.$field: expected number")
}

internal fun Map<String, MNode>.optBool(field: String, path: String): Boolean? {
    val v = this[field] ?: return null
    if (v is MNode.Null) return null
    return (v as? MNode.Bool)?.value ?: throw IllegalArgumentException("$path.$field: expected boolean")
}

internal fun Map<String, MNode>.reqArr(field: String, path: String): List<MNode> {
    val v = this[field] ?: throw IllegalArgumentException("$path: missing field '$field'")
    return (v as? MNode.Arr)?.items ?: throw IllegalArgumentException("$path.$field: expected array")
}

internal fun Map<String, MNode>.optArr(field: String, path: String): List<MNode> {
    val v = this[field] ?: return emptyList()
    if (v is MNode.Null) return emptyList()
    return (v as? MNode.Arr)?.items ?: throw IllegalArgumentException("$path.$field: expected array")
}

internal fun Map<String, MNode>.reqObj(field: String, path: String): Map<String, MNode> {
    val v = this[field] ?: throw IllegalArgumentException("$path: missing field '$field'")
    return v.asObj("$path.$field")
}

internal fun Map<String, MNode>.optObj(field: String, path: String): Map<String, MNode>? {
    val v = this[field] ?: return null
    if (v is MNode.Null) return null
    return v.asObj("$path.$field")
}
