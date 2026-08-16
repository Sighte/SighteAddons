# Progress Log

## Current Verified State

The state, not a stack of corrections. Edit it in place: a session that finds a line here wrong
rewrites that line. Superseding it in a new paragraph is how this section reached 276 lines
carrying five different values for `main`, and it is not the habit any more. What each session
measured at the time is in its own log entry below, and beyond the last two, in `git log`.

- Repository root: the directory holding `build.gradle` and `gradlew` (clone of
  `Sighte/SighteAddons`). Environment, invariants and probe scripts: `ENVIRONMENT.md`.
- **`mod_version` is 0.13.0 and 0.13.0 is released.** `dist/` holds
  `sighteaddons-0.13.0.jar`, sha256
  `d6dafa1d81b46a769b62a78ec4428f97a18c849e0429bca1d0d57bd153015bf3`; the same bytes are on the
  GitHub release under tag `v0.13.0` and on Modrinth (version `wS3OCLGl`, `listed`), verified by
  comparing sha1 `c3f584598b4121cb36f0eab3cbda7b35b4c1f6e1` three ways rather than assumed. The
  build is reproducible here: two `--rerun-tasks` builds gave byte-identical jars.
- **A GREEN MODRINTH UPLOAD IS NOT A BUILD PLAYERS CAN FIND.** Measured anonymously on 2026-08-16,
  and it is the single thing standing between this repository and its users:
  `api.modrinth.com/v2/project/XuCA5Jje` and `/v2/version/wS3OCLGl` both answer **404**, while
  `api.modrinth.com/v2/search` answers 200 — so it is the project, not the network. The CDN file
  itself *is* reachable (**200**), so a direct link works and nothing else does: the project is not
  browsable, not searchable, and not installable the way anybody actually installs a mod. The
  workflow says why in its own comment — "a project still awaiting Modrinth's review is not
  readable" — and it authenticates for exactly that reason, which is also why the run goes green
  regardless. **Publishing the Modrinth project is a step only the user can take**, and until it is
  taken, every release including this one reaches nobody through Modrinth.
- **One branch is open: `idletime-001`**, off `main` at `2cacb60`, not pushed and not merged.
  `main` itself is at `2cacb60`; PR #51 merged `secrethud-001` into it as `cdc980d` and two
  bookkeeping commits sit on top. The leftover mutation probe that sat uncommitted in
  `RoomHistory.kt:449` is **stashed, not lost** — `git stash list`.
- **The HUD says where the run's time went, and the report carries it.** `idletime-001` adds two
  run-long counters — `idleTicks`, inside an already-cleared room (a `preCleared` one counts) with
  no secret run active, and `navTicks`, inside no room at all — drawn as `Idle  0:24.5  ·  Nav
  1:07.0` behind `Config.showIdle` and an `idle & nav` row in the `/sa` HUD tab. The definitions are
  the receiver's `SETUP.md` section 4 and are implemented as written; `IdleTime.classify` is pure
  over one room reference and is the whole decision. Boss ticks advance `runTicks` and neither
  counter. **The per-tick call and the HUD line have never run in a game** — probes S and T of
  `build/idleprobe.py` measure that nothing in the suite guards them.
- **The HUD says how much of the floor was yours.** `secrethud-001` adds one standing line under the
  header — `Your secrets  2/5 room  ·  11 run` — behind `Config.showSecrets` and a `your secrets`
  row in the `/sa` HUD tab. It is **display only**: `SecretHud.line` formats `TrackedRoom.ownSecrets`
  and decides nothing, so it under-counts by exactly the amount `SecretTracker` does and
  `ownsecrets-001` stays the fix for that. `RunReport.kt` is not in the diff. **The wiring in
  `renderHud` has never run in a game**; the formatter is pure and swept by three probes.
- **The run summary now reports secrets honestly**, and neither half touches a record, a report
  field or the receiver: `RunReport.SCHEMA` stays 5 and `RunReport.kt` is not in the diff.
  `secretcount-001` reads the floor's true party-wide total out of the tab list, so the line is
  `(19 rooms · 10 of 29 secrets)` rather than a count that only covered the rooms this client stood
  in — measured at 5 of 19 rooms producing any reading at all. `secretapi-001` fills in the dash
  every teammate used to get, from two snapshots of the lifetime `skyblock_treasure_hunter`
  achievement, and prints the local player's provable count beside the true one so
  `SecretTracker`'s attribution gap is measured once per run instead of reconstructed from old logs.
  **Neither has run on a real floor**: the two wiring lines need a live `Minecraft`, and
  `secret_api_baseline` / `secret_api_settle` are logged so one played floor settles it.
  `Config.hypixelKey` is blank by default and there is no bundled fallback.
