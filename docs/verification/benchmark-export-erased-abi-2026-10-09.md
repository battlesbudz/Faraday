# Benchmark export release-test ABI repair (9 October 2026)

Build 1247, source `770cde61791cceed664f7c991e17fc08f0a35a7d`, reached all
82 main journeys on API 30; 81 passed. Test45 failed when its independently
compiled instrumentation DEX called a typed AndroidX method that the release
app's R8 optimization had removed. This is an instrumentation ABI defect.

The [failed run](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37995191775)
and its API 30 artifact `11647554791` retain the failure. The artifact archive
was digest-verified before inspection. The app, test and mapping SHA-256 values
are recorded in the [DEX audit](evidence/benchmark-export-erased-abi-2026-10-09/build1247-export-dex-audit.json).
No successful older build is substituted for this failed source.

## Cause and bounded correction

`BenchmarkExportFiles.createDocument` returns the real production subclass of
AndroidX Activity 1.10.0 `CreateDocument`, including the OPENABLE correction.
The fixture inferred that concrete return type. Its typed synchronous-result
and URI-returning parse calls therefore depended on subclass ABI that the
production activity-result registry does not use.

The exact app DEX and R8 mapping identify `f.b` as `CreateDocument` and show:

| Old instrumentation reference | App DEX |
| --- | --- |
| `createIntent(Context,String):Intent`, renamed `a` | Present |
| `getSynchronousResult(Context,String):SynchronousResult` | Absent; body folded into Object-input bridge |
| `parseResult(int,Intent):Uri` | Absent; body folded into Object-output bridge |

The generic `ActivityResultContract` methods and concrete erased bridges are
present. Existing release rules already keep the generic base ABI, and the
production launcher/registry uses it. The normal and compact APKs contain
identical DEX bytes and share these findings.

Both initial and recreated fixture contracts now have the explicit type
`ActivityResultContract<String, Uri?>`. All three method calls consequently
use this preserved ABI, including the recreated contract's `createIntent`.
The runtime objects and every assertion remain unchanged. This avoids adding
test-only typed overloads to the shipping keep rules. No production source,
R8 rule, named-test contract, threshold, timeout, I/O or export behavior changes.

## Acceptance and evidence

- JVM/classfile linkage: the old fixture passes against stock AndroidX, then
  fails with the observed missing typed synchronous-result signature when the
  two removed typed method bodies are folded into their erased bridges.
- JVM/classfile behavior: the fixed exact fixture passes all 78 existing
  assertions against both stock and folded AndroidX for TXT, JSON and CSV.
  This covers action, MIME, title/extension, OPENABLE, fresh repeated/recreated
  intents, null synchronous result, cancelled/null results and successful URI.
- Negative controls: a direct typed parse call separately fails with its
  missing URI-returning signature; removing OPENABLE from a temporary copy of
  production still fails the fixed fixture's existing assertion.
- Compile coverage: the exact test45 method, changed probe and actual production
  benchmark/UI sources compile against cached Android 14, Activity 1.10.0,
  UiAutomator 2.3.0 and Compose 1.7.6 APIs. Existing activity/navigation helpers
  are signature-only seams and generated BuildConfig values are substituted.
- Helper coverage: `python3 scripts/dev.py check` passes architecture boundaries,
  109 helper tests and 82 verification-helper tests.

The [offline host harness](evidence/benchmark-export-erased-abi-2026-10-09/harness/run_host.py)
verifies cached dependency digests, compiles production and test separately,
compares retained assertion lines, and audits the compiled call descriptors.
Its Java helper copies actual AndroidX bodies into erased bridges to reproduce
the observed missing descriptors. It is a synthetic classfile linkage
reproduction, not R8 execution. Only String inputs are exercised, so removal
of the original bridge's redundant String cast does not affect these cases.
The [static APK auditor](evidence/benchmark-export-erased-abi-2026-10-09/harness/audit-export-dex.py)
separately reads real class definitions and method references from both old
APKs. It does not infer method existence from inlining entries in a mapping.

Run the host harness with JDK 21 and the previously verified dependency cache:

```sh
python3 docs/verification/evidence/benchmark-export-erased-abi-2026-10-09/harness/run_host.py \
  --repo . --deps /path/to/dependency-cache --out /path/to/new-evidence-directory
```

The [source-bound local receipt](evidence/benchmark-export-erased-abi-2026-10-09/summary.json)
records source, harness and result hashes. An independent review of the source,
three-method DEX boundary and harness found no blocking issue.

## Required release validation

These checks do not establish a new signed/minified APK or device pass. The
exact integrated revision still requires the full release JVM/native checks,
R8 build and all five Android profiles, including the unchanged complete test45
ContentResolver/FileProvider, repeated-save/share, clipboard, privacy and
persisted-report assertions and all later verification phases. Real third-party
pickers/receivers, model inference, acoustics and physical-device performance
remain outside this host evidence. Retain the failed Build 1247 evidence.
