package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * M5 community workflows and isolated scripts (D41–D44, T19–T20).
 *
 * Covers the versioned manifest format (round-trip, forward-compatible
 * unknown fields, version/kind rejection), export redaction (rule table and
 * end-to-end, no secrets survive), import review/admission (missing
 * dependencies save disabled with a reason; declined imports rejected),
 * setup bindings, the update policy (doc-only auto-apply vs behavior-change
 * review, version pinning), and the isolated script runtime (denials of
 * file/network/tool access, resource-exhaustion termination, cancellation,
 * allowlist behavior) plus engine integration.
 *
 * Deterministic; no network, no Android.
 */
class M5WorkflowsTest {

    private var nowMs = 1_700_000_000_000L
    private val now: () -> Long = { nowMs }
    private fun uid() = UUID.randomUUID().toString()

    private fun batteryStep(id: String = uid()) =
        WorkflowStep.Tool(id, ActionRequest("read_battery"))

    private fun volumeStep(level: String, id: String = uid()) =
        WorkflowStep.Tool(id, ActionRequest("set_volume", mapOf("level" to level)))

    private fun scriptStep(source: String = "log(\"hi\");", id: String = uid()) =
        WorkflowStep.Script(id, source, listOf("log"))

    private fun definition(
        name: String = "Test routine",
        steps: List<WorkflowStep> = listOf(batteryStep(), volumeStep("20")),
        triggers: List<WorkflowTrigger> = listOf(WorkflowTrigger.Manual)
    ) = WorkflowDefinition(uid(), name, "A test routine.", steps, triggers,
        WorkflowOrigin.CONVERSATION, createdAtMs = nowMs, updatedAtMs = nowMs)

    private fun manifestOf(def: WorkflowDefinition) = WorkflowManifest(
        workflow = def,
        requiredTools = listOf(ToolContract("read_battery", 1), ToolContract("set_volume", 1)),
        permittedScopes = listOf("battery.read", "audio.modify"),
        scriptRuntime = ScriptRuntimeRequirements(
            engine = SCRIPT_ENGINE_NAME, maxTimeMs = 10_000L, maxMemoryKb = 8192L,
            maxOutputChars = 8192, requiredHostFunctions = listOf("log")),
        provenance = ManifestProvenance("tester", "test", nowMs),
        documentation = "Test docs."
    )

    private fun fullCaps() = DeviceCapabilities(
        availableTools = mapOf("read_battery" to 1L, "set_volume" to 1L),
        grantedScopes = setOf("battery.read", "audio.modify"),
        scriptRuntime = ScriptRuntimeCapabilities(
            engine = SCRIPT_ENGINE_NAME, maxTimeMs = 10_000L, maxMemoryKb = 8192L,
            maxOutputChars = 8192, hostFunctions = setOf("log"))
    )

    private fun richDefinition(): WorkflowDefinition {
        val batteryId = uid()
        val volumeId = uid()
        return definition(steps = listOf(
            batteryStep(batteryId),
            WorkflowStep.Branch(uid(),
                WorkflowCondition.GreaterThan(WorkflowBinding(batteryId, "battery_percent"), 20.0),
                thenSteps = listOf(volumeStep("20", volumeId)),
                elseSteps = listOf(WorkflowStep.Wait(uid(), WorkflowWait.Timer(60_000L)))),
            WorkflowStep.Adaptive(uid(), "check power",
                listOf(ActionRequest("read_battery")), EffortBudget(3, 60_000L, 2)),
            scriptStep("log(\"done\");")
        ))
    }

    // -- Manifest format --------

    @Test fun manifestRoundTripsEveryStepKind() {
        val def = richDefinition()
        val manifest = manifestOf(def)
        val json = manifest.toJson()
        val parsed = parseWorkflowManifest(json)
        assertEquals(manifest, parsed)
        // Canonical: re-serializing the parse gives identical bytes.
        assertEquals(json, parsed.toJson())
    }

    @Test fun manifestIgnoresUnknownFields() {
        val manifest = manifestOf(definition())
        val json = manifest.toJson()
            .replace("\"manifestVersion\":1", "\"manifestVersion\":1,\"zzz_future\":{\"a\":[1,2]}")
            .replace("\"kind\":\"tool\"", "\"kind\":\"tool\",\"zzz\":true")
        assertEquals(manifest, parseWorkflowManifest(json))
    }

