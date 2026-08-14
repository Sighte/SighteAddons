# Progress Log

## Current Verified State

This is the only section that gets edited in place. Keep it accurate — it is the first thing every
new session reads.

- Repository root: the directory holding `build.gradle` and `gradlew` (clone of `Sighte/SighteAddons`)
- Standard startup path: `./gradlew runClient` — Loom's dev client, which has no valid session and
  cannot reach Hypixel
- Standard verification path: `./init.sh` → `./gradlew test`; full is `./gradlew build`
- Baseline status (last `./init.sh` run): **PASSING** — 101 tests in 12 classes, 0 failures,
  0 skipped, 2026-08-14, at `a6d92b6` on branch `clear-001` (off `main` at `b588cc4`),
  `mod_version=0.9.0`
- Last feature completed: `clear-001` — `enterTick` is anchored on a minimum stay instead of on the
  first sighting, and `RunReport.SCHEMA` is **5**. Its receiver half, `schema-001`, was deployed
  first and is live on `master` at `f1085a7`.
- Current highest-priority unfinished feature: `clearpoints-001` — weight rooms instead of counting
  them. Not paired with the receiver and not a schema change.
- Current blocker: none for `clearpoints-001`. `ingame-001` is blocked on a human playing a real
  floor, which no command here can produce.
- **The report schema is now 5 in source and 4 in every install.** `dist/sighteaddons-0.9.0.jar` is
  deliberately **not** rebuilt — it is still the released 0.9.0 artifact — so `residue-001` and
  `clear-001` both exist in source only. Neither reaches a player until somebody bumps the version
  and takes the release gate at the top of `CLAUDE.md`, which is the user's decision. Nothing breaks
  in the meantime: the receiver accepts v4 and v5 alike and buckets their clear spans apart.

## Session Log

Rules: insert the newest session at the TOP of this section. Never edit or delete past session
entries — they are the audit trail. Copy the template below for each new session.

### Session 004 — a harness change, at the user's explicit request

- Date: 2026-08-14
- **This is a harness change and `CLAUDE.md` requires it be recorded here.** The user asked for it
  directly; no session may touch `init.sh` otherwise. No feature was worked on and no source file was
  changed. Branch `harness/init-verify-command`, off `main` at `cf3ff3c`.
- **What was wrong.** `init.sh` line 15 declared `VERIFY_CMD=(./gradlew build)` and printed it as "the
  full verification command" — the one every session is told to run. `build` is `finalizedBy
  copyToDist`, and `copyToDist dependsOn cleanDist`, which **deletes `dist/sighteaddons-*.jar`** and
  writes the current tree's build in its place. Run with an unreleased fix on a branch, it silently
  swaps the released artifact for a different build wearing the same version number. `session-handoff.md`
  has warned about this since the `residue-001` session; `init.sh` said the opposite, and `init.sh` is
  what step 5 of the operating loop tells you to run.
- **Why it was not simply a mistake.** `build` is deliberate in the *release gate* at the top of
  `CLAUDE.md`, where refreshing `dist/` is the entire point: if the jar changes there, the committed
  one was stale. The same command is right in one place and wrong in the other, which is why it
  survived three sessions and two evaluations. The fix keeps both readings and says so.
- **What changed.** `VERIFY_CMD=(./gradlew assemble check)`, with the reasoning as a comment at the
  declaration, and the printed note rewritten from "build also refreshes dist/ — if it changes, the
  jar was stale" to naming which context is which: at the release gate a change means stale, here it
  means you lost it.
- Verification: `bash -n init.sh` clean; `bash init.sh` → `BASELINE: PASSING`, printing
  `./gradlew assemble check`. Then the recommendation was actually executed — `./gradlew assemble
  check` → `BUILD SUCCESSFUL`, and `dist/sighteaddons-0.9.0.jar` kept md5 `b2ebc35c…` with
  `git status --short dist/` empty, before and after. The old text was never tested this way; that is
  how it stayed wrong.
- Provenance: found by the `clear-001` evaluator, which also noted that it had to **carry three
  `residue-001` findings forward by hand** because overwriting `evaluator-rubric.md` would have erased
  them. That is the larger defect and it is not fixed here: evaluator findings never reach
  `feature_list.json` and vanish at the next review. Recorded for whoever opens that file next.

### Session 003

- Date: 2026-08-14
- Goal: `clear-001` — anchor `enterTick` on a minimum stay, so the reported clear stops starting at
  somebody's walk-through.
- Completed: `TrackedRoom` now tracks a **current stay** per member (`start`, `ticks`, `lastSeen`)
  alongside the run-long tick total it already kept, and `enteredAtTick` — now `private set` — is the
  start of the first stay to reach `MIN_TICKS`. Two new pure methods carry it: `onPresence(player,
  at)` and `anchorOnClear(at)`. The first-sighting assignment in `discover` is gone, and its
  `ponytail:` note shrank from two ceilings to one (decoration lag, whose upgrade path is party
  sync). `ContributionTracker.tick` calls both and logs a new `room_anchored` debug event; the
  `cleared` event now carries `enterTick` and `anchoredOnClear`. **`RunReport.SCHEMA` 4 → 5.** New
  `ContributionTrackerTest` with 16 cases — the class both `clear-001` and `clearpoints-001` name in
  their `verification_command` and which did not exist before.
- Verification run (exact commands):
  - `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RunReportTest' --rerun-tasks`
  - `bash init.sh`
  - `./gradlew assemble check`
  - two mutation checks, both reverted immediately (see `feature_list.json` evidence)
