package com.battlesbudz.jarvis.v2.memory

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Memory RAG Unit 3 (nightly dream synthesis): the dream journal.
 *
 * The journal is the pass's durable audit trail and its ONLY output surface
 * besides committed records: every run leaves exactly one entry, and every
 * group the judge touched leaves one output row — committed, would-commit
 * (dry-run nights, OD-5), journaled-because-a-gate-refused-it (OD-3: dropped
 * proposals are recorded with the gate that killed them), or no-op. Under
 * OD-4 the dream never supersedes autonomously, so MERGE/SUPERSEDE outputs
 * can only ever appear here as JOURNALED rows with reason
 * [DreamReasons.AUTONOMOUS_SUPERSEDE_DISABLED]; the diagnostics counters
 * for those verbs stay structurally zero until Battles re-opens the verb.
 *
 * The journal also carries the pass's two pieces of scheduling state: the
 * watermark (max updatedAtMs of records fully processed by completed runs)
 * and the set of night keys that counted toward the OD-5 dry-run staging.
 * Both are monotonic: saving can never move the watermark backwards or
 * un-count a night.
 *
 * The journal lives apart from the memory ledger on purpose: dry-run purity
 * (scope test of the same name) requires dry-run nights to write nothing to
 * the record store, and the journal is where those nights write instead.
 */
enum class DreamRunOutcome {
    COMPLETED,
    SKIPPED_CONTENTION,
    SKIPPED_INSUFFICIENT,
    SKIPPED_NO_MODEL,
    FAILED,
}

/** What the judge (or, for CREATE, the extraction pass) proposed. */
enum class DreamVerb { MERGE, SUPERSEDE, SYNTHESIZE, CREATE, NO_OP }

/** What the pass did with a proposal. */
enum class DreamOutputAction {
    /** Committed to the ledger as a new APPROVED record. */
    COMMITTED,

    /** Passed every gate on a dry-run night; journaled instead of committed. */
    WOULD_COMMIT,

    /** Refused by a gate or policy; [DreamOutputEntry.reason] names the gate. */
    JOURNALED,

    /** No action proposed or possible (no-op group, invalid judge output). */
    NONE,
}

data class DreamOutputEntry(
    val verb: DreamVerb,
    val action: DreamOutputAction,
    /** The committed record's id, when [action] is COMMITTED. */
    val newRecordId: String?,
    /** Exact source record ids the proposal was based on (allowlist-checked). */
    val sourceRecordIds: List<String>,
    /** Proposed content for creates; null for no-ops. */
    val content: String?,
    /** Judge confidence 0..100 when the judge produced one. */
    val judgeConfidence: Int?,
    /** Gate reason code; see [DreamReasons]. */
    val reason: String,
)

data class DreamRunEntry(
    val runId: String,
    /** Local calendar date the run belongs to (yyyy-MM-dd). */
    val nightKey: String,
    val startedAtMs: Long,
    /** Null while the run is in progress (crash-resume marker). */
    val finishedAtMs: Long?,
    val outcome: DreamRunOutcome?,
    val dryRun: Boolean,
    val watermarkBeforeMs: Long,
    val watermarkAfterMs: Long,
    /** Stable keys of groups/clusters fully processed (crash-resume skip set). */
    val processedGroupKeys: List<String>,
    val groupsExamined: Int,
    val outputs: List<DreamOutputEntry>,
    /** Judge model id when a model ran; null on skipped runs. */
    val judgeModelId: String?,
    val note: String?,
) {
    val isComplete: Boolean get() = finishedAtMs != null
    val committedCreates: Int get() = outputs.count {
        it.action == DreamOutputAction.COMMITTED || it.action == DreamOutputAction.WOULD_COMMIT
    }
    val committedOf: (DreamVerb) -> Int = { verb ->
        outputs.count {
            it.verb == verb &&
                (it.action == DreamOutputAction.COMMITTED || it.action == DreamOutputAction.WOULD_COMMIT)
        }
    }
    val noopCount: Int get() = outputs.count { it.action == DreamOutputAction.NONE }
    val vetoedCount: Int get() = outputs.count { it.action == DreamOutputAction.JOURNALED }
}