- **Baseline: PASSING. 252 tests across 18 classes on `main` at `2cacb60`; 265 across 19 on branch
  `idletime-001` at `7b17342`**, 0 failures and 0 skipped either way. Take the count from the branch
  you are on, out of `build/test-results/test/*.xml` rather than the console. The tests themselves
  run in ~1.1 s; the rest of a `./gradlew test` is Gradle startup.
- Standard startup path: `./gradlew runClient` — Loom's dev client, which has no valid session and
  cannot reach Hypixel.
- Standard verification path: `./init.sh` → `./gradlew test`; full is **`./gradlew assemble check`**.
  **Not `./gradlew build`** while fixes sit unreleased: `build` is `finalizedBy copyToDist`, which
  deletes and rewrites the released jar under the same version number. `build` belongs to the
  release gate in `CLAUDE.md`, where refreshing that jar is the point.
- **`RunReport.SCHEMA` is 6 on branch `idletime-001` and 5 in every install**, including 0.13.0 —
  the schema change is deliberately unreleased and no version was bumped for it. **No receiver work
  is owed in either direction**: `skyblock-server` `master` is `1a7f435`, deployed, and accepts
  `idleTicks`/`navTicks` as *optional* run-level keys, so v5 reports still validate and v6 reports
  are already understood. `python build/keydiff.py` is CLEAN at 6.

### What is measured, and what is only argued

- **`RoomStats` has all three layers now.** (1) a fetch of the receiver's `GET /roomstats` at game
  start on a daemon thread; (2) a cached file at `configDir/sighteaddons/roomstats.json`, the
  receiver's own document verbatim, written through `.part` + rename with its `ETag` beside it;
  (3) the seeds. Absent is the ordinary first case, not an error, and yields exactly the seeds — as
  does every way layer 1 can fail. **The measured half is inert regardless**: the served document
  has 105 rooms and `sampled 0`, every `clearStay` at `n=0`, because no schema 5 build has been
  *released* on the receiver's side of the pair.
- **The strict secret gate keeps roughly one record in seven, and that is the user's decision**,
  reaffirmed after being shown the measurement and offered the majority rule. The frozen figure is
  in git: `docs/evidence/session-1786719912927/`, five `secret_run_done` events, four refused and
  Chains 2/2 kept. **The aggregate ("12 of 87") is computed over a directory the user appends to and
  moved 80 → 87 → 90 → 91 within minutes — quote `python build/ownsecrets.py` and its output, never
  a transcribed integer.** The weakness underneath is `ownsecrets-001`, `not_started`.
- **A real dungeon run is in the repository and it does not cover everything.** M7, solo,
  2026-08-14, `docs/evidence/session-1786719912927/`, self-checking via `readout.sh`. It settles
  `clear-001`'s sightings-vs-elapsed-ticks note and `anchorOnClear`'s frequency (one in ten). It
  does **not** touch `party-001` — the run was solo, `roster_skew` fired zero times — and does not
  settle `clear-001`'s gap tolerance, which needs a party and a death.
- **`clearpoints-001`'s exclusions all stand and are all guarded** — rarity, "secrets from the
  database, not `secretsFound`", and the floor. **Two earlier entries claimed a floor guard was
  impossible. They were wrong.**
- **Old and new ClearPoints standings are not comparable.** The committed M7 was played on a debug
  build of `72e0825`, under the **old** formula, so it corroborates the old figures and says nothing
  about the new ones. Pinned by `the seed weight of Pipes is the user's model, not the old one`.
- **Two players sharing a decoration is not a defect and never was.** Decorations are one per
  player; two in one room resolve to the same `Pos` cell, so swapping them changes nothing. The
  damaging failure is the **count mismatch** across different rooms, which `trustOrder` blacks out.
  `party-001`'s entry described the harmless one as the defect until session 011; do not
  reintroduce that reading.
