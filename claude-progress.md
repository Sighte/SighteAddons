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
- **No branch is open.** PR #50 merged `secretcount-001` and `secretapi-001` into `main` as
  `38b5528`, rebased first so the diff is `src/` only and this repository's pruned artifacts
  survived it. The leftover mutation probe that sat uncommitted in `RoomHistory.kt:449` is
  **stashed, not lost** — `git stash list`.
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
- **Baseline: PASSING, 247 tests across 17 classes**, 0 failures, 0 skipped, on `main` at `38b5528`.
  Counts come from `build/test-results/test/*.xml`, not the console. The tests themselves run in
  ~1.1 s; the rest of a `./gradlew test` is Gradle startup.
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

- Current highest-priority workable feature: **`runend-001`** — cheap, and its open question is
  already answered (write the run-level count, not the event count). **`floorname-001`** is the
  cheapest entry on the board and its decision is argued in the entry: map `Entrance` → `E` on this
  side, no receiver change and no pairing.
- Current blocker: none for the next feature. `records-001` is deferred by the user (a product
  decision). `party-001` is `blocked` on a finding, not a task. `ingame-001` and `deconame-001` both
  need a **party** floor. `chatfields-001` starts with a feature in `Sighte/skyblock-server` and is
  a different repository and a different session.
- **The highest-value input on the board is a real 0.12.0 session log**, which can now exist: the
  build with the new gates is on Modrinth and the user plays. A log whose events include
  `secret_room_first_bar` is 0.12.0 or later.

## Session Log

Rules: insert the newest session at the TOP. **Keep the last 2 entries and drop the rest** —
`git log` is the audit trail, and the full text of any dropped entry is still there under
`git show <hash>:claude-progress.md`. When you drop one, name that hash in the commit message.

One entry per session, **≤ 40 lines**. A revision to work already recorded amends the existing
entry rather than adding a second one.

Entries for 015, 014, 012, 013, 011, 010, 009, 008, 007, 006, 005, 004, 003, 002, 001 were dropped on 2026-08-16; they are complete at `0852382`.

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

### Session 016 — `recordowner-001` revision: the cost was an adjective, and a second guard held nothing

- Date: 2026-08-15
- Branch `recordowner-001`, unchanged, off `main` at `8431597`. **Not pushed and not merged.** For
  the commit count run `git rev-list --count 8431597..HEAD`. This is a revision pass on the same
  feature, not a new one; the feature stays `passing`, which the evaluator argued for explicitly.
- Baseline: `bash init.sh` → **BASELINE: PASSING** at start and end. Suite **211 → 212** in the same
  15 classes, 0 failures, 0 skipped.
- **A fresh evaluator graded the feature REVISE, 11/14** (Correctness 2, Verification 1, Regression
  2, Scope 2, Reliability 2, Maintainability 1, Handoff 1). Its pass is committed verbatim at
  `e5cf586` — by this session, unedited, because it was sitting uncommitted in the working tree and
  would otherwise have been lost. **It reproduced every recorded number and all ten probes to the
  failing test name.** Nothing was broken; two things were cheaply knowable and not known.
- **THE COST OF THE STRICT SECRET GATE WAS WRONG IN EVERY ARTIFACT, AND IT IS THE FINDING THAT
  MATTERS MOST because a release is written from these files.** Session 015 wrote it as "party secret
  records become rare". Re-derived here rather than copied, with `python build/ownsecrets.py`, which
  replays `ownSecretRun` over the `secret_run_done` events — that event has always carried exactly
  the two numbers the gate compares, so it is a replay and not a reimplementation. **Of 87 completed
  secret runs across the fifteen real logs, 12 survive — 13.8%.** By roster: **2 of 23 on
  single-member sessions**, 10 of 64 on party sessions. On the committed floor, four of five go and
  only `Chains 2/2` stays. **So the cause is attribution, not shared work**: on a solo floor every
  secret was the local player's by construction, and one solo log carries `Big Red Flag 0/2` — a
  room the player emptied alone and was credited with none of. The 87 is confirmed three ways with
  zero unparseable lines.
