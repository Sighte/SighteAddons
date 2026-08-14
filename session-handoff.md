# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. **140 tests across 13 classes, 0 failures,
  0 skipped — identical to `main`**, which is the expected reading for a comment-and-artifact-only
  branch and is itself part of its evidence. `mod_version=0.9.0`, `dist/sighteaddons-0.9.0.jar`
  unchanged (md5 `b2ebc35ccfeb9cc96134eb3b18f0306f`, measured before and after `assemble check`),
  `RunReport.SCHEMA` still 5, `RunReport.kt` not touched by this branch.
- Branch: `artifacts-001`, off `main` at `9f71b96`. **Not pushed and not merged.** For how many
  commits it carries, run `git rev-list --count 9f71b96..HEAD` and `git log --oneline 9f71b96..HEAD`
  for what each is. **Do not write the number into an artifact** — three consecutive reviews found a
  hand-transcribed count wrong, which is why it is derived rather than stated.
- What verification actually ran (exact commands), all at `54be420`:
  - `bash docs/evidence/session-1786719912927/readout.sh` → `==> READOUT: OK`, exit 0, **35
    assertions ok, 0 failed**. This is `artifacts-001`'s and `ingame-001`'s `verification_command`.
  - `./gradlew test --rerun-tasks` → `classes 13, tests 140, skipped 0, failures 0, errors 0`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 identical either side, and
    `git status --short dist/ gradle.properties` empty
  - `bash init.sh` → `BASELINE: PASSING`
  - `git diff -- src/ | grep -E '^[+-][^+-]' | grep -vE '^[+-]\s*(\*|//|/\*)'` → **empty.** The
    mechanical proof that no runtime behaviour changed. `git diff 9f71b96..HEAD -- src/test/ |
    grep -cE '^[+-]\s*@Test'` → `0`.
  - **Two mutation probes against the evidence file itself, both caught.** Rewriting `Cathedral`'s
    award from 4.5 to 4.0 fails 2 assertions; deleting the one `anchoredOnClear` line fails 7. Both
    restored with `git checkout`, never by writing the file back from Python (see Environment
    Quirks). Re-create them from the descriptions in `artifacts-001`'s evidence.

## Changed This Session

`artifacts-001` — an artifact pass. **The hard constraint was: change no runtime behaviour**, and it
held. The one thing that wanted a code change was recorded as a feature instead.

- **The first real dungeon run is in the repository**: `docs/evidence/session-1786719912927/`. M7,
  solo, 2026-08-14, on a debug build of `72e0825` — the `clearpoints-001` formula, *before*
  `clearpoints-002` changed the weights. 143 of the 220 lines verbatim (everything but the
  `player_room` position stream, three of which are kept as a sample), a README saying what the run
  settles and at equal length what it does not, and `readout.sh`, which **asserts** every figure the
  README quotes.
- **Measured, and recorded on the features they belong to:** `MIN_TICKS` — nine of ten anchors
  stamped exactly 19 ticks after their stay began, `New Trap` at 24, closing `clear-001` note (1);
  `anchorOnClear` fires **one in ten**, not the three the KDocs estimated, closing `clear-001` note
  (3); calibration, room naming, core hashing and **checkmark reading in both directions** giving
  `ingame-001` its first evidence.
- **Six stale statements retired.** `clearpoints-001`'s note now carries a supersession marker and
  its formula paragraph is past tense (kept, not deleted); `ingame-001`'s dead cross-reference to
  deleted weight constants now names where the concern moved; the real-M7 figures in three artifacts
  now cite committed evidence; `TIME_EXPONENT`'s calibration now names its proxy in both KDocs that
  carried it; `ingame-001`'s `verification_command` grepped for `"roomName"`, which is not a
  `DebugLog` key anywhere and sat under the gitignored `run/`, so it would have returned 0 against a
  perfect file; and `quality-document.md`'s own preamble said "nothing here has run against real
  Hypixel data".
