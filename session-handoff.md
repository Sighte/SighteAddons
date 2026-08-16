# Session Handoff

Current state only, **<= 150 lines**. Amend the sections that changed; do not rewrite the file
from scratch each session. Standing facts — the toolchain, the quoting traps, the probe scripts,
and the code invariants a tidy-up would break — are in `ENVIRONMENT.md`. Read that; do not rewrite
it. Past sessions are in `claude-progress.md` and, beyond the last two, in `git log`.

**Branch state:** **0.13.0 is released** — PR #50 merged the two secret-count features, `v0.13.0`
is tagged and published on GitHub, and Modrinth version `wS3OCLGl` carries the identical jar.
`secrethud-001` merged via PR #51 → `cdc980d`; `main` is now `2cacb60`.
**One branch is open: `idletime-001` at `7b17342`, `passing`, not pushed and not merged.** It moves
`RunReport.SCHEMA` 5 → 6 and **grading is required for it** (report schema). No release gate was
pulled: `mod_version` is untouched at 0.13.0 and `dist/` is absent from the diff. The leftover
mutation probe once uncommitted in `RoomHistory.kt:449` is stashed, not lost: `git stash list`.

**The release does not reach players and the reason is not in this repository.** The Modrinth
project answers 404 to anyone not logged in — still awaiting review — so the build is downloadable
by direct CDN link and by nothing else. Publishing the project is the user's step; the measurement
is in `claude-progress.md`.

## Verified Now

- **`main` is at `2cacb60` and `mod_version` is 0.13.0.** The release, its jar hashes and the
  Modrinth state are in `claude-progress.md`'s Current Verified State and are not repeated here.
- **The suite is 252 across 18 classes on `main`, 265 across 19 on `idletime-001`**, 0 failures and
  0 skipped either way. Counts come from `build/test-results/test/*.xml`, not the console.
- **`RunReport.SCHEMA` is 6 on the branch and 5 in every install, and the pair is closed.**
  `skyblock-server`'s `master` is `1a7f435`, deployed and verified on the box 2026-08-16: it accepts
  run-level `idleTicks` and `navTicks` as **optional** keys, so v5 reports in backlogs still
  validate and the v6 this branch produces is already understood. `python build/keydiff.py` is CLEAN
  at 6, and `roomstats.py` routes `enterTick` on `v >= 5`, so 6 keeps its `clearStay` bucket.
  **Nothing is owed to the receiver in either direction and that repository was only read.**
- **`secrethud-001` is display only and was verified as such** — `SecretHud.line` is pure over
  tracker state, swept by three mutations of `build/secrethudprobe.py`, all CAUGHT. `SecretTracker`
  is not in the diff of that feature or of `idletime-001`, so `ownsecrets-001` stays unclaimed.

## Changed This Session

**`idletime-001`, created and implemented on branch `idletime-001`.** No version bump, no release,
`dist/` and `gradle.properties` untouched, so none of the release gate was pulled.

- New `IdleTime.kt` (the accumulator plus the pure `classify`) and `IdleTimeTest.kt` (11 cases).
- `TrackedRoom`: one read-only `secretRunOpen`. Attribution untouched.
- `SighteAddons.onTick`: `IdleTime.tick(currentRoom(client))`, after `ContributionTracker.tick` and
  inside the boss early-return. `renderHud`: one line behind `Config.showIdle`.
- `DungeonSession.reset`: `IdleTime.reset()`, so the counters are per run like the clock.
- `RunReport`: `SCHEMA` 5 → 6, `idleTicks`/`navTicks` behind `runTicks`, both required parameters of
  `build` so a new write path cannot forget them; `RunReportTest` gained 2 cases and its `v`
  assertion moved to 6.
- `Config`: `showIdle`, in the defaults, in `read` and in `save` — a key missing from `save` is a
  HUD that switches itself off after an update. `SettingsScreen`: an `idle & nav` row on the HUD
  tab, and the placement preview gained the line so the mock is the height of the block it places.
