# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. **167 tests across 14 classes, 0 failures,
  0 skipped** — `main` at `9cb71ee` (which is `chat-001` merged) carries 160 in the same 14, and the
  difference is exactly `PartyTrackerTest` 7 → 14. No class was added. `mod_version=0.9.0`,
  `dist/sighteaddons-0.9.0.jar` unchanged (md5 `b2ebc35ccfeb9cc96134eb3b18f0306f`, measured before
  and after `assemble check`), `RunReport.SCHEMA` still 5, `RunReport.kt` not touched by this branch.
- Branch: `party-001`, off `main` at `9cb71ee`. **Not pushed and not merged.** For how many commits
  it carries, run `git rev-list --count 9cb71ee..HEAD` and `git log --oneline 9cb71ee..HEAD` for what
  each is. **Do not write the number into an artifact** — three consecutive reviews found a
  hand-transcribed count wrong, which is why it is derived rather than stated.
- What verification actually ran (exact commands), all at `80e5f57`:
  - `./gradlew test --tests 'sighteaddons.PartyTrackerTest'` → PASS, 14 tests, 0 failures. This is
    `party-001`'s `verification_command`, **unchanged in text and no longer vacuous** — see the
    mutation probes below for why the text did not need to change.
  - `./gradlew test --rerun-tasks` → `classes 14, tests 167, skipped 0, failures 0, errors 0`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 identical either side, and
    `git status --short dist/ gradle.properties` empty
  - `bash init.sh` → `BASELINE: PASSING` (run at session start, on `main` at `9cb71ee`)
  - **The paired-feature check, mechanically.** `python build/keydiff.py` — re-created this session,
    because `chat-001` recorded the description of the script and not the script. It parses every
    `addProperty` key out of `RunReport.build()`/`room()` and `RUN_KEYS`/`RUN_OPTIONAL`/`ROOM_KEYS`/
    `ROOM_OPTIONAL` out of `SighteAddonServerside/ingest.py`: **four empty sets, both directions**,
    and `playerTicks`, `playersInRoom`, `ownTicks`, `enterTick`, `unattributed` all present on both
    sides. `build/` is gitignored, so **the script does not survive a clean** — it is short and the
    docstring says what it does. Re-run it before any feature that touches `RunReport.kt`.
    `SighteAddonServerside` was **read and never written**.
  - **Three mutation probes, all caught, all restored with `git checkout`.** Dropping the
    `trustOrder` guard (`if (trustOrder) teammates.getOrNull(next++) else null` →
    `teammates.getOrNull(next++)`) fails 2 of 14. Appending `.also { next++ }` to `assign`'s frame
    branch — counting the local player's own marker in the teammate index, which is NoammAddons'
    actual defect — fails 4. Shadowing `localSlot` with `0` inside `assign` fails 1. Re-create all
    three from these descriptions.

## Changed This Session

`party-001` — and it ended `blocked` on a **finding**, not on a task. Read the finding before
planning anything in this area; it closes a door that three artifacts held open.

- **The entry's title named a mechanism the mod's design forbids, and is corrected in place** the way
  `clear-001`'s, `schema-001`'s and `chat-001`'s were. It was "Party sync instead of the
  decoration-order heuristic". `README.md:9`: "full per-player attribution **with no party sync**".
  `SighteAddons.kt:24`: "**No packets are sent and nothing is automated**".
- **THE FINDING: the only concrete upgrade path the codebase named does not exist.** The comment at
  the old `PartyTracker.kt:134-138` claimed NoammAddons reads the decoration's map key, whose last
  character is a digit identifying the player slot, and that a `MapItemSavedData` accessor mixin
  would remove the counting. Both halves are false:
  - `ClientboundMapItemDataPacket` carries `Optional<List<MapDecoration>>` — an **unkeyed list**. No
    key crosses the wire. `MapItemSavedData.addClientSideDecorations` clears the private map and
    re-keys every entry `"icon-" + i` from its own loop index (that class's string-concat bootstrap
    constants are literally `icon-` and `frame-`); the field is a `LinkedHashMap`, so
    `getDecorations()` is already in packet order. The mixin would return this client's own list
    order spelled as a string, and past nine decorations the "last character" is not even the index.
  - NoammAddons does not do what the comment said. `DungeonUtils.kt`:
    `val index = key[key.lastIndex].digitToInt()` used as an index into `livingTeammates` — the
    identical order heuristic, plus a defect this mod does not have, since the digit counts the local
    player's own marker while the list it indexes excludes them.
  The mixin was therefore **not built**. The finding is recorded at the site, in the entry's
  `blocked_reason`, in the progress log and in `quality-document.md`.
