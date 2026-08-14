# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- **Branch `floorloss-001`, off `main` at `ec12a27`. Not pushed and not merged.** For how many
  commits it carries, run `git rev-list --count ec12a27..HEAD` and `git log --oneline ec12a27..HEAD`
  for what each is. **Do not write the number into an artifact** — three consecutive reviews found a
  hand-transcribed count wrong, which is why it is derived.
- **The suite is 193 across 15 classes, 0 failures, 0 skipped.** The branch point `ec12a27` carries
  184 in 14; the difference is exactly the new `DungeonSessionTest` (7) plus `RunReportTest` 30 → 32.
  No class was removed, nothing renamed or weakened.
- `mod_version` is **0.10.0** and `dist/sighteaddons-0.10.0.jar` is untouched — md5
  `5e0b1cd2d3b97cfaa6cd5e86061cbdbe`, measured before and after `assemble check`.
  `RunReport.SCHEMA` still 5.
- What verification actually ran (exact commands), all at `c6142d4`:
  - `./gradlew test --tests 'sighteaddons.DungeonSessionTest' --tests 'sighteaddons.RunReportTest'`
    → PASS. `DungeonSessionTest` 7, `RunReportTest` 32, 0 failures. This is `floorloss-001`'s
    `verification_command`, **unchanged in text** — `DungeonSessionTest` did not exist and the entry
    named it anyway.
  - `./gradlew test --rerun-tasks` → `classes 15, tests 193, skipped 0, failures 0, errors 0`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 identical either side, and
    `git status --short dist/ gradle.properties` empty
  - `bash init.sh` → `BASELINE: PASSING` (run at session start on `ec12a27`, 184 tests, and again at
    the end)
  - **The paired-feature check, mechanically.** `python build/keydiff.py` → `KEYDIFF: CLEAN`, four
    empty sets both directions, 17 run keys and 17 room keys, `SCHEMA` 5. `SighteAddonServerside`
    was **read** (`ingest.py`) and **never written**.
  - **Five mutation probes, all caught, all restored with `git checkout` after the feature was
    committed.** (A) `if (seen != null) floor = seen` → `floor = seen`, the defect verbatim → fails
    2. (B) `return seen != null` → `return floor != null`, the fix done wrong → fails the same 2.
    (C) drop `floor = null` from `reset()` → fails 1. (D) `reportedFloor()` → `"?"` → fails 2.
    (E) drop `@Volatile` → fails 1. Re-create from `build/floorprobe.py` or from the exact old→new
    pairs in the entry's evidence.

## Changed This Session

`floorloss-001` — **a report knows which floor it was.** `DungeonSession.kt:61` did two jobs in one
assignment: it answered "are we in a dungeon" *by writing* the floor, so every tick outside a dungeon
overwrote a known floor with `null`. Two of the three report paths — `ClientPlayConnectionEvents.JOIN`
and `DISCONNECT` — fire after leaving by definition, so for them the live reading is always the wrong
one. Measured on the box: 20 of 22 uploaded reports carry `?`, including all three schema 5 ones.

- **A split, not a second field.** `inDungeon(client)` is now `observeSidebar(sidebarLines(client))`.
  `observeSidebar` returns `seen != null` — about *now* — and writes `floor` only when it saw one.
  Nothing else clears it; `DungeonSession.reset()` does, and that runs on `JOIN` *after* the JOIN site
  has written the report for the run being left.
- **One field and not a live/sticky pair, on purpose.** Within a run they cannot disagree: `onTick`
  returns at `inDungeon` before anything reads `floorNumber` or `inBoss`, and entering or leaving a
  dungeon is a server transfer, so `reset()` lands on both edges of every real run.
- **`floor` is `@Volatile`.** `DISCONNECT` reads it from a Netty event-loop thread while the client
  thread writes it — the same reason `RunReport.reported` is an `AtomicBoolean`.
- **`RunReport.reportedFloor()` names the read all three paths share.** `RunReport.write()` needs a
  live `Minecraft` and cannot be called from this suite at all, which is how a one-line defect
  survived five schema versions with 184 tests around it.
- **No report field, no schema change**, verified with the key diff rather than assumed.

