# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. **101 tests across 12 classes, 0 failures,
  0 skipped** — up from 84, with no existing test removed or weakened. `mod_version=0.9.0`,
  `dist/sighteaddons-0.9.0.jar` unchanged.
- Branch: `clear-001`, off `main` at `b588cc4`. Two commits, **not pushed and not merged**.
- What verification actually ran (exact commands), all at `a6d92b6`:
  - `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RunReportTest' --rerun-tasks`
    → `BUILD SUCCESSFUL in 7s`, `7 actionable tasks: 7 executed`;
    `ContributionTrackerTest tests=16 failures=0`, `RunReportTest tests=21 failures=0`
  - `bash init.sh` → `BASELINE: PASSING`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, and `git status --short dist/ gradle.properties` empty
  - Test totals from `build/test-results/test/*.xml`: `classes 12, tests 101, failures 0, errors 0`
  - Two mutation checks, both reverted immediately, both recorded as evidence: reverting `SCHEMA` to
    4 fails 2 tests; reverting the anchor to first-sighting fails 9 of the 16 new ones.

## Changed This Session

- `clear-001` → `passing`. The clear anchor is a **stay**, not a sighting.
  - `TrackedRoom` keeps a current stay per member (`start`, `ticks`, `lastSeen`) beside the run-long
    tick total attribution already used. `enteredAtTick` is now `private set`.
  - `onPresence(player, at)` stamps the anchor at the **start** of the first stay to reach
    `MIN_TICKS` — its own start, not the tick it qualified at, which would shorten every clear in the
    data by a flat second. A sighting gap of more than `MIN_TICKS` begins a new stay; shorter ones do
    not, because `PartyTracker.positions` reports no teammate positions at all for the 10–20 ticks
    around a death and a stay must not be split by our own blind spot.
  - `anchorOnClear(at)` is the fallback for a room that clears before anyone qualifies — the empty
    1x1s. Without it the server's mean would be built from every room *except* the fastest, which is
    a bias rather than sparsity. Its window bounds it: every span it can produce is under a second.
  - Nothing anchors after the clear, so `enterTick <= clearTick` holds whenever both are set. A
    `preCleared` room therefore keeps a null anchor; `roomstats.py:102` skips those anyway.
  - The first-sighting assignment in `discover` is gone; its `ponytail:` note now names one remaining
    ceiling (decoration lag) instead of two.
- **`RunReport.SCHEMA` 4 → 5.** The load-bearing line. See "Do Not Touch".
- New `ContributionTrackerTest`, 16 cases — the class `clearpoints-001` also names in its
  `verification_command`, and which no previous session had created.
- Two existing `RunReportTest` assertions updated because this feature deliberately changes what they
  pin: `run context survives` now expects `v = 5`, and the shared room fixture earns its anchor
  through `onPresence` instead of assigning it, landing on the same `120` as before.
- `clear-001`'s `notes` corrected in place: its claim that this pair risked a `400` and would lose
  every run of the build was false, and was checked rather than trusted. `ingest.py:214` validates
  `v` as `_num(x, 1, 10)`; `ingest.py:159` has had `enterTick` in `ROOM_OPTIONAL` since schema 3.
- No version bump, no `dist/` refresh, no `build.gradle` line, no harness file edited, and
  `SighteAddonServerside` was read but never written. The release gate did not fire.

## Broken Or Unverified

- Known defect: none introduced.
- **Unverified path — the wiring.** The anchor is proven only at the `TrackedRoom` seam.
  `ContributionTracker.tick` needs a `Minecraft` and a `MapItemSavedData`, so *that* it calls
  `onPresence` once per member per tick with the run clock, and `anchorOnClear` exactly on the clear
  and before `clearedAtTick` is set, is asserted by reading the code and by nothing else. No command
  in this repository can observe it.
- **Unverified assumption — the gap tolerance.** That a stay survives a sighting blackout is reasoned
  from the 10–20 tick roster-skew window `PartyTracker.positions` documents, not measured against a
  real decoration stream. If real blackouts are longer, some rooms will anchor about a second late.
- Unverified: how often the `anchorOnClear` fallback actually fires on a real floor, and therefore
  how many rooms end up with a null anchor. The `room_anchored` and `cleared` debug events were added
  to make exactly that answerable from a session file — that is the first thing to look at when one
  arrives.