- **What `chat-001` reads and what it does not.** Chat carries a named finder for **wither-essence
  secrets only** — chests, levers, item pickups and the redstone key are announced nowhere, so
  `SecretTracker.isOwn`'s 40-tick coincidence still attributes almost every secret. Hypixel
  announces puzzle failures and solves, but there is no per-secret line and no "who opened the blood
  door". Every pattern is cited to a published mod; **none has been seen arriving here**, because
  the dev client cannot reach Hypixel. A wrong pattern fails silently and benignly, and
  `ChatEvents.nearMiss` writes the offending line (redacted) to the debug log so one real floor says
  which ones are wrong.

### Next

- **`idletime-001` is `passing` on its branch and wants merging or grading before anything else
  starts in this repository** — one active feature at a time, and its branch is the only one open.
  **Grading is required for it**: it changes the report schema.
- Current highest-priority workable feature: **`runend-001`** — cheap, and its open question is
  already answered (write the run-level count, not the event count). **`floorname-001`** is the
  cheapest entry on the board and its decision is argued in the entry: map `Entrance` → `E` on this
  side, no receiver change and no pairing.
- Current blocker: none for the next feature. `records-001` is deferred by the user (a product
  decision). `party-001` is `blocked` on a finding, not a task. `ingame-001` and `deconame-001` both
  need a **party** floor. `chatfields-001` starts with a feature in `Sighte/skyblock-server` and is
  a different repository and a different session.
- **The highest-value input on the board is a real session log from 0.12.0 or later**, which can now
  exist: the build with the new gates is released and the user plays. A log whose events include
  `secret_room_first_bar` is 0.12.0 or later — but note the Modrinth entry above, so the build has to
  have come from the direct CDN link or the GitHub release.

## Session Log

Rules: insert the newest session at the TOP. **Keep the last 2 entries and drop the rest** —
`git log` is the audit trail, and the full text of any dropped entry is still there under
`git show <hash>:claude-progress.md`. When you drop one, name that hash in the commit message.

One entry per session, **≤ 40 lines**. A revision to work already recorded amends the existing
entry rather than adding a second one.

Entries for 015, 014, 012, 013, 011, 010, 009, 008, 007, 006, 005, 004, 003, 002, 001 were dropped on 2026-08-16; they are complete at `0852382`. Session 017 was dropped the same day; it is complete at `7b17342`.

### Session 019 — `idletime-001`: where a run's time went, and the schema catches up with the receiver

- Date: 2026-08-16
- Branch `idletime-001` off `main` at `2cacb60`. **Not pushed and not merged.** Baseline `./init.sh`
  → **BASELINE: PASSING** before a line was written, 18 classes / 252 tests; after, **19 / 265**,
  0 failures, 0 skipped, counts from `build/test-results/test/*.xml`.
- **The feature did not exist and was created here**, per the delegation: `idletime-001`,
  `priority` 21, `area` telemetry. Two counters, at the user's decision — `idleTicks` for run ticks
  inside an already-cleared room (a `preCleared` one counts) with no secret run active, `navTicks`
  for run ticks inside no room at all. One number could not say which problem a player has.
- **The receiver moved first and that was checked rather than assumed.** `skyblock-server` `master`
  is `1a7f435`, deployed; `RUN_OPTIONAL` in its `ingest.py` carries both keys, so a v5 report in a
  backlog still validates. `python build/keydiff.py` is **CLEAN** at `SCHEMA = 6`, and
  `roomstats.py` routes `enterTick` on `v >= 5`, so 6 keeps its `clearStay` bucket. The definitions
  are that repository's `SETUP.md` section 4, implemented as written.
- **What is pure and what is wiring.** `IdleTime.classify(room)` is a total function of one room
  reference and is the whole decision; `IdleTime.tick` is one `when` over it, called from
  `SighteAddons.onTick` *after* `ContributionTracker.tick` (the room may only have been discovered
  by that call) and inside the boss early-return, so the boss advances `runTicks` and neither
  counter. `DungeonSession.reset` clears them with the clock.
- **`SecretTracker` is not in the diff**, so `ownsecrets-001` stays `not_started` and unclaimed.
  `TrackedRoom` gained one read-only `secretRunOpen`; nothing about how a secret is credited moved.
- **THE AMBIGUITY, resolved as written and not softened.** A *discarded* secret run is not an active
  one, so a cleared room whose run was abandoned counts as idle while its leftovers are still being
  collected. A softer "still working" invented here would be the divergence the receiver-first
  ordering exists to prevent, so it is documented at the site, in the test and in `notes` instead.
