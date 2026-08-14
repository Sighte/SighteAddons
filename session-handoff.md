# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. **119 tests across 12 classes, 0 failures,
  0 skipped** — up from 116, with no existing test removed, weakened or changed. `mod_version=0.9.0`,
  `dist/sighteaddons-0.9.0.jar` unchanged (md5 `b2ebc35ccfeb9cc96134eb3b18f0306f`),
  `RunReport.SCHEMA` still 5.
- Branch: `clearpoints-001`, off `main` at `1e27b42`. **Four commits** — `13c9fb5` (the code the
  feature's original evidence is recorded against), `0b6373f` (artifacts), `ddfddc0` (gitignore
  repair), `390399b` (this session's code) — plus this session's artifact commit. **Not pushed and
  not merged.** Previous handoffs said "one commit"; that was wrong from `0b6373f` onwards.
- What verification actually ran (exact commands), all at `390399b`:
  - `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RoomDatabaseTest' --rerun-tasks`
    → `BUILD SUCCESSFUL in 7s`, `7 actionable tasks: 7 executed`;
    `ContributionTrackerTest tests=32 failures=0`, `RoomDatabaseTest tests=6 failures=0`
  - `./gradlew test --rerun-tasks` → `classes 12, tests 119, skipped 0, failures 0, errors 0`
  - `bash init.sh` → `BASELINE: PASSING`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, and `git status --short dist/ gradle.properties`
    empty
  - Three mutation probes, all reverted immediately and all recorded as evidence: adding
    `secretsFound` to the weight fails 3, substituting it for the database count fails 6, a
    run-progress factor fails 1.

## Changed This Session

This session closed an evaluation. `evaluator-rubric.md` scored `clearpoints-001` **12/14 —
Revise**: Correctness, Regression, Scope discipline and Handoff readiness all 2, Verification and
Maintainability 1. Only the two docked items were touched, and the feature stays `passing`.

- **The two exclusions that were argued but not guarded now have tests.** The overclaim was real and
  the evaluator proved it: adding `+ room.secretsFound * SECRET_POINTS` to `weightOf` — the exact
  edit that constant's KDoc argues against — passed all 116 tests. Three cases in
  `ContributionTrackerTest`:
  - `the live secret counter is not what a room is worth` — 8 secrets *found* in a room the database
    says holds none adds nothing, and a room the database says holds 8 is worth strictly more. Two
    assertions because there are two plausible edits, the additive one and substituting
    `secretsFound` for `info.secrets`.
  - `the credit is the whole room even though the checkmark lands mid-collection` — drives a room
    through `onCleared` with 2 of 5 secrets in hand. Pins the *reason*: `award()` fires on the
    checkmark while the party is still collecting, so the live counter is a race against when the
    last mob dropped.
  - `a room is worth the same however far the run has got` — no factor drawn from run progress. A
    `(1.0 + roomsCleared * 0.1)` probe fails this and nothing else in the suite.
- **`claude-progress.md`'s "Current Verified State" no longer tells sessions to run `./gradlew
  build`.** It now says `./gradlew assemble check`, with the reason inline. Following the old line
  deletes and rewrites the released `dist/sighteaddons-0.9.0.jar`.
- **Four open review findings moved from `evaluator-rubric.md` into `feature_list.json`**, as notes on
  the features they belong to. The rubric is overwritten wholesale each pass, so a finding only
  living there has a half-life of one review — two reviewers had already hand-copied items forward,
  and the `./gradlew build` hazard above (fixed in `init.sh`, left live in the progress log) is what
  that costs. Moved: `settle`'s KDoc arguing against the old clamp rather than the symmetric
  threshold (`residue-001`); and on `clear-001`, `stay.ticks` counting sightings rather than elapsed
  ticks, the gap tolerance calibrated with zero margin, and `anchorOnClear`'s unknown real-floor
  frequency. `ingame-001` cross-references the two that need a real run. The rubric was not
  restructured — that is a harness change and the user's call.
- The only `src/main` change is a comment block in `weightOf`'s KDoc. No behaviour moved.

## Broken Or Unverified

- Known defect: none introduced.
- **Unverified, and unguardable here — the floor exclusion.** "The floor is not a multiplier" rests
  on the KDoc argument and on nothing else. `weightOf` takes no floor, and `DungeonSession.floor` is
  `private set` written only by `inDungeon(Minecraft)`, which no test in this repository can build —
  so a floor factor would read null under test and fall through. **Do not add a test that claims to
  catch it**; it would pass whether or not the factor was there, which is the failure shape this
  project keeps removing. A real guard needs a seam on `DungeonSession`, and that is a feature.
  `a room is worth the same however far the run has got` pins the shape (no run-progress factor) and
  is deliberately not sold as more.
- **Unverified — the weights themselves.** 1.5 for a puzzle and 0.25 a secret are judgement, not
  measurement. That they *separate* rooms is tested; that they separate rooms in a way a player would
  agree with is not, and cannot be until a real run produces a debug session.
- **Unverified — the wiring, still.** `tick()` needs a `Minecraft` and a `MapItemSavedData`, so that
  it calls `onCleared` exactly once per clear (and `onPresence` once per member per tick) is asserted
  by reading the code and by nothing else. `onCleared` itself is covered.
- Unverified: the cross-repo reading that `unattributed` is only ever consumed as a ratio against
  `roomsCleared`. It is what `agent/AGENT-PROMPT.md:62` says and `roomstats.py` does not read the
  field at all — but no receiver test has been run from here. Nothing this session touched goes near
  the wire, and `SighteAddonServerside` was neither read nor written.
- Still unverified from before, unchanged: everything `ingame-001` lists — calibration, decoration
  mapping, checkmark reading, core hashing, and every pixel of the `/sa` screen. Its notes now also
  name the three open findings that the first real session file would settle.
- Regressions found: none.
- Risk for the next session: unchanged — **the schema is 5 in source and 4 in every install**, and
  three features (`residue-001`, `clear-001`, `clearpoints-001`) exist in source only. Nothing breaks
  meanwhile; nothing reaches a player either, until somebody bumps the version and takes the release
  gate, which is the user's decision.

## Next Best Step

- **First, a fresh evaluator pass on `clearpoints-001`.** It was left at Revise 12/14 and both docked
  items are now closed; nothing more should be built on this branch until somebody who did not
  implement it re-runs the checks. The two things to re-run are the feature's own
  `verification_command` and the evaluator's mutation 4 — it must now fail 3 tests where it used to
  pass 116.
- Then the highest-priority unfinished feature: `chat-001` — read the events Hypixel puts in chat.
  `records-001` is deferred by the user (a product decision — rooms stay global — not a technical
  blocker), and `ingame-001` cannot be finished by any session here.
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
- `SighteAddonServerside`. Read it — the schema diff `CLAUDE.md` requires means reading `ingest.py`
  and `roomstats.py` — but a change needed there is a paired feature and a different session.
- `evaluator-rubric.md`'s structure. Making it append-only was discussed and deliberately not done:
  it is a harness file, and `CLAUDE.md` says harness changes need the user to ask. The findings were
  routed into `feature_list.json` instead, which is the path `CLAUDE.md` already prescribes.

## Environment Quirks

- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes, not seconds. A run that looks stuck is almost always still downloading. Warm,
  it is ~25 s; it was warm throughout this session and ran in 1–7 s.
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
  Note that `DungeonSession` is *not* reset there — `DungeonSession.reset()` resets half the mod —
  so a test that moves the run clock leaks into later tests. This session's new cases deliberately
  move only `ContributionTracker`'s own state for that reason.
- JDK 25+ required (bytecode 25 via `--release`, no pinned toolchain). Gradle uses `JAVA_HOME`, not
  `PATH`, so `init.sh`'s version line can describe a different JDK than the build uses. Measured
  here: 25.0.4.
- Mappings are official Mojang, not Yarn — class names in this repository are Mojmap.
- `./gradlew runClient` cannot log in to Hypixel. `run/config/sighteaddons/debug/session-<millis>.jsonl`
  from a real install is the only source of real data.
- Git is set to `core.autocrlf=true` on this machine; `gradlew` and `*.sh` are pinned to LF in
  `.gitattributes` and must stay that way. Kotlin sources warn `LF will be replaced by CRLF` on
  `git add`; that is normal here and not something to fix.
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