## Broken Or Unverified

- **Unverified, and it is this feature's ceiling: none of the three write paths has run under this
  code.** The dev client cannot log in, so no real report has been written since the fix. What the
  suite measures is the whole remember/forget/answer decision on real sidebar strings and the
  report's read of it. What it cannot: that `sidebarLines` reads a real Hypixel sidebar (unchanged
  by this feature, and evidenced by the two reports on the box that *do* carry `M7`), and that
  `reset()` lands on the `JOIN` edge of every real run. What a real run would show: a report on the
  box carrying `F7`/`M7` instead of `?`, from a floor that was left normally *and* from one quit
  from inside.
- **Unverified in the strict sense — that `@Volatile` is needed.** Its absence cannot be reliably
  observed by a test, so the modifier is asserted by reflection. Probe E is what makes that a guard.
- **`runTicks` is read on the DISCONNECT path and is not volatile.** Pre-existing, out of
  `floorloss-001`'s scope, deliberately not fixed. It is the same shape as the bug just fixed and a
  session that goes near `DungeonSession` next should consider it.
- **Open and recorded, not fixed: `floorname-001`.** The receiver validates `floor` as
  `?|E|[FM][1-7]` under `fullmatch` (`ingest.py:93`, used at `:225`) and `DungeonSession.floor` can
  hold `Entrance` — the receiver plainly expected `E`. A 400 is never retried, so that loses the run.
  Measured unreachable today and **not widened by this fix**; see the entry for the argument. Note
  that `build/keydiff.py` compares key *sets* and cannot see a value-domain mismatch.
- **The 20 `?` reports on the box are not recoverable.** The floor they were played on was never
  stored anywhere. `roomstats.json` groups 115 of 125 room entries under `?` and will keep doing so
  until runs from a build carrying this fix arrive — which needs a release, which is the user's call.
- Known defect otherwise: none introduced. `SighteAddons.RUN_END` still has no test of any kind.
- **`RoomStats.start()` has still never run inside a game** (`scores-fetch-001`'s ceiling), and every
  earlier unverified item carries over unchanged: the atomic rename is atomic; the weights against a
  real run; whether the order heuristic is correct at all; whether `roster_skew` ever fires and
  whether `MapDecoration.name()` carries anything (`deconame-001` — **if the answer is no,
  `party-001` should be closed rather than carried**); the wiring of `positions()`; that Hypixel
  actually sends `chat-001`'s strings; the party half of everything (the one real run is solo and
  deathless, so `clear-001`'s zero-margin gap tolerance is open and the death path has never been
  exercised); the `RED` checkmark path and every pixel of the `/sa` screen; and the cross-repo
  reading that `unattributed` is only ever consumed as a ratio against `roomsCleared`.
- Regressions found: none. `./gradlew test --rerun-tasks` ran over everything and the addition is
  strictly additive — the only pre-existing test line touched is `RunReportTest`'s private `report()`
  helper, which gained `floor: String = "M5"` with the old value as its default.
- Risk for the next session: **eight features and now a ninth exist in source only.** 0.10.0 is
  released and carries none of them. Nothing breaks meanwhile; nothing reaches a player either.

## Next Best Step

- **First, a fresh evaluator pass on `floorloss-001`.** It claims `passing` on three write paths that
  no real client has executed. The argument made here is that the defect and the fix are both
  entirely on the testable side of the seam — the remember/forget/answer decision runs on real
  sidebar strings, five probes measure that the cases can fail, and the unobserved half is named as
  unobserved rather than folded into the grade (telemetry deliberately stayed B). If the evaluator
  disagrees, the alternative status is `in_progress` until somebody plays a floor. Re-run: the
  `verification_command`, the whole suite (expect 193 in 15), `assemble check` with the jar md5
  either side, `python build/keydiff.py`, and all five mutation probes. **The evaluator must not be
  the session that implemented it.**
- **Then the branch needs the user's decision, not another feature.** `floorloss-001` is off
  `ec12a27` and is not merged.
