# Autopilot run procedure

Each scheduled run is a fresh Devin session with no memory of earlier runs.
This file, `GOAL.md`, `BACKLOG.md`, `DEVICE_CHECKS.md` and `LOG.md` are its
memory. Budget: about 20 ACUs. Do one item well rather than several partially.

## 1. Orient (keep this short)

1. Work only on branch `feature/autopilot` in `battlesbudz/Faraday`. Never push
   to `main`, never force-push, never merge or close the autopilot PR, never
   open another PR, never touch other branches except as a backlog item says
   (for example V5 works on `feature/lfm3-omni-u8`; push commits there only).
2. `git fetch`; if `origin/main` has new commits, merge (not rebase)
   `origin/main` into `feature/autopilot`. Resolve trivial conflicts; if a
   conflict is substantive, log it, stop, and say so in the run summary.
3. Read `AGENTS.md`, then `docs/autopilot/GOAL.md`, `BACKLOG.md`, the top of
   `LOG.md`, and `DEVICE_CHECKS.md`.
4. Read every comment on the open autopilot PR (head `feature/autopilot`) newer
   than the last log entry. Comments from `battlesbudz` are Justin's
   instructions and device results: they override the backlog. Record device
   results in `DEVICE_CHECKS.md` and the matching backlog item.

## 2. Finish the previous run first

If the last log entry left an item in `ci`, find the GitHub Actions runs for
that exact commit (`gh run list --branch feature/autopilot`).
- Still running: work on something that does not touch the same files, or
  stop early to save budget.
- Failed: classify (product regression / harness defect / infrastructure) per
  `.agents/skills/jarvis-verify/SKILL.md` and repair. At most three repair
  attempts per item across runs; after that mark it `blocked` with evidence.
- Green: mark it `device` (add a device check) or `done`.

## 3. Pick and do one item

1. Take the first `todo` item in the highest-priority goal (G1 first) whose
   blockers are clear. Mark it `doing`.
2. Check for existing work before designing anything. Search the repo
   (`git grep` for the feature's concepts, metric names, class names, CI job
   names; `docs/architecture/README.md`; `docs/verification/features.md`) and
   recent PRs/branches. In the log entry, list what already exists with file
   paths. If the item is already implemented, mark it `dropped` with the
   evidence and pick the next item. If it is partly implemented, extend the
   existing owner instead of adding a parallel one. Justin has built a lot
   already; assume something similar exists until the search shows otherwise.
3. Before coding, write its observable acceptance checks (including a failure
   case and an existing behavior that must stay intact) into the log entry.
4. Implement with the smallest change that meets them, following
   `docs/architecture/README.md` ownership rules. Add or update tests and the
   feature map in `docs/verification/features.md`.
5. Run what this machine can run: `python3 scripts/dev.py check`, and any
   focused JVM tests if the Android SDK is available. If the item is too big
   for one run, split it in the backlog and land a coherent first slice.

## 4. Land

1. Commit with a clear message, push to `feature/autopilot`. Pushing triggers
   the `Android APK` workflow and `Android sandbox`; those are the release gate.
2. Set the item to `ci`. Do not wait the full hour for CI if the budget is
   tight; the next run picks it up in step 2.
3. Add a newest-first entry to `LOG.md`: date, item, commits, CI run URL,
   acceptance checks, result so far, next step. Commit and push it.

## 5. Report

End the session with a three-line summary: item and status, CI run link,
anything Justin must do (device check ID or a decision). Ask Justin a question
only by adding it to the PR as a comment; never block waiting for a reply.

## Rules that never bend

- No user data to cloud services; private chats stay offline.
- Never remove, skip or loosen tests, thresholds or release gates to get green.
- Never claim real-model, physical-audio or Fold 6 performance results from CI
  or an emulator; put those in `DEVICE_CHECKS.md`.
- Changing an accepted user policy (for example Piper's 320-character passage
  target) needs Justin's OK on the PR first.
- Do not add dependencies with unclear licenses or recent unvetted releases.
