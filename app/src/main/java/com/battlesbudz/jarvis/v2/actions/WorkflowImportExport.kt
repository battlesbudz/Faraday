package com.battlesbudz.jarvis.v2.actions

/**
 * M5 community workflows: import with review, export with redaction (D41–D44, T19).
 *
 * Import never activates anything by itself: every import is admitted as a
 * disabled draft (M2 draft semantics), and the review names exactly what is
 * missing. If required tools, scopes, or the script runtime are unavailable,
 * the draft is saved DISABLED with a clear reason instead of being
 * silently half-working.
 *
 * Export is fail-closed for privacy: accounts, tokens, personal argument
 * values, private endpoints, identifiers and embedded secrets are replaced
 * with `{{setup:name}}` placeholders, and the manifest lists them as setup
 * bindings the importer must fill in. [ExportPreview] shows what will be
 * shared versus what was redacted, so the exporter reviews before sending.
 */

// ---------------------------------------------------------------------------
// Capabilities & review
// ---------------------------------------------------------------------------

/** What the on-phone script runtime offers (null on [DeviceCapabilities] = none installed). */
data class ScriptRuntimeCapabilities(
    val engine: String,
    val maxTimeMs: Long,
    val maxMemoryKb: Long,
    val maxOutputChars: Int,
    val hostFunctions: Set<String>
)

/** What this device can run: tool catalog versions, granted scopes, script runtime. */
data class DeviceCapabilities(
    val availableTools: Map<String, Long>,
    val grantedScopes: Set<String>,
    val scriptRuntime: ScriptRuntimeCapabilities? = null
)

data class ImportReview(
    val manifest: WorkflowManifest,
    val missingTools: List<ToolContract>,
    val missingScopes: List<String>,
    val runtimeProblems: List<String>,
    val setupBindings: List<SetupBinding>,
    val previewText: String
) {
    /** True when everything the manifest needs is present on this device. */
    val ready: Boolean get() = missingTools.isEmpty() && missingScopes.isEmpty() && runtimeProblems.isEmpty()
}

/**
 * Parse [json] and check it against [caps]. Never throws for a missing
 * dependency — those are reported in the review. Throws
 * IllegalArgumentException only for a malformed manifest.
 */
fun reviewWorkflowManifest(json: String, caps: DeviceCapabilities): ImportReview {
    val manifest = parseWorkflowManifest(json)
    val usedTools = collectManifestToolNames(manifest.workflow.steps)
    val contracts = (manifest.requiredTools + usedTools.map { ToolContract(it, 1) })
        .groupBy { it.name }
        .map { (name, cs) -> ToolContract(name, cs.maxOf { it.minVersion }) }
    val missingTools = contracts.filter { (caps.availableTools[it.name] ?: 0L) < it.minVersion }
    val missingScopes = manifest.permittedScopes.filter { it !in caps.grantedScopes }
    // The top-level scriptRuntime metadata is advisory: a manifest that
    // omits it but contains Script steps still needs the runtime, so the
    // requirement is derived from the steps when the metadata is absent.
    val runtimeProblems = checkScriptRuntime(effectiveScriptRuntime(manifest), caps.scriptRuntime)
    val preview = buildString {
        appendLine(manifest.workflow.previewText())
        appendLine()
        appendLine("Shared by ${manifest.provenance.author} via ${manifest.provenance.source}.")
        if (manifest.documentation.isNotBlank()) appendLine(manifest.documentation)
        if (manifest.setupBindings.isNotEmpty())
            appendLine("${manifest.setupBindings.size} value(s) were redacted by the sender and need filling in before enabling.")
        if (!ready(missingTools, missingScopes, runtimeProblems))
            appendLine("Missing on this device: " + listOf(
                missingTools.map { "tool ${it.name} (v${it.minVersion})" },
                missingScopes.map { "scope $it" },
                runtimeProblems
            ).flatten().joinToString("; "))
    }
    return ImportReview(manifest, missingTools, missingScopes, runtimeProblems, manifest.setupBindings, preview)
}

private fun ready(missingTools: List<ToolContract>, missingScopes: List<String>, runtimeProblems: List<String>) =
    missingTools.isEmpty() && missingScopes.isEmpty() && runtimeProblems.isEmpty()

