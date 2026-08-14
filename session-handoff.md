# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. **176 tests across 14 classes, 0 failures,
  0 skipped** — `main` at `d356ff2` (which is `party-001` merged) carries 167 in the same 14, and the
  difference is exactly `RunReportTest` 21 → 30. No class was added. `mod_version=0.9.0`,
  `dist/sighteaddons-0.9.0.jar` unchanged (md5 `b2ebc35ccfeb9cc96134eb3b18f0306f`, measured before
  and after `assemble check`), `RunReport.SCHEMA` still 5.
- Branch: `runloss-001`, off `main` at `d356ff2`. **Not pushed and not merged.** For how many commits
  it carries, run `git rev-list --count d356ff2..HEAD` and `git log --oneline d356ff2..HEAD` for what
  each is. **Do not write the number into an artifact** — three consecutive reviews found a
  hand-transcribed count wrong, which is why it is derived rather than stated.
- What verification actually ran (exact commands), all at `cd26786`:
  - `./gradlew test --tests 'sighteaddons.RunReportTest'` → PASS, 30 tests, 0 failures. This is
    `runloss-001`'s `verification_command`, **unchanged in text**; the class grew from 21 to 30 and
    four mutation probes measure that it can fail.
  - `./gradlew test --rerun-tasks` → `classes 14, tests 176, skipped 0, failures 0, errors 0`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 identical either side, and
    `git status --short dist/ gradle.properties` empty
  - `bash init.sh` → `BASELINE: PASSING` (run at session start, on `main` at `d356ff2`, 167 tests)
  - `bash docs/evidence/session-1786719912927/readout.sh` → `READOUT: OK`
  - **The paired-feature check, mechanically.** `python build/keydiff.py` — re-created this session
    (it is in `build/`, which is gitignored, so it never survives a clean; the docstring says what it
    does). It parses every `addProperty`/`add` key out of `RunReport.build()`/`room()` and
    `RUN_KEYS`/`RUN_OPTIONAL`/`ROOM_KEYS`/`ROOM_OPTIONAL` out of `SighteAddonServerside/ingest.py`:
    **four empty sets, both directions**, 17 run keys and 17 room keys, run before *and* after the
    change. `SighteAddonServerside` was **read and never written**.
  - **Four mutation probes, all caught, all restored with `git checkout` after the feature was
    committed.** (A) `RunReport.uploader(live, captured) = live`, i.e. the pre-feature behaviour →
    fails 1. (B) delete the `reported.compareAndSet(false, true)` line from `RunReport.queue` →
    fails 2. (C) `queue` returns `publish()`'s result without giving the claim back → fails 1.
    (D) `publish` calls `Files.writeString(dir.resolve(name), body)` instead of `replace(...)` →
    fails 1. Re-create all four from these descriptions.

## Changed This Session

`runloss-001` — **the only entry on either repository's list known to have destroyed real data, and
it is now `passing` in source.** Quitting the game from inside a dungeon wrote no report, because
`RunReport.write` had exactly two call sites and that exit produces neither.

- **`ClientPlayConnectionEvents.DISCONNECT` is the third call site**, writing `complete = false`
  unconditionally. The three questions that had to be answered before it could be were the feature;
  the `ponytail:` note that used to sit at `SighteAddons.kt:53-57` was right that none of them was a
  one-liner. The note is gone from the source because it is no longer a plan.
- **The player is genuinely not resolvable there — measured, not argued.** `RunReport.uploader`'s
  KDoc carries the four bytecode facts with the `javap` commands to recheck them:
  `Minecraft.destroy()` disconnects the level *before* `disconnectWithProgressScreen()`;
  `Connection.disconnect` is `channel.close().awaitUninterruptibly()`; Fabric raises DISCONNECT from
  a **HEAD inject on `Connection.channelInactive`**, a Netty callback that `invokeDisconnectEvent`
  runs with no hop to the client thread; and Netty completes the close future *before* firing
  `channelInactive`, so the handler races `Minecraft.player = null` from another thread.
  **`PartyTracker.localName`** now holds the name — captured every second of the run by the `update`
  that already read it — and it is preferred over the live player rather than used as a fallback,
  because every room's tick map is keyed by the name that was current *during* the run.
- **A half-written report is worse than none**, so reports now go through the `.part` + atomic move
  that `restamp` already used. The write happens a few statements before `System.exit(0)`; a
  truncated `run-*.json` matches `TelemetryUpload.RUN`, is posted at the next launch, 400s and lives
  in `rejected/` for good. `.part` is outside that pattern by construction.