- Evidence captured: `BUILD SUCCESSFUL in 7s`, `7 actionable tasks: 7 executed`;
  `ContributionTrackerTest tests=16 failures=0`, `RunReportTest tests=21 failures=0`;
  `BASELINE: PASSING` with the JUnit XML summing to `tests=101 skipped=0 failures=0 errors=0` across
  12 classes, up from 84. All at `a6d92b6`. The two mutation checks are the load-bearing evidence:
  reverting `SCHEMA` to 4 fails 2 tests, and reverting the anchor to first-sighting fails 9 of the
  16 new ones — so neither half can be silently undone.
- Commits: `a6d92b6` (the anchor, the schema bump and the tests) plus the artifact commit that
  follows it, both on branch `clear-001` off `main` at `b588cc4`. Not pushed, not merged.
- Files or artifacts updated: `ContributionTracker.kt`, `RunReport.kt`, `RunReportTest.kt`, new
  `ContributionTrackerTest.kt`, `feature_list.json`, this file, `quality-document.md`,
  `session-handoff.md`.
- Regressions found: none. No test was removed or weakened. Two existing `RunReportTest` assertions
  were updated because this feature deliberately changes what they pin: `run context survives` now
  expects `v = 5`, and the shared room fixture earns its anchor through `onPresence` rather than
  assigning it, landing on the same `120` the assertions have always expected.
- Correction recorded: `clear-001`'s own `notes` claimed the pair risked a `400` and the permanent
  loss of every run of the build. That was **false**, and it was checked rather than trusted:
  `ingest.py:214` validates `v` as `_num(x, 1, 10)` so `v: 5` was accepted before the receiver
  moved, and `ingest.py:159` has had `enterTick` in `ROOM_OPTIONAL` since schema 3. This feature adds
  no field; it changes what one means. The note is corrected in place, with the real risk in its
  stead — which is worse than a `400` precisely because nothing reports it.
- Known risk or unresolved issue: the anchor is verified only through the `TrackedRoom` seam.
  `ContributionTracker.tick` needs a `Minecraft` and a `MapItemSavedData`, so the wiring — that
  `tick` calls `onPresence` once per member per tick with the run clock, and `anchorOnClear` exactly
  on the clear — has no test and no command here can produce one. The gap tolerance is reasoned from
  `PartyTracker.positions`' documented 10–20 tick roster-skew window, not measured against a real
  decoration stream. And the schema is now 5 in source while every install still sends 4: correct and
  harmless, but it means the receiver's `clearStay` bucket stays empty until a release happens.

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
