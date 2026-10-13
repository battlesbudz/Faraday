package com.battlesbudz.jarvis.v2.actions

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Authority consumption and dispatch intent share one durable transaction. */
interface ToolTaskStore {
    fun read(): List<ToolTaskAttempt>
    fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>): List<ToolTaskAttempt>
    // Compatibility for attempt-only fixture stores. Production overrides both journal methods.
    fun readJournal() = ToolTaskJournal(attempts = read())
    fun updateJournal(change: (ToolTaskJournal) -> ToolTaskJournal): ToolTaskJournal {
        var next = ToolTaskJournal()
        update { attempts ->
            next = change(ToolTaskJournal(attempts = attempts))
            if (next.copy(attempts = emptyList()) != ToolTaskJournal()) throw ToolTaskStorageException()
            next.attempts
        }
        return next
    }
}
/**
 * Typed journal failures. The journal is the device's memory of what was
 * authorized: every failure mode is explicit so callers can report
 * honestly and never silently reset or rewrite the bytes on disk.
 */
enum class ToolTaskStorageFailure { UNAVAILABLE, INVALID_CONTENT, UNSUPPORTED_SCHEMA, UNSUPPORTED_CONTENT, WRITE_FAILED }

class ToolTaskStorageException(
    val failure: ToolTaskStorageFailure = ToolTaskStorageFailure.UNAVAILABLE,
    val journalSchemaVersion: Int? = null
) : IllegalStateException("The phone-action journal is unavailable.") {
    /** Deliberately excludes paths, task payloads, parser messages and other private data. */
    fun userMessage(): String = when (failure) {
        ToolTaskStorageFailure.UNSUPPORTED_SCHEMA ->
            "Saved phone tasks use journal format ${journalSchemaVersion ?: "unknown"}; this build supports formats 1–3. Use a compatible build to continue. Your saved data is preserved."
        ToolTaskStorageFailure.UNSUPPORTED_CONTENT ->
            "Some saved phone tasks need features this build doesn't support. Use a compatible build to continue them. Your saved data is preserved."
        ToolTaskStorageFailure.INVALID_CONTENT ->
            "Saved phone-task data couldn't be validated. Phone actions are paused to protect it; the data hasn't been reset."
        ToolTaskStorageFailure.WRITE_FAILED ->
            "The phone-task change couldn't be saved. Please try again; no further action will start from this request."
        ToolTaskStorageFailure.UNAVAILABLE ->
            "Saved phone tasks couldn't be read. Phone actions are paused; the data hasn't been reset."
    }
}

class InMemoryToolTaskStore : ToolTaskStore {
    private var journal = ToolTaskJournal()
    @Synchronized override fun read() = journal.frozen().attempts
    @Synchronized override fun readJournal() = journal.frozen()
    @Synchronized override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>) =
        updateJournal { it.copy(attempts = change(it.attempts)) }.attempts
    @Synchronized override fun updateJournal(change: (ToolTaskJournal) -> ToolTaskJournal): ToolTaskJournal {
        journal = change(journal.frozen()).frozen()
        return journal.frozen()
    }
}

