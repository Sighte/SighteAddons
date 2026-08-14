# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. **160 tests across 14 classes, 0 failures,
  0 skipped** — `main` at `a6dc629` carries 140 in 13, and the difference is exactly what `chat-001`
  added: the new `ChatEventsTest` (11), `SecretTrackerTest` 5 → 8, `ContributionTrackerTest` 42 → 48.
  `mod_version=0.9.0`, `dist/sighteaddons-0.9.0.jar` unchanged (md5
  `b2ebc35ccfeb9cc96134eb3b18f0306f`, measured before and after `assemble check`),
  `RunReport.SCHEMA` still 5, `RunReport.kt` not touched by this branch.
- Branch: `chat-001`, off `main` at `a6dc629`. **Not pushed and not merged.** For how many commits it
  carries, run `git rev-list --count a6dc629..HEAD` and `git log --oneline a6dc629..HEAD` for what
  each is. **Do not write the number into an artifact** — three consecutive reviews found a
  hand-transcribed count wrong, which is why it is derived rather than stated.
- What verification actually ran (exact commands), all at `382c2c2`:
  - `./gradlew test --tests 'sighteaddons.ChatEventsTest' --tests 'sighteaddons.SecretTrackerTest'
    --tests 'sighteaddons.ContributionTrackerTest'` → PASS, 11 / 8 / 48, 0 failures. This is
    `chat-001`'s `verification_command`, **and it changed this session** — the entry named only
    `ChatEventsTest`, which would have run a third of the feature's own tests. Recorded in the entry
    rather than quietly swapped, the way `ingame-001`'s was corrected in session 009.
  - `./gradlew test --rerun-tasks` → `classes 14, tests 160, skipped 0, failures 0, errors 0`
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 identical either side, and
    `git status --short dist/ gradle.properties` empty
  - `bash init.sh` → `BASELINE: PASSING`
  - **The paired-feature check, mechanically.** A script extracting every `addProperty` key from
    `RunReport.build()` and `RunReport.room()` and diffing it against `RUN_KEYS`/`RUN_OPTIONAL` and
    `ROOM_KEYS`/`ROOM_OPTIONAL` parsed straight out of `SighteAddonServerside/ingest.py`: **four
    empty sets, both directions.** The exact script is in `chat-001`'s evidence; re-run it before any
    feature that touches `RunReport.kt`. `SighteAddonServerside` was **read and never written**.
  - **Two mutation probes, both caught, both restored with `git checkout`** (never by writing the
    file back from Python — see Environment Quirks). Deleting the `^` from `ChatEvents.LEAD` *and*
    switching `parse` from `matchEntire` to `find` fails 2 of 11. Making
    `SecretTracker.chatAttribution` return `false` instead of `null` when no chat line landed fails
    3 of 8. Re-create both from the descriptions in `chat-001`'s evidence.
  - **One thing the first probe measured that is worth keeping:** removing the `^` *alone* fails
    nothing, and switching to `find` *alone* fails nothing — either mechanism anchors on its own.
    So the redundancy is real rather than one guard sitting on a dead one. Recorded in the comment
    on `ChatEvents.parse`.

## Changed This Session

`chat-001` — the mod reads the dungeon events Hypixel states in chat. One listener already existed
and threw away everything but the run-end headline; it now feeds a pure `String -> Event?` parser in
the same seam `SecretTracker.parseSecrets` and `PartyTracker.TAB` use.

- **The entry's `user_visible_behavior` was factually wrong and is corrected in place**, the way
  `clear-001`'s and `schema-001`'s were. It promised four things and three were misdescribed:
  **deaths were already read**, not inferred (the tab row flip; what was wrong was the *timing*, and
  the room, which is still inferred and still is); **wither doors and puzzle solvers did not exist at
  all** — `grep -inE "wither|door|solver"` over `src/main/` returned one hit, a skull id. Only secret
  clicks were a real inference. Honest scope: one inference narrowed, one timing improved, two
  capabilities added.
