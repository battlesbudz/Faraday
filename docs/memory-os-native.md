# Native MemoryOS milestone

This milestone ports the reviewable local ledger from the original Jarvis OS MemoryOS work into the Android app. The upstream reference is [jarvis-os commit d8018e4b4ce263a9d03aef41cb864a66e45e331d](https://github.com/battlesbudz/jarvis-os/commit/d8018e4b4ce263a9d03aef41cb864a66e45e331d); the native branch is based on `audio-pr2` at `c20b5137d010aa2a99814bea4387201b485182c7`. The combined feature line is `feature/memory-os-v2`; it merges the build-768 conversation line with the existing audio/MemoryOS integration. APK and build status come only from the exact combined run receipt.

The feature content is combined with the pinned natural-routing audio snapshot `9b133b466cf8508618778ea41c25aefd36910737` (including audio snapshots `9ccaca5`, `d37eb0c`, `5643517`, and `9b133b4`) by a single-parent content import; it is not a Git merge.

## What is implemented

The native API is in `app/src/main/java/com/battlesbudz/jarvis/v2/memory/`:

* `MemoryModels.kt` defines bounded records, source/provenance, review state, outcomes, snapshots and packet results.
* `MemoryPolicy.kt` validates content, timestamps, confidence, provenance and restricted data. It uses conservative detectors for raw financial or identity data; it is not a blanket secret detector.
* `MemoryStore.kt` stores schema-versioned JSON with an atomic write, a per-file process lock, cleanup of store-owned interrupted-write artifacts on access, a 1 MiB encoded-store cap, record/tombstone caps, and fail-closed reads.
* `MemoryOs.kt` exposes `propose`, `approve`, `reject`, correction supersession, lineage deletion, generation/revision checks, listing, retrieval and bounded context packets.
* `MemoryRetrieval.kt` performs deterministic lexical token matching and emits JSON-quoted historical entries under a character budget.
* `AndroidMemoryOs.kt` binds the store to the app's `noBackupFilesDir/memory-os.json`; it uses no network and adds no model.

The lifecycle is explicit: a proposal is `PENDING`, a user can approve or reject it, and an approved correction supersedes its active target. A correction records `correctsMemoryId` and checks the target revision. Erasing a record erases its complete correction lineage and retains only opaque event tombstones for idempotency; content is not retained in the tombstone. Reused event IDs with different payloads are conflicts. Storage, schema, capacity, stale revision and unavailable-store failures are returned as outcomes/messages for callers to show.

`MemoryScreen.kt` is opened from the setup/model screen's **Memory** button. Its **Add** action opens a modal, and the screen separates Wiki, Review, and History. It supports add-for-review, approve, reject, correct, lexical search over approved records, category/topic placement, source inspection, derived links/backlinks, per-record erase, confirmed erase-all, and reload after Activity recreation. Finalized text and voice inputs enter the same conservative pending-review path; they are not approved or injected automatically. Pending, rejected, superseded, deleted, and expired records are absent from the wiki. Approved recall is local, quoted historical context with no tool authority. The restricted example in the release journey must remain rejected, and storage errors must remain visible instead of looking like an empty history. See `docs/memory-wiki.md` for the user-facing verification recipe.

## Retrieval and trust boundary

Only approved, non-expired records can match retrieval. A query must be non-blank and its limit must be 1–50. Matching is lexical and deterministic; confidence, then recency, then ID break equal lexical scores. Narrow deterministic patterns propose explicit remembers, preferences, names, and locations for review. There are no embeddings, semantic similarity, cloud retrieval, or model-based extraction in this milestone.

`contextPacket` applies a caller-supplied character budget and wraps each selected value in JSON quoting inside a clear historical-data header. The packet is a bounded model-context artifact. It is not an instruction, tool request, authorization, current-user message, or source of truth. Any future integration must route the raw current user request through action selection first, then add an approved packet only as untrusted historical model context. It must never let memory authorize a tool or override system, developer, safety, tool, or current-user instructions.

The current candidate exercises the finalized-input bridge and prompt builder through controlled acceptance surfaces. Integration must route the raw current request through action selection first, then add only the approved packet as untrusted historical context. Personal recall is local and expires with the approved record; expiry, correction, and erase must invalidate a seeded packet, as must model switching and retrieval/setup failures, with an explicit result. Memory must never authorize a tool. These paragraphs define the acceptance boundary; completion requires the exact combined-tree run receipt and review.

## Port parity and scope

The parity reference is the original server modules:

| Original module | Native counterpart | Native milestone boundary |
| --- | --- | --- |
| `server/memory/memoryOs.ts` | `MemoryOs.kt`, `MemoryModels.kt` | Local lifecycle and result contracts; no server callers or user account |
| `server/memory/writePipeline.ts` | `MemoryOs.kt`, `MemoryPolicy.kt`, finalized-input bridge | Explicit text/voice proposal and review path; no inferred/working/dream auto-approval |
| `server/memory/trust.ts` | `MemoryModels.kt`, `MemoryPolicy.kt`, packet header | Provenance and untrusted context shape; no cloud trust graph |
| `server/memory/retrieve.ts` | `MemoryRetrieval.kt` | Bounded lexical retrieval only; no embeddings or pgvector |
| `server/memory/contextBuilder.ts` | `MemoryRetrieval.kt` | Quoted bounded packet only; runtime prompt integration with mutation/expiry delivery fences |
| `server/memory/restrictedContent.ts` | `MemoryPolicy.kt` | Conservative local restricted-content checks |

Cloud embeddings/pgvector, G-Brain, extraction, people/SOUL/vault/dreaming, automatic review and multi-user sync are outside the port. Redis and Graphiti remain upstream plans. The upstream `rememberEpisode` gap is not silently dropped from an existing native feature: it has no native implementation in this milestone.

## Verification contract

Focused JVM coverage is present in:

* `MemoryPolicyTest`: restricted content/provenance, bounds and opaque source keys.
* `MemoryStoreTest`: reopen/commit behavior, failed writes, corruption and unknown schema.
* `MemoryOsTest`: source idempotency/conflicts, concurrent store instances, stale review and lineage deletion.
* `MemoryRetrievalTest`: lexical/expiry/review filtering and delimiter/injection-safe packets.

The release journeys are `test24_memoryManagerReviewsCorrectsSearchesAndErases`, `test25_finalizedTextAndVoiceMemoryNeedsApprovalBeforePromptUse`, `test26_memoryCorrectionAndEraseRefreshApprovedPacket`, and `test27_controlledConversationSurfaceKeepsCallUntilExplicitEnd`, listed in `scripts/verification/scenarios.json`. Together they describe the real Memory modal/review/wiki lifecycle, controlled finalized text/voice proposals that require an actual Review approval tap, category/topic organization, linked pages/backlinks, source inspection, rejected/pending search exclusion, correction/erase index invalidation, recreation persistence, quoted approved local recall, bounded typed follow-ups, and retaining one call across Voice, Chat and Memory until explicit end. They use controlled fixtures. Build 768 passed 685 JVM tests and 28 named journeys per emulator variant; the merged revision requires fresh CI. Activity recreation is UI persistence coverage; Android process death has not been tested here.

The combined revision requires code review and the full signed release gate: JVM, native/helper checks, normal API 30 sandbox, compact API 35 sandbox, and the consolidated exact-build receipt. Use the exact combined-tree run receipt for APK/build status. Do not infer microphone capture, ASR, TTS, model weights, physical audio, or device performance from these controlled journeys.

## Memory Conversations integration

The merge preserves both parents: MemoryOS/audio `7a72dfe1fe0577f94e9da580af9b0020f4173771` and Memory Conversations build 768 `649f58cfb5fd03197cf760db1ddf1d8fbc39dcde`. Only `feature/memory-os-v2` advances. The existing PR and other branches remain untouched.

The conversation implementation supplies finalized-input capture, one approved snapshot per ordinary turn, persisted history cutoffs, mutation/expiry delivery fences, persistent calls, and bounded typed/spoken follow-ups. The prior branch's reviewed local-personal-recall behavior is retained: matching approved personal facts bypass automatic references; explicit lookup and lookup confirmation retain references. Phone actions are authorized from the raw current request and receive no memory packet. An invalidated incremental input is closed and excluded from generation.

`test28_voiceNavigationRetainsCallIdUntilExplicitEnd` preserves the prior branch's navigation contract in addition to the stronger controlled production surface journey `test27`. The merged contract contains 29 journeys per emulator variant. `MemoryRecallIntegrationTest` verifies pending/approved/erased routing and explicit lookup/action precedence. Fresh combined-commit CI is required; historical parent passes are not combined-revision evidence.

## SQLite implementation checkpoint (2026-09-30)

Completed for the first phone-testable follow-on milestone:

- Introduced `MemoryPersistence` and a shared strict snapshot/record codec; the JSON store remains available for legacy migration and JVM regression tests.
- Wired `AndroidMemoryOs` to transactional, private `noBackupFilesDir/memory-os.db`. Memories and erase tombstones are canonical separate rows, with indexed review/expiry columns and one committed generation. Only changed rows are written.
- Added validated one-time schema-1/schema-2 JSON migration, preserving IDs, immutable fingerprints, review statuses, revisions, correction references, wiki organization, provenance, expiry and erase tombstones. Retire the old JSON/owned temporary files only after durable validation. A committed migration marker prevents a surviving stale JSON from restoring deleted memories.
- Added fail-closed handling for corrupt migration, unknown SQLite versions and write failures. Erase either commits the lineage removal/tombstones/generation together or leaves the previous snapshot intact. Secure-delete is enabled and long-lived WAL is disabled; this does not promise forensic erasure from flash storage.
- Removed the production JSON 1 MiB serialized-file ceiling. Existing 500-record and 2,000 combined record/tombstone caps remain intentionally enforced pending measured scale work; reads/lifecycle operations still use a full snapshot.
- Moved the production Memory wiki release journey onto SQLite and added migration/reopen, rollback/future-schema, capacity/concurrency Android acceptance. The named release contract has 33 tests per variant, plus the existing complete JVM/native/helper gate.
- Added a Memory-branch-only GitHub prerelease job gated by signed build, both complete emulator suites and the exact-build receipt, to deliver the requested phone APK without opening or merging a PR.

Validation: the signed candidate is handed off only after its exact-revision JVM/native checks, both Android suites and consolidated CI receipt pass. Build/run evidence is linked from the GitHub test release. Controlled Android fixtures exercise real SQLite and the production UI, not model-generated extraction or phone hardware. The handoff will name the signed APK build/run and remaining physical-device checks.

Still to implement: source-event/episode storage and 90-day searchable archives; secret sanitization before archive writes; lock/sensitivity and inference semantics; automatic local extraction; embeddings and hybrid ordinary-turn retrieval; temporal entities/relations and GBrain projections; tappable reply memory badges; SMS/MMS/email/Messenger/calendar/contact adapters; proactive follow-up scheduling; indexed queries, scale benchmarks and downstream invalidation. SQLite is the canonical storage foundation for those tables, not their implementation. The existing conservative review/exclusion behavior remains in this APK until the new policy gates and extraction pipeline are complete.

Phone acceptance for this checkpoint: install the normal signed APK as an update; open Memory, inspect existing approved/pending/history entries, add and approve a new fact, organize/correct it, close and reopen Jarvis, verify approved lexical recall, and erase it to check it stops ordinary recall. Do not uninstall first if testing migration: this selected device-only design does not restore memory after uninstall.

## Source archive checkpoint (next implementation step)

Implemented following the SQLite foundation; handoff requires the exact signed CI receipt:

- SQLite schema 2 adds source events with opaque event/conversation/call keys, original capture time, exact 90-day expiry, bounded text and immutable replay fingerprints. The additive schema-1 upgrade preserves the fact ledger and tombstones in one transaction.
- Production finalized text/voice capture now archives eligible input even when it contains no deterministic fact. Drafts, failed recognition, invalid/future timestamps and oversized inputs are rejected. Detected passwords, payment identifiers and short access codes exclude the whole event before an archive write; the full bounded input is checked, including text past 2,000 characters.
- Source reads have a separate explicit-history API. All source text is treated as personal and requires the current device to be unlocked in this checkpoint; lock is checked before and after the query. These sources do not enter ordinary recall, approved packets, model prompts or proactive notifications.
- Read/write paths remove expired text. A daily WorkManager maintenance job retries cleanup without a model; Android may delay it, so access-time filtering independently enforces expiry. Opaque expired-event metadata remains to prevent retries from renewing the retention window. Useful approved facts survive source expiry.
- Erasing a saved fact retains its source text within the original window for explicit history access, while the existing fact tombstone prevents replay from recreating it. This is not yet duplicate-source or inferred-derivative suppression for the future extraction/index pipeline.
- Archive capacity is bounded to 20,000 source-event metadata rows and 16 MiB of live UTF-8 text, with 32,768 characters per event and at most 50 results per explicit query. A full archive reports failure instead of dropping prior sources. Lexical history search is a literal substring baseline, not a semantic index or scale qualification.
- Added six JVM policy/boundary cases and Android `test33`/`test34` for source capture, source/fact separation, erasure, exact expiry, reopen, lock races, v1 migration, secret exclusion, duplicate/conflict handling and write rollback. The next release contract requires 35 journeys per variant.

Still required: user-facing history search and source badges; privacy-safe migration/retention of the separate existing `ConversationHistory` copies; broader secret-detection qualification; sensitive fact/inference states and all output delivery fences; durable extraction jobs; duplicate-source suppression; external source adapters; embeddings, graph and proactive integration. This checkpoint does not claim complete app-wide secret exclusion or transcript retention: the new archive is protected, while the existing chat/history copies need the next privacy integration. The detector is a conservative bounded baseline, not a guarantee for unlabeled or obfuscated secrets.

Device-lock API reference: https://developer.android.com/reference/android/app/KeyguardManager . Use current `isDeviceLocked`/`isKeyguardLocked`, not a since-boot unlock flag.

## Plan to finish MemoryOS: local capture, semantic recall, and temporal memory

**Status:** SQLite and source-archive foundations implemented; release verification is tracked in the acceptance map and exact-build receipt. Remaining capabilities are planned. The original milestone above retains manual review and lexical retrieval; Android production storage now uses the SQLite checkpoint above. The decisions below were confirmed by Justin during the 2026-09-29 interview (America/New_York); they replace the older follow-on proposal that every extracted or inferred fact must wait for manual review. They do not claim that automatic capture, embeddings, or the temporal graph already work.

**Goal:** Jarvis should automatically retain useful information and recall relevant context during ordinary Chat and Voice turns, including paraphrases, implied references, and point-in-time questions. Memory, extraction, indexing, and inference run locally on Android. A hosted memory service is not required. Optional access to incoming external sources is separately authorized; saved local memory remains usable offline.

### Confirmed user behavior

| Area | Decision |
| --- | --- |
| Ordinary personal facts | Save automatically; Justin can inspect, correct, or delete them. |
| Sensitive facts | Automatically save relevant health, financial, and family facts. Sensitivity controls access rather than forcing every fact into review. |
| Secrets | Exclude passwords, payment card numbers, and access codes entirely from memory, including retained source text, indexes, summaries, and memory diagnostics. |
| Inferences | Save and recall supported conclusions as explicitly tentative inferences; do not present them as directly stated or confirmed facts. |
| Source conflicts | Prefer what Justin explicitly told Jarvis over conflicting outside information when reconciling personal memory. Retain the outside assertion with its source and disagreement, rather than silently overwriting Justin's statement. |
| Changes over time | Keep prior values as dated history when a fact changes. Distinguish a genuine change from a correction of an erroneous fact. |
| Jarvis conversations | Capture finalized text and voice transcripts. Keep source text searchable for 90 days; retain useful saved facts afterward. This does not authorize retaining raw voice audio. |
| Incoming sources | Include SMS/MMS, email, Facebook Messenger, calendar, and contacts after the user grants access. Collect relevant new information automatically. WhatsApp, Telegram, and standalone document ingestion were not selected for this initial scope. |
| Screens and other notifications | Capture only during approved phone tasks. The separately authorized message-source adapters may receive new message events in the background. |
| Initial message history | New messages only after connection; do not backfill existing SMS, email, or Messenger history. |
| Message evidence | Jarvis's local copies of message text expire after 90 days; useful saved facts remain. This retention rule does not delete messages from their original apps or services. |
| Locked phone | Require unlocking before speaking, displaying, or delivering sensitive details to model context. Non-sensitive personal recall can remain available. Sensitive notifications use a generic preview until unlock. |
| Recovery | Keep memory only on this device. No memory backup, export/import, multi-device sync, or remote recovery is included in this selected scope. |
| Delete a saved fact | Remove the saved fact and its derived records while preserving source text for the remainder of its 90-day retention window. |
| Recall after deletion | Retained text supporting a deleted fact can surface only when Justin explicitly asks about that history. It is excluded from ordinary automatic recall and cannot automatically recreate the deleted fact. |
| Explainability | Replies using memory show a small tappable memory/source badge with the supporting records, source, dates, and uncertainty. |
| Proactive use | Relevant follow-ups may produce notifications outside conversations. Memory emits evidence-backed events to the existing autonomy runtime; it does not create a second agent or independently authorize actions. |

These are implementation requirements, not grants to connect accounts or enable Android permissions now. Account and source permissions are selected in setup. A source that is unavailable, incomplete, or revoked must expose that state rather than appear connected and empty.

### 1. Define scalable canonical storage and policy states

The SQLite checkpoint now replaces the 1 MiB JSON ledger as Android production authority and includes validated legacy migration. Complete Memory OS still requires the additional canonical source, graph, queue and projection tables below, plus indexed reads and measured scale limits. Keep the original local ledger recoverable until migration verifies; do not silently drop records at the old capacity limit.

Separate canonical records from rebuildable projections within the storage design:

- source events and episodes, with source IDs, capture time, source/event time, bounded sanitized text, sensitivity, and an explicit raw-text expiry;
- fact assertions and revisions, with subject/topic, kind, value, source support, statement versus inference, confidence, acceptance origin, validity, and correction lineage;
- entity identities and aliases, typed relationships, dated events, goals/commitments, and applicable procedural records;
- source-to-fact dependencies, deletion/suppression tombstones, adapter cursors, and durable extraction/index-maintenance jobs;
- derived G-Brain-style pages, chunks, links, timelines, page versions, lexical indexes, and vectors.

Automatic acceptance under the interview policy must be a distinct recorded origin; it must not impersonate an actual manual approval tap. Keep manual review available for user edits and unresolved cases. Pending/rejected candidates are not eligible facts. Accepted assertions, usable tentative inferences, historical facts, corrected errors, and deleted facts require separate query eligibility rules. Sensitivity is independent of those states.

Use transactions for canonical writes, revision changes, and durable queued work. Tag every derived row with canonical IDs/revisions, content hash, index format, and embedding model/version. Reject stale projections during reads. Define idempotent recovery after process death, interrupted migration, low storage, or a partial rebuild; an async indexing failure cannot lose the canonical memory.

Rebuild projections from retained canonical facts and source metadata after raw episode text expires. Keeping useful facts beyond 90 days must not depend on retaining their original transcript forever. A source badge must honestly show when its original excerpt has expired.

### 2. Capture and extract locally

Persist a sanitized source event before queuing bounded local extraction. Extend capture beyond the existing narrow remember/preference patterns. Use the app's selected local reasoning model as the first extraction candidate, beginning with the established Gemma E2B configuration; the extractor must remain replaceable through the runtime. EmbeddingGemma is a retrieval model, not the fact or relationship extractor.

The extraction contract must distinguish:

- statements by Justin, attributed statements by another person, and direct evidence from an authorized source;
- a present fact, historical event, intention, goal, commitment, preference, or procedure;
- negation, hypothetical discussion, quoted material, jokes, and uncertain speech transcription;
- a supported tentative inference versus an asserted fact;
- a changed value versus a correction of information that was wrong.

Assistant-generated answers must not become independent evidence of facts about the user. Preserve source spans and attribution so an extraction can be inspected and corrected. Automatically accepted sensitive facts remain sensitive throughout derived summaries, graphs, and context packets. Secret exclusion occurs before persistent source capture and before producing embeddings or memory summaries.

Extract in resumable batches that yield to active chat, voice, and tool work. Do not add a second blocking language-model pass to every reply without physical-device evidence that its delay is acceptable. If extraction cannot run, retain the eligible source event until its normal expiry and show queued/degraded status. The app must remain conversational while indexing or consolidating.

### 3. Qualify the local embedding runtime

Use **EmbeddingGemma 300M** as the first Android candidate. Google's current Text Embedder documentation supports the EmbeddingGemma 300M task artifact on Android through MediaPipe Tasks Text, including `TextFormatContext` for retrieval query/document roles. Use the app's Kotlin integration and a pinned, reviewed model artifact; do not send memory text or queries to a hosted embedding service.

The model overview describes a 308M multilingual model, 768-to-128 output dimensions, and on-device/offline use. The Android task artifact has a 512-token sequence limit, even though the general model overview describes a longer context; verify tokenization and chunking against the exact artifact. Benchmark 768- and smaller-dimensional output, plus float and scalar-quantized vectors, against the same recall suite before choosing the stored representation. Precompute document vectors when a memory becomes eligible; only the current query needs to be embedded on a normal turn.

Check warm and cold inference, peak memory, model storage/download size, battery, and voice-turn latency on the target Galaxy Z Fold 6. Google's published Text Embedder table reports 200 ms CPU latency on a Samsung S26 Ultra, which is a reference point, not a Fold 6 result. Choose whether the artifact is bundled or explicitly acquired once on device after measuring APK/storage impact; the resulting recall path must work offline. Audit dependency telemetry/network behavior and confirm no memory or query text leaves the device. Review Gemma Terms before commercial distribution. If EmbeddingGemma fails the device or licensing gates, benchmark BGE-small-en-v1.5 with ONNX Runtime Mobile as the English-only fallback; it needs its own tokenizer/pooling integration and quality test.

Measure the embedding runtime alongside the actual selected language model, ASR, TTS, and active voice session. Include residency/eviction, CPU/GPU contention, thermal behavior, and low-memory recovery; a standalone embedding benchmark does not establish complete-app performance. Document the corpus sizes used for exact cosine search, and choose an ANN index only when the measured corpus/latency limit requires it.

### 4. Build autonomous hybrid retrieval and context assembly

Run retrieval for ordinary conversational turns. Define the pipeline precisely: the raw current request establishes intent and the permitted action boundary; memory is then retrieved before final response planning and argument generation. Kotlin policy and the Tool Gateway enforce authorization from the current request. A remembered preference, source message, or tentative inference cannot grant permission for an action.

Use a bounded recent-conversation window to resolve references, with an explicit query planner for exact recall, semantic recall, relationships, temporal questions, and goals/commitments. Do not let an earlier topic dominate the embedding for the current question. Preserve exact/lexical matching for names, dates, and rare terms, and combine it with dense similarity and bounded graph expansion.

Retrieve canonical facts and G-Brain projections as peer candidate sources; retain canonical authority and source/chunk/page provenance when deduplicating. Use RRF or equivalent rank fusion for candidate combination, then separately calibrate relevance filtering. A fused rank score is not a probability that a memory is relevant. Set candidate limits, graph traversal limits, reranking rules, and inclusion thresholds from a fixed evaluation corpus.

Use two runtime-enforced query modes:

- **Ordinary context:** eligible current facts, appropriately dated historical facts when the question calls for them, labeled tentative inferences, and eligible recent episodes. Suppress source spans and derivatives associated with deleted facts.
- **Explicit source-history search:** the user explicitly requests the retained original history. This can return surviving source text about a deleted saved fact, clearly attributed as historical source material. It still applies secret, sensitivity, retention, and unlock restrictions. It does not restore the saved fact.

An inferred retrieval intent or instructions inside a stored source must not bypass the explicit-history restriction. If the user's request is ambiguous, clarify rather than automatically revealing suppressed source text.

Build a small evidence packet using the actual selected model's token budget, with dates, source IDs, statement/inference labels, and uncertainty. Retain JSON quoting and the untrusted historical-data boundary. If no evidence clears relevance thresholds, send no memory context. If retrieval is unavailable, report degradation and use the existing lexical fallback under the same policies; do not label failure as an empty memory.

### 5. Implement G-Brain and temporal graph behavior natively

Garry Tan's upstream [GBrain](https://github.com/garrytan/gbrain) remains a design reference for pages, compiled facts, evidence timelines, typed links, hybrid search, and graph expansion. Jarvis V1 implemented its own derived Postgres projection; the [V1 G-Brain plan](https://github.com/battlesbudz/jarvis-os/blob/main/docs/gbrain-implementation-plan.md) records pages/chunks/links/versions, canonical-plus-derived retrieval fusion, provenance, hosted embeddings, and optional pgvector. V2 preserves canonical-versus-derived authority while replacing the server runtime with local storage and models.

The local projection must include source-linked pages, lexical/vector chunks, explicit typed links/backlinks, dated timelines, and source-revision-linked page versions. Remove or regenerate all derived versions that expose a deleted or corrected fact. Vector similarity alone cannot create a factual relationship.

The [V1 Temporal Graph plan](https://github.com/battlesbudz/jarvis-os/blob/main/docs/memory-os-temporal-graph-plan.md) lists its Graphiti adapter and full temporal-query experience as later work. Its time-phrase parser is not a stored knowledge graph. Graphiti is a temporal-data/retrieval design reference for V2; qualify the needed behavior as native Kotlin/SQLite rather than making a Python graph service part of the offline core.

Specify versioned subject-predicate-object assertions and timeline events with both recorded time and valid time, source support, confidence, sensitivity, and statement/inference labels. Preserve approximate/unknown dates, source timezone, user-local query timezone, intervals, and repeated occurrences. Do not invent a validity date just because an extraction ran today.

Define entity resolution before graph ingestion: stable IDs, aliases, ambiguous same-name people, and reversible merge/split operations. Do not merge people based on a name match alone. Specify relationship types and cardinality, plus query/traversal limits.

A changed fact closes the prior applicable validity interval and adds the new state while preserving dated history. An erroneous fact is marked corrected and must not be described as having been true previously. Outside assertions remain attributed and cannot silently overwrite Justin's explicit personal-memory statement. Historical queries may retrieve prior valid states; ordinary current-state queries must not present superseded states as current.

### 6. Implement retention, forgetting, and privacy across every copy

Source text expires 90 days after capture. Enforce expiry in every read path even if Android background maintenance is delayed. Purge raw text and raw-text-derived archive indexes/caches, while retaining eligible saved facts, fact history, and minimal provenance needed to explain them. Do not silently refresh the retention clock by rereading or summarizing an old source.

Deleting a fact removes its canonical saved content and all normal-recall vectors, pages, graph edges, summaries, cached packets, and model-context references derived from it. Preserve supporting source text only within its remaining retention window and only for explicit history search. Persist content-free suppression metadata tied to source IDs/spans and fact lineage so maintenance/re-extraction cannot resurrect the deleted fact. Qualify duplicate-source matching and deletion propagation before claiming this guarantee.

Use the same revision/delivery fences for edits, erasure, expiry, model switching, and device lock changes. Test deletion or locking during retrieval, generation, displayed streaming output, and TTS. A packet that was valid when built is not sufficient authority to reveal sensitive or deleted information later.

Keep stores, sidecars, archives, and temporary files in app-private storage excluded from backup and transfer. Qualify encryption at rest and local Android key handling; no external memory account or recovery service is part of this scope. Enforce the actual current device-lock state, not merely whether the phone has been unlocked once since boot. Sensitive source snippets, facts, prompts, badges, and notification previews must all obey the unlock rule.

Consolidation may deduplicate, summarize, and lower the relevance of stale records while retaining source lineage and tentative-inference labels. It cannot upgrade an inference to a confirmed statement or silently destroy saved facts/history. Run maintenance opportunistically with durable jobs; required privacy/expiry rules must also be enforced synchronously on access.

### 7. Add authorized source adapters and autonomy integration

Implement new-event adapters for SMS/MMS, email, Messenger, calendar, and contacts, plus capture from approved task screens/notifications. Establish consent, per-source/account scope, stable IDs, startup cursors, edits/deletions, reconnection, duplicate delivery, and revocation behavior. Initial message connections establish a baseline without importing old messages.

Qualify actual Android permission/role requirements and the available access method for each source. Notification previews may be truncated, missing, or unavailable; they must not be represented as complete message bodies or complete coverage. Email providers/accounts are chosen during connection; assess the supported provider adapter and access scopes before promising its availability. Source-network access and on-device memory processing are separate boundaries; never send stored memories or recall queries to hosted embedding/LLM services.

Memory updates may emit deduplicated evidence-backed follow-up events to the shared autonomy runtime on feature-tools. Integrate with the separately established runtime behavior: proactive review on new information, chat plus notification without speech when no voice call is active, silent immediate notifications during Do Not Disturb, and unloading the model below 20% battery while unplugged until interaction or charging. Memory capture/index jobs must coordinate with that model lifecycle and prioritize foreground conversation. Read-only preparation follows permitted-tool rules; consequential actions still require the existing authorization and confirmation policy.

A memory-driven notification must respect deletion suppression, source uncertainty, relevance, sensitivity, device lock, and source permission revocation. Show the same inspectable memory/source evidence badge in the associated chat entry. The memory branch must not create an independent tool executor or permission system.

### 8. Verification and implementation sequence

Implement and verify in dependent milestones:

1. Scalable canonical storage/migration, policy states, local event capture/extraction, secret exclusion, 90-day retention, and forgetting/unlock fences.
2. Local embeddings, rebuildable hybrid indexes, every-turn retrieval, token-budgeted context, and source badges.
3. G-Brain projections, entity resolution, temporal graph/history, relationship queries, and local consolidation.
4. Source adapters and event-driven proactive integration, with an explicit capability/coverage receipt per source.

Update the repository acceptance map and journeys as behavior actually lands; the historical review-only milestone tests are not evidence for this new contract. Follow the repository's Jarvis verification workflow for code changes and APK candidates.

Use a privacy-safe fixed golden corpus plus held-out cases to evaluate capture precision, missed facts, extraction attribution, entity resolution, date/interval correctness, Recall@5, ranking quality, answer grounding, irrelevant-memory injection, and inference labeling. Include negatives, paraphrases, coreference, conflicting sources, changed versus erroneous facts, deleted facts still present in raw archives, and no-relevant-memory turns. Fix numeric quality and latency thresholds after establishing the baseline and before release evaluation; do not choose them retrospectively to pass a build.

Require zero lifecycle/policy failures: secrets never persist; sensitive content never appears while locked; pending/rejected/deleted facts never enter ordinary context; deleted supporting source text appears only in explicit permitted history searches; corrected errors do not become past truths; legitimate prior states remain available for dated questions; expiry holds even when cleanup is delayed; and memory never grants tool authorization.

Test first connection without backfill, source disconnect/revocation, duplicate deliveries, process death, low storage, interrupted migrations/rebuilds, model-version/dimension changes, unavailable models, context invalidation, and retained-source re-extraction after deletion. Verify the offline core and content-free diagnostics.

Measure actual-model performance on the Fold 6 with real voice capture and simultaneous selected model/ASR/TTS workload, warm/cold retrieval, corpus growth, peak RAM, battery, thermal effects, and app responsiveness during indexing. Keep API 30/API 35 emulator journeys for integration coverage, and record exact-build signed release evidence. Neither fixtures nor emulator passes prove physical audio, real extraction quality, or target-device performance.

### Remaining engineering qualification

The interview settles the user-facing rules. Implementation still requires evidence for the exact embedding artifact/runtime and extraction prompts; additional SQLite schemas, encryption and scale policy; entity and relationship schema; deletion-suppression matching; numeric relevance/quality/capacity/latency thresholds; and SMS/MMS, email, Messenger, calendar/contacts access and coverage. Research and bounded device/source spikes should resolve these choices. Do not reopen already answered approval, retention, source scope, backup, or recall questions to avoid the engineering work.

### Research references

- [EmbeddingGemma model overview](https://ai.google.dev/gemma/docs/embeddinggemma)
- [MediaPipe Text Embedder guide](https://developers.google.com/edge/mediapipe/solutions/text/text_embedder)
- [EmbeddingGemma Android task artifact](https://huggingface.co/litert-community/embeddinggemma-300m)
- [BGE-small-en-v1.5 model card](https://huggingface.co/BAAI/bge-small-en-v1.5)
- [ONNX Runtime Mobile](https://onnxruntime.ai/docs/get-started/with-mobile.html)
- [Graphiti official repository](https://github.com/getzep/graphiti)

Proactive scheduling clarification: a known relevant deadline may schedule a local reminder without another incoming message. New-information review remains event-driven; avoid periodic model polling. This scheduling behavior is planned, not part of the SQLite APK.
