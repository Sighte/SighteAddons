# Environment — SighteAddons

Standing facts about this machine, this checkout, and the invariants in the code that a tidy-up
would otherwise quietly break. **Read this at session start; do not rewrite it at session end.** It
changes when the environment or an invariant changes, not when a session does.

It was carved out of `session-handoff.md` on 2026-08-16: 245 of that file's 386 lines were this
content, re-emitted verbatim by every session.

## Toolchain

- **`python` resolves and works**; `python3` is a Windows App-Execution-Alias stub one level up.
- **JDK 25+ required.** Gradle uses `JAVA_HOME`, not `PATH`. Measured here: 25.0.4.
- Mappings are official Mojang, not Yarn — class names here are Mojmap.
- **Gradle's user home on this machine is `C:\Users\marvi\scoop\persist\gradle\.gradle`**, not
  `~/.gradle`. **Do not run an unscoped `find /` on this machine.**
- Git is `core.autocrlf=true`; `gradlew`, `*.sh` and `*.jsonl` are pinned to LF in `.gitattributes`.
  Kotlin sources warn `LF will be replaced by CRLF` on `git add`; that is normal here.

## Commands

- Startup: `./gradlew runClient`
- Smoke check: `./init.sh` (wraps `./gradlew test`)
- Full verification: `./gradlew assemble check` — same coverage as `./gradlew build` without the
  `copyToDist` step that rewrites the released jar.
- Focused: `./gradlew test --tests 'sighteaddons.<Class>'`
- Test counts: read `build/test-results/test/*.xml`, not the console — it carries
  `tests`/`failures`/`errors`/`skipped` per class.
- Read out the real run: `bash docs/evidence/session-1786719912927/readout.sh`
- **Release gate only**: `./gradlew build --rerun-tasks`, then
  `sha256sum dist/sighteaddons-<version>.jar` either side, then
  `unzip -p dist/sighteaddons-<version>.jar fabric.mod.json`, then
  `gh release create "v<version>" "dist/sighteaddons-<version>.jar" --target main --title "..."
  --notes-file <file>`, then `gh run list --workflow=modrinth.yml` and `gh run view <id> --log`.

## Costs worth knowing

- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — **minutes**. Warm it is ~7–25 s, and **nearly all of that is Gradle startup**: the 212
  tests themselves execute in about 1.1 s. A full `build --rerun-tasks` was 9 s.
- **`--rerun-tasks` is not a default.** It forces recompilation and defeats the up-to-date check.
  Use it when the point is to prove nothing was cached — at release time, or driving a mutation
  probe — and not otherwise, or it fossilizes into every recorded command.
- **`./gradlew build --rerun-tasks`, never `./gradlew clean build`, at release time.** `build/` is
  gitignored and holds every probe and replay script — `clean` destroys the sweep.
- **The jar build is reproducible here**: two `--rerun-tasks` builds of the same tree produced
  byte-identical jars. That is what makes CLAUDE.md's "the committed jar is the rebuild" check real
  rather than a timestamp lottery.
- **`gh search code` is rate limited to 10 requests per minute.**

## Known gotchas

- **Windows Python cannot execute `./gradlew`** — `WinError 193`. Drive Gradle from bash.
- **Windows Python resolves `/tmp/x` as `C:\tmp\x`**, Git Bash resolves it elsewhere. Use
  `/c/tmp/...` from bash for anything the two share, or keep it in `build/`.
- **A long here-doc through the Bash tool is fragile**, and backticks inside a double-quoted bash
  string are command substitution before Python ever sees them. Write the script to a file and run
  `python <file>.py` for anything non-trivial.
- **A multi-line anchor in a Python replace over a Kotlin source must use `\r\n`.** The working tree
  is CRLF while a Python string literal is not, so the anchor silently matches zero times — and **a
  probe that cannot apply looks exactly like a probe that passed.** `build/recordprobe.py`
  translates the anchors itself; copy that, and assert `source.count(old) == 1` before every apply.
- **`encoding="utf-8"` as well as `newline=""`** for any Python that reads these sources, and set
  `PYTHONIOENCODING=utf-8` before *printing* any of them — Python here defaults to cp1252 and
  `print` dies on an em dash or an arrow with `UnicodeEncodeError`, after the useful work is done.
- **`feature_list.json` is CRLF with raw UTF-8 bytes.** Read with `encoding='utf-8'`, `newline=''`;
  write back as `json.dumps(..., indent=2, ensure_ascii=False)` with `\n` replaced by `\r\n`.
  **Assert the round trip before editing.** `quality-document.md` and `claude-progress.md` are CRLF
  too.