/**
 * The script runtime a manifest actually needs. The declared top-level
 * metadata wins for engine and limits, but the required host functions are
 * always the UNION of the metadata and the Script steps' own declarations:
 * underdeclared metadata must never mark a script ready when a required
 * host function is unavailable on the device. When the metadata is omitted
 * but the workflow contains Script steps, the whole requirement is derived
 * from the steps (interpreter defaults) — trusting the omission would admit
 * a workflow whose scripts cannot run. And any Script step at all — even one
 * with no host functions and no declared metadata — requires a runtime: a
 * function-free script still cannot run without an interpreter.
 */
private fun effectiveScriptRuntime(manifest: WorkflowManifest): ScriptRuntimeRequirements? {
    val scriptSteps = collectScriptSteps(manifest.workflow.steps)
    val stepFunctions = scriptSteps
        .flatMap { it.requiredHostFunctions }.distinct()
    val declared = manifest.scriptRuntime
    if (declared == null) {
        if (scriptSteps.isEmpty()) return null
        return ScriptRuntimeRequirements(
            engine = SCRIPT_ENGINE_NAME,
            maxTimeMs = DEFAULT_SCRIPT_MAX_TIME_MS,
            maxMemoryKb = DEFAULT_SCRIPT_MAX_MEMORY_KB,
            maxOutputChars = DEFAULT_SCRIPT_MAX_OUTPUT_CHARS,
            requiredHostFunctions = stepFunctions
        )
    }
    if (scriptSteps.isEmpty()) return declared
    return declared.copy(
        requiredHostFunctions = (declared.requiredHostFunctions + stepFunctions).distinct()
    )
}

private fun checkScriptRuntime(
    required: ScriptRuntimeRequirements?,
    caps: ScriptRuntimeCapabilities?
): List<String> {
    if (required == null) return emptyList()
    if (caps == null) return listOf("script runtime “${required.engine}” is not installed on this device")
    val problems = mutableListOf<String>()
    if (caps.engine != required.engine)
        problems += "script engine “${required.engine}” is not available (have “${caps.engine}”)"
    if (caps.maxTimeMs < required.maxTimeMs)
        problems += "script runtime allows ${caps.maxTimeMs}ms but the workflow needs ${required.maxTimeMs}ms"
    if (caps.maxMemoryKb < required.maxMemoryKb)
        problems += "script runtime allows ${caps.maxMemoryKb}KB but the workflow needs ${required.maxMemoryKb}KB"
    if (caps.maxOutputChars < required.maxOutputChars)
        problems += "script runtime allows ${caps.maxOutputChars} output chars but the workflow needs ${required.maxOutputChars}"
    val missingFns = required.requiredHostFunctions.filter { it !in caps.hostFunctions }
    if (missingFns.isNotEmpty()) problems += "script host functions not available: ${missingFns.joinToString(", ")}"
    return problems
}

sealed interface ImportDecision {
    /** Admitted as a disabled draft. [disabledReason] is null when fully ready. */
    data class Admitted(val definition: WorkflowDefinition, val disabledReason: String?) : ImportDecision
    data class Rejected(val reason: String) : ImportDecision
}

/**
 * Decide on a reviewed manifest. The definition is always admitted disabled
 * (M2: drafts never run until an explicit enable); [disabledReason] tells
 * the UI why it cannot be enabled yet, or null when it is ready to enable.
 */
fun admitWorkflowManifest(review: ImportReview, userApproved: Boolean): ImportDecision {
    if (!userApproved) return ImportDecision.Rejected("Import declined during review.")
    val reasons = buildList {
        review.missingTools.forEach { add("tool “${it.name}” (needs v${it.minVersion}) is not available on this device") }
        review.missingScopes.forEach { add("scope “$it” is not granted on this device") }
        addAll(review.runtimeProblems)
        if (review.setupBindings.isNotEmpty())
            add("${review.setupBindings.size} redacted value(s) need filling in: " +
                review.setupBindings.joinToString(", ") { it.name })
    }
    return ImportDecision.Admitted(
        definition = review.manifest.workflow.copy(enabled = false),
        disabledReason = reasons.takeIf { it.isNotEmpty() }?.joinToString("; ")
    )
}

/**
 * A redacted branch-condition regex is stored as a quoted setup placeholder:
 * `\Q{{setup:name}}\E`. A bare `{{setup:name}}` is not a valid regex — Java's
 * Pattern rejects the braces ("Illegal repetition") — which would make an
 * exported manifest fail [WorkflowDefinition]'s regex validation at import
 * review. The `\Q…\E` quoting keeps the stored value a valid regex (it
 * matches the literal placeholder text) while leaving the `{{setup:name}}`
 * substring intact and greppable; [resolveSetupBindings] restores the exact
 * original regex via typed whole-string replacement, never by leaving the
 * quotes in place.
 */
