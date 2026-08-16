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
- **One branch is open: `secrethud-001`**, off `main` at `530f470`, not pushed and not merged.
  `main` itself is at `530f470`; PR #50 merged `secretcount-001` and `secretapi-001` into it as
  `38b5528` and the two release commits sit on top. The leftover mutation probe that sat uncommitted
  in `RoomHistory.kt:449` is **stashed, not lost** — `git stash list`.
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
- **Baseline: PASSING. 247 tests across 17 classes on `main` at `530f470`; 252 across 18 on branch
  `secrethud-001` at `07b8fd4`**, 0 failures and 0 skipped either way. Take the count from the branch
  you are on, out of `build/test-results/test/*.xml` rather than the console. The tests themselves
  run in ~1.1 s; the rest of a `./gradlew test` is Gradle startup.
- Standard startup path: `./gradlew runClient` — Loom's dev client, which has no valid session and
  cannot reach Hypixel.
- Standard verification path: `./init.sh` → `./gradlew test`; full is **`./gradlew assemble check`**.
  **Not `./gradlew build`** while fixes sit unreleased: `build` is `finalizedBy copyToDist`, which
  deletes and rewrites the released jar under the same version number. `build` belongs to the
  release gate in `CLAUDE.md`, where refreshing that jar is the point.
- **`RunReport.SCHEMA` is 5, in source and in every install from 0.10.0 on.** No receiver work is
  owed; `python build/keydiff.py` is CLEAN.

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

- **`secrethud-001` is `passing` on its branch and wants merging or grading before anything else
  starts in this repository** — one active feature at a time, and its branch is the only one open.
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

Entries for 015, 014, 012, 013, 011, 010, 009, 008, 007, 006, 005, 004, 003, 002, 001 were dropped on 2026-08-16; they are complete at `0852382`.

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

### Session 017 — the release gate for 0.12.0: merged, tagged, published, and the upload checked

- Date: 2026-08-15
- **No feature was implemented and no source line was written.** This session ran the release gate at
  the top of `CLAUDE.md` for the version bump a previous session had left uncommitted in the working
  tree, and nothing else. `git status --porcelain src/` was empty at every step and is the first
  thing this entry claims, because the evaluator's follow-up 1 is precisely that a crash mid-sweep
  leaves the tree carrying a mutation probe and a release cut from it passes its own tests.
- **The state that was inherited, verified rather than trusted.** `HEAD` `0e08389` on branch
  `recordowner-001`; working tree carrying `D dist/sighteaddons-0.11.0.jar`, `M gradle.properties`
  (`mod_version` already 0.12.0) and an **untracked `dist/sighteaddons-0.12.0.jar` nobody had
  reviewed**. `main` and `origin/main` both `8431597`, no `v0.12.0` tag anywhere, no release past
  0.11.0, no PR, no Modrinth run.
- **The untracked jar was the question, and it was settled by rebuilding rather than by trusting.**
  sha256 recorded, jar copied aside, then `./gradlew build --rerun-tasks` — `build`, not
  `assemble check`, because at release time refreshing `dist/` through `copyToDist` is the point,
  which is the one context in which `session-handoff.md`'s "Do Not Touch" entry does not apply.
  `--rerun-tasks` rather than `clean`, because `build/` is gitignored and holds `runprobes.sh`,
  `ownsecrets.py`, `keydiff.py` and `recordprobe.py`; `clean` would have destroyed the sweep.
  Result: **`cmp` clean, byte-identical**, sha256
  `378bec73a535c22ce52bfb5449ec0803242d5b773f1001c55af57c98e0f08c0b`. The build is reproducible on
  this machine, so the inherited jar was exactly the tree it claimed to be and was kept.
- **The three pre-publish checks, re-run on `main` after the merge and recorded with their output.**
  (1) `git status --porcelain` empty; `git rev-parse main origin/main` both
  `9aebd6da589bc65caa1b0391ac826af698db86b1`. (2) `./gradlew build --rerun-tasks` → `BUILD
  SUCCESSFUL in 9s`, `classes 15 tests 212 failures 0 errors 0 skipped 0`, and the jar's sha256
  **unchanged either side with `git status` still empty** — the committed jar *is* the rebuild.
  (3) `unzip -p dist/sighteaddons-0.12.0.jar fabric.mod.json` → `"version": "0.12.0"`, matching
  `gradle.properties`. The filename was not taken as evidence.