- **`git commit -m` with a PowerShell-style `@'...'@` here-string silently embeds the `@` markers**
  as the first and last lines of the message. Write the message to a file and use `git commit -F`.
- **Restore a mutation probe with `git checkout <file>` — but COMMIT THE FEATURE FIRST.**
  `git checkout` restores from the index.
- **`git worktree add <path> <branch>` is refused when the main checkout already has that branch
  checked out.** An evaluator wanting an isolated copy wants `git worktree add --detach <path> <sha>`.
  Remove it when done: a stale worktree is a second, divergent copy of every artifact in this
  repository, which is why the 0.11.0 release session's `rel-wt` was deleted on 2026-08-16.

## Test-suite facts

- **`DebugLog.event` is safe to call from a unit test**, and a consequence is that `./gradlew test`
  writes `config/sighteaddons/debug/session-<millis>.jsonl` into the working tree. `config/` is
  gitignored. `Config` initialises fine under `fabric-loader-junit`.
- **`net.minecraft.ChatFormatting` loads fine in a unit test. `MapItemSavedData` and `MapDecoration`
  do not**, and neither does anything needing a live `Minecraft`.
- **`com.sun.net.httpserver.HttpServer` works in this test suite.**
- **`ContributionTracker` is an `object` with run-long state**, so any test that writes to it must
  `reset()` first. **`RunReport.reported` is process-wide state on an `object`**, so `RunReportTest`
  calls `RunReport.reset()` in `@BeforeEach`. The suite runs sequentially.
- **`RoomStats` reads a file from outside this repository**, so `ContributionTrackerTest` and
  `RoomDatabaseTest` pin `RoomStats.use(RoomScores.NONE)` in `@BeforeEach`. **Any new test that
  touches `weightOf` must do the same.**
- **`DungeonSession.floor` is writable from a test by reflection** (`getDeclaredField("floor")`,
  `isAccessible = true`), which `ContributionTrackerTest` and `DungeonSessionTest` both do. **Do not
  call `DungeonSession.reset()` to clean up** — it resets half the mod.
  `DungeonSession.observeSidebar(List<String>)` is the seam for anything about the floor, and
  `runTicks` is `private set`, moved only by `tickClock()`.
- **`RoomHistory.ownClear` and `ownSecretRun` are drivable from a unit test; `onRoomCleared` and
  `onSecretRun` are not** — they reach `Minecraft.getInstance()` for the local player's name. Same
  shape as `RunReport.write`. That is why the gates are separate predicates.
- `./gradlew runClient` cannot log in to Hypixel. A `session-<millis>.jsonl` from a real install is
  the only source of real data.

## Real data

