# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. **140 tests across 13 classes, 0 failures,
  0 skipped** — up from 120. `mod_version=0.9.0`, `dist/sighteaddons-0.9.0.jar` unchanged (md5
  `b2ebc35ccfeb9cc96134eb3b18f0306f`, measured before and after `assemble check`),
  `RunReport.SCHEMA` still 5 and `RunReport.kt` untouched by this branch
  (`git diff --name-only fa075bd | grep -c RunReport` → 0).
- Branch: `clearpoints-002`, off `main` at `fa075bd`. **Not pushed and not merged.** For how many
  commits it carries, run `git rev-list --count fa075bd..HEAD` and `git log --oneline fa075bd..HEAD`
  for what each is. **Do not write the number into an artifact** — three consecutive reviews found a
  hand-transcribed count wrong, which is why it is derived now instead of stated.
- What verification actually ran (exact commands), all at `0d81667`:
  - `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RoomDatabaseTest' --tests 'sighteaddons.RoomStatsTest' --rerun-tasks`
    → `BUILD SUCCESSFUL`; `ContributionTrackerTest tests=42 failures=0`, `RoomDatabaseTest tests=8
    failures=0`, `RoomStatsTest tests=9 failures=0`
  - `./gradlew test --rerun-tasks` → `classes 13, tests 140, skipped 0, failures 0, errors 0`
  - `bash init.sh` → `BASELINE: PASSING`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 identical either side, and
    `git status --short dist/ gradle.properties` empty
  - **Seven mutation probes, all re-run against the committed source and all caught.** The harness is
    `build/probe.sh` (gitignored, so re-create it if you want it — the probes themselves are written
    out in `feature_list.json`'s evidence). It restores each file with `git checkout` rather than
    from memory, because writing the file back from Python turns CRLF into LF here.

## Changed This Session

`clearpoints-002` — the feature the list had recorded as `blocked` on data. **The blocker was
resolved by the user, not by data arriving**, and correcting that entry was part of the task.

- **Five constants deleted**: `PUZZLE_BONUS`, `TRAP_BONUS`, `MINIBOSS_BONUS`, `BLOOD_BONUS`,
  `SEGMENT_POINTS`, plus `kindBonus`. Size and kind are emergent now — a 1x4 earns its points by
  measuring slow rather than by being a 1x4.
- **The model**: `weight = base + 0.25 per database secret`;
  `base = seed + n/(n+10) * (measured - seed)`;
  `measured = 0.75 * (avgTicks / median) ^ 0.5`, clamped to `[0.25, 2.5]`. The seed is the user's
  table — `Ice Fill` 2.0, `Water Board` 1.5, `Quiz` 1.0, any other puzzle 1.0, everything else 0.75 —
  and it is a **prior, not a constant**. Every number is argued in the KDoc where it is made.
- **Three resolution layers, bottom two implemented** (`RoomStats`): a fetch that does not exist, a
  cached file at `configDir/sighteaddons/roomstats.json`, and the seeds. **No network call.** The
  first design was a snapshot bundled in the jar; it was written, then dropped mid-implementation on
  the user's push-back, and the reason decides it — if improving the values needs a jar release, they
  improve when somebody does release work rather than on their own.
- **`RoomScores.generatedTs` is recorded** on the `award` and new `room_scores` debug events and as a
  `scoresTs` key on every `history.jsonl` line, so a past run's points stay explainable once the
  scores start moving. Not on the run report — an unknown key there is a `400`.
- **Two existing tests replaced, neither weakened**, plus one fixture threshold loosened. The full
  accounting is in `clearpoints-002`'s second evidence entry; read it before concluding anything was
  quietly dropped.
- New feature recorded rather than built: `scores-fetch-001` (layer 1), `blocked` on the receiver.

## Broken Or Unverified

- Known defect: none introduced.
- **Unverified — the measured half of the model, entirely.** Every room is on its seed today and will
  be until something serves the averages. `blend` is tested at every property that matters and
  mutation-probed seven ways, but no room in any real install has ever had a non-zero `n`. This is
  not a gap in the code; it is what `scores-fetch-001` is for.
- **Unverified — layer 2 on a real install.** The cache path is exercised only by `@TempDir`
  fixtures. Nothing writes a cache yet, so no `roomstats.json` has ever been read from a game
  directory.
- **Unverified — whether the weights separate players in a way a player would agree with.** Same
  ceiling `clearpoints-001` had, unchanged: it needs a floor played with the mod installed. Rides
  with `ingame-001`.
- **Unverified — the wiring, still, and it is a genuine limit.** `tick()` needs a `Minecraft` and a
  `MapItemSavedData`, so that it calls `onCleared` exactly once per clear (and `onPresence` once per
  member per tick) is asserted by reading the code and by nothing else. There is no reflection trick
  available: the missing objects are constructor arguments, not private fields. `onCleared` itself is
  covered.
- Unverified: the cross-repo reading that `unattributed` is only ever consumed as a ratio against
  `roomsCleared`. Unchanged from before. `SighteAddonServerside` was **read** this session
  (`ingest.py` for `RUN_KEYS`/`ROOM_KEYS`, `roomstats.py` for the document shape) and **never
  written**, which is what `CLAUDE.md` allows.
- Still unverified from before, unchanged: everything `ingame-001` lists — calibration, decoration
  mapping, checkmark reading, core hashing, and every pixel of the `/sa` screen.
- Regressions found: none.
- Risk for the next session: unchanged — **the schema is 5 in source and 4 in every install**, and
  four features (`residue-001`, `clear-001`, `clearpoints-001`, `clearpoints-002`) exist in source
  only. Nothing breaks meanwhile; nothing reaches a player either, until somebody bumps the version
  and takes the release gate, which is the user's decision. **The release notes now owe one more
  line: room points changed meaning and old standings are not comparable to new ones.**

## Next Best Step

- **First, a fresh evaluator pass on `clearpoints-002`.** It has never been evaluated. The things to
  re-run are the feature's own `verification_command` (expect `ContributionTrackerTest tests=42`),
  the whole suite, and the seven mutation probes recorded in the evidence — in particular
  `clearStay` → `clear`, which is the only silent failure this feature can have, and the
  seed-key misspelling, which is the other. The evaluator must not be this session.
- **A real session file may still be about to exist.** A debug build from `72e0825` was installed and
  playing a real floor during session 007, and the file has not come back. If the user hands one
  over it unblocks `ingame-001` and three cross-referenced findings. Note that the build in their
  game predates this branch, so it scores rooms under `clearpoints-001`'s formula — its `award`
  events carry no `scoresTs`, and its point numbers are the old ones.
- Otherwise the highest-priority unfinished feature: `chat-001` — read the events Hypixel puts in
  chat. Its `ChatEventsTest` does not exist yet and creating it is part of the feature. Check before
  starting whether anything it adds reaches `RunReport.kt`; if it does, diff against `RUN_KEYS` in
  the receiver's `ingest.py` first and the receiver goes first.
- **Do not start `scores-fetch-001`.** It is blocked on the receiver serving `roomstats.json`, which
  it does not — `do_GET` answers `/health` and 404s everything else. That endpoint is a feature on
  the receiver's list, not this one, and it was reported to the orchestrator rather than written
  there from here.
- Cheaper alternative if a short session is wanted: `runend-001`, one field on one debug event, but
  read its notes — decide which of the two `unattributed` numbers the receiver's analyst wants before
  writing the line.

## Do Not Touch

- **`unattributed` must stay a count of *rooms*, and nothing may go back to deriving it by
  subtraction.** Unchanged from `clearpoints-001` and now more important, not less: the weighted
  score has moved again, so `roomsCleared - pointsByPlayer().values.sum()` is still the silent
  failure it always was — it clamps to `0.0` on every run forever, with no exception, no `400` and no
  log line, and the receiver reads this field *relative to* `roomsCleared` as its only diagnostic for
  a broken decoration→player mapping. `weighting cannot silence the unattributed count` is the guard.
- **`RunReport.SCHEMA`, 5, must not move for this feature and must not go back down.** Nothing this
  feature adds goes near the wire: the per-player breakdown is not in `RUN_KEYS`, `unattributed`'s
  values are unchanged, and `scoresTs` was deliberately kept out of the report because a key the
  validator has not learned is a `400` the client never retries.
- **The seed keys are `rooms.json`'s spelling** — `Ice Fill` and `Water Board`, two words each. A
  typo here does not fail, it silently misses and the room drops to the ordinary puzzle seed, which
  is a plausible number. `the seeded rooms are spelled the way the database spells them` checks
  against the bundled database rather than against the seed map, which is the only way it is a guard.
- **The metric is `clearStay` and only `clearStay`.** `clear` is the same span under the schema-4
  anchor (an upper bound a walk-through inflates), `afterClear` is the post-checkmark secret hunt the
  user explicitly excluded, `secretRun` is not a clear time at all. `roomstats.py` never mixes the
  buckets, so reading the wrong one produces plausible numbers off the wrong measurement and nothing
  downstream would say so.
- **Do not re-add a bundled snapshot of `roomstats.json` to the jar.** It was built and deliberately
  removed. A weighting that only improves at release time does not improve on its own, which is the
  whole feature.
- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`). Never edited, never
  regenerated, and in particular never adjusted to make a weight come out nicer.
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate at the top of
  `CLAUDE.md`. The notes for the next release now have four things to say: the schema moved to 5,
  older installs are unaffected because the receiver still accepts 4, room points are no longer flat,
  and **room points changed meaning again — old and new standings are not comparable**.
- `dist/` by hand — and **`./gradlew build` is not a neutral verification command** while fixes sit
  unreleased: it is `finalizedBy copyToDist` and `copyToDist dependsOn cleanDist`, so it deletes and
  replaces `dist/sighteaddons-0.9.0.jar` with a jar that is no longer the released 0.9.0. Use
  `./gradlew assemble check`. `README.md`'s "Build" section still says `./gradlew build`, correctly —
  it is the contributor build instruction. Do not follow it mid-feature, and do not "fix" it either.
- **Past session entries in `claude-progress.md`.** Supersede in a new entry; do not rewrite history.
- `SighteAddonServerside`. Read it — the schema diff `CLAUDE.md` requires means reading `ingest.py`
  and `roomstats.py` — but a change needed there is a paired feature and a different session. In
  particular **do not add the scores endpoint to its `feature_list.json` from here.**
- `evaluator-rubric.md`'s structure. Harness file; changes need the user to ask.

## Environment Quirks

- **`RoomStats` reads a file from outside this repository** —
  `FabricLoader.getInstance().configDir.resolve("sighteaddons/roomstats.json")`, which under the test
  runtime is `<project>/config/sighteaddons/` and `config/` is gitignored. `ContributionTrackerTest`
  and `RoomDatabaseTest` therefore pin `RoomStats.use(RoomScores.NONE)` in `@BeforeEach` and release
  it in `@AfterEach`. **Any new test that touches `weightOf` must do the same**, or it will pass for
  whoever has no cache file and fail for whoever does.
- **Windows Python cannot execute `./gradlew`** — `subprocess.run(['./gradlew', ...])` raises
  `WinError 193` (not a valid Win32 application) and `'gradlew.bat'` without a path raises
  `WinError 2`. Drive Gradle from bash. Cost a working mutation harness two rewrites this session.
- **Windows Python resolves `/tmp/x` as `C:\tmp\x`, while Git Bash resolves it as something else**,
  so a file written by `python` and read by `cp` in the same script is not the same file. Use
  `/c/tmp/...` from bash for anything the two have to share, or keep it in `build/`.
- **Restore a mutated source file with `git checkout`, not by writing it back from Python.** Git is
  `core.autocrlf=true` here, so the working copy is CRLF; Python reading with universal newlines and
  writing with `newline=''` converts the whole file to LF and leaves it looking modified.
- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes, not seconds. Warm it is ~6 s and it was warm throughout this session.
- **`DungeonSession.floor` is writable from a test by reflection**, measured in session 007 and
  relied on by one case: `DungeonSession::class.java.getDeclaredField("floor")`,
  `isAccessible = true`, `field.set(DungeonSession, "F7")`. **Do not call `DungeonSession.reset()` to
  clean up**: it resets half the mod. Set the field back to null in a `finally` instead.
- **`DebugLog.event` is safe to call from a unit test** — it resolves `Config` and `FabricLoader`
  without throwing. That is what makes driving `award()` and `RoomStats.resolve()` from a test
  possible at all.
- **A consequence: `./gradlew test` writes `config/sighteaddons/debug/session-<millis>.jsonl` into
  the working tree**, one per JVM run. `config/` is in `.gitignore` since `ddfddc0`.
- **`ContributionTracker` is an `object` with run-long state**, so any test that writes to it must
  `reset()` first. `ContributionTrackerTest` does it in `@BeforeEach`; the suite runs sequentially.
- **The live box is reachable read-only and was used this session** to re-measure the blocker rather
  than to trust the note: `ssh -i ~/.ssh/sighte_box -o IdentitiesOnly=yes root@217.160.51.229`, then
  a `python3 -c` fold over `/srv/sighte/roomstats.json`. 83 rooms, 9 with a `clear` sample, sum of
  every `clearStay.n` = 0. Nothing on the box was written.
- JDK 25+ required. Gradle uses `JAVA_HOME`, not `PATH`. Measured here: 25.0.4.
- Mappings are official Mojang, not Yarn — class names in this repository are Mojmap.
- `./gradlew runClient` cannot log in to Hypixel. A `session-<millis>.jsonl` from a real install is
  the only source of real data.
- Git is `core.autocrlf=true`; `gradlew` and `*.sh` are pinned to LF in `.gitattributes` and must stay
  that way. Kotlin sources warn `LF will be replaced by CRLF` on `git add`; that is normal here.
- **`git commit -m` with a PowerShell-style `@'...'@` here-string silently embeds the `@` markers as
  the first and last lines of the message.** Write the message to a file and use `git commit -F`.
- **`python` resolves and works**; `python3` is a Windows App-Execution-Alias stub one level up.
  Measure before relying on either.

## Commands

- Startup: `./gradlew runClient`
- Smoke check: `./init.sh` (wraps `./gradlew test`)
- Full verification: `./gradlew assemble check` — same coverage as `./gradlew build` without the
  `copyToDist` step that rewrites the released jar
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
