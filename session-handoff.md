# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. **116 tests across 12 classes, 0 failures,
  0 skipped** — up from 101, with no existing test removed, weakened or changed. `mod_version=0.9.0`,
  `dist/sighteaddons-0.9.0.jar` unchanged, `RunReport.SCHEMA` still 5.
- Branch: `clearpoints-001`, off `main` at `1e27b42`. One commit, `13c9fb5`, **not pushed and not
  merged**.
- What verification actually ran (exact commands), all at `13c9fb5`:
  - `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RoomDatabaseTest' --rerun-tasks`
    → `BUILD SUCCESSFUL in 6s`, `7 actionable tasks: 7 executed`;
    `ContributionTrackerTest tests=29 failures=0`, `RoomDatabaseTest tests=6 failures=0`
  - `bash init.sh` → `BASELINE: PASSING`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, and `git status --short dist/ gradle.properties`
    empty
  - Test totals from `build/test-results/test/*.xml`: `classes 12, tests 116, failures 0, errors 0`
  - Two mutation checks, both reverted immediately, both recorded as evidence: restoring the old
    `unattributed` subtraction fails 2 tests; flattening every weight constant to `0.0` fails 7.

## Changed This Session

- `clearpoints-001` → `passing`. Rooms are weighted instead of counted, and `unattributed` is a
  count of rooms instead of a subtraction.
  - `ContributionTracker.weightOf(room)` = `BASE_POINTS` 1.0, plus a kind bonus (puzzle 1.5; trap,
    champion/miniboss, blood 1.0), plus 0.25 per secret the room **database** says the room holds,
    plus 0.5 per segment beyond the first. The split over the members in the room is untouched, so
    two players in one room are still separated only by time.
  - Three exclusions are deliberate, argued in the KDoc, and each has a test: **rare** rooms are paid
    nothing for being rare; **secrets come from the database, not `secretsFound`**, because `award()`
    fires on the clear checkmark while the room's secrets are usually still being collected; and the
    **floor is not a multiplier** — it is constant across a run, so it scales everyone equally and
    separates nobody, and points never leave the client to be compared across runs.
  - An unnamed room falls back to the map colour for its kind and pays no secret bonus. Worth less
    than it should be, never nothing.
- **`unattributed` is now a count of rooms, held in `ContributionTracker.unattributedRooms` and
  incremented in `award()`.** See "Do Not Touch" — this is the load-bearing line of the session.
- New seam: `internal fun ContributionTracker.onCleared(room)` does `roomsCleared++` then
  `award(room)`; `tick()` calls it where it used to do both inline. This is what made the accounting
  testable, and it closes the gap session 002 recorded.
- `README.md`: `ClearPoints` is a section describing the weighting instead of a "Not implemented yet"
  bullet, and `rooms unattributed` is no longer described as "the gap between rooms cleared and points
  handed out" — that sentence would have become false with this change.
- New feature recorded, not fixed inline: **`runend-001`**, priority 8. The receiver's
  `agent/AGENT-PROMPT.md:62` tells its analysis agent to read `unattributed` against `roomsCleared`
  in the `run_end` event; the mod's `run_end` (`SighteAddons.kt:149`) has never carried
  `unattributed`.
- No version bump, no `dist/` refresh, no `build.gradle` line, no harness file edited, `rooms.json`
  read and never written, and `SighteAddonServerside` read but never written. The release gate did
  not fire.

## Broken Or Unverified

- Known defect: none introduced.
- **Unverified — the weights themselves.** 1.5 for a puzzle and 0.25 a secret are judgement, not
  measurement. That they *separate* rooms is tested; that they separate rooms in a way a player would
  agree with is not, and cannot be until a real run produces a debug session. Treat the constants as
  a first calibration rather than a result.
- **Unverified — the wiring, still.** `tick()` needs a `Minecraft` and a `MapItemSavedData`, so that
  it calls `onCleared` exactly once per clear (and `onPresence` once per member per tick) is asserted
  by reading the code and by nothing else. `onCleared` itself is now covered.
- Unverified: the cross-repo reading that `unattributed` is only ever consumed as a ratio against
  `roomsCleared`. It is what `agent/AGENT-PROMPT.md:62` says and `roomstats.py` does not read the
  field at all — but no receiver test was run from here, because neither `python` nor `python3`
  resolves on this machine.
- Still unverified from before, unchanged: everything `ingame-001` lists — calibration, decoration
  mapping, checkmark reading, core hashing, and every pixel of the `/sa` screen. And the `clear-001`
  gap tolerance, reasoned from `PartyTracker.positions`' documented roster-skew window rather than
  measured.
- Regressions found: none.
- Risk for the next session: unchanged from before — **the schema is 5 in source and 4 in every
  install**, and now three features (`residue-001`, `clear-001`, `clearpoints-001`) exist in source
  only. Nothing breaks meanwhile; nothing reaches a player either, until somebody bumps the version
  and takes the release gate, which is the user's decision.

