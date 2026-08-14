# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. **120 tests across 12 classes, 0 failures,
  0 skipped** — up from 119, with no existing test removed, weakened or changed. `mod_version=0.9.0`,
  `dist/sighteaddons-0.9.0.jar` unchanged (md5 `b2ebc35ccfeb9cc96134eb3b18f0306f`, measured before
  and after `assemble check`), `RunReport.SCHEMA` still 5 and `RunReport.kt` untouched by this branch.
- Branch: `clearpoints-001`, off `main` at `1e27b42`. **Not pushed and not merged.** For how many
  commits it carries, run `git rev-list --count 1e27b42..HEAD` and `git log --oneline 1e27b42..HEAD`
  for what each is. **Do not write the number into an artifact** — three consecutive reviews found a
  hand-transcribed count wrong (one against three, four against six, four against seven), which is
  why it is derived now instead of stated.
- What verification actually ran (exact commands), all at `0795236`:
  - `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RoomDatabaseTest' --rerun-tasks`
    → `BUILD SUCCESSFUL in 6s`, `7 actionable tasks: 7 executed`;
    `ContributionTrackerTest tests=33 failures=0`, `RoomDatabaseTest tests=6 failures=0`
  - `./gradlew test --rerun-tasks` → `classes 12, tests 120, skipped 0, failures 0, errors 0`
  - `bash init.sh` → `BASELINE: PASSING`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 identical either side, and
    `git status --short dist/ gradle.properties` empty
  - Three mutation probes, all reverted immediately and all recorded as evidence: a `floorNumber`
    multiplier fails 1, a master-mode bonus read off the floor string fails 1, and neutering the
    test's own `setFloor` helper fails 1.

## Changed This Session

This session closed an evaluation. The second `evaluator-rubric.md` pass scored `clearpoints-001`
**12/14 — Revise** with one cause, and only that was touched. The feature stays `passing`, and
**no behaviour moved** — the only `src/main` edit is a KDoc. That was a hard constraint: a debug build
of this branch is installed in the user's game and was playing a real floor while this ran, so the
session file they bring back has to still describe this code.

- **The floor exclusion is now guarded, and the claim that it could not be is retracted.** Sessions
  005 and 006 recorded, in `weightOf`'s KDoc and in five artifacts, that `DungeonSession.floor` being
  `private set` behind `inDungeon(Minecraft)` puts a floor guard out of reach, so any such test "would
  pass either way". The evaluator disproved it by writing one.
  `DungeonSession::class.java.getDeclaredField("floor")` with `isAccessible = true` reaches the
  `object`'s backing field; `floorNumber` then reads the real digit.
- **What that cost while it stood:** under a live floor multiplier **all 119 tests at `72e0825`
  passed**. The exclusion was pinned by nothing at all, while three artifacts explained why it could
  not be pinned — and `feature_list.json` called `a room is worth the same however far the run has
  got` "the closest any test in this repository can get to the floor exclusion" when on a floor
  multiplier it catches none of it. That description is corrected in place; the case itself is a good
  guard against a *run-progress* factor and is now sold as only that.
- **The new case: `a room is worth the same on every floor`.** Three floors, because two edits read
  different things — `F1` vs `F7` catches a factor drawn from `floorNumber`, `F7` vs `M7` catches one
  drawn from the floor *string* (a master-mode bonus, invisible to `floorNumber`, which reads 7 for
  both), and `Entrance` is the null reading every other test runs under. Weight and credited points
  are both asserted, since a floor factor could land in `weightOf` or between it and the split in
  `award`.
- **It asserts the reflective write took before it asserts the invariant** — that is what makes it a
  guard rather than the guard-in-name-only session 006 feared. Probed: neuter `setFloor` and the case
  fails with `the floor was not actually set`.
- **The corrections landed in every living artifact**: `ContributionTracker.kt`'s `weightOf` KDoc,
  `ContributionTrackerTest`'s run-progress KDoc, `feature_list.json` (the notes *and* the evidence
  entry that overstated it), `quality-document.md` (the scoring row and a new Change History entry),
  and `claude-progress.md`'s "Current Verified State". Session 006's *log entry* still contains the
  false paragraph and was deliberately left — session entries are the audit trail and
  `claude-progress.md` forbids editing them, so session 007's entry supersedes it explicitly.

## Broken Or Unverified

- Known defect: none introduced.
- **The floor exclusion is no longer in this section.** It is guarded, mutation-checked in both edit
  forms, and the guard is checked against its own degradation. Do not re-add a claim that it cannot
  be tested.
- **Unverified — the weights themselves.** 1.5 for a puzzle and 0.25 a secret are judgement, not
  measurement. That they *separate* rooms is tested; that they separate rooms in a way a player would
  agree with is not, and cannot be until a real run produces a debug session. One may be arriving —
  see Next Best Step.