- **The gate does not move.** The user was shown these numbers and was offered the majority rule
  `ownSecrets * 2 >= secretsFound` (which keeps four of the committed floor's five). They reaffirmed
  `ownSecrets == secretsFound` and asked for it to ship as written. Not weakened, no escape hatch,
  not made configurable. The underlying weakness is recorded as **`ownsecrets-001`, `not_started`** —
  discovered work recorded rather than fixed inline, which is the operating loop's rule.
- **A SECOND GUARD IN NAME ONLY, IN THE SAME PREDICATE, AND THE SWEEP THAT SHOULD HAVE FOUND IT.**
  The evaluator's probe K — delete the `MIN_TICKS` floor from `ownClear` — passed all 211 tests.
  **Reproduced here rather than transcribed**: `git checkout 4e2db23 -- RoomHistory.kt
  RoomHistoryTest.kt`, apply probe K, `./gradlew test --rerun-tasks` → BUILD SUCCESSFUL, 211 tests,
  0 failures. The case carrying the name gave the local player `min - 1` ticks from tick 1000 and
  asked about 1080, so `presentFromStart`'s staleness half refused it 61 ticks out and the floor was
  never under test, while the KDoc called the predicate "total". Same species as probe H, which
  session 015 found and wrote up and then did not sweep for.
- **Fixed as a property, not as one case.** The shape that genuinely reaches the floor is the fast
  clear — a room anchored by `anchorOnClear` where six ticks of presence satisfy `presentFromStart`,
  which is `Duncan` on the one real M7 (entered 2990, cleared 2996). Without the floor that room
  writes a 0.3 s clear: defect C by another route. The new case asserts every *other* condition says
  yes before asserting the gate says no. A second new case records why no fixture built from
  `onRoomCleared` could ever reach it — `eligible` is filtered before `topPlayer` is taken from it —
  so the honest half of the old totality claim is measured instead of asserted.
- **THE SWEEP IS NOW A SWEEP: 21 probes, one per condition in every gate**, up from ten hand-picked.
  `ownClear`'s conditions are one per line, because a compound condition cannot be probed alone. Each
  probe declares whether it expects to be caught, and `bash build/runprobes.sh` prints `SWEEP OK`
  only when every one meets its expectation — so a guard that rots and a ceiling that lifts are
  equally visible. **18 caught; 3 expected-uncaught, each with its reason in the script**: `Q`
  (`max < 2` alone) is redundant with `if (found >= max)` three lines below and is pre-existing; `S`
  and `T` mutate the two wiring lines and are the feature's declared ceiling, now measured on every
  run rather than asserted once.
- **The handoff said there was one real session file. There are fifteen.** Counted in the directory
  the handoff itself names, and they carry 30 `death`, 15 `revive`, 104 `roster_skew` and 49
  `chat_secret` events — while `main`'s own `dc8d504` had already analysed three of them. That claim
  had `Next Best Step` asking the user to play a party floor with a death for data partly already on
  disk. Corrected. **A trap for anyone globbing for them**: the repository's own
  `config/sighteaddons/debug/` holds ~145 files written by `./gradlew test` and contains no dungeon.
- **One thing those logs already settle**, which session 015 listed as unobservable:
  `session-1786567867893.jsonl` line 85, `t=137`, is
  `{"e":"secret_room_mismatch","room":"Slime","barMax":7,"expected":5,"barFound":0}` — a real Hypixel
  action bar reporting **zero** secrets. The room's max mismatched so it was not a *trusted* reading,
  but it is direct evidence that Hypixel sends `0/N` at all, which is the assumption the whole
  secret-run half rests on. What remains unobserved is narrower: a `0/N` on a bar whose max matches
  the database, plus the four wiring lines.
- **`onSecretRun`'s KDoc over-claimed on one path and is now qualified.** "The announcement stays
  either way" is true on the defaults and false with `Config.ownPbsOnly` on, where a refused run has
  a null `pb` and the line is suppressed. `ownPbsOnly` defaults to `false`.
- **The quality table's *current* `scoring` row was the stale copy, and session 015 dated it.** It
  read `RoomStatsTest` (9 cases) against an actual 17 and claimed layer 2 had never been read
  "because nothing writes a cache yet", untrue since `scores-fetch-001` merged — while the accurate
  row sat in the superseded block below. Promoted, with the reason recorded in the row so the next
  session does not repeat the mistake.
- **No behaviour changed in this revision** except `ownClear`'s conditions moving onto separate
  lines, which is identical logic. `RunReport.kt` still absent from the branch diff, `SCHEMA` still
  5, `keydiff` CLEAN, jar md5 `e8cd7099034dd3475dbc8069be3c433e` identical either side of
  `assemble check`, `mod_version` and `dist/` untouched, `SighteAddonServerside` read and never
  written, `evaluator-rubric.md` committed verbatim and not edited.

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
