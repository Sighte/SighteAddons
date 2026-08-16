# Session Handoff

Current state only, **<= 120 lines** (the ceiling in `CLAUDE.md`). Amend the sections that changed;
do not rewrite from scratch. Standing facts are in `ENVIRONMENT.md`, past sessions in
`claude-progress.md` and `git log`.

**Branch state: 0.15.0 is released, `main` is `1ef43d3`, and branch `ownsecrets-001` is open off
it.** `main` carries `critcalc-001`, `stormtimer-001` and `secretpoints-001` on top of 0.14.0's
secret readout and idle counters. `v0.15.0` is tagged and published, and Modrinth version `YNlbBvlI` carries the identical
jar — `sha1 de94c224…`, 260722 bytes, compared against the local file rather than assumed.
`RunReport.SCHEMA` is 6 and unchanged since 0.14.0, so the receiver is owed nothing. The leftover
mutation probe once uncommitted in `RoomHistory.kt:449` is stashed, not lost: `git stash list`.

**The release does not reach players and the reason is not in this repository.** The Modrinth
project answers 404 to anyone not logged in — still awaiting review — so the build is downloadable
by direct CDN link and by nothing else. Publishing the project is the user's step. The 64-character
`version_title` cap that failed the first upload is written into `CLAUDE.md`; the measurements are
in `claude-progress.md`.

## Verified Now

- **`mod_version` is 0.15.0 and this branch does not touch it.** The release, its jar hashes and the
  Modrinth state are in `claude-progress.md`'s Current Verified State and are not repeated here.
- **The suite is 289 across 21 classes on `ownsecrets-001`**, 0 failures and 0 skipped; three cases
  added to `SecretTrackerTest`, none removed or weakened. Counts come from
  `build/test-results/test/*.xml`, not the console.
- **`RunReport.SCHEMA` is 6 on `main` and 5 in every install, and the pair is closed.**
  `skyblock-server`'s `master` is `1a7f435`, deployed and verified on the box 2026-08-16: it accepts
  run-level `idleTicks` and `navTicks` as **optional** keys, so v5 reports in backlogs still
  validate and v6 is already understood. `python build/keydiff.py` is CLEAN at 6.
- **`RunReport.kt` is untouched on this branch and no receiver work is owed.** `ownSecrets` changes
  *meaning* here, which is the `clear-001` shape, but the receiver routes nothing on it: its only
  two references in production code are a `ROOM_KEYS` entry and a bounded-number validator
  (`ingest.py:171`, `:289`); `roomstats.py` and `metrics.py` do not mention it. **That repository
  was read and not written to.**

## Changed This Session

**`ownsecrets-001` implemented on branch `ownsecrets-001` off `1ef43d3`.** Two live findings from
the user on the 0.15.0 build opened it: the secret counter does not credit you for a secret you
**pick up**, nor for a **bat you kill**. Both are now signals. No version bump; `dist/`,
`gradle.properties` and `RunReport.kt` untouched. `stormtimer-001`'s detail survives at
`git show 1ef43d3:session-handoff.md`.

- **Neither signal is a second mechanism.** Both arm `lastOwnInteraction`, the same 40-tick
  `OWN_WINDOW` a right-click arms, and Hypixel's room counter still decides that a secret
  *happened* — so a signal no rise follows expires unspent, and one credit consumes it exactly as a
  click's does. The user's report that the run **timer** already starts on a pickup is the proof the
  anchor was never the broken half.
- **The pickup is `TakeItemEntityMixin`**, HEAD of `ClientPacketListener.handleTakeItemEntity`,
  collector id compared against the local player. **The bat is `AttackEntityCallback` narrowed to
  `Bat`**, read-only `PASS` like the block callback — a swing is all the client can ever see.
- **`require = 0` overrides `defaultRequire: 1` deliberately.** `ClientPacketListener` loads only on
  joining a server, so an unresolved injector would crash the game on the way into Hypixel rather
  than here; silent is the right failure for a feature that fixes a number. Detection is the absence
  of `own_pickup`.
- **`SECRET_ITEMS` is an exact-match whitelist and that is the whole false-positive defence.** Never
  prefix or substring: `Enchanted Superboom TNT` and `Decoy Wand` are the cases in the test. Ten
  names, every one a guess; `pickup_unmatched` (deduplicated, capped at 32 a run) is the only thing
  that can correct them. `attributedBy` now reads `chat`/`click`/`pickup`/`bat`/`none`.
- **The 40-tick window was NOT widened.** Measured over the six single-member sessions on disk,
  where every secret is the local player's by construction: 80 credited, 37 missed, and 26 of the 37
  have no `own_interaction` within 80 ticks at all. Only 6 sit in the 41–80 band, so widening buys
  almost nothing and costs false-positive exposure in every party room.

## Broken Or Unverified

- **`stormtimer-001` and `critcalc-001` (both released in 0.15.0): TWO SEPARATE CEILINGS EACH, and
  only one of them can ever announce itself.** (1) **The strings**, which nothing on disk confirms —
  no build ever looked, so `grep -ril storm` over the logs finds nothing and never could. A
  `storm_unparsed` or `crit_unparsed` present with no `storm_start`/readout says the strings are
  wrong and quotes what was actually said; **both** absent in a run where the line was spoken says
  it never reached `onChat` at all, which correcting strings would not fix. (2) **The tick counts —
  138, 20, `TIME_WORTH = 2.5`** — invisible when wrong: the timer still counts down and fires, just
  at the wrong moment. Both are `/sa` rows for that reason. Each feature's `verification_manual`
  carries the rest; the pure logic is covered by the suite, and no wiring line of either is.
