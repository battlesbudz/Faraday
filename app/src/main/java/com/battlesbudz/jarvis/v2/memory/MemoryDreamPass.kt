package com.battlesbudz.jarvis.v2.memory

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * Memory RAG Unit 3: one nightly dream pass.
 *
 * Reads only the canonical ledger through [MemoryOs], groups records changed
 * since the journal watermark, asks the judge for one verb per group, runs
 * every actionable proposal through [MemoryDreamGate], and commits survivors
 * through `MemoryOs.propose` + immediate approve — the D-6 shape — with exact
 * per-source provenance and a fence registration for every commit.
 *
 * Crash discipline: the journal entry is created in-progress before any group
 * runs and re-saved after every group with the processed-group set and the
 * frontier watermark, so a killed worker resumes at the next unprocessed
 * group; deterministic per-output event ids make a replayed commit resolve
 * to ALREADY_RECORDED instead of a duplicate. Dry-run nights (OD-5) run the
 * full pipeline and journal WOULD_COMMIT outputs, writing nothing to the
 * ledger — the pass does not even call propose on a dry night.
 */
class MemoryDreamPass(
    private val memoryOs: MemoryOs,
    private val fence: AutomaticMemoryFence,
    private val journal: MemoryDreamJournal,
    /** Current-stamp vectors by record id; null when the projection is unreadable. */
    private val vectors: () -> Map<String, FloatArray>?,
    private val judge: DreamJudge,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class StorageAbort(message: String) : Exception(message)

    suspend fun run(nightKey: String, judgeModelId: String?): DreamRunEntry {
        val state = journal.state()
        val incomplete = state.entries.lastOrNull { !it.isComplete }
        if (incomplete != null) {
            return execute(incomplete, countedNight = incomplete.nightKey !in state.completedNightKeys)
        }
        val priorCompletion = state.entries.lastOrNull {
            it.nightKey == nightKey && it.isComplete && it.outcome == DreamRunOutcome.COMPLETED
        }
        if (priorCompletion != null || nightKey in state.completedNightKeys) {
            // A second firing on a completed night journals a no-op run and
            // counts nothing twice (scope §3.1).
            val now = clock()
            val entry = DreamRunEntry(
                runId = uniqueRunId(state, nightKey, now),
                nightKey = nightKey,
                startedAtMs = now,
                finishedAtMs = now,
                outcome = DreamRunOutcome.COMPLETED,
                dryRun = priorCompletion?.dryRun ?: (state.completedNightKeys.size < MemoryDreamJournal.DRY_RUN_NIGHTS),
                watermarkBeforeMs = state.watermarkMs,
                watermarkAfterMs = state.watermarkMs,
                processedGroupKeys = emptyList(),
                groupsExamined = 0,
                outputs = emptyList(),
                judgeModelId = judgeModelId,
                note = "repeat_firing_noop",
            )
            journal.save(state.copy(entries = state.entries + entry))
            return entry
        }
        val started = clock()
        val entry = DreamRunEntry(
            runId = uniqueRunId(state, nightKey, started),
            nightKey = nightKey,
            startedAtMs = started,
            finishedAtMs = null,
            outcome = null,
            dryRun = state.completedNightKeys.size < MemoryDreamJournal.DRY_RUN_NIGHTS,
            watermarkBeforeMs = state.watermarkMs,
            watermarkAfterMs = state.watermarkMs,
            processedGroupKeys = emptyList(),
            groupsExamined = 0,
            outputs = emptyList(),
            judgeModelId = judgeModelId,
            note = null,
        )
        return execute(entry, countedNight = true)
    }

    private fun uniqueRunId(state: DreamJournalState, nightKey: String, startedAtMs: Long): String {
        val base = "dream-$nightKey-$startedAtMs"
        if (state.entries.none { it.runId == base }) return base
        var suffix = 2
        while (state.entries.any { it.runId == "$base-$suffix" }) suffix++
        return "$base-$suffix"
    }

    private suspend fun execute(initialEntry: DreamRunEntry, countedNight: Boolean): DreamRunEntry {
        var state = journal.state()
        var entry = initialEntry
        val outputs = initialEntry.outputs.toMutableList()
        val processedKeys = initialEntry.processedGroupKeys.toMutableSet()
        // Cross-night registry: groups a previous LIVE run fully processed are
        // skipped without a judge call. This entry's own keys join it on
        // persist — unless this is a dry run, whose groups stay eligible for
        // the live commit they are still owed.
        val globalProcessedKeys = state.processedGroupKeys.toSet()
        var groupsExamined = initialEntry.groupsExamined
        var watermark = maxOf(state.watermarkMs, initialEntry.watermarkBeforeMs)
        var note = initialEntry.note

        fun persist(finished: Boolean = false, outcome: DreamRunOutcome? = null, finalNote: String? = note) {
            // A dry run never advances the watermark: its would-commits are
            // still owed, so the changed set must survive intact until the
            // first live night can commit it. (The journal's own clamp keeps
            // the stored watermark monotonic regardless.)
            val effectiveWatermark = if (entry.dryRun) entry.watermarkBeforeMs else watermark
            entry = entry.copy(
                finishedAtMs = if (finished) clock() else null,
                outcome = outcome ?: entry.outcome,
                watermarkAfterMs = effectiveWatermark,
                processedGroupKeys = processedKeys.sorted(),
                groupsExamined = groupsExamined,
                outputs = outputs.toList(),
                note = finalNote,
            )
            val entries = state.entries.filterNot { it.runId == entry.runId } + entry
            val nights = if (finished && outcome == DreamRunOutcome.COMPLETED && countedNight) {
                state.completedNightKeys + entry.nightKey
            } else {
                state.completedNightKeys
            }
            val globalKeys = if (entry.dryRun) {
                state.processedGroupKeys
            } else {
                (state.processedGroupKeys + processedKeys).distinct()
            }
            state = state.copy(
                entries = entries,
                watermarkMs = maxOf(state.watermarkMs, effectiveWatermark),
                completedNightKeys = nights,
                processedGroupKeys = globalKeys,
            )
            journal.save(state)
            state = journal.state()
        }

        fun isFenced(content: String, sources: List<MemoryRecord>, snapshot: MemorySnapshot): Boolean {
            if (fence.isFenced(AutomaticMemory.contentFingerprint(content), snapshot)) return true
            return sources.any { fence.isFenced(AutomaticMemory.contentFingerprint(it.content), snapshot) }
        }

        fun commitOutput(
            verb: DreamVerb,
            content: String,
            type: MemoryType,
            category: MemoryCategory,
            sources: List<MemoryRecord>,
            confidence: Int,
            groupKey: String,
        ): DreamOutputEntry {
            val fingerprint = AutomaticMemory.contentFingerprint(content)
            val eventId = "dream-" + MessageDigest.getInstance("SHA-256")
                .digest("${entry.runId}|$groupKey|${verb.name}|$fingerprint".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(48)
            val provenance = listOf(MemoryProvenance("dream_cycle", entry.runId, "Nightly dream synthesis")) +
                sources.map { MemoryProvenance("source_memory", it.id) }
            val proposal = MemoryProposal(
                content = content,
                source = MemorySource(eventId, "dream_cycle/${entry.runId}", entry.startedAtMs, MemorySensitivity.NORMAL, provenance),
                category = category,
                tier = MemoryTier.LONG_TERM,
                type = type,
                confidence = confidence,
                wikiAssignment = MemoryWiki.suggest(content, category),
            )
            fun committed(record: MemoryRecord, reason: String): DreamOutputEntry {
                fence.recordCommitted(fingerprint, record.id)
                return DreamOutputEntry(verb, DreamOutputAction.COMMITTED, record.id, sources.map { it.id }, content, confidence, reason)
            }
            fun journaled(reason: String): DreamOutputEntry =
                DreamOutputEntry(verb, DreamOutputAction.JOURNALED, null, sources.map { it.id }, content, confidence, reason)
            val proposed = memoryOs.propose(proposal)
            return when (proposed.outcome) {
                MemoryOutcome.CREATED -> {
                    val record = proposed.memory ?: return journaled(DreamReasons.EVENT_CONFLICT)
                    val approved = memoryOs.approve(record.id)
                    when (approved.outcome) {
                        MemoryOutcome.APPROVED -> committed(approved.memory ?: record, DreamReasons.OK)
                        MemoryOutcome.STORAGE_FAILURE -> throw StorageAbort("approve failed: storage")
                        else -> journaled(DreamReasons.proposalRejected(approved.outcome))
                    }
                }
                MemoryOutcome.ALREADY_RECORDED -> {
                    val record = proposed.memory ?: return journaled(DreamReasons.EVENT_CONFLICT)
                    when (record.reviewStatus) {
                        MemoryReviewStatus.APPROVED -> committed(record, DreamReasons.ALREADY_RECORDED)
                        MemoryReviewStatus.PENDING -> {
                            // A crash between propose and approve on the first
                            // attempt: finish the commit the replay stands for.
                            val approved = memoryOs.approve(record.id)
                            when (approved.outcome) {
                                MemoryOutcome.APPROVED -> committed(approved.memory ?: record, DreamReasons.ALREADY_RECORDED)
                                MemoryOutcome.STORAGE_FAILURE -> throw StorageAbort("approve failed: storage")
                                else -> journaled(DreamReasons.proposalRejected(approved.outcome))
                            }
                        }
                        else -> journaled(DreamReasons.EVENT_CONFLICT)
                    }
                }
                MemoryOutcome.CONFLICT -> journaled(DreamReasons.EVENT_CONFLICT)
                MemoryOutcome.STORAGE_FAILURE -> throw StorageAbort("propose failed: storage")
                else -> journaled(DreamReasons.proposalRejected(proposed.outcome))
            }
        }

        /** Runs one actionable proposal through the gate and the dry-run/live split. */
        fun applyProposal(
            verb: DreamVerb,
            content: String,
            type: MemoryType,
            category: MemoryCategory,
            sources: List<MemoryRecord>,
            confidence: Int,
            groupKey: String,
        ): DreamOutputEntry {
            val live = memoryOs.read().snapshot ?: throw StorageAbort("ledger unreadable at apply")
            val decision = MemoryDreamGate.evaluate(
                verb = verb,
                content = content,
                confidence = confidence,
                sourceRecords = sources,
                fenced = isFenced(content, sources, live),
                currentStoredCount = live.memories.size,
            )
            if (!decision.allowed) {
                return DreamOutputEntry(verb, DreamOutputAction.JOURNALED, null, sources.map { it.id }, content, confidence, decision.reason)
            }
            if (entry.dryRun) {
                return DreamOutputEntry(verb, DreamOutputAction.WOULD_COMMIT, null, sources.map { it.id }, content, confidence, DreamReasons.DRY_RUN)
            }
            return commitOutput(verb, content, type, category, sources, confidence, groupKey)
        }

        try {
            persist()
            val snapshot = memoryOs.read().snapshot ?: throw StorageAbort("ledger unreadable")
            val eligible = MemoryDreamGrouping.eligible(snapshot.memories, clock())
            if (eligible.isEmpty()) {
                persist(finished = true, outcome = DreamRunOutcome.SKIPPED_INSUFFICIENT, finalNote = "no_eligible_records")
                return entry
            }
            val vectorMap = vectors() ?: run {
                persist(finished = true, outcome = DreamRunOutcome.FAILED, finalNote = DreamReasons.VECTOR_STORE_UNAVAILABLE)
                return entry
            }
            val grouping = MemoryDreamGrouping.formGroups(snapshot.memories, entry.watermarkBeforeMs, vectorMap, clock())
            val processedSeedIds = HashSet<String>()
            grouping.groups.filter { it.key in processedKeys || it.key in globalProcessedKeys }
                .forEach { processedSeedIds += it.seedIds }
            processedSeedIds += grouping.singletonSeedIds
            fun advanceFrontier() {
                for (record in grouping.changed) {
                    if (record.id in processedSeedIds) {
                        if (record.updatedAtMs > watermark) watermark = record.updatedAtMs
                    } else break
                }
            }
            advanceFrontier()
            persist()

            var judgedThisRun = 0
            for (group in grouping.groups) {
                if (group.key in processedKeys || group.key in globalProcessedKeys) continue
                if (judgedThisRun >= MAX_GROUPS_PER_RUN) {
                    note = "group_cap_reached"
                    break
                }
                judgedThisRun++
                groupsExamined++
                val output: DreamOutputEntry = when {
                    group.band == DreamBand.LINK_ONLY -> DreamOutputEntry(
                        DreamVerb.NO_OP, DreamOutputAction.NONE, null, group.recordIds, null, null, DreamReasons.LINK_ONLY)
                    group.allDerived -> DreamOutputEntry(
                        DreamVerb.NO_OP, DreamOutputAction.NONE, null, group.recordIds, null, null, DreamReasons.DERIVED_SOLE_SOURCE)
                    group.conservative && group.records.any { DreamVetoPatterns.hasContradictionSignal(it.content) } ->
                        DreamOutputEntry(DreamVerb.NO_OP, DreamOutputAction.NONE, null, group.recordIds, null, null, DreamReasons.VETO_CONTRADICTION)
                    else -> {
                        var judgeFailed = false
                        val judgment = try {
                            judge.judgeGroup(group)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            judgeFailed = true
                            null
                        }
                        when {
                            judgeFailed -> DreamOutputEntry(
                                DreamVerb.NO_OP, DreamOutputAction.NONE, null, group.recordIds, null, null, DreamReasons.JUDGE_ERROR)
                            judgment == null -> DreamOutputEntry(
                                DreamVerb.NO_OP, DreamOutputAction.NONE, null, group.recordIds, null, null, DreamReasons.JUDGE_OUTPUT_INVALID)
                            judgment.verb == DreamVerb.NO_OP -> DreamOutputEntry(
                                DreamVerb.NO_OP, DreamOutputAction.NONE, null, group.recordIds, null, judgment.confidence, DreamReasons.NO_OP)
                            judgment.content.isNullOrBlank() -> DreamOutputEntry(
                                DreamVerb.NO_OP, DreamOutputAction.NONE, null, group.recordIds, null, judgment.confidence, DreamReasons.JUDGE_OUTPUT_INVALID)
                            else -> {
                                val byId = group.records.associateBy { it.id }
                                val sources = judgment.sourceRecordIds.mapNotNull { byId[it] }
                                applyProposal(
                                    verb = judgment.verb,
                                    content = judgment.content.orEmpty(),
                                    type = MemoryType.SEMANTIC,
                                    category = group.category,
                                    sources = sources,
                                    confidence = judgment.confidence,
                                    groupKey = group.key,
                                )
                            }
                        }
                    }
                }
                outputs += output
                processedKeys += group.key
                processedSeedIds += group.seedIds
                advanceFrontier()
                persist()
            }
            persist(finished = true, outcome = DreamRunOutcome.COMPLETED)
            return entry
        } catch (cancelled: CancellationException) {
            // Leave the entry in-progress: the next firing resumes this night.
            persist()
            throw cancelled
        } catch (abort: StorageAbort) {
            persist(finished = true, outcome = DreamRunOutcome.FAILED, finalNote = DreamReasons.STORAGE_FAILURE)
            return entry
        } catch (failure: Exception) {
            persist(finished = true, outcome = DreamRunOutcome.FAILED, finalNote = "error:${failure.javaClass.simpleName}")
            return entry
        }
    }

    companion object {
        /** Plain-worker budget control: at most this many judged groups per run. */
        const val MAX_GROUPS_PER_RUN = 12
    }
}