- **New feature recorded rather than built: `runloss-001`.** The M7 wrote no `run_end` and no run
  report — ten cleared rooms permanently gone from the box.
- **Grades moved:** room naming C → B and the Map reading layer C → B, on real data rather than new
  code. Telemetry deliberately stayed B despite the measured loss; the reasoning is in the table.

## Broken Or Unverified

- Known defect: none introduced. One **found and not fixed** — `runloss-001`, see below.
- **`runloss-001` is a measured, permanent data loss and it is unfixed.** Quitting the game straight
  from a dungeon writes no run report, because `RunReport.write` is reachable only from the
  end-of-run chat headline and from `ClientPlayConnectionEvents.JOIN` (`SighteAddons.kt:53-57`) and
  that exit produces neither. It happened on 2026-08-14 and cost ten cleared rooms. `history.jsonl`
  kept its 14 lines, so only the report and the receiver's copy are gone.
- **Unverified — the party half of everything, and one real run has not changed that.** The M7 was
  **solo and deathless**: one player in tab, `decoIndex` 0 throughout, `roster_skew` fired zero
  times. So `party-001` is untouched by it, and `clear-001`'s note (2) — the zero-margin gap
  tolerance — is still open, because its failure mode is the roster-skew blackout around a death in a
  party. **One party floor would move both.** Do not let "a real run happened" be read as covering
  them; `party-001`'s notes say so explicitly for exactly this reason.
- **Unverified — the `RED` checkmark path and every pixel of the `/sa` screen.** Neither occurred in
  the run and no session file can show the second.
- **Unverified — the measured half of the scoring model, entirely.** Unchanged: every room is on its
  seed and will be until something serves the averages (`scores-fetch-001`). The M7 does not help —
  it was scored under the **old** formula and its report never reached the box, so it added no
  `clearStay` samples anywhere.
- **Unverified — layer 2 on a real install.** Unchanged; nothing writes a cache yet.
- **Unverified — the wiring, still.** `tick()` needs a `Minecraft` and a `MapItemSavedData`, so that
  it calls `onCleared` once per clear and `onPresence` once per member per tick is read, not
  asserted. `onCleared` itself is covered.
- Unverified: the cross-repo reading that `unattributed` is only ever consumed as a ratio against
  `roomsCleared`. Unchanged. **`SighteAddonServerside` was neither read nor written this session** —
  nothing here goes near the wire.
- Regressions found: none.
- Risk for the next session: unchanged — **the schema is 5 in source and 4 in every install**, and
  five features now exist in source only. Nothing breaks meanwhile; nothing reaches a player either,
  until somebody bumps the version and takes the release gate, which is the user's decision.

## Next Best Step

- **First, a fresh evaluator pass on `artifacts-001`.** It has never been evaluated and it is the
  only `passing` entry this branch adds. The things to re-run: its `verification_command`
  (`bash docs/evidence/session-1786719912927/readout.sh`, expect 35 ok / 0 failed), the whole suite
  (expect 140, *identical to `main`* — a change there would mean a KDoc edit moved a fixture), the
  comment-only diff check, and the two evidence mutation probes. **The evaluator must not be this
  session.** Judge one thing specifically: whether the excerpt is an honest subset — 77 `player_room`
  lines were left out, and whether that was the right call is the judgement most worth a second pair
  of eyes.
- **Then `runloss-001`, and it is the strongest candidate on the list.** Priority 10 is queue
  position, not importance: it is the only entry known to have destroyed real data, it needs no
  receiver change and no real dungeon to verify the write path, and every `clearStay` sample the box
  is waiting for has to survive it to arrive at all — so it is upstream of `clearpoints-002`'s
  measured half being worth anything. Read its notes first: the fix is *when* `RunReport.write` is
  called, and whether the player is still resolvable at `DISCONNECT` is something to **measure**
  rather than assume.
