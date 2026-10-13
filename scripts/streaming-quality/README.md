# Hosted full-Gemma E2B native quality prerequisite

## Same-host observable equivalence proposal — 10 October 2026

The versioned acceptance contract is
`same_host_original_static_stateful_observable_equivalence_v1`. It compares the
unchanged original static encoder plus original adapter, independently invoked
from sections of the pinned complete public bundle, with the independently
reassembled stateful encoder. The same host, native binary, CPU-thread setting,
full PCM/Mel fixture and original model identities bind both paths. All 77 by
1536 valid post-adapter float32 values, including the final row and every chunk
seam, must match bitwise; counts, masks and learned EOA must match exactly.
No numeric tolerance or accepted output-hash list is introduced.

The earlier `e51d19c6…` projected-row fingerprint remains recorded, together with
the actual same-run fingerprint and a factual match boolean. Its explicit role
is `fingerprint_diagnostic_only`: it is not a cross-CPU release oracle and does
not imply that CPU hardware caused a difference. A differing historical digest
cannot suppress a same-host mismatch. The original static/adapter path and full
raw Conversation embedding tap remain independently invoked controls, but shared
LiteRT/XNNPACK backend bugs can affect both paths and escape their equality
check. Independent expected semantics are limited to the one public phrase
reference; this is not broad model validation. All source, input, model,
reassembly, SDK, binary and dependency identities remain fail closed.

Normal success now requires the existing pure validator to inspect all 131
emitted outputs for exact schema/size, legal booleans and float finiteness. All
98 state outputs receive their declared invariants: history counts
`[12,24,24,24,24,24,24]`, exact last-four-valid-Mel retention, all twelve retained
layer prefixes and byte-exact positive-zero invalid tails. These are validity
and transition checks, not independent equality of newly calculated cache rows.
Both cache-reference-equivalence claims remain false. A synthetic finite
mutation confined to a final newly appended cache value demonstrates this limit:
it may pass the declared invariants and observed row equality. Pre-adapter
reference equality, all-layer cache reference equality and other audio fixtures
are outside this property. Invalid padded projected rows must be finite but
are not compared for byte equality.

Every normal run now requires live same-invocation reassembly, frontend and
four-model process witnesses, exact fixture manifests and complete native output
inventories. Pinned file identities include model sections and full source bundle,
fixture inputs, prerequisite receipts and all emitted outputs. They are rechecked
before each encoder stage and before/after each full-model lane. A serialized
receipt cannot grant execution admission. Each lane uses its actual same-run
projected hash, and its returned identity, lifecycle, embedding tap, positive
counts and output controls must pass before another lane starts. The first
lane's request/result/process receipts remain pinned through the second lane.
Every lane remains a separate full-E2B process, engine and Conversation, with
unchanged compute admission, resource limits, cleanup and artifact restrictions.

The release binder requires the exact new contract in summary, oracle, comparison and input
identity, checks detailed row/state/fingerprint facts and their hash bindings,
requires executed/cleaned-up prerequisites, verifies each lane's expected input
and runtime identity, and recomputes pair/public-phrase acceptance from the lane
results. Prior failed summaries and diagnostic exports remain ineligible, even
if their diagnostic decoder pair happened to pass. This is a new narrow
acceptance property; old failed receipts stay failed under the policy that
produced them. This local source-only proposal has run synthetic Python checks
only. It has not rebuilt native probes, executed weights, verified an APK or
established Android/device quality, broad speech quality or cross-host numerical
equivalence.

## Historical diagnostic compatibility

The old `--diagnostic-on-known-reference-mismatch` spelling is retained for
invocation compatibility. The current oracle never raises a historical-hash
failure, so this flag does not change the new acceptance property. Existing
legacy failure-producer helpers retain their live single-use ticket, immutable
prerequisites and failed-summary/exit semantics. Their tests explicitly inject
the old failure producer; they are not assertions about current normal success.
There is no receipt replay/resume interface. The fixed-schema 8 KiB historical
`diagnostic-pair.json` reader/export restrictions remain so old diagnostics can
still be interpreted, but such an export cannot supply a release pass. No old
artifact is rewritten or relabeled.

