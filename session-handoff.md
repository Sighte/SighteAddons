# Session Handoff

Current state only, **<= 150 lines**. Amend the sections that changed; do not rewrite the file
from scratch each session. Standing facts — the toolchain, the quoting traps, the probe scripts,
and the code invariants a tidy-up would break — are in `ENVIRONMENT.md`. Read that; do not rewrite
it. Past sessions are in `claude-progress.md` and, beyond the last two, in `git log`.

**Branch state:** **0.13.0 is released** — PR #50 merged the two secret-count features, `v0.13.0`
is tagged and published on GitHub, and Modrinth version `wS3OCLGl` carries the identical jar.
**`secrethud-001` merged via PR #51 → `cdc980d` on `main`** on 2026-08-16; no branch is open, and the
merge cut no release — `mod_version` is untouched at 0.13.0, `dist/` and `RunReport.kt` absent from
the diff. The leftover mutation probe that sat uncommitted in
`RoomHistory.kt:449` (it made the `ofTotal == null` arm print "of null") is stashed, not lost:
`git stash list`.

**The release does not reach players and the reason is not in this repository.** The Modrinth
project answers 404 to anyone not logged in — it is still awaiting review — so the build is
downloadable by direct CDN link and by nothing else. See Current Verified State in
`claude-progress.md`. Publishing the project is the user's step.

## Verified Now

- **`main` is at `cdc980d` and `mod_version` is 0.13.0.** The 0.13.0 release, its jar hashes and the
  Modrinth state are in `claude-progress.md`'s Current Verified State and are not repeated here. The
  0.12.0 release-gate output this section used to carry survives at
  `git show 530f470:session-handoff.md`.
- **The build is reproducible on this machine.** Two `./gradlew build --rerun-tasks` of one tree gave
  byte-identical jars, which is what makes CLAUDE.md's second pre-publish check meaningful here
  rather than vacuous.
- **The suite is 252 across 18 classes on `main`**, 0 failures and 0 skipped, from 247 across 17
  before the merge. Counts come from `build/test-results/test/*.xml`, not the console.
- **`RunReport.SCHEMA` is 5, and the receiver is ahead of it on purpose.** `skyblock-server`'s
  `master` is `1a7f435`, deployed and verified on the box 2026-08-16: it accepts run-level
  `idleTicks` and `navTicks` (schema 6, optional) and nothing sends them. That unblocks
  `idletime-001`.
- **`secrethud-001` is display only and was verified as such.** `SecretHud.line` is pure over tracker
  state; `./gradlew test --tests 'sighteaddons.SecretHudTest'` is 5 tests, and
  `python build/secrethudprobe.py` sweeps three mutations of it — the run total reading
  `secretsFound`, the room half reading `secretsFound`, and an unknown room spelled `0/0` — all
  **CAUGHT**. That script is in gitignored `build/` like every probe here, which is the standing
  defect ENVIRONMENT.md records; the three anchors are written out above so the sweep is
  reconstructible without it. `SecretTracker` is not in the diff, so `ownsecrets-001` is untouched and unclaimed.
- **The frozen four-of-five figure and the caution about transcribing the aggregate now live in
  `claude-progress.md`'s Current Verified State**, where they were duplicated word for word. Full
  text at `git show 530f470:session-handoff.md`.

## Changed This Session

**`secrethud-001`, created and implemented on branch `secrethud-001`.** No version bump, no release,
`dist/` and `gradle.properties` untouched, so none of the release gate was pulled.

- New `SecretHud.kt` (pure formatter) and `SecretHudTest.kt` (5 cases).
- `SighteAddons.renderHud`: one line under the header, behind `Config.showSecrets`.
- `Config`: `showSecrets`, in the defaults, in `read` and in `save` — a key missing from `save` is a
  HUD that switches itself off after an update, which is what that file's KDoc is about.
- `SettingsScreen`: a `your secrets` row on the HUD tab, and the placement preview gained the line
  so the mock is the height of the block it is placing.
- `feature_list.json` gained the `secrethud-001` entry, which did not exist before this session.

## Broken Or Unverified

- **`secrethud-001`'s wiring has never run in a game.** What is verified is the formatter: what the
  line says for a given tracker state. What is **not** is that `renderHud` draws it once a frame with
  the room the player is standing in, that `currentRoom` resolves the right room, that the new `/sa`
  row renders and toggles, and that `Config.showSecrets` survives a restart on a real install. All of
  it needs a live `Minecraft`; one played floor settles all of it by eye.
- **The readout under-counts and that is the specification, not a defect.** It shows
  `TrackedRoom.ownSecrets`, so a secret walked over shows as nobody's — the same gap that keeps six
  secret-run records in seven from being written. `ownsecrets-001` is where that is fixed, and it was
  deliberately not touched here.
- **Neither of `recordowner-001`'s gates has ever run in a game, and it is now in players' hands.**
  The predicates are driven directly and swept by 21 mutation probes; the **wiring** — that
  `onRoomCleared` calls `ownClear` with the `topPlayer` it just computed, that `onSecretRun` calls
  `ownSecretRun` before `record`, that `ClearPopup.show` fires under the same answer, that
  `SecretTracker.onActionBar` calls `readBar` at all — needs a live `Minecraft`. Probes **S and T**
  measure that nothing in the suite guards those four lines and come back uncaught by design.
