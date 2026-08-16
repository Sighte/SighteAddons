# Session Handoff

Current state only, **<= 150 lines**. Amend the sections that changed; do not rewrite the file
from scratch each session. Standing facts — the toolchain, the quoting traps, the probe scripts,
and the code invariants a tidy-up would break — are in `ENVIRONMENT.md`. Read that; do not rewrite
it. Past sessions are in `claude-progress.md` and, beyond the last two, in `git log`.

**Branch state:** **0.13.0 is released** — PR #50 merged the two secret-count features, `v0.13.0`
is tagged and published on GitHub, and Modrinth version `wS3OCLGl` carries the identical jar. No
branch is open. The leftover mutation probe that sat uncommitted in `RoomHistory.kt:449` (it made
the `ofTotal == null` arm print "of null") is stashed, not lost: `git stash list`.

**The release does not reach players and the reason is not in this repository.** The Modrinth
project answers 404 to anyone not logged in — it is still awaiting review — so the build is
downloadable by direct CDN link and by nothing else. See Current Verified State in
`claude-progress.md`. Publishing the project is the user's step.

## Verified Now

- **`main` is at `9aebd6d` and 0.12.0 is released.** `recordowner-001` merged as PR **#48** with
  zero conflicts — `git merge-base main recordowner-001` was `8431597`, the branch point, so there
  was nothing to reconcile. `main` and `origin/main` are level. **The branch is merged; do not
  continue work on it.**
- **`mod_version` is 0.12.0 and `dist/` holds exactly one jar**, `sighteaddons-0.12.0.jar`,
  sha256 `378bec73a535c22ce52bfb5449ec0803242d5b773f1001c55af57c98e0f08c0b`. The same bytes are on
  the GitHub release, in `dist/`, and on the Modrinth CDN — the uploaded file's sha1
  `a3712e1a362227c366a0b5fa977c83d47b70c22b` was compared, not assumed.
- **The build is reproducible on this machine.** `./gradlew build --rerun-tasks` reproduced the jar
  an earlier session had left untracked in the tree **byte-identically** (`cmp` clean). That is why
  it was shipped rather than replaced, and it is the reason CLAUDE.md's second pre-publish check is
  meaningful here rather than vacuous.
- **The suite is 212 across 15 classes, 0 failures, 0 skipped**, unchanged by the release. Counts
  come from `build/test-results/test/*.xml`, not the console.
- **`RunReport.SCHEMA` is 5 and `RunReport.kt` is absent from the release diff.** No receiver work
  was owed, none was done, `Sighte/skyblock-server` was not touched this session at all.
- The release gate's three checks, with what they actually printed:
  - `git status --porcelain` empty; `git rev-parse main origin/main` both
    `9aebd6da589bc65caa1b0391ac826af698db86b1`
  - `./gradlew build --rerun-tasks` → `BUILD SUCCESSFUL in 9s`, `classes 15 tests 212 failures 0
    errors 0 skipped 0`, jar sha256 **unchanged either side** with `git status` still empty
  - `unzip -p dist/sighteaddons-0.12.0.jar fabric.mod.json` → `"version": "0.12.0"`, matching
    `gradle.properties`
  - `git status --porcelain src/` **empty before the build and after it** — the check that a release
    was not cut from a tree still carrying a mutation probe
- **The Modrinth upload was watched, not assumed.** Workflow run `31856152798`, success in 7s;
  version `diMDvw5I` on project `XuCA5Jje`, `version_number` 0.12.0, `version_type` release, status
  `listed`, `game_versions [26.1.2]`, `loaders [fabric]`, size 221873.
- **The frozen release figure, recomputed rather than transcribed.** From
  `docs/evidence/session-1786719912927/session-excerpt.jsonl`, five `secret_run_done` events; under
  `ownSecrets == secretsFound` **four of five no longer record** — Atlas 6/4, New Trap 3/2, Slime
  5/2, Pipes 7/5 refused, **Chains 2/2 kept**. This figure is in git and cannot drift. The aggregate
  ("12 of 87 / 13.8%") still quoted in `feature_list.json`, `claude-progress.md` and
  `quality-document.md` **is computed over a directory the user is appending to** and moved
  80 → 87 → 90 → 91 within minutes. Prefer the frozen figure or `python build/ownsecrets.py` with
  its output; do not transcribe the integer again.

## Changed This Session

**Nothing under `src/`.** This session ran the release gate at the top of `CLAUDE.md` for the
version bump a previous session had left uncommitted, and nothing else.

- `gradle.properties`: `mod_version` 0.11.0 → 0.12.0.
- `dist/`: `sighteaddons-0.11.0.jar` → `sighteaddons-0.12.0.jar`, committed as a rename.
- Merged `recordowner-001` to `main` (PR #48 → `9aebd6d`), tagged `v0.12.0` on that sha, published
  the GitHub release, and verified the Modrinth upload it triggered.
- The record artifacts, on branch `record/release-0.12.0`.

**The release notes were written to be strippable and the strip was simulated before publishing.**
`.github/workflows/modrinth.yml` removes the operator section with
`re.sub(r"\n## Not in the jar.*?(?=\n## )", ...)`. That lookahead **only fires if another `## `
heading follows**, so the section must be named exactly `## Not in the jar` and must not be last.
Here it sits before `## Requirements`: 5266 chars on GitHub, 4654 on Modrinth, `skyblock-server`
absent from the player-facing copy. Simulated locally first, then confirmed in the run log.

## Broken Or Unverified

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

- **READ A REAL 0.12.0 SESSION LOG. It is now the cheapest and highest-value input on the board, and
  unlike a week ago it can actually exist** — the build with the new gates is on Modrinth and the
  user plays. One floor settles `recordowner-001`'s entire remaining ceiling: whether
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
- **Nine features still exist in source only** (`recordowner-001` is no longer one of them). Nothing
  breaks meanwhile; nothing reaches a player either.

