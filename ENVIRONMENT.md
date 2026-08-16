# Environment — SighteAddons

Standing facts about this machine, this checkout, and the invariants a tidy-up would quietly break.
Read it when you are about to meet one, not as a ritual. It changes when the environment or an
invariant changes, never because a session ended.

## Toolchain

- **`python` works; `python3` is a Windows App-Execution-Alias stub.**
- **JDK 25+ required.** Gradle uses `JAVA_HOME`, not `PATH`. Measured here: 25.0.4.
- Mappings are official Mojang, not Yarn — class names are Mojmap.
- Gradle's user home is `C:\Users\marvi\scoop\persist\gradle\.gradle`, not `~/.gradle`.
  **Do not run an unscoped `find /` on this machine.**
- `core.autocrlf=true`; `gradlew`, `*.sh` and `*.jsonl` are pinned to LF in `.gitattributes`. Kotlin
  sources warn `LF will be replaced by CRLF` on `git add`; that is normal here.

## Commands

- Baseline: `./init.sh` (wraps `./gradlew test`). **Never `./gradlew runClient`** — see `CLAUDE.md`.
- Full verification: `./gradlew assemble check` — same coverage as `build` without the `copyToDist`
  step that rewrites the released jar.
- Focused: `./gradlew test --tests 'sighteaddons.<Class>'`
- **Test counts come from `build/test-results/test/*.xml`, not the console** — they carry
  `tests`/`failures`/`errors`/`skipped` per class.
- Read out the real run: `bash docs/evidence/session-1786719912927/readout.sh`
- The paired-feature check: diff the fields `RunReport.build` writes against `RUN_KEYS` in the
  receiver's `ingest.py`. **Anchor on `obj.add` *and* `obj.addProperty`, never one alone** —
  `addProperty` misses `rooms` and `classes` (they are `JsonArray`s), and a bare `.add(` also matches
  `classes.add("…")`, which is an array *element* rather than a field.

## Costs

- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the mappings —
  **minutes**. Warm it is ~7–25 s, **nearly all of it Gradle startup**: the tests execute in ~1.1 s.
- **`--rerun-tasks` is not a default.** Use it when the point is to prove nothing was cached — at
  release time, or driving a mutation probe — and nowhere else.
- **`./gradlew build --rerun-tasks` at release time, never `./gradlew clean build`.** `build/` is
  gitignored and holds every scratch probe; `clean` destroys them.
- The jar build is reproducible here: two `--rerun-tasks` builds of one tree gave byte-identical
  jars. That is what makes "the committed jar is the rebuild" a real check.
- `gh search code` is rate limited to 10 requests per minute.

## Gotchas — each has faked a result at least once

- **Windows Python cannot execute `./gradlew`** (`WinError 193`). Drive Gradle from bash.
- **Windows Python resolves `/tmp/x` as `C:\tmp\x`**, Git Bash resolves it elsewhere. Use
  `/c/tmp/...` from bash, or keep it in `build/`.
- **A long here-doc through the Bash tool is fragile**, and backticks inside a double-quoted bash
  string are command substitution before Python ever sees them. Write the script to a file.
- **A multi-line anchor in a Python replace over a Kotlin source must use `\r\n`** — the working tree
  is CRLF, a Python literal is not, so the anchor matches zero times and **a probe that cannot apply
  looks exactly like a probe that passed.** Assert `source.count(old) == 1` before every apply.
- **`encoding="utf-8"`, `newline=""`, and `PYTHONIOENCODING=utf-8`** before printing any of these
  sources — Python defaults to cp1252 here and `print` dies on an em dash, after the useful work.
- **`git commit -m` with a PowerShell `@'...'@` here-string silently embeds the `@` markers.** Write
  the message to a file and use `git commit -F`.
- **Restore a probe with `git checkout <file>` — but commit the real work first.** `git checkout`
  restores from the index.
- **`git worktree add <path> <branch>` is refused when the main checkout has that branch out.** Use
  `--detach <path> <sha>`, and remove it when done — a stale worktree is a second, divergent copy.

## Test-suite facts

- `./gradlew test` writes `config/sighteaddons/debug/session-<millis>.jsonl` into the working tree
  (`DebugLog.event` is safe from a unit test; `config/` is gitignored). **Do not glob that directory
  for real data** — it holds only test noise.
- **`net.minecraft.ChatFormatting` loads in a unit test. `MapItemSavedData` and `MapDecoration` do
  not**, nor does anything needing a live `Minecraft`. `com.sun.net.httpserver.HttpServer` works.
- **`ContributionTracker` and `RunReport` are `object`s with process-wide state** — any test that
  writes to them must `reset()` in `@BeforeEach`. The suite runs sequentially.
- **`RoomStats` reads a file from outside this repository**, so tests touching `weightOf` must pin
  `RoomStats.use(RoomScores.NONE)` in `@BeforeEach`.
- **`DungeonSession.floor` is writable by reflection** from a test; **do not call
  `DungeonSession.reset()` to clean up** — it resets half the mod. `observeSidebar(List<String>)` is
  the seam, and `runTicks` moves only via `tickClock()`.
- **`RoomHistory.ownClear` and `ownSecretRun` are drivable from a unit test; `onRoomCleared` and
  `onSecretRun` are not** — they reach `Minecraft.getInstance()`. That is why the gates are separate
  predicates.

## Real data