internal const val REDACTED_REGEX_PREFIX = "\\Q{{setup:"
internal const val REDACTED_REGEX_SUFFIX = "}}\\E"

/**
 * The setup binding name when [regex] is a redacted-regex placeholder
 * ([REDACTED_REGEX_PREFIX]…[REDACTED_REGEX_SUFFIX]), or null for an ordinary
 * regex. The name is restricted to the binding-name alphabet so a
 * user-authored regex that merely resembles the shape cannot be mistaken
 * for a placeholder.
 */
internal fun redactedRegexBindingName(regex: String): String? {
    if (!regex.startsWith(REDACTED_REGEX_PREFIX) || !regex.endsWith(REDACTED_REGEX_SUFFIX)) return null
    val name = regex.removePrefix(REDACTED_REGEX_PREFIX).removeSuffix(REDACTED_REGEX_SUFFIX)
    if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) return null
    return name
}

/**
 * Fill the `{{setup:name}}` placeholders left by export redaction.
 * Throws IllegalArgumentException if any placeholder has no value.
 */
fun resolveSetupBindings(definition: WorkflowDefinition, values: Map<String, String>): WorkflowDefinition {
    val missing = mutableSetOf<String>()
    fun resolve(value: String): String {
        var out = value
        var i = out.indexOf("{{setup:")
        while (i >= 0) {
            val end = out.indexOf("}}", i)
            if (end < 0) break
            val name = out.substring(i + "{{setup:".length, end)
            val replacement = values[name]
            if (replacement == null) {
                missing += name
                i = out.indexOf("{{setup:", end)
            } else {
                out = out.substring(0, i) + replacement + out.substring(end + 2)
                i = out.indexOf("{{setup:", i + replacement.length)
            }
        }
        return out
    }
    /**
     * Restore a redacted Matches regex to its exact original value.
     * Redacted regexes are stored quoted ([REDACTED_REGEX_PREFIX]) so the
     * manifest passes regex validation at import review; generic substring
     * replacement would leave the quoting wrapper behind and change what
     * the regex matches, so a quoted placeholder is detected and replaced
     * whole-string here instead. An ordinary (non-placeholder) regex still
     * goes through the generic placeholder pass.
     */
    fun resolveRedactedRegex(regex: String): String {
        val name = redactedRegexBindingName(regex) ?: return resolve(regex)
        val replacement = values[name]
        if (replacement == null) {
            missing += name
            return regex
        }
        return replacement
    }
    /** Restore redacted branch condition values (Equals/NotEquals literal, Matches regex). */
    fun resolveCondition(condition: WorkflowCondition): WorkflowCondition = when (condition) {
        is WorkflowCondition.Equals -> condition.copy(literal = resolve(condition.literal))
        is WorkflowCondition.NotEquals -> condition.copy(literal = resolve(condition.literal))
        is WorkflowCondition.Matches -> condition.copy(regex = resolveRedactedRegex(condition.regex))
        else -> condition
    }
    fun resolveStep(step: WorkflowStep): WorkflowStep = when (step) {
        is WorkflowStep.Tool -> step.copy(
            request = step.request.copy(arguments = step.request.arguments.mapValues { resolve(it.value) }))
        is WorkflowStep.Branch -> step.copy(
            condition = resolveCondition(step.condition),
            thenSteps = step.thenSteps.map(::resolveStep),
            elseSteps = step.elseSteps.map(::resolveStep))
        is WorkflowStep.Wait -> step
        is WorkflowStep.Adaptive -> step.copy(
            goal = resolve(step.goal),
            candidates = step.candidates.map { it.copy(arguments = it.arguments.mapValues { e -> resolve(e.value) }) })
        is WorkflowStep.Script -> step.copy(source = resolve(step.source))
    }
    fun resolveTrigger(trigger: WorkflowTrigger): WorkflowTrigger = when (trigger) {
        is WorkflowTrigger.Deadline -> trigger.copy(title = resolve(trigger.title))
        else -> trigger
    }
    val resolved = definition.copy(
        description = resolve(definition.description),
        triggers = definition.triggers.map(::resolveTrigger),
        steps = definition.steps.map(::resolveStep)
    )
    require(missing.isEmpty()) { "Setup values still missing: ${missing.sorted().joinToString(", ")}" }
    return resolved
}