- `feature_list.json` gained the `idletime-001` entry, which did not exist before this session.
- New probe scripts in gitignored `build/`: `idleprobe.py` (11 probes) and `idlesweep.sh`, which
  restores in a `trap` and refuses to start on a dirty `src/`.

## Broken Or Unverified

- **`idletime-001`'s wiring has never run in a game, and probes S and T measure exactly that.**
  Verified: what a tick counts as, for a given room state, and what the report and the line say for
  given counters. **Not** verified: that `onTick` calls `IdleTime.tick` once per tick with the room
  the player is actually in, that `currentRoom` resolves that room from the player's own x/z, that
  the `idle & nav` row renders and toggles, and that `Config.showIdle` survives a restart. Removing
  either wiring line passes the whole suite — by declaration, not by oversight. One played floor
  settles all of it by eye, and `jq '{v, runTicks, idleTicks, navTicks}'` over the new profile line
  on the box settles the report half.
- **The one ambiguity in the shared definition, resolved as written rather than softened.** A
  *discarded* secret run is not an active one, so a cleared room whose run was abandoned counts as
  **idle** while its leftovers are still being collected — an over-count of `idleTicks`. The receiver
  and the mod say the same thing here; if the user wants the other reading it is a change to
  `SETUP.md` section 4 first and to both halves after, never to this side alone.
- **`secrethud-001`'s wiring has never run in a game either** — the formatter is verified, the
  `renderHud` call, `currentRoom`, the `/sa` row and `Config.showSecrets` surviving a restart are
  not. Same live-`Minecraft` ceiling, and the same one floor settles it.
- **The `Your secrets` readout under-counts and that is the specification, not a defect** — it shows
  `TrackedRoom.ownSecrets`, the same gap that keeps six secret-run records in seven from being
  written. `ownsecrets-001` fixes it, at the tracker.
- **Neither of `recordowner-001`'s gates has ever run in a game, and it is now in players' hands.**
  The predicates are swept by 21 probes; the **wiring** — `onRoomCleared` → `ownClear` with the
  `topPlayer` it just computed, `onSecretRun` → `ownSecretRun` before `record`, `ClearPopup.show`
  under the same answer, `SecretTracker.onActionBar` → `readBar` at all — needs a live `Minecraft`.
  `recordprobe.py`'s own S and T measure that nothing guards those four lines.
- **The sharp one, and shipping it did not soften it.** The secret-run gate depends on a **trusted
  `0/N`** action bar being read on entering the room. If Hypixel does not deliver one for a room the
  mod has identified, secret records do not become rare — they **stop entirely**. Hypixel is known
  to send `0/N` (`session-1786567867893.jsonl` line 85, `t=137`, a `secret_room_mismatch` with
  `barFound: 0`) but on a room whose bar max disagreed with the database, so the *trusted* path is
  unproven. `secret_room_first_bar` and `firstBar` on `secret_run_discarded` ship in 0.12.0 for
  exactly this. Falsified by: `secret_room_first_bar` never appearing, or always carrying
  `untouched: false` for rooms entered clean.
- **The strict secret gate keeps roughly one record in seven, and that is intended** — the user's
  decision, reaffirmed; the measurement is in `claude-progress.md`. The weakness underneath is
  **`ownsecrets-001`, `not_started`**: a secret counts as yours only via a right-click inside
  `SecretTracker.OWN_WINDOW` (40 ticks, `SecretTracker.kt:42`) or a wither-essence chat line, so one
  walked over sinks the room's whole run. Its first task needs no dungeon — `attributedBy` and
  `own_interaction` in the fifteen logs on disk answer it.
- **The bogus bests already in `history.jsonl` are not repaired and deliberately not repairable.**
  The user was told, accepted it, and the release notes state it as fact.
- **A tie in `topPlayer` is arbitrary** — `maxByOrNull` over a `HashMap`, so two members on the same
  tick count resolve in hash order. Pre-existing; pinning it is a decision nobody has asked for.