- **The described failure mode was the harmless one, and both are now asserted rather than
  described.** Decorations are one per player; two players never share one. Two players in one *room*
  produce two decorations a few pixels apart that both resolve to the same `Pos` cell, so a swap
  changes nothing — pinned, along with the fact that a third teammate one room over *is* a different
  cell, so the equality is a property of sharing a room and not of the grid being too coarse. The
  damaging failure is the **count mismatch across different rooms**, which `trustOrder` blacks out,
  and which is now pinned in both directions (a marker too few and a marker too many).
  `quality-document.md`'s party row repeated the loose phrasing and is fixed.
- **What landed: the testability seam.** `PartyTracker.assign(roster, localSlot, isFrame) ->
  Assignment` is pure and `internal`; `positions()` keeps the Minecraft plumbing, the grid math and
  the logging. Behaviour is unchanged. Stated as the design decision it is, because `positions()`
  takes a `MapItemSavedData` and reads `DungeonSession` statics — so the heuristic had **zero**
  coverage and could not get any — while `ContributionTracker.tick` iterates it and only ever creates
  a room some decoration resolved into. Room discovery is inside the blast radius.
- **The `verification_command` was vacuously green and is now not.** It named `PartyTrackerTest`,
  which already passed with cases covering only the tab regex and the living-class carry. The text is
  **deliberately unchanged**; what changed is that the class now holds 7 cases that exercise the
  assignment and three probes that measure the command can fail. Padding it with class names would
  have hidden the problem.
- **New feature recorded rather than built: `deconame-001`** — log whether `MapDecoration.name()` is
  populated, the one identity channel that survives the wire. It carries the design question that
  makes it a feature: a populated name is probably the teammate's IGN, and `Pseudonym` exists so the
  debug log never carries one — so it has to be redacted, and a redacted value cannot tell you what
  the name *was*. Decide that before writing the field.
- **Grade: party stays C.** The tests pin the guard and the failure classification where they pinned
  nothing, and three mutation probes back that — but they still pin *the heuristic rather than the
  truth*, which is the document's own stated reason for C. The row is rewritten; the letter is not.

## Broken Or Unverified

- Known defect: none introduced. The pre-existing ones are unchanged — `runloss-001` (below) and
  `SighteAddons.RUN_END`, which still has no test of any kind.
- **Unverified, and this is now the ceiling on the whole party domain: whether the order heuristic is
  correct at all.** `assign` is pinned against its own model of a dungeon. That Hypixel emits
  decorations in tab order has never been observed — the one real run was **solo**, so a single
  decoration could not be mis-ordered. `party-001`'s `verification_manual` is a procedure for
  whoever plays a party floor: which event answers which question.
- **Unverified — that `roster_skew` ever fires.** It fired **zero** times on the one committed run.
  The blackout window's real duration (documented as 10-20 ticks) is an estimate from other mods.
- **Unverified — whether `MapDecoration.name()` carries anything.** This is `party-001`'s blocker and
  `deconame-001`'s whole subject. If the answer is no, **`party-001` should be closed rather than
  carried**: there is no client-side channel left, and party sync is not a fallback — it is forbidden
  by the design.
- **Unverified — the wiring of `positions()` itself**, unchanged: that `map.decorations` on a real
  Hypixel dungeon map contains exactly one `FRAME` and one marker per living teammate is read from
  other mods, not observed here.
- **Unverified — that Hypixel actually sends `chat-001`'s strings**, and the ordering question that
  decides whether its secret half works. Unchanged; see `chat-001`'s entry.
- **Unverified — the party half of everything else.** The one real run is solo and deathless, so
  `clear-001`'s zero-margin gap tolerance is still open and **the death path has never been exercised
  at all**, by either source. A party floor with a death in it now moves five things at once.