// ---------------------------------------------------------------------------
// Export with redaction
// ---------------------------------------------------------------------------

data class RedactionRecord(val field: String, val binding: String)
data class ExportPreview(val shared: List<String>, val redacted: List<RedactionRecord>)
data class WorkflowExport(val manifestJson: String, val preview: ExportPreview)

/**
 * Export [definition] as a shareable manifest JSON.
 *
 * Redaction (deterministic; see [redactExportValue]):
 * - argument keys hinting at secrets (token, secret, password, api_key,
 *   auth, credential, …) → `{{setup:<key>}}`
 * - identifier keys (email, phone, account, username, …) → placeholder
 * - email-looking or URL-looking values → placeholder (private endpoints)
 * - reminder messages, notification titles/bodies and screen typing text
 *   → placeholder (personal content, regardless of value shape)
 * - workflow descriptions, adaptive goals and deadline-reminder titles → placeholder
 * - branch condition literals/regexes (personal text compared against TEXT
 *   outputs) → placeholder; condition numbers stay as-is
 * - location triggers: coordinates never survive — the trigger is removed
 *   (the workflow keeps working; the importer adds their own trigger)
 * - location waits: cannot be exported — coordinates must not survive and
 *   a wait has no honest degraded form, so export refuses with guidance
 * - string literals inside on-phone scripts that look like URLs, emails,
 *   or secrets (secret-named variables, token-like strings) → placeholder
 *
 * The same argument key with the same value reuses one binding; the same
 * key with a different value gets a distinct binding (`api_key_2`, …).
 * Everything redacted becomes a [SetupBinding] the importer must fill in.
 * The preview exposes every retained value (post-redaction), not just
 * categories and counts, so the exporter reviews exactly what is shared.
 */