- **`runTicks` is read on the DISCONNECT path and is not volatile.** Pre-existing, out of scope —
  note `IdleTime`'s two counters, read on that same path, *are* `@Volatile`.
- **Open and recorded, not fixed: `floorname-001`.** The receiver validates `floor` as
  `?|E|[FM][1-7]` under `fullmatch` (`ingest.py:93`, used at `:225`) and `DungeonSession.floor` can
  hold `Entrance`; a 400 is never retried. **`keydiff.py` compares key *sets*** and will never catch
  a value-domain mismatch.
- **`SighteAddons.RUN_END` still has no test of any kind.**
- **`RoomStats.start()` has still never run inside a game** (`scores-fetch-001`'s ceiling), and every
  earlier unverified item carries over unchanged: the atomic rename; the weights against a real run;
  whether the order heuristic is correct; whether `roster_skew` ever fires and whether
  `MapDecoration.name()` carries anything (`deconame-001` — **if not, close `party-001` rather than
  carry it**); the wiring of `positions()`; that Hypixel sends `chat-001`'s strings; the `RED`
  checkmark path and every pixel of `/sa`; the three write paths of `floorloss-001`; and that
  `unattributed` is only ever consumed as a ratio against `roomsCleared`.
- **`build/runprobes.sh` is still not crash-safe** — it restores on the success path rather than in
  a `trap`, so an interruption leaves the tree carrying a deliberate defect, which happened once and
  was repaired by hand. **Evaluator follow-up 1, still open.** `build/evalsweep.sh` and now
  `build/idlesweep.sh` are worked fixes (`trap restore EXIT INT TERM`, a refusal to start unless
  `git status --porcelain src/` is empty) — but `build/` is gitignored, so all three are one
  `git clean` from gone.
- Regressions found: **none.** `./gradlew test --rerun-tasks` on the branch is 19 classes / 265
  tests / 0 failures / 0 skipped, from 18 / 252 on `main`; every previously passing feature's class
  is in that run and none moved.

## Next Best Step

- **Decide `idletime-001` first: grade it, then merge it.** It is `passing` on its branch and it is
  the only branch open, so a second feature started here breaks `single_active_feature`. Unlike
  `secrethud-001`, **grading is required** — it changes the report schema. The evaluator must be a
  fresh agent and should re-run `bash build/idlesweep.sh` itself rather than trust the recorded
  SWEEP OK.
- **READ A REAL SESSION LOG FROM 0.12.0 OR LATER — the cheapest and highest-value input on the
  board, and unlike a week ago it can actually exist.** One floor settles `recordowner-001`'s entire
  remaining ceiling: whether `secret_room_first_bar` appears at all, whether it ever carries
  `untouched: true`, and whether the four wiring lines do what the call graph says. Logs are at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-*.jsonl`;
  one whose events include `secret_room_first_bar` is 0.12.0 or later.
- **Then `ownsecrets-001`** — the measured cause of the record loss, and its first task needs no
  dungeon. `python build/ownsecrets.py` replays the decision against the logs on disk.
- **Follow-up 1 (make `runprobes.sh` crash-safe) before the next mutation sweep**, not before the
  next feature. `build/idlesweep.sh` is the newest worked example of the `trap` form.
- **`floorname-001` is the cheapest entry on the board** and its decision is already argued in the
  entry: map `Entrance` → `E` on this side, which needs no receiver change and no pairing.
- **Then `runend-001`.** Cheap, and its open question is already answered: write the run-level count,
  not the event count.
- **Do not start `chatfields-001`** by editing `RunReport.kt` — its first move is a feature in
  `Sighte/skyblock-server`. `records-001` is deferred by the user, a product decision.
- **Eleven features still exist in source only** — `idletime-001` joined them, since nothing on a
  branch reaches a player. Nothing breaks meanwhile either.