/** Gate reason codes recorded in [DreamOutputEntry.reason]. */
object DreamReasons {
    const val OK = "ok"
    const val ALREADY_RECORDED = "already_recorded"
    const val DRY_RUN = "dry_run"
    const val AUTONOMOUS_SUPERSEDE_DISABLED = "autonomous_supersede_disabled"
    const val FENCED_CONTENT = "fenced_content"
    const val EVIDENCE_FLOOR = "evidence_floor"
    const val CONFIDENCE_FLOOR = "confidence_floor"
    const val VETO_RESTRICTED = "veto_restricted_content"
    const val VETO_SENSITIVE = "veto_sensitive_pattern"
    const val VETO_CONTRADICTION = "veto_contradiction"
    const val VETO_HEDGE = "veto_hedging"
    const val CAP_HEADROOM = "cap_headroom"
    const val EVENT_CONFLICT = "event_conflict"
    const val JUDGE_OUTPUT_INVALID = "judge_output_invalid"
    const val JUDGE_ERROR = "judge_error"
    const val DERIVED_SOLE_SOURCE = "derived_sole_source"
    const val NO_OP = "no_op"
    const val LINK_ONLY = "link_only"
    const val STORAGE_FAILURE = "storage_failure"
    const val VECTOR_STORE_UNAVAILABLE = "vector_store_unavailable"
    fun proposalRejected(outcome: MemoryOutcome): String = "proposal_rejected:${outcome.name}"
}

data class DreamJournalState(
    val entries: List<DreamRunEntry> = emptyList(),
    /** Max updatedAtMs of records fully processed by completed runs; monotonic. */
    val watermarkMs: Long = 0L,
    /** Night keys that counted as completed dream nights (OD-5 staging). */
    val completedNightKeys: Set<String> = emptySet(),
    /**
     * Groups fully processed by LIVE runs, across nights: a group whose exact
     * source set was already judged is not re-judged on later nights (its key
     * changes the moment a member joins, leaves, or is replaced). Dry-run
     * nights never publish here — their would-commits are still owed a live
     * commit, so their groups must stay eligible.
     */
    val processedGroupKeys: List<String> = emptyList(),
) {
    val completedNights: Int get() = completedNightKeys.size
    val lastEntry: DreamRunEntry? get() = entries.lastOrNull()
    val lastCompleted: DreamRunEntry? get() = entries.lastOrNull {
        it.isComplete && it.outcome == DreamRunOutcome.COMPLETED
    }
}

interface DreamJournalStore {
    fun load(): DreamJournalState
    fun save(state: DreamJournalState)
}

/** Test/fake store: state lives in memory only. */
class InMemoryDreamJournalStore(initial: DreamJournalState = DreamJournalState()) : DreamJournalStore {
    private var state: DreamJournalState = initial
    override fun load(): DreamJournalState = state
    override fun save(state: DreamJournalState) { this.state = state }
}

/**
 * The production store: one JSON file beside the ledger in noBackupFilesDir.
 * Writes are atomic (temp file + rename). A corrupt or unreadable file loads
 * as an empty state — fail-closed, like the vector projection: the pass then
 * re-derives from the ledger, and deterministic event ids make the replay a
 * no-op rather than a duplication.
 */
class FileDreamJournalStore(private val file: File) : DreamJournalStore {
    override fun load(): DreamJournalState {
        if (!file.isFile) return DreamJournalState()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return DreamJournalState()
        return runCatching { DreamJournalJson.decode(JSONObject(text)) }.getOrDefault(DreamJournalState())
    }

    override fun save(state: DreamJournalState) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(DreamJournalJson.encode(state).toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}

/**
 * The journal facade the pass and the diagnostics/settings surfaces use.
 * Single-writer (the dream worker); readers load fresh state each call.
 */
class MemoryDreamJournal(private val store: DreamJournalStore) {
    fun state(): DreamJournalState = store.load()