- **`summaryPrinted` was not enough**, which was the specific thing to check. It is only ever set by
  the headline, so it cannot see the pair DISCONNECT introduces: title screen from inside a floor
  writes the report, then joining any server reaches JOIN with nothing having reset in between. The
  guard moved into `RunReport` as an `AtomicBoolean`, claimed before the write and **given back if
  the write fails**, cleared by `DungeonSession.reset()`. The JOIN site no longer checks
  `summaryPrinted`; behaviour there is unchanged (the two cases where they differ both end in an
  early return).
- **New `run_report` debug event**, written by `write()` itself so all three call sites emit it
  exactly once, carrying `complete`, the room count and the file name. It is the observable that
  turns the manual check into a measurement.
- **`verification_manual` was wrong and is corrected.** Its old step 3 said to confirm a `run_end`
  event, which is precisely what this path does not and must not produce — `run_end` means the
  headline came. It now names `run_report`, and adds the two checks that matter: no `.part` left
  next to the report, and the file landing in `uploaded/` rather than `rejected/` at the next launch.
- **Grade: telemetry stays B.** Session 009 held it at B while the gap was open; closing the gap does
  not promote it either, because the fix's one real path has never been observed. Promoting on a
  disassembly would apply a lower standard than `party` is held to at C.

## Broken Or Unverified

- Known defect: none introduced. `SighteAddons.RUN_END` still has no test of any kind.
- **Unverified, and it is this feature's whole ceiling: that Fabric raises `DISCONNECT` at all when
  somebody closes the game on Hypixel.** The dev client cannot log in and the event cannot be raised
  in a unit test, so the wiring rests on two disassemblies. What a real quit would show is a
  `run_report` line with `"complete":false` as the last event in the session file, and a
  `run-<millis>-<installId>.json` in `config/sighteaddons/runs/`. Nobody has seen either.
- **Uncovered on purpose and not implied to be covered: a hard kill.** Task manager, `SIGKILL`, power
  loss — nothing runs and the run is lost exactly as before. A Java-level crash that still reaches
  `Minecraft.destroy()` *is* covered by the same disconnect, but that is read off `destroy()`'s
  bytecode and has never been observed. A JVM shutdown hook was considered and rejected; the entry's
  notes say why.
- **Unverified — that the atomic move is atomic.** A mutation probe replacing it with a direct
  `Files.writeString` passed all 29 checks before a test was added, because a *successful* direct
  write and a *successful* move leave an identical directory. What is now asserted is the observable
  half — a `.part` from an interrupted attempt is consumed by the next successful write. The move
  itself is a property of the code.
- **Unverified — whether the order heuristic is correct at all.** Unchanged; the ceiling on the whole
  party domain. `assign` is pinned against its own model of a dungeon and the one real run was solo.
- **Unverified — that `roster_skew` ever fires**, and **whether `MapDecoration.name()` carries
  anything** (`party-001`'s blocker, `deconame-001`'s subject). Both unchanged. If the answer to the
  second is no, **`party-001` should be closed rather than carried**.
- **Unverified — the wiring of `positions()` itself**, unchanged.
- **Unverified — that Hypixel actually sends `chat-001`'s strings**, unchanged.
- **Unverified — the party half of everything else.** The one real run is solo and deathless, so
  `clear-001`'s zero-margin gap tolerance is still open and **the death path has never been exercised
  at all**.
- **Unverified — the `RED` checkmark path and every pixel of the `/sa` screen.** Unchanged.
- **Unverified — the measured half of the scoring model, entirely.** Unchanged: every room is on its
  seed until something serves the averages (`scores-fetch-001`).
- Unverified: the cross-repo reading that `unattributed` is only ever consumed as a ratio against
  `roomsCleared`. Unchanged.
- Regressions found: none. The one to watch was the JOIN path, since its guard changed; the two cases
  where old and new differ both end in an early return, and the whole suite ran at `--rerun-tasks`.
- Risk for the next session: unchanged in kind — **the schema is 5 in source and 4 in every install**,
  and now seven features exist in source only. Nothing breaks meanwhile; nothing reaches a player
  either, until somebody bumps the version and takes the release gate, which is the user's decision.

## Next Best Step