/** App-private journal; schema 1 attempts migrate on the first successful transaction. */
class FileToolTaskStore(
    private val file: File,
    private val commitWriter: (File, String) -> Unit = ::atomicWrite
) : ToolTaskStore {
    override fun read() = readJournal().attempts
    override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>) =
        updateJournal { it.copy(attempts = change(it.attempts)) }.attempts
    override fun readJournal(): ToolTaskJournal = synchronized(lockFor(file)) { readLocked().frozen() }
    override fun updateJournal(change: (ToolTaskJournal) -> ToolTaskJournal): ToolTaskJournal = synchronized(lockFor(file)) {
        val before = readLocked()
        val after = retain(retainWorkflows(change(before.frozen()).frozen()))
        if (after != before) {
            val encoded = try {
                validate(after)
                encode(after).also { require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) }
            } catch (_: Exception) { throw ToolTaskStorageException(ToolTaskStorageFailure.WRITE_FAILED) }
            try { commitWriter(file, encoded) }
            catch (_: Exception) { throw ToolTaskStorageException(ToolTaskStorageFailure.WRITE_FAILED) }
        }
        after.frozen()
    }

    private fun readLocked(): ToolTaskJournal = try {
        val parent = file.parentFile
        if (parent != null && parent.exists()) {
            if (!parent.isDirectory) throw ToolTaskStorageException()
            val files = parent.listFiles() ?: throw ToolTaskStorageException()
            files.filter { it.isFile && it.name.startsWith(".${file.name}.") && it.name.endsWith(".tmp") }
                .forEach { if (!it.delete()) throw ToolTaskStorageException() }
        }
        if (!file.exists()) ToolTaskJournal() else {
            require(file.isFile && file.length() <= MAX_BYTES)
            val root = JSONObject(file.readText(StandardCharsets.UTF_8))
            val version = root.getInt("schemaVersion")
            require(root.get("schemaVersion") is Int)
            if (version !in 1..3) throw ToolTaskStorageException(ToolTaskStorageFailure.UNSUPPORTED_SCHEMA, version)
            if (version >= 2) {
                val authorityFields = setOf("authority", "provider", "toolSchemaVersion", "stepId", "groupId",
                    "grantId", "approvalId", "actionRevision", "reconciled")
                // The published prior-APK upgrade seed uses schema 2 but omits all later
                // ownership fields, a shape older readers accepted. Terminal, unlinked receipts carry
                // no dispatch authority. Never extend this migration to active or partially
                // annotated records, where a missing field could erase an approval requirement.
                val legacyReceipts = version == 2 && root.nullString("activeQuestionId") == null &&
                    listOf("groups", "approvals", "grants", "events").all { root.getJSONArray(it).length() == 0 }
                root.getJSONArray("attempts").objects { a ->
                    val complete = authorityFields.all(a::has)
                    val terminalLegacy = legacyReceipts && authorityFields.none(a::has) &&
                        a.getString("state") in setOf("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN_OUTCOME")
                    require(complete || terminalLegacy)
                }
            }
            if (version == 3) {
                require(root.has("sourceAccess"))
                root.getJSONArray("groups").objects { require(it.has("resumeAfterRestart")) }
            }
            val journal = ToolTaskJournal(
                attempts = root.getJSONArray("attempts").objects { a -> ToolTaskAttempt(
                    id = a.getString("id"), generation = a.strictLong("generation"),
                    state = ToolTaskState.valueOf(a.getString("state")), request = a.getJSONObject("request").request(),
                    createdAtMs = a.strictLong("createdAtMs"), updatedAtMs = a.strictLong("updatedAtMs"),
                    result = a.nullString("result"), resultOutcome = a.nullString("resultOutcome")?.let(ExecutionResult.Outcome::valueOf),
                    groupId = a.nullString("groupId"), stepId = a.optString("stepId", a.getString("id")),
                    provider = a.optString("provider", "native"), schemaVersion = a.optInt("toolSchemaVersion", MobileToolCatalog.VERSION),
                    authority = ToolAuthority.valueOf(a.optString("authority", ToolAuthority.USER_REQUEST.name)),
                    grantId = a.nullString("grantId"), approvalId = a.nullString("approvalId"),
                    actionRevision = if (a.has("actionRevision")) a.strictLong("actionRevision") else 0L,
                    reconciled = a.optBoolean("reconciled")) },
                groups = if (version == 1) emptyList() else root.getJSONArray("groups").objects { g -> ToolTaskGroup(
                    g.getString("id"), g.getString("conversationId"), g.getString("summary"),
                    g.getJSONArray("attemptIds").strings(), g.strictLong("createdAtMs"), g.strictLong("expiresAtMs"), g.getBoolean("cancelled"),
                    if (g.has("resumeAfterRestart")) g.get("resumeAfterRestart").also { require(it is Boolean) } as Boolean else true) },
                approvals = if (version == 1) emptyList() else root.getJSONArray("approvals").objects { a -> ActionApprovalRequest(
                    a.getString("id"), a.getString("taskId"), a.getString("stepId"), a.getString("provider"),
                    a.getJSONObject("action").request(), a.getInt("schemaVersion"), a.strictLong("revision"),
                    a.getString("fingerprint"), a.strictLong("createdAtMs"), a.getBoolean("consumed"),
                    a.nullString("decision")?.let(ApprovalDecision::valueOf)) },
                grants = if (version == 1) emptyList() else root.getJSONArray("grants").objects { g -> ToolActionGrant(
                    g.getString("id"), g.getString("routineId"), g.getString("provider"), g.getInt("schemaVersion"),
                    g.getJSONArray("requests").objects { it.request() }, g.strictLong("createdAtMs"),
                    g.strictLong("expiresAtMs"), g.getBoolean("revoked")) },
                events = if (version == 1) emptyList() else root.getJSONArray("events").objects { e -> ToolTaskEvent(
                    e.getString("attemptId"), e.strictLong("generation"), ToolTaskEventKind.valueOf(e.getString("kind")), e.strictLong("atMs")) },
                activeQuestionId = if (version == 1) null else root.nullString("activeQuestionId"),
                sourceAccess = if (version == 1 || !root.has("sourceAccess")) emptyList()
                    else root.getJSONArray("sourceAccess").objects { s -> ToolSourceAccessRecord(
                    s.getString("family"), s.getJSONArray("scopes").strings().toSet(),
                    SourceAccessState.valueOf(s.getString("state")), s.strictLong("updatedAtMs")) },
                workflows = if (version < 3) emptyList()
                    else root.getJSONArray("workflows").objects { it.workflowDefinition() },
                occurrences = if (version < 3) emptyList()
                    else root.getJSONArray("occurrences").objects { o -> WorkflowOccurrence(
                    o.getString("id"), o.getString("workflowId"), o.strictLong("definitionVersion"),
                    o.getInt("triggerIndex"), o.getString("dedupKey"), o.strictLong("scheduledForMs"),
                    o.strictLong("windowEndMs"), WorkflowOccurrenceState.valueOf(o.getString("state")),
                    o.getJSONArray("resumePath").ints(), o.strictLong("createdAtMs"), o.strictLong("updatedAtMs"),
                    o.nullString("resultSummary"),
                    if (o.isNull("resumeAtMs")) null else o.strictLong("resumeAtMs"),
                    o.optJSONArray("completedStepIds")?.strings() ?: emptyList(),
                    o.optJSONObject("stepResults")?.let { sr ->
                        sr.keys().asSequence().associateWith { k ->
                            val inner = sr.getJSONObject(k)
                            inner.keys().asSequence().associateWith { k2 -> inner.getString(k2) }
                        }
                    } ?: emptyMap()) },
                workflowReceipts = if (version < 3) emptyList()
                    else root.getJSONArray("workflowReceipts").objects { r -> WorkflowReceipt(
                    r.getString("id"), r.getString("workflowId"), r.nullString("occurrenceId"),
                    WorkflowReceiptKind.valueOf(r.getString("kind")), r.getString("message"), r.strictLong("atMs")) })
            validate(journal)
            // An older typed writer must never discard newer fields or coerce malformed values.
            // Unknown semantics stay on disk untouched until a compatible build understands them.
            // Schema stays 3 with the new Script step kind: Script steps are a step kind
            // within schema 3, not a schema bump. Upgrade: older schema-3 journals (and
            // schema 1/2 journals) read normally; the authority checks above still apply.
            // Rollback: a build without the Script codec that opens a journal containing
            // Script steps fails closed during decoding; the bytes on disk are
            // preserved. Unknown fields on supported steps are UNSUPPORTED_CONTENT.
            verifyLosslessRead(root, JSONObject(encode(journal)), rootObject = true)
            journal
        }
    } catch (failure: ToolTaskStorageException) { throw failure }
      catch (_: java.io.IOException) { throw ToolTaskStorageException() }
      catch (_: Exception) { throw ToolTaskStorageException(ToolTaskStorageFailure.INVALID_CONTENT) }

    private fun verifyLosslessRead(original: Any, decoded: Any, rootObject: Boolean = false) {
        when (original) {
            is JSONObject -> {
                require(decoded is JSONObject)
                original.keys().forEach { key ->
                    if (!(rootObject && key == "schemaVersion")) {
                        if (!decoded.has(key)) throw ToolTaskStorageException(ToolTaskStorageFailure.UNSUPPORTED_CONTENT)
                        verifyLosslessRead(original.get(key), decoded.get(key))
                    }
                }
            }
            is JSONArray -> {
                require(decoded is JSONArray && original.length() == decoded.length())
                for (index in 0 until original.length()) verifyLosslessRead(original.get(index), decoded.get(index))
            }
            is Number -> require(decoded is Number && original.toString().toBigDecimal().compareTo(decoded.toString().toBigDecimal()) == 0)
            else -> require(original == decoded)
        }
    }

    /** Preserve unfinished groups and unresolved unknown effects; keep 256 recent completed attempts. */
    private fun retain(j: ToolTaskJournal): ToolTaskJournal {
        val protectedGroups = j.groups.filter { group -> j.attempts.any {
            it.id in group.attemptIds && (!it.state.isTerminal() || it.state == ToolTaskState.UNKNOWN_OUTCOME && !it.reconciled)
        } }.mapTo(hashSetOf()) { it.id }
        val removable = j.attempts.filter {
            it.state.isTerminal() && (it.state != ToolTaskState.UNKNOWN_OUTCOME || it.reconciled) && it.groupId !in protectedGroups
        }.sortedBy { it.updatedAtMs }
        val drop = hashSetOf<String>()
        var count = j.attempts.size
        for (attempt in removable) {
            if (count <= RETAIN_RECEIPTS) break
            val ids = attempt.groupId?.let { id -> j.groups.find { it.id == id }?.attemptIds } ?: listOf(attempt.id)
            ids.filterNot { it in drop }.forEach { drop += it; count-- }
        }
        val kept = j.attempts.filterNot { it.id in drop }
        val approvalIds = kept.mapNotNullTo(hashSetOf()) { it.approvalId }
        val recent = j.approvals.filter { it.consumed && it.id !in approvalIds }.takeLast(RETAIN_RECEIPTS).mapTo(hashSetOf()) { it.id }
        val approvals = j.approvals.filter { !it.consumed || it.id in approvalIds || it.id in recent }
        val grantIds = kept.mapNotNullTo(hashSetOf()) { it.grantId }
        val latestAt = maxOf(j.attempts.maxOfOrNull { it.updatedAtMs } ?: 0L, j.grants.maxOfOrNull { it.createdAtMs } ?: 0L)
        val removableGrants = j.grants.filter { it.id !in grantIds && (it.revoked || it.expiresAtMs <= latestAt) }
            .sortedBy { it.createdAtMs }.take((j.grants.size - MAX_GRANTS).coerceAtLeast(0)).mapTo(hashSetOf()) { it.id }
        return j.copy(attempts = kept, groups = j.groups.filter { it.attemptIds.any { id -> id !in drop } },
            approvals = approvals, grants = j.grants.filterNot { it.id in removableGrants },
            events = j.events.filterNot { it.attemptId in drop }.takeLast(MAX_EVENTS),
            activeQuestionId = j.activeQuestionId?.takeIf { id -> approvals.any { it.id == id && !it.consumed } })
    }

    private fun validate(j: ToolTaskJournal) = try {
        require(j.attempts.size <= MAX_ATTEMPTS && j.groups.size <= MAX_ATTEMPTS && j.approvals.size <= MAX_APPROVALS &&
            j.grants.size <= MAX_GRANTS && j.events.size <= MAX_EVENTS)
        require(j.attempts.map { it.id }.toSet().size == j.attempts.size && j.groups.map { it.id }.toSet().size == j.groups.size)
        require(j.approvals.map { it.id }.toSet().size == j.approvals.size && j.grants.map { it.id }.toSet().size == j.grants.size)
        j.attempts.forEach { a ->
            require(a.id.isUuid() && a.stepId.isUuid() && a.generation >= 0 && a.actionRevision >= 0)
            require(a.createdAtMs >= 0 && a.updatedAtMs >= a.createdAtMs && a.provider == "native" && a.schemaVersion > 0)
            a.request.validateRequest()
            require(a.result == null || a.result.length <= 2048)
            require(a.groupId == null || j.groups.any { it.id == a.groupId && a.id in it.attemptIds })
            require(a.grantId == null || j.grants.any { it.id == a.grantId })
            require(a.approvalId == null || j.approvals.any { it.id == a.approvalId })
        }
        j.groups.forEach { g ->
            require(g.id.isUuid() && g.summary.length <= 256 && g.conversationId.length in 1..256)
            require(g.createdAtMs >= 0 && g.expiresAtMs >= g.createdAtMs && g.attemptIds.size in 1..3)
            require(g.attemptIds.toSet().size == g.attemptIds.size && g.attemptIds.all { id -> j.attempts.any { it.id == id && it.groupId == g.id } })
        }
        j.approvals.forEach { a ->
            require(a.id.isUuid() && a.taskId.length in 1..256 && a.stepId.length in 1..256 && a.provider == "native")
            require(a.schemaVersion > 0 && a.revision > 0 && a.createdAtMs >= 0)
            a.action.validateRequest()
            require(a.fingerprint == approvalFingerprint(a.taskId, a.stepId, a.provider, a.action, a.schemaVersion, a.revision))
            require(a.consumed == (a.decision != null))
        }
        j.grants.forEach { g ->
            require(g.id.isUuid() && g.routineId.length in 1..256 && g.provider == "native" && g.schemaVersion > 0)
            require(g.createdAtMs >= 0 && g.expiresAtMs > g.createdAtMs && g.requests.size in 1..3)
            g.requests.forEach { it.validateRequest(); require(it.isRoutineEligible()) }
        }
        require(j.activeQuestionId == null || j.approvals.any { it.id == j.activeQuestionId && !it.consumed })
        j.events.forEach { e -> require(e.attemptId.isUuid() && e.generation >= 0 && e.atMs >= 0) }
        require(j.sourceAccess.size <= MAX_SOURCE_ACCESS &&
            j.sourceAccess.map { it.family }.toSet().size == j.sourceAccess.size)
        j.sourceAccess.forEach { s ->
            require(s.family.isNotBlank() && s.family.length <= 64 && s.updatedAtMs >= 0)
            require(s.scopes.size <= 16 && s.scopes.all { it.length <= 64 })
            // A persisted grant can never exceed its family's scope set (T08).
            // M3: provider families are capped by their static namespace —
            // enforceable without a registry at read time.
            if (ToolSourcePolicy.isProviderFamily(s.family)) {
                require(s.scopes.all { ToolSourcePolicy.providerScopeWithinCap(s.family, it) })
            } else {
                require(s.scopes.all { it in ToolSourcePolicy.familyScopes(s.family) })
            }
        }
        // M2 workflows: definitions validate structurally; occurrences pin
        // an existing version; dedup keys are unique among unfinished runs.
        require(j.workflows.size <= MAX_WORKFLOWS && j.occurrences.size <= MAX_OCCURRENCES &&
            j.workflowReceipts.size <= MAX_WORKFLOW_RECEIPTS)
        j.workflows.forEach { w ->
            require(w.id.isUuid())
            try { validateWorkflowDefinition(w) } catch (_: IllegalArgumentException) {
                throw ToolTaskStorageException()
            }
        }
        j.occurrences.forEach { o ->
            require(o.id.isUuid() && o.dedupKey.isNotBlank() && o.dedupKey.length <= 256)
            require(o.scheduledForMs > 0 && o.windowEndMs >= o.scheduledForMs)
            require(o.createdAtMs >= 0 && o.updatedAtMs >= o.createdAtMs)
            require(o.resumePath.size <= 8 && o.resumePath.all { it >= 0 })
            require(o.resumeAtMs == null || o.resumeAtMs > 0)
            require(o.completedStepIds.size <= 512 &&
                o.stepResults.size <= 512 &&
                o.stepResults.all { (k, v) -> k.length <= 128 && v.size <= 64 })
            require(o.resultSummary == null || o.resultSummary.length <= 512)
            require(j.workflows.any { it.id == o.workflowId && it.version == o.definitionVersion })
        }
        require(j.occurrences.filter { !it.state.isWorkflowTerminal() }.map { it.dedupKey }.toSet().size ==
            j.occurrences.count { !it.state.isWorkflowTerminal() })
        j.workflowReceipts.forEach { r ->
            require(r.id.isUuid() && r.workflowId.isNotBlank() && r.message.length <= 512 && r.atMs >= 0)
        }
        Unit
    } catch (_: Exception) { throw ToolTaskStorageException(ToolTaskStorageFailure.INVALID_CONTENT) }

    private fun encode(j: ToolTaskJournal) = JSONObject().put("schemaVersion", 3)
        .put("attempts", JSONArray(j.attempts.map { a -> JSONObject()
            .put("id", a.id).put("generation", a.generation).put("state", a.state.name).put("request", a.request.json())
            .put("createdAtMs", a.createdAtMs).put("updatedAtMs", a.updatedAtMs)
            .put("result", a.result ?: JSONObject.NULL).put("resultOutcome", a.resultOutcome?.name ?: JSONObject.NULL)
            .put("groupId", a.groupId ?: JSONObject.NULL).put("stepId", a.stepId).put("provider", a.provider)
            .put("toolSchemaVersion", a.schemaVersion).put("authority", a.authority.name)
            .put("grantId", a.grantId ?: JSONObject.NULL).put("approvalId", a.approvalId ?: JSONObject.NULL)
            .put("actionRevision", a.actionRevision).put("reconciled", a.reconciled) }))
        .put("groups", JSONArray(j.groups.map { g -> JSONObject().put("id", g.id).put("conversationId", g.conversationId)
            .put("summary", g.summary).put("attemptIds", JSONArray(g.attemptIds)).put("createdAtMs", g.createdAtMs)
            .put("expiresAtMs", g.expiresAtMs).put("cancelled", g.cancelled).put("resumeAfterRestart", g.resumeAfterRestart) }))
        .put("approvals", JSONArray(j.approvals.map { a -> JSONObject().put("id", a.id).put("taskId", a.taskId)
            .put("stepId", a.stepId).put("provider", a.provider).put("action", a.action.json()).put("schemaVersion", a.schemaVersion)
            .put("revision", a.revision).put("fingerprint", a.fingerprint).put("createdAtMs", a.createdAtMs)
            .put("consumed", a.consumed).put("decision", a.decision?.name ?: JSONObject.NULL) }))
        .put("grants", JSONArray(j.grants.map { g -> JSONObject().put("id", g.id).put("routineId", g.routineId)
            .put("provider", g.provider).put("schemaVersion", g.schemaVersion).put("requests", JSONArray(g.requests.map { it.json() }))
            .put("createdAtMs", g.createdAtMs).put("expiresAtMs", g.expiresAtMs).put("revoked", g.revoked) }))
        .put("events", JSONArray(j.events.map { e -> JSONObject().put("attemptId", e.attemptId).put("generation", e.generation)
            .put("kind", e.kind.name).put("atMs", e.atMs) }))
        .put("sourceAccess", JSONArray(j.sourceAccess.map { s -> JSONObject().put("family", s.family)
            .put("scopes", JSONArray(s.scopes.toList())).put("state", s.state.name).put("updatedAtMs", s.updatedAtMs) }))
        .put("workflows", JSONArray(j.workflows.map { it.json() }))
        .put("occurrences", JSONArray(j.occurrences.map { o -> JSONObject().put("id", o.id)
            .put("workflowId", o.workflowId).put("definitionVersion", o.definitionVersion)
            .put("triggerIndex", o.triggerIndex).put("dedupKey", o.dedupKey)
            .put("scheduledForMs", o.scheduledForMs).put("windowEndMs", o.windowEndMs)
            .put("state", o.state.name).put("resumePath", JSONArray(o.resumePath))
            .put("createdAtMs", o.createdAtMs).put("updatedAtMs", o.updatedAtMs)
            .put("resultSummary", o.resultSummary ?: JSONObject.NULL)
            .put("resumeAtMs", o.resumeAtMs ?: JSONObject.NULL)
            .put("completedStepIds", JSONArray(o.completedStepIds))
            .put("stepResults", JSONObject(o.stepResults.mapValues { (_, m) -> JSONObject(m) })) }))
        .put("workflowReceipts", JSONArray(j.workflowReceipts.map { r -> JSONObject().put("id", r.id)
            .put("workflowId", r.workflowId).put("occurrenceId", r.occurrenceId ?: JSONObject.NULL)
            .put("kind", r.kind.name).put("message", r.message).put("atMs", r.atMs) }))
        .put("activeQuestionId", j.activeQuestionId ?: JSONObject.NULL).toString()

    private fun JSONObject.strictLong(key: String): Long = get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
    private fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.request(): ActionRequest {
        val args = getJSONObject("arguments")
        return ActionRequest(getString("name"), args.keys().asSequence().associateWith { key -> args.get(key).also { require(it is String) } as String })
    }
    private fun ActionRequest.json() = JSONObject().put("name", name).put("arguments", JSONObject(arguments))
    private fun ActionRequest.validateRequest() {
        // M1d: the durable journal must accept every catalog tool, not just
        // the original four — screen and destination tools are admitted for
        // tracking/approval and must persist like the rest.
        // M3: provider wire names (`provider:<kind>:<id>:<function>`) are
        // admitted too; their grant discipline lives in ToolSourceAccess.
        require(MobileToolCatalog.find(name) != null || ProviderWireNames.isProviderTool(name))
        require(arguments.size <= 3 && arguments.all { (k, v) -> k.length <= 64 && v.length <= 512 })
    }
    private fun <T> JSONArray.objects(map: (JSONObject) -> T) = (0 until length()).map { map(getJSONObject(it)) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.ints() = (0 until length()).map { getInt(it) }
    private fun String.isUuid() = UUID.fromString(this).toString() == this

    // -- M2 workflow JSON codec (schema 3) ------------------------------------

    private fun WorkflowDefinition.json() = JSONObject()
        .put("id", id).put("name", name).put("description", description)
        .put("steps", JSONArray(steps.map { it.json() }))
        .put("triggers", JSONArray(triggers.map { it.json() }))
        .put("origin", origin.name).put("version", version).put("enabled", enabled)
        .put("createdAtMs", createdAtMs).put("updatedAtMs", updatedAtMs)
        .put("maxStepsPerRun", maxStepsPerRun)

    private fun JSONObject.workflowDefinition(): WorkflowDefinition {
        val steps = getJSONArray("steps").objects { it.workflowStep() }
        val triggers = getJSONArray("triggers").objects { it.workflowTrigger() }
        return WorkflowDefinition(getString("id"), getString("name"), getString("description"),
            steps, triggers, WorkflowOrigin.valueOf(getString("origin")),
            strictLong("version"), getBoolean("enabled"), strictLong("createdAtMs"),
            strictLong("updatedAtMs"), getInt("maxStepsPerRun"))
    }

    private fun WorkflowStep.json(): JSONObject = when (this) {
        is WorkflowStep.Tool -> JSONObject().put("kind", "tool").put("id", id)
            .put("request", request.json())
            .put("bindings", JSONObject(bindings.mapValues { (_, b) ->
                JSONObject().put("stepId", b.stepId).put("output", b.outputName) }))
            .put("outputs", JSONObject(outputs.mapValues { (_, t) -> t.name }))
        is WorkflowStep.Branch -> JSONObject().put("kind", "branch").put("id", id)
            .put("condition", condition.json())
            .put("then", JSONArray(thenSteps.map { it.json() }))
            .put("else", JSONArray(elseSteps.map { it.json() }))
        is WorkflowStep.Wait -> JSONObject().put("kind", "wait").put("id", id)
            .put("wait", wait.json())
        is WorkflowStep.Adaptive -> JSONObject().put("kind", "adaptive").put("id", id)
            .put("goal", goal)
            .put("candidates", JSONArray(candidates.map { it.json() }))
            .put("budget", JSONObject().put("maxAttempts", budget.maxAttempts)
                .put("maxWallMs", budget.maxWallMs).put("noProgressLimit", budget.noProgressLimit))
        is WorkflowStep.Script -> JSONObject().put("kind", "script").put("id", id)
            .put("source", source)
            .put("requiredHostFunctions", JSONArray(requiredHostFunctions))
            .put("outputs", JSONObject(outputs.mapValues { (_, t) -> t.name }))
    }

    private fun JSONObject.workflowStep(): WorkflowStep {
        val id = getString("id")
        return when (getString("kind")) {
            "tool" -> {
                val bindings = getJSONObject("bindings").keys().asSequence().associateWith { key ->
                    val b = getJSONObject("bindings").getJSONObject(key)
                    WorkflowBinding(b.getString("stepId"), b.getString("output"))
                }
                val outputsObj = getJSONObject("outputs")
                val outputs = outputsObj.keys().asSequence().associateWith { key ->
                    WorkflowValueType.valueOf(outputsObj.getString(key))
                }
                WorkflowStep.Tool(id, getJSONObject("request").request(), bindings, outputs)
            }
            "branch" -> WorkflowStep.Branch(id, getJSONObject("condition").workflowCondition(),
                getJSONArray("then").objects { it.workflowStep() },
                getJSONArray("else").objects { it.workflowStep() })
            "wait" -> WorkflowStep.Wait(id, getJSONObject("wait").workflowWait())
            "adaptive" -> {
                val budget = getJSONObject("budget")
                WorkflowStep.Adaptive(id, getString("goal"),
                    getJSONArray("candidates").objects { it.request() },
                    EffortBudget(budget.getInt("maxAttempts"), budget.strictLong("maxWallMs"),
                        budget.getInt("noProgressLimit")))
            }
            "script" -> {
                val outputsObj = getJSONObject("outputs")
                val outputs = outputsObj.keys().asSequence().associateWith { key ->
                    WorkflowValueType.valueOf(outputsObj.getString(key))
                }
                WorkflowStep.Script(id, getString("source"),
                    getJSONArray("requiredHostFunctions").strings(), outputs)
            }
            else -> throw IllegalArgumentException("Unknown step kind.")
        }
    }

    private fun WorkflowCondition.json(): JSONObject {
        fun base(kind: String, binding: WorkflowBinding) = JSONObject().put("kind", kind)
            .put("stepId", binding.stepId).put("output", binding.outputName)
        return when (this) {
            is WorkflowCondition.Equals -> base("eq", binding).put("literal", literal)
            is WorkflowCondition.NotEquals -> base("ne", binding).put("literal", literal)
            is WorkflowCondition.GreaterThan -> base("gt", binding).put("number", number)
            is WorkflowCondition.LessThan -> base("lt", binding).put("number", number)
            is WorkflowCondition.Matches -> base("matches", binding).put("regex", regex)
        }
    }

    private fun JSONObject.workflowCondition(): WorkflowCondition {
        val binding = WorkflowBinding(getString("stepId"), getString("output"))
        return when (getString("kind")) {
            "eq" -> WorkflowCondition.Equals(binding, getString("literal"))
            "ne" -> WorkflowCondition.NotEquals(binding, getString("literal"))
            "gt" -> WorkflowCondition.GreaterThan(binding, getDouble("number"))
            "lt" -> WorkflowCondition.LessThan(binding, getDouble("number"))
            "matches" -> WorkflowCondition.Matches(binding, getString("regex"))
            else -> throw IllegalArgumentException("Unknown condition kind.")
        }
    }

    private fun WorkflowWait.json(): JSONObject = when (this) {
        is WorkflowWait.Timer -> JSONObject().put("kind", "timer").put("durationMs", durationMs)
        is WorkflowWait.UntilTime -> JSONObject().put("kind", "until").put("epochMs", epochMs)
        is WorkflowWait.Event -> JSONObject().put("kind", "event").put("eventKind", kind.name)
            .put("appKey", appKey ?: JSONObject.NULL)
            .put("latitude", latitude ?: JSONObject.NULL).put("longitude", longitude ?: JSONObject.NULL)
            .put("radiusMeters", radiusMeters ?: JSONObject.NULL)
    }

    private fun JSONObject.workflowWait(): WorkflowWait = when (getString("kind")) {
        "timer" -> WorkflowWait.Timer(strictLong("durationMs"))
        "until" -> WorkflowWait.UntilTime(strictLong("epochMs"))
        "event" -> WorkflowWait.Event(WorkflowEventKind.valueOf(getString("eventKind")),
            nullString("appKey"),
            if (isNull("latitude")) null else getDouble("latitude"),
            if (isNull("longitude")) null else getDouble("longitude"),
            if (isNull("radiusMeters")) null else getDouble("radiusMeters"))
        else -> throw IllegalArgumentException("Unknown wait kind.")
    }

    private fun WorkflowTrigger.json(): JSONObject = when (this) {
        is WorkflowTrigger.Manual -> JSONObject().put("kind", "manual")
        is WorkflowTrigger.Reminder -> JSONObject().put("kind", "reminder").put("atMs", atMs)
        is WorkflowTrigger.Daily -> JSONObject().put("kind", "daily").put("hour", hour).put("minute", minute)
        is WorkflowTrigger.Window -> JSONObject().put("kind", "window")
            .put("earliestMs", earliestMs).put("latestMs", latestMs)
        is WorkflowTrigger.OnNotification -> JSONObject().put("kind", "notification").put("appKey", appKey)
        is WorkflowTrigger.OnLocation -> JSONObject().put("kind", "location")
            .put("latitude", latitude).put("longitude", longitude).put("radiusMeters", radiusMeters)
        is WorkflowTrigger.Deadline -> JSONObject().put("kind", "deadline")
            .put("atMs", atMs).put("title", title)
    }

    private fun JSONObject.workflowTrigger(): WorkflowTrigger = when (getString("kind")) {
        "manual" -> WorkflowTrigger.Manual
        "reminder" -> WorkflowTrigger.Reminder(strictLong("atMs"))
        "daily" -> WorkflowTrigger.Daily(getInt("hour"), getInt("minute"))
        "window" -> WorkflowTrigger.Window(strictLong("earliestMs"), strictLong("latestMs"))
        "notification" -> WorkflowTrigger.OnNotification(getString("appKey"))
        "location" -> WorkflowTrigger.OnLocation(getDouble("latitude"), getDouble("longitude"), getDouble("radiusMeters"))
        "deadline" -> WorkflowTrigger.Deadline(strictLong("atMs"), getString("title"))
        else -> throw IllegalArgumentException("Unknown trigger kind.")
    }

    /**
     * M2 retention: keep every unfinished occurrence and the versions they
     * pin; keep recent terminal occurrences and their receipts; drop oldest
     * unreferenced non-current definition versions first when over budget.
     */
    private fun retainWorkflows(j: ToolTaskJournal): ToolTaskJournal {
        val unfinished = j.occurrences.filter { !it.state.isWorkflowTerminal() }
        val keptOccurrences = (unfinished +
            j.occurrences.filter { it.state.isWorkflowTerminal() }
                .sortedByDescending { it.updatedAtMs }.take(RETAIN_OCCURRENCES)).toSet()
        val keptOccurrenceIds = keptOccurrences.mapTo(hashSetOf()) { it.id }
        val keptReceipts = (j.workflowReceipts.filter { it.occurrenceId in keptOccurrenceIds } +
            j.workflowReceipts.sortedByDescending { it.atMs }.take(RETAIN_WORKFLOW_RECEIPTS))
            .distinctBy { it.id }.sortedBy { it.atMs }.takeLast(MAX_WORKFLOW_RECEIPTS)
        val referenced = keptOccurrences.mapTo(hashSetOf()) { it.workflowId to it.definitionVersion }
        val currentVersions = j.workflows.groupBy { it.id }.mapValues { (_, versions) -> versions.maxOf { it.version } }
        var keptWorkflows = j.workflows.filter { (it.id to it.version) in referenced || currentVersions[it.id] == it.version }
        if (keptWorkflows.size > MAX_WORKFLOWS) {
            val drop = keptWorkflows
                .filter { (it.id to it.version) !in referenced && currentVersions[it.id] != it.version }
                .sortedBy { it.updatedAtMs }
                .take(keptWorkflows.size - MAX_WORKFLOWS)
                .mapTo(hashSetOf()) { it.id to it.version }
            keptWorkflows = keptWorkflows.filter { (it.id to it.version) !in drop }
        }
        return j.copy(workflows = keptWorkflows,
            occurrences = j.occurrences.filter { it.id in keptOccurrenceIds },
            workflowReceipts = keptReceipts)
    }
    companion object {
        const val MAX_ATTEMPTS = 512
        const val MAX_BYTES = 1_048_576
        const val MAX_APPROVALS = 512
        const val MAX_GRANTS = 128
        const val MAX_EVENTS = 256
        const val MAX_SOURCE_ACCESS = 64
        const val RETAIN_RECEIPTS = 256
        /** M2 workflow storage budgets. */
        const val MAX_WORKFLOWS = 64
        const val MAX_OCCURRENCES = 256
        const val MAX_WORKFLOW_RECEIPTS = 256
        const val RETAIN_OCCURRENCES = 128
        const val RETAIN_WORKFLOW_RECEIPTS = 256
        private val locks = ConcurrentHashMap<String, Any>()
        private fun lockFor(file: File) = locks.getOrPut(file.canonicalPath) { Any() }
        private fun atomicWrite(destination: File, contents: String) {
            val parent = destination.parentFile ?: throw ToolTaskStorageException()
            if (!parent.isDirectory && !parent.mkdirs()) throw ToolTaskStorageException()
            val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { out -> out.write(contents.toByteArray(StandardCharsets.UTF_8)); out.fd.sync() }
                if (!temporary.renameTo(destination)) throw ToolTaskStorageException()
            } finally { temporary.delete() }
        }
    }
}