- **The sharp one, and shipping it did not soften it.** The secret-run gate depends on the mod having
  read a **trusted `0/N`** action bar on entering the room. If Hypixel does not deliver one for a
  room the mod has already identified, secret records do not become rare — they **stop entirely**.
  Hypixel is known to send `0/N`: `session-1786567867893.jsonl` line **85**, `t=137`,
  `{"e": "secret_room_mismatch", "room": "Slime", "barMax": 7, "expected": 5, "barFound": 0}` — but
  on a room whose bar max disagreed with the database, so the *trusted* path is unproven.
  **`secret_room_first_bar` (with `untouched`) and `firstBar` on `secret_run_discarded` ship in
  0.12.0 for exactly this.** What would falsify the design: `secret_room_first_bar` never appearing,
  or always carrying `untouched: false` for rooms entered clean.
- **The strict secret gate keeps roughly one record in seven, and that is intended, not a defect.**
  `ownSecrets == secretsFound` is the user's decision, reaffirmed after being shown the measurement
  and offered the majority rule. The weakness underneath is **`ownsecrets-001`, `not_started`**:
  a secret counts as yours only via a right-click inside `SecretTracker.OWN_WINDOW` (40 ticks, read
  from `SecretTracker.kt:42`) or a wither-essence chat line, so a secret walked over counts as
  somebody else's and sinks the room's whole run. Its first task needs no dungeon — `attributedBy`
  and `own_interaction` in the fifteen logs on disk say what fraction of unattributed rises have no
  interaction near them.
- **The bogus bests already in `history.jsonl` are not repaired and deliberately not repairable.**
  Rooms whose record was set by walking into somebody else's work will never report a PB again. The
  user was told and accepted it, and the release notes state it as fact.
- **A tie in `topPlayer` is arbitrary.** `maxByOrNull` over a `HashMap`, so two members on exactly
  the same tick count resolve in hash order. Pre-existing, not made worse, and pinning it is a
  decision the user has not been asked for.
- **`runTicks` is read on the DISCONNECT path and is not volatile.** Pre-existing, out of scope.
- **Open and recorded, not fixed: `floorname-001`.** The receiver validates `floor` as
  `?|E|[FM][1-7]` under `fullmatch` (`ingest.py:93`, used at `:225`) and `DungeonSession.floor` can
  hold `Entrance`. A 400 is never retried. **`build/keydiff.py` compares key *sets* and cannot see a
  value-domain mismatch**, so it will never catch this.
- **`SighteAddons.RUN_END` still has no test of any kind.**
- **`RoomStats.start()` has still never run inside a game** (`scores-fetch-001`'s ceiling), and every
  earlier unverified item carries over unchanged: the atomic rename is atomic; the weights against a
  real run; whether the order heuristic is correct at all; whether `roster_skew` ever fires and
  whether `MapDecoration.name()` carries anything (`deconame-001` — **if the answer is no,
  `party-001` should be closed rather than carried**); the wiring of `positions()`; that Hypixel
  actually sends `chat-001`'s strings; the `RED` checkmark path and every pixel of the `/sa` screen;
  the three write paths of `floorloss-001`; and the cross-repo reading that `unattributed` is only
  ever consumed as a ratio against `roomsCleared`.
- **The mutation harness is still not crash-safe.** `build/runprobes.sh` restores mutated source on
  the success path rather than in a `trap`, so an interruption between applying a probe and reaching
  `git checkout` leaves the tree carrying a deliberate defect — which happened, and was repaired by
  hand. **This is the evaluator's follow-up 1 and its stated highest-value item, and it is still
  open.** `build/evalsweep.sh` is the worked fix (`trap restore EXIT INT TERM`, a refusal to start
  unless `git status --porcelain src/` is empty, a `git diff --numstat` proof per probe) — but
  `build/` is gitignored, so both scripts are one `git clean` from gone.
- Regressions found: none. Nothing under `src/` was touched.

## Next Best Step

- **Decide `secrethud-001` first: merge it or grade it.** It is `passing` on its branch and it is the
  only branch open, so a second feature started here breaks `single_active_feature`. It is not on a
  deploy path, changes no schema and caused no regression, and its `priority` is 20 — so grading is
  the orchestrator's call, not a requirement.
- **READ A REAL SESSION LOG FROM 0.12.0 OR LATER. It is the cheapest and highest-value input on the
  board, and unlike a week ago it can actually exist** — the build with the new gates is released and
  the user plays. One floor settles `recordowner-001`'s entire remaining ceiling: whether
  `secret_room_first_bar` appears at all, whether it ever carries `untouched: true`, and whether the
  four wiring lines do what the call graph says. Logs are at
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-*.jsonl`.
  A log whose events include `secret_room_first_bar` is from 0.12.0 or later; one without is older.
- **Then `ownsecrets-001`** — the measured cause of the record loss, and its first task needs no
  dungeon at all. `python build/ownsecrets.py` is the worked example of replaying a decision against
  the logs on disk.
- **Follow-up 1 (make `runprobes.sh` crash-safe) before the next mutation sweep**, not before the
  next feature. The release it was blocking is out; the exposure now is a *feature* verified against
  a mutated tree rather than a jar cut from one.
- **`floorname-001` is the cheapest entry on the board** and its decision is already argued in the
  entry: map `Entrance` → `E` on this side, which needs no receiver change and no pairing.
- **Then `runend-001`.** Cheap, and its open question is already answered: write the run-level count,
  not the event count.
- **Do not start `chatfields-001`** by editing `RunReport.kt`. Its first move is a feature in
  `Sighte/skyblock-server`, which is a different repository and a different session.
- `records-001` is deferred by the user — a product decision, not a technical blocker.
- **Ten features still exist in source only** — `secrethud-001` joined them, since nothing on a
  branch reaches a player. Nothing breaks meanwhile either.