- Otherwise `chat-001`, unchanged: read the events Hypixel puts in chat. Its `ChatEventsTest` does
  not exist yet and creating it is part of the feature. Check before starting whether anything it
  adds reaches `RunReport.kt`; if it does, diff against `RUN_KEYS` in the receiver's `ingest.py`
  first and **the receiver goes first**.
- `runend-001` is now cheaper than it was: **its open question is answered.** The M7 had one
  `unattributed` event and zero genuinely unattributed rooms, so write the run-level count, not the
  event count. The reasoning is in its notes.
- **Do not start `scores-fetch-001`.** Still blocked on the receiver serving `roomstats.json`, which
  its `do_GET` does not.
- **If the user offers another run, ask for a party floor with a death in it.** That one file would
  move `party-001`, `clear-001`'s last open note and the rest of `ingame-001` at once.

## Do Not Touch

- **`docs/evidence/session-1786719912927/` is evidence, not documentation.** Do not "tidy" the
  excerpt, do not re-sort it, and do not extend it with lines from a different run. If a claim in it
  turns out wrong, add a correction to the README and change the assertion; do not edit the `.jsonl`
  to match a claim. `readout.sh` is what makes that discipline enforceable — a trimmed excerpt fails
  seven assertions, measured.
- **`unattributed` must stay a count of *rooms*.** Unchanged and still the silent failure this
  project has spent three sessions removing: `roomsCleared - pointsByPlayer().values.sum()` clamps to
  `0.0` on every run forever, with no exception, no `400` and no log line, and the receiver reads this
  field *relative to* `roomsCleared` as its only diagnostic for a broken decoration→player mapping.
  `weighting cannot silence the unattributed count` is the guard.
- **`RunReport.SCHEMA`, 5, must not move and must not go back down.**
- **The seed keys are `rooms.json`'s spelling** — `Ice Fill` and `Water Board`, two words each. A typo
  does not fail, it silently misses and the room drops to the ordinary puzzle seed.
- **The metric is `clearStay` and only `clearStay`.** `clear` is the same span under the schema-4
  anchor, `afterClear` is the post-checkmark secret hunt, `secretRun` is not a clear time at all.
  Note that `TIME_EXPONENT`'s calibration is drawn from `clear` **because `clearStay` is `n=0`
  everywhere** — both KDocs now say so, and that is a documented exception, not licence to read the
  wrong key in code.
- **Do not re-add a bundled snapshot of `roomstats.json` to the jar.** Built and deliberately removed.
- **`clearpoints-001`'s notes are history and are marked as such.** Do not delete the superseded
  paragraphs — they are the record of what was built, and their load-bearing half is still live. Do
  not read them as a description of live behaviour either.
- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`). Never edited, never
  regenerated, and never adjusted to make a weight come out nicer.
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate. The notes
  for the next release now owe five things: the schema moved to 5, older installs are unaffected
  because the receiver still accepts 4, room points are no longer flat, room points changed meaning
  again so old and new standings are not comparable, and — if `runloss-001` lands first — that runs
  ended by quitting from inside a floor used to be discarded.
- `dist/` by hand — and **`./gradlew build` is not a neutral verification command** while fixes sit
  unreleased: it is `finalizedBy copyToDist` and `copyToDist dependsOn cleanDist`. Use
  `./gradlew assemble check`. `README.md`'s "Build" section still says `./gradlew build`, correctly —
  it is the contributor build instruction. Do not follow it mid-feature, and do not "fix" it.
- **Past session entries in `claude-progress.md`.** Supersede in a new entry; do not rewrite history.
- `SighteAddonServerside`. It was neither read nor written this session. A change needed there is a
  paired feature and a different session.
- `evaluator-rubric.md`'s structure. Harness file; changes need the user to ask.

## Environment Quirks

- **The real session file lives outside this repository** and only an excerpt is committed. The
  original is at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-1786719912927.jsonl`
  on this machine, alongside `history.jsonl` and `runs/`. Treat it as read-only: it is the user's game
  directory, not a working tree.