- **Deaths: two sources, one charge.** Chat states a death on the tick it happens;
  `PartyTracker.update` polls once a second on top of the tab row's own lag. The tab path is **kept**
  — a death message this client never received still shows up in tab — so `ContributionTracker.onDeath`
  became idempotent instead, keyed per player, 60-tick window. **`onRevive` clears the entry**, which
  is what makes the window a guard rather than a bet on revive speed.
- **Secrets: chat overrules the 40-tick coincidence, for exactly one kind of secret.** Hypixel names
  a finder for the **wither essence** and for nothing else — chests, levers, item pickups and the
  redstone key are announced nowhere. So `SecretTracker.isOwn` stays and still attributes almost
  every secret. What chat buys is the false positive: a teammate taking the essence while your own
  click on a chest is still inside its window used to credit you.
- **Doors and puzzles reach the debug log and nothing else, deliberately.** Every run-report field is
  one the receiver already knows; a key it has not learned is a `400` that `TelemetryUpload` files
  under `rejected/` and never retries. Debug events are free — `/ingest` validates the filename and
  never the body, and **nothing server-side reads event types at all**, which was checked rather than
  assumed.
- **New feature recorded rather than built: `chatfields-001`** — blocked, the report side of the
  above, receiver first. It carries the design question that makes it a feature and not a commit: a
  per-player secret breakdown would put **teammate names** in a permanent server-side record, which
  `RunReport`'s header refuses on purpose, so it has to be per-slot or per-class.
- **`verification_manual` now declares that it cannot be performed here** and is a procedure rather
  than two lines: which debug event answers which open question. That was the condition the entry was
  silently missing.
- **Grade moved: events (chat) D → C.** Not to B, and the reason is the ceiling `quality-document.md`
  already states — the patterns are cited to published mods rather than observed.

## Broken Or Unverified

- Known defect: none introduced. Two **found and not fixed**, both pre-existing — `runloss-001` (see
  below) and `SighteAddons.RUN_END`, which still has no test of any kind.
- **Unverified — that Hypixel actually sends these strings.** Every pattern in `ChatEvents` is cited
  to a published mod that runs against the live server (`SkyHanni`'s `DungeonChatFilter`,
  `Cowlection`'s `DungeonsListener`, `UnclaimedBloom6/IllegalMap`, `inglettronald/DulkirMod`,
  `odtheking/OdinLegacy`) — the standard `SECRET_SKULLS` is already held to, and not the same as
  having seen one. **A wrong pattern fails silently and benignly**: it matches nothing and the mod
  infers exactly as it did before. `ChatEvents.nearMiss` writes the offending line, redacted through
  `Pseudonym.row`, as `chat_unparsed`.
- **Unverified — the wiring of `chat-001`, entirely.** That Fabric hands
  `SighteAddons.onDungeonEvent` a given line, with `overlay` false, on the tick Hypixel sent it, is
  unobservable here — the same condition `PartyTracker.positions` and `ContributionTracker.tick` are
  under.
- **Unverified, and this one decides whether half the feature works: the ORDERING.** A `chat_secret`
  must arrive **before** the action-bar update it attributes. If it arrives second, the attribution is
  always too late and `CHAT_WINDOW` cannot fix it. One real floor answers it: read `attributedBy` on
  each `secret` event.
- **`runloss-001` is a measured, permanent data loss and it is unfixed.** Unchanged. Quitting the game
  straight from a dungeon writes no run report, because `RunReport.write` is reachable only from the
  end-of-run chat headline and from `ClientPlayConnectionEvents.JOIN` (`SighteAddons.kt`) and that
  exit produces neither. It cost ten cleared rooms on 2026-08-14. `history.jsonl` kept its 14 lines.
- **Unverified — the party half of everything, and `chat-001` did not change that.** The one real run
  is solo and deathless, so `party-001` is untouched, `clear-001`'s zero-margin gap tolerance is
  still open, and — new this session — **the death path has never been exercised at all**, by either
  source. A party floor with a death in it now moves four things at once.