    /**
     * Persists [state] with the monotonicity invariants enforced against what
     * is already stored: the watermark never moves backwards and a counted
     * night is never un-counted, whatever a stale in-memory copy believes.
     */
    fun save(state: DreamJournalState) {
        val current = store.load()
        store.save(
            state.copy(
                entries = state.entries.takeLast(MAX_ENTRIES),
                watermarkMs = maxOf(state.watermarkMs, current.watermarkMs),
                completedNightKeys = state.completedNightKeys + current.completedNightKeys,
                processedGroupKeys = (current.processedGroupKeys + state.processedGroupKeys)
                    .distinct().takeLast(MAX_PROCESSED_GROUP_KEYS),
            )
        )
    }

    /**
     * The Copy Diagnostics line (OD-6), exact format from the scope:
     * `Memory dream: runs=N last=<runId|none> lastOutcome=<…> merged=A
     * superseded=B synthesized=C noop=D vetoed=E watermark=<ms>`.
     * Verb counts describe the last completed run; on a dry-run last night
     * they count would-commits, which is what `lastOutcome=dry_run` flags.
     */
    fun diagnosticsLine(): String {
        val state = store.load()
        val last = state.lastEntry
        val countsFrom = state.lastCompleted
        val outcome = when {
            last == null -> "none"
            !last.isComplete -> "in_progress"
            last.outcome == DreamRunOutcome.COMPLETED && last.dryRun -> "dry_run"
            else -> last.outcome?.name?.lowercase() ?: "unknown"
        }
        fun count(verb: DreamVerb): Int = countsFrom?.committedOf?.invoke(verb) ?: 0
        val synthesized = count(DreamVerb.SYNTHESIZE) + count(DreamVerb.CREATE)
        return "Memory dream: runs=${state.entries.size} last=${last?.runId ?: "none"} " +
            "lastOutcome=$outcome merged=${count(DreamVerb.MERGE)} superseded=${count(DreamVerb.SUPERSEDE)} " +
            "synthesized=$synthesized noop=${countsFrom?.noopCount ?: 0} vetoed=${countsFrom?.vetoedCount ?: 0} " +
            "watermark=${state.watermarkMs}"
    }

    companion object {
        /** Retained run history; older entries are dropped from the front. */
        const val MAX_ENTRIES = 90

        /** OD-5: the first this-many counted nights run dry. */
        const val DRY_RUN_NIGHTS = 3

        /** Bound on the cross-night processed-group registry. */
        const val MAX_PROCESSED_GROUP_KEYS = 512
    }
}

internal object DreamJournalJson {
    fun encode(state: DreamJournalState): JSONObject {
        val entries = JSONArray()
        state.entries.forEach { entry -> entries.put(encodeEntry(entry)) }
        val nights = JSONArray()
        state.completedNightKeys.sorted().forEach { nights.put(it) }
        val groupKeys = JSONArray()
        state.processedGroupKeys.forEach { groupKeys.put(it) }
        return JSONObject()
            .put("version", 1)
            .put("entries", entries)
            .put("watermarkMs", state.watermarkMs)
            .put("completedNightKeys", nights)
            .put("processedGroupKeys", groupKeys)
    }

    fun decode(json: JSONObject): DreamJournalState {
        val entries = ArrayList<DreamRunEntry>()
        val rawEntries = json.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until rawEntries.length()) {
            rawEntries.optJSONObject(i)?.let { entries += decodeEntry(it) }
        }
        val nights = LinkedHashSet<String>()
        val rawNights = json.optJSONArray("completedNightKeys") ?: JSONArray()
        for (i in 0 until rawNights.length()) rawNights.optString(i).takeIf { it.isNotBlank() }?.let { nights += it }
        val groupKeys = ArrayList<String>()
        val rawKeys = json.optJSONArray("processedGroupKeys") ?: JSONArray()
        for (i in 0 until rawKeys.length()) rawKeys.optString(i).takeIf { it.isNotBlank() }?.let { groupKeys += it }
        return DreamJournalState(
            entries = entries,
            watermarkMs = json.optLong("watermarkMs", 0L).coerceAtLeast(0L),
            completedNightKeys = nights,
            processedGroupKeys = groupKeys.distinct().takeLast(MemoryDreamJournal.MAX_PROCESSED_GROUP_KEYS),
        )
    }