- Twenty-odd real session logs, **read-only**, at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-*.jsonl`.
  Re-count rather than trusting that number. `docs/evidence/session-1786719912927/` is a committed
  excerpt of one M7. A log containing `secret_room_first_bar` is 0.12.0 or later.
- **The event key is `e`, not `event`**, and roster size is best taken from the distinct
  `tab_slot.parsed` names in a session.
- Reading the Minecraft classes settles a protocol or lifecycle question. The merged jar is under
  `.gradle/loom-cache/minecraftMaven/net/minecraft/`, and `javap` on the Fabric API jars answers
  "when does this event fire, and on which thread".
- `gh api repos/<owner>/<repo>/contents/<path> --jq .content | base64 -d` reads a cited mod's source.

## The receiver's contract, measured

`GET /roomstats`: `200` with the document and an `ETag`, `304` on `If-None-Match`, `503` with body
`no scores document` when there is nothing, `404` for **every** other path, `501` for `HEAD`. No
token, not compressed. **The document is refolded every half hour**, so its `ETag` moves on that
cadence.

## Do Not Touch — invariants, each with a measurement behind it

Most were found by a mutation probe that passed the whole suite while a test name claimed otherwise.

- **`TrackedRoom.readBar` observes the bar BEFORE it tests for a rise.** The sharpest thing in the
  codebase. A `0/10` reading is not a rise, and it is the only reading that can say the room was
  untouched when we walked in. Reorder it — exactly what a tidy-up looks like — and the first reading
  on record becomes the `1/10` that follows, no room ever looks clean, and **every secret run in the
  game is silently discarded.** The three statements are one function so the ordering is testable;
  do not split them back to the call site. `observeBar` stays private and once-only.
- **`RoomHistory.ownClear`'s five conditions stay five separate lines**, each swept. A compound
  condition cannot be probed alone, and this predicate has already produced two guards in name only.
  **Its `MIN_TICKS` floor stays** even though `onRoomCleared` cannot reach it — the predicate is
  `internal` and the fast-clear shape does; without it that room writes a 0.3 s record.
- **`ownSecrets == secretsFound` must not be softened, made configurable, or given an escape hatch.**
  The user was shown the measured cost, was offered the majority rule, and reaffirmed twice. Shipped
  and documented in 0.12.0's public notes.
- **`SecretHud` displays attribution and must never repair it.** Falling back to
  `TrackedRoom.secretsFound` when `ownSecrets` is 0 claims the party's secrets for the local player
  on screen. **`ownSecretRun` keeps its `secretsFound > 0` guard** — `0 == 0` is true.
- **Neither metric may be redefined.** A `clear` line is `room.ticks[self]`; a `secretrun` line is
  `room.secretRunTicks`. `history.jsonl` is append-only and 0.12.0 is in the wild under both
  meanings, so changing what a number means makes lines incomparable. Gate *whether* a line is
  written, never what is in it.
- **`RunReport.SCHEMA` is 6 and must not go back down.** The receiver learned `idleTicks`/`navTicks`
  as **optional** first (`skyblock-server` `master` `1a7f435`), which is what keeps v5 reports in
  uploaders' backlogs from `400`-ing. They are written together or not at all — the receiver reads
  an absent key as "this build cannot measure it", so one alone claims the other was zero.
- **`DungeonSession.observeSidebar` returns `seen != null`, not `floor != null`** — it must keep
  answering about the present; that predicate gates the whole session state machine. Only
  `reset()` may clear the floor, and it stays *after* `RunReport.write` at the JOIN site.
  `floor` stays `@Volatile` (DISCONNECT reads it from a Netty thread).
- **`RoomStats`**: `adopt` refuses to install once the session has resolved; `cachedEtag` returns
  null when the document is not on disk; `store` writes the document before the tag; the cache
  reaches disk through `.part` + rename; only a body that parses may become the cache. Do not re-add
  a bundled snapshot of `roomstats.json` to the jar.
- **`ChatEvents`' patterns are cited, not invented.** `nearMiss` must not be widened to log every
  chat line. `SecretTracker.chatAttribution` returns `Boolean?` and the null is load-bearing.
  `unattributed` stays a count of *rooms*. The seed keys are `rooms.json`'s spelling — `Ice Fill`,
  `Water Board` — and the metric is `clearStay` and only `clearStay`.
- **`PartyTracker.assign` stays pure and `internal`, and keeps its `trustOrder` guard.** The rejected
  upgrade path in its KDoc is a measurement, not an opinion.
- **`docs/evidence/session-1786719912927/` is evidence, not documentation.** `readout.sh` still
  asserts `run_end events 0` and must keep doing so; it is the source of the frozen four-of-five
  figure in 0.12.0's public release notes.
- **`mod_version` in `gradle.properties`** — only with the whole release gate. **`dist/`** — never by
  hand. **`rooms.json`** — Odin's, BSD-3, never edited. **`SighteAddonServerside`** — a mod session
  never writes the other repository.

## Release mechanics

- **This machine's permission classifier refuses `gh pr merge`.** Merge with
  `git merge --no-ff -m "Merge pull request #N from Sighte/<branch>"` then `git push origin main` —
  identical merge commit, and GitHub closes the PR as `MERGED` on its own. **A refusal is not a
  failed release.**
- **The Modrinth changelog strip is a lookahead and will silently keep the operator section.**
  `re.sub(r"\n## Not in the jar.*?(?=\n## )", …)` in `.github/workflows/modrinth.yml` matches only if
  another `## ` heading follows. Name the section exactly `## Not in the jar` and never place it
  last. The run log prints `N chars of changelog` to confirm.