fun exportWorkflow(
    definition: WorkflowDefinition,
    author: String,
    source: String = "export",
    nowMs: Long = System.currentTimeMillis()
): WorkflowExport {
    val redactions = mutableListOf<RedactionRecord>()
    val bindings = linkedMapOf<String, SetupBinding>()
    val bindingValues = mutableMapOf<String, String>()

    /**
     * Register a setup binding for a redacted [value]. Same key + same
     * value reuses the binding; same key + different value gets a distinct
     * one, so the importer can fill each correctly.
     */
    fun bind(baseName: String, description: String, sensitive: Boolean, value: String): String {
        var name = baseName
        var n = 2
        while (true) {
            val seen = bindingValues[name]
            if (seen == null) {
                bindingValues[name] = value
                bindings[name] = SetupBinding(name, description, sensitive)
                return "{{setup:$name}}"
            }
            if (seen == value) return "{{setup:$name}}"
            name = "${baseName}_$n"
            n++
        }
    }

    /**
     * Branch condition values are personal content, whatever their shape:
     * an Equals/NotEquals literal or Matches regex is user-authored text
     * compared against a TEXT output (names, phrases, patterns), so it is
     * redacted into a resolvable setup binding like any other personal
     * value — the same key + same value reuses one binding, distinct values
     * get distinct bindings. Numbers (GreaterThan/LessThan) are not strings
     * and stay as-is.
     */
    fun redactCondition(condition: WorkflowCondition, path: String): WorkflowCondition {
        fun redactValue(value: String, field: String, baseName: String): String {
            if (value.isBlank()) return value
            val placeholder = bind(
                baseName,
                "Value compared in a branch condition — fill in after import.",
                sensitive = false,
                value = value
            )
            redactions += RedactionRecord(field, placeholder)
            return placeholder
        }
        return when (condition) {
            is WorkflowCondition.Equals -> condition.copy(
                literal = redactValue(condition.literal, "$path.condition.literal", "condition_literal"))
            is WorkflowCondition.NotEquals -> condition.copy(
                literal = redactValue(condition.literal, "$path.condition.literal", "condition_literal"))
            is WorkflowCondition.Matches -> {
                val placeholder = redactValue(condition.regex, "$path.condition.regex", "condition_regex")
                // A bare {{setup:name}} is not a valid regex, so the
                // redacted value is stored quoted ([REDACTED_REGEX_PREFIX]):
                // still a valid regex for import validation, still
                // resolvable to the exact original on setup. A blank regex
                // is left alone (redactValue returns it unchanged).
                condition.copy(regex = if (placeholder == condition.regex) placeholder
                    else {
                        // bind() returns the FULL {{setup:name}} placeholder;
                        // the prefix/suffix wrapper already adds the setup
                        // braces, so pass only the bare binding name —
                        // wrapping the full placeholder nests the braces
                        // (\Q{{setup:{{setup:name}}}}\E) and the setup
                        // parser rejects it on restoration.
                        val bindingName = placeholder
                            .removePrefix("{{setup:")
                            .removeSuffix("}}")
                        "$REDACTED_REGEX_PREFIX$bindingName$REDACTED_REGEX_SUFFIX"
                    })
            }
            else -> condition
        }
    }

    fun redactStep(step: WorkflowStep, path: String): WorkflowStep = when (step) {
        is WorkflowStep.Tool -> {
            val newArgs = step.request.arguments.mapValues { (k, v) ->
                val r = redactToolArgument(step.request.name, k, v)
                if (r != null) {
                    val field = "$path.arguments[$k]"
                    val placeholder = bind(
                        r.bindingName,
                        "Value for “$k” used by the “${step.request.name}” step — fill in after import.",
                        r.sensitive,
                        v
                    )
                    redactions += RedactionRecord(field, placeholder)
                    placeholder
                } else v
            }
            step.copy(request = step.request.copy(arguments = newArgs))
        }
        is WorkflowStep.Branch -> step.copy(
            condition = redactCondition(step.condition, path),
            thenSteps = step.thenSteps.mapIndexed { i, s -> redactStep(s, "$path.then[$i]") },
            elseSteps = step.elseSteps.mapIndexed { i, s -> redactStep(s, "$path.else[$i]") }
        )
        is WorkflowStep.Wait -> {
            val wait = step.wait
            if (wait is WorkflowWait.Event && wait.kind == WorkflowEventKind.LOCATION) {
                throw IllegalArgumentException(
                    "Cannot export: $path waits for a saved location, and location " +
                        "coordinates must not leave the device. A location wait has no " +
                        "honest degraded form — remove it (or replace it) before exporting."
                )
            }
            step
        }
        is WorkflowStep.Adaptive -> step.copy(
            goal = bind(
                "adaptive_goal",
                "Goal of an adaptive step — fill in after import.",
                sensitive = false,
                value = step.goal
            ).also { placeholder -> redactions += RedactionRecord("$path.goal", placeholder) },
            candidates = step.candidates.mapIndexed { i, c ->
                c.copy(arguments = c.arguments.mapValues { (k, v) ->
                    val r = redactToolArgument(c.name, k, v)
                    if (r != null) {
                        val field = "$path.candidates[$i].arguments[$k]"
                        val placeholder = bind(
                            r.bindingName,
                            "Value for “$k” used by an adaptive candidate — fill in after import.",
                            r.sensitive,
                            v
                        )
                        redactions += RedactionRecord(field, placeholder)
                        placeholder
                    } else v
                })
            }
        )
        is WorkflowStep.Script -> {
            val (newSource, scriptRecords) = redactScriptLiterals(
                step.source, "$path.script literal"
            ) { name, desc, sensitive, value -> bind(name, desc, sensitive, value) }
            redactions += scriptRecords
            step.copy(source = newSource)
        }
    }

    fun redactTrigger(trigger: WorkflowTrigger, path: String): WorkflowTrigger = when (trigger) {
        is WorkflowTrigger.OnLocation -> {
            // Coordinates never survive export. The trigger is removed (the
            // workflow still runs); the importer adds their own location.
            val placeholder = bind(
                "location_trigger",
                "This workflow fired when entering a saved location; the coordinates " +
                    "were removed for privacy. Add your own location trigger after import.",
                sensitive = false,
                value = "${trigger.latitude},${trigger.longitude},${trigger.radiusMeters}"
            )
            redactions += RedactionRecord("$path (on_location)", placeholder)
            WorkflowTrigger.Manual
        }
        is WorkflowTrigger.Deadline -> {
            if (trigger.title.isBlank()) trigger
            else {
                val placeholder = bind(
                    "deadline_title",
                    "Title of an automatic deadline reminder — fill in after import.",
                    sensitive = false,
                    value = trigger.title
                )
                redactions += RedactionRecord("$path.title", placeholder)
                trigger.copy(title = placeholder)
            }
        }
        else -> trigger
    }

    val redactedSteps = definition.steps.mapIndexed { i, s -> redactStep(s, "steps[$i]") }
    val redactedTriggers = definition.triggers.mapIndexed { i, t -> redactTrigger(t, "triggers[$i]") }
    // The description is personal content: it never survives export.
    val descriptionPlaceholder = if (definition.description.isBlank()) definition.description
    else {
        val placeholder = bind(
            "description",
            "The workflow's description — fill in after import.",
            sensitive = false,
            value = definition.description
        )
        redactions += RedactionRecord("description", placeholder)
        placeholder
    }
    val redactedDef = definition.copy(
        steps = redactedSteps,
        triggers = redactedTriggers,
        description = descriptionPlaceholder,
        enabled = false
    )

    val toolNames = collectManifestToolNames(redactedSteps).distinct()
    val scopes = toolNames.flatMap { ToolSourcePolicy.requiredScopes(it) }.distinct().sorted()
    val scriptSteps = collectScriptSteps(redactedSteps)
    val manifest = WorkflowManifest(
        workflow = redactedDef,
        requiredTools = toolNames.map { ToolContract(it, MobileToolCatalog.VERSION.toLong()) },
        permittedScopes = scopes,
        scriptRuntime = if (scriptSteps.isEmpty()) null else ScriptRuntimeRequirements(
            engine = SCRIPT_ENGINE_NAME,
            maxTimeMs = DEFAULT_SCRIPT_MAX_TIME_MS,
            maxMemoryKb = DEFAULT_SCRIPT_MAX_MEMORY_KB,
            maxOutputChars = DEFAULT_SCRIPT_MAX_OUTPUT_CHARS,
            requiredHostFunctions = scriptSteps.flatMap { it.requiredHostFunctions }.distinct().sorted()
        ),
        setupBindings = bindings.values.toList(),
        provenance = ManifestProvenance(author, source, nowMs),
        documentation = ""
    )
    // The preview exposes every retained value post-redaction — not just
    // categories and counts — so the exporter reviews exactly what is shared.
    val shared = buildList {
        add("workflow “${definition.name}” (${redactedSteps.size} top-level step(s))")
        if (definition.description.isBlank()) add("description: (none)")
        else add("description: [redacted — see setup bindings]")
        add("triggers:")
        redactedTriggers.forEach { add("  - ${describeExportTrigger(it)}") }
        add("steps:")
        redactedSteps.forEachIndexed { i, s -> addAll(describeExportStep(s, "steps[$i]", "  - ")) }
        add("tools: ${toolNames.joinToString(", ")}")
        if (scopes.isNotEmpty()) add("scopes: ${scopes.joinToString(", ")}")
        if (scriptSteps.isNotEmpty()) add("${scriptSteps.size} on-phone script(s)")
        add("provenance: author “$author”, source “$source”")
        if (bindings.isNotEmpty())
            add("setup bindings needed: ${bindings.keys.joinToString(", ")}")
    }
    return WorkflowExport(manifest.toJson(), ExportPreview(shared, redactions.toList()))
}