The optional encoder capsule has a separate bounded public diagnostic-status
receipt. It retains only fixed phase/error codes, reviewed module/line and scalar
producer/process observations; private logs and exception strings stay excluded.
The exporter validates its strict 8 KiB schema, and final release binding requires
complete checked cleanup for the exact quality producer. Failed capture remains
optional after cleanup; it never changes numerical/model acceptance.

Compiler depfiles may contain in-root parent components and Bazel virtual include
aliases. Collection normalizes only after selecting the original trusted root,
then verifies canonical repository/system confinement before reading bytes. A
virtual include may use that same repository's source or generated output, never
another repository or a private path. Unrecognized compiler roots remain blocked.

Status: Builds 1117 and 1127 passed the Android SDK stage but reached the Linux
probe compiler deadline before full-model execution. Build 1127 reached 6,416 of
6,477 actions with continuing progress and no memory-pressure failure. **No
full-model pass is claimed.** Every input and receipt below is regenerated in a
new hosted directory; no earlier native fixture result substitutes for this gate.

## Minimal integration

The authoritative integration is `.github/workflows/android.yml`. The independent
quality job first selects the successful SDK producer's exact artifact and checks
the AAR, provenance and source-receipt hashes, including their run/source/attempt
identity. `prepare_sdk_source.py` uses the sibling streaming-sdk helpers to
reconstruct the exact official SDK commit, reviewed patch and both recorded
overlays. No Android binary cache transfer or model download is needed for that
source preparation. Copying this directory alone is insufficient: keep the
reviewed SDK helpers and source inventory alongside it.

The host probes build from that independently verified source checkout. Bazel
batch mode exits before any model download, encoder reconstruction or native
inference. The pipeline first verifies a fresh worktree recreated from the exact
SDK commit + reviewed source PATCH + exactly the Android API30 and Android-only
owner linkstatic overlays equals the actual Android source tree. Then it installs
a separate hosted experimental package, hashes every SDK source/config input,
checks official pinned Git-LFS host libraries, compiles three tiny probe targets,
and hashes the resulting binaries and resolved dynamic dependencies. Source and
runtime identities are rechecked before both Conversation lanes.

A passed quality job remains mandatory for the consolidated verification receipt
and every release publication. Signed APK assembly runs in parallel after the
successful SDK producer; its early artifact is explicitly unverified. Build,
download, source/hash, numerical, native-execution, evidence, semantic-reference,
and resource failures all leave the job failing. There is no continue-on-error,
retry, automatic budget expansion, skipped-pass, or legacy-model substitution.
Failure receipts do not make an early APK quality-approved. Keep all preexisting
app checks. The aggregate source-build/quality ceiling remains 160 minutes:
45 for the SDK producer, then 115 for quality. Host compilation admits four CPUs
only with at least 8 GiB available RAM and four schedulable CPUs, watches at most
6 GiB process-tree RSS and a 2 GiB system reserve, and has a 60-minute deadline.
The compiler and diagnostic limits are separate from the fixed model profiles
below. A bounded compiler-only
tail and resource/action progress are retained even if compilation fails.

## Fresh input chain

- Official LiteRT-LM 0.16 commit 924e79c91542761242244e4f1651851f822e4cbb;
  its WORKSPACE LiteRT pin 0ff28117f1cb5556d0e015bf80b773f74e2bee51.
- Full public bundle from the official HF revision
  6e5c4f1e395deb959c494953478fa5cec4b8008f, 2,588,147,712 bytes,
  SHA256 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c.
  The complete file hash is checked on download, before section extraction,
  by the reassembler, and freshly before each Conversation lane.