    @Test fun manifestRejectsNewerVersion() {
        val json = manifestOf(definition()).toJson()
            .replace("\"manifestVersion\":1", "\"manifestVersion\":99")
        try {
            parseWorkflowManifest(json)
            fail("expected rejection of manifestVersion 99")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("newer app version"))
        }
    }

    @Test fun manifestRejectsUnknownStepKind() {
        val json = manifestOf(definition()).toJson()
            .replace("\"kind\":\"tool\"", "\"kind\":\"teleport\"")
        try {
            parseWorkflowManifest(json)
            fail("expected rejection of unknown step kind")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown step kind"))
        }
    }

    @Test fun manifestRejectsInvalidWorkflow() {
        val json = "{\"manifestVersion\":1,\"workflow\":{\"id\":\"" + uid() + "\",\"name\":\"x\"," +
            "\"description\":\"\",\"version\":1,\"maxStepsPerRun\":16,\"origin\":\"conversation\"," +
            "\"createdAtMs\":1,\"updatedAtMs\":1,\"steps\":[],\"triggers\":[{\"kind\":\"manual\"}]}," +
            "\"requiredTools\":[],\"permittedScopes\":[],\"scriptRuntime\":null,\"setupBindings\":[]," +
            "\"provenance\":{\"author\":\"a\",\"source\":\"s\",\"createdAtMs\":1},\"documentation\":\"\"}"
        try {
            parseWorkflowManifest(json)
            fail("expected validation failure for empty steps")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1-16"))
        }
    }

    @Test fun manifestRejectsMalformedJson() {
        try {
            parseWorkflowManifest("""{"manifestVersion":1,"workflow":}""")
            fail("expected malformed JSON rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("malformed"))
        }
    }

    // -- Redaction --------

    @Test fun redactionRuleTable() {
        assertNotNull(redactExportValue("api_key", "xyz"))
        assertEquals(true, redactExportValue("api_key", "xyz")!!.sensitive)
        assertNotNull(redactExportValue("authToken", "abc"))
        assertNotNull(redactExportValue("password", "hunter2"))
        assertNotNull(redactExportValue("email", "a@b.com"))
        assertEquals(false, redactExportValue("email", "a@b.com")!!.sensitive)
        assertNotNull(redactExportValue("url", "https://user:pass@internal.example/x"))
        assertEquals(true, redactExportValue("url", "https://user:pass@internal.example/x")!!.sensitive)
        assertNotNull(redactExportValue("url", "https://public.example/x"))
        assertNull(redactExportValue("level", "20"))
        assertNull(redactExportValue("text", "hello world"))
        assertNull(redactExportValue("title", ""))
        assertNull(redactExportValue("app", "Settings"))
    }

    @Test fun exportRedactsSecretsAndListsBindings() {
        val secretEmail = "user@example.com"
        val def = definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("open_app", mapOf("app" to secretEmail))),
            volumeStep("20")
        ))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        // No secret survives in the shared JSON — neither the email nor the
        // workflow description.
        assertFalse(export.manifestJson.contains("user@example.com"))
        assertFalse(export.manifestJson.contains("A test routine."))
        assertTrue(export.manifestJson.contains("{{setup:app}}"))
        assertTrue(export.manifestJson.contains("{{setup:description}}"))
        // The bindings are listed for the importer to fill in.
        val binding = export.preview.redacted.single { it.binding == "{{setup:app}}" }
        assertTrue(binding.field.contains("arguments[app]"))
        assertTrue(export.preview.redacted.any { it.binding == "{{setup:description}}" })
        // Preview shows shared vs redacted.
        assertTrue(export.preview.shared.any { it.contains("open_app") })
        assertEquals(2, export.preview.redacted.size)
        // The exported manifest parses and still validates.
        val parsed = parseWorkflowManifest(export.manifestJson)
        assertEquals(2, parsed.setupBindings.size)
        assertEquals("app", parsed.setupBindings.single { it.name == "app" }.name)
        assertFalse(parsed.setupBindings.single { it.name == "app" }.sensitive) // email: redacted, but not a secret
        // Bindings round-trip through the resolver.
        val resolved = resolveSetupBindings(parsed.workflow,
            mapOf("app" to secretEmail, "description" to "A test routine."))
        assertEquals("A test routine.", resolved.description)
        assertEquals(secretEmail, (resolved.steps[0] as WorkflowStep.Tool).request.arguments["app"])
    }

    @Test fun exportLeavesOrdinaryValuesAlone() {
        val def = definition(steps = listOf(volumeStep("20")))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        // Only the personal description is redacted; ordinary arguments survive.
        assertEquals(listOf("{{setup:description}}"), export.preview.redacted.map { it.binding })
        assertTrue(export.manifestJson.contains("\"level\":\"20\""))
        assertTrue(export.preview.shared.any { it.contains("set_volume") && it.contains("level=\"20\"") })
        val parsed = parseWorkflowManifest(export.manifestJson)
        assertEquals(listOf("description"), parsed.setupBindings.map { it.name })
    }

    @Test fun exportRedactsSecretsInsideScriptSource() {
        val def = definition(steps = listOf(
            WorkflowStep.Script(uid(), "let u = \"https://hooks.example.com/abc\";\nlog(\"hi\");", emptyList())
        ))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        assertFalse(export.manifestJson.contains("hooks.example.com"))
        assertTrue(export.manifestJson.contains("{{setup:script_literal_1}}"))
        assertTrue(export.preview.redacted.any { it.field.contains("script literal") })
    }

    @Test fun exportRedactsReminderMessageText() {
        val message = "Call mom about Sunday dinner"
        val def = definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("create_reminder",
                mapOf("message" to message, "at_ms" to "1791230400000")))
        ))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        assertFalse("reminder text must not survive, got: ${export.manifestJson}",
            export.manifestJson.contains(message))
        assertTrue(export.manifestJson.contains("{{setup:message}}"))
        // The trigger time is not personal content: it survives.
        assertTrue(export.manifestJson.contains("1791230400000"))
        assertTrue(export.preview.redacted.any { it.binding == "{{setup:message}}" })
    }

    @Test fun exportRedactsNotificationTitleAndBody() {
        val title = "Dinner plans"
        val body = "Pick up milk on the way home"
        val def = definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("post_notification",
                mapOf("title" to title, "text" to body)))
        ))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        assertFalse(export.manifestJson.contains(title))
        assertFalse(export.manifestJson.contains(body))
        assertTrue(export.manifestJson.contains("{{setup:title}}"))
        assertTrue(export.manifestJson.contains("{{setup:text}}"))
    }

    @Test fun exportRemovesLocationTriggerCoordinates() {
        val def = definition(
            steps = listOf(batteryStep()),
            triggers = listOf(
                WorkflowTrigger.Manual,
                WorkflowTrigger.OnLocation(40.7128, -74.0060, 100.0)
            )
        )
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        assertFalse(export.manifestJson.contains("40.7128"))
        assertFalse(export.manifestJson.contains("-74.006"))
        assertTrue(export.preview.redacted.any { it.field.contains("on_location") })
        assertTrue(export.preview.redacted.any { it.binding == "{{setup:location_trigger}}" })
        // The exported manifest still parses and validates: the location
        // trigger became a manual one.
        val parsed = parseWorkflowManifest(export.manifestJson)
        assertEquals(
            listOf(WorkflowTrigger.Manual, WorkflowTrigger.Manual),
            parsed.workflow.triggers
        )
    }

    @Test fun exportRefusesLocationWait() {
        val def = definition(steps = listOf(
            WorkflowStep.Wait(uid(), WorkflowWait.Event(WorkflowEventKind.LOCATION,
                latitude = 40.7128, longitude = -74.0060, radiusMeters = 100.0))
        ))
        try {
            exportWorkflow(def, author = "tester", nowMs = nowMs)
            fail("location waits must refuse export")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("location"))
        }
    }

    @Test fun exportRedactsSecretScriptLiterals() {
        val secretVar = "sk-live-abc123"
        val tokenLike = "aB3xY9qW2eRt5uI8oP0lKjH7"
        val source = "let api_key = \"$secretVar\";\n" +
            "let t = \"$tokenLike\";\n" +
            "let note = \"hello\";\n" +
            "log(note);"
        val def = definition(steps = listOf(WorkflowStep.Script(uid(), source, emptyList())))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        assertFalse(export.manifestJson.contains(secretVar))
        assertFalse(export.manifestJson.contains(tokenLike))
        // Ordinary literals survive.
        assertTrue(export.manifestJson.contains("hello"))
        assertTrue(export.preview.redacted.count { it.field.contains("script literal") } == 2)
        // Secret script bindings are marked sensitive.
        val parsed = parseWorkflowManifest(export.manifestJson)
        assertTrue(parsed.setupBindings.filter { it.name.startsWith("script_literal") }
            .all { it.sensitive })
    }

    @Test fun exportDistinctBindingsForDistinctValuesSharingAKey() {
        // Note: open_website is not routine-eligible (Jerry's hardening), so this
        // test uses post_notification, a routine-eligible tool with personal-content
        // args that are always redacted into setup bindings.
        val def = definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("post_notification", mapOf("title" to "Hello A", "text" to "Body A"))),
            WorkflowStep.Tool(uid(), ActionRequest("post_notification", mapOf("title" to "Hello B", "text" to "Body B")))
        ))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        assertFalse(export.manifestJson.contains("Hello A"))
        assertFalse(export.manifestJson.contains("Hello B"))
        assertTrue(export.manifestJson.contains("{{setup:title}}"))
        assertTrue(export.manifestJson.contains("{{setup:title_2}}"))
        val parsed = parseWorkflowManifest(export.manifestJson)
        val names = parsed.setupBindings.map { it.name }
        assertTrue(names.contains("title"))
        assertTrue(names.contains("title_2"))
        // Same key + same value reuses one binding.
        val def2 = definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("post_notification", mapOf("title" to "Hello A", "text" to "Body A"))),
            WorkflowStep.Tool(uid(), ActionRequest("post_notification", mapOf("title" to "Hello A", "text" to "Body A")))
        ))
        val export2 = exportWorkflow(def2, author = "tester", nowMs = nowMs)
        val names2 = parseWorkflowManifest(export2.manifestJson).setupBindings.map { it.name }
        assertEquals(listOf("title", "text", "description").sorted(), names2.sorted())
    }

    @Test fun exportRedactsPersonalContentInAdaptiveCandidates() {
        // Jerry's review (build 1189): the Adaptive candidate path applied
        // only redactExportValue, so plain message/title/text values survived
        // export. Adaptive candidates now share the tool-aware redaction used
        // for Tool steps, while binding deduplication is preserved.
        val def = definition(steps = listOf(
            WorkflowStep.Adaptive(uid(), "stay on top of things",
                listOf(
                    ActionRequest("create_reminder",
                        mapOf("message" to "Call the dentist Tuesday", "at_ms" to "1791230400000")),
                    ActionRequest("post_notification",
                        mapOf("title" to "Medication reminder", "text" to "Take your pills"))
                ),
                EffortBudget(3, 60_000L, 2))
        ))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        assertFalse(export.manifestJson.contains("Call the dentist Tuesday"))
        assertFalse(export.manifestJson.contains("Medication reminder"))
        assertFalse(export.manifestJson.contains("Take your pills"))
        assertTrue(export.manifestJson.contains("{{setup:message}}"))
        assertTrue(export.manifestJson.contains("{{setup:title}}"))
        assertTrue(export.manifestJson.contains("{{setup:text}}"))
        val parsed = parseWorkflowManifest(export.manifestJson)
        val names = parsed.setupBindings.map { it.name }
        assertTrue(names.containsAll(listOf("message", "title", "text", "description")))
        // The preview still exposes the bindings so the exporter reviews
        // exactly what is shared.
        val shared = export.preview.shared.joinToString("\n")
        assertTrue(shared.contains("message=\"{{setup:message}}\""))
        assertTrue(shared.contains("title=\"{{setup:title}}\""))
    }

    @Test fun exportRedactsAdaptiveCandidatesInNestedBranches() {
        // The same tool-aware redaction reaches Adaptive steps nested in
        // branch then/else steps, and binding deduplication is preserved:
        // same key + same value reuses one binding, distinct values stay
        // distinct. The condition binds a step that actually produces the
        // binding (the validator rejects dangling bindings).
        val batteryId = uid()
        val def = definition(steps = listOf(
            batteryStep(batteryId),
            WorkflowStep.Branch(uid(),
                WorkflowCondition.GreaterThan(WorkflowBinding(batteryId, "battery_percent"), 20.0),
                thenSteps = listOf(
                    WorkflowStep.Adaptive(uid(), "morning briefing",
                        listOf(ActionRequest("post_notification",
                            mapOf("title" to "Good morning", "text" to "Your day at a glance"))),
                        EffortBudget(2, 30_000L, 2))
                ),
                elseSteps = listOf(
                    WorkflowStep.Adaptive(uid(), "evening wind-down",
                        listOf(ActionRequest("post_notification",
                            mapOf("title" to "Good morning", "text" to "Time to rest"))),
                        EffortBudget(2, 30_000L, 2))
                ))
        ))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        assertFalse(export.manifestJson.contains("Your day at a glance"))
        assertFalse(export.manifestJson.contains("Time to rest"))
        // "Good morning" is the same title value in both candidates → one binding.
        val names = parseWorkflowManifest(export.manifestJson).setupBindings.map { it.name }
        assertTrue(names.contains("title"))
        assertFalse(names.contains("title_2"))
        // Distinct text values → distinct bindings.
        assertTrue(names.contains("text"))
        assertTrue(names.contains("text_2"))
    }

    @Test fun exportPreviewExposesAllRetainedContent() {
        val scriptSource = "log(\"hi\");\nreturn 1;"
        val def = definition(
            steps = listOf(
                WorkflowStep.Tool(uid(), ActionRequest("set_volume", mapOf("level" to "20"))),
                WorkflowStep.Script(uid(), scriptSource, listOf("log"))
            ),
            triggers = listOf(WorkflowTrigger.Daily(8, 30), WorkflowTrigger.Manual)
        )
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        val shared = export.preview.shared.joinToString("\n")
        // Every retained value is exposed, not just categories and counts.
        assertTrue(shared.contains("set_volume"))
        assertTrue(shared.contains("level=\"20\""))
        assertTrue(shared.contains("daily at 08:30"))
        assertTrue(shared.contains("manual"))
        assertTrue(shared.contains("log(\"hi\");"))
        assertTrue(shared.contains("return 1;"))
        assertTrue(shared.contains("description: [redacted"))
        assertTrue(shared.contains("setup bindings needed:"))
        // The redacted list names each field and its binding.
        assertTrue(export.preview.redacted.any { it.field == "description" })
    }

    @Test fun resolveSetupBindingsFillsDescriptionAndDeadlineTitle() {
        val def = definition(
            steps = listOf(batteryStep()),
            triggers = listOf(WorkflowTrigger.Deadline(1_800_000_000_000L, "Dentist appointment"))
        )
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        assertTrue(export.manifestJson.contains("{{setup:deadline_title}}"))
        val parsed = parseWorkflowManifest(export.manifestJson)
        val resolved = resolveSetupBindings(parsed.workflow, mapOf(
            "description" to "My routine",
            "deadline_title" to "Dentist"
        ))
        assertEquals("My routine", resolved.description)
        assertEquals(
            WorkflowTrigger.Deadline(1_800_000_000_000L, "Dentist"),
            resolved.triggers.single()
        )
    }

    // -- Import --------

    @Test fun importReviewFindsMissingDependencies() {
        val json = manifestOf(richDefinition()).toJson()
        val caps = DeviceCapabilities(emptyMap(), emptySet(), scriptRuntime = null)
        val review = reviewWorkflowManifest(json, caps)
        assertFalse(review.ready)
        assertTrue(review.missingTools.any { it.name == "read_battery" })
        assertTrue(review.missingScopes.contains("battery.read"))
        assertTrue(review.runtimeProblems.any { it.contains("not installed") })
        assertTrue(review.previewText.contains("tester"))
    }

    @Test fun functionFreeScriptStillRequiresRuntime() {
        // Jerry's review (build 1194): a Script step with no host functions
        // and no declared runtime metadata still needs an interpreter — it
        // must not review as ready when no runtime is installed.
        val manifest = manifestOf(definition(steps = listOf(
            WorkflowStep.Script(uid(), "return 1;", emptyList())
        ))).copy(scriptRuntime = null)
        val review = reviewWorkflowManifest(manifest.toJson(),
            DeviceCapabilities(emptyMap(), emptySet(), scriptRuntime = null))
        assertFalse("a function-free script with no runtime must not be ready", review.ready)
        assertTrue("must report the missing runtime, got: ${review.runtimeProblems}",
            review.runtimeProblems.any { it.contains("not installed") })
    }

    @Test fun importAdmitsDisabledWithReasonWhenDepsMissing() {
        val json = manifestOf(richDefinition()).toJson()
        val review = reviewWorkflowManifest(json, DeviceCapabilities(emptyMap(), emptySet()))
        val decision = admitWorkflowManifest(review, userApproved = true)
        assertTrue(decision is ImportDecision.Admitted)
        decision as ImportDecision.Admitted
        assertFalse(decision.definition.enabled)
        assertNotNull(decision.disabledReason)
        assertTrue(decision.disabledReason!!.contains("read_battery"))
    }

    @Test fun importAdmitsReadyWhenAllPresent() {
        val json = manifestOf(richDefinition()).toJson()
        val review = reviewWorkflowManifest(json, fullCaps())
        assertTrue(review.ready)
        val decision = admitWorkflowManifest(review, userApproved = true)
        assertTrue(decision is ImportDecision.Admitted)
        decision as ImportDecision.Admitted
        assertFalse(decision.definition.enabled) // drafts never run until enabled (T12)
        assertNull(decision.disabledReason)
    }

    @Test fun importDeclinedIsRejected() {
        val review = reviewWorkflowManifest(manifestOf(definition()).toJson(), fullCaps())
        val decision = admitWorkflowManifest(review, userApproved = false)
        assertTrue(decision is ImportDecision.Rejected)
    }

    @Test fun importRejectsUnknownToolVersions() {
        val json = manifestOf(richDefinition()).toJson()
        val caps = fullCaps().copy(availableTools = mapOf("read_battery" to 1L, "set_volume" to 1L))
        // Manifest demands v2 of set_volume; device has v1.
        val v2 = manifestOf(richDefinition()).copy(
            requiredTools = listOf(ToolContract("read_battery", 1), ToolContract("set_volume", 2)))
        val review = reviewWorkflowManifest(v2.toJson(), caps)
        assertTrue(review.missingTools.any { it.name == "set_volume" })
        assertFalse(review.ready)
    }

    // -- Setup bindings --------

    @Test fun resolveSetupBindingsFillsPlaceholders() {
        val def = definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("open_app", mapOf("app" to "{{setup:app}}")))
        ))
        val resolved = resolveSetupBindings(def, mapOf("app" to "Settings"))
        val args = (resolved.steps[0] as WorkflowStep.Tool).request.arguments
        assertEquals("Settings", args["app"])
    }

    @Test fun resolveSetupBindingsThrowsWhenMissing() {
        val def = definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("open_app", mapOf("app" to "{{setup:app}}")))
        ))
        try {
            resolveSetupBindings(def, emptyMap())
            fail("expected missing-binding failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("app"))
        }
    }

    // -- Update policy --------

    private fun admittedOf(manifest: WorkflowManifest, enabled: Boolean = false) =
        AdmittedWorkflow(manifest.workflow.copy(enabled = enabled), manifest)

    @Test fun docOnlyUpdateAutoApplies() {
        val old = manifestOf(definition())
        val new = old.copy(
            documentation = "New docs.",
            workflow = old.workflow.copy(description = "New description.", version = 2)
        )
        assertEquals(UpdateClass.DOCUMENTATION_ONLY, classifyWorkflowUpdate(old, new))
        val decision = evaluateWorkflowUpdate(admittedOf(old, enabled = true), new)
        assertTrue(decision is UpdateDecision.AutoApplied)
        decision as UpdateDecision.AutoApplied
        assertEquals("New description.", decision.admitted.definition.description)
        assertTrue(decision.admitted.definition.enabled) // cosmetic update keeps enablement
        assertNull(decision.admitted.pendingUpdate)
    }

    @Test fun scriptChangeNeedsReviewAndPinsRunningVersion() {
        val oldDef = richDefinition()
        val old = manifestOf(oldDef)
        val changedSteps = oldDef.steps.map {
            if (it is WorkflowStep.Script) it.copy(source = "log(\"changed\");") else it
        }
        val new = old.copy(workflow = oldDef.copy(steps = changedSteps, version = 2))
        assertEquals(UpdateClass.BEHAVIOR_CHANGE, classifyWorkflowUpdate(old, new))
        val decision = evaluateWorkflowUpdate(admittedOf(old), new)
        assertTrue(decision is UpdateDecision.NeedsReview)
        decision as UpdateDecision.NeedsReview
        // Running version stays pinned: the admitted definition is untouched.
        assertEquals(oldDef.steps, decision.admitted.definition.steps)
        assertNotNull(decision.admitted.pendingUpdate)
        assertTrue(decision.changes.any { it.contains("script") })
    }

    @Test fun addedStepNeedsReview() {
        val oldDef = definition()
        val old = manifestOf(oldDef)
        val new = old.copy(workflow = oldDef.copy(
            steps = oldDef.steps + volumeStep("30"), version = 2))
        assertEquals(UpdateClass.BEHAVIOR_CHANGE, classifyWorkflowUpdate(old, new))
        val decision = evaluateWorkflowUpdate(admittedOf(old), new)
        assertTrue(decision is UpdateDecision.NeedsReview)
        decision as UpdateDecision.NeedsReview
        assertTrue(decision.changes.any { it.startsWith("step added") })
    }

    @Test fun approvePendingUpdateApplies() {
        val oldDef = richDefinition()
        val old = manifestOf(oldDef)
        val changedSteps = oldDef.steps.map {
            if (it is WorkflowStep.Script) it.copy(source = "log(\"v2\");") else it
        }
        val new = old.copy(workflow = oldDef.copy(steps = changedSteps, version = 2))
        val needsReview = evaluateWorkflowUpdate(admittedOf(old), new) as UpdateDecision.NeedsReview
        val applied = approvePendingWorkflowUpdate(needsReview.admitted)
        assertNull(applied.pendingUpdate)
        assertEquals(changedSteps, applied.definition.steps)
        assertEquals(2, applied.manifest.workflow.version)
    }

    @Test fun rejectPendingUpdateDrops() {
        val oldDef = richDefinition()
        val old = manifestOf(oldDef)
        val new = old.copy(workflow = oldDef.copy(description = "x", version = 2),
            documentation = "also changed")
        // description+docs would be doc-only; force behavior change via scopes.
        val new2 = new.copy(permittedScopes = listOf("battery.read"))
        val needsReview = evaluateWorkflowUpdate(admittedOf(old), new2) as UpdateDecision.NeedsReview
        val kept = rejectPendingWorkflowUpdate(needsReview.admitted)
        assertNull(kept.pendingUpdate)
        assertEquals(oldDef.steps, kept.definition.steps)
    }

    @Test fun staleUpdateIsRejected() {
        val old = manifestOf(definition())
        val same = old.copy(documentation = "docs")
        val decision = evaluateWorkflowUpdate(admittedOf(old), same)
        assertTrue(decision is UpdateDecision.Rejected)
    }

    // -- Script runtime: language --------

    @Test fun scriptLanguageBasics() {
        val r = runScript(
            """
            let x = 10;
            let s = "a";
            if (x > 5) { s = s + "b"; } else { s = s + "c"; }
            let i = 0;
            while (i < 3) { i = i + 1; }
            return s;
            """.trimIndent(),
            ScriptHost.empty()
        )
        assertTrue(r is ScriptResult.Success)
        r as ScriptResult.Success
        assertEquals(ScriptValue.Str("ab"), r.value)
    }

    @Test fun scriptTypeErrorsAreFailuresNotCrashes() {
        assertTrue(runScript("1 + \"a\";", ScriptHost.empty()) is ScriptResult.Failed)
        assertTrue(runScript("1 / 0;", ScriptHost.empty()) is ScriptResult.Failed)
        assertTrue(runScript("if (1) { };", ScriptHost.empty()) is ScriptResult.Failed)
        assertTrue(runScript("nope;", ScriptHost.empty()) is ScriptResult.Failed)
        assertTrue(runScript("let x = 1;", ScriptHost.empty()) is ScriptResult.Success)
    }

    @Test fun scriptParseErrorsAreFailures() {
        assertTrue(runScript("let = 5;", ScriptHost.empty()) is ScriptResult.Failed)
        assertTrue(runScript("if true { };", ScriptHost.empty()) is ScriptResult.Success) // parens optional
    }

    // -- Script runtime: isolation --------

    @Test fun scriptDeniesFileAccess() {
        val r = runScript("read_file(\"/etc/passwd\");", ScriptHost.withLog())
        assertTrue(r is ScriptResult.Denied)
        r as ScriptResult.Denied
        assertTrue(r.reason.contains("read_file"))
    }

    @Test fun scriptDeniesNetworkAccess() {
        val r = runScript("http_get(\"https://example.com\");", ScriptHost.empty())
        assertTrue(r is ScriptResult.Denied)
    }

    @Test fun scriptDeniesToolAccess() {
        val r = runScript("run_tool(\"read_battery\");", ScriptHost.empty())
        assertTrue(r is ScriptResult.Denied)
    }

    @Test fun scriptDeniesEvenPlausibleUnregisteredFunctions() {
        val r = runScript("notify(\"title\", \"text\");", ScriptHost.withLog())
        assertTrue(r is ScriptResult.Denied)
    }

    @Test fun scriptDeniesWithEmptyHost() {
        val r = runScript("log(\"hi\");", ScriptHost.empty())
        assertTrue(r is ScriptResult.Denied)
    }

    @Test fun scriptAllowlistWorks() {
        val r = runScript("log(\"hi\"); return 1 + 2;", ScriptHost.withLog())
        assertTrue(r is ScriptResult.Success)
        r as ScriptResult.Success
        assertEquals(ScriptValue.Num(3.0), r.value)
        assertEquals("hi\n", r.output)
    }

    @Test fun scriptKilledOnInfiniteLoop() {
        val r = runScript(
            "let x = 0; while (true) { x = x + 1; }",
            ScriptHost.empty(),
            ScriptLimits(maxOps = 100L)
        )
        assertTrue(r is ScriptResult.Killed)
        r as ScriptResult.Killed
        assertTrue(r.reason.contains("operation budget"))
    }

    @Test fun scriptKilledOnMemoryBomb() {
        val r = runScript(
            "let s = \"0123456789\"; s = s + s; s = s + s; s = s + s;",
            ScriptHost.empty(),
            ScriptLimits(maxOps = 1_000_000L, maxStringChars = 25L)
        )
        assertTrue(r is ScriptResult.Killed)
        r as ScriptResult.Killed
        assertTrue(r.reason.contains("memory budget"))
    }

    @Test fun scriptKilledOnOutputBomb() {
        var n = 0
        val host = ScriptHost(mapOf(
            "spam" to ScriptHostFunction("spam", 0..0) { _, emit ->
                emit("x".repeat(100))
                n++
                ScriptValue.Null
            }
        ))
        val r = runScript(
            "let i = 0; while (i < 100) { spam(); i = i + 1; }",
            host,
            ScriptLimits(maxOps = 1_000_000L, maxOutputChars = 150)
        )
        assertTrue(r is ScriptResult.Killed)
        assertTrue(n < 100)
    }

    @Test fun scriptCancelledImmediately() {
        val r = runScript("let x = 1;", ScriptHost.empty(), isCancelled = { true })
        assertEquals(ScriptResult.Cancelled, r)
    }

    @Test fun scriptCancelledMidRun() {
        var calls = 0
        val r = runScript(
            "let i = 0; while (i < 1000000) { i = i + 1; }",
            ScriptHost.empty(),
            ScriptLimits(maxOps = 10_000_000L),
            isCancelled = { ++calls > 500 }
        )
        assertEquals(ScriptResult.Cancelled, r)
    }

    // -- Script runtime: engine integration --------

    private fun okDispatch(): (ActionRequest) -> ExecutionResult = { ExecutionResult(true, "ok") }

    @Test fun engineRunsScriptStep() {
        val sId = uid()
        val def = definition(steps = listOf(WorkflowStep.Script(sId, "return 40 + 2;", emptyList())))
        val outcome = WorkflowEngine().run(
            def,
            dispatch = okDispatch(),
            runScript = { step -> step.runWithInterpreter(ScriptHost.empty()) }
        )
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        assertEquals("42", outcome.results[sId]?.get("result"))
        assertTrue(outcome.results.containsKey(sId))
    }

    @Test fun engineScriptStepCanBindResult() {
        val sId = uid()
        val vId = uid()
        val def = definition(steps = listOf(
            WorkflowStep.Script(sId, "return 75;", emptyList()),
            WorkflowStep.Tool(vId, ActionRequest("set_volume", mapOf("level" to "20")),
                bindings = mapOf("level" to WorkflowBinding(sId, "result")))
        ))
        val seen = mutableListOf<ActionRequest>()
        val outcome = WorkflowEngine().run(
            def,
            dispatch = { req -> seen += req; ExecutionResult(true, "ok") },
            runScript = { step -> step.runWithInterpreter(ScriptHost.empty()) }
        )
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        assertEquals("75", seen.single().arguments["level"])
    }

    @Test fun engineFailsScriptStepWithoutRuntime() {
        val def = definition(steps = listOf(scriptStep("return 1;")))
        val outcome = WorkflowEngine().run(def, dispatch = okDispatch())
        assertTrue(outcome is WorkflowRunOutcome.Failed)
        outcome as WorkflowRunOutcome.Failed
        assertTrue(outcome.reason.contains("No script runtime"))
    }

    @Test fun engineScriptDenialFailsTheStep() {
        val def = definition(steps = listOf(scriptStep("read_file(\"/x\");")))
        val outcome = WorkflowEngine().run(
            def,
            dispatch = okDispatch(),
            runScript = { step -> step.runWithInterpreter(ScriptHost.empty()) }
        )
        assertTrue(outcome is WorkflowRunOutcome.Failed)
        outcome as WorkflowRunOutcome.Failed
        assertTrue(outcome.reason.contains("denied"))
    }

    @Test fun scriptStepValidation() {
        try {
            WorkflowStep.Script(uid(), "", emptyList()).let { step ->
                definition(steps = listOf(step))
            }
            fail("expected empty script rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1-8192"))
        }
        try {
            definition(steps = listOf(WorkflowStep.Script(uid(), "return 1;", listOf("bad name!"))))
            fail("expected bad host-function-name rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("host function"))
        }
    }

    // -- Script runtime: host-function least privilege --------

    @Test fun runWithInterpreterDeniesAvailableButUndeclaredHostFunction() {
        // The host provides log(), but the step declares nothing: the call
        // must be denied, not silently granted.
        val step = WorkflowStep.Script(uid(), "log(\"hi\");", emptyList())
        val outcome = step.runWithInterpreter(ScriptHost.withLog())
        assertTrue("undeclared host function must be denied, got $outcome",
            outcome is ScriptExecution.Failed)
        assertTrue((outcome as ScriptExecution.Failed).reason.contains("denied"))
    }

    @Test fun runWithInterpreterGrantsOnlyDeclaredFunctions() {
        val host = ScriptHost(
            mapOf(
                "log" to ScriptHostFunction("log", 1..1) { args, emit ->
                    emit(args[0].display() + "\n"); ScriptValue.Null
                },
                "extra" to ScriptHostFunction("extra", 0..0) { _, _ -> ScriptValue.Num(1.0) }
            )
        )
        val ok = WorkflowStep.Script(uid(), "log(\"hi\");", listOf("log"))
            .runWithInterpreter(host)
        assertTrue("declared function must stay available, got $ok", ok is ScriptExecution.Succeeded)
        val denied = WorkflowStep.Script(uid(), "extra();", listOf("log"))
            .runWithInterpreter(host)
        assertTrue("available-but-undeclared function must be denied, got $denied",
            denied is ScriptExecution.Failed)
        assertTrue((denied as ScriptExecution.Failed).reason.contains("denied"))
    }

    // -- Script runtime: parser hardening --------

    @Test fun scriptDeeplyNestedParensIsTypedFailure() {
        // Thousands of nested parens: a typed parse failure, never a stack
        // overflow. (The 8,192-char workflow limit still permits this.)
        val depth = 3000
        val source = "return " + "(".repeat(depth) + "1" + ")".repeat(depth) + ";"
        val r = runScript(source, ScriptHost.empty())
        assertTrue("deep nesting must fail typed, got $r", r is ScriptResult.Failed)
        assertTrue((r as ScriptResult.Failed).reason.contains("deeply nested"))
    }

    @Test fun scriptDeeplyNestedCallsIsTypedFailure() {
        val depth = 1500
        val source = "return " + "neg(".repeat(depth) + "1" + ")".repeat(depth) + ";"
        val r = runScript(
            source,
            ScriptHost(mapOf("neg" to ScriptHostFunction("neg", 1..1) { args, _ ->
                ScriptValue.Num(-(args[0] as ScriptValue.Num).v)
            }))
        )
        assertTrue("deep call nesting must fail typed, got $r", r is ScriptResult.Failed)
        assertTrue((r as ScriptResult.Failed).reason.contains("deeply nested"))
    }

    @Test fun scriptLongPrefixOperatorsDoNotRecurse() {
        // Thousands of prefix operators are iterative, not recursive.
        val source = "return " + "!".repeat(3000) + "true;"
        val r = runScript(source, ScriptHost.empty())
        assertTrue("long prefix runs must parse, got $r", r is ScriptResult.Success)
        assertEquals(ScriptValue.Bool(true), (r as ScriptResult.Success).value)
    }

    @Test fun scriptPreCancelledSkipsParsing() {
        // A pre-cancelled run reports Cancelled without doing parse work —
        // even for a script that would otherwise fail parsing.
        val r = runScript("this is not valid script (((;", ScriptHost.empty(), isCancelled = { true })
        assertEquals(ScriptResult.Cancelled, r)
    }

    @Test fun scriptMalformedIsTypedFailure() {
        val r = runScript("let x = ;", ScriptHost.empty())
        assertTrue("malformed input must fail typed, got $r", r is ScriptResult.Failed)
        assertTrue((r as ScriptResult.Failed).reason.contains("parse error"))
    }

    // -- Import review: script runtime requirements --------

    @Test fun importReviewDerivesScriptRuntimeFromStepsWhenMetadataOmitted() {
        // A manifest that omits top-level scriptRuntime metadata but
        // contains Script steps still needs the runtime: the review must
        // flag it on a device without one, and derive the host-function
        // needs from the steps.
        val def = definition(steps = listOf(scriptStep()))
        val json = manifestOf(def).copy(scriptRuntime = null).toJson()
        val noRuntime = reviewWorkflowManifest(json,
            DeviceCapabilities(mapOf("read_battery" to 1L, "set_volume" to 1L),
                setOf("battery.read", "audio.modify"), scriptRuntime = null))
        assertFalse(noRuntime.ready)
        assertTrue(noRuntime.runtimeProblems.any { it.contains("not installed") })

        // A device whose host lacks the declared function is flagged too.
        val poorCaps = fullCaps().copy(
            scriptRuntime = fullCaps().scriptRuntime!!.copy(hostFunctions = emptySet()))
        val review = reviewWorkflowManifest(json, poorCaps)
        assertFalse(review.ready)
        assertTrue(review.runtimeProblems.any { it.contains("log") })
    }

    @Test fun importReviewNoScriptRuntimeNeededWithoutScriptSteps() {
        val def = definition(steps = listOf(batteryStep()))
        val json = manifestOf(def).copy(scriptRuntime = null).toJson()
        val review = reviewWorkflowManifest(json, fullCaps())
        assertTrue(review.runtimeProblems.isEmpty())
    }

    @Test fun importReviewUnionsStepHostFunctionsWithUnderdeclaredMetadata() {
        // Jerry's review (build 1189): explicit scriptRuntime metadata used
        // to win outright, so underdeclared metadata could mark a script
        // ready when a required host function was unavailable. The required
        // host functions are now the union of the metadata and the steps'
        // own declarations.
        val def = definition(steps = listOf(scriptStep())) // declares "log"
        val underdeclared = manifestOf(def).copy(
            scriptRuntime = ScriptRuntimeRequirements(
                engine = SCRIPT_ENGINE_NAME, maxTimeMs = 10_000L, maxMemoryKb = 8192L,
                maxOutputChars = 8192, requiredHostFunctions = emptyList()))
        // A device whose host lacks the step-declared function is flagged,
        // even though the metadata declares nothing.
        val noLog = fullCaps().copy(
            scriptRuntime = fullCaps().scriptRuntime!!.copy(hostFunctions = emptySet()))
        val review = reviewWorkflowManifest(underdeclared.toJson(), noLog)
        assertFalse("underdeclared metadata must not hide the step's host-function need",
            review.ready)
        assertTrue(review.runtimeProblems.any { it.contains("log") })
        // A device with the function stays ready: the union adds nothing missing.
        val ok = reviewWorkflowManifest(underdeclared.toJson(), fullCaps())
        assertTrue(ok.runtimeProblems.isEmpty())
        assertTrue(ok.ready)
        // Metadata declaring a superset still enforces its own entries too.
        val strictMeta = manifestOf(def).copy(
            scriptRuntime = ScriptRuntimeRequirements(
                engine = SCRIPT_ENGINE_NAME, maxTimeMs = 10_000L, maxMemoryKb = 8192L,
                maxOutputChars = 8192, requiredHostFunctions = listOf("log", "notify")))
        val missingNotify = reviewWorkflowManifest(strictMeta.toJson(), fullCaps())
        assertFalse(missingNotify.ready)
        assertTrue(missingNotify.runtimeProblems.any { it.contains("notify") })
    }
}