/** Tool arguments whose values are always personal content, whatever their shape. */
private val PERSONAL_CONTENT_ARGS = mapOf(
    "create_reminder" to setOf("message"),
    "post_notification" to setOf("title", "text"),
    "screen_type" to setOf("text")
)

private fun describeExportTrigger(trigger: WorkflowTrigger): String = when (trigger) {
    is WorkflowTrigger.Manual -> "manual (when you ask in chat)"
    is WorkflowTrigger.Reminder -> "reminder at ${trigger.atMs}"
    is WorkflowTrigger.Daily -> "daily at %02d:%02d".format(trigger.hour, trigger.minute)
    is WorkflowTrigger.Window -> "once in window ${trigger.earliestMs}..${trigger.latestMs}"
    is WorkflowTrigger.OnNotification -> "when ${trigger.appKey} posts a notification"
    is WorkflowTrigger.OnLocation -> "when arriving near the saved location"
    is WorkflowTrigger.Deadline -> "automatic reminder: ${trigger.title}"
}

private fun describeExportStep(step: WorkflowStep, path: String, indent: String): List<String> =
    when (step) {
        is WorkflowStep.Tool -> {
            val args = step.request.arguments.entries.joinToString(", ") { (k, v) -> "$k=\"$v\"" }
            listOf("$indent$path: tool ${step.request.name}($args)")
        }
        is WorkflowStep.Branch -> listOf("$indent$path: branch (${describeExportCondition(step.condition)})") +
            step.thenSteps.flatMapIndexed { i, s -> describeExportStep(s, "$path.then[$i]", "$indent  ") } +
            step.elseSteps.flatMapIndexed { i, s -> describeExportStep(s, "$path.else[$i]", "$indent  ") }
        is WorkflowStep.Wait -> listOf("$indent$path: wait ${describeExportWait(step.wait)}")
        is WorkflowStep.Adaptive -> listOf("$indent$path: adaptive “${step.goal}”") +
            step.candidates.flatMapIndexed { i, c ->
                val args = c.arguments.entries.joinToString(", ") { (k, v) -> "$k=\"$v\"" }
                listOf("$indent  $path.candidates[$i]: ${c.name}($args)")
            }
        is WorkflowStep.Script -> listOf("$indent$path: script") +
            step.source.lines().map { "$indent    $it" }
    }