- Exact official public roses-are.wav, 295,404 bytes, SHA256
  9e42e31cbc41cb3c31dc4ddb1dc05fef322c61b98cc03a38c06749b2db94f6b2.
  NumPy2.3.5/SciPy1.17.0 reproduce the existing resample operation into all
  49,221 Float32 mono16kHz samples; expected SHA256
  f57adbb58a9a9ce6f198a56166b8f8f8021ff0073f7a54fc2c5171161f933c12.
  A float WAV copies those exact bits, SHA256
  6438b41f257e31bfdd94148bd9d805a7845ed27a5dfd57d774f1c0ff3ef3cb7b.
- The current full SDK MiniAudio frontend checks WAV decoding against those PCM
  bits, then independently preprocesses WAV and PCM, compares the complete Mel
  arrays bitwise, and writes fresh307x128 Mel bytes. Python requires SHA256
  243f69cbf70e632fd44535c33ba7f8d01fb3e9907e708880d550a5c5d3cdd851.
- Original encoder/adapter/EOA sections are copied from that full bundle with
  pinned offsets, sizes and hashes. The existing audited Java source and220,198
  structural-only asset bytes reconstruct the103,668,112-byte explicit-state
  encoder, SHA256
  d5c50b140ace235717e6713d287e73ccfa4f32d0090e1cceb9d00714da850a1b.
  Learned source payload is absent from the recipe assets. Reassembly and model
  derivative license scope are documented in recipe/; no reconstructed model is
  copied into the source tree, artifact upload, or APK assets.
- A same-pinned-native-runtime oracle runs original static encoder+adapter and
  the reconstructed stateful encoder across48,48,48,48,48,48,19 Mel-frame steps.
  It checks all emitted outputs and the declared state invariants, counts/masks,
  all77x1536 valid post-adapter rows bitwise, and learned EOA equality. The
  historical projected prefix SHA256
  e51d19c68f19e02ea3075720674932ddcc83948f8e4c8b507047e055b06f93e9
  is diagnostic only. Padded-row equality and freshly computed internal-layer
  cache reference equality remain outside this observable-equivalence contract.

All pinned source/input/model/runtime hashes are fail-closed checks. The actual
projected digest is a same-run identity, never an expected numeric answer. model-structure.json is fixed tensor schema/section metadata for
those hashes, not a borrowed inference receipt. The native runner also checks
actual signature names, tensor types, shapes and byte sizes.

## Pair and semantic scope

Fresh native C++ processes run projected_null, then raw, each with a fresh engine
and Conversation, same complete PCM, same prompt and context640, max64 output
tokens, CPU1, greedy sampling, thinking/speculation/tools disabled. The projected
lane supplies only77 post-adapter rows, no learned EOA; the runtime owns its EOA.
Both lanes must invoke the post-adapter callback exactly once with all77 rows
bitwise equal to the fresh oracle, consume positive prefill/decode tokens, finish
below the decode cap, and pass checked drain/delete. The comparator requires
identical full response JSON, text, tool-call arrays, counts and all identities.

In addition, both transcripts must match "Roses are red, violets are blue" after
case/punctuation/whitespace normalization. The reference is the official Google
documentation's example output for this exact public audio. It is **not a new
human listening annotation**. An equal but wrong transcript fails this reference
check. A pass is one narrow native transcription prerequisite. It is not an
Android/JNI full-model test, a human-annotated benchmark, an action-intent test,
or a phone-performance result. No tools/actions are dispatched.

## Resource and evidence policy

Reassembly, frontend and encoder prerequisites keep their hard4GiB address-space,
3.5GiB sampled aggregate-tree RSS and minimum5GiB effective available-memory
limits. Only the hash-bound full-E2B Conversation control has hard6GiB per-process
address space, sampled4GiB aggregate whole-tree RSS and minimum6GiB effective
available memory. Effective availability is the minimum of host MemAvailable and
visible cgroup-v2 ancestor headroom. Both profiles retain CPU affinity1, thread
hints1, 240s wall,239s CPU soft/240s hard,128MiB regular-file output and1GiB live
reserve. Reassembly retains Java128MiB heap and small JVM reservations.