- **If the user offers a run, the ask has grown by one and it is cheap: play a floor, leave it
  normally, then play another and quit the game from inside it.** That verifies both of
  `floorloss-001`'s unobserved paths *and* `runloss-001` end to end, and any launch at all also
  verifies `scores-fetch-001`'s real path. **A party floor with a death in it is still the
  highest-value input overall** — it would move `party-001`, `deconame-001`, `clear-001`'s last open
  note, the rest of `ingame-001` and all three of `chat-001`'s unverified halves at once.
- **`floorname-001` is the cheapest entry on the board** and its decision is already argued in the
  entry: map `Entrance` → `E` on this side, which needs no receiver change and no pairing.
- **Then `runend-001`.** Cheap, and its open question is already answered: write the run-level count,
  not the event count.
- **Do not start `chatfields-001`** by editing `RunReport.kt`. Its first move is a feature in
  `Sighte/skyblock-server`, which is a different repository and a different session.
- `records-001` is deferred by the user — a product decision, not a technical blocker. Worth knowing
  that `floorloss-001` is what would make per-floor judgement possible at all: until now the data
  could not support it either way.

## Do Not Touch

- **`DungeonSession.observeSidebar` must keep answering about the present.** `return seen != null`,
  not `return floor != null`. It looks like it could read the field it just wrote, and it must not:
  that predicate gates the whole session state machine in `SighteAddons.onTick`, so a `true` outside
  a dungeon would be far worse than the `?` this feature removed. Measured: probe B fails 2.
- **Only `DungeonSession.reset()` may clear the floor**, and it must stay *after* `RunReport.write`
  at the JOIN site. Clearing anywhere else — including "tidying" `observeSidebar` back to an
  unconditional assignment — files every abandoned run under `?` again. Measured: probes A and C.
- **`DungeonSession.floor` must stay `@Volatile`.** `DISCONNECT` reads it from a Netty thread. The
  test asserts the modifier by reflection because no test can observe its absence.
- **`RunReport.reportedFloor()` must not ask the client for anything.** It runs on the disconnect
  path, where `Minecraft.player` is being nulled by another thread — the whole argument is in
  `RunReport.uploader`'s KDoc.
- **`RoomStats.adopt` must refuse to install once the session has resolved.** A document that lands
  mid-run would make a run incomparable with itself. Measured: removing it fails 1.
- **`RoomStats.cachedEtag` must return null when the document is not on disk, and `store` must write
  the document before the tag.** Two halves of one guard. Measured: removing either fails 1.
- **The scores cache must reach disk through `.part` + rename**, same as `RunReport`'s. The suite
  sees that a stale `.part` is consumed, not the rename itself, so a "simplification" back to
  `Files.writeString` will look harmless and will not be. `RoomStats` carries its own copy of
  `replace` **on purpose**; unify them once both branches have landed, not before.
- **Only a body that parses may become the cache.** Measured: accepting an unparseable `200` fails 1.
- **`RoomStatsTest`'s loopback server is not a network test and must not be turned into one.** Do not
  point any case in the suite at the real box — `init.sh` would then depend on the box being up.
- **`docs/evidence/session-1786719912927/` is evidence, not documentation.** **`readout.sh` still
  asserts `run_end events 0` and must keep doing so.**
- **The rejected upgrade path in `PartyTracker.assign`'s KDoc is a measurement, not an opinion.**
- **`assign`'s `trustOrder` guard**, and **`assign` must stay pure and stay `internal`**.
- **`ChatEvents`' patterns are cited, not invented — keep them that way.**
- **`SecretTracker.chatAttribution` returns `Boolean?` and the null is load-bearing.**
- **`ChatEvents.nearMiss` must not be widened to log every chat line.**
- **`unattributed` must stay a count of *rooms*.**
- **`RunReport.SCHEMA`, 5, must not move and must not go back down.**
- **The seed keys are `rooms.json`'s spelling** — `Ice Fill` and `Water Board`, two words each.
- **The metric is `clearStay` and only `clearStay`.**
- **Do not re-add a bundled snapshot of `roomstats.json` to the jar**, and do not commit the fetched
  document anywhere else. It is 107 KB of the receiver's state, and a copy is that copy drifting.
