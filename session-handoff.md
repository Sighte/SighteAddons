# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- **Branch `recordowner-001`, off `main` at `8431597`. Not pushed and not merged.** For how many
  commits it carries, run `git rev-list --count 8431597..HEAD` and `git log --oneline 8431597..HEAD`
  for what each is. **Do not write the number into an artifact** — hand-transcribed counts have been
  found wrong three times, which is why it is derived.
- **The suite is 211 across 15 classes, 0 failures, 0 skipped.** The branch point `8431597` carries
  193 in the same 15; the difference is `SecretRunTest` 6 → 11, `RoomHistoryTest` 5 → 12,
  `ContributionTrackerTest` 48 → 54. No class was added or removed, nothing renamed or weakened.
- `mod_version` is **0.11.0** and `dist/sighteaddons-0.11.0.jar` is untouched — md5
  `e8cd7099034dd3475dbc8069be3c433e`, measured before and after `assemble check`.
  `RunReport.SCHEMA` still 5, and **`RunReport.kt` is not touched by this branch at all**.
- What verification actually ran (exact commands), all at `9a00325` unless stated:
  - `./gradlew test --tests 'sighteaddons.SecretRunTest' --tests 'sighteaddons.RoomHistoryTest'
    --tests 'sighteaddons.ContributionTrackerTest' --rerun-tasks` → PASS. 11 / 12 / 54, 0 failures.
    This is `recordowner-001`'s `verification_command`.
  - `./gradlew test --rerun-tasks` → `classes 15, tests 211, skipped 0, failures 0, errors 0`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 identical either side, and
    `git status --short dist/ gradle.properties` empty
  - `bash init.sh` → `BASELINE: PASSING` (at session start on `8431597`, 193 tests, and again at the
    end on `9a00325`, 211)
  - **The paired-feature check, mechanically.** `python build/keydiff.py` → `KEYDIFF: CLEAN`, four
    empty sets both directions, 17 run keys and 17 room keys, `SCHEMA` 5. `SighteAddonServerside`
    was **read** (`ingest.py`) and **never written**.
  - **Ten mutation probes, all caught**, all restored with `git checkout` after the feature was
    committed. Re-create from `build/recordprobe.py`, drive all ten with `bash build/runprobes.sh`.
    A (drop `|| firstBarFound != 0`, the defect verbatim) fails 3. B (`firstBarFound == null`, the
    fix done wrong) fails 2. C (observe the bar after the rise test) fails 5. D (first reading
    overwritten) fails 2. E (`ownSecrets > 0`) fails 1. F (drop `secretsFound > 0`) fails 1.
    G (drop `presentFromStart`) fails 2. H (drop `self != topPlayer`) fails 1. I (drop the staleness
    half) fails 1. J (null anchor answers `true`) fails 1.

## Changed This Session

`recordowner-001` — **a record is only yours when the work was yours.** The feature did not exist in
`feature_list.json`; it was added by this session at priority 18 out of two defects the user reported
in German, both quoted verbatim in the entry. A third of the same shape was found while reading them.

- **A secret run is recorded only when every secret in it was yours.** `RoomHistory.ownSecretRun` is
  `ownSecrets == secretsFound`, with a `secretsFound > 0` guard so `0 == 0` is not a vacuous yes.
- **A clear is recorded only when you were there from the start AND had the most ticks.**
  `RoomHistory.ownClear` = "you are `topPlayer`" **and** `TrackedRoom.presentFromStart`. Both halves,
  neither implying the other.
- **A secret run may only start from a room that was untouched when we arrived.**
  `TrackedRoom.firstBarFound` is the first *trusted* bar reading. The old guard read `previous`,
  i.e. `room.secretsFound`, which is this client's observation and 0 for every room until a bar has
  been read — so a room already at 3/10 read as `previous = 0, found = 3` and started a run there.
- **`TrackedRoom.readBar` folds three statements into one function on purpose.** See "Do Not Touch".
- **`history.jsonl` is not touched and no metric is redefined.** A `clear` still carries
  `room.ticks[self]`, a `secretrun` still carries `room.secretRunTicks`. The gates decide *whether a
  line is written*, never what the number in it means, which is what keeps old and new lines
  comparable inside one kind. Records already in the file stay records — the user's decision.
