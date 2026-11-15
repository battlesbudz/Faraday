# Scene reconciliation fix report

## Changes

- `app/src/main/java/com/battlesbudz/jarvis/v2/voice/CallVisualContext.kt`: gated scene injection on the per-turn video-call toggle. Snapshot reconciliation combines same-label tracks only when their latest boxes have IoU >= 0.5 and their last sightings are within 5 seconds. The consolidated line lists all merged track IDs and sightings. Snapshot budget omissions now count raw tracks, while the summary block explicitly reports tracks omitted by its recent-track policy. This keeps one-frame tracks in image-backed snapshots and preserves the summary's existing one-frame trust filter when no image is attached.
- `app/src/test/java/com/battlesbudz/jarvis/v2/voice/CallVisualContextTest.kt`: added coverage for video gating on/off, conservative duplicate merging, spatially separate same-label objects, and raw-track accounting under the snapshot text budget. Updated the video-off expectation to require no scene block.

## Deliberately not done

- Did not change the detector, vision server, tracker identity algorithm, snapshot submission path, runtime composition, or files outside this branch's video-call ownership.
- Did not build, create an APK, run Gradle, or use the network.

## Tests

- Added JVM unit tests pinning video toggle gating, duplicate ID accounting, non-merge behavior for separate objects, and `tracksIncluded + tracksOmitted == raw in-window tracks` under the snapshot character budget.
- Existing snapshot tests continue to pin one-frame inclusion when an image is attached and the summary trust rule without an image.
- Tests were not executed, per task constraints. `git diff --check` passed.

## Risks / owner review

- The duplicate rule is intentionally conservative and geometry-only. It can merge visually overlapping same-label objects if their tracks were minted around the tracker retirement boundary; it can still miss an object whose box moved far enough to stop overlapping. The detector boxes do not provide appearance identity.
- Review summary-mode omission wording and make sure the prompt's counted omissions remain understandable to Gemma. The existing summary count is label-based; it does not claim per-track identities.
- No compile or device evidence is available from this environment.

## On-phone proof

On a video-enabled call, arrange a stable object in view and ask what is visible. Copy diagnostics and confirm the scene decision is injected. For a camera-motion case that mints a new same-label track near the earlier box, inspect the model prompt/response context for one consolidated entry listing both IDs and `sightings=2`. Then turn video off while leaving the voice call active and ask again; diagnostics should show `reason=video_off`, with no camera scene instruction in the turn. With a snapshot-capable model, a one-frame track should still appear in the snapshot block; without image attachment, the prior summary trust filter should remain active and report omitted tracks.