/**
 * Renders a branch condition exactly as it will be shared: post-redaction
 * values (placeholders where personal strings were), so the preview covers
 * the complete condition content instead of just the word "branch".
 */
private fun describeExportCondition(condition: WorkflowCondition): String {
    fun ref(binding: WorkflowBinding) = "“${binding.outputName}” from step ${binding.stepId.take(8)}"
    return when (condition) {
        is WorkflowCondition.Equals -> "if ${ref(condition.binding)} is “${condition.literal}”"
        is WorkflowCondition.NotEquals -> "if ${ref(condition.binding)} is not “${condition.literal}”"
        is WorkflowCondition.GreaterThan -> "if ${ref(condition.binding)} is more than ${condition.number}"
        is WorkflowCondition.LessThan -> "if ${ref(condition.binding)} is less than ${condition.number}"
        is WorkflowCondition.Matches -> "if ${ref(condition.binding)} matches “${condition.regex}”"
    }
}

private fun describeExportWait(wait: WorkflowWait): String = when (wait) {
    is WorkflowWait.Timer -> "timer ${wait.durationMs}ms"
    is WorkflowWait.UntilTime -> "until ${wait.epochMs}"
    is WorkflowWait.Event -> when (wait.kind) {
        WorkflowEventKind.NOTIFICATION -> "notification from ${wait.appKey}"
        WorkflowEventKind.LOCATION -> "arriving near the saved location"
    }
}

// ---------------------------------------------------------------------------
// Redaction rules
// ---------------------------------------------------------------------------

internal data class ValueRedaction(val bindingName: String, val sensitive: Boolean)

private val SECRET_KEY_HINTS = listOf(
    "token", "secret", "password", "passwd", "api_key", "apikey",
    "auth", "credential", "private_key", "access_key", "bearer", "session_key"
)
private val IDENTIFIER_KEYS = setOf(
    "email", "phone", "phone_number", "account", "account_id",
    "user_id", "username", "user", "device_id"
)
private val EMAIL_LIKE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** Variable-name hints that a string literal holds a secret (API key, token, password). */
private val SECRET_VAR_HINTS = setOf("password", "passwd", "secret", "token", "api_key", "apikey", "auth")

private fun bindingNameFor(key: String): String {
    val clean = key.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
    return clean.ifEmpty { "value" }.take(48)
}

/** Returns non-null when [value] must be redacted. Visible for unit tests of the rule table. */
internal fun redactExportValue(argKey: String, value: String): ValueRedaction? {
    if (value.isBlank()) return null
    val key = argKey.lowercase()
    if (SECRET_KEY_HINTS.any { it in key }) return ValueRedaction(bindingNameFor(argKey), sensitive = true)
    if (key in IDENTIFIER_KEYS) return ValueRedaction(bindingNameFor(argKey), sensitive = false)
    if (value.startsWith("http://") || value.startsWith("https://"))
        return ValueRedaction(bindingNameFor(argKey), sensitive = '@' in value)
    if (EMAIL_LIKE.matches(value)) return ValueRedaction(bindingNameFor(argKey), sensitive = false)
    return null
}

/**
 * Tool-aware redaction for tool arguments, used for both [WorkflowStep.Tool]
 * requests and [WorkflowStep.Adaptive] candidates.
 *
 * Argument keys that always carry personal content for the tool
 * ([PERSONAL_CONTENT_ARGS]) are redacted whatever their shape; every other
 * key falls back to [redactExportValue]. Returns non-null when [value] must
 * be redacted. Binding deduplication is preserved by the caller through
 * `bind`, which reuses one binding for the same key + same value.
 */
internal fun redactToolArgument(toolName: String, argKey: String, value: String): ValueRedaction? {
    if (value.isBlank()) return null
    if (argKey in (PERSONAL_CONTENT_ARGS[toolName] ?: emptySet()))
        return ValueRedaction(bindingNameFor(argKey), sensitive = false)
    return redactExportValue(argKey, value)
}