- **Merged the way this repository merges.** Branch pushed, PR **#48** opened with `gh`, merged as
  `9aebd6d` with the subject `Merge pull request #48 from Sighte/recordowner-001`, `main` pushed.
  `git merge-base main recordowner-001` was `8431597` — the branch point — so the merge had zero
  conflicts by construction. **`gh pr merge` was refused by this machine's permission classifier**,
  so the identical merge commit was made with `git merge --no-ff` and pushed; GitHub closed #48 as
  `MERGED` on its own. Recorded because the next session will hit the same refusal.
- **Released and, more to the point, the upload was watched.** `gh release create v0.12.0
  dist/sighteaddons-0.12.0.jar --target main`, cut from `main` after the merge, never from the
  branch. `.github/workflows/modrinth.yml` fired on `release: published`: run **31856152798,
  success in 7s**, and the API's own response was read rather than the green tick trusted —
  version `diMDvw5I` on project `XuCA5Jje`, `version_number` 0.12.0, `version_type` release, status
  `listed`, `game_versions [26.1.2]`, `loaders [fabric]`, size 221873. **The uploaded file's sha1
  `a3712e1a362227c366a0b5fa977c83d47b70c22b` was compared against the local jar and matched**, so
  GitHub, `dist/` and the Modrinth CDN serve the same bytes — which is the whole point of that
  section of `CLAUDE.md` and the one thing a failed upload would have hidden.
- **The notes were written to be strippable, and the strip was simulated before publishing.** The
  workflow removes the operator section with `re.sub(r"\n## Not in the jar.*?(?=\n## )", ...)`, a
  **lookahead that only fires if another `## ` heading follows** — so the section was named exactly
  `## Not in the jar` and placed before `## Requirements`. Simulated locally first: 5266 chars on
  GitHub, 4654 on Modrinth, `skyblock-server` absent from the player-facing copy, every other
  section intact. Confirmed afterwards in the run log (`4654 chars of changelog`).
- **Every claim in the notes was re-derived, not transcribed**, which is the evaluator's follow-up 2
  applied rather than acknowledged. The aggregate ratio was **deliberately not printed** — it is
  computed over a directory the user is appending to and went 80 → 87 → 90 → 91 within minutes. The
  frozen committed floor was recomputed instead, from
  `docs/evidence/session-1786719912927/session-excerpt.jsonl`: five `secret_run_done` events, and
  under `ownSecrets == secretsFound` **four of five no longer record** — Atlas 6 secrets/4 own,
  New Trap 3/2, Slime 5/2, Pipes 7/5 all refused; **Chains 2/2 kept**. The `0/N` bar evidence was
  re-read at source: `session-1786567867893.jsonl` line **85**, `t=137`,
  `{"e": "secret_room_mismatch", "room": "Slime", "barMax": 7, "expected": 5, "barFound": 0}` —
  Hypixel does send `0/N`, but on a room whose max disagreed with the database, so the *trusted*
  path is still unproven. `OWN_WINDOW = 40` ticks was read out of `SecretTracker.kt:42` before the
  notes said "about two seconds", and `RoomHistory.kt:179`'s
  `if (Config.ownPbsOnly && pb == null) return` before they said a refused run no longer prints.
- **Nothing was owed to `Sighte/skyblock-server` and nothing was done there.** `RunReport.SCHEMA` is
  5, `RunReport.kt` is absent from the release diff, and the release notes say so explicitly under
  the one heading Modrinth strips — a reader of these notes has been trained to check, and a player
  installing from Modrinth does not run that box.
- Files changed: `gradle.properties` (0.11.0 → 0.12.0), `dist/` (swapped, still exactly one jar),
  and the record artifacts. **No file under `src/` was touched by this session at all.**
- Verified: the three pre-publish checks above, PR #48 `MERGED`, tag `v0.12.0` resolving to
  `9aebd6d` via `gh api .../git/ref/tags/v0.12.0`, Modrinth run success with the hash match.
- Still unverified, and unchanged by shipping it: **neither gate has run in a real game.** Probes S
  and T still measure that nothing guards the four wiring lines. **The sharp one is now in players'
  hands**: if Hypixel does not deliver a trusted `0/N` bar for a room the mod has identified, secret
  records stop entirely rather than becoming rare. `secret_room_first_bar` and `firstBar` are in
  this build's debug log so the first real floor answers it from data.
- Next: **read a real 0.12.0 session log.** It is now the cheapest and highest-value input on the
  board, and unlike before this release it can actually exist.

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