- **`runloss-001` is a measured, permanent data loss and it is unfixed.** Unchanged. Quitting the game
  straight from a dungeon writes no run report, because `RunReport.write` is reachable only from the
  end-of-run chat headline and from `ClientPlayConnectionEvents.JOIN` (`SighteAddons.kt`) and that
  exit produces neither. It cost ten cleared rooms on 2026-08-14. `history.jsonl` kept its 14 lines.
- **Unverified — the `RED` checkmark path and every pixel of the `/sa` screen.** Unchanged.
- **Unverified — the measured half of the scoring model, entirely.** Unchanged: every room is on its
  seed until something serves the averages (`scores-fetch-001`).
- Unverified: the cross-repo reading that `unattributed` is only ever consumed as a ratio against
  `roomsCleared`. Unchanged.
- Regressions found: none. The one that mattered was room discovery, since `ContributionTracker.tick`
  iterates `positions()`; `ContributionTrackerTest`'s 48 cases are unchanged and green.
- Risk for the next session: unchanged — **the schema is 5 in source and 4 in every install**, and
  six features now exist in source only. Nothing breaks meanwhile; nothing reaches a player either,
  until somebody bumps the version and takes the release gate, which is the user's decision.

## Next Best Step

- **First, a fresh evaluator pass — on `party-001` specifically, and on one judgement.** Nothing on
  this branch is `passing`, so the usual "did it earn `passing`" question does not apply. The
  question worth a second pair of eyes is the opposite one: **is `blocked` the honest status for a
  feature whose blocker is that the thing it was going to build does not exist?** The argument made
  here is that `blocked` is right because one testable question remains (`MapDecoration.name()`, via
  `deconame-001`) and closing the entry before that is answered would discard it. If the evaluator
  disagrees, the alternative is to close `party-001` outright and let `deconame-001` stand alone.
  Re-run: the `verification_command`, the whole suite (expect 167 in 14), `assemble check` with the
  jar md5 either side, `python build/keydiff.py`, and all three mutation probes.
  **The evaluator must not be this session.**
- **Then `runloss-001`, and it is now the strongest candidate on the list by number as well as by
  value.** `records-001` is deferred by the user, `party-001` is blocked, `ingame-001` needs a party
  floor. `runloss-001` is the only entry known to have destroyed real data, needs no receiver change,
  and needs no real dungeon to verify the write path. Read its notes first — the fix is *when*
  `RunReport.write` is called, and whether the player is still resolvable at `DISCONNECT` is
  something to **measure**, not assume.
- `runend-001` remains cheap: its open question is answered — write the run-level count, not the
  event count.
- **Do not start `chatfields-001`** by editing `RunReport.kt`. Its first move is a feature in
  `Sighte/skyblock-server`, which is a different repository and a different session.
- **Do not start `scores-fetch-001`.** Still blocked on the receiver serving `roomstats.json`.
- **Do not start `deconame-001` unless somebody is about to play a party floor.** It proves nothing
  on its own and it cannot be verified here.
- **If the user offers another run, ask for a party floor with a death in it.** That one file would
  now move `party-001`, `deconame-001`, `clear-001`'s last open note, the rest of `ingame-001` **and
  all three of `chat-001`'s unverified halves** at once. It is the single highest-value input this
  repository can receive and it costs the user one dungeon.

## Do Not Touch

- **The rejected upgrade path in `PartyTracker.assign`'s KDoc is a measurement, not an opinion — do
  not delete it as stale and do not re-add the mixin plan.** It records what the packet, the client
  class and NoammAddons's source actually do, with the constants named so it can be rechecked in one
  `javap`. Its whole purpose is that the next session does not spend a day building the mixin. If a
  future Minecraft version changes the wire format, correct it against a fresh `javap` and say which
  version — do not soften it into "may not work".
- **`assign`'s `trustOrder` guard.** Removing it does not break a test in an obvious place; it
  silently credits rooms to players who were never in them, in exactly the 10-20 ticks around a
  death when `ContributionTracker.onDeath` is charging one. Measured: removing it fails 2 of 14.
- **`assign` must stay pure and stay `internal`.** The moment it reads `DungeonSession` or takes a
  `MapItemSavedData` it becomes untestable again, which is the state `party-001` found it in.
- **`ChatEvents`' patterns are cited, not invented — keep them that way.** Every shape names the
  published mod it came from. If one turns out wrong on a real floor, correct it *and* move the
  citation to what the session file actually contained; do not quietly widen a regex until it
  matches, and do not add a shape with no source.