- **First, a fresh evaluator pass on `runloss-001`.** It claims `passing` on a data-loss defect whose
  fix cannot be exercised here, so the question worth a second pair of eyes is whether the evidence
  carries that claim: is a bytecode measurement of *where* the event fires enough to call a write
  path `passing` when the event itself has never been seen? The argument made here is yes — the write
  path is pinned by 30 checks and four probes, the event's existence and timing are read off the two
  jars this module actually compiles against, and everything unobserved is named as unobserved rather
  than folded into the grade. If the evaluator disagrees, the alternative status is `in_progress`
  until somebody plays and quits a floor. Re-run: the `verification_command`, the whole suite (expect
  176 in 14), `assemble check` with the jar md5 either side, `python build/keydiff.py`,
  `bash docs/evidence/session-1786719912927/readout.sh`, and all four mutation probes.
  **The evaluator must not be this session.**
- **If the user offers a run, the ask has changed and is now cheap to state: play a floor and then
  quit the game from inside it.** That single act verifies `runloss-001` end to end and costs one
  dungeon. **A party floor with a death in it is still the highest-value input overall** — it would
  move `party-001`, `deconame-001`, `clear-001`'s last open note, the rest of `ingame-001` and all
  three of `chat-001`'s unverified halves at once. A party floor that is *quit from* does both.
- **Then `runend-001`.** It is now the only `not_started` entry that can be worked here, it is cheap,
  and its open question is already answered: write the run-level count, not the event count.
- **Do not start `chatfields-001`** by editing `RunReport.kt`. Its first move is a feature in
  `Sighte/skyblock-server`, which is a different repository and a different session.
- **Do not start `scores-fetch-001`.** Still blocked on the receiver serving `roomstats.json`.
- **Do not start `deconame-001` unless somebody is about to play a party floor.**
- `records-001` is deferred by the user — a product decision, not a technical blocker.

## Do Not Touch

- **`RunReport.uploader`'s KDoc is a measurement, not an opinion.** It records what
  `Minecraft.destroy()`, `Connection.disconnect`, `ConnectionMixin` and `AbstractNetworkAddon`
  actually do, at named offsets and inject targets, so the next session does not re-derive it or
  "simplify" the report back to reading `Minecraft.getInstance().player`. If a future Minecraft or
  Fabric API version changes the shape, correct it against a fresh `javap` and say which version —
  do not soften it into "may not work".
- **The DISCONNECT handler must not call `DungeonSession.reset()`.** It looks like the missing mirror
  of the JOIN site and it is not: that callback can arrive on a Netty thread while the client is
  still rendering, and `reset()` tears down `ContributionTracker`, `PartyTracker` and `ClearPopup` —
  state `renderHud` reads every frame. A dropped connection must not become a
  `ConcurrentModificationException` in the render loop. The next JOIN resets, as it always did.
- **`RunReport.reported` must be claimed *before* the write and given back if the write fails.**
  Claiming after a successful write leaves a window where both call sites write; never claiming back
  means one failed write loses the run twice over. Measured: dropping the claim fails 2, dropping the
  rollback fails 1.
- **Reports must reach the queue through `.part` + move.** The final name matches
  `TelemetryUpload.RUN` and `.part` does not, which is the entire reason a torn write cannot be
  uploaded. The suite cannot see the move itself — it sees that a stale `.part` is consumed — so a
  "simplification" back to `Files.writeString` will look harmless and will not be.
- **The rejected upgrade path in `PartyTracker.assign`'s KDoc is a measurement, not an opinion — do
  not delete it as stale and do not re-add the mixin plan.**
- **`assign`'s `trustOrder` guard**, and **`assign` must stay pure and stay `internal`**.
- **`ChatEvents`' patterns are cited, not invented — keep them that way.**
- **`SecretTracker.chatAttribution` returns `Boolean?` and the null is load-bearing.**
- **`ChatEvents.nearMiss` must not be widened to log every chat line.**
- **`docs/evidence/session-1786719912927/` is evidence, not documentation.** Do not tidy the excerpt
  or extend it with lines from a different run. In particular **`readout.sh` still asserts
  `run_end events 0` and must keep doing so** — `runloss-001` is fixed, but that session file will
  say what it says forever, and it is the only proof of what the defect cost.