- **Unverified — the `RED` checkmark path and every pixel of the `/sa` screen.** Unchanged.
- **Unverified — the measured half of the scoring model, entirely.** Unchanged: every room is on its
  seed until something serves the averages (`scores-fetch-001`).
- Unverified: the cross-repo reading that `unattributed` is only ever consumed as a ratio against
  `roomsCleared`. Unchanged.
- Regressions found: none.
- Risk for the next session: unchanged — **the schema is 5 in source and 4 in every install**, and
  six features now exist in source only. Nothing breaks meanwhile; nothing reaches a player either,
  until somebody bumps the version and takes the release gate, which is the user's decision.

## Next Best Step

- **First, a fresh evaluator pass on `chat-001`.** It is the only `passing` entry this branch adds and
  it has never been evaluated. Re-run: its `verification_command` (the three-class one — the entry's
  old single-class command is not it), the whole suite (expect 160 in 14), `assemble check` with the
  jar md5 either side, the mechanical `RunReport`↔`ingest.py` key diff, and both mutation probes.
  **The evaluator must not be this session.** Judge one thing specifically: whether `passing` is the
  right status for a feature whose *strings* are unverified — the argument made here is that this is
  the same condition every other `passing` feature's wiring is under, and that a `blocked` would
  misstate it, since nothing is waiting on anything. That judgement is the one most worth a second
  pair of eyes.
- **Then `runloss-001`, and it is still the strongest candidate on the list.** Unchanged from session
  009: the only entry known to have destroyed real data, no receiver change, no real dungeon needed
  to verify the write path. Read its notes first — the fix is *when* `RunReport.write` is called, and
  whether the player is still resolvable at `DISCONNECT` is something to **measure**, not assume.
- `runend-001` remains cheap: its open question is answered — write the run-level count, not the
  event count.
- **Do not start `chatfields-001`** by editing `RunReport.kt`. Its first move is a feature in
  `Sighte/skyblock-server`, which is a different repository and a different session.
- **Do not start `scores-fetch-001`.** Still blocked on the receiver serving `roomstats.json`.
- **If the user offers another run, ask for a party floor with a death in it.** That one file would
  now move `party-001`, `clear-001`'s last open note, the rest of `ingame-001` **and all three of
  `chat-001`'s unverified halves** at once. It is the single highest-value input this repository can
  receive and it costs the user one dungeon.

## Do Not Touch

- **`ChatEvents`' patterns are cited, not invented — keep them that way.** Every shape names the
  published mod it came from. If one turns out wrong on a real floor, correct it *and* move the
  citation to what the session file actually contained; do not quietly widen a regex until it
  matches, and do not add a shape with no source. A pattern nobody can trace is a pattern the next
  session cannot judge.
- **`SecretTracker.chatAttribution` returns `Boolean?` and the null is load-bearing.** Null means
  chat said nothing, which is the case for every secret that is not a wither essence. Collapsing it
  to `false` un-credits every chest, lever and item secret in the game — `ownSecrets` goes to zero on
  every run with no exception and no log line. Measured: that change fails 3 of 8 in
  `SecretTrackerTest`.
- **`ChatEvents.nearMiss` must not be widened to log every chat line.** It reports only lines opening
  with one of five markers a player cannot type, because Hypixel prefixes anything a player says.
  Chat is the one stream carrying strangers' conversation, and a diagnostic that logged it to find a
  regex bug would be a worse defect than the bug. Wither doors, essence secrets and the blood door
  therefore get no near-miss reporting; that is the accepted cost and it is pinned by a test.
- **`docs/evidence/session-1786719912927/` is evidence, not documentation.** Unchanged: do not tidy
  the excerpt, re-sort it, or extend it with lines from a different run. `readout.sh` enforces it.
- **`unattributed` must stay a count of *rooms*.** Unchanged, and still the silent failure this
  project spent three sessions removing.