- **`SecretTracker.chatAttribution` returns `Boolean?` and the null is load-bearing.** Null means
  chat said nothing, which is the case for every secret that is not a wither essence. Collapsing it
  to `false` un-credits every chest, lever and item secret in the game. Measured: fails 3 of 8.
- **`ChatEvents.nearMiss` must not be widened to log every chat line.** Chat is the one stream
  carrying strangers' conversation, and a diagnostic that logged it to find a regex bug would be a
  worse defect than the bug.
- **`docs/evidence/session-1786719912927/` is evidence, not documentation.** Do not tidy the excerpt,
  re-sort it, or extend it with lines from a different run. `readout.sh` enforces it.
- **`unattributed` must stay a count of *rooms*.** Unchanged, and still the silent failure this
  project spent three sessions removing.
- **`RunReport.SCHEMA`, 5, must not move and must not go back down.**
- **The seed keys are `rooms.json`'s spelling** — `Ice Fill` and `Water Board`, two words each.
- **The metric is `clearStay` and only `clearStay`.**
- **Do not re-add a bundled snapshot of `roomstats.json` to the jar.**
- **`clearpoints-001`'s notes are history and are marked as such.**
- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`).
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate. The notes
  for the next release owe six things: the schema moved to 5, older installs are unaffected because
  the receiver still accepts 4, room points are no longer flat, room points changed meaning again so
  old and new standings are not comparable, that the mod now reads chat events (and that the strings
  behind them are sourced from other mods rather than observed), and — if `runloss-001` lands first —
  that runs ended by quitting from inside a floor used to be discarded. `party-001` adds nothing to
  that list: it changed no behaviour.
- `dist/` by hand — and **`./gradlew build` is not a neutral verification command** while fixes sit
  unreleased. Use `./gradlew assemble check`. `README.md`'s "Build" section still says
  `./gradlew build`, correctly; do not follow it mid-feature and do not "fix" it.
- **Past session entries in `claude-progress.md`.** Supersede in a new entry; do not rewrite history.
- `SighteAddonServerside`. It was **read** this session (`ingest.py`, for the key diff) and **not
  written**. A change needed there is a paired feature and a different session.
- `evaluator-rubric.md`'s structure. Harness file; changes need the user to ask.

## Environment Quirks

- **`git worktree add <path> <branch>` is refused when the main checkout already has that branch
  checked out** (`fatal: '<branch>' is already used by worktree at ...`). An evaluator wanting an
  isolated copy at a specific commit wants **`git worktree add --detach <path> <sha>`**. Two
  evaluators have now had to be told this by hand; it belongs here.
- **Restore a mutation probe with `git checkout <file>` — but COMMIT THE FEATURE FIRST.**
  `git checkout` restores from the index, so on an uncommitted working tree it reverts the mutation
  *and the entire feature edit in that file*, silently and with a cheerful `Updated 1 path from the
  index`. That happened this session and cost a re-edit. Never restore by writing the file back from
  Python either: git is `core.autocrlf=true` here, so the working copy is CRLF, and Python reading
  with universal newlines and writing with `newline=''` converts the file to LF and leaves it looking
  modified.
- **`build/` is gitignored, so scripts written there do not survive a clean.** `build/keydiff.py`
  (the `RunReport` ↔ `ingest.py` key diff) and `build/edit_features.py` (the byte-safe
  `feature_list.json` writer) are both there. Both are short; their docstrings say what they do and
  the handoff and evidence describe their output. Re-create rather than hunt for them.
- **Reading the Minecraft classes is the way to settle a protocol question, and it is cheap.** The
  merged jar this module compiles against is at
  `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.1.2/minecraft-merged-043a8b3edf-26.1.2.jar`
  and `javap -p -c -v -cp <jar> <class>` answers questions that prose in a comment cannot. That is how
  `party-001`'s finding was established. `javap -v` also prints `BootstrapMethods`, which is where
  string-concat recipes like `icon-\u0001` live — an `invokedynamic makeConcatWithConstants` in the
  disassembly is otherwise opaque.
- **`gh api repos/<owner>/<repo>/contents/<path> --jq .content | base64 -d` is the way to read a
  cited mod's source**, and it is on the ordinary 5000/hour limit. **`gh search code` is rate limited
  to 10 requests per minute** and returns HTTP 403 rather than an empty result when exceeded — which
  reads exactly like "no matches". `gh api rate_limit --jq .resources.code_search` says when it
  resets. `gh api repos/<owner>/<repo>/git/trees/HEAD?recursive=1 --jq '.tree[].path'` finds the file
  when you do not know the path.
- **The real session file lives outside this repository** and only an excerpt is committed. The
  original is at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-1786719912927.jsonl`
  on this machine. Treat it as read-only: it is the user's game directory, not a working tree.
