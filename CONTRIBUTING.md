# Contributing

Start with the [architecture](docs/architecture/README.md),
[development setup](docs/architecture/development.md), and
[change guide](docs/architecture/change-guide.md). Repository-specific collaboration
and verification requirements live in [AGENTS.md](AGENTS.md).

## Choose a boundary before editing

1. Identify the existing owner and closest behavioral tests. Use
   `python3 scripts/dev.py map` and `rg` to trace callers.
2. Write down the behavior that must remain stable: input/output, lifecycle owner,
   cancellation, persistence keys and native/resource ordering.
3. Place policy in the feature package and Android operations in its adapter.
   Keep UI callbacks and service/activity entry points thin.
4. Make the smallest cohesive change that solves the problem. A collaborator owns
   a responsibility and its state; avoid splitting one function across files just
   to reduce line counts.
5. Run the applicable gates, inspect the result, and update the source map or
   coverage documentation when a contract changes.

The [single-module decision](docs/architecture/adr-001-package-boundaries.md)
keeps cross-package implementation contracts `internal`. New Gradle modules need
an explicit ownership, dependency and testing reason, plus native/ABI validation.

## Coding and lifecycle conventions

- Prefer typed inputs/results and small explicit interfaces. Inject the few
  collaborators a component uses rather than passing `MainActivity` or the entire
  runtime into domain policy. Keep `JarvisRuntime` at the composition/lifecycle
  boundary; stage files must not recover arbitrary process state through runtime
  extensions, global lookup or an all-capability wrapper.
- Use feature-specific names. Extend an existing policy or adapter before adding
  a generic helper or a parallel code path for voice and text.
- Keep Compose presentation free of model initialization, downloads and durable
  action dispatch. Activity-result launchers and permission prompts belong to the
  Android/UI boundary; operations belong to their controller or worker.
- Match work to its owner: activity/UI work to its lifecycle, calls to their call
  owner, accepted work to its process runtime, and downloads to WorkManager.
  Ending playback does not implicitly cancel accepted Android work.
- Preserve the single admission/model lease and microphone handoff. Join native
  borrowers before releasing their owner. Close native objects on their owning
  dispatcher and propagate coroutine cancellation through cleanup.
- Parse and validate a complete action plan before effects. Report executor
  receipts honestly, persist dispatch intent, and retain unknown outcomes rather
  than blindly replaying them.
- Keep memory revision/expiry/lock checks and delivery fences intact. A stale
  token callback must not publish a fact removed during generation.
- Keep preference keys, database schemas, model filenames and serialized values
  stable unless an intentional migration is included. Use private storage for
  production data; do not commit signing material, personal exports or model weights.

## Verification

For feature/release work, read [.agents/skills/jarvis-verify/SKILL.md](.agents/skills/jarvis-verify/SKILL.md)
and follow the [verification workflow](docs/verification/README.md). The
[development guide](docs/architecture/development.md#checks) gives local commands;
[scripts/README.md](scripts/README.md) explains each harness.

Add tests for changed behavior or a meaningful regression, using existing seams.
Do not add tests that only repeat an implementation or weaken existing acceptance
to get a green result. JVM tests prove policy/state-machine behavior; release
instrumentation proves Android/UI/persistence integration. Neither proves acoustic
quality or local-model accuracy. Preserve failed evidence and state missing coverage.

## Review and handoff

Describe the user-visible problem and resulting behavior first. Include affected
owners, persistence/ABI changes, checks run, exact tested revision, and material
coverage gaps. Update [architecture](docs/architecture/README.md) when ownership
changes and [feature coverage](docs/verification/features.md) when acceptance changes.
A repository-wide refactor also needs an explicit subsystem audit and justified
cohesive exceptions; use the [completion contract](docs/verification/modular-refactor.md).
Link a dated measurement or plan as evidence; do not present it as current behavior.

Coordinate one file scope per concurrent worker and keep one owner for integration
and publication. Refresh the destination branch before pushing; preserve another
worker's changes. The existing `audio-pr2` work uses PR #6. Do not open a new PR,
merge a PR, or delete a reserved branch without the authorization in [AGENTS.md](AGENTS.md).