- **`ClearPopup.show` follows the same gate at both call sites**, so its KDoc's promise is true again.
- **No report field, no schema change**, verified with the key diff rather than assumed.

## Broken Or Unverified

- **Unverified, and it is this feature's ceiling: neither gate has run in a game.** The predicates
  are driven directly and ten probes measure that they can fail. What no command here can observe is
  **the wiring** — that `onRoomCleared` calls `ownClear` with the `topPlayer` it just computed and
  the local player's name, that `onSecretRun` calls `ownSecretRun` before `record`, that
  `ClearPopup.show` fires under the same answer, and that `SecretTracker.onActionBar` calls `readBar`
  at all. Those four lines need a live `Minecraft`. **What a real party floor would show:** a room a
  teammate opens first → `secret_room_first_bar` with `untouched=false`, a `secret_run_discarded`
  carrying `firstBar>0`, and **no** `secretrun` line in `history.jsonl` for it; a room walked into as
  the checkmark lands → no `clear` line and no popup; a room done alone from the start → both, as
  before. **What would falsify it:** `secret_room_first_bar` never appearing, or always carrying
  `untouched=false` even for rooms entered clean — either would mean the strict gate has closed the
  door on every record rather than on the wrong ones.
- **A tie in `topPlayer` is arbitrary and was left that way.** `maxByOrNull` over a `HashMap`, so two
  members on exactly the same tick count resolve in hash order and the record follows the chat line
  wherever that lands. Pre-existing, not made worse, and pinning it is a decision the user has not
  been asked for.
- **The bogus bests already in `history.jsonl` are not repairable and deliberately not repaired.**
  Rooms whose record was set by walking into somebody else's work will never report a PB again. The
  user was told and accepted it.
- **`runTicks` is read on the DISCONNECT path and is not volatile.** Pre-existing, out of scope,
  carried over from the previous session unchanged.
- **Open and recorded, not fixed: `floorname-001`.** The receiver validates `floor` as
  `?|E|[FM][1-7]` under `fullmatch` (`ingest.py:93`, used at `:225`) and `DungeonSession.floor` can
  hold `Entrance`. A 400 is never retried. **Note that `build/keydiff.py` compares key *sets* and
  cannot see a value-domain mismatch.**
- Known defect otherwise: none introduced. `SighteAddons.RUN_END` still has no test of any kind.
- **`RoomStats.start()` has still never run inside a game** (`scores-fetch-001`'s ceiling), and every
  earlier unverified item carries over unchanged: the atomic rename is atomic; the weights against a
  real run; whether the order heuristic is correct at all; whether `roster_skew` ever fires and
  whether `MapDecoration.name()` carries anything (`deconame-001` — **if the answer is no,
  `party-001` should be closed rather than carried**); the wiring of `positions()`; that Hypixel
  actually sends `chat-001`'s strings; the party half of everything (the one real run is solo and
  deathless, so `clear-001`'s zero-margin gap tolerance is open and the death path has never been
  exercised); the `RED` checkmark path and every pixel of the `/sa` screen; the three write paths of
  `floorloss-001`; and the cross-repo reading that `unattributed` is only ever consumed as a ratio
  against `roomsCleared`.
- Regressions found: none. `./gradlew test --rerun-tasks` ran over everything. `git diff main`
  deletes zero lines from `ContributionTrackerTest` and `RoomHistoryTest`; in `SecretRunTest` the only
  deletion is the one-line `room()` helper, which now calls `readBar(0)` — the room walked into
  clean, which is what all six existing cases already meant, and their assertions are unchanged.
- Risk for the next session: **ten features exist in source only.** 0.11.0 is released and carries
  none of `recordowner-001`. Nothing breaks meanwhile; nothing reaches a player either.

## Next Best Step

- **First, a fresh evaluator pass on `recordowner-001`.** It claims `passing` on two gates no real
  client has executed. The argument is that the decisions and the defects are both entirely on the
  testable side of the seam — pure predicates on `TrackedRoom` and `RoomHistory`, ten probes
  measuring that the cases can fail, and the unobserved wiring named as unobserved rather than folded
  into the grade (history and records deliberately stayed B, and its row now says the earlier B was
  over-generous). Re-run: the `verification_command`, the whole suite (expect 211 in 15),
  `assemble check` with the jar md5 either side, `python build/keydiff.py`, and
  `bash build/runprobes.sh`. **The evaluator must not be the session that implemented it.**
