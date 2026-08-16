# Session Handoff

Current state only, **<= 80 lines** — the ceiling in `CLAUDE.md`, which this header claimed as 150
until 2026-08-16. Amend the sections that changed; do not rewrite the file from scratch. Standing
facts — toolchain, quoting traps, probe scripts, the code invariants a tidy-up would break — are in
`ENVIRONMENT.md`. Past sessions are in `claude-progress.md` and, beyond that, in `git log`.

**Branch state:** **0.13.0 is released** — PR #50 merged the two secret-count features, `v0.13.0`
is tagged and published on GitHub, and Modrinth version `wS3OCLGl` carries the identical jar.
**`idletime-001` is merged: `main` is now `3643004`** and carries `RunReport.SCHEMA` 6.
**`critcalc-001` is open at `d271ae3`, `passing`, not pushed and not merged.** It pulled no release
gate: `gradle.properties`, `dist/` and `RunReport.kt` are all absent from `3643004..critcalc-001`.
**A SECOND SESSION IS LIVE IN THIS REPOSITORY** — worktree `../rel-wt`, on `main`, which it moved to
`64a0f8e` "Bump to 0.14.0" (`mod_version` 0.14.0 + a refreshed `dist/` jar) mid-session. That is
not `critcalc-001`'s doing and the two do not conflict, but **0.14.0 was cut from `3643004` and does
NOT contain the crit readout** — its release notes must not claim it, and no `crit_unparsed` can
appear until a later build ships. The leftover mutation probe once uncommitted in
`RoomHistory.kt:449` is stashed, not lost: `git stash list`.

**The release does not reach players and the reason is not in this repository.** The Modrinth
project answers 404 to anyone not logged in — still awaiting review — so the build is downloadable
by direct CDN link and by nothing else. Publishing the project is the user's step; the measurement
is in `claude-progress.md`.

## Verified Now

- **`main` is at `3643004` and `mod_version` is 0.13.0.** The release, its jar hashes and the
  Modrinth state are in `claude-progress.md`'s Current Verified State and are not repeated here.
- **The suite is 265 across 19 classes on `main`, 276 across 20 on `critcalc-001`**, 0 failures and
  0 skipped either way. Counts come from `build/test-results/test/*.xml`, not the console.
- **`RunReport.SCHEMA` is 6 on `main` and 5 in every install, and the pair is closed.**
  `skyblock-server`'s `master` is `1a7f435`, deployed and verified on the box 2026-08-16: it accepts
  run-level `idleTicks` and `navTicks` as **optional** keys, so v5 reports in backlogs still
  validate and the v6 this branch produces is already understood. `python build/keydiff.py` is CLEAN
  at 6, and `roomstats.py` routes `enterTick` on `v >= 5`, so 6 keeps its `clearStay` bucket.
  **Nothing is owed to the receiver in either direction and that repository was only read.**
- **`secrethud-001` is display only and was verified as such** — `SecretHud.line` is pure over
  tracker state, swept by three mutations of `build/secrethudprobe.py`, all CAUGHT. `SecretTracker`
  is not in the diff of that feature or of `idletime-001`, so `ownsecrets-001` stays unclaimed.

## Changed This Session

**`critcalc-001`, created and implemented on branch `critcalc-001`.** No version bump, no release,
`dist/`, `gradle.properties` and **`RunReport.kt` untouched**, so no release gate and no schema pair.

- New `CritMeter.kt` (parse, roman numerals, power sum, combat window, wording) and
  `CritMeterTest.kt` (11 cases), ported from a `CC0-1.0` mod in the parent directory.
- `PlayerTabOverlayAccessor` gained an instance `@Accessor("footer")`; field name from `javap` on
  the merged jar, not remembered. `SighteAddons`: `onCrit(text)` and `tabFooter()`, **not** gated on
  `DungeonSession.calibrated` (the Maxor window is stricter). `DungeonSession.reset`:
  `CritMeter.reset()`. `Config.critLine` + a `crit readout` row on the **chat** tab, not the HUD one.
