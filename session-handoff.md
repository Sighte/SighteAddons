# Session Handoff

Current state only, **<= 120 lines** — the ceiling in `CLAUDE.md`, raised there on 2026-08-16 in
`0c1e4a5` after this header had claimed 150 and then 80. Amend the sections that changed; do not
rewrite the file from scratch. Standing facts — toolchain, quoting traps, probe scripts, the code
invariants a tidy-up would break — are in `ENVIRONMENT.md`. Past sessions are in
`claude-progress.md` and, beyond that, in `git log`.

**Branch state: 0.15.0 is released and `main` is `b5ad8e1`. No branch is open.** It carries
`critcalc-001`, `stormtimer-001` and `secretpoints-001` on top of 0.14.0's secret readout and idle
counters. `v0.15.0` is tagged and published, and Modrinth version `YNlbBvlI` carries the identical
jar — `sha1 de94c224…`, 260722 bytes, compared against the local file rather than assumed.
`RunReport.SCHEMA` is 6 and unchanged since 0.14.0, so the receiver is owed nothing. The leftover
mutation probe once uncommitted in `RoomHistory.kt:449` is stashed, not lost: `git stash list`.

**The Modrinth upload failed once on the way and the repair is written into `CLAUDE.md`:**
`version_title` is capped at 64 characters, 0.15.0's was 65, and the 400 landed *after* the GitHub
release was published. `gh release edit --title` plus a workflow re-run fixed it without touching the
tag, the jar or the notes.

**The release does not reach players and the reason is not in this repository.** The Modrinth
project answers 404 to anyone not logged in — still awaiting review — so the build is downloadable
by direct CDN link and by nothing else. Publishing the project is the user's step; the measurement
is in `claude-progress.md`.

## Verified Now

- **`main` carries 0.14.0 plus the crit readout; `mod_version` is 0.14.0.** The release, its jar hashes and the
  Modrinth state are in `claude-progress.md`'s Current Verified State and are not repeated here.
- **The suite is 277 across 20 classes on `main` (`0c1e4a5`), 286 across 21 on `stormtimer-001`**,
  0 failures and 0 skipped either way. Counts come from `build/test-results/test/*.xml`, not the
  console.
- **`RunReport.SCHEMA` is 6 on `main` and 5 in every install, and the pair is closed.**
  `skyblock-server`'s `master` is `1a7f435`, deployed and verified on the box 2026-08-16: it accepts
  run-level `idleTicks` and `navTicks` as **optional** keys, so v5 reports in backlogs still
  validate and the v6 this branch produces is already understood. `python build/keydiff.py` is CLEAN
  at 6, and `roomstats.py` routes `enterTick` on `v >= 5`, so 6 keeps its `clearStay` bucket.
  **Nothing is owed to the receiver in either direction and that repository was only read.**
- **`secrethud-001` is display only and was verified as such** — `SecretHud.line` is pure over
  tracker state, swept by three mutations of `build/secrethudprobe.py`, all CAUGHT. `SecretTracker`
  now carries one added line (`secretpoints-001`) and `ownsecrets-001` still stays unclaimed:
  nothing in that diff changes what attribution decides, only what a decided secret is worth.

## Changed This Session

**`stormtimer-001`, created and implemented on branch `stormtimer-001` off `0c1e4a5` (`dedc98b`).**
The second half of the `CC0-1.0` crit-mod port `critcalc-001` started. No version bump, `dist/`,
`gradle.properties` and **`RunReport.kt` untouched**; `python build/keydiff.py` CLEAN at 6, so no
receiver work is owed in either direction. `secretpoints-001`'s detail survives at
`git show 0c1e4a5:session-handoff.md`.

- **`StormTimer.kt` keeps one number and nothing else** — `client.level.gameTime` at the trigger, in
  a `@Volatile Long?` — and `readout(elapsed, countdown, shoot)` is a *total pure function* over the
  ticks since. The source mod's five mutable fields and its `ClientTickEvents` callback did **not**
  come across, and that is not tidying: `SighteAddons.onTick` returns early when the map is
  unreadable and Storm is a boss phase, so a ticked countdown could quietly not run in the only place
  it is ever used.
- **`StormHud.kt` adds no second `HudElementRegistry` registration** — it draws from the existing
  `renderHud`, before its `calibrated` gate for the reason `onCrit` is not behind one.
- **138 and 20 are `/sa` → hud rows, not constants** (click steps a tick, shift-click back). The
  source's position and scale settings did **not** come across: centring is what they approximated,
  and a coordinate with one right answer is a worse setting than none.
- Nine cases in `StormTimerTest`; `CritMeter.normalize` reused, not repeated; `DungeonSession.reset`
  clears it. No test deleted or weakened, no probes, no sweep, no grading pass. Rationale for every
  choice above is in `dedc98b`'s message and the KDoc at each site rather than repeated here.

## Broken Or Unverified