- **`unattributed` must stay a count of *rooms*.**
- **`RunReport.SCHEMA`, 5, must not move and must not go back down.**
- **The seed keys are `rooms.json`'s spelling** — `Ice Fill` and `Water Board`, two words each.
- **The metric is `clearStay` and only `clearStay`.**
- **Do not re-add a bundled snapshot of `roomstats.json` to the jar.**
- **`clearpoints-001`'s notes are history and are marked as such.**
- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`).
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate. The notes
  for the next release owe seven things now: the schema moved to 5, older installs are unaffected
  because the receiver still accepts 4, room points are no longer flat, room points changed meaning
  again so old and new standings are not comparable, that the mod now reads chat events (and that the
  strings behind them are sourced from other mods rather than observed), **that a run ended by
  quitting from inside a floor used to be discarded and now is not — and that this path is unverified
  against a real client**, and that a hard kill still loses the run.
- `dist/` by hand — and **`./gradlew build` is not a neutral verification command** while fixes sit
  unreleased. Use `./gradlew assemble check`. `README.md`'s "Build" section still says
  `./gradlew build`, correctly; do not follow it mid-feature and do not "fix" it.
- **Past session entries in `claude-progress.md`.** Supersede in a new entry; do not rewrite history.
- `SighteAddonServerside`. It was **read** this session (`ingest.py`, for the key diff) and **not
  written**. A change needed there is a paired feature and a different session.
- `evaluator-rubric.md`'s structure. Harness file; changes need the user to ask.

## Environment Quirks

- **Gradle's user home on this machine is `C:\Users\marvi\scoop\persist\gradle\.gradle`, not
  `~/.gradle`** — `~/.gradle/caches/modules-2/files-2.1` does not exist and a `find` for a dependency
  jar under `~` returns nothing after several minutes. The reliable way to locate one is
  `./gradlew -q --init-script <script> printCp` with an init script that prints
  `configurations.compileClasspath`. That is how `fabric-networking-api-v1` was found this session.
  **Do not run an unscoped `find /` on this machine**; it does not finish inside a tool timeout.
- **`javap` on the Fabric API jars answers "when does this event fire, and on which thread" and it is
  cheap.** Extract the module jar, then `javap -p -c -v -cp . <mixin class>` — the `@Inject`
  `method`/`at` values are `Utf8` constants in the annotation's `RuntimeVisibleAnnotations`, so read
  the indices out of the annotation and look them up in the constant pool dump. That is how the
  Netty-thread finding behind `runloss-001` was established.
- **`git worktree add <path> <branch>` is refused when the main checkout already has that branch
  checked out.** An evaluator wanting an isolated copy at a specific commit wants
  **`git worktree add --detach <path> <sha>`**.
- **Restore a mutation probe with `git checkout <file>` — but COMMIT THE FEATURE FIRST.**
  `git checkout` restores from the index, so on an uncommitted working tree it reverts the mutation
  *and the entire feature edit in that file*. Never restore by writing the file back from Python
  either: git is `core.autocrlf=true` here, so the working copy is CRLF, and Python reading with
  universal newlines and writing with `newline=''` converts the file to LF. **Apply** a probe from
  Python with `io.open(..., newline='')` on both ends, which preserves CRLF; that is what this
  session did, and the restore was clean every time.
- **`build/` is gitignored, so scripts written there do not survive a clean.** `build/keydiff.py`
  (the `RunReport` ↔ `ingest.py` key diff), `build/edit_features.py` (the byte-safe
  `feature_list.json` writer) and `build/edit_quality.py` (CRLF-safe edits to the long table rows in
  `quality-document.md`) are all there. All three are short and their docstrings say what they do.
  Re-create rather than hunt for them.
- **Reading the Minecraft classes is the way to settle a protocol or lifecycle question.** The merged
  jar this module compiles against is at
  `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.1.2/minecraft-merged-043a8b3edf-26.1.2.jar`.
  `javap -v` also prints `BootstrapMethods`, which is where string-concat recipes live. To find which
  classes call a method, extract the jar and `grep -rl "<methodName>" --include="*.class"` — constant
  pools hold the name as raw UTF-8, so plain grep works.
- **`gh api repos/<owner>/<repo>/contents/<path> --jq .content | base64 -d` is the way to read a
  cited mod's source.** **`gh search code` is rate limited to 10 requests per minute** and returns
  HTTP 403 rather than an empty result when exceeded.
- **The real session file lives outside this repository** and only an excerpt is committed. The
  original is at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-1786719912927.jsonl`.
  Treat it as read-only: it is the user's game directory, not a working tree.
- **`*.jsonl` is pinned to LF in `.gitattributes`.**
- **`feature_list.json` is CRLF with raw UTF-8 bytes.** Read it with `encoding='utf-8'` and write it
  back as `json.dumps(..., indent=2, ensure_ascii=False).replace('\n', '\r\n')` plus a trailing
  `\r\n`. **Assert the round trip before editing** rather than trusting this line;
  `build/edit_features.py` does exactly that and refuses to write if it fails. It held this session.
- **`quality-document.md` and `claude-progress.md` are CRLF too**, and the quality table rows are
  single lines of several hundred characters — edit them from a script with `newline=''`, not from a
  here-doc.