- **`*.jsonl` is now pinned to LF in `.gitattributes`**, added this session so the committed evidence
  cannot change bytes depending on whose machine checked it out. Every other `.jsonl` in the tree is
  under the gitignored `config/`, so nothing else is affected.
- **`RoomStats` reads a file from outside this repository** —
  `FabricLoader.getInstance().configDir.resolve("sighteaddons/roomstats.json")`, which under the test
  runtime is `<project>/config/sighteaddons/` and `config/` is gitignored. `ContributionTrackerTest`
  and `RoomDatabaseTest` pin `RoomStats.use(RoomScores.NONE)` in `@BeforeEach` and release it in
  `@AfterEach`. **Any new test that touches `weightOf` must do the same**, or it will pass for whoever
  has no cache file and fail for whoever does.
- **Restore a mutated file with `git checkout`, not by writing it back from Python.** Git is
  `core.autocrlf=true` here, so the working copy is CRLF; Python reading with universal newlines and
  writing with `newline=''` converts the file to LF and leaves it looking modified. This bit the
  evidence probes this session too, which is why both restore with `git checkout`.
- **Windows Python cannot execute `./gradlew`** — `WinError 193`. Drive Gradle from bash.
- **Windows Python resolves `/tmp/x` as `C:\tmp\x`**, Git Bash resolves it elsewhere. Use `/c/tmp/...`
  from bash for anything the two share, or keep it in `build/`.
- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes. Warm it is ~10 s and it was warm throughout this session.
- **`DungeonSession.floor` is writable from a test by reflection**:
  `DungeonSession::class.java.getDeclaredField("floor")`, `isAccessible = true`,
  `field.set(DungeonSession, "F7")`. **Do not call `DungeonSession.reset()` to clean up** — it resets
  half the mod. Set the field back to null in a `finally`.
- **`DebugLog.event` is safe to call from a unit test** — it resolves `Config` and `FabricLoader`
  without throwing. **A consequence: `./gradlew test` writes
  `config/sighteaddons/debug/session-<millis>.jsonl` into the working tree**, one per JVM run.
  `config/` is gitignored since `ddfddc0`.
- **`ContributionTracker` is an `object` with run-long state**, so any test that writes to it must
  `reset()` first. `ContributionTrackerTest` does it in `@BeforeEach`; the suite runs sequentially.
- The live box is reachable read-only (`ssh -i ~/.ssh/sighte_box -o IdentitiesOnly=yes
  root@217.160.51.229`). **It was not used this session** — nothing here needed it. Last measured in
  session 008: 83 rooms in `/srv/sighte/roomstats.json`, 9 with a `clear` sample, sum of every
  `clearStay.n` = 0.
- JDK 25+ required. Gradle uses `JAVA_HOME`, not `PATH`. Measured here: 25.0.4.
- Mappings are official Mojang, not Yarn — class names here are Mojmap.
- `./gradlew runClient` cannot log in to Hypixel. A `session-<millis>.jsonl` from a real install is
  the only source of real data, and there is now exactly one in the repository.
- Git is `core.autocrlf=true`; `gradlew` and `*.sh` are pinned to LF in `.gitattributes` and must stay
  that way. Kotlin sources warn `LF will be replaced by CRLF` on `git add`; that is normal here.
- **`git commit -m` with a PowerShell-style `@'...'@` here-string silently embeds the `@` markers as
  the first and last lines of the message.** Write the message to a file and use `git commit -F`.
- **`python` resolves and works**; `python3` is a Windows App-Execution-Alias stub one level up.

## Commands

- Startup: `./gradlew runClient`
- Smoke check: `./init.sh` (wraps `./gradlew test`)
- Full verification: `./gradlew assemble check` — same coverage as `./gradlew build` without the
  `copyToDist` step that rewrites the released jar
- Read out the real run: `bash docs/evidence/session-1786719912927/readout.sh`
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
