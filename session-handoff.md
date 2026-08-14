# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. **84 tests across 11 classes, 0 failures,
  0 skipped** — up from 79, with no existing test changed, weakened or removed. `mod_version=0.9.0`,
  `dist/sighteaddons-0.9.0.jar` unchanged.
- Branch: `residue-001`, off `main` at `43b425f`. Two commits, **not pushed and not merged**.
- What verification actually ran (exact commands), all at `2f742cd`:
  - `./gradlew test --tests 'sighteaddons.RunReportTest' --tests 'sighteaddons.RoomHistoryTest' --tests 'sighteaddons.DungeonGridTest' --rerun-tasks`
    → `BUILD SUCCESSFUL in 10s`, `7 actionable tasks: 7 executed`
  - `bash init.sh` → `BASELINE: PASSING`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, and `git status --short dist/` empty
  - Test totals from `build/test-results/test/*.xml`: `classes 11, tests 84, failures 0, errors 0`

## Changed This Session

- `residue-001`, which was **not in `feature_list.json` when the session started** — it came from a
  real uploaded report and was recorded as a new entry before it was implemented. It sits at
  priority 1; the six pre-existing features shifted to 2–7 and were otherwise untouched.
- Code: `ContributionTracker.unattributed()` and `ContributionTracker.settle()` added.
  `RunReport.build` settles the field instead of clamping only its negative side.
  `RunReport.write` and `RoomHistory.printSummary` both call `unattributed()` rather than each
  spelling out `roomsCleared - pointsByPlayer().values.sum()`.
- Tests: five new cases in `RunReportTest`. Four of them cover the residue in both directions —
  including one that derives the live value `3.552713678800501e-15` from `DungeonGrid.splitPoints`
  rather than hard-coding it — and one pins that a genuinely unattributed room still counts.
- No version bump, no schema change, no `dist/` refresh, no `build.gradle` line, no harness file
  edited. The release gate at the top of `CLAUDE.md` did not fire.

## Broken Or Unverified

- Known defect: none introduced.
- Unverified path: the fix is proven only through the `RunReport.build` seam. No command in this
  repository can play a floor, so the end-to-end path — points drifting over real rooms, `write`
  calling `unattributed()`, the receiver accepting the result — has not been observed.
  `ContributionTracker.unattributed()`'s own subtraction has no test either: `roomsCleared` and
  `credited` are private and only a real run fills them.
- Still unverified from before, unchanged: everything `ingame-001` lists — calibration, decoration
  mapping, checkmark reading, core hashing, and every pixel of the `/sa` screen.
- Regressions found: none.
- Risk for the next session: **the fix is in source only.** `dist/` still holds the released 0.9.0
  jar, so no install anywhere carries this. It reaches players at the next version bump, which pulls
  the whole release gate with it and is the user's decision.

## Next Best Step

- Highest-priority unfinished feature: `clear-001` — anchor `enterTick` on a minimum stay.
- Why it is next: it is the one open item that makes another number wrong. `clear` is reported as a
  duration but is only an upper bound, so `avgSeconds` on the server cannot be used as a difficulty
  weight until this lands.
- What counts as passing: a `ContributionTrackerTest` that pins the minimum-stay behaviour (that
  class still does not exist — creating it is part of the feature, and both `clear-001` and
  `clearpoints-001` name it in their `verification_command`), the field present in `RunReport`,
  `./gradlew test` green, evidence in `feature_list.json` — **and the receiver's `schema-001`
  deployed first**. A build that sends a field the receiver has not learned gets a `400` per report
  and `TelemetryUpload` never retries it.

## Do Not Touch

- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`). Never edited, never
  regenerated. The receiver reads this exact file.
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate at the top
  of `CLAUDE.md` (tagged GitHub release + Modrinth, same jar, same notes).
- `dist/` by hand — `./gradlew build` refreshes it. Note that this means `build` is not a neutral
  verification command while a fix is sitting unreleased: it is `finalizedBy copyToDist`, so it will
  overwrite `dist/sighteaddons-0.9.0.jar` with a jar that is no longer the 0.9.0 that shipped. Use
  `./gradlew assemble check` for a full check that leaves `dist/` alone.
- The report schema, until the receiver's paired change is deployed. `residue-001` was not one —
  `unattributed` was already in `RUN_KEYS` and already validated as `_real(x, 0, MAX_CLEARED)`.

## Environment Quirks

- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes, not seconds. A run that looks stuck is almost always still downloading. Warm,
  it is ~25 s; it was warm throughout this session and ran in 5–12 s.
- JDK 25+ required (bytecode 25 via `--release`, no pinned toolchain). Gradle uses `JAVA_HOME`, not
  `PATH`, so `init.sh`'s version line can describe a different JDK than the build uses.
- Mappings are official Mojang, not Yarn — class names in this repository are Mojmap.
- `./gradlew runClient` cannot log in to Hypixel. `run/config/sighteaddons/debug/session-<millis>.jsonl`
  from a real install is the only source of real data.
- Git is set to `core.autocrlf=true` on this machine; `/gradlew` and `*.sh` are pinned to LF in
  `.gitattributes` and must stay that way.
- Neither `python` nor `python3` resolves from the Bash tool on this machine (the Windows App
  Execution Alias answers and then refuses). Irrelevant to this repository's own suite, but it means
  a quick scratch script has to be `java` on a single `.java` file instead.

## Commands

- Startup: `./gradlew runClient`
- Smoke check: `./init.sh` (wraps `./gradlew test`)
- Full verification: `./gradlew assemble check` — same coverage as `./gradlew build` without the
  `copyToDist` step that rewrites the released jar
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