- **`RoomStats` reads a file from outside this repository**, so `ContributionTrackerTest` and
  `RoomDatabaseTest` pin `RoomStats.use(RoomScores.NONE)` in `@BeforeEach`. **Any new test that
  touches `weightOf` must do the same.** `RunReportTest` does not touch it.
- **`RunReport.reported` is process-wide state on an `object`**, so `RunReportTest` calls
  `RunReport.reset()` in `@BeforeEach`. The suite runs sequentially. Any new test that calls
  `RunReport.queue` must do the same, exactly like `ContributionTracker`'s `reset()`.
- **Windows Python cannot execute `./gradlew`** — `WinError 193`. Drive Gradle from bash.
- **Windows Python resolves `/tmp/x` as `C:\tmp\x`**, Git Bash resolves it elsewhere. Use `/c/tmp/...`
  from bash for anything the two share, or keep it in `build/`.
- **A long here-doc through the Bash tool is fragile.** Writing the script to `build/` with the Write
  tool and running `python build/<name>.py` is the reliable path for anything non-trivial. Short
  here-docs are fine and were used throughout this session.
- **Test results are easiest to read from XML, not from Gradle's console output.**
  `build/test-results/test/*.xml` carries `tests`/`failures`/`errors`/`skipped` per class and the
  `<testcase>` names of the failures. That is how every count here was obtained.
- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes. Warm it is ~6-9 s and it was warm throughout this session.
- **`net.minecraft.ChatFormatting` loads fine in a unit test.** But **`MapItemSavedData` and
  `MapDecoration` do not**, and neither does anything needing a live `Minecraft` — which is why
  `RunReport.write` itself is untestable and the feature was built as three testable pieces
  (`uploader`, `publish`, `queue`) plus a thin shim that reads the world.
- **`DungeonSession.runTicks` is `private set`** and only `tickClock()` moves it. New tick-dependent
  logic must take the tick as a parameter.
- **`DungeonSession.floor` is writable from a test by reflection**;
  **do not call `DungeonSession.reset()` to clean up** — it resets half the mod, and since this
  session that includes `RunReport`'s guard.
- **`DebugLog.event` is safe to call from a unit test**, and a consequence is that `./gradlew test`
  writes `config/sighteaddons/debug/session-<millis>.jsonl` into the working tree. `config/` is
  gitignored. `Config` initialises fine under `fabric-loader-junit`, so `Config.installId` and
  `Config.uploadName` are readable — and writable — from a test.
- **`ContributionTracker` is an `object` with run-long state**, so any test that writes to it must
  `reset()` first.
- The live box is reachable read-only (`ssh -i ~/.ssh/sighte_box -o IdentitiesOnly=yes
  root@217.160.51.229`). **It was not used this session** — nothing here needed it.
- JDK 25+ required. Gradle uses `JAVA_HOME`, not `PATH`. Measured here: 25.0.4.
- Mappings are official Mojang, not Yarn — class names here are Mojmap.
- `./gradlew runClient` cannot log in to Hypixel. A `session-<millis>.jsonl` from a real install is
  the only source of real data, and there is exactly one in the repository.
- Git is `core.autocrlf=true`; `gradlew` and `*.sh` are pinned to LF in `.gitattributes`. Kotlin
  sources warn `LF will be replaced by CRLF` on `git add`; that is normal here.
- **`git commit -m` with a PowerShell-style `@'...'@` here-string silently embeds the `@` markers as
  the first and last lines of the message.** Write the message to a file and use `git commit -F`.
- **`python` resolves and works**; `python3` is a Windows App-Execution-Alias stub one level up.

## Commands

- Startup: `./gradlew runClient`
- Smoke check: `./init.sh` (wraps `./gradlew test`)
- Full verification: `./gradlew assemble check` — same coverage as `./gradlew build` without the
  `copyToDist` step that rewrites the released jar
- `runloss-001`: `./gradlew test --tests 'sighteaddons.RunReportTest'`
- `party-001`: `./gradlew test --tests 'sighteaddons.PartyTrackerTest'`
- `chat-001`: `./gradlew test --tests 'sighteaddons.ChatEventsTest'
  --tests 'sighteaddons.SecretTrackerTest' --tests 'sighteaddons.ContributionTrackerTest'`
- Paired-feature key diff: `python build/keydiff.py` (re-create from its docstring after a clean)
- Read out the real run: `bash docs/evidence/session-1786719912927/readout.sh`
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
- Test counts: `build/test-results/test/*.xml`, not the console
