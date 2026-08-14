# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. **175 tests across 14 classes, 0 failures,
  0 skipped** — `main` at `d356ff2` (which is `party-001` merged) carries 167 in the same 14, and the
  difference is exactly `RoomStatsTest` 9 → 17. No class was added, nothing was removed, renamed or
  weakened. `mod_version=0.9.0`, `dist/sighteaddons-0.9.0.jar` unchanged (md5
  `b2ebc35ccfeb9cc96134eb3b18f0306f`, measured before and after `assemble check`),
  `RunReport.SCHEMA` still 5.
- Branch: `scores-fetch-001`, off `main` at `d356ff2`, on the user's instruction. **Not pushed and
  not merged.** For how many commits it carries, run `git rev-list --count d356ff2..HEAD` and
  `git log --oneline d356ff2..HEAD` for what each is. **Do not write the number into an artifact** —
  three consecutive reviews found a hand-transcribed count wrong, which is why it is derived.
- **A second branch is open and it is not this one.** `runloss-001`, also off `d356ff2`, `passing` in
  its own artifacts and under evaluation. This branch predates it, so **this branch's
  `feature_list.json` shows `runloss-001` as `not_started` and its progress log has no session 012** —
  both are the branch point speaking, not a claim, and neither should be "corrected" here. Merging
  the two will conflict in `feature_list.json`, `claude-progress.md`, `session-handoff.md` and
  `quality-document.md`; **the resolution is a union**, because the two features share no source file
  (`RunReport.kt` and `SighteAddons.kt`'s connection events are that branch's and were not touched
  here; `RoomStats.kt`, `TelemetryUpload.kt` and the one `RoomStats.start()` line in
  `onInitializeClient` are this one's).
- What verification actually ran (exact commands), all at `74e6859`:
  - `./gradlew test --tests 'sighteaddons.RoomStatsTest'` → PASS, 17 tests, 0 failures. This is
    `scores-fetch-001`'s `verification_command`, **unchanged in text**; the class grew from 9 to 17
    and five mutation probes measure that it can fail.
  - `./gradlew test --rerun-tasks` → `classes 14, tests 175, skipped 0, failures 0, errors 0`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 identical either side, and
    `git status --short dist/ gradle.properties` empty
  - `bash init.sh` → `BASELINE: PASSING` (run at session start, on `main` at `d356ff2`, 167 tests,
    and again at the end)
  - **The paired-feature check, mechanically.** `python build/keydiff.py` — re-created this session
    (`build/` is gitignored, so it never survives a clean; the docstring says what it does). Four
    empty sets, both directions, 17 run keys and 17 room keys, `SCHEMA` 5. **One correction against
    the version the previous handoff describes**: the key regex now requires the comma after the
    string, because `classes.add("${it.livingClass} …")` appends a *value* to a `JsonArray` and
    counting it invents an 18th run key that the validator appears to reject. `SighteAddonServerside`
    was **read** (`ingest.py`, `SETUP.md` §10c, its `feature_list.json`) and **never written**.
  - **The live receiver, through the mod's own code.** A `LiveScoresProbe` test class calling
    `RoomStats.refresh(client, TelemetryUpload.PUBLIC_URL, build/live-probe)` twice, **deleted rather
    than committed** — a test that needs the box up would make `init.sh` depend on the box being up.
    Recorded in the entry's evidence with everything needed to re-create it. Result: cold `Fresh`,
    107,362 bytes, `generatedTs 1786743023019`, `rooms 105`, **`sampled 0`**, no stray `.part`; warm
    `Current` (the 304) with the cache untouched; a 404 path → `HTTP 404`; `192.0.2.1` →
    `HttpConnectTimeoutException`. `cmp` against `curl`'s bytes matched, and `curl` with the stored
    `ETag` answered `304`.
  - **Five mutation probes, all caught, all restored with `git checkout` after the feature was
    committed.** (A) drop `if (resolved != null) return false` from `adopt` → fails 1. (B) `store`
    writes with `Files.writeString` instead of `replace` → fails 1. (C) `cachedEtag` skips the
    "is the document actually on disk" guard → fails 1. (D) a `200` that does not parse becomes
    `Fresh` → fails 1. (E) drop the `If-None-Match` header → fails 1. Re-create all five from the
    exact old→new pairs in the entry's evidence.

## Changed This Session

`scores-fetch-001` — **layer 1 of `RoomStats` exists, so the room weights no longer need a jar
release to improve.** The blocker was the receiver's and the receiver cleared it: `scores-002` on
`Sighte/skyblock-server` is deployed and `GET /roomstats` is live. That was checked from outside
before a line was written, not taken from a paste.

- **It is wiring, exactly as `clearpoints-002` predicted.** `RoomScores.parse` already read the
  receiver's document verbatim, so no format work was owed and none was done. New:
  `RoomStats.start/refresh/fetch/store/cachedEtag/adopt` and a `room_scores_fetch` debug event.
- **The pattern is `TelemetryUpload`'s, deliberately**, on its own thread: a named daemon thread from
  `onInitializeClient`, everything wrapped, nothing on the client thread and nothing during a run.
  Its own rather than a step inside the upload's, because the upload hands over a backlog at 60 s a
  file and the scores would then arrive after the first room of the evening.
- **A document that arrives after the session resolved is cached and not adopted.** That refusal is
  the feature: a room's weight is read the moment it is cleared and points are compared between party
  members, so a mid-run change makes a run incomparable with itself. `resolved`, `adopt` and `use`
  are synchronised on `RoomStats` now, because the fetch is on another thread.
- **The `ETag` wedge is guarded at both ends** — document written before tag, and no tag is ever
  offered for a document that is not on disk. Own case, own probe.
- **`Config.upload` gates the fetch**, which is a decision rather than a requirement. It is written
  down as one in the entry's notes, with the single line to delete for the other trade.
- **No report field, no schema change**, verified with the key diff rather than assumed.

## Broken Or Unverified

- Known defect: none introduced. `SighteAddons.RUN_END` still has no test of any kind.
- **Unverified, and it is this feature's ceiling: `RoomStats.start()` has never run inside a game.**
  The dev client cannot log in, so no real launch has fetched, cached and adopted. Every *piece* it
  calls is measured against the live box through the mod's own code, and the suite drives the whole
  of layer 1 against a loopback server — but the thread starting from `onInitializeClient`, and the
  fetch landing before the first room, are read rather than observed. What a real launch would show:
  a `room_scores_fetch` line then a `room_scores` line with `source "fetch"` in the session file, and
  `config/sighteaddons/roomstats.json` + `roomstats.etag` on disk.
- **Unverified — that the atomic rename is atomic.** Same as `runloss-001` records for the same
  shape: what is asserted is the observable half (a stale `.part` is consumed by the next successful
  write). The move itself is a property of the code. A probe replacing it with a direct write is
  caught only by that case.
- **The measured half of the scoring model is still inert, and fetching did not change that.** The
  served document has 105 rooms and `sampled 0` — every `clearStay` is `n = 0` — because no schema 5
  build has been **released**. Every score in it is exactly seed plus secrets. What this feature
  removes is the release from the loop for every *later* improvement.
- **Not built, and it is a decision rather than an omission: the receiver's `scores-002` notes ask
  the mod to "prefer the served `score` over the one it computes".** The document carries a `scores`
  block of resolved per-room scores beside the `rooms` array of raw averages; this feature reads the
  averages and keeps `clearpoints-002`'s model on the client, which is what the brief asked for.
  Moving the model to the server would make every client agree exactly and would also make a weight
  unexplainable from the jar. **Raise it with the user**; it is not recorded as a feature.
- **Unverified — the weights against a real run**, unchanged: that these numbers separate players in
  a way a player would agree with rides with `ingame-001`.
- **Unverified — whether the order heuristic is correct at all**, unchanged; the ceiling on the whole
  party domain.
- **Unverified — that `roster_skew` ever fires**, and **whether `MapDecoration.name()` carries
  anything** (`party-001`'s blocker, `deconame-001`'s subject). Unchanged. If the answer to the
  second is no, **`party-001` should be closed rather than carried**.
- **Unverified — the wiring of `positions()` itself**, unchanged.
- **Unverified — that Hypixel actually sends `chat-001`'s strings**, unchanged.
- **Unverified — the party half of everything else.** The one real run is solo and deathless, so
  `clear-001`'s zero-margin gap tolerance is still open and **the death path has never been exercised
  at all**.
- **Unverified — the `RED` checkmark path and every pixel of the `/sa` screen.** Unchanged.
- Unverified: the cross-repo reading that `unattributed` is only ever consumed as a ratio against
  `roomsCleared`. Unchanged.
- Regressions found: none. `./gradlew test --rerun-tasks` ran over everything and the addition is
  strictly additive.
- Risk for the next session: unchanged in kind — **the schema is 5 in source and 4 in every install**,
  and now eight features exist in source only. Nothing breaks meanwhile; nothing reaches a player
  either, until somebody bumps the version and takes the release gate, which is the user's decision.

## Next Best Step

- **First, a fresh evaluator pass on `scores-fetch-001`.** It claims `passing` on a startup network
  path that no real client has ever executed, so the question worth a second pair of eyes is whether
  the evidence carries the claim: is a loopback server plus a live-box probe through the mod's own
  code enough, when the thread that starts it has never started in a game? The argument made here is
  yes — every function is exercised, the failure table is nine real shapes, five probes measure that
  the tests can fail, and the unobserved half is named as unobserved rather than folded into the
  grade (scoring deliberately stayed B). If the evaluator disagrees, the alternative status is
  `in_progress` until somebody launches the mod. Re-run: the `verification_command`, the whole suite
  (expect 175 in 14), `assemble check` with the jar md5 either side, `python build/keydiff.py`, the
  `curl` contract checks, and all five mutation probes. **The evaluator must not be this session.**
- **Then the two open branches need the user's decision, not another feature.** `runloss-001` and
  `scores-fetch-001` are both off `d356ff2` and neither is merged. Merging is the user's call and the
  union resolution is described under "Verified Now".
- **If the user offers a run, the ask is unchanged and now buys one thing more: play a floor, then
  quit the game from inside it.** That verifies `runloss-001` end to end, and any launch at all now
  also verifies this feature's real path. **A party floor with a death in it is still the
  highest-value input overall** — it would move `party-001`, `deconame-001`, `clear-001`'s last open
  note, the rest of `ingame-001` and all three of `chat-001`'s unverified halves at once.
- **Then `runend-001`.** The only `not_started` entry that can be worked here (besides
  `deconame-001`, which needs a party floor), it is cheap, and its open question is already answered:
  write the run-level count, not the event count.
- **Do not start `chatfields-001`** by editing `RunReport.kt`. Its first move is a feature in
  `Sighte/skyblock-server`, which is a different repository and a different session.
- `records-001` is deferred by the user — a product decision, not a technical blocker.

## Do Not Touch

- **`RoomStats.adopt` must refuse to install once the session has resolved.** It looks like an
  over-cautious guard and it is the feature: a room's weight is read at the moment it is cleared and
  points are compared between party members, so a document that lands mid-run would make a run
  incomparable with itself and with the other four players'. It is already in the cache by then, so
  nothing is lost — the next launch resolves it. Measured: removing it fails 1.
- **`RoomStats.cachedEtag` must return null when the document is not on disk, and `store` must write
  the document before the tag.** Two halves of one guard. An `ETag` naming bytes that are not there
  earns a `304`, and a `304` with no cache behind it is a session on the seeds at *every* launch
  until the receiver's document happens to change. Measured: removing either fails 1.
- **The scores cache must reach disk through `.part` + rename.** Same reason and same shape as
  `RunReport`'s: a half-written cache survives the launch that produced it and is read as scores by
  the next one. The suite cannot see the rename itself — it sees that a stale `.part` is consumed —
  so a "simplification" back to `Files.writeString` will look harmless and will not be. `RoomStats`
  carries its own copy of `replace` **on purpose**, because `RunReport.kt` was under evaluation on
  another branch; unify them once both have landed, not before.
- **Only a body that parses may become the cache.** A proxy's HTML error page arrives with a `200`
  on it, and writing it would turn one bad minute on the box into a bad cache on every install that
  launched during it. Measured: accepting an unparseable `200` fails 1.
- **`RoomStatsTest`'s loopback server is not a network test and must not be turned into one.** It
  binds an ephemeral port on the loopback address and closes it with the test. Do not point any case
  in the suite at the real box — `init.sh` would then depend on the box being up.
- **`docs/evidence/session-1786719912927/` is evidence, not documentation.** Do not tidy the excerpt
  or extend it with lines from a different run. **`readout.sh` still asserts `run_end events 0` and
  must keep doing so.**
- **The rejected upgrade path in `PartyTracker.assign`'s KDoc is a measurement, not an opinion — do
  not delete it as stale and do not re-add the mixin plan.**
- **`assign`'s `trustOrder` guard**, and **`assign` must stay pure and stay `internal`**.
- **`ChatEvents`' patterns are cited, not invented — keep them that way.**
- **`SecretTracker.chatAttribution` returns `Boolean?` and the null is load-bearing.**
- **`ChatEvents.nearMiss` must not be widened to log every chat line.**
- **`unattributed` must stay a count of *rooms*.**
- **`RunReport.SCHEMA`, 5, must not move and must not go back down.**
- **The seed keys are `rooms.json`'s spelling** — `Ice Fill` and `Water Board`, two words each.
- **The metric is `clearStay` and only `clearStay`.**
- **Do not re-add a bundled snapshot of `roomstats.json` to the jar** — and do not commit the fetched
  document anywhere else either. It is 107 KB of the receiver's state, and a copy is that copy
  drifting.
- **`clearpoints-001`'s notes are history and are marked as such**, and so is the "layer 1 is not
  built" paragraph in `clearpoints-002`'s, which now carries a `SUPERSEDED` note rather than an edit.
- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`).
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate. The notes
  for the next release owe eight things now: the schema moved to 5, older installs are unaffected
  because the receiver still accepts 4, room points are no longer flat, room points changed meaning
  again so old and new standings are not comparable, that the mod now reads chat events (and that the
  strings behind them are sourced from other mods rather than observed), that a run ended by quitting
  from inside a floor used to be discarded and now is not — and that this path is unverified against
  a real client — that a hard kill still loses the run, and **that the mod now fetches its room
  weights from the analysis server at game start (a `GET`, no token, gated on the `/sa` upload
  switch, and it never blocks the game)**, which is the first outbound request this mod makes that is
  not an upload and therefore belongs on both the GitHub and the Modrinth notes.
- `dist/` by hand — and **`./gradlew build` is not a neutral verification command** while fixes sit
  unreleased. Use `./gradlew assemble check`. `README.md`'s "Build" section still says
  `./gradlew build`, correctly; do not follow it mid-feature and do not "fix" it.
- **Past session entries in `claude-progress.md`.** Supersede in a new entry; do not rewrite history.
- `SighteAddonServerside`. It was **read** this session and **not written**. A change needed there is
  a paired feature and a different session.
- `evaluator-rubric.md`'s structure. Harness file; changes need the user to ask.

## Environment Quirks

- **The receiver's `/roomstats` contract, measured 2026-08-14**: `200` with the document and an
  `ETag`, `304` on `If-None-Match`, `503` with body `no scores document` when there is nothing to
  serve, and `404` for **every** other path — the match is exact, so `/roomstats.json`,
  `/roomstats/`, `/roomstats?v=1` and a POST are all `404`. `HEAD` answers `501`, which is
  `BaseHTTPRequestHandler` answering for a verb with no `do_HEAD`; the mod never sends one. No token.
  Not compressed — gzip would be 18× smaller but Java's `HttpClient` does not decompress
  transparently, so it is the mod's half of a feature that does not exist. `SETUP.md` §10c in the
  receiver is the authority.
- **The document is refolded every half hour**, so its `ETag` changes on that cadence. Two fetches
  two hours apart this session returned different tags. If a `304` ever looks flaky, that is why —
  the suite's 304 case uses a loopback server precisely so it does not depend on the box.
- **`com.sun.net.httpserver.HttpServer` works in this test suite** (it is `jdk.httpserver`, resolved
  for classpath applications). Bind with `InetSocketAddress(InetAddress.getLoopbackAddress(), 0)` and
  read the port back off `server.address`. For a "nothing is listening" case, bind a `ServerSocket`
  on port 0, take its port and close it.
- **Gradle's user home on this machine is `C:\Users\marvi\scoop\persist\gradle\.gradle`, not
  `~/.gradle`**. **Do not run an unscoped `find /` on this machine**; it does not finish inside a
  tool timeout.
- **`javap` on the Fabric API jars answers "when does this event fire, and on which thread" and it is
  cheap.** Extract the module jar, then `javap -p -c -v -cp . <mixin class>`.
- **`git worktree add <path> <branch>` is refused when the main checkout already has that branch
  checked out.** An evaluator wanting an isolated copy at a specific commit wants
  **`git worktree add --detach <path> <sha>`**.
- **Restore a mutation probe with `git checkout <file>` — but COMMIT THE FEATURE FIRST.**
  `git checkout` restores from the index, so on an uncommitted working tree it reverts the mutation
  *and the entire feature edit in that file*. **Apply** a probe from Python with
  `io.open(..., newline='')` on both ends, which preserves CRLF (git is `core.autocrlf=true` here);
  `build/probe.py` does exactly that and holds all five of this session's probes.
- **`build/` is gitignored, so scripts written there do not survive a clean.** `build/keydiff.py`
  (the `RunReport` ↔ `ingest.py` key diff), `build/edit_features.py` (the byte-safe
  `feature_list.json` writer, which asserts its round trip before writing) and `build/probe.py` are
  all there. Re-create rather than hunt for them.
- **The Edit tool preserves CRLF in these `.md` files** — measured this session on
  `claude-progress.md` (`git diff --numstat` showed 9 changed lines, not the whole file). The warning
  about writing them back from Python still stands; it is about Python, not about surgical edits.
- **Reading the Minecraft classes is the way to settle a protocol or lifecycle question.** The merged
  jar this module compiles against is at
  `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.1.2/minecraft-merged-043a8b3edf-26.1.2.jar`.
- **`gh api repos/<owner>/<repo>/contents/<path> --jq .content | base64 -d` is the way to read a
  cited mod's source.** **`gh search code` is rate limited to 10 requests per minute.**
- **The real session file lives outside this repository** and only an excerpt is committed. The
  original is at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-1786719912927.jsonl`.
  Treat it as read-only.
- **`*.jsonl` is pinned to LF in `.gitattributes`.**
- **`feature_list.json` is CRLF with raw UTF-8 bytes.** Read it with `encoding='utf-8'` and write it
  back as `json.dumps(..., indent=2, ensure_ascii=False).replace('\n', '\r\n')` plus a trailing
  `\r\n`. **Assert the round trip before editing**; `build/edit_features.py` refuses to write if it
  fails. It held this session.
- **`quality-document.md` and `claude-progress.md` are CRLF too**, and the quality table rows are
  single lines of several hundred characters — the Edit tool handles them; a here-doc does not.
- **`RoomStats` reads a file from outside this repository**, so `ContributionTrackerTest` and
  `RoomDatabaseTest` pin `RoomStats.use(RoomScores.NONE)` in `@BeforeEach`. **Any new test that
  touches `weightOf` must do the same.** `RoomStatsTest` clears the resolution in `@BeforeEach` *and*
  `@AfterEach` since this session, because layer 1 will not adopt over an existing resolution and a
  leftover would silently turn a fetch case into a no-op.
- **`RunReport.reported` is process-wide state on an `object`** on the `runloss-001` branch, so
  `RunReportTest` there calls `RunReport.reset()` in `@BeforeEach`. The suite runs sequentially.
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
- **`net.minecraft.ChatFormatting` loads fine in a unit test.** But **`MapItemSavedData` and
  `MapDecoration` do not**, and neither does anything needing a live `Minecraft` — which is why
  `RoomStats.start()`'s thread body is the one part of this feature with no test, and why everything
  inside it is a function that has one.
- **`DungeonSession.runTicks` is `private set`** and only `tickClock()` moves it.
- **`DungeonSession.floor` is writable from a test by reflection**;
  **do not call `DungeonSession.reset()` to clean up** — it resets half the mod.
- **`DebugLog.event` is safe to call from a unit test**, and a consequence is that `./gradlew test`
  writes `config/sighteaddons/debug/session-<millis>.jsonl` into the working tree. `config/` is
  gitignored. `Config` initialises fine under `fabric-loader-junit`.
- **`ContributionTracker` is an `object` with run-long state**, so any test that writes to it must
  `reset()` first.
- The live box is reachable read-only over SSH (`ssh -i ~/.ssh/sighte_box -o IdentitiesOnly=yes
  root@217.160.51.229`). **It was not used this session** — everything needed came over HTTPS from
  the public endpoint, which is the interface the mod itself has.
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
- `scores-fetch-001`: `./gradlew test --tests 'sighteaddons.RoomStatsTest'`
- `party-001`: `./gradlew test --tests 'sighteaddons.PartyTrackerTest'`
- `chat-001`: `./gradlew test --tests 'sighteaddons.ChatEventsTest'
  --tests 'sighteaddons.SecretTrackerTest' --tests 'sighteaddons.ContributionTrackerTest'`
- Paired-feature key diff: `python build/keydiff.py` (re-create from its docstring after a clean)
- The endpoint this feature depends on:
  `curl -s -o /dev/null -w '%{http_code}\n' https://217.160.51.229.sslip.io/roomstats` → `200`, and
  the same with `-H 'If-None-Match: "<tag>"'` → `304`
- Read out the real run: `bash docs/evidence/session-1786719912927/readout.sh`
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
- Test counts: `build/test-results/test/*.xml`, not the console