- **`stormtimer-001`: TWO SEPARATE CEILINGS, and only one of them can ever announce itself.**
  (1) **The strings.** `grep -ril storm` over `docs/evidence/` and the twenty session logs finds
  nothing — same answer `critcalc-001` got, and for the same reason: no build ever looked. (2) **138
  and 20**, the source mod's undocumented constants. Unlike a wrong string a wrong tick count is
  invisible — the timer counts down, turns red and fires, just at the wrong moment — which is why
  both are `/sa` rows and why nothing presents the countdown as authoritative. Verified by the suite:
  both triggers start it and a pasted line does not, the state after N ticks, that `SHOOT NOW`
  begins on the expiry tick and ends one tick after the hold, four distinct colours at three seconds
  and one, that both tick counts actually drive it, and that `DungeonSession.reset()` shuts it
  (the test drives `reset()` itself, so that one line is real wiring coverage). **Not** verified:
  that Fabric delivers Storm's line to `onChat` with `overlay` false, that `client.level.gameTime`
  advances during the boss, that `renderHud` is reached in a boss phase at all, or that either
  `/sa` row survives a restart.
- **Two debug events, two different faults, and the distinction is the point.** `storm_unparsed`
  present with no `storm_start` ⇒ the strings are wrong, and the event quotes what Storm actually
  says. **Both** absent in a run where Storm spoke ⇒ the line never reached `onChat` — a delivery or
  stripping fault that no amount of correcting strings would fix.
  `./gradlew runClient` was **not** run: unlike `critcalc-001` this adds no mixin and no
  `HudElementRegistry` registration, and the dev client has no world in which a HUD would draw.
- **`secretpoints-001` (now on `main`): the arithmetic is verified, the wiring never ran in a game.**
  Not verified: that `onActionBar` → `SecretTracker.onActionBar(text, self, x, z)` delivers the name
  Hypixel's roster keys on, that the local player's standings row exists at all (it needs
  `PartyTracker.roster()` to contain them — pre-existing, true of clear points too), or that the HUD
  repaints between the find and the next clear. One floor settles all three: open a chest, watch
  your own row rise 0.25 before the checkmark.
- **The under-count is inherited, not introduced.** A secret walked over is attributed to nobody, so
  it now costs 0.25 as well as a line on the HUD. That is `ownsecrets-001`, still `not_started` and
  still unclaimed; this feature spends attribution and does not touch it.
- **`critcalc-001`: EVERY STRING IN IT IS A HYPOTHESIS AND NOTHING ON DISK CONFIRMS ONE** — the same
  two ceilings as `stormtimer-001` above, and its `verification_manual` carries the detail. Its
  `TIME_WORTH = 2.5` is the invisible-when-wrong one: it produces a plausible quotient, and is the
  single line to change if the user says the number is off. **Zero `crit_unparsed` *and* zero
  readouts in an M7 with crits** is its failure signal — pattern matches nothing, or the window
  never opened.
- **The tab-footer mixin was checked to *apply*, not to return anything useful.** One `runClient`
  reached a full resource reload, exit 0, zero mixin errors, which rules out a startup crash
  (`required: true`). **The compile does not catch a wrong `@Accessor` name** — renaming it to
  `footerXYZ` still compiles clean, so `javap` on the merged jar is the only check there is.
- **What must not be added back, for BOTH halves of the port:** the source mod's `ApiSender`
  (POSTed name/crit/power/ratio to a third party on every hit, no toggle) and its automatic `/msg`
  + party lines. Neither is present in any form behind any flag; the crit readout is
  `addClientSystemMessage` only and the storm timer draws to the screen only.
- **`idletime-001` and `secrethud-001`'s wiring (both on `main`) have never run in a game.** The pure
  halves are verified; `onTick` → `IdleTime.tick` with the right room, `renderHud`, `currentRoom`,
  the `/sa` rows and `showIdle`/`showSecrets` surviving a restart are not — removing either
  `IdleTime` wiring line passes the whole suite, by declaration. One floor settles it by eye. The
  definitional ambiguity stands: a *discarded* secret run is not active, so a cleared room whose run
  was abandoned counts as **idle**; changing that is `SETUP.md` section 4 first, never this side
  alone.
- **The sharpest standing risk, unchanged by anything this session did.** `recordowner-001`'s
  secret-run gate needs a **trusted `0/N`** action bar on entering a room; if Hypixel does not send
  one for a room the mod identified, secret records do not get rare, they **stop**. Hypixel is known
  to send `0/N` (`session-1786567867893.jsonl` line 85, `t=137`) but only on a room whose bar max
  disagreed with the database, so the trusted path is unproven. `secret_room_first_bar` ships in
  0.12.0 for exactly this; falsified by it never appearing, or always carrying `untouched: false`
  for rooms entered clean. Neither of that feature's gates has ever run in a game — the predicates
  are swept, the four wiring lines are not.