- **`RunReport.SCHEMA`, 5, must not move and must not go back down.**
- **The seed keys are `rooms.json`'s spelling** — `Ice Fill` and `Water Board`, two words each.
- **The metric is `clearStay` and only `clearStay`.**
- **Do not re-add a bundled snapshot of `roomstats.json` to the jar.**
- **`clearpoints-001`'s notes are history and are marked as such.**
- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`).
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate. The notes
  for the next release now owe six things: the schema moved to 5, older installs are unaffected
  because the receiver still accepts 4, room points are no longer flat, room points changed meaning
  again so old and new standings are not comparable, that the mod now reads chat events (and that the
  strings behind them are sourced from other mods rather than observed), and — if `runloss-001` lands
  first — that runs ended by quitting from inside a floor used to be discarded.
- `dist/` by hand — and **`./gradlew build` is not a neutral verification command** while fixes sit
  unreleased. Use `./gradlew assemble check`. `README.md`'s "Build" section still says
  `./gradlew build`, correctly; do not follow it mid-feature and do not "fix" it.
- **Past session entries in `claude-progress.md`.** Supersede in a new entry; do not rewrite history.
- `SighteAddonServerside`. It was **read** this session (`ingest.py`, for the key diff and to confirm
  `/ingest` never validates a debug body) and **not written**. A change needed there is a paired
  feature and a different session.
- `evaluator-rubric.md`'s structure. Harness file; changes need the user to ask.

## Environment Quirks

- **`gh search code` is rate limited to 10 requests per minute** and returns an HTTP 403 rather than
  an empty result when you exceed it — which reads exactly like "no matches" if you pipe it through
  `head`. `gh api rate_limit --jq .resources.code_search` says when it resets. `gh api
  repos/<owner>/<repo>/contents/<path>` is on the ordinary 5000/hour limit and is the better tool
  when you know which file you want: that is how `ChatEvents`' line shapes were sourced.
- **The real session file lives outside this repository** and only an excerpt is committed. The
  original is at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-1786719912927.jsonl`
  on this machine. Treat it as read-only: it is the user's game directory, not a working tree.
- **`*.jsonl` is pinned to LF in `.gitattributes`** so committed evidence cannot change bytes
  depending on whose machine checked it out.
- **`feature_list.json` is CRLF with raw UTF-8 bytes.** Read it with `encoding='utf-8'` (Windows
  Python defaults to cp1252 and will show em dashes as mojibake that is not in the file), and write
  it back as `json.dumps(..., indent=2, ensure_ascii=False).replace('\n', '\r\n')` plus a trailing
  `\r\n`. That round-trips the file byte for byte — verified this session before editing it, which is
  worth doing again rather than trusting this line.
- **`RoomStats` reads a file from outside this repository**, so `ContributionTrackerTest` and
  `RoomDatabaseTest` pin `RoomStats.use(RoomScores.NONE)` in `@BeforeEach`. **Any new test that
  touches `weightOf` must do the same.**
- **Restore a mutated file with `git checkout`, not by writing it back from Python.** Git is
  `core.autocrlf=true` here, so the working copy is CRLF; Python reading with universal newlines and
  writing with `newline=''` converts the file to LF and leaves it looking modified.
- **Windows Python cannot execute `./gradlew`** — `WinError 193`. Drive Gradle from bash.
- **Windows Python resolves `/tmp/x` as `C:\tmp\x`**, Git Bash resolves it elsewhere. Use `/c/tmp/...`
  from bash for anything the two share, or keep it in `build/`.
- **A long here-doc through the Bash tool is fragile.** A ~180-line `python - <<'PY'` failed with an
  unrelated shell parse error this session; writing the script to `build/` with the Write tool and
  running `python build/<name>.py` is the reliable path for anything non-trivial.
- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes. Warm it is ~10 s and it was warm throughout this session.
- **`net.minecraft.ChatFormatting` loads fine in a unit test** — `ChatEventsTest` calls
  `stripFormatting` directly, which is what lets the tests carry the raw `§`-coded strings.
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
- `chat-001`: `./gradlew test --tests 'sighteaddons.ChatEventsTest'
  --tests 'sighteaddons.SecretTrackerTest' --tests 'sighteaddons.ContributionTrackerTest'`
- Read out the real run: `bash docs/evidence/session-1786719912927/readout.sh`
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