- **Then the branch needs the user's decision, not another feature.** `recordowner-001` is off
  `8431597` and is not merged.
- **If the user offers a run, the ask has grown and it is now the highest-value input on the board by
  a wide margin: a PARTY floor with at least one death, in which somebody else opens a secret in a
  room before you do, and in which you walk into one room as its checkmark lands.** That single floor
  would move `recordowner-001`'s whole unobserved half, `party-001`, `deconame-001`, `clear-001`'s
  last open note, the rest of `ingame-001` and all three of `chat-001`'s unverified halves at once.
  A solo floor still verifies `floorloss-001`'s three write paths and `runloss-001` end to end if it
  is quit from inside.
- **`floorname-001` is the cheapest entry on the board** and its decision is already argued in the
  entry: map `Entrance` → `E` on this side, which needs no receiver change and no pairing.
- **Then `runend-001`.** Cheap, and its open question is already answered: write the run-level count,
  not the event count.
- **Do not start `chatfields-001`** by editing `RunReport.kt`. Its first move is a feature in
  `Sighte/skyblock-server`, which is a different repository and a different session.
- `records-001` is deferred by the user — a product decision, not a technical blocker.

## Do Not Touch

- **`TrackedRoom.readBar` must observe the bar BEFORE it tests for a rise.** This is the sharpest
  thing on the branch. A `0/10` reading is not a rise, and it is the only reading that can ever say
  the room was untouched when we walked in. Reorder it — which is exactly what a tidy-up back to
  "test for a rise first, then note it" looks like — and the first reading on record becomes the
  `1/10` that follows, no room ever looks clean, and **every secret run in the game is silently
  discarded**, which is strictly worse than the defect this fixed. The three statements are one
  function precisely so the ordering is testable; **do not split them back out to the call site**,
  where `SecretTracker.onActionBar` needs a live client and nothing can guard it. Measured: probe C
  fails 5, three of them pre-existing.
- **`TrackedRoom.observeBar` must stay private and must stay once-only.** `readBar` is the only way
  in. Measured: probe D.
- **`RoomHistory.ownClear` needs both halves.** `self == topPlayer` and `presentFromStart`. Neither
  implies the other and each was probed separately (H and G). The fixture for the top-player half
  asserts `presentFromStart` is **true for both members** before asserting the gate refuses — that
  assertion is what stops the case degrading into a test of the other half, which is exactly what it
  did on the first commit, where probe H passed all 211 tests.
- **`RoomHistory.ownSecretRun` keeps its `secretsFound > 0` guard.** `0 == 0` is true and must never
  be the answer. Measured: probe F.
- **`TrackedRoom.presentFromStart`'s staleness check uses the same tolerance `onPresence` continues a
  stay with.** A second, quieter notion of "still here" is how two guards start disagreeing.
  Measured: probe I.
- **A null `enteredAtTick` means no record, for everybody.** Measured: probe J.
- **Neither metric may be redefined.** A `clear` line is `room.ticks[self]`; a `secretrun` line is
  `room.secretRunTicks`. `history.jsonl` is append-only and old lines are still folded, so changing
  what a number means makes lines of one kind incomparable with each other — the exact failure the
  `secrets` → `secretrun` rename was invented to avoid. Gate whether a line is written; never what is
  in it.
- **`TrackedRoom.stays` is private and stays private.** `presentFromStart` takes `at` rather than
  reading the clock, the `onPresence` precedent, and that is what makes it drivable from a test.
- **`DungeonSession.observeSidebar` must keep answering about the present.** `return seen != null`,
  not `return floor != null`. That predicate gates the whole session state machine.
- **Only `DungeonSession.reset()` may clear the floor**, and it must stay *after* `RunReport.write`
  at the JOIN site.
- **`DungeonSession.floor` must stay `@Volatile`.** `DISCONNECT` reads it from a Netty thread.
- **`RunReport.reportedFloor()` must not ask the client for anything.**
- **`RoomStats.adopt` must refuse to install once the session has resolved.**
- **`RoomStats.cachedEtag` must return null when the document is not on disk, and `store` must write
  the document before the tag.**