- **`*.jsonl` is pinned to LF in `.gitattributes`** so committed evidence cannot change bytes
  depending on whose machine checked it out.
- **`feature_list.json` is CRLF with raw UTF-8 bytes.** Read it with `encoding='utf-8'` (Windows
  Python defaults to cp1252 and will show em dashes as mojibake that is not in the file), and write
  it back as `json.dumps(..., indent=2, ensure_ascii=False).replace('\n', '\r\n')` plus a trailing
  `\r\n`. That round-trips the file byte for byte — **assert it before editing** rather than trusting
  this line; `build/edit_features.py` does exactly that and refuses to write if it fails.
- **`RoomStats` reads a file from outside this repository**, so `ContributionTrackerTest` and
  `RoomDatabaseTest` pin `RoomStats.use(RoomScores.NONE)` in `@BeforeEach`. **Any new test that
  touches `weightOf` must do the same.** `PartyTrackerTest` does not touch it.
- **Windows Python cannot execute `./gradlew`** — `WinError 193`. Drive Gradle from bash.
- **Windows Python resolves `/tmp/x` as `C:\tmp\x`**, Git Bash resolves it elsewhere. Use `/c/tmp/...`
  from bash for anything the two share, or keep it in `build/`.
- **A long here-doc through the Bash tool is fragile.** A ~180-line `python - <<'PY'` failed with an
  unrelated shell parse error in session 010; writing the script to `build/` with the Write tool and
  running `python build/<name>.py` is the reliable path for anything non-trivial. Short here-docs
  are fine and were used throughout this session.
- **Test results are easiest to read from XML, not from Gradle's console output.** `./gradlew test`
  prints `BUILD SUCCESSFUL` and no counts; `build/test-results/test/*.xml` carries
  `tests`/`failures`/`errors`/`skipped` per class and the `<testcase>` names of the failures. That is
  how every count in this handoff was obtained.
- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes. Warm it is ~5-8 s and it was warm throughout this session.
- **`net.minecraft.ChatFormatting` loads fine in a unit test** — `ChatEventsTest` calls
  `stripFormatting` directly. But **`MapItemSavedData` and `MapDecoration` do not**, which is the
  whole reason `PartyTracker.assign` takes `List<Boolean>` rather than decorations.
- **`DungeonSession.runTicks` is `private set`** and only `tickClock()` moves it, so a function that
  reads the clock can only ever be tested at tick zero. New tick-dependent logic must take the tick
  as a parameter — `onPresence`, `onSecret`, `onDeath` and `onChatSecret` all do.
- **`DungeonSession.floor` is writable from a test by reflection**;
  **do not call `DungeonSession.reset()` to clean up** — it resets half the mod.
- **`DebugLog.event` is safe to call from a unit test**, and a consequence is that `./gradlew test`
  writes `config/sighteaddons/debug/session-<millis>.jsonl` into the working tree. `config/` is
  gitignored.
- **`ContributionTracker` is an `object` with run-long state**, so any test that writes to it must
  `reset()` first. `ContributionTrackerTest` does it in `@BeforeEach`; the suite runs sequentially.
  `PartyTracker.assign` is pure and needs none of this, which is the point of it.
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
- `party-001`: `./gradlew test --tests 'sighteaddons.PartyTrackerTest'`
- `chat-001`: `./gradlew test --tests 'sighteaddons.ChatEventsTest'
  --tests 'sighteaddons.SecretTrackerTest' --tests 'sighteaddons.ContributionTrackerTest'`
- Paired-feature key diff: `python build/keydiff.py` (re-create from its docstring after a clean)
- Read out the real run: `bash docs/evidence/session-1786719912927/readout.sh`
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
- Test counts: `build/test-results/test/*.xml`, not the console