- Still unverified from before, unchanged: everything `ingame-001` lists — calibration, decoration
  mapping, checkmark reading, core hashing, and every pixel of the `/sa` screen.
- Regressions found: none.
- Risk for the next session: **the schema is 5 in source and 4 in every install.** That is correct
  and harmless — the receiver accepts both and buckets their clear spans apart — but it means the
  receiver's `clearStay` metric stays at `n = 0` for all 83 rooms until a release happens, and a
  release is the user's decision. Do not read an empty `clearStay` as this feature failing.

## Next Best Step

- Highest-priority unfinished feature: `clearpoints-001` — weight rooms instead of counting them.
- Why it is next: it is the only remaining item that makes a number the mod already reports wrong
  rather than merely absent. Every room is worth exactly 1 point today, which reproduces the flat
  weighting the whole ClearPoints metric exists to replace, and it is what holds the scoring domain
  at C in `quality-document.md`.
- What counts as passing: its own `verification_command`
  (`ContributionTrackerTest` + `RoomDatabaseTest`) green, `./gradlew test` green over everything
  else, evidence in `feature_list.json`. `ContributionTrackerTest` now exists, so that part of the
  setup is done. Check before starting whether it needs a schema change — the point split feeds
  `unattributed`, which the receiver validates as `_real(x, 0, MAX_CLEARED)`, so a *weighted* total
  may exceed `roomsCleared` in a way the current range and the current field meaning do not expect.
  If it does, it is one feature per repository and **the receiver goes first**.

## Do Not Touch

- **`RunReport.SCHEMA`, now 5, must not go back down while `enterTick` is stay-anchored.** This is
  the one mistake on this pair that nothing would report: no `400`, no log line, no failing request.
  `roomstats.py` routes the clear span on `v` alone (`STAY_ANCHOR_SCHEMA = 5`) — below 5 into
  `clear`, at or above into `clearStay` — and `profiles/` is append-only, so a stay-anchored span
  filed as v4 contaminates the old average permanently. The test
  `the stay anchor only ships under a schema that says so` is the guard; it is written as `>= 5` so a
  later bump passes and a revert does not.
- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`). Never edited, never
  regenerated. The receiver reads this exact file.
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate at the top of
  `CLAUDE.md` (tagged GitHub release + Modrinth, same jar, same notes). Note that the notes for the
  next release have two things to say that this session created: the schema moved to 5, and older
  installs are unaffected because the receiver still accepts 4.
- `dist/` by hand — and note that `./gradlew build` is **not** a neutral verification command while a
  fix sits unreleased: it is `finalizedBy copyToDist` and `copyToDist dependsOn cleanDist`, so it
  deletes and replaces `dist/sighteaddons-0.9.0.jar` with a jar that is no longer the released 0.9.0.
  Use `./gradlew assemble check`.
- `SighteAddonServerside`. Read it — the schema diff `CLAUDE.md` requires before touching
  `RunReport.kt` means reading `ingest.py` and `roomstats.py` — but a change needed there is a paired
  feature and a different session.

## Environment Quirks

- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes, not seconds. A run that looks stuck is almost always still downloading. Warm,
  it is ~25 s; it was warm throughout this session and ran in 4–17 s.
- JDK 25+ required (bytecode 25 via `--release`, no pinned toolchain). Gradle uses `JAVA_HOME`, not
  `PATH`, so `init.sh`'s version line can describe a different JDK than the build uses.
- Mappings are official Mojang, not Yarn — class names in this repository are Mojmap.
- `./gradlew runClient` cannot log in to Hypixel. `run/config/sighteaddons/debug/session-<millis>.jsonl`
  from a real install is the only source of real data.
- Git is set to `core.autocrlf=true` on this machine; `gradlew` and `*.sh` are pinned to LF in
  `.gitattributes` and must stay that way. Kotlin sources warn `LF will be replaced by CRLF` on
  `git add`; that is normal here and not something to fix.
- Neither `python` nor `python3` resolves from the Bash tool on this machine (the Windows App
  Execution Alias answers and then refuses). Irrelevant to this repository's own suite, but it means
  the receiver's own tests cannot be run from here — read its source instead, which is all the
  cross-repo schema diff needs.

## Commands

- Startup: `./gradlew runClient`
- Smoke check: `./init.sh` (wraps `./gradlew test`)
- Full verification: `./gradlew assemble check` — same coverage as `./gradlew build` without the
  `copyToDist` step that rewrites the released jar
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
