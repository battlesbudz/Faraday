# ADR 001: cohesive package boundaries within one Android module

Status: accepted for the current maintainability refactor, 2026-10-02.

## Context

The application is currently one Gradle module, `:app`. Its largest coordination
paths combine process/call lifetimes, mutable conversation state and native audio
resources. Moving those paths into arbitrary files or new modules would not by
itself give them clearer ownership.

The release also has real binary constraints. Moonshine needs its namespaced ONNX
runtime alongside Sherpa's runtime; the speech JNI build uses a constrained source
profile; Sherpa calls a concrete Java PCM callback; and the separately shrunk
instrumentation APK links shared application/Kotlin/Compose contracts. These
boundaries are covered by native packaging checks and `app/proguard-rules.pro`.

## Decision

Keep one Android application module while decomposing responsibilities into
feature packages and explicit collaborators. Runtime and UI coordinators keep
their lifecycle ownership; helpers own a cohesive task and, where appropriate,
its state. Pure policies and typed contracts remain independent of activity/UI
details. Use `internal` for implementation contracts and expose only required APIs.

Prefer constructor-injected narrow collaborators or explicit operation callbacks
to a second global service locator. The process runtime is the composition/lifecycle
facade; voice/conversation stages receive typed input and their required ports.
An unrestricted runtime extension or a wrapper exposing the full process surface
is not an independent stage boundary. A new extraction must reduce knowledge or
state in its caller and preserve cancellation/resource ordering; changing file
length alone is insufficient.

## Consequences

- Contributors can navigate by responsibility without a build graph migration.
- JVM regressions, release instrumentation, JNI symbol checks and APK receipts
  continue to exercise the existing shipping boundaries.
- Packages are a design boundary, not compiler-enforced isolation. The source
  dependency guard checks explicit process/activity composition and model-storage
  admission boundaries; source review and the ownership map also verify port
  breadth, lifecycle and coherent UI/policy/adapter responsibility.
- Some call and conversation coordinators remain substantial because they own
  ordering. Further extraction should use typed turn stages with one admission
  owner, rather than runtime extension files with unrestricted access.

Revisit this decision when a package has an acyclic dependency set, an independently
useful public contract, and a measurable build/testing/reuse benefit. A proposed
JVM or Android library must define its native packaging, R8, instrumentation and
internal-visibility migration. More modules do not inherently reduce APK size.

Related: [architecture](README.md), [refactoring map](../app-modularization.md),
[APK size audit](../apk-size-audit.md), [release workflow](../verification/README.md).