- **Unverified — the wiring, still, and this one is a genuine limit unlike the floor claim was.**
  `tick()` needs a `Minecraft` and a `MapItemSavedData`, so that it calls `onCleared` exactly once per
  clear (and `onPresence` once per member per tick) is asserted by reading the code and by nothing
  else. There is no reflection trick available here: the missing objects are constructor arguments,
  not private fields. `onCleared` itself is covered.
- Unverified: the cross-repo reading that `unattributed` is only ever consumed as a ratio against
  `roomsCleared`. It is what `agent/AGENT-PROMPT.md:62` says and `roomstats.py` does not read the
  field at all — but no receiver test has been run from here. Nothing this session touched goes near
  the wire, and `SighteAddonServerside` was neither read nor written.
- Still unverified from before, unchanged: everything `ingame-001` lists — calibration, decoration
  mapping, checkmark reading, core hashing, and every pixel of the `/sa` screen.
- **New, small, and the price of the fix:** the floor guard reaches a `private set` field by
  reflection, so renaming `DungeonSession.floor` breaks it at runtime rather than at compile time.
  Mitigated inside the test — the `floorNumber` assertions make that failure loud and self-naming
  rather than silent. Recorded in `quality-document.md` and in `clearpoints-001`'s notes.
- Regressions found: none.
- Risk for the next session: unchanged — **the schema is 5 in source and 4 in every install**, and
  three features (`residue-001`, `clear-001`, `clearpoints-001`) exist in source only. Nothing breaks
  meanwhile; nothing reaches a player either, until somebody bumps the version and takes the release
  gate, which is the user's decision.

## Next Best Step

- **First, a fresh evaluator pass on `clearpoints-001`.** It was left at Revise 12/14 twice, and the
  single cause of the second one is now closed. The three things to re-run are the feature's own
  `verification_command` (expect `ContributionTrackerTest tests=33`), the floor multiplier in both
  forms (each must fail exactly `a room is worth the same on every floor` and nothing else), and the
  `setFloor` neutering probe (must fail with `the floor was not actually set`). Nothing more should be
  built on this branch until somebody who did not implement it re-runs those.
- **A real session file may be about to exist.** A debug build from `72e0825` of this branch was
  installed and playing a real floor while this session ran. If the user hands one over, it unblocks
  more than anything a session can do here: `ingame-001` entirely, plus the three findings its notes
  cross-reference — `clear-001`'s gap tolerance and `anchorOnClear` frequency, and this feature's
  weight constants. Read it before starting `chat-001`.
- Otherwise the highest-priority unfinished feature: `chat-001` — read the events Hypixel puts in
  chat. `records-001` is deferred by the user (a product decision — rooms stay global — not a
  technical blocker), and `ingame-001` cannot be finished by any session here.
- What counts as passing for `chat-001`: its own `verification_command`
  (`./gradlew test --tests 'sighteaddons.ChatEventsTest'`) green — **that class does not exist yet,
  and creating it is part of the feature** — plus `./gradlew test` green over everything else and
  evidence in `feature_list.json`. Check before starting whether anything it adds reaches
  `RunReport.kt`; if it does, diff against `RUN_KEYS` in the receiver's `ingest.py` first and the
  receiver goes first.
- Cheaper alternative if a short session is wanted: `runend-001`, one field on one debug event, but
  read its notes — decide which of the two `unattributed` numbers the receiver's analyst actually
  wants before writing the line.

## Do Not Touch

- **Runtime behaviour on this branch, while the user's installed debug build is out there.** A build
  from `72e0825` is in their game. Until the session file comes back and has been read, a behaviour
  change here means the file describes code that no longer exists. Documentation and tests are free;
  `weightOf`'s arithmetic is not.
- **`unattributed` must stay a count of *rooms*, and nothing may go back to deriving it by
  subtraction.** This is the one mistake on this feature that nothing would report. It was
  `roomsCleared - pointsByPlayer().values.sum()`, which was only correct while a room was worth
  exactly 1.0 point and a count and a score were therefore interchangeable. Weighted, the score
  exceeds the count, the subtraction goes negative, and `settle`'s clamp returns `0.0` — on every
  run, forever, with no exception, no `400` and no log line. The receiver reads this field *relative
  to* `roomsCleared` (`agent/AGENT-PROMPT.md:62`) as its only diagnostic for a broken
  decoration→player mapping, so a permanent zero silently removes that diagnostic. The test
  `weighting cannot silence the unattributed count` is the guard, and it asserts both halves.
- **`RunReport.SCHEMA`, 5, must not move for this feature and must not go back down.** `clear-001`'s
  reasons stand unchanged. `clearpoints-001` needed no bump because the field's *values* are
  unchanged — under flat weighting every award credited either the full point or nothing, so the old
  subtraction already produced exactly this count.
- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`). Never edited, never
  regenerated, and in particular never adjusted to make a weight come out nicer. The receiver reads
  this exact file.
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate at the top of
  `CLAUDE.md`. The notes for the next release have three things to say: the schema moved to 5, older
  installs are unaffected because the receiver still accepts 4, and room points are no longer flat.
- `dist/` by hand — and **`./gradlew build` is not a neutral verification command** while fixes sit
  unreleased: it is `finalizedBy copyToDist` and `copyToDist dependsOn cleanDist`, so it deletes and
  replaces `dist/sighteaddons-0.9.0.jar` with a jar that is no longer the released 0.9.0. Use
  `./gradlew assemble check`, which is what `init.sh` and `claude-progress.md` both now print. Note
  that `README.md`'s "Build" section still says `./gradlew build`, correctly — it is the contributor
  build instruction and it accurately describes the `dist/` copy as the point. Do not follow it
  mid-feature, and do not "fix" it either.
- **Past session entries in `claude-progress.md`.** The file says so, and session 007 respected it
  even where session 006's entry contains a claim now known to be false. Supersede in a new entry;
  do not rewrite history.
- `SighteAddonServerside`. Read it — the schema diff `CLAUDE.md` requires means reading `ingest.py`
  and `roomstats.py` — but a change needed there is a paired feature and a different session.
- `evaluator-rubric.md`'s structure. Making it append-only was discussed and deliberately not done:
  it is a harness file, and `CLAUDE.md` says harness changes need the user to ask. The findings were
  routed into `feature_list.json` instead, which is the path `CLAUDE.md` already prescribes.

## Environment Quirks

- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes, not seconds. A run that looks stuck is almost always still downloading. Warm,
  it is ~25 s; it was warm throughout this session and ran in 1–6 s.
- **`DungeonSession.floor` is writable from a test by reflection**, measured this session and now
  relied on by one case: `DungeonSession::class.java.getDeclaredField("floor")`, `isAccessible = true`,
  `field.set(DungeonSession, "F7")` — after which `DungeonSession.floorNumber` reads `7`, not null.
  Kotlin's `private set` on an `object` is not a barrier to the backing field. **Do not call
  `DungeonSession.reset()` to clean up**: it resets half the mod, including `ContributionTracker`,
  `PartyTracker` and `SecretTracker`. Set the field back to null in a `finally` instead — the suite
  runs sequentially in one JVM and a left-behind floor leaks into every later test.
- **`DebugLog.event` is safe to call from a unit test**, measured rather than assumed: it resolves
  `Config` and `FabricLoader` without throwing and reports `enabled=true` under the test runtime.
  That is what makes driving `award()` from a test possible at all — `RoomHistory.onRoomCleared` is
  *not* on that seam, which is why `onCleared` stops short of it.
- **A consequence: `./gradlew test` writes `config/sighteaddons/debug/session-<millis>.jsonl` into
  the working tree**, one per JVM run, because `FabricLoader`'s gameDir under the test runtime is the
  project root and `isDevelopmentEnvironment` is true. `config/` is in `.gitignore` since `ddfddc0`;
  seven such files were swept into `13c9fb5` before that was noticed. `13c9fb5` was deliberately
  *not* amended — it is the hash the feature's original evidence is recorded against.
- **`ContributionTracker` is an `object` with run-long state**, so any test that writes to it must
  `reset()` first. `ContributionTrackerTest` does it in `@BeforeEach`; the suite runs sequentially.
  `ContributionTracker.reset()` itself is safe and touches no other object.
- JDK 25+ required (bytecode 25 via `--release`, no pinned toolchain). Gradle uses `JAVA_HOME`, not
  `PATH`, so `init.sh`'s version line can describe a different JDK than the build uses. Measured
  here: 25.0.4.
- Mappings are official Mojang, not Yarn — class names in this repository are Mojmap.
- `./gradlew runClient` cannot log in to Hypixel. `run/config/sighteaddons/debug/session-<millis>.jsonl`
  from a real install is the only source of real data.
- Git is set to `core.autocrlf=true` on this machine; `gradlew` and `*.sh` are pinned to LF in
  `.gitattributes` and must stay that way. Kotlin sources warn `LF will be replaced by CRLF` on
  `git add`; that is normal here and not something to fix.
- **`git commit -m` with a PowerShell-style `@'...'@` here-string silently embeds the `@` markers as
  the first and last lines of the message.** It happened this session and cost an amend. Write the
  message to a file and use `git commit -F`.
- **`python` resolves and works** (`/c/Users/marvi/AppData/Local/Python/bin/`), and was used here to
  sum the test-result XMLs and to apply the mutation probes. The harness `CLAUDE.md` one level up
  describes `python3` as a Windows App-Execution-Alias stub — a third description of the same thing.
  Measure before relying on any of them.

## Commands

- Startup: `./gradlew runClient`
- Smoke check: `./init.sh` (wraps `./gradlew test`)
- Full verification: `./gradlew assemble check` — same coverage as `./gradlew build` without the
  `copyToDist` step that rewrites the released jar
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
