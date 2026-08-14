# Progress Log

## Current Verified State

This is the only section that gets edited in place. Keep it accurate — it is the first thing every
new session reads.

- Repository root: the directory holding `build.gradle` and `gradlew` (clone of `Sighte/SighteAddons`)
- Standard startup path: `./gradlew runClient` — Loom's dev client, which has no valid session and
  cannot reach Hypixel
- Standard verification path: `./init.sh` → `./gradlew test`; full is `./gradlew build`
- Baseline status (last `./init.sh` run): **PASSING** — 84 tests in 11 classes, 0 failures,
  0 skipped, 2026-08-14, at `2f742cd` on branch `residue-001` (off `main` at `43b425f`),
  `mod_version=0.9.0`
- Last feature completed: `residue-001` — `unattributed` is settled rather than half-clamped. Not a
  schema change, so nothing is waiting on the receiver for it.
- Current highest-priority unfinished feature: `clear-001` — anchor `enterTick` on a minimum stay.
  It is paired with the receiver's `schema-001` and **the receiver ships first**.
- Current blocker: none for `clear-001`. `ingame-001` is blocked on a human playing a real floor,
  which no command here can produce.
- `dist/sighteaddons-0.9.0.jar` is deliberately **not** rebuilt: it is still the released 0.9.0
  artifact. `residue-001` therefore exists in source only until somebody bumps the version and takes
  the release gate at the top of `CLAUDE.md`.

## Session Log

Rules: insert the newest session at the TOP of this section. Never edit or delete past session
entries — they are the audit trail. Copy the template below for each new session.

### Session 002

- Date: 2026-08-14
- Goal: `residue-001` — stop the point split's floating-point residue from reaching the report.
- Completed: `ContributionTracker.unattributed()` and `ContributionTracker.settle()`; `RunReport.build`
  now settles the field instead of clamping one side of it; `RunReport.write` and
  `RoomHistory.printSummary` both call `unattributed()` rather than each computing
  `roomsCleared - pointsByPlayer().values.sum()` inline. Five new tests in `RunReportTest`. The
  feature itself was not in `feature_list.json` at the start of the session — it came from real live
  telemetry, agreed with the user, and was recorded as a new entry (priority 1; the six existing
  features shifted to 2–7, nothing else about them touched).
- Verification run (exact commands):
  - `./gradlew test --tests 'sighteaddons.RunReportTest' --tests 'sighteaddons.RoomHistoryTest' --tests 'sighteaddons.DungeonGridTest' --rerun-tasks`
  - `bash init.sh`
  - `./gradlew assemble check`
- Evidence captured: `BUILD SUCCESSFUL in 10s` with `7 actionable tasks: 7 executed`;
  `BASELINE: PASSING`; the JUnit XML under `build/test-results/test/` sums to
  `tests=84 skipped=0 failures=0 errors=0` across 11 classes, up from 79. All at `2f742cd`.
- Commits: `2f742cd` (the fix and its tests) plus the artifact commit that follows it, both on branch
  `residue-001`. Not pushed, not merged.
- Files or artifacts updated: `ContributionTracker.kt`, `RunReport.kt`, `RoomHistory.kt`,
  `RunReportTest.kt`, `feature_list.json`, this file, `quality-document.md`, `session-handoff.md`.
- Regressions found: none. No existing test was changed, weakened or removed; the two assertions that
  already pinned `unattributed` at `1.25` still pass unmodified, because `settle` is exact on it.
- Known risk or unresolved issue: the fix is verified only through the `build` seam. Nothing here can
  produce a real run, so the end-to-end path — a floor whose credited points drift, `write` calling
  `unattributed()`, the receiver accepting the result — has not been observed, and
  `ContributionTracker.unattributed()`'s own composition (`roomsCleared` minus the credited sum) has
  no test because both inputs are private and only a real run fills them. There is still no
  `ContributionTrackerTest`; `clear-001` and `clearpoints-001` both name one in their
  `verification_command`, so whoever takes those creates it. Separately, `dist/` was deliberately not
  refreshed, so no installed build carries this fix yet.
- Next best step: `clear-001`, but only after the receiver's `schema-001` is deployed.

### Session 001

- Date: 2026-08-13
- Goal: Instantiate the DevLoop harness in this repository.
- Completed: `init.sh`, `feature_list.json`, `claude-progress.md`, `session-handoff.md`,
  `quality-document.md`, `evaluator-rubric.md`, an Operating Loop section appended to `CLAUDE.md`,
  and a `*.sh text eol=lf` line in `.gitattributes`. No source file, no resource and no build script
  was changed — `mod_version` is untouched at 0.9.0, so the release gate at the top of `CLAUDE.md`
  does not fire.
- Verification run (exact commands):
  - `bash init.sh`
- Evidence captured: `BUILD SUCCESSFUL in 25s`, `BASELINE: PASSING`; the JUnit XML under
  `build/test-results/test/` sums to `tests=79 skipped=0 failures=0 errors=0`.
- Commits: the harness commit on branch `devloop-harness`.
- Files or artifacts updated: the six new files above plus the two edited ones.
- Regressions found: none — the suite was green before and after, and nothing it covers was edited.
- Known risk or unresolved issue: the seeded features are read out of this repository's own README
  ("Not implemented yet", "Known limits") and its `ponytail:` notes. They have never been reviewed
  by the user — treat the list as a starting point, not as an agreed backlog. Separately, everything
  `ingame-001` names is still unverified in a real dungeon, and that has not changed.
- Next best step: `clear-001`, but only after the receiver's `schema-001` is deployed.

<!-- SESSION TEMPLATE — copy, do not fill in here
### Session NNN

- Date:
- Goal:
- Completed:
- Verification run (exact commands):
- Evidence captured:
- Commits:
- Files or artifacts updated:
- Regressions found:
- Known risk or unresolved issue:
- Next best step:
-->