- **The strict gate keeps roughly one record in seven, intended, the user's reaffirmed decision.**
  The weakness under it is **`ownsecrets-001`, `not_started`**: a secret is yours only via a
  right-click inside `SecretTracker.OWN_WINDOW` (40 ticks, `SecretTracker.kt:42`) or a
  wither-essence chat line, so one walked over sinks the room's run — and it is the same gap that
  makes `Your secrets` under-count, which is specification and not a defect. Its first task needs no
  dungeon: `attributedBy` and `own_interaction` in the **twenty** logs on disk answer it.
- **Open and recorded, not fixed: `floorname-001`.** The receiver `fullmatch`es `floor` against
  `?|E|[FM][1-7]` (`ingest.py:93`, used at `:225`) and `DungeonSession.floor` can hold `Entrance`; a
  400 is never retried, and `keydiff.py` compares key *sets* so it will never catch this.
- **Pre-existing and out of scope, carried forward:** the bogus bests in `history.jsonl` are not
  repairable (the user was told and accepted it); a `topPlayer` tie resolves in hash order;
  `runTicks` is read on the DISCONNECT path and is not `@Volatile` (`IdleTime`'s two counters are);
  `SighteAddons.RUN_END` has no test of any kind.
- **`RoomStats.start()` has still never run inside a game** (`scores-fetch-001`'s ceiling), and every
  earlier unverified item carries over unchanged: the atomic rename; the weights against a real run;
  whether the order heuristic is correct; whether `roster_skew` ever fires and whether
  `MapDecoration.name()` carries anything (`deconame-001` — **if not, close `party-001` rather than
  carry it**); the wiring of `positions()`; that Hypixel sends `chat-001`'s strings; the `RED`
  checkmark path and every pixel of `/sa`; the three write paths of `floorloss-001`; and that
  `unattributed` is only ever consumed as a ratio against `roomsCleared`.
- **`build/runprobes.sh` is still not crash-safe** — it restores on the success path rather than in a
  `trap`, so an interruption leaves a deliberate defect in the tree, which happened once. **Evaluator
  follow-up 1, still open.** `build/evalsweep.sh` and `build/idlesweep.sh` are the worked fixes, and
  `build/` is gitignored so all three are one `git clean` from gone.
- Regressions found: **none.** The suite on the branch is 21 classes / 286 tests / 0 failures / 0
  skipped, from 20 / 277 on `main`; every previously passing feature's class is in that run and none
  moved.

## Next Best Step

- **Decide `stormtimer-001`: it is the only branch open, so a second feature here breaks
  `single_active_feature`.** No schema change, no deploy path, nothing in `RunReport.kt`, and it
  adds no mixin — the lightest merge on the board.
- **READ A REAL SESSION LOG FROM 0.12.0 OR LATER — the cheapest and highest-value input on the
  board, and unlike a week ago it can actually exist.** One floor settles `recordowner-001`'s entire
  remaining ceiling: whether `secret_room_first_bar` appears at all, whether it ever carries
  `untouched: true`, and whether the four wiring lines do what the call graph says. Logs are at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-*.jsonl`;
  one whose events include `secret_room_first_bar` is 0.12.0 or later.
- **Then `ownsecrets-001`** — the measured cause of the record loss; its first task needs no dungeon
  (`python build/ownsecrets.py` replays the decision against the logs on disk). Then `floorname-001`,
  the cheapest entry on the board and already argued in its entry (map `Entrance` → `E` here, no
  receiver change, no pairing), then `runend-001` (write the run-level count, not the event count).
- **Follow-up 1 (make `runprobes.sh` crash-safe) before the next mutation sweep**, not before the
  next feature. `build/idlesweep.sh` is the newest worked example of the `trap` form.
- **Do not start `chatfields-001`** by editing `RunReport.kt` — its first move is a feature in
  `Sighte/skyblock-server`. `records-001` is deferred by the user, a product decision.
- **Fourteen features exist in source only** — `stormtimer-001` joined them. Nothing breaks
  meanwhile, but **a released build is the only thing that can produce a `crit_unparsed` or a
  `storm_unparsed`**, which are the only evidence that will ever exist for either half of the port's
  strings. The whole `LBRelease/` half of the decompiled mod has now been read and ported; nothing
  is left in it.

**This file is 186 lines against a 120-line ceiling, from 174 with a feature added — net +12, and
that is an honest failure to hold the line rather than a reason.** Cut here to pay for most of it:
`secretpoints-001`'s implementation detail (`git show 0c1e4a5:session-handoff.md`) and
`critcalc-001`'s four bullets folded into one, since its detail now lives in its own
`verification_manual`. What is left over the ceiling is other features' standing briefs —
`recordowner-001`'s trusted-`0/N` risk, `scores-fetch-001`'s carried list, and now
`stormtimer-001`'s two ceilings — which `CLAUDE.md` says an open feature keeps in full. **The real
prune wants those features closed, not another session trimming around them**, and five of them are
settled by one played floor, which is still the cheapest thing anybody can do for this repository.