- **`clearpoints-001`'s notes are history and are marked as such**, and so is the "layer 1 is not
  built" paragraph in `clearpoints-002`'s.
- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`).
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate. **The notes
  for the next release owe one more thing now**, on top of everything `scores-fetch-001` listed: that
  reports written on the way out of a floor used to be filed under `?` and now carry the floor, that
  this is a *value* change and not a schema change so no receiver work is needed, and that runs
  already on the box are not repaired.
- `dist/` by hand — and **`./gradlew build` is not a neutral verification command** while fixes sit
  unreleased. Use `./gradlew assemble check`. `README.md`'s "Build" section still says
  `./gradlew build`, correctly; do not follow it mid-feature and do not "fix" it.
- **Past session entries in `claude-progress.md`.** Supersede in a new entry; do not rewrite history.
  The "Current Verified State" section now opens with a correction block for exactly this reason.
- `SighteAddonServerside`. It was **read** this session and **not written**.
- `evaluator-rubric.md`'s structure. Harness file; changes need the user to ask.

## Environment Quirks

- **`build/probe.py`'s recipe needs one correction: `encoding="utf-8"` as well as `newline=""`.**
  `DungeonSession.kt` contains em dashes and Python on this machine defaults to cp1252, which throws
  `UnicodeDecodeError` at position 692. `build/floorprobe.py` has it right.
- **`DungeonSession.floor` is still writable from a test by reflection** (`getDeclaredField("floor")`,
  `isAccessible = true`) and `ContributionTrackerTest` and `DungeonSessionTest` both do it. **Do not
  call `DungeonSession.reset()` to clean up** — it resets half the mod. `DungeonSessionTest` calls it
  in exactly one case, the one that is *about* `reset()`.
- **`DungeonSession.observeSidebar(List<String>)` is the seam** for anything about the floor. It takes
  the stripped sidebar lines, so a test needs no `Minecraft`. `sidebarLines` still does.
- **`DungeonSession.runTicks` is `private set`** and only `tickClock()` moves it.
- **`RunReport.reported` is process-wide state on an `object`**, so `RunReportTest` calls
  `RunReport.reset()` in `@BeforeEach`. The suite runs sequentially.
- **`RoomStats` reads a file from outside this repository**, so `ContributionTrackerTest` and
  `RoomDatabaseTest` pin `RoomStats.use(RoomScores.NONE)` in `@BeforeEach`. **Any new test that
  touches `weightOf` must do the same.** `RoomStatsTest` clears the resolution in `@BeforeEach` *and*
  `@AfterEach`.
- **`build/` is gitignored, so scripts written there do not survive a clean.** `build/keydiff.py`
  (the `RunReport` ↔ `ingest.py` key diff), `build/edit_features.py` and
  `build/edit_features_floor.py` (byte-safe `feature_list.json` writers that assert their round trip
  before writing), `build/probe.py` and `build/floorprobe.py` are all there. Re-create rather than
  hunt for them.
- **`feature_list.json` is CRLF with raw UTF-8 bytes.** Read it with `encoding='utf-8'` and write it
  back as `json.dumps(..., indent=2, ensure_ascii=False).replace('\n', '\r\n')` plus a trailing
  `\r\n`. **Assert the round trip before editing.** It held again this session.
- **`quality-document.md` and `claude-progress.md` are CRLF too**, and the quality table rows are
  single lines of several hundred characters. The Edit tool preserves CRLF in them; **a Python
  string built from a bash heredoc does not** — normalise with
  `s.replace("\r\n","\n").replace("\n","\r\n")` before writing, or git will warn on `add`.
- **A multi-line anchor in a Python replace must use `\r\n`** in these files, or it silently matches
  zero times.
- **The receiver's `/roomstats` contract, measured 2026-08-14**: `200` with the document and an
  `ETag`, `304` on `If-None-Match`, `503` with body `no scores document` when there is nothing to
  serve, `404` for **every** other path (exact match), `501` for `HEAD`. No token. Not compressed.
  `SETUP.md` §10c in the receiver is the authority. **The document is refolded every half hour**, so
  its `ETag` changes on that cadence.
- **`com.sun.net.httpserver.HttpServer` works in this test suite.** Bind with
  `InetSocketAddress(InetAddress.getLoopbackAddress(), 0)` and read the port back off
  `server.address`.
- **Gradle's user home on this machine is `C:\Users\marvi\scoop\persist\gradle\.gradle`**, not
  `~/.gradle`. **Do not run an unscoped `find /` on this machine.**
- **`javap` on the Fabric API jars answers "when does this event fire, and on which thread" and it is
  cheap.** Extract the module jar, then `javap -p -c -v -cp . <mixin class>`.
- **`git worktree add <path> <branch>` is refused when the main checkout already has that branch
  checked out.** An evaluator wanting an isolated copy wants `git worktree add --detach <path> <sha>`.
- **Restore a mutation probe with `git checkout <file>` — but COMMIT THE FEATURE FIRST.**
  `git checkout` restores from the index.
- **Reading the Minecraft classes is the way to settle a protocol or lifecycle question.** The merged
  jar is at
  `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.1.2/minecraft-merged-043a8b3edf-26.1.2.jar`.
- **`gh api repos/<owner>/<repo>/contents/<path> --jq .content | base64 -d` is the way to read a
  cited mod's source.** **`gh search code` is rate limited to 10 requests per minute.**
- **The real session file lives outside this repository** and only an excerpt is committed. The
  original is at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-1786719912927.jsonl`.
  Treat it as read-only.