- **`secretpoints-001` (now on `main`): the arithmetic is verified, the wiring never ran in a game.**
  Not verified: that `onActionBar` → `SecretTracker.onActionBar(text, self, x, z)` delivers the name
  Hypixel's roster keys on, that the local player's standings row exists at all (it needs
  `PartyTracker.roster()` to contain them — pre-existing, true of clear points too), or that the HUD
  repaints between the find and the next clear. One floor settles all three: open a chest, watch
  your own row rise 0.25 before the checkmark.
- **`ownsecrets-001`: the DECISION is verified, NOT ONE LINE OF THE WIRING IS, and the vocabulary is
  a guess.** Verified by the suite: which names count as secret items, that a near miss
  (`Enchanted Superboom TNT`, `Decoy Wand`) does not, and that the unknown-name log says each name
  once and stops at 32. **Not** verified, and no dungeon on this machine can be: that Hypixel sends
  a take-item packet naming the collector when you walk over a secret; that the mixin's injector
  resolves at all (`ClientPacketListener` loads only on joining a server, and `require = 0` means it
  fails silently if it does not); that `AttackEntityCallback` fires for a dungeon bat; that any of
  the ten item names is the string Hypixel uses. **One floor settles all of it** — see the feature's
  `verification_manual`, which reads the three questions in the order that tells them apart.
- **`./gradlew runClient` IS OFF THE TABLE FROM 2026-08-17, by the user's instruction** — it opens a
  game window over the one they are playing on this machine. It ran three times earlier that day
  (exit 0, full resource reload, zero mixin errors) and must not run again. What that costs is
  exact and is not worked around: a wrong `@Accessor` field name is now only checkable with `javap`
  on the merged jar, and **a mixin injector that does not resolve has no local check at all**. Both
  are unverified paths like any other this machine cannot reach.
- **A bat killed at range stays uncredited, by construction.** `AttackEntityCallback` is a melee
  swing; a bow, a wand or any AOE weapon never reaches it. That is the direction this feature is
  required to err in — a missing credit, not an invented one — but it means `attributedBy: bat` can
  be legitimately rare on a floor where bats were shot rather than hit.
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
- **The strict gate keeps roughly one record in seven, intended, the user's reaffirmed decision, and
  `ownsecrets-001` does not loosen it** — `RoomHistory.ownSecretRun` is still
  `ownSecrets == secretsFound`. What the branch changes is the number that gate reads. Whether the
  13.8% survival rate actually moves is measurable only from a floor played on a build that carries
  this, and no such build exists yet.
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
- Regressions found: **none.** 21 classes / 289 tests / 0 failures / 0 skipped on the branch, from
  21 / 286 on `main`; every previously passing feature's class is in that run and none moved.

## Next Best Step

- **Decide `ownsecrets-001`: it is the only branch open, so a second feature here breaks
  `single_active_feature`.** No schema change and nothing in `RunReport.kt`. It **does** add a
  mixin, which is the one thing to weigh: `require = 0` means a wrong injector is silent rather
  than fatal, so the merge cannot brick a join, but it also cannot announce itself.
- **READ A REAL SESSION LOG FROM 0.12.0 OR LATER — the cheapest and highest-value input on the
  board, and unlike a week ago it can actually exist.** One floor settles `recordowner-001`'s entire
  remaining ceiling: whether `secret_room_first_bar` appears at all, whether it ever carries
  `untouched: true`, and whether the four wiring lines do what the call graph says. Logs are at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-*.jsonl`;
  one whose events include `secret_room_first_bar` is 0.12.0 or later.
  It now also settles `ownsecrets-001`, which no earlier log can: `own_pickup`, `own_bat` and
  `pickup_unmatched` only exist on a build carrying this branch.
- **Then `floorname-001`**, the cheapest entry on the board and already argued in its entry (map
  `Entrance` → `E` here, no receiver change, no pairing), then `runend-001` (write the run-level
  count, not the event count). **`secretburst-001` was opened this session** and recorded rather
  than fixed: a rise of `delta: 2` that was entirely yours still credits 1, which this branch makes
  likelier rather than rarer. Its entry says why `ownSecrets += delta` is the wrong fix.
- **Follow-up 1 (make `runprobes.sh` crash-safe) before the next mutation sweep**, not before the
  next feature. `build/idlesweep.sh` is the newest worked example of the `trap` form.
- **Do not start `chatfields-001`** by editing `RunReport.kt` — its first move is a feature in
  `Sighte/skyblock-server`. `records-001` is deferred by the user, a product decision.
- **Features exist in source only until a release, and `ownsecrets-001` joins that queue.** Nothing
  breaks meanwhile, but **a released build is the only thing that can produce an `own_pickup`, a
  `pickup_unmatched`, a `crit_unparsed` or a `storm_unparsed`** — the only evidence that will ever
  exist for any of those strings. The whole `LBRelease/` half of the decompiled mod is read and
  ported; nothing is left in it.

**This file is 187 lines against the 120-line ceiling, down from 186 at the start of the session and
with a feature's worth of new state added.** Paid for by folding `stormtimer-001` and `critcalc-001` into
one bullet — the two ceilings were the same two ceilings, and the detail is in each feature's
`verification_manual` — and by dropping `ownsecrets-001`'s standing brief, which the branch
answers. What is left over the ceiling is standing briefs for work that is still open, which
`CLAUDE.md` says to keep in full. **The real prune wants those closed, not another session trimming
around them**, and one played floor settles most of them at once.