- `feature_list.json` gained the entry, which did not exist before. No probes, no mutation sweep.
- This file was **pruned toward its 80-line ceiling and is still over it — 140, from 150, with a
  whole feature added.** Everything cut survives at `git show 3643004:session-handoff.md`. What
  remains over budget is other features' standing briefs (`recordowner-001`'s trusted-`0/N` risk,
  `scores-fetch-001`'s carried list), which `CLAUDE.md` says an open feature keeps in full; deciding
  which of those has expired is the next prune and wants the features closed first, not a session
  that was here for one afternoon.

## Broken Or Unverified

- **`critcalc-001`: EVERY STRING IN IT IS A HYPOTHESIS AND NOTHING ON DISK CONFIRMS ONE.** Grepping
  `docs/evidence/` and the fifteen real session logs for "explosive shot" or "blessing of power"
  finds nothing — no build ever looked. Verified: the parse, the divide-by-enemies, the roman
  numerals, the power sum, the window, the wording, and that `DungeonSession.reset` shuts the window
  (the test drives `reset()` itself, so that one line is real wiring coverage). **Not** verified:
  that Hypixel's crit, Maxor, Goldor or blessing lines look anything like what is assumed; that
  Fabric delivers them with `overlay` false; that `getFooter()` returns blessings; that the `/sa` row
  toggles and `critLine` survives a restart. **Zero `crit_unparsed` *and* zero readouts in an M7 with
  crits is the failure signal** — pattern matches nothing, or the window never opened.
- **The 2.5 per Blessing of Time is inherited, unexplained and unverifiable here.** Unlike the
  strings it never announces itself wrong — it produces a plausible quotient. `CritMeter.TIME_WORTH`
  is the one line to change if the user says the number is off.
- **What must not be added back:** the source mod's `ApiSender` (POSTed name/crit/power/ratio to a
  third party on every hit, no toggle) and its automatic `/msg` + party lines. Neither is present in
  any form behind any flag; the readout is `addClientSystemMessage` only.
- **The tab-footer mixin was checked to *apply*, not to return anything useful.** One `runClient`
  reached a full resource reload, exit 0, zero mixin errors — which rules out a startup crash
  (`required: true`). **The compile does not catch a wrong `@Accessor` name**: renaming it to
  `footerXYZ` still compiles clean, so `javap` on the merged jar is the only check there is.
- **`idletime-001` (now on `main`) and `secrethud-001`'s wiring have never run in a game.** The pure
  halves are verified; `onTick` → `IdleTime.tick` with the right room, `renderHud`, `currentRoom`,
  the `/sa` rows and `showIdle`/`showSecrets` surviving a restart are not — removing either
  `IdleTime` wiring line passes the whole suite, by declaration. One played floor settles it by eye.
  The definitional ambiguity stands as written: a *discarded* secret run is not an active one, so a
  cleared room whose run was abandoned counts as **idle**; changing that is `SETUP.md` section 4
  first and both halves after, never this side alone.
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
  dungeon: `attributedBy` and `own_interaction` in the fifteen logs on disk answer it.
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
- Regressions found: **none.** The suite on the branch is 20 classes / 276 tests / 0 failures / 0
  skipped, from 19 / 265 on `main`; every previously passing feature's class is in that run and none
  moved.

## Next Best Step

- **Decide `critcalc-001`: it is the only branch open, so a second feature here breaks
  `single_active_feature`.** Grading is **not** required — no schema change, no deploy path, nothing
  in `RunReport.kt`, `priority` 22. Merging it is the ordinary next move.
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
- **Twelve features still exist in source only** — `critcalc-001` joined them. Nothing breaks
  meanwhile, but a released build is the only thing that can produce a `crit_unparsed`, so that
  feature stays a hypothesis until one ships.
- **`stormtimer-001` does not exist yet.** The `LBRelease/` half of the same decompiled mod was
  explicitly out of scope for this session and was not read.