The full-E2B profile is fixed and accepted only for the verified Conversation
probe, two known lanes, context640, max64 output and required embedding tap.
There is no generic caller-selectable budget override. Each lane uses a fresh
process/engine/Conversation with no persistent model cache. Context640 is the
smallest128-aligned capacity that fits the selected prefill128 local-attention
update width639; it adds1.125MiB to one bank of int8 KV inputs. It is a host probe
setting and changes no Android/GPU configuration. Resource fit remains unproven.

The supervisor reuses the compiler's start-identity and subreaper ownership
primitives, watches all descendants including escaped sessions, and uses pidfds
for identity-bound cleanup signals. Releasing ownership additionally requires
waitpid to prove ECHILD after reaping all adopted descendants. Wall, RSS, reserve and cancellation failures
terminate/reap the owned tree. A zero-exit parent with live descendants fails.
Pending cleanup is recorded in the build directory before launch; uncertain
cleanup or receipt writing blocks later lanes, prerequisites and quality runs.
A cleanup error remains a failure even if the best-effort retry removes every
process. The original stop reason is retained separately. Model stdout/stderr
stay separate and are never passed to compiler diagnostic collection.

Only an explicit memory-headroom refusal, supervisor watchdog reason, SIGXCPU,
ENOMEM, std::bad_alloc, RESOURCE_EXHAUSTED, or explicit allocation/mapping out-of-
memory diagnostic is called resource_constrained. An unadorned
per_layer_embedding_lookup_ load failure, generic mmap failure, or SIGKILL is
native_execution_failure, not evidence of memory exhaustion. Both are failures.
Unknown/unrun/inconclusive status can never pass. Download/build bounds are
separate from inference limits: one bounded600s bundle transfer,60min host build,
and finite CI step/job windows; no automatic retries.

export_evidence.py exports only the enumerated structured receipts: source/model
hashes, compiler/binary identities, process limits/exit/peaks, frontend/oracle
booleans, exact small textual Conversation responses and the comparison. It
excludes all model files, PCM/WAV/Mel, activation arrays, request blobs, runfiles,
binaries and raw model-inference stdout/stderr logs. A malformed/oversized/raw-data receipt makes
export fail closed. Always upload only streaming-quality-evidence/, never the
build/run/input trees. EVIDENCE-INDEX.json includes the exact CI commit/run/attempt.

## Source adaptations

native_conversation_quality_probe.cc and compare_native_pair.py bind the fixed
context640/full-E2B profile while preserving exact identities, output/tap checks,
semantic reference and checked drain. The native entrypoint requires the same
6GiB hard/soft address-space cap as the supervisor. pinned_encoder_probe.cc is the
existing native probe with its CPU thread count reduced from2 to1. The frontend
probe is the earlier full-SDK boundary check with a small fresh-Mel output branch
instead of reading a historical Mel file; both native paths still compare bitwise
and Python checks the pinned Mel hash. Recipe assets and Java are byte-for-byte
copies of the reviewed weightless recipe. No SDK source or CI job was executed
while preparing this package.

## Retained numerical failures

`encoder-oracle.json` is written on success and failure. The current versioned
contract records exact same-run row/EOA equality, complete emitted-output/state
validity and the historical fingerprint diagnostic without exporting tensors.
Earlier failures remain evidence under their original fixed-hash policy. Build
1132 passed within-run row and EOA equality but failed that fixed hash; both
one-thread and two-thread local repeats preserved the old hash. This proposal
does not resolve the cause or relabel that failure. Bounded runner CPU/ISA
metadata does not establish that hardware caused the difference.