## Next Best Step

- Highest-priority unfinished feature: `chat-001` — read the events Hypixel puts in chat.
- Why it is next: `records-001` is deferred by the user (a product decision — rooms stay global — not
  a technical blocker), `ingame-001` cannot be finished by any session, and `chat-001` is the last
  item that makes something the mod currently infers *observed* instead. A dead player is detected
  only via the tab list today, which is late and lossy.
- What counts as passing: its own `verification_command`
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
  `weighting cannot silence the unattributed count` is the guard, and it asserts both halves: that
  the count survives, and that the old expression would have gone quiet on the same run.
- **`RunReport.SCHEMA`, 5, must not move for this feature and must not go back down.** `clear-001`'s
  reasons stand unchanged. `clearpoints-001` needed no bump because the field's *values* are
  unchanged — under flat weighting every award credited either the full point or nothing, so the old
  subtraction already produced exactly this count.
- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`). Never edited, never
  regenerated, and in particular never adjusted to make a weight come out nicer. The receiver reads
  this exact file.
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate at the top of
  `CLAUDE.md`. The notes for the next release now have three things to say: the schema moved to 5,
  older installs are unaffected because the receiver still accepts 4, and room points are no longer
  flat.
- `dist/` by hand — and `./gradlew build` is **not** a neutral verification command while fixes sit
  unreleased: it is `finalizedBy copyToDist` and `copyToDist dependsOn cleanDist`, so it deletes and
  replaces `dist/sighteaddons-0.9.0.jar` with a jar that is no longer the released 0.9.0. Use
  `./gradlew assemble check`, which is what `init.sh` now prints.
- `SighteAddonServerside`. Read it — the schema diff `CLAUDE.md` requires means reading `ingest.py`
  and `roomstats.py` — but a change needed there is a paired feature and a different session.

## Environment Quirks

- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes, not seconds. A run that looks stuck is almost always still downloading. Warm,
  it is ~25 s; it was warm throughout this session and ran in 1–7 s.
- **`DebugLog.event` is safe to call from a unit test**, measured this session with a throwaway test
  rather than assumed: it resolves `Config` and `FabricLoader` without throwing and reports
  `enabled=true` under the test runtime. That is what makes driving `award()` from a test possible at
  all — `RoomHistory.onRoomCleared` is *not* on that seam, which is why `onCleared` stops short of it.
- **A consequence of the above: `./gradlew test` now writes `config/sighteaddons/debug/session-<millis>.jsonl`
  into the working tree**, one per JVM run, because `FabricLoader`'s gameDir under the test runtime is
  the project root and `isDevelopmentEnvironment` is true. Nothing did this before, since no test
  reached `DebugLog.event`. `config/` is now in `.gitignore` — seven of those files were swept into
  commit `13c9fb5` by a `git add -A` before this was noticed, and the branch's last commit ("Stop
  tracking the debug sessions the test suite now writes") untracks them. `13c9fb5` was deliberately
  *not* amended: it is the hash `clearpoints-001`'s evidence was verified against, and tidying it
  would have made the record unreproducible to buy nothing.
- `ContributionTracker` is an `object` with run-long state, so any test that writes to it must
  `reset()` first. `ContributionTrackerTest` does it in `@BeforeEach`; the suite runs sequentially
  (`test { useJUnitPlatform() }`, no parallel configuration).
- JDK 25+ required (bytecode 25 via `--release`, no pinned toolchain). Gradle uses `JAVA_HOME`, not
  `PATH`, so `init.sh`'s version line can describe a different JDK than the build uses.
- Mappings are official Mojang, not Yarn — class names in this repository are Mojmap.
- `./gradlew runClient` cannot log in to Hypixel. `run/config/sighteaddons/debug/session-<millis>.jsonl`
  from a real install is the only source of real data.
- Git is set to `core.autocrlf=true` on this machine; `gradlew` and `*.sh` are pinned to LF in
  `.gitattributes` and must stay that way. Kotlin sources warn `LF will be replaced by CRLF` on
  `git add`; that is normal here and not something to fix.
- **The previous handoff's claim that neither `python` nor `python3` resolves from the Bash tool is
  no longer true**, measured this session rather than carried forward: both resolve to
  `/c/Users/marvi/AppData/Local/Python/bin/` and both report `Python 3.14.0`. `python` was used here
  to inspect `rooms.json` and to sum the test-result XMLs. Note the harness `CLAUDE.md` one level up
  still describes `python3` as the Windows App-Execution-Alias stub, which is a *third* description
  of the same thing — measure before relying on any of them.

## Commands

- Startup: `./gradlew runClient`
- Smoke check: `./init.sh` (wraps `./gradlew test`)
- Full verification: `./gradlew assemble check` — same coverage as `./gradlew build` without the
  `copyToDist` step that rewrites the released jar
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