- **A guard-in-name-only was found by the sweep, not by reading.** Probe E — dropping
  `!secretRunDiscarded` from `secretRunOpen` — came back **UNCAUGHT**: the test set up a run that
  was never *started*, so `secretRunStart` was null and the discard clause was never reached.
  Replaced with the abandoned case (`expireSecretRun`) at `7b17342`, and E is CAUGHT.
- Evidence, at `7b17342`: the two-class `verification_command` → 11 and 34 tests, 0 failures;
  `./gradlew test --rerun-tasks` → 19/265/0/0 uncached; `bash build/idlesweep.sh` → **SWEEP OK**,
  probes A–H and U all CAUGHT, `git status --porcelain src/` empty after it. That script restores in
  a `trap` — the evaluator's follow-up 1 applied here rather than fixed in `runprobes.sh`.
- **THE CEILING: probes S and T are declared UNCAUGHT and are the whole unverified half.** Removing
  the per-tick `IdleTime.tick` call, or the HUD line, passes the entire suite — both need a live
  `Minecraft`. One played floor settles them by eye.
- **No version bump**: `gradle.properties` and `dist/` are absent from the diff, so none of the
  release gate was pulled and the schema change lands unreleased, which is correct.
- Next: grading is **required** here (report schema), then a real 0.13.0 log, then `ownsecrets-001`.

### Session 018 — `secrethud-001`: the run seen from your side, and nothing about attribution moved

- Date: 2026-08-16
- Branch `secrethud-001` off `main` at `530f470`. **Not pushed and not merged.** Baseline `./init.sh`
  → **BASELINE: PASSING** before a line was written, 17 classes / 247 tests; after, **18 / 252**,
  0 failures, 0 skipped, counts from `build/test-results/test/*.xml`.
- **The feature did not exist and was created here**, per the delegation: `secrethud-001`,
  `priority` 20, `area` hud. One HUD line under the always-drawn header —
  `Your secrets  2/5 room  ·  11 run` — behind `Config.showSecrets` and a `your secrets` row in the
  `/sa` HUD tab. The line is anchored directly under the header on purpose: the header is the only
  unconditional line, so the readout does not move up and down the screen as `showRoom` or
  `showStandings` change.
- **Nothing about attribution was touched, and that was the constraint.** `SecretHud.line(current,
  rooms)` is pure and formats `TrackedRoom.ownSecrets`; `SecretTracker` is not in the diff, so
  `ownsecrets-001` stays `not_started` and unclaimed. The readout under-counts by exactly the amount
  attribution does — a secret walked over is credited to nobody — and that is displayed rather than
  compensated for. The label is `Your secrets` so it remains a true statement when `ownsecrets-001`
  raises the number underneath it.
- **A separate switch rather than part of `showRoom`.** The existing room block already prints
  `3/5 (2 you)` for the room you are in, so the two overlap while both are on; the new line is the
  one that keeps saying something between rooms and carries the run total, which nothing on screen
  had.
- **No schema change and no receiver work.** `RunReport.kt` is absent from `git diff main...HEAD`
  and `RunReport.SCHEMA` is still 5. `Sighte/skyblock-server` was not opened.
- Evidence, at `07b8fd4`: `./gradlew test --tests 'sighteaddons.SecretHudTest'` → 5 tests, 0
  failures; `./gradlew test --rerun-tasks` → 18/252/0/0 uncached; and a three-probe sweep
  (`build/secrethudprobe.py`) in which the run total reading `secretsFound`, the room half reading
  `secretsFound`, and an unknown room spelled `0/0` are **all CAUGHT** — so the tests guard the
  honesty of the number, not just its formatting. `git status --porcelain src/` empty after the
  sweep, which is also what proved the restore was exact (the next `./gradlew test` hit the cache).
- **THE CEILING: the wiring has never run in a game and cannot here.** That `renderHud` calls
  `SecretHud.line` once a frame with the room the player is standing in, that `currentRoom` resolves
  the right room, and every pixel of the new `/sa` row are unverified — they need a live `Minecraft`.
  One played floor settles all of it by eye.
- Next: unchanged — a real 0.13.0 session log is still the highest-value input, then
  `ownsecrets-001`, which this feature deliberately left alone and now has a live readout for.

<!-- SESSION TEMPLATE — copy, do not fill in here
### Session NNN

- Date:
- Goal:
- Completed:
- Verification run (exact commands):
- Evidence captured:
- Commits:
- Files or artifacts updated:
- Regressions found:
- Known risk or unresolved issue:
- Next best step:
-->