/**
 * Redact sensitive string literals inside a script source, in one pass:
 * URL/email-looking literals, literals assigned to secret-named variables,
 * and token-looking literals. [bind] registers each binding (getting the
 * literal's value for distinct-value dedup) and returns its
 * `{{setup:name}}` placeholder. Returns the rewritten source and the
 * redaction records.
 */
private fun redactScriptLiterals(
    source: String,
    fieldPrefix: String,
    bind: (name: String, description: String, sensitive: Boolean, value: String) -> String
): Pair<String, List<RedactionRecord>> {
    val out = StringBuilder()
    val records = mutableListOf<RedactionRecord>()
    var i = 0
    var n = 0
    while (i < source.length) {
        if (source[i] == '"') {
            val end = scanStringLiteral(source, i)
            val literal = unescapeLiteral(source.substring(i + 1, end - 1))
            val varName = assignmentTargetBefore(source, i)
            val secretByName = varName != null && SECRET_VAR_HINTS.any { it in varName.lowercase() }
            val tokenLike = looksLikeSecretLiteral(literal)
            val needs = literal.isNotEmpty() && (
                EMAIL_LIKE.matches(literal) ||
                    literal.startsWith("http://") || literal.startsWith("https://") ||
                    secretByName || tokenLike
                )
            if (needs) {
                n++
                val placeholder = bind(
                    "script_literal_$n",
                    "Redacted text inside an on-phone script — fill in after import.",
                    secretByName || tokenLike || '@' in literal,
                    literal
                )
                records += RedactionRecord("$fieldPrefix #$n", placeholder)
                out.append("\"$placeholder\"")
            } else out.append(source, i, end)
            i = end
        } else { out.append(source[i]); i++ }
    }
    return out.toString() to records
}

/**
 * Heuristic for a secret-looking string literal: long, no spaces, mixed
 * character classes — the shape of an API key or token. Short words and
 * sentences are never flagged.
 */
private fun looksLikeSecretLiteral(literal: String): Boolean {
    if (literal.length < 12 || literal.contains(' ')) return false
    val classes = listOf(
        literal.any { it.isLowerCase() },
        literal.any { it.isUpperCase() },
        literal.any { it.isDigit() },
        literal.any { !it.isLetterOrDigit() }
    ).count { it }
    return classes >= 3 || (literal.length >= 24 && classes >= 2)
}

/** The variable name a string literal is assigned to (`let name = "..."`), if any. */
private fun assignmentTargetBefore(source: String, literalStart: Int): String? {
    val before = source.substring(0, literalStart)
    return Regex("""(?:let\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$""")
        .find(before)?.groupValues?.get(1)
}

private fun scanStringLiteral(source: String, open: Int): Int {
    var i = open + 1
    while (i < source.length) {
        when (source[i]) {
            '\\' -> i += 2
            '"' -> return i + 1
            else -> i++
        }
    }
    return source.length
}

private fun unescapeLiteral(s: String): String = buildString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n' -> append('\n')
                't' -> append('\t')
                'r' -> append('\r')
                '"' -> append('"')
                '\\' -> append('\\')
                else -> append(s[i + 1])
            }
            i += 2
        } else { append(c); i++ }
    }
}.toString()

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

internal fun collectManifestToolNames(steps: List<WorkflowStep>): List<String> {
    val names = mutableListOf<String>()
    fun visit(step: WorkflowStep) {
        when (step) {
            is WorkflowStep.Tool -> names += step.request.name
            is WorkflowStep.Branch -> { step.thenSteps.forEach(::visit); step.elseSteps.forEach(::visit) }
            is WorkflowStep.Wait -> Unit
            is WorkflowStep.Adaptive -> names += step.candidates.map { it.name }
            is WorkflowStep.Script -> Unit
        }
    }
    steps.forEach(::visit)
    return names
}

private fun collectScriptSteps(steps: List<WorkflowStep>): List<WorkflowStep.Script> {
    val out = mutableListOf<WorkflowStep.Script>()
    fun visit(step: WorkflowStep) {
        when (step) {
            is WorkflowStep.Script -> out += step
            is WorkflowStep.Branch -> { step.thenSteps.forEach(::visit); step.elseSteps.forEach(::visit) }
            else -> Unit
        }
    }
    steps.forEach(::visit)
    return out
}