    private fun encodeEntry(entry: DreamRunEntry): JSONObject {
        val outputs = JSONArray()
        entry.outputs.forEach { output ->
            val ids = JSONArray()
            output.sourceRecordIds.forEach { ids.put(it) }
            outputs.put(
                JSONObject()
                    .put("verb", output.verb.name)
                    .put("action", output.action.name)
                    .put("newRecordId", output.newRecordId ?: JSONObject.NULL)
                    .put("sourceRecordIds", ids)
                    .put("content", output.content ?: JSONObject.NULL)
                    .put("judgeConfidence", output.judgeConfidence ?: JSONObject.NULL)
                    .put("reason", output.reason)
            )
        }
        return JSONObject()
            .put("runId", entry.runId)
            .put("nightKey", entry.nightKey)
            .put("startedAtMs", entry.startedAtMs)
            .put("finishedAtMs", entry.finishedAtMs ?: JSONObject.NULL)
            .put("outcome", entry.outcome?.name ?: JSONObject.NULL)
            .put("dryRun", entry.dryRun)
            .put("watermarkBeforeMs", entry.watermarkBeforeMs)
            .put("watermarkAfterMs", entry.watermarkAfterMs)
            .put("processedGroupKeys", JSONArray(entry.processedGroupKeys))
            .put("groupsExamined", entry.groupsExamined)
            .put("outputs", outputs)
            .put("judgeModelId", entry.judgeModelId ?: JSONObject.NULL)
            .put("note", entry.note ?: JSONObject.NULL)
    }

    private fun decodeEntry(json: JSONObject): DreamRunEntry {
        val outputs = ArrayList<DreamOutputEntry>()
        val rawOutputs = json.optJSONArray("outputs") ?: JSONArray()
        for (i in 0 until rawOutputs.length()) {
            val raw = rawOutputs.optJSONObject(i) ?: continue
            val ids = ArrayList<String>()
            val rawIds = raw.optJSONArray("sourceRecordIds") ?: JSONArray()
            for (j in 0 until rawIds.length()) rawIds.optString(j).takeIf { it.isNotBlank() }?.let { ids += it }
            outputs += DreamOutputEntry(
                verb = runCatching { DreamVerb.valueOf(raw.getString("verb")) }.getOrDefault(DreamVerb.NO_OP),
                action = runCatching { DreamOutputAction.valueOf(raw.getString("action")) }.getOrDefault(DreamOutputAction.NONE),
                newRecordId = raw.optString("newRecordId").takeIf { it.isNotBlank() && !raw.isNull("newRecordId") },
                sourceRecordIds = ids,
                content = if (raw.isNull("content")) null else raw.optString("content").takeIf { it.isNotEmpty() },
                judgeConfidence = if (raw.isNull("judgeConfidence")) null else raw.optInt("judgeConfidence"),
                reason = raw.optString("reason", DreamReasons.OK),
            )
        }
        val keys = ArrayList<String>()
        val rawKeys = json.optJSONArray("processedGroupKeys") ?: JSONArray()
        for (i in 0 until rawKeys.length()) rawKeys.optString(i).takeIf { it.isNotBlank() }?.let { keys += it }
        return DreamRunEntry(
            runId = json.getString("runId"),
            nightKey = json.optString("nightKey"),
            startedAtMs = json.optLong("startedAtMs"),
            finishedAtMs = if (json.isNull("finishedAtMs")) null else json.optLong("finishedAtMs"),
            outcome = if (json.isNull("outcome")) null else runCatching {
                DreamRunOutcome.valueOf(json.getString("outcome"))
            }.getOrNull(),
            dryRun = json.optBoolean("dryRun"),
            watermarkBeforeMs = json.optLong("watermarkBeforeMs"),
            watermarkAfterMs = json.optLong("watermarkAfterMs"),
            processedGroupKeys = keys,
            groupsExamined = json.optInt("groupsExamined"),
            outputs = outputs,
            judgeModelId = if (json.isNull("judgeModelId")) null else json.optString("judgeModelId").takeIf { it.isNotBlank() },
            note = if (json.isNull("note")) null else json.optString("note").takeIf { it.isNotEmpty() },
        )
    }
}
