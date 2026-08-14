# Progress Log

## Current Verified State

This is the only section that gets edited in place. Keep it accurate — it is the first thing every
new session reads.

- Repository root: the directory holding `build.gradle` and `gradlew` (clone of `Sighte/SighteAddons`)
- Standard startup path: `./gradlew runClient` — Loom's dev client, which has no valid session and
  cannot reach Hypixel
- Standard verification path: `./init.sh` → `./gradlew test`; full is **`./gradlew assemble check`**.
  **Not `./gradlew build`** while fixes sit unreleased: `build` is `finalizedBy copyToDist` and
  `copyToDist dependsOn cleanDist`, so it deletes and rewrites `dist/sighteaddons-0.9.0.jar` — the
  released artifact — with a jar built from the current tree under the same version number. This line
  said `build` until session 006; `init.sh` was corrected at `c4c0c56` and prints `assemble check`,
  and session 004 below records that repair. `build` belongs to the release gate in `CLAUDE.md`, where
  refreshing that jar is the whole point.
- Baseline status (last `./init.sh` run): **PASSING** — 116 tests in 12 classes, 0 failures,
  0 skipped, 2026-08-14, at `13c9fb5` on branch `clearpoints-001` (off `main` at `1e27b42`),
  `mod_version=0.9.0`
- Last feature completed: `clearpoints-001` — rooms are weighted rather than counted
  (`ContributionTracker.weightOf`), and `unattributed` is now a **count of rooms** rather than
  `roomsCleared` minus the points handed out. No schema change and no receiver change: `RUN_KEYS`
  carries no points field, and the field's values are unchanged.
- Current highest-priority unfinished feature: `chat-001` — read the events Hypixel puts in chat.
  Its test class `ChatEventsTest` does not exist yet; creating it is part of the feature.
- Current blocker: none for `chat-001`. `records-001` is deferred by the user (a product decision,
  not a technical blocker), and `ingame-001` is blocked on a human playing a real floor, which no
  command here can produce.
- **The report schema is now 5 in source and 4 in every install.** `dist/sighteaddons-0.9.0.jar` is
  deliberately **not** rebuilt — it is still the released 0.9.0 artifact — so `residue-001` and
  `clear-001` both exist in source only. Neither reaches a player until somebody bumps the version
  and takes the release gate at the top of `CLAUDE.md`, which is the user's decision. Nothing breaks
  in the meantime: the receiver accepts v4 and v5 alike and buckets their clear spans apart.

## Session Log

Rules: insert the newest session at the TOP of this section. Never edit or delete past session
entries — they are the audit trail. Copy the template below for each new session.

### Session 005 — `clearpoints-001`: weight rooms, and stop measuring rooms in points

- Date: 2026-08-14
- Branch `clearpoints-001`, off `main` at `1e27b42`. One commit, `13c9fb5`, **not pushed and not
  merged**. Baseline before starting: `bash init.sh` → **PASSING**, so no repair was owed.
- **The cross-repo check first**, because `CLAUDE.md` requires it before anything schema-shaped and
  because it was the question the feature turned on. Read against the receiver's source, not
  assumed: `RUN_KEYS` (`ingest.py:137`) and `ROOM_KEYS` (`ingest.py:152`) carry **no per-player
  points field at all** — the breakdown never leaves the client — so the weighting is invisible to
  the validator. `unattributed` is `_real(x, 0, MAX_CLEARED)` with `MAX_CLEARED = 200`
  (`ingest.py:167,226`), the same ceiling `roomsCleared` is bounded by, and no `400` is reachable
  from either the old or the new definition. **Not paired, no schema bump, `SCHEMA` stays 5.**
- **What the weighting is.** `ContributionTracker.weightOf(room)` = 1.0 base, plus a kind bonus
  (puzzle 1.5; trap, champion/miniboss and blood 1.0), plus 0.25 per secret the room database says
  the room holds, plus 0.5 per segment beyond the first. The split over the members in the room is
  untouched, so two players in one room are still separated only by time. Three exclusions are
  deliberate and each is the obvious next edit: **rare** rooms are paid nothing for being rare (rare
  is unusual to draw, not harder to clear); **secrets come from the database, not from
  `secretsFound`**, because `award()` fires on the clear checkmark while the room's secrets are
  usually still being collected; and **the floor is not a multiplier**, though `floorNumber` is right
  there and the README listed it — it is constant across a run, so it scales everyone equally and
  separates nobody, and points are only ever compared inside one run because they are not in the run
  report at all.
- **The half that carried the risk, and the actual design question.** `unattributed` was
  `roomsCleared - pointsByPlayer().values.sum()`. That was only ever correct because a room was worth
  exactly 1.0, which made a *count* and a *score* numerically interchangeable. Weighted, the score
  exceeds the count, the subtraction goes negative, and `settle`'s clamp reports `0.0` on every run
  forever — no exception, no `400`, no log line. The same shape of failure this project has spent two
  sessions removing elsewhere. So the count is now **counted**, in `award()`, and stays **in rooms**:
  a heavy room nobody was in is one unattributed room, not five points of one. Not a preference — the
  receiver reads the field *relative to* `roomsCleared` and it is its only diagnostic for a broken
  decoration→player mapping (`agent/AGENT-PROMPT.md:62`).
- **Why no schema bump is owed**, and this is the mirror image of `clear-001`: under flat weighting
  every award credited either the full point or nothing, so the old subtraction already produced
  exactly this count. Same key, same meaning, same numbers — now by construction instead of by
  coincidence. `clear-001` was the other case, where the key stayed and the meaning moved, and that
  one cost a bump.
- New seam: `ContributionTracker.onCleared(room)` is `internal` and does `roomsCleared++` then
  `award(room)`; `tick()` calls it. This closes the gap session 002 recorded — that
  `unattributed()`'s composition had no test because `roomsCleared` and `credited` are private and
  only a real run fills them.
- Verification, all at `13c9fb5`: the `verification_command` green (`ContributionTrackerTest` 29,
  `RoomDatabaseTest` 6, 0 failures); `bash init.sh` → `BASELINE: PASSING`, whole suite 12 classes /
  **116 tests** / 0 failures, up from 101 with nothing removed or weakened; `./gradlew assemble check`
  → `BUILD SUCCESSFUL` with `git status --short dist/ gradle.properties` empty. Two mutation checks,
  both reverted immediately and both recorded as evidence: restoring the old subtraction fails 2
  tests, flattening every weight to 0.0 fails 7.
- Discovered work, recorded rather than fixed inline: **`runend-001`**. The receiver's
  `agent/AGENT-PROMPT.md:62` tells its analysis agent to read `unattributed` against `roomsCleared`
  *in `run_end`*, and the mod's `run_end` event has never carried `unattributed`. Its notes also flag
  the ambiguity — the per-room `unattributed` *event* is a different number, since it fires whenever
  the `MIN_TICKS` split was empty even when the raw-presence fallback then credited somebody.
- Not done and deliberately so: no version bump, no `dist/` refresh, no harness file edited,
  `rooms.json` read and never written, `records-001` left `blocked` as the user decided, and
  `SighteAddonServerside` read but never written.

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