- **The scores cache must reach disk through `.part` + rename.** `RoomStats` carries its own copy of
  `replace` **on purpose**; unify them once both branches have landed, not before.
- **Only a body that parses may become the cache.**
- **`RoomStatsTest`'s loopback server is not a network test and must not be turned into one.**
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
- **Do not re-add a bundled snapshot of `roomstats.json` to the jar.**
- **`clearpoints-001`'s notes are history and are marked as such**, and so is the "layer 1 is not
  built" paragraph in `clearpoints-002`'s.
- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`).
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate. **The notes
  for the next release owe one more thing now**, on top of everything `floorloss-001` and
  `scores-fetch-001` listed: that room records are now only written when the work was yours, that
  party secret records will become rare as a direct consequence, that this is a client-side change
  needing no receiver work, and that records already in `history.jsonl` are not repaired.
- `dist/` by hand — and **`./gradlew build` is not a neutral verification command** while fixes sit
  unreleased. Use `./gradlew assemble check`. `README.md`'s "Build" section still says
  `./gradlew build`, correctly; do not follow it mid-feature and do not "fix" it.
- **Past session entries in `claude-progress.md`.** Supersede in a new entry; do not rewrite history.
  The "Current Verified State" section opens with a correction block for exactly this reason, and
  this session added to it rather than editing what was under it.
- `SighteAddonServerside`. It was **read** this session and **not written**.
- `evaluator-rubric.md`'s structure, and its content — a fresh evaluator fills it in, not the
  implementer. This session did not touch it.

## Environment Quirks

- **`build/` is gitignored, so scripts written there do not survive a clean.** This session's are
  `build/recordprobe.py` (the ten mutation probes), `build/runprobes.sh` (drives all ten and reports
  which tests each fails), `build/keydiff.py` (the `RunReport` ↔ `ingest.py` key diff, re-created
  this session), `build/edit_features_record.py` (a byte-safe `feature_list.json` writer that asserts
  its round trip before writing), `build/quality.py` and `build/progress.py`. Re-create rather than
  hunt for them.
- **`build/keydiff.py` must anchor on `obj.add` / `obj.addProperty`, not on either alone.**
  `addProperty` alone misses `rooms` and `classes`, which are `JsonArray`s added with `add`; a bare
  `.add(` then matches `classes.add("${it.livingClass} …")`, which is an array *element*, not a
  field. Both mistakes were made this session and both were caught only because the previous session
  wrote down 17 run keys and 17 room keys. It reads `SighteAddonServerside/ingest.py` and writes
  nothing.
- **A multi-line anchor in a Python replace over a Kotlin source must use `\r\n`.** Git is
  `core.autocrlf=true`, so the working tree is CRLF while a Python string literal is not, and the
  anchor silently matches zero times — a probe that cannot apply looks exactly like a probe that
  passed. `build/recordprobe.py` translates the anchors itself; copy that.
- **`encoding="utf-8"` as well as `newline=""`** for any Python that reads these sources. They
  contain em dashes and Python on this machine defaults to cp1252.
- **`feature_list.json` is CRLF with raw UTF-8 bytes.** Read it with `encoding='utf-8'` and write it
  back as `json.dumps(..., indent=2, ensure_ascii=False).replace('\n', '\r\n')` plus a trailing
  `\r\n`. **Assert the round trip before editing.** It held again this session.
- **`quality-document.md` and `claude-progress.md` are CRLF too**, and the quality table rows are
  single lines of several hundred characters. The Edit tool preserves CRLF in them; a Python string
  built from a heredoc does not — normalise with `s.replace("\r\n","\n").replace("\n","\r\n")`.
- **The quality table is split by a blank line into a fresh block and a superseded one.** Rows
  updated by a session are copied into the top block; the older copies stay below. `telemetry` and
  `scoring` appear twice for that reason, and the top one is the current one.
- **`DungeonSession.floor` is still writable from a test by reflection** (`getDeclaredField("floor")`,
  `isAccessible = true`) and `ContributionTrackerTest` and `DungeonSessionTest` both do it. **Do not
  call `DungeonSession.reset()` to clean up** — it resets half the mod.
- **`DungeonSession.observeSidebar(List<String>)` is the seam** for anything about the floor.
- **`DungeonSession.runTicks` is `private set`** and only `tickClock()` moves it.
- **`RunReport.reported` is process-wide state on an `object`**, so `RunReportTest` calls
  `RunReport.reset()` in `@BeforeEach`. The suite runs sequentially.
- **`RoomStats` reads a file from outside this repository**, so `ContributionTrackerTest` and
  `RoomDatabaseTest` pin `RoomStats.use(RoomScores.NONE)` in `@BeforeEach`. **Any new test that
  touches `weightOf` must do the same.** `RoomHistoryTest`'s new cases do not touch `weightOf` and so
  do not need it.
- **`RoomHistory.ownClear` and `ownSecretRun` are drivable from a unit test; `onRoomCleared` and
  `onSecretRun` are not** — they reach `Minecraft.getInstance()` for the local player's name. Same
  shape as `RunReport.write`. That is why the gates are separate predicates.
- **The receiver's `/roomstats` contract, measured 2026-08-14**: `200` with the document and an
  `ETag`, `304` on `If-None-Match`, `503` with body `no scores document` when there is nothing to
  serve, `404` for **every** other path (exact match), `501` for `HEAD`. No token. Not compressed.
  **The document is refolded every half hour**, so its `ETag` changes on that cadence.
- **`com.sun.net.httpserver.HttpServer` works in this test suite.**
- **Gradle's user home on this machine is `C:\Users\marvi\scoop\persist\gradle\.gradle`**, not
  `~/.gradle`. **Do not run an unscoped `find /` on this machine.**
- **`javap` on the Fabric API jars answers "when does this event fire, and on which thread".**
- **`git worktree add <path> <branch>` is refused when the main checkout already has that branch
  checked out.** An evaluator wanting an isolated copy wants `git worktree add --detach <path> <sha>`.
  Note there is already a detached worktree at `../rel-wt` from the 0.11.0 release session.
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
  `build/test-results/test/*.xml` carries `tests`/`failures`/`errors`/`skipped` per class. That is how
  every count here was obtained.
- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes. Warm it is ~7-10 s and it was warm throughout this session.
- **`net.minecraft.ChatFormatting` loads fine in a unit test.** **`MapItemSavedData` and
  `MapDecoration` do not**, and neither does anything needing a live `Minecraft`.
- **`DebugLog.event` is safe to call from a unit test**, and a consequence is that `./gradlew test`
  writes `config/sighteaddons/debug/session-<millis>.jsonl` into the working tree. `config/` is
  gitignored. `Config` initialises fine under `fabric-loader-junit`.
- **`ContributionTracker` is an `object` with run-long state**, so any test that writes to it must
  `reset()` first.
- The live box is reachable read-only over SSH (`ssh -i ~/.ssh/sighte_box -o IdentitiesOnly=yes
  root@217.160.51.229`). **It was not used this session** — nothing in this feature leaves the client.
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
- `recordowner-001`: `./gradlew test --tests 'sighteaddons.SecretRunTest'
  --tests 'sighteaddons.RoomHistoryTest' --tests 'sighteaddons.ContributionTrackerTest'`
- Mutation probes for this feature: `bash build/runprobes.sh` (or
  `python build/recordprobe.py <A..J>`, then `./gradlew test --rerun-tasks`, then
  `git checkout src/main/kotlin/sighteaddons/ContributionTracker.kt
  src/main/kotlin/sighteaddons/RoomHistory.kt`)
- `floorloss-001`: `./gradlew test --tests 'sighteaddons.DungeonSessionTest'
  --tests 'sighteaddons.RunReportTest'`
- `scores-fetch-001`: `./gradlew test --tests 'sighteaddons.RoomStatsTest'`
- `party-001`: `./gradlew test --tests 'sighteaddons.PartyTrackerTest'`
- `chat-001`: `./gradlew test --tests 'sighteaddons.ChatEventsTest'
  --tests 'sighteaddons.SecretTrackerTest' --tests 'sighteaddons.ContributionTrackerTest'`
- Paired-feature key diff: `python build/keydiff.py` (re-create from its docstring after a clean)
- Read out the real run: `bash docs/evidence/session-1786719912927/readout.sh`
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
- Test counts: `build/test-results/test/*.xml`, not the console