- **`*.jsonl` is pinned to LF in `.gitattributes`.**
- **Windows Python cannot execute `./gradlew`** — `WinError 193`. Drive Gradle from bash.
- **Windows Python resolves `/tmp/x` as `C:\tmp\x`**, Git Bash resolves it elsewhere. Use `/c/tmp/...`
  from bash for anything the two share, or keep it in `build/`.
- **A long here-doc through the Bash tool is fragile.** Writing the script to `build/` with the Write
  tool and running `python build/<name>.py` is the reliable path for anything non-trivial.
- **Test results are easiest to read from XML, not from Gradle's console output.**
  `build/test-results/test/*.xml` carries `tests`/`failures`/`errors`/`skipped` per class and the
  `<testcase>` names of the failures. That is how every count here was obtained.
- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes. Warm it is ~6-9 s and it was warm throughout this session.
- **`net.minecraft.ChatFormatting` loads fine in a unit test.** **`MapItemSavedData` and
  `MapDecoration` do not**, and neither does anything needing a live `Minecraft`.
- **`DebugLog.event` is safe to call from a unit test**, and a consequence is that `./gradlew test`
  writes `config/sighteaddons/debug/session-<millis>.jsonl` into the working tree. `config/` is
  gitignored. `Config` initialises fine under `fabric-loader-junit`.
- **`ContributionTracker` is an `object` with run-long state**, so any test that writes to it must
  `reset()` first.
- The live box is reachable read-only over SSH (`ssh -i ~/.ssh/sighte_box -o IdentitiesOnly=yes
  root@217.160.51.229`). **It was not used this session** — the measurement in `floorloss-001`'s
  notes was made before this session started and was taken as given.
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
- `floorloss-001`: `./gradlew test --tests 'sighteaddons.DungeonSessionTest'
  --tests 'sighteaddons.RunReportTest'`
- `scores-fetch-001`: `./gradlew test --tests 'sighteaddons.RoomStatsTest'`
- `party-001`: `./gradlew test --tests 'sighteaddons.PartyTrackerTest'`
- `chat-001`: `./gradlew test --tests 'sighteaddons.ChatEventsTest'
  --tests 'sighteaddons.SecretTrackerTest' --tests 'sighteaddons.ContributionTrackerTest'`
- Paired-feature key diff: `python build/keydiff.py` (re-create from its docstring after a clean)
- Mutation probes for this feature: `python build/floorprobe.py <A|B|C|D|E>`, then
  `git checkout src/main/kotlin/sighteaddons/DungeonSession.kt src/main/kotlin/sighteaddons/RunReport.kt`
- Read out the real run: `bash docs/evidence/session-1786719912927/readout.sh`
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
- Test counts: `build/test-results/test/*.xml`, not the console