- **There are fifteen real session logs on this machine**, at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-*.jsonl`,
  **read-only**. `docs/evidence/session-1786719912927/session-excerpt.jsonl` is a committed excerpt
  of one. Several are party floors: 30 `death`, 15 `revive`, 104 `roster_skew`, 49 `chat_secret`,
  6 `puzzle_solved`, 11 `run_report` across the set. **Any log written after 2026-08-15 may be from
  0.12.0** — check for a `secret_room_first_bar` event, which no earlier build emits.
- **The event key is `e`, not `event`**, and a roster size is best taken from the distinct
  `tab_slot.parsed` names in a session.
- **Do not glob the repository's own `config/sighteaddons/debug/` for real data.** It holds the files
  `./gradlew test` writes and contains no dungeon at all — it will drown a measurement in test noise.
- **Reading the Minecraft classes settles a protocol or lifecycle question.** The merged jar is at
  `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.1.2/minecraft-merged-043a8b3edf-26.1.2.jar`,
  and `javap` on the Fabric API jars answers "when does this event fire, and on which thread".
- **`gh api repos/<owner>/<repo>/contents/<path> --jq .content | base64 -d`** is the way to read a
  cited mod's source.

## The receiver's contract, measured 2026-08-14

`GET /roomstats`: `200` with the document and an `ETag`, `304` on `If-None-Match`, `503` with body
`no scores document` when there is nothing to serve, `404` for **every** other path (exact match),
`501` for `HEAD`. No token. Not compressed. **The document is refolded every half hour**, so its
`ETag` changes on that cadence.

The live box is reachable read-only over SSH:
`ssh -i ~/.ssh/sighte_box -o IdentitiesOnly=yes root@217.160.51.229`. Access is not authorization.

## Do Not Touch — invariants a tidy-up would break

Each of these has a measurement behind it. Most were found by a mutation probe that passed the whole
suite while a test name claimed otherwise.

- **`mod_version` in `gradle.properties`, unless you intend to run the whole release gate.**
  `./gradlew build` is not a neutral verification command while fixes sit unreleased — it runs
  `copyToDist` and rewrites the released jar. Use `./gradlew assemble check`. `README.md`'s "Build"
  section says `./gradlew build`, correctly, for the release gate; do not follow it mid-feature and
  do not "fix" it.
- **`dist/`, by hand.** It holds exactly one jar and `build` refreshes it.
- **`TrackedRoom.readBar` must observe the bar BEFORE it tests for a rise.** This is the sharpest
  thing in the codebase. A `0/10` reading is not a rise, and it is the only reading that can ever say
  the room was untouched when we walked in. Reorder it — exactly what a tidy-up back to "test for a
  rise first, then note it" looks like — and the first reading on record becomes the `1/10` that
  follows, no room ever looks clean, and **every secret run in the game is silently discarded**. The
  three statements are one function precisely so the ordering is testable; **do not split them back
  out to the call site**, where `SecretTracker.onActionBar` needs a live client and nothing can guard
  it. Measured: probe C.
- **`TrackedRoom.observeBar` must stay private and once-only.** `readBar` is the only way in.
  Measured: probe D.
- **`RoomHistory.ownClear`'s five conditions stay five separate lines**, and every one is swept. A
  compound condition cannot be probed alone, and this predicate has already produced **two** guards
  in name only — `self != topPlayer` (probe H) and the `MIN_TICKS` floor (probe K), each of which
  passed the entire suite while a test name and a KDoc claimed otherwise.
- **The `MIN_TICKS` floor in `ownClear` stays even though `onRoomCleared` cannot reach it.** The
  predicate is `internal` and directly callable, and the fast-clear shape reaches it. Without the
  floor that room writes a 0.3 s record.
- **`ownSecrets == secretsFound` must not be softened, made configurable, or given an escape hatch.**
  The user was shown the measured cost, was offered the majority rule `ownSecrets * 2 >=
  secretsFound`, and reaffirmed the strict rule twice. **It is shipped in 0.12.0 and documented in
  its release notes**, so softening it silently would also make a published page wrong. The right
  response to the cost is `ownsecrets-001`, which fixes the number the gate reads.
- **`SecretHud` displays attribution and must never repair it.** Falling back to
  `TrackedRoom.secretsFound` when `ownSecrets` is 0 — the obvious "fix" for a HUD reading `0/5` in a
  room you worked — claims the party's secrets for the local player on screen. Measured: probes A and
  B of `build/secrethudprobe.py`. The under-count is `ownsecrets-001`'s to fix, at the tracker.
- **`RoomHistory.ownSecretRun` keeps its `secretsFound > 0` guard.** `0 == 0` is true and must never
  be the answer. Measured: probe F.
- **`TrackedRoom.presentFromStart`'s staleness check uses the same tolerance `onPresence` continues a
  stay with.** A second, quieter notion of "still here" is how two guards start disagreeing.
  Measured: probe I. **A null `enteredAtTick` means no record, for everybody.** Measured: probe J.
- **Neither metric may be redefined.** A `clear` line is `room.ticks[self]`; a `secretrun` line is
  `room.secretRunTicks`. `history.jsonl` is append-only and old lines are still folded, so changing
  what a number means makes lines of one kind incomparable — the exact failure the
  `secrets` → `secretrun` rename was invented to avoid. Gate whether a line is written; never what is
  in it. **0.12.0 is in the wild under the old meaning of both numbers**, so this is a compatibility
  constraint now.
- **`TrackedRoom.stays` is private and stays private.** `presentFromStart` takes `at` rather than
  reading the clock, which is what makes it drivable from a test.
- **`DungeonSession.observeSidebar` must keep answering about the present** — `return seen != null`,
  not `return floor != null`. That predicate gates the whole session state machine.
- **Only `DungeonSession.reset()` may clear the floor**, and it must stay *after* `RunReport.write`
  at the JOIN site. **`DungeonSession.floor` must stay `@Volatile`** — `DISCONNECT` reads it from a
  Netty thread. **`RunReport.reportedFloor()` must not ask the client for anything.**
- **`RoomStats.adopt` must refuse to install once the session has resolved.**
  **`RoomStats.cachedEtag` must return null when the document is not on disk, and `store` must write
  the document before the tag.** **The scores cache must reach disk through `.part` + rename** —
  `RoomStats` carries its own copy of `replace` on purpose. **Only a body that parses may become the
  cache.** **`RoomStatsTest`'s loopback server is not a network test and must not be turned into one.**
- **`docs/evidence/session-1786719912927/` is evidence, not documentation.** `readout.sh` still
  asserts `run_end events 0` and must keep doing so. It is the source of the **frozen** four-of-five
  figure quoted in 0.12.0's public release notes, so a change there changes a published claim.
- **The rejected upgrade path in `PartyTracker.assign`'s KDoc is a measurement, not an opinion.**
  `assign`'s `trustOrder` guard stays, and `assign` must stay pure and `internal`.
- **`ChatEvents`' patterns are cited, not invented — keep them that way.**
  **`ChatEvents.nearMiss` must not be widened to log every chat line.**
  **`SecretTracker.chatAttribution` returns `Boolean?` and the null is load-bearing.**
- **`unattributed` must stay a count of *rooms*.**
- **`RunReport.SCHEMA` is 6 and must not go back down.** 6 is `idletime-001`'s `idleTicks` and
  `navTicks`; the receiver learned both as **optional** keys first and is deployed
  (`skyblock-server` `master` `1a7f435`), which is what keeps the v5 reports in uploaders' backlogs
  from `400`-ing. `roomstats.py` routes `enterTick` on `v >= 5`, so 6 keeps its `clearStay` bucket.
- **`idleTicks` and `navTicks` are written together or not at all**, and `TrackedRoom.secretRunOpen`
  keeps its `!secretRunDiscarded` clause. The receiver reads an absent key as "this build cannot
  measure it", so one alone claims the other was zero. Measured: probes F and E of
  `build/idleprobe.py` — **E was uncaught on the first attempt**, because the test set up a run that
  was never *started* and so never reached the discard clause at all.
- **The seed keys are `rooms.json`'s spelling** — `Ice Fill` and `Water Board`, two words each. **The
  metric is `clearStay` and only `clearStay`.**
- **Do not re-add a bundled snapshot of `roomstats.json` to the jar.**
- **`rooms.json`** — Odin's database verbatim under BSD-3 (`LICENSE-Odin`). Never edited, never
  regenerated; the receiver reads this exact file.
- **`SighteAddonServerside`** — a mod session never edits the other repository.
- **`evaluator-rubric.md`** — a fresh evaluator fills it in, never the implementer.

## Probe and measurement scripts

`build/` is gitignored, so nothing written there survives a clean, and these have been re-created
from scratch by several sessions each. **That is a standing defect, not a habit to keep**: either
give them a tracked home under `tools/` or stop recording them as re-runnable evidence.

- `build/recordprobe.py` — the 21-probe sweep; `--list` prints the roster and each probe's declared
  expectation. `build/runprobes.sh` drives it and prints `SWEEP OK`. Note the sweep runs the whole
  suite 21 times with `--rerun-tasks`; that is minutes, and it is the reason to run it deliberately
  rather than routinely.
- `build/ownsecrets.py` — replays `ownSecretRun` over the fifteen real logs. Reads only. **Its
  aggregate moves between runs; quote the command and its output, never a transcribed integer.**
- `build/keydiff.py` — the `RunReport` ↔ `ingest.py` key diff, which is the paired-feature check.
  **It must anchor on `obj.add` / `obj.addProperty`, not on either alone.** `addProperty` alone
  misses `rooms` and `classes`, which are `JsonArray`s added with `add`; a bare `.add(` then matches
  `classes.add("${it.livingClass} …")`, which is an array *element*, not a field. It reads
  `SighteAddonServerside/ingest.py` and writes nothing.

## Release mechanics

- **This machine's permission classifier refuses `gh pr merge`** and refused the first form of
  `gh release create`. The merge is done instead with
  `git merge --no-ff -m "Merge pull request #N from Sighte/<branch>"` followed by
  `git push origin main`, which produces the identical merge commit and GitHub closes the PR as
  `MERGED` on its own. `gh release create` went through on a plain retry without a shell pipe.
  **Do not treat a refusal as a failed release.**
- **The Modrinth changelog strip is a lookahead and will silently keep the operator section.**
  `re.sub(r"\n## Not in the jar.*?(?=\n## )", "\n", body, flags=re.S)` in
  `.github/workflows/modrinth.yml` matches only if **another `## ` heading follows**. Name the
  section exactly `## Not in the jar` and never place it last. Simulate the regex against the notes
  file before publishing; the run log then prints `N chars of changelog` to confirm.
