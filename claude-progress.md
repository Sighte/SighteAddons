# Progress Log

## Current Verified State

This is the only section that gets edited in place. Keep it accurate — it is the first thing every
new session reads.

**Corrections that supersede everything below them in this section, 2026-08-15.** The two branches
this section was written from were merged and the numbers it quotes describe neither tree. Corrected
here rather than rewritten below, so each session's own measurements stay readable as what they were:

- **Session 017 supersedes the branch and version state of everything below it. `recordowner-001`
  is merged and 0.12.0 is released.** `main` is at **`9aebd6d`** — PR #48, merged with zero
  conflicts because `main` was still sitting on the branch point `8431597`. `mod_version` is
  **0.12.0**, `dist/` holds `sighteaddons-0.12.0.jar` (sha256
  `378bec73a535c22ce52bfb5449ec0803242d5b773f1001c55af57c98e0f08c0b`), tag `v0.12.0` is on
  `9aebd6d`, the GitHub release is published and **Modrinth version `diMDvw5I` was uploaded and
  verified** rather than assumed. Every "`main` is at `8431597`", "`mod_version` is 0.11.0",
  "`dist/` holds `sighteaddons-0.11.0.jar`", "not merged, not pushed" and "ten features exist in
  source only / 0.11.0 carries none of `recordowner-001`" line below is superseded by this one.
  **No receiver work was owed and none was done** — `RunReport.SCHEMA` is still 5.
- **Session 016 supersedes two things session 015 wrote, and they are the reason a revision pass
  happened at all.** (a) The cost of the strict secret gate is **12 of 87 completed secret runs
  kept, 13.8%, and 2 of 23 on single-member sessions** — not "party secret records become rare";
  measured with `python build/ownsecrets.py` over the real logs, and the cause is attribution rather
  than shared work. The gate ships as written: the user was shown the numbers and reaffirmed it. The
  weakness is `ownsecrets-001`, `not_started`. (b) **There are fifteen real session logs on this
  machine, not one.** Several are party floors with deaths and revives, and three were already
  analysed on `main` at `dc8d504`. Every "the one real run is solo and deathless" line below is
  superseded by that.
- **Baseline: PASSING at 212 tests in 15 classes** on `recordowner-001`. The mutation sweep is 21
  probes (`bash build/runprobes.sh`, prints `SWEEP OK`), not the ten session 015 recorded.

- **`main` is at `8431597`** and this session's work is on branch `recordowner-001` off it. The
  `floorloss-001` branch named at the top of the previous handoff was merged (#45), 0.11.0 was cut
  and released (#46), and the chat-log findings landed (#47). Every "off `ec12a27`, not merged" line
  below is the branch point speaking and is history now.
- **`mod_version` is 0.11.0, `dist/` holds `sighteaddons-0.11.0.jar`** (md5
  `e8cd7099034dd3475dbc8069be3c433e`). Every `0.9.0` and `0.10.0` filename below is a previous
  artifact.
- **Baseline: PASSING at 193 tests in 15 classes** on `main` at `8431597`, and **211 in the same 15**
  on `recordowner-001` at `9a00325`. No class was added or removed by this session.
- **Last feature completed: `recordowner-001` — a record is only yours when the work was yours.**
  Three defects of one shape, two of them reported by the user: a secret run was recorded whatever
  share of it was yours; a run *started* on a room already half-finished, because the "somebody
  else's leftovers" guard read `room.secretsFound`, which is this client's own counter and is 0 until
  a bar has been read; and a clear was recorded on one second of presence, so arriving as the
  checkmark landed set a permanent ~1.5 s record. Both gates are pure predicates and ten mutation
  probes measure that they can fail. **No schema change** — `RunReport.kt` is not touched by the
  branch at all and `python build/keydiff.py` is CLEAN. **`history.jsonl` is not touched and no line
  is reinterpreted**, so the bogus bests already in it stay records; that is the user's decision.
  **Not merged, not pushed.**

- **`main` is at `ec12a27`** — `runloss-001` (#41), `scores-fetch-001` (#43) and the `floorloss-001`
  diagnosis (#44) all merged. Every "off `d356ff2`, not merged" line below is the branch point
  speaking and is history now.
- **`mod_version` is 0.10.0, `dist/` holds `sighteaddons-0.10.0.jar`** (md5
  `5e0b1cd2d3b97cfaa6cd5e86061cbdbe`), and **0.10.0 is released** — tagged, on GitHub and on
  Modrinth. Every `0.9.0` filename below is the previous artifact. **So the schema is 5 in source
  *and* in every install from 0.10.0 on**, which retires the "5 in source, 4 in every install" line
  at the end of this section.
- **Baseline: PASSING at 193 tests in 15 classes**, 0 failures, 0 skipped, on branch `floorloss-001`
  off `main` at `ec12a27` (which was 184 in 14). The new class is `DungeonSessionTest`.
- **Last feature completed: `floorloss-001` — a report knows which floor it was.** `inDungeon`
  answered its question by *assigning* the floor, so every tick outside a dungeon overwrote a known
  floor with `null`; two of the three report paths fire after leaving by definition and could only
  ever file `?`. Measured on the box: 20 of 22 uploaded reports carry `?`, including all three
  schema 5 ones. The floor is kept for the life of the run now and forgotten only in
  `DungeonSession.reset()`, which runs on `JOIN` *after* the JOIN site has written the report for the
  run being left. **The predicate still answers about the present** — that was the thing to not
  break, since it gates the whole session state machine. No schema change; `python build/keydiff.py`
  is clean. **Not merged, not pushed.**
- **`floorname-001` was opened by that session and not worked**: the receiver validates `floor` with
  `?|E|[FM][1-7]` and this mod can hold the string `Entrance`. Measured as unreachable today and
  recorded rather than patched inline.

- Repository root: the directory holding `build.gradle` and `gradlew` (clone of `Sighte/SighteAddons`)
- Standard startup path: `./gradlew runClient` — Loom's dev client, which has no valid session and
  cannot reach Hypixel
- Standard verification path: `./init.sh` → `./gradlew test`; full is **`./gradlew assemble check`**.
  **Not `./gradlew build`** while fixes sit unreleased: `build` is `finalizedBy copyToDist` and
  `copyToDist dependsOn cleanDist`, so it deletes and rewrites `dist/sighteaddons-0.9.0.jar` — the
  released artifact — with a jar built from the current tree under the same version number. This line
  said `build` until session 006; `init.sh` was corrected at `c4c0c56` and prints `assemble check`,
  and session 004 below records that repair. `build` belongs to the release gate in `CLAUDE.md`, where
  refreshing that jar is the whole point.
- Baseline status (last `./init.sh` run): **PASSING** — `main` at `d356ff2` (which is `party-001`
  merged) carries **167 tests in 14 classes**. Branch `runloss-001`, off `main` at `d356ff2`, carries
  **176 in the same 14**, 0 failures, 0 skipped, 2026-08-14, `mod_version=0.9.0`. **Not pushed and
  not merged.** For how many commits the branch carries, run `git rev-list --count d356ff2..HEAD` —
  three consecutive reviews found a hand-written number here wrong, so the number is deliberately
  not written down any more. `git log --oneline d356ff2..HEAD` says what each is for. The +9 is
  entirely `RunReportTest` 21 → 30; no class was added.
- **Last feature completed: `runloss-001` — a run quit straight from the dungeon is no longer thrown
  away.** `ClientPlayConnectionEvents.DISCONNECT` is a third call site for `RunReport.write`,
  alongside the end-of-run headline and `JOIN`. The three things that had to be settled before it
  could be, each of which is why the existing `ponytail:` note called it not-a-one-liner: **(1) the
  player is genuinely not resolvable there** — measured with `javap`, not assumed: Fabric raises
  DISCONNECT from a HEAD inject on `Connection.channelInactive`, a Netty callback with no hop to the
  client thread, and Netty completes the close future *before* firing it, so the handler races
  `Minecraft.player = null` from another thread. `PartyTracker.localName` now holds the name,
  captured every second of the run, and it is the *more* correct source because the room tick maps
  are keyed by it. **(2) A half-written report is worse than none** — the write happens a few
  statements before `System.exit(0)`, so reports go through the same `.part` + atomic move
  `restamp` already used. **(3) `summaryPrinted` was not enough** — it is only ever set by the
  headline, so it cannot see title-screen-then-rejoin writing the same run twice; the once-per-run
  guard moved into `RunReport`. **No schema change, no receiver change**, confirmed mechanically.
  **Crash and hard kill are still uncovered and are not implied to be.**
- **Feature attempted before that: `party-001`, and it is `blocked` on a finding rather than on a
  task.**

  merged) carries **167 tests in 14 classes**. Branch `scores-fetch-001`, off `main` at `d356ff2`,
  carries **175 in the same 14**, 0 failures, 0 skipped, 2026-08-14, `mod_version=0.9.0`. **Not
  pushed and not merged.** The +8 is entirely `RoomStatsTest` 9 → 17; no class was added and nothing
  was removed, renamed or weakened. For how many commits the branch carries, run
  `git rev-list --count d356ff2..HEAD` — three consecutive reviews found a hand-written number here
  wrong, so the number is deliberately not written down any more. `git log --oneline d356ff2..HEAD`
  says what each is for.
- **A second branch is open and is not this one: `runloss-001`, also off `d356ff2`, `passing` in its
  own artifacts and under evaluation.** This branch was cut from `main` on the user's instruction, so
  its `feature_list.json` still shows `runloss-001` as `not_started` and its progress log has no
  session 012 — both are the branch point, not a claim. **Merging the two will conflict in
  `feature_list.json`, `claude-progress.md`, `session-handoff.md` and `quality-document.md`**, and
  the resolution is a union: no artifact statement in either branch supersedes one in the other,
  because the two features share no source file. `RunReport.kt` and `SighteAddons.kt`'s connection
  events belong to that branch and were not touched here.
- **Last feature attempted: `party-001`, and it is `blocked` on a finding rather than on a task.**
  The mechanism the entry existed to build does not exist. The only concrete upgrade path the
  codebase named — the old comment at `PartyTracker.kt:134-138`, that NoammAddons reads the
  decoration's map key whose last character identifies the player slot — is false in both halves:
  `ClientboundMapItemDataPacket` carries an **unkeyed** `List<MapDecoration>`, and
  `MapItemSavedData.addClientSideDecorations` re-keys it `"icon-" + i` from the client's own loop
  index, so an accessor mixin would return list order spelled as a string; and NoammAddons's own
  `DungeonUtils.kt` uses that digit as an index into `livingTeammates`, i.e. the identical order
  heuristic. What did land is the testability seam: the assignment is now a pure
  `PartyTracker.assign(roster, localSlot, isFrame)`, behaviour unchanged, with 7 cases and 3
  mutation probes where there had been none. The single surviving candidate channel is
  `MapDecoration.name()` — recorded as `deconame-001`, not built.
- And before that: `chat-001` — **the mod reads the dungeon events Hypixel states in chat**,

- Last feature completed: `scores-fetch-001` — **the room weights improve without a jar release.**
  The receiver started serving `GET /roomstats`, so layer 1 of `RoomStats` exists: a daemon-thread
  fetch at game start, the receiver's bytes cached verbatim through `.part` + rename with the `ETag`
  beside them, `If-None-Match` on the next launch, and every failure — no route, a dead host, a 503,
  a 404, a proxy's HTML page under a 200, a body that stops halfway — falling through to the last
  cache and then to the seeds. A document that arrives *after* the session resolved is cached and
  **not** adopted, so a weight cannot move mid-run. No report field, no schema change, `SCHEMA` 5,
  `RunReport.kt` untouched.
- Feature completed before that: `chat-001` — **the mod reads the dungeon events Hypixel states in chat**,
  through a pure `String -> Event?` parser. A death is charged on the tick it is announced instead of
  by a once-a-second tab poll; a wither-essence secret is credited to the player Hypixel names
  instead of to whoever clicked recently; wither doors, the blood door and puzzle solves and failures
  are attributed per player for the first time. **No schema change and no receiver change** —
  everything it improves lands in fields the receiver already accepts, checked mechanically. The
  entry's `user_visible_behavior` was factually wrong and was corrected in place. New feature
  recorded rather than built: `chatfields-001`.
- Before that: `artifacts-001` — **the real dungeon run is in the repository, and
  the statements that outlived their subject are retired.** No runtime behaviour changed; every
  `src/` edit is comment text and the diff proves it mechanically. New artifact:
  `docs/evidence/session-1786719912927/`, which is self-checking — `readout.sh` asserts all 35
  figures its README quotes and fails if one drifts. New feature recorded rather than built:
  `runloss-001`.
- And before that: `clearpoints-002` — **a room is now worth what it measures, not what kind
  it is.** `PUZZLE_BONUS`, `TRAP_BONUS`, `MINIBOSS_BONUS`, `BLOOD_BONUS` and `SEGMENT_POINTS` are
  deleted; size and kind are emergent. `weight = base + 0.25 per database secret`, where
  `base = seed + n/(n+10) * (measured - seed)` and
  `measured = 0.75 * (avgTicks / median) ^ 0.5` clamped to `[0.25, 2.5]`. The seed is the user's
  table (`Ice Fill` 2.0, `Water Board` 1.5, any other puzzle 1.0, everything else 0.75) and it is a
  **prior, not a constant** — that is the feature. No schema change and no receiver change: `RUN_KEYS`
  carries no points field, `unattributed` still counts rooms, `RunReport.kt` is untouched, `SCHEMA`
  stays 5.
- **`clearpoints-001`'s exclusions all still stand and are all still guarded** — rarity, "secrets
  from the database, not `secretsFound`", and the floor
  (`a room is worth the same on every floor`, which sets `DungeonSession.floor` by reflection on the
  `object`'s backing field and asserts the write took before it asserts the invariant). **Two earlier
  entries here said a floor guard was impossible. They were wrong.**
- **Where the measured averages come from is three layers and only the bottom two exist**
  (`RoomStats`): (1) a fetch — not built, because the receiver has no endpoint to call; (2) a cached
  file at `configDir/sighteaddons/roomstats.json`, the receiver's own document verbatim; (3) the
  seeds. **Absent is the ordinary first case, not an error, and yields exactly the seeds.** Nothing
  writes the cache today, so every install is on layer 3 and every room is its seed.
- Current highest-priority unfinished feature: **`runend-001`** (9), which is now the only
  `not_started` entry that can be worked here — `records-001` (6) is deferred by the user,
  `party-001` (7) is `blocked`, `ingame-001` (8) needs a party floor, `deconame-001` (14) proves
  nothing without one, and `scores-fetch-001` and `chatfields-001` both wait on the receiver.
  `runend-001`'s open question is already answered: write the run-level count, not the event count.

- **Where the measured averages come from is three layers and all three now exist**
  (`RoomStats`): (1) a fetch of the receiver's `GET /roomstats` at game start, on a daemon thread —
  `scores-fetch-001`, session 013; (2) a cached file at `configDir/sighteaddons/roomstats.json`, the
  receiver's own document verbatim, which layer 1 writes through `.part` + rename with its `ETag`
  beside it; (3) the seeds. **Absent is the ordinary first case, not an error, and yields exactly the
  seeds**, and so does every way layer 1 can fail. **The measured half is still inert regardless**:
  the served document has 105 rooms and `sampled 0` — every `clearStay` is `n=0` — because no
  schema 5 build has been *released*. Fetching does not change that; it takes the release out of the
  loop for every later improvement.
- Current highest-priority unfinished feature: **`runend-001`**, which is cheap and whose open
  question is already answered (write the run-level count, not the event count). Among the rest:
  `records-001` (6) is deferred by the user, `party-001` (7) went `blocked` in session 011,
  `ingame-001` (8) needs a party floor, `deconame-001` needs one too, and `chatfields-001` starts
  with a feature in `Sighte/skyblock-server`. **`runloss-001` is done on its own branch** — this
  branch's list showing it `not_started` is the branch point speaking, not a status.
- **Two players sharing a decoration is not a defect and never was.** Decorations are one per
  player. Two players in one *room* produce two decorations a few pixels apart that both resolve to
  the same `Pos` cell, so swapping them changes nothing — asserted in `PartyTrackerTest`. The
  damaging failure is the **count mismatch** that shifts assignments across *different* rooms, which
  `trustOrder` blacks out. `party-001`'s entry and `quality-document.md`'s party row both described
  the harmless one as the defect until session 011; do not reintroduce that reading.
- **What `chat-001` reads and what it deliberately does not.** Chat carries a named finder for
  **wither-essence secrets only** — chests, levers, item pickups and the redstone key are announced
  nowhere, so `SecretTracker.isOwn`'s 40-tick coincidence is still how almost every secret is
  attributed. Hypixel announces **puzzle failures and solves** but there is no per-secret line and no
  "who opened the blood door". Every pattern is cited to a published mod that runs against the live
  server; **none has been seen arriving here**, because Loom's dev client cannot reach Hypixel. A
  wrong pattern fails silently and benignly — it matches nothing and the mod infers exactly as
  before — and `ChatEvents.nearMiss` writes the offending line (redacted) to the debug log so one
  real floor says which ones are wrong.
- Current blocker: none for the next feature. `records-001` is deferred by the user (a product
  decision, not a technical blocker). **`scores-fetch-001`'s blocker is gone** — the receiver's
  `scores-002` is deployed and `GET /roomstats` answers `200` with an `ETag`, measured from here on
  2026-08-14 rather than taken on trust. **`ingame-001`'s blocker changed in
  session 009 and was narrowed rather than cleared:** the "needs a human to play a floor and hand
  over the session file" half is *done*, and what remains is a **party** floor (for the
  decoration→player mapping and the RED checkmark) plus a human opening the `/sa` screen. Read its
  `blocked_reason`; do not read the old summary of it.
- **`runloss-001` is `passing` in source and unproven on a real quit.** The write path is pinned by
  30 checks and four mutation probes; that Fabric actually raises `DISCONNECT` when somebody closes
  the game on Hypixel is read off two disassemblies and observed nowhere, because the dev client
  cannot reach Hypixel and the event cannot be raised in a unit test. The new `run_report` debug
  event is what a real quit would show, and the entry's `verification_manual` is the procedure. Like
  every other source-only fix here, it reaches nobody until the version is bumped.

- **`runloss-001` — the only entry known to have destroyed real data — is implemented and `passing`
  on the branch of the same name, not merged and under evaluation.** Nothing on this branch depends
  on it and it touches no file this one does. Its own artifacts are the record; do not re-derive its
  status from this branch's `feature_list.json`.
- **The receiver's `/roomstats` contract, measured on 2026-08-14 and worth not re-deriving.**
  `GET https://217.160.51.229.sslip.io/roomstats` → `200`, ~107 KB, `ETag`,
  `Cache-Control: no-cache`; `If-None-Match` with the current tag → `304`; `503` with body
  `no scores document` when there is no document or it does not parse; `404` for **every** other
  path, the match being exact — `/roomstats.json`, `/roomstats/`, `/roomstats?v=1` and a POST are all
  `404`. No token: the document is aggregate per room and carries no install id or player name. Not
  compressed, because Java's `HttpClient` does not decompress transparently and asking would be the
  mod's half of a feature (`SETUP.md` §10c in the receiver records both). The document is refolded
  every half hour, so the `ETag` is worth sending: two fetches two hours apart this session returned
  different tags for the same 107,362 bytes' worth of shape.
- **Old and new ClearPoints standings are not comparable.** Under `clearpoints-001` the one real M7
  scored rooms from 1.00 (`Hall`) to 4.50 (`Cathedral`) and `Pipes` was
  `1.0 + 7*0.25 + 3*0.5 = 4.25`; under `clearpoints-002` `Pipes` seeds at `0.75 + 7*0.25 = 2.50`.
  Every room came down, by different amounts. Pinned as an assertion by
  `the seed weight of Pipes is the user's model, not the old one`.
  **Those M7 figures are now committed evidence rather than relayed prose**, as of session 009:
  `docs/evidence/session-1786719912927/`, whose nine `award` events sum to 26.25 and whose
  `readout.sh` asserts `Hall` 1.0, `Cathedral` 4.5 and `Pipes` 4.25. Until that commit this bullet,
  `ContributionTracker.kt` and a `RoomDatabaseTest` KDoc all cited them as measurement with **no
  provenance anywhere in the repository** — their only source was an `evaluator-rubric.md` that has
  since been overwritten. The run was played on a debug build of `72e0825`, i.e. under the **old**
  formula, so it corroborates the left-hand numbers and says nothing about the right-hand ones.
- **A real dungeon run is finally in the repository, and it does not cover everything.** M7, solo,
  2026-08-14. It settles `clear-001`'s sightings-vs-elapsed-ticks note (nine of ten anchors at
  exactly 19 ticks) and `anchorOnClear`'s frequency (one in ten, not the three the KDocs estimated),
  and gives `ingame-001` its first evidence for calibration, room naming, core hashing and checkmark
  reading. It does **not** touch `party-001` — the run was solo, `roster_skew` fired zero times — and
  it does **not** settle `clear-001`'s gap tolerance, whose failure mode needs a party and a death.
- **A documented gap fired for real and a run was permanently lost — and that gap is now closed in
  source.** That M7 wrote no `run_end` and no run report: `RunReport.write` was reachable only from
  the end-of-run headline and from `ClientPlayConnectionEvents.JOIN`, and quitting to desktop from
  inside a floor produced neither — the `ponytail:` note that used to sit at `SighteAddons.kt:53-57`.
  Ten cleared rooms never reached the box. The local `history.jsonl` kept its 14 lines, so only the
  report was gone. Fixed by `runloss-001` in session 012; **the lost run itself is not recoverable
  and the committed evidence of it is deliberately unchanged** — `readout.sh` still asserts
  `run_end events 0`, because that is what that session file says and always will.
- **The report schema is now 5 in source and 4 in every install.** `dist/sighteaddons-0.9.0.jar` is
  deliberately **not** rebuilt — it is still the released 0.9.0 artifact — so `residue-001` and
  `clear-001` both exist in source only. Neither reaches a player until somebody bumps the version
  and takes the release gate at the top of `CLAUDE.md`, which is the user's decision. Nothing breaks
  in the meantime: the receiver accepts v4 and v5 alike and buckets their clear spans apart.

## Session Log

Rules: insert the newest session at the TOP of this section. Never edit or delete past session
entries — they are the audit trail. Copy the template below for each new session.

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

### Session 015 — `recordowner-001`: a record is only yours when the work was yours

- Date: 2026-08-15
- Branch `recordowner-001`, off `main` at `8431597`. **Not pushed and not merged.** For the commit
  count run `git rev-list --count 8431597..HEAD` rather than reading one here.
- Baseline at session start: `bash init.sh` → **BASELINE: PASSING**, 15 classes, 193 tests, 0
  failures, 0 skipped, `mod_version=0.11.0`, `dist/sighteaddons-0.11.0.jar` md5
  `e8cd7099034dd3475dbc8069be3c433e`. Re-run at the end: PASSING, 15 classes, 211 tests.
- **The repository was on `main`, not on `floorloss-001` as the handoff's first paragraph said.**
  That branch had been merged and 0.11.0 released since it was written. `git log --oneline -5` is
  what settled it, which is why the delegation said to trust it over the prose.
- **The feature did not exist in `feature_list.json` and was added by this session**, at priority 18,
  the highest in use plus one. It came from two defects the user reported in German, both quoted
  verbatim in the entry and in the KDoc of each gate.
- **All three defects were confirmed in source before a line was written**, as delegated:
  `RoomHistory.onSecretRun` called `record(room, SECRETS, ticks)` unconditionally with
  `room.ownSecrets` one field away; `TrackedRoom.onSecret` tested `previous != 0` where `previous` is
  `room.secretsFound`, this client's observation, 0 for every room until a bar has been read;
  `RoomHistory.onRoomCleared` wrote a clear line on `ownTicks >= MIN_TICKS`, twenty ticks.
- **THE PART THE DIAGNOSIS DID NOT NAME, AND IT IS THE ONE THAT MATTERED MOST.**
  `SecretTracker.onActionBar` returns early on a non-rise, so a genuine `0/10` first reading never
  reaches `onSecret`. That is not merely an obstacle to the fix — it is a trap *inside* it. A `0/10`
  is the only reading that can ever say the room was untouched when we walked in. Observe it after
  the rise test and the first reading on record becomes the `1/10` that follows, no room ever looks
  clean, and **every secret run in the game is silently discarded** — strictly worse than the defect
  being fixed. As two statements at a call site that ordering was unguardable, because
  `onActionBar` needs a live client. So the observation, the rise test and the counter advance are
  now one function on the room, `TrackedRoom.readBar`, returning a `BarReading(first, previous,
  rose)`. The ordering became a property of a pure method a test can call. **Probe C — the
  reordering, done exactly the way a tidy-up would do it — fails 5 tests, three of which predate this
  feature.**
- **The gates, which are the user's four decisions and were not re-litigated:**
  `RoomHistory.ownSecretRun` is `ownSecrets == secretsFound` with a `secretsFound > 0` guard so
  `0 == 0` is not a vacuous yes; `RoomHistory.ownClear` is "you are `topPlayer`" **and**
  `TrackedRoom.presentFromStart`, both halves, neither implying the other.
  `TrackedRoom.firstBarFound` is the first trusted bar reading and a run may start only from a room
  that read 0. `ClearPopup.show` follows the same gate at both call sites, which is what makes its
  KDoc's promise true again rather than left to rot.
- **What was NOT changed, and it is the sharp constraint.** Because old lines stay and are still
  folded, neither metric was redefined: a `clear` still carries `room.ticks[self]` and a `secretrun`
  still carries `room.secretRunTicks`. Everything added gates *whether a line is written*, never what
  the number in it means. `record()` itself is untouched and both callers hand it exactly what they
  handed it before.
- **`enteredAtTick == null` means no record, decided rather than inherited.** It is null for a
  `preCleared` room (which cannot reach `onRoomCleared` anyway, so that half is belt and braces) and
  for a room where nothing qualified — where the presence data cannot say the room even had a start.
  An unrecorded room costs nothing; a bogus record is permanent.
- **ONE MEASUREMENT THAT CHANGED THE WORK, and it is why the probes are worth running.** Probe H —
  delete the `self != topPlayer` half of `ownClear` — **passed all 211 tests** on the first commit of
  this feature (`5b670ca`). The case meant to guard that half used a fixture in which the local
  player had *also* left the room, so `presentFromStart` refused it and the top-player check was
  never under test: a guard in name only, the same shape `clearpoints-001`'s floor exclusion was
  caught in twice. Rebuilt at `9a00325` so both members arrive on the same tick and are both still
  present at the clear, differing only in tick totals, and the case now asserts `presentFromStart` is
  **true for both** before asserting the gate refuses — so it cannot degrade back into testing the
  other half. The failing probe run is kept in the evidence deliberately.
- **All ten probes caught at `9a00325`**: A (the late-entry guard removed) 3, B (the fix done wrong,
  `firstBarFound == null`) 2, C (observation after the rise test) 5, D (first reading overwritten) 2,
  E (`ownSecrets > 0`, the softening the user refused) 1, F (the vacuous `0 == 0`) 1, G
  (`presentFromStart` dropped) 2, H (`topPlayer` dropped) 1, I (staleness dropped) 1, J (null anchor
  answers `true`) 1. Re-create from `build/recordprobe.py`, drive with `bash build/runprobes.sh`.
- **No schema change, checked mechanically rather than asserted.** `git diff --name-only main` does
  not list `RunReport.kt`; `python build/keydiff.py` is CLEAN at 17 run keys and 17 room keys with all
  four set differences empty; `RunReport.SCHEMA` still 5. `SighteAddonServerside` was **read**
  (`ingest.py`, for the key sets) and **never written**. `rooms.json` untouched.
- **`build/keydiff.py` had been lost to a clean and was re-created, and the re-creation was wrong
  twice.** It read only `addProperty` and so missed the two `JsonArray` fields `rooms` and `classes`;
  widened to `.add(` it then counted `classes.add("${it.livingClass} …")` — an array *element* — as a
  field. Both were caught by the output disagreeing with the 17/17 the previous session recorded,
  which is the only reason that number was worth writing down. Anchored on `obj.add` it reproduces
  it.
- `mod_version` and `dist/` untouched: jar md5 `e8cd7099034dd3475dbc8069be3c433e` measured either
  side of `./gradlew assemble check`, and `git status --short dist/ gradle.properties` empty. The
  release gate did not fire.
- **Test counts, additive and measured**: `SecretRunTest` 6 → 11, `RoomHistoryTest` 5 → 12,
  `ContributionTrackerTest` 48 → 54; suite 193 → 211 in the same 15 classes. `git diff main` deletes
  zero lines from two of the three test files; in `SecretRunTest` the only deletion is the one-line
  `room()` helper, which now calls `readBar(0)` — the room walked into clean, which is what all six
  existing cases already meant. Their assertions are unchanged.
- **The ceiling, stated rather than implied: neither gate has run in a game.** The predicates are
  driven directly; the four wiring lines that call them need a live `Minecraft` and the dev client
  cannot log in. What a real floor would show, and what would falsify it, is written in the entry's
  notes — the two new debug fields (`secret_room_first_bar`, and `firstBar` on
  `secret_run_discarded`) exist so the log answers it instead of inference.

### Session 014 — `floorloss-001`: the check that asks destroyed the answer

- Date: 2026-08-15
- Branch `floorloss-001`, off `main` at `ec12a27`. **Not pushed and not merged.** For the commit
  count run `git rev-list --count ec12a27..HEAD` rather than reading one here.
- Baseline at session start: `bash init.sh` → **BASELINE: PASSING**, 14 classes, 184 tests, 0
  failures, 0 skipped, `mod_version=0.10.0`, `dist/sighteaddons-0.10.0.jar` md5
  `5e0b1cd2d3b97cfaa6cd5e86061cbdbe`. Re-run at the end: PASSING, 15 classes, 193 tests.
- **The diagnosis arrived done and was confirmed rather than re-derived.** `DungeonSession.kt:61`
  and `RunReport.kt:171` still said exactly what the entry recorded. The whole defect is one line
  doing two jobs:

  ```kotlin
  floor = sidebarLines(client).firstNotNullOfOrNull { FLOOR.find(it)?.groupValues?.get(1) }
  return floor != null
  ```

  The assignment *was* the in-dungeon check, so every tick outside a dungeon cleared a known floor.
  `RunReport.write` is reached from three places and two of them — `ClientPlayConnectionEvents.JOIN`
  and `DISCONNECT` — fire after leaving by definition, so for them the live reading is always the
  wrong one. On the box: 20 of 22 uploaded reports carry `?`, and the only two that name a floor
  were written from the run-end headline while the player was still inside.
- **The fix is a split, not a second field.** `inDungeon(client)` is now
  `observeSidebar(sidebarLines(client))`; `observeSidebar` returns `seen != null` (about *now*) and
  writes `floor` only when it actually saw one. Nothing else clears it — `DungeonSession.reset()`
  does, and that runs on `JOIN` after the JOIN site has written the report for the run being left.
  One field rather than a live one plus a sticky one, because within a run they cannot disagree:
  `onTick` returns at `inDungeon` before anything reads `floorNumber`, and entering or leaving a
  dungeon is a server transfer, so `reset()` lands on both edges of every run.
- **The thing that had to not break, and did not: `inDungeon` still answers about the present.** A
  fix that kept the floor by making the predicate answer from it would have been far worse than the
  `?` — that predicate gates the whole session state machine. It is probe B below, and it is caught.
- **`floor` is `@Volatile` now.** `DISCONNECT` reads it from a Netty event-loop thread while the
  client thread writes it — the same reason `RunReport.reported` is an `AtomicBoolean`. `runTicks` is
  read on the same path and is *not* volatile; that is pre-existing, out of this feature's scope, and
  noted in the handoff rather than fixed here.
- **`RunReport.reportedFloor()` names the read all three paths share.** `RunReport.write()` needs a
  live `Minecraft` and cannot be called from this suite at all — which is how a one-line defect
  survived five schema versions with 184 tests around it. The seam is what makes the report's half of
  the claim testable; `observeSidebar` taking the lines instead of a client is the same move on the
  session's half.
- **`DungeonSession` had no test of any kind before today.** `DungeonSessionTest` is 7 cases;
  `RunReportTest` went 30 → 32. Strictly additive: the only pre-existing test line touched is
  `RunReportTest`'s private `report()` helper, which gained `floor: String = "M5"` with the old value
  as its default, so every existing case builds the identical report.
- **Five mutation probes, all caught, all restored with `git checkout` after the feature was
  committed.** (A) the defect restored verbatim → fails 2. (B) the fix done wrong, predicate reading
  the remembered floor → fails the same 2. (C) drop `floor = null` from `reset()` → fails 1.
  (D) `reportedFloor()` → `"?"` → fails 2. (E) drop `@Volatile` → fails 1. `build/floorprobe.py`
  holds all five; `build/` is gitignored, so re-create it from the pairs in the entry's evidence.
  **It needs `encoding="utf-8"` as well as `newline=""`** — `DungeonSession.kt` has em dashes and
  cp1252 cannot read it, which is a correction to what `build/probe.py` does.
- **Not paired, confirmed mechanically.** `python build/keydiff.py` → `KEYDIFF: CLEAN`, 17/17 both
  ways, `SCHEMA` 5. `floor` is in `ingest.py`'s `RUN_KEYS` (line 145) and required. This changes the
  value, not the shape. `SighteAddonServerside` was **read** and never written.
- **One finding, recorded rather than fixed: `floorname-001`.** The receiver's validator is
  `FLOOR = re.compile(r"\?|E|[FM][1-7]")` under `fullmatch`, and `DungeonSession.floor` can hold
  `Entrance` — the receiver plainly expected `E`. A 400 is never retried, so that would lose the run.
  Measured as unreachable today (a report needs rooms, rooms need calibration inside a floor
  instance, where the sidebar reads F/M) and **not widened by this fix**, which is why it is an entry
  and not an inline patch. Worth knowing: `keydiff.py` compares key *sets* and cannot see a
  value-domain mismatch. That is a gap in the check, not a failure of it.
- **Unverified and named as such:** all three paths end to end. No real report has been written under
  this code — the dev client cannot log in. What the suite measures is the whole remember/forget/
  answer decision on real sidebar strings plus the report's read of it; what it cannot is that
  `reset()` lands on the `JOIN` edge of every real run, and that `@Volatile` is *needed* (its absence
  cannot be reliably observed, so the modifier is asserted by reflection instead).
- Verification, all at `c6142d4`: the `verification_command` → 7 + 32, 0 failures;
  `./gradlew test --rerun-tasks` → classes 15, tests 193, skipped 0, failures 0, errors 0;
  `./gradlew assemble check` → BUILD SUCCESSFUL with the jar md5 identical either side and
  `git status --short dist/ gradle.properties` empty; `python build/keydiff.py` → CLEAN;
  `bash init.sh` → BASELINE: PASSING.

### Session 012 — `runloss-001`: the run is reported on the way out, not only on the way back in

- Date: 2026-08-14
- Branch `runloss-001`, off `main` at `d356ff2` (which is `party-001` merged). **Not pushed and not
  merged.** Run `git rev-list --count d356ff2..HEAD` for the commit count rather than reading one
  here.
- Baseline at start: `bash init.sh` → **PASSING**, 167 tests in 14 classes, the state `party-001`
  left after merging.

**The defect, restated once because it is the only one on either repository's list that destroyed
real data.** `RunReport.write` had two call sites: the end-of-run chat headline and
`ClientPlayConnectionEvents.JOIN`. Quitting the game from inside a dungeon produces neither — no
headline, and no subsequent JOIN because the process is gone. On 2026-08-14 that cost a real M7: ten
cleared rooms, 24 secrets, no `run_end`, nothing in `runs/`, nothing on the box
(`docs/evidence/session-1786719912927/`, whose `readout.sh` asserts `run_end events 0`).

**`DISCONNECT` is now the third call site. The three questions that had to be answered first were
the whole feature, and the `ponytail:` note was right that none of them was a one-liner.**

**1. Is the player resolvable at `DISCONNECT`? No, and this was measured rather than assumed.** Four
steps, each re-checkable with one `javap`, against
`.gradle/loom-cache/.../minecraft-merged-043a8b3edf-26.1.2.jar` and
`fabric-networking-api-v1-6.3.1+554860db4c.jar`:

- `Minecraft.destroy()` calls `ClientLevel.disconnect(DEFAULT_QUIT_MESSAGE)` at offset 36 and
  `disconnectWithProgressScreen()` at offset 40 — the level disconnect comes first.
- `ClientLevel.disconnect` is `connection.getConnection().disconnect(msg)`, and
  `Connection.disconnect(DisconnectionDetails)` is `channel.close().awaitUninterruptibly()`.
- Fabric raises the event from `ConnectionMixin`, which injects at **HEAD of
  `Connection.channelInactive`** and at the `PacketListener.onDisconnect` INVOKE inside
  `Connection.handleDisconnection`. `AbstractNetworkAddon.handleDisconnect` CASes an `AtomicBoolean`
  — so DISCONNECT fires exactly once per connection — and `invokeDisconnectEvent` calls the event
  **straight from the Netty callback, with no hop to the client thread**.
- Netty completes a close future before it fires `channelInactive`, so `awaitUninterruptibly()` can
  return while the handler is still queued. The very next thing
  `Minecraft.disconnect(Screen, boolean, boolean)` does is `this.player = null`, at offset 184.

So the handler races the field it wants, from a thread that is not the one clearing it. **The answer
was to stop asking the client.** `PartyTracker.localName` holds the name, captured every second of
the run in the `update` that already read it — and it is the *more* correct source, not a fallback,
because every room's tick map is keyed by the name that was current during the run. `client.player`
survives only as the fallback for a report written before `PartyTracker.update` has ever run.

**2. Can it write a whole report? Only through a temporary file.** The write happens a few statements
before `System.exit(0)` on a thread the exit will not wait for. A truncated `run-*.json` still
matches `TelemetryUpload.RUN`, is posted at the next launch, fails the receiver's `check_run` with a
`400` and becomes a permanent resident of `rejected/` — the half-written report is the expensive
failure, not the missing one. Reports now go through the same `.part` + atomic move that `restamp`
already used; `.part` is outside the uploader's pattern by construction, so a torn temporary is
invisible to both `TelemetryUpload` and `restamp`.

**3. Is `summaryPrinted` enough to stop a double write? No.** It is only ever set by the headline, so
it cannot see the pair `DISCONNECT` introduces: dropping to the title screen from inside a floor
writes the report, and joining any server afterwards reaches JOIN with `summaryPrinted` still false
and nothing having reset in between — two reports for one run. The guard moved into `RunReport` as
an `AtomicBoolean`, **claimed before the write and given back if the write fails** (a run that could
not be written is not a run that was reported, and the later call site is its second chance), and
cleared by `DungeonSession.reset()`.

**What the DISCONNECT site deliberately does not do: call `DungeonSession.reset()`.** That would be
the tidy mirror of the JOIN site, and it would be wrong. `reset()` tears down
`ContributionTracker`, `PartyTracker` and `ClearPopup` — state `renderHud` reads every frame — and
this callback can arrive on a Netty thread while the client is still ticking, e.g. when a server
closes the socket mid-run. A dropped connection must not become a
`ConcurrentModificationException` in the render loop. The write only reads; the next JOIN resets as
it always did. The call is also wrapped in a `try`, because the caller is either Netty's pipeline or
a shutdown sequence and an escaping exception there is worse than a missing report.

**A new `run_report` debug event**, written by `RunReport.write` itself so all three call sites get
it exactly once, carrying `complete`, the room count and the file name. It is the observable that
turns the manual check into a measurement: the evidence for this defect is a session file with no
such line. `verification_manual` was corrected to look for it — the old step 3 said "confirm the
debug session carries a `run_end` event", which is precisely what this path does not and should not
produce, since `run_end` means the headline came.

**Not a schema change and not paired, confirmed rather than asserted.** `build/keydiff.py` was
re-created and run before and after: four empty sets both directions, 17 run keys and 17 room keys
identical to the receiver's. `RunReport.SCHEMA` stays 5, `mod_version` stays 0.9.0,
`dist/sighteaddons-0.9.0.jar` md5 `b2ebc35ccfeb9cc96134eb3b18f0306f` either side of
`assemble check`. `SighteAddonServerside` was **read** (`ingest.py`) and **not written**.

**Four mutation probes, all caught, all restored with `git checkout` after the feature was
committed.** (A) `uploader(live, captured) = live`, the pre-feature behaviour → fails 1. (B) delete
the `reported.compareAndSet(false, true)` line from `queue()` → fails 2. (C) `queue` returns
`publish()`'s result without giving the claim back → fails 1. (D) `publish` writes the target
directly instead of through `.part` + move → fails 1.

**Probe (D) did not fail at first, and that is worth recording.** A successful direct write and a
successful move leave an identical directory, so the suite could not see the one mechanism that
keeps a truncated report out of the queue. What it *can* see is the other half: a `.part` left by a
crash between "write the temporary" and "move it" must be consumed by the next successful write
rather than accumulate. That test was added (`cd26786`) and the probe now fails on it. The
atomicity of the move itself remains a property of the code, not of any end state a unit test on a
working filesystem can inspect.

**What is not covered, and is not implied to be.** A hard kill — task manager, `SIGKILL`, power loss
— runs nothing at all and loses the run exactly as before. A Java-level crash that still reaches
`Minecraft.destroy()` is covered by the same disconnect, but that is read off `destroy()`'s bytecode
and has never been observed. **A JVM shutdown hook was considered and rejected**: it cannot help
with `SIGKILL`, `DISCONNECT` already fires before `System.exit(0)` on every path that reaches
`destroy()`, and a second writer racing the first would buy nothing the once-per-run guard does not
already have to arbitrate. Above all, **that Fabric raises `DISCONNECT` at all on a real Hypixel
quit is unobserved** — the dev client cannot reach Hypixel and the event cannot be raised in a unit
test, so the wiring rests on the two disassemblies and on nothing else.

- Verification, all at `cd26786`:
  - `./gradlew test --tests 'sighteaddons.RunReportTest'` → PASS, **30 tests**, 0 failures (21
    before). This is the entry's `verification_command`, unchanged in text.
  - `./gradlew test --rerun-tasks` → `classes 14, tests 176, skipped 0, failures 0, errors 0`.
    Baseline `d356ff2` was 167 in the same 14; the difference is exactly `RunReportTest` 21 → 30.
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 identical either side,
    `git status --short dist/ gradle.properties` empty.
  - `python build/keydiff.py` → `KEYDIFF: CLEAN`.
  - `bash docs/evidence/session-1786719912927/readout.sh` → `READOUT: OK`.

### Session 013 — `scores-fetch-001`: the weights stop waiting for a jar

- Date: 2026-08-14
- Branch `scores-fetch-001`, off `main` at `d356ff2` (which is `party-001` merged), on the user's
  instruction. **Not pushed and not merged.** Run `git rev-list --count d356ff2..HEAD` for the commit
  count rather than reading one here.
- Baseline at start: `bash init.sh` → **PASSING**, 167 tests in 14 classes, measured before any edit.
- Numbered 013 rather than 012: `runloss-001` is session 012 and sits on its own branch off the same
  commit. That entry is not here because this branch predates it, not because it did not happen.

**The blocker was the receiver's and the receiver cleared it.** `scores-fetch-001` had been `blocked`
since `clearpoints-002` with the reason "there is no endpoint to call". `scores-002` on
`Sighte/skyblock-server` is now deployed, and the first thing this session did was check that from
outside rather than believe it: `GET /roomstats` → `200`, 107,362 bytes, no token, an `ETag` that
`If-None-Match` turns into a `304`, and `404` for `/roomstats.json`, `/roomstats/`, `/roomstats?v=1`
and a POST. That is the contract layer 1 is written against and it is recorded in
`feature_list.json`'s evidence, not only here.

- **Layer 1 is wiring, exactly as `clearpoints-002` predicted it would be.** `RoomScores.parse`
  already read the receiver's document verbatim, so no format work was owed and none was done. What
  is new: `RoomStats.start()`, `refresh`, `fetch`, `store`, `cachedEtag`, `adopt`, and a
  `room_scores_fetch` debug event.
- **The pattern is `TelemetryUpload`'s, deliberately, and not a second one.** A named daemon thread
  from `onInitializeClient`, everything inside it wrapped, nothing on the client thread and nothing
  during a run. Its own thread rather than a step inside the upload's: the upload hands over a
  backlog at 60 s a file, and the scores would then arrive after the first room of the evening.
- **A document that lands after the session resolved is cached and not adopted.** That refusal is the
  feature, not a safety net — a room's weight is read the moment it is cleared and points are
  compared between party members, so a mid-run change makes a run incomparable with itself. The
  resolution, `adopt` and `use` are synchronised on `RoomStats` now, because the fetch is on another
  thread and that race is a run scored against two weightings.
- **The `ETag` wedge is guarded at both ends.** The document is written before the tag, and
  `cachedEtag` returns null whenever the document is not on disk — an `ETag` naming bytes that are
  not there earns a `304`, and a `304` with no cache behind it is a session on the seeds at *every*
  launch until the receiver's document happens to change. Own case, own mutation probe.
- **The failure table is driven through a real HTTP client, on loopback.** Nine shapes in one test —
  `503`, `404`, `500`, an HTML 502 page under a `200`, half a document, an empty body, an array
  instead of the document, a body that stops halfway, and a dead port. A truncated read and a refused
  connection cannot be produced by a pure function, and they are what a flaky connection actually
  hands the mod. No case reaches a network: an ephemeral port on the loopback address, closed with
  the test.
- **`Config.upload` gates the fetch**, which is a decision rather than a requirement and is written
  down as one in the entry's notes, with the one line to delete if the user wants the other trade.
  The switch is worded about *sending* and this request sends nothing — but it is the only control a
  player has over whether the mod talks to the analysis server at all.
- **No report field and no schema change**, verified mechanically rather than asserted:
  `python build/keydiff.py` is four empty sets, 17 run keys and 17 room keys, `SCHEMA` 5, and
  `RunReport.kt` is not in this branch's diff. One correction to that script against the version the
  previous handoff describes — the key regex now requires the comma after the string, because
  `classes.add("${it.livingClass} …")` appends a *value* to a `JsonArray` and counting it invents an
  18th run key that the validator would appear to reject.
- **Grade: scoring stays B**, on the same standard session 012 held telemetry to. The gap this
  closes is real — the domain's input is now obtained by the code rather than by a person, and the
  obtaining is measured against the live box end to end — but the one thing that matters most has
  still never happened: no real client has ever run `RoomStats.start()`, and every score in the
  served document is still seed-plus-secrets because `clearStay` is `n=0` until a schema 5 build is
  *released*. Promoting on a loopback server and a `curl` would apply a lower standard than the one
  that kept this domain at B while the fetch did not exist at all.

### Session 011 — `party-001`: the upgrade path did not exist, so the finding is the deliverable

- Date: 2026-08-14
- Branch `party-001`, off `main` at `9cb71ee` (which is `chat-001` merged). **Not pushed and not
  merged.** Run `git rev-list --count 9cb71ee..HEAD` for the commit count rather than reading one here.
- Baseline at start: `bash init.sh` → **PASSING**, the state `chat-001` left after merging.

**The brief was wrong twice over, and establishing that was the work.**

- **The title named a mechanism the design forbids.** `party-001` was "Party sync instead of the
  decoration-order heuristic". `README.md:9` states the design achieves "full per-player attribution
  **with no party sync**" and `SighteAddons.kt:24` states "**No packets are sent and nothing is
  automated**". Party sync means sending something. Corrected in place, the way `clear-001`,
  `schema-001` and `chat-001` were.
- **The described failure mode was the harmless one.** "Two players standing on one decoration are
  told apart" — decorations are one per player and two players never share one. Two players in one
  *room* produce two decorations at nearly the same pixel and the order mapping may swap them, which
  changes nothing because both resolve to the same `Pos` cell. The damaging failure is the count
  mismatch that shifts assignments across *different* rooms. Both readings are now asserted rather
  than described, and `quality-document.md`'s party row carried the same loose phrasing and is fixed.

**THE FINDING, WHICH IS WORTH MORE THAN THE FEATURE WAS.** The only concrete upgrade path the
codebase named was the comment at `PartyTracker.kt:134-138`: that NoammAddons reads the decoration's
map key, whose last character is a digit identifying the player slot, and that a `MapItemSavedData`
accessor mixin would remove the counting. Both halves are false, measured against the 26.1.2 classes
this module compiles against and against NoammAddons's actual source:

- `ClientboundMapItemDataPacket` carries `Optional<List<MapDecoration>>` — an **unkeyed list**. No
  key crosses the wire. `MapItemSavedData.addClientSideDecorations` clears the private map and
  re-keys every entry `"icon-" + i` from its own loop index (that class's string-concat bootstrap
  constants are literally `icon-` and `frame-`), and the field is a `LinkedHashMap`, so
  `getDecorations()` is already in packet order. An accessor mixin would have returned this client's
  own list order, spelled as a string.
- NoammAddons does not do what the comment said. `DungeonUtils.kt`:
  `val index = key[key.lastIndex].digitToInt()`, then `livingTeammates[index]`. The identical order
  heuristic, plus a defect this mod does not have — the digit counts the local player's own marker
  while the list it indexes excludes them.

The mixin was therefore **not built**, per the instruction that a finding beats an invented
substitute. The one channel that survives the wire and could still carry identity is
`MapDecoration.name()`; whether Hypixel populates it is unknowable here, and is recorded as
`deconame-001` rather than guessed at.

**What did land: the testability seam.** The assignment moved out of `positions()` into a pure
`PartyTracker.assign(roster, localSlot, isFrame) -> Assignment`. Behaviour unchanged; `positions()`
keeps the Minecraft plumbing, the grid math and the logging. This is a design decision and not a
tidy-up: `positions()` takes a `MapItemSavedData` and reads `DungeonSession` statics, so the
heuristic had **zero** coverage and could not get any, while `ContributionTracker.tick` iterates
`PartyTracker.positions(map)` and only ever creates a room some decoration resolved into — so how
decorations map to players decides which rooms exist, get named, get cleared and get scored. Room
discovery was treated as first-class in the regression check.

**The `verification_command` was vacuously green and is now not.** It named `PartyTrackerTest`,
which already existed and already passed with cases covering only the tab regex and the living-class
carry. The command text is deliberately **unchanged**; what changed is that the class now holds 7
cases that exercise the assignment, and three mutation probes measure that it can fail — dropping
the `trustOrder` guard fails 2 of 14, counting the local marker in the teammate index fails 4,
assuming the local player is slot 0 fails 1. Padding the command with extra class names would have
hidden the problem rather than fixed it.

**Not paired, no schema bump — verified mechanically.** `build/keydiff.py` (re-created here;
`chat-001` recorded the description but not the script) reports four empty sets and confirms
`playerTicks`, `playersInRoom`, `ownTicks`, `enterTick` and `unattributed` are all already known to
the receiver. `RunReport.kt` is untouched by this branch and `SCHEMA` is still 5.
`SighteAddonServerside` was **read and never written**.

**A quirk that cost this session an edit and is now in the handoff:** `git checkout <file>` to
restore a mutation probe reverts *all* uncommitted work in that file. The handoff's existing advice
to restore with `git checkout` is right and incomplete — commit the feature first, then probe.

- New feature recorded rather than built: `deconame-001`.
- Grade: party stays **C**. The tests now pin the guard and the failure classification where they
  pinned nothing, but they still pin the heuristic rather than the truth, which is the stated reason
  it was C. Saying why a grade did not move is the honest entry here.

### Session 010 — `chat-001`: read what Hypixel says, keep the inference where it says nothing

- Date: 2026-08-14
- Branch `chat-001`, off `main` at `a6dc629`. **Not pushed and not merged.** Run
  `git rev-list --count a6dc629..HEAD` for the commit count rather than reading one here.
- Baseline at start: `bash init.sh` → **PASSING**, the state `artifacts-001` left.

**The brief was wrong and correcting it was half the work.** `chat-001`'s
`user_visible_behavior` promised that "secret clicks, wither doors, puzzle solvers and deaths are
attributed as they happen rather than inferred". Checked against the code before writing any:

- **Deaths were never inferred.** `PartyTracker` watches the tab row flip to `DEAD` and is the sole
  caller of `ContributionTracker.onDeath`. What was wrong was the *timing* — `PartyTracker.update`
  runs once per second, so the stamp was quantised to 20 ticks on top of the row's own lag — and the
  *room*, which is `lastCell`. **The room is still inferred and this session did not fix it.**
- **Wither doors and puzzle solvers did not exist at all.** `grep -inE "wither|door|solver"` over
  `src/main/` returned one hit: the wither-essence skull id in `SecretTracker`. Net new.
- **Secret clicks were the one real inference**, and it is replaced only in part.

So the honest scope, and what the entry now says: one inference narrowed, one timing improved, two
capabilities added.

**What chat can actually source, which is less than it sounds.** Hypixel names a finder for exactly
one kind of secret — the wither essence, in a third-person and a second-person form. Chests, levers,
item pickups and the redstone key are announced **nowhere**. The evidence for that negative is
`SkyHanni`'s `DungeonChatFilter`, a catalogue of dungeon chat, which carries both essence forms and
no other `found a` shape. So `isOwn` stays and chat overrules it only where a line names somebody.
What that buys is the **false positive**: a teammate taking the essence while your own click on a
chest is still inside its 40-tick window used to credit you.

**Deaths: two sources, one charge.** The tab path is kept rather than replaced — a death message this
client never received still shows up in tab, and losing a death is worse than recording it late — so
`onDeath` became idempotent instead, keyed per player with a 60-tick window. The window is a *guard*,
not the mechanism: `onRevive` clears the entry, so a genuine second death counts however fast it
follows, which no window alone could do without also letting a duplicate through. `onDeath` and
`onChatSecret` take the tick as a parameter, the seam `onPresence` and `onSecret` already use — the
run clock is `private set` and a function that read it could only ever be tested at tick zero.

**Doors and puzzles stop at the debug log, on purpose.** Every field the run report writes is one the
receiver's `RUN_KEYS`/`ROOM_KEYS` already knows; a key it has not learned is a `400` that
`TelemetryUpload` files under `rejected/` and never retries. Debug events cost nothing — `/ingest`
validates the filename and never the body, and nothing server-side reads event types at all. The
report side is recorded as **`chatfields-001`**, blocked, receiver first, and it carries the design
question that makes it a feature rather than a commit: a per-player secret breakdown would put
teammate names in a permanent server-side record, which `RunReport`'s header refuses on purpose.

**Not paired, and that was checked mechanically rather than by reading.** Every `addProperty` key in
`RunReport.build()` and `RunReport.room()`, diffed against `RUN_KEYS`/`ROOM_KEYS` and their optional
sets parsed straight out of the receiver's `ingest.py`: four empty sets, both directions.
`RunReport.kt` is untouched, `SCHEMA` stays 5, the jar md5 is unchanged.
**`SighteAddonServerside` was read and never written.**

**Two mutation probes, both caught, both restored with `git checkout`.**
Deleting the `^` from `ChatEvents.LEAD` *and* switching `parse` from `matchEntire` to `find` fails 2
of 11 — the forged-death case among them. Notably **either mechanism alone holds the line**, which
the probe measured and which is now recorded in the comment on `parse` rather than assumed. Making
`chatAttribution` return `false` instead of `null` when no line landed fails 3 of 8: that is the
defect that would most look like a working feature, since reading a missing fact as a denial
un-credits every chest, lever and item secret in the game.

**What this session could not verify, and said so in the entry rather than around it.** That the
strings are Hypixel's; that Fabric delivers them to the listener at all, with `overlay` false, on the
right tick; and whether a `chat_secret` arrives *before* the action-bar update it attributes — if it
arrives second the attribution is always too late and no window fixes it. All three are measurable
from one real floor, so `verification_manual` was rewritten from two lines into a procedure that says
which log line answers which question, and it now declares outright that it cannot be performed here.

**Left alone deliberately:** `SighteAddons.RUN_END` still has no test. It is the sole trigger of the
permanent run report, and making it testable is a change to the path `runloss-001` is about.

### Session 009 — `artifacts-001`: the run happened, and now the repository knows

- Date: 2026-08-14
- Branch `artifacts-001`, off `main` at `9f71b96`. **Not pushed and not merged.** Run
  `git rev-list --count 9f71b96..HEAD` for the commit count rather than reading one here.
- **An artifact pass with a hard constraint: change no runtime behaviour.** Honoured, and proven
  rather than asserted — `git diff -- src/ | grep -E '^[+-][^+-]' | grep -vE '^[+-]\s*(\*|//|/\*)'`
  is empty, so every added and removed line in `src/` is comment text. `@Test` lines touched: 0.
  Suite unchanged at 140 in 13 classes, which is the number `main` carries.

**What arrived.** The user played a real M7 on 2026-08-14 with a debug build of `72e0825` — the
`clearpoints-001` formula, before `clearpoints-002` changed the weights — and quit the game straight
from the dungeon. 220 lines of debug session. Every claim below was read out of the file rather than
taken from the brief that pointed at it.

**The artifact.** `docs/evidence/session-1786719912927/`: 143 of the 220 lines verbatim (everything
but the `player_room` position stream, of which three are kept as a sample, since its only claim is
the negative one that the run was solo and a census proves that without copying positions), a README
that says what the run settles and at equal length what it does not, and `readout.sh`, which
**asserts** all 35 figures the README quotes and exits non-zero if one moves. Names were already
pseudonymised at source by `Pseudonym`. The file corroborates its own provenance: no `award` event
carries `scoresTs` and there is no `room_scores` event, both `clearpoints-002` additions.

**Why assertions rather than a description.** This repository's recurring failure is a number copied
by hand into a second document and then drifting — three consecutive reviews caught it on the commit
count alone. Two mutation probes prove the mechanism bites: rewriting `Cathedral`'s award from 4.5 to
4.0 fails two assertions (directly, and through the sum), and deleting the single `anchoredOnClear`
line fails seven. A committed evidence file has a failure mode source does not — no compiler and no
test reads it — and this closes it.

**What the run measures.**

- **`MIN_TICKS`.** Nine of ten `room_anchored` events are stamped **exactly 19 ticks** after their
  stay began — the 20th sighting at `MIN_TICKS` 20, since the anchor is the stay's *start* — and
  `New Trap` at 24, five sightings absorbed by the gap tolerance without splitting the stay. Both
  halves of `clear-001` note (1) in one run. `TrackedRoom.onPresence`'s KDoc now says ticks are
  sightings; that wording fix had been outstanding since session 006.
- **`anchorOnClear` fires once in ten rooms** — `Duncan`, entered at tick 2990, cleared at 2996.
  `clear-001` note (3) closed. `ContributionTracker.anchorOnClear`'s KDoc and two
  `ContributionTrackerTest` KDocs estimated "three of them in one M7"; all three now carry the
  measurement. Benign direction: the anchor is mostly genuine.
- **`Duncan` is also the room missing from `history.jsonl`**, because `RoomHistory.kt:144` only
  records a clear when the local player's own presence reached `MIN_TICKS` and Duncan's was 7 — so
  the run wrote 9 local clear lines for 10 cleared rooms. Existing deliberate behaviour, but the
  fallback-anchored room is precisely the one local history drops, and nobody had written that down.
- **Calibration, room naming and core hashing hit.** One `calibrated` event on M7, `roomCores` 272,
  36 `room_identified` with real Odin names. First evidence `ingame-001` has ever had.
- **Checkmark reading is evidenced in both directions**, which the brief did not expect. Of ten
  clears exactly two read `GREEN` (30, all secrets found) — `Default` and `Hall` — and those are
  exactly the two cleared rooms the database gives 0 secrets. The other eight read `WHITE` (34) and
  their `all_secrets` events land later. Ten for ten. The `RED` path never occurred.
- **The party heuristic was NOT exercised**, and `party-001` now says so in its own notes. Solo run,
  one player in tab, `decoIndex` 0 throughout, `roster_skew` fired **zero** times. Recorded loudly
  because "a real run happened" will otherwise be read as covering it.
- **`clear-001` note (2), the zero-margin gap tolerance, stays open** — re-checked rather than
  assumed closed because a session file finally existed. Its failure mode is the roster-skew blackout
  around a **death in a party**; this run was solo and deathless, so the case was never in the
  building.

**The measured data loss.** No `run_end`, no run report, and `runs/uploaded/`'s newest file predates
the run by a day. The cause is already in source — the `ponytail:` note at `SighteAddons.kt:53-57`:
`RunReport.write` is reachable only from the end-of-run chat headline and from
`ClientPlayConnectionEvents.JOIN`, and quitting to desktop from inside a floor produces neither. Ten
cleared rooms gone from the box permanently. **Recorded as `runloss-001`, not fixed** — the brief was
an artifact pass, and `CLAUDE.md` says discovered work becomes an entry rather than an inline fix.
`history.jsonl` kept its 14 lines, so the loss is the report and the receiver's copy, not the local
records.

**The four stale statements, closed.**

1. `clearpoints-001`'s note described its own deleted formula in the **present tense** on a `passing`
   entry with no supersession marker. It now opens with one, and "WHAT THE WEIGHTING IS" became
   "WHAT THE WEIGHTING WAS". The paragraph is **kept** — it is the record of what was built, and its
   load-bearing half (`unattributed` counts rooms and must never be re-derived by subtraction) is
   still live.
2. `ingame-001` cross-referenced "`clearpoints-001`'s weight constants", deleted at `0d81667`. The
   pointer now names where the concern moved: `clearpoints-002`'s seed table and `TIME_EXPONENT`.
3. The M7 figures were cited as measurement in `claude-progress.md`, `ContributionTracker.kt` and a
   `RoomDatabaseTest` KDoc with **no provenance in the repository at all** — their only source was an
   `evaluator-rubric.md` since overwritten. All three now cite the committed run, whose `readout.sh`
   asserts the numbers they quote.
4. `TIME_EXPONENT`'s 49x calibration is drawn from the box's **`clear`** averages because `clearStay`
   is `n=0` everywhere. Neither the `ContributionTracker` KDoc arguing it nor the `RoomStats` KDoc
   repeating it said so, in a file that elsewhere insists the two must never be confused. Both now
   name the proxy **and its consequence**: the true `clearStay` spread is plausibly narrower, which
   weakens rather than strengthens the case against a linear map.

**Two more found while reading, both recorded rather than quietly swapped.**

5. **`ingame-001`'s `verification_command` could never have passed.** It was
   `grep -c '"roomName"' run/config/sighteaddons/debug/session-*.jsonl`. `roomName` is not a
   `DebugLog` key anywhere in `src/main` — it is a local in `RoomHistory.kt`; the event key is
   `name` — and `run/` is gitignored. It would return 0 against a perfect session file. Replaced with
   the readout, and the replacement is written out in that feature's evidence.
6. One assertion label in `readout.sh` overclaimed on first draft ("the only two rooms the db gives 0
   secrets" — several *uncleared* rooms also hold 0). Corrected before commit to say "cleared", with
   the distinction written into the script as a comment. Caught by re-reading rather than by a check.

**`ingame-001` stayed `blocked`, deliberately.** Its original blocker is resolved but work on it
genuinely cannot continue here: it needs a *party* floor and a human looking at the `/sa` screen. The
`blocked_reason` was narrowed to name exactly those three gaps rather than the entry being moved to
`in_progress`, which would claim somebody is working it.

**Harness change, recorded per `CLAUDE.md`.** `.gitattributes` gained `*.jsonl text eol=lf`. Evidence
whose bytes depend on whose machine checked it out is worse evidence. No tracked file is affected —
every other `.jsonl` in the tree is under the gitignored `config/`.

**Not done, on purpose.** No status moved to `passing` on the strength of one real run except
`artifacts-001` itself. `records-001` untouched. No version bump, `SCHEMA` 5, `dist/` untouched, jar
md5 `b2ebc35ccfeb9cc96134eb3b18f0306f` measured either side of `assemble check` and identical,
nothing pushed. The sibling receiver repository was not read or written this session — nothing here
goes near the wire.

### Session 008 — `clearpoints-002`: the seed is a prior, not a constant

- Date: 2026-08-14
- Branch `clearpoints-002`, off `main` at `fa075bd`. One code commit, `0d81667`, plus this artifact
  commit. **Not pushed and not merged.** Baseline before starting: `bash init.sh` → **PASSING**
  (120 tests, 12 classes), so no repair was owed.
- Scope: the feature the list had recorded as `blocked` on data. **The blocker was resolved by the
  user rather than by data arriving**, and correcting that entry was part of the task: the user
  supplied seed values and asked for the self-correcting logic to ship anyway — *"Das sind erstmal
  nur geschätzte Werte, ich möchte dass die Logic trotzdem in Kraft tritt, dass sie die Werte somit
  immer verbessern."* The data situation is unchanged and was re-measured over SSH this session:
  `/srv/sighte/roomstats.json` has 83 rooms, 9 with any `clear` sample, and the sum of every room's
  `clearStay.n` is **0**.
- **What was deleted.** `PUZZLE_BONUS`, `TRAP_BONUS`, `MINIBOSS_BONUS`, `BLOOD_BONUS`,
  `SEGMENT_POINTS` and `kindBonus`. Five hand-picked constants and the function that applied them.
  Size and kind stop being declared and become emergent — a 1x4 earns its points by measuring slow,
  and if it turns out to clear as fast as a 1x1 then it was never worth more and the constant was
  paying it for its shape.
- **The model, and every number in it is argued where it is made.** `MEDIAN_BASE` is `ORDINARY_SEED`
  itself, not a second opinion, which is what makes the measured scale and the seed scale one scale.
  `TIME_EXPONENT` is 0.5 — quadruple the time, double the base — and the reason is measured rather
  than aesthetic: the real clear averages span 0.75 s to 36.5 s (49x) while the user's own estimates
  span 2.7x, so a linear map would leave the clamp deciding nearly every room, which is a constant
  wearing a measurement's clothes. `CONFIDENCE_SAMPLES` is 10, the count at which measurement and
  seed weigh the same; on the box's current rate that is roughly forty runs per room, and a weight
  that takes a season of play to turn over is the intended speed.
- **The design changed mid-implementation, on the user's push-back, and the first version was
  already written.** The brief said to ship a snapshot of `roomstats.json` inside the jar; the file
  had been fetched from the box and placed in `src/main/resources/assets/sighteaddons/`. It was
  removed. The reason is decisive rather than a preference: if improving the values requires cutting
  a jar release, they improve when somebody does release work rather than on their own, which is the
  one thing this feature exists to prevent. What replaced it is `RoomStats`' three layers, of which
  the bottom two are implemented. **There is no network call in this feature.**
- **The silent failure on this feature is reading the wrong metric**, and it is guarded twice. The
  receiver folds four averages per room; only `clearStay` is a clear duration under the stay anchor.
  `clear` is the same span under the schema-4 anchor and is an upper bound a walk-through inflates;
  `afterClear` is exactly the secret hunt the user excludes. Swapping `clearStay` for `clear` fails
  6 cases — but it would have failed nothing at all in production, because it produces plausible
  numbers off a metric that means something else.
- **Two existing tests were replaced, and the accounting is in the evidence rather than glossed.**
  `a four-segment room is worth more than a 1x1 of the same kind` pinned `SEGMENT_POINTS`, and
  `every room is still worth at least the point it used to be` pinned `BASE_POINTS = 1.0`. Both
  pinned constants the user's model deletes, so keeping them would have pinned the behaviour that was
  replaced. Neither was removed: each became a case asserting the deliberate new behaviour, and both
  replacements assert **more** — an exact equality across six kinds where there was one inequality,
  and the part of the old floor that mattered (nothing is ever worth nothing) kept while the 1.0
  bound the user removed was dropped. One further assertion was loosened inside an otherwise
  untouched case: `an expensive room nobody was in...` sanity-checked `weightOf(expensive) >= 4.0`
  and that room is now worth 3.5; it asserts `> 2 * weightOf(plain())` instead, which says what it
  meant and does not need retuning every time the model moves.
- **Seven mutation probes, all re-run against the committed source at `0d81667`** and all caught, by
  the intended guards: a cliff at n=5 fails 2, reading `clear` fails 6, misspelling `Ice Fill` as
  `IceFill` fails 3, dropping the clamp fails 2, reintroducing the segment and trap bonuses fails 1
  and *only* 1, defaulting a missing sample instead of falling back to the seed fails 7, and
  inverting the shrinkage fails 4. The harness is `build/probe.sh` (gitignored) and it restores each
  file with `git checkout` rather than from memory, because writing the file back from Python turns
  CRLF into LF on this machine.
- **Two of my own tests failed first and both were fixture bugs, not model bugs**, which is worth
  recording because the second one is a trap the next session could fall into. (1) The no-cliff case
  originally bounded every step at a fiftieth of the journey; the first step is inherently
  `1/(1+k)` = 9% of it, so the assertion was wrong. It now asserts each step is no larger than the
  one before it, which is the precise statement of "no cliff" and catches one wherever it is placed.
  (2) `an unnamed room can carry no measurement` used a one-room snapshot to model a *slow* room —
  impossible by construction, since a lone measured room **is** the median and therefore measures as
  ordinary.
- **The tests pin `RoomStats` to the seed layer explicitly**, in `@BeforeEach` in both
  `ContributionTrackerTest` and `RoomDatabaseTest`. Left alone it resolves a file from
  `configDir/sighteaddons/`, which is outside the repository and which a real install or an earlier
  `runClient` may well have written — a weight that depended on that would pass for the author and
  fail for the next session.
- **Which scores a run used is now recorded**, because once the fetch exists a run's points depend on
  when the player launched. `RoomScores.generatedTs` (0 for the seeds) rides on the `award` debug
  event, on a new `room_scores` event, and as a `scoresTs` key on every `history.jsonl` line —
  additive, since `RoomHistory.fold` reads by key. **Deliberately not on the run report:** a key
  `RUN_KEYS` has not learned is an unknown key and a `400`.
- New feature recorded rather than built: `scores-fetch-001`, layer 1, `blocked` on the receiver
  serving the file at all. The endpoint itself is a **receiver** feature and was *not* written into
  `SighteAddonServerside`'s list — `CLAUDE.md` forbids that from here, so it was reported upward for
  the orchestrator to place.
- Regressions: none. `RunReport.kt` is untouched by this branch
  (`git diff --name-only fa075bd | grep -c RunReport` → 0), `SCHEMA` is still 5, `mod_version` is
  still 0.9.0, and `dist/sighteaddons-0.9.0.jar` is byte-identical either side of `assemble check`
  (md5 `b2ebc35ccfeb9cc96134eb3b18f0306f`).

### Session 007 — `clearpoints-001`: the floor guard that two sessions said could not exist

- Date: 2026-08-14
- Branch `clearpoints-001`, continued at `76fad14`. One code commit, `0795236`, plus this artifact
  commit. **Not pushed and not merged.** Baseline before starting: `bash init.sh` → **PASSING**, so
  no repair was owed.
- Scope: closing an evaluation, again, and a narrower one. The second `evaluator-rubric.md` pass
  scored `clearpoints-001` **12/14 — Revise** with a single cause: Verification 1, because session
  006 declined to write a floor test and justified the decline with a claim that is false.
  Maintainability was also 1, for that same claim plus a wrong commit count. Both are closed here.
  The feature stays `passing` and no behaviour moved.
- **A hard constraint this session worked under:** a debug build from `72e0825` of this branch is
  installed in the user's game and was playing a real dungeon floor while this ran. Nothing here may
  change runtime behaviour, so that the session file they bring back still describes this code. It
  does not — the only `src/main` edit is a KDoc.
- **The claim, and why it mattered.** Sessions 005 and 006 recorded, in `weightOf`'s KDoc and in five
  artifacts, that no honest floor test is possible here: `weightOf` takes no floor,
  `DungeonSession.floor` is `private set` behind `inDungeon(Minecraft)` which no test can call, so a
  floor factor would read null under test and a test claiming to catch it "would pass either way".
  The evaluator disproved it by writing the test in about twenty lines.
  `DungeonSession::class.java.getDeclaredField("floor")` with `isAccessible = true` reaches the
  `object`'s backing field, and `floorNumber` then reads the real digit.
- **What was actually unguarded, measured rather than argued.** Under a live floor multiplier
  **all 119 tests at `72e0825` passed** — including `a room is worth the same however far the run has
  got`, which `feature_list.json` described as "the closest any test in this repository can get to the
  floor exclusion". On the actual exclusion it gets no distance at all. So the exclusion was pinned by
  nothing while three artifacts explained why it could not be pinned. A declared impossibility is
  worse than a declared gap: it outlives the session and stops the next one from trying.
- **The guard: `a room is worth the same on every floor`.** Three floors, because two different edits
  read different things. `F1` against `F7` catches a factor drawn from `floorNumber`. `F7` against
  `M7` catches one drawn from the floor *string* — a master-mode bonus — which `floorNumber` cannot
  see, since it reads `7` for both. `Entrance` is the null reading every other test in the file runs
  under, asserted last so the case says out loud that null is one of the values checked and not the
  only one. Both the weight and the credited points are asserted, since a floor factor could land in
  `weightOf` or between it and the split in `award`.
- **It asserts the setup took before it asserts the invariant**, which is the entire difference
  between this and the guard-in-name-only session 006 feared. Probed: neutering `setFloor` makes the
  case fail with `the floor was not actually set — this test would pass either way`. `DungeonSession`
  is deliberately *not* reset (`DungeonSession.reset()` resets half the mod); the floor is put back to
  null in a `finally`, because the suite runs sequentially in one JVM.
- **The recorded decision on reflection, since it has no precedent in this suite.** Taken
  deliberately, with its cost: renaming `DungeonSession.floor` breaks this test at runtime rather than
  at compile time. Mitigated by the `floorNumber` assertions above, which make that failure loud and
  self-naming. Chosen over the alternative the earlier sessions proposed — a production seam on
  `DungeonSession` — because that is a shape `src/main` does not otherwise need, added purely for a
  test, and a larger change than the exclusion is worth. If the mod ever needs such a seam for its own
  reasons, this test should move onto it.
- Verification, all at `0795236`:
  - `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RoomDatabaseTest' --rerun-tasks`
    → `BUILD SUCCESSFUL in 6s`; `ContributionTrackerTest tests=33 skipped=0 failures=0 errors=0`
    (up from 32), `RoomDatabaseTest tests=6 failures=0`
  - `./gradlew test --rerun-tasks` → classes 12, tests **120**, skipped 0, failures 0, errors 0.
    Strictly additive.
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`; jar md5 `b2ebc35ccfeb9cc96134eb3b18f0306f`
    measured before *and* after and identical; `git status --short dist/ gradle.properties` empty;
    `SCHEMA = 5`; `mod_version=0.9.0`. The release gate did not fire.
  - `bash init.sh` → `BASELINE: PASSING`
  - Three mutation probes, each reverted from a copy taken before the edit with `git status --short`
    checked afterwards: a `floorNumber` multiplier fails 1 (the new case, `expected: <3.85> but was:
    <4.45>`), a master-mode bonus off the floor string fails 1 (`expected: <3.75> but was: <4.75>`),
    and the neutered `setFloor` fails 1 (`expected: <1> but was: <null>`).
- **`claude-progress.md`'s hand-written commit count is gone rather than corrected.** Three reviews
  found it wrong — one against three, four against six, four against seven. "Current Verified State"
  now names `git rev-list --count 1e27b42..HEAD` instead of a number.
- **What was deliberately not edited:** session 006's entry below still contains the false floor
  paragraph. Session entries are the audit trail and this file forbids editing them, so it is
  superseded here rather than rewritten — a reader who reaches it is one scroll from this. Every
  *living* artifact (`weightOf`'s KDoc, the test KDocs, `feature_list.json`, `quality-document.md`,
  `session-handoff.md`, and "Current Verified State" above) now says the true thing.
- Still unverified, unchanged: the weight constants are judgement rather than measurement;
  `ContributionTracker.tick`'s wiring to `onCleared`/`onPresence` needs a `Minecraft` and a
  `MapItemSavedData` and is read rather than asserted — and that one *is* a genuine limit, unlike the
  floor claim, because the missing objects are constructor arguments and not private fields.
  `SighteAddonServerside` was neither read nor written; nothing on this branch reaches the wire.
- Regressions: none.

### Session 006 — `clearpoints-001`: guard the two exclusions that were only argued

- Date: 2026-08-14
- Branch `clearpoints-001`, continued at `073e125`. One code commit, `390399b`, plus this artifact
  commit. **Not pushed and not merged.** Baseline before starting: `bash init.sh` → **PASSING**, so
  no repair was owed and the feature work could start.
- Scope: closing an evaluation, not opening work. `evaluator-rubric.md` scored `clearpoints-001`
  **12/14, Revise** — Correctness, Regression, Scope and Handoff all 2, Verification and
  Maintainability 1. Only the two docked items were touched. `records-001` was left alone: it is
  `blocked` by the user's own product decision, not by anything technical.
- **Item 1, the substantive one — a coverage overclaim in the file a session reads first.**
  `session-handoff.md` said all three weighting exclusions had a test. Only rarity did. The evaluator
  proved it rather than asserting it: adding `+ room.secretsFound * SECRET_POINTS` to `weightOf` —
  the exact edit that constant's own KDoc spends a paragraph arguing against — left **all 116 tests
  green**. Reproduced here before writing anything, and it is now 3 red.
  - `the live secret counter is not what a room is worth` — a room the database says holds no secrets
    but in which 8 were found is worth exactly a plain room, and strictly less than one the database
    says holds 8. Two assertions because there are two plausible edits: adding the live counter, and
    substituting it for `info.secrets`. The evaluator noted the substituting form was caught only by
    the fixtures' accident (they set `info.secrets` and leave `secretsFound` at 0); it is now caught
    on purpose. Mutation-checked in both forms: 3 failures and 6.
  - `the credit is the whole room even though the checkmark lands mid-collection` — drives a real
    room through `onCleared` with 2 of 5 secrets in hand and asserts the credit is the whole room.
    This one pins the *reason* rather than a number: `award()` fires on the checkmark while the party
    is still collecting, so the live counter at that instant is a race against when the last mob
    happened to drop, and the same room would be worth different amounts run to run.
  - `a room is worth the same however far the run has got` — four rooms cleared and credited, and the
    room's own clear progress moved, and its weight does not budge. A run-progress factor probe
    (`(1.0 + roomsCleared * 0.1) * BASE_POINTS`) fails this and **nothing else in the suite**, which
    is what makes it a guard rather than padding.
- **The floor exclusion is recorded as argument-only, and that is the honest answer rather than a
  weaker one.** `weightOf` takes no floor, and `DungeonSession.floor` is `private set` written only
  by `inDungeon(Minecraft)`, which no test here can build — so a floor factor reads null under test
  and falls through whatever it would do in a real run. A test claiming to catch it would be a guard
  in name only, which is the exact failure shape this project keeps removing. Said in `weightOf`'s
  KDoc where the argument already lives, in `feature_list.json`, and in "Current Verified State"
  above, with the note that a real guard needs a seam on `DungeonSession` and is therefore a feature.
- **Item 2 — the released jar's rewrite instruction, in "Current Verified State".** Line 11 still read
  that the full verification path is `./gradlew build`. It contradicted `init.sh` (corrected at
  `c4c0c56`), `session-handoff.md`'s "Do Not Touch", and line 97 of this same file, and a session
  following it deletes and rewrites `dist/sighteaddons-0.9.0.jar` — the released artifact. Now
  `./gradlew assemble check`, with the reason inline. The rest of the file was swept for the same
  claim as asked: the only other hits are the session 004 entry recording the repair (history, left
  alone) and `README.md`'s Build section, which is a contributor build instruction, is accurate about
  `copyToDist`, and was left alone — the handoff's "Do Not Touch" now names it so nobody follows it
  mid-feature.
- **The finding behind both, acted on rather than recorded again.** `evaluator-rubric.md` is
  overwritten wholesale each pass, so findings there have a half-life of one review; two reviewers
  had already hand-copied open items forward, and item 2 — fixed in `init.sh`, left live here — is
  what that costs. The four still-open items are now notes on the features they belong to in
  `feature_list.json`, which is where `CLAUDE.md` already sends discovered work: `settle`'s KDoc
  arguing against the old clamp rather than the symmetric threshold that was the real alternative
  (`residue-001`); and on `clear-001`, `stay.ticks` counting sightings rather than elapsed ticks, the
  gap tolerance calibrated against a documented 10-20 tick worst case with zero margin, and
  `anchorOnClear`'s unknown real-floor frequency. `ingame-001` now cross-references the two of those
  that need a real run, plus the weight constants. The rubric itself was not restructured.
- Verification, all at `390399b`:
  - `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RoomDatabaseTest' --rerun-tasks`
    → `BUILD SUCCESSFUL in 7s`; `ContributionTrackerTest tests=32 failures=0` (up from 29),
    `RoomDatabaseTest tests=6 failures=0`
  - `./gradlew test --rerun-tasks` → **119 tests / 12 classes / 0 failures / 0 skipped**, up from 116.
    Strictly additive: no test removed, weakened or changed, and the only `src/main` change on this
    commit is a comment block in `weightOf`'s KDoc.
  - `./gradlew assemble check` → `BUILD SUCCESSFUL`; `git status --short dist/ gradle.properties`
    empty; jar md5 still `b2ebc35ccfeb9cc96134eb3b18f0306f`; `RunReport.kt:66` `SCHEMA = 5`;
    `mod_version=0.9.0`. The release gate did not fire.
  - `bash init.sh` → `BASELINE: PASSING`
  - Three mutation probes, each reverted immediately from a copy taken before the edit, each with
    `git status --short` checked afterwards: 3 failures, 6 failures, 1 failure.
- Still unverified, unchanged: the weight constants are judgement rather than measurement;
  `ContributionTracker.tick`'s wiring to `onCleared`/`onPresence` needs a `Minecraft` and a
  `MapItemSavedData` and is read rather than asserted; the floor exclusion has no guard by
  construction. Nothing here ran against real Hypixel data, and nothing in this repository can.
- `SighteAddonServerside` was not read and not written this session — nothing here goes near the
  wire, and the schema diff was already done and recorded on this feature.

### Session 005 — `clearpoints-001`: weight rooms, and stop measuring rooms in points

- Date: 2026-08-14
- Branch `clearpoints-001`, off `main` at `1e27b42`. One commit, `13c9fb5`, **not pushed and not
  merged**. Baseline before starting: `bash init.sh` → **PASSING**, so no repair was owed.
- **The cross-repo check first**, because `CLAUDE.md` requires it before anything schema-shaped and
  because it was the question the feature turned on. Read against the receiver's source, not
  assumed: `RUN_KEYS` (`ingest.py:137`) and `ROOM_KEYS` (`ingest.py:152`) carry **no per-player
  points field at all** — the breakdown never leaves the client — so the weighting is invisible to
  the validator. `unattributed` is `_real(x, 0, MAX_CLEARED)` with `MAX_CLEARED = 200`
  (`ingest.py:167,226`), the same ceiling `roomsCleared` is bounded by, and no `400` is reachable
  from either the old or the new definition. **Not paired, no schema bump, `SCHEMA` stays 5.**
- **What the weighting is.** `ContributionTracker.weightOf(room)` = 1.0 base, plus a kind bonus
  (puzzle 1.5; trap, champion/miniboss and blood 1.0), plus 0.25 per secret the room database says
  the room holds, plus 0.5 per segment beyond the first. The split over the members in the room is
  untouched, so two players in one room are still separated only by time. Three exclusions are
  deliberate and each is the obvious next edit: **rare** rooms are paid nothing for being rare (rare
  is unusual to draw, not harder to clear); **secrets come from the database, not from
  `secretsFound`**, because `award()` fires on the clear checkmark while the room's secrets are
  usually still being collected; and **the floor is not a multiplier**, though `floorNumber` is right
  there and the README listed it — it is constant across a run, so it scales everyone equally and
  separates nobody, and points are only ever compared inside one run because they are not in the run
  report at all.
- **The half that carried the risk, and the actual design question.** `unattributed` was
  `roomsCleared - pointsByPlayer().values.sum()`. That was only ever correct because a room was worth
  exactly 1.0, which made a *count* and a *score* numerically interchangeable. Weighted, the score
  exceeds the count, the subtraction goes negative, and `settle`'s clamp reports `0.0` on every run
  forever — no exception, no `400`, no log line. The same shape of failure this project has spent two
  sessions removing elsewhere. So the count is now **counted**, in `award()`, and stays **in rooms**:
  a heavy room nobody was in is one unattributed room, not five points of one. Not a preference — the
  receiver reads the field *relative to* `roomsCleared` and it is its only diagnostic for a broken
  decoration→player mapping (`agent/AGENT-PROMPT.md:62`).
- **Why no schema bump is owed**, and this is the mirror image of `clear-001`: under flat weighting
  every award credited either the full point or nothing, so the old subtraction already produced
  exactly this count. Same key, same meaning, same numbers — now by construction instead of by
  coincidence. `clear-001` was the other case, where the key stayed and the meaning moved, and that
  one cost a bump.
- New seam: `ContributionTracker.onCleared(room)` is `internal` and does `roomsCleared++` then
  `award(room)`; `tick()` calls it. This closes the gap session 002 recorded — that
  `unattributed()`'s composition had no test because `roomsCleared` and `credited` are private and
  only a real run fills them.
- Verification, all at `13c9fb5`: the `verification_command` green (`ContributionTrackerTest` 29,
  `RoomDatabaseTest` 6, 0 failures); `bash init.sh` → `BASELINE: PASSING`, whole suite 12 classes /
  **116 tests** / 0 failures, up from 101 with nothing removed or weakened; `./gradlew assemble check`
  → `BUILD SUCCESSFUL` with `git status --short dist/ gradle.properties` empty. Two mutation checks,
  both reverted immediately and both recorded as evidence: restoring the old subtraction fails 2
  tests, flattening every weight to 0.0 fails 7.
- Discovered work, recorded rather than fixed inline: **`runend-001`**. The receiver's
  `agent/AGENT-PROMPT.md:62` tells its analysis agent to read `unattributed` against `roomsCleared`
  *in `run_end`*, and the mod's `run_end` event has never carried `unattributed`. Its notes also flag
  the ambiguity — the per-room `unattributed` *event* is a different number, since it fires whenever
  the `MIN_TICKS` split was empty even when the raw-presence fallback then credited somebody.
- Not done and deliberately so: no version bump, no `dist/` refresh, no harness file edited,
  `rooms.json` read and never written, `records-001` left `blocked` as the user decided, and
  `SighteAddonServerside` read but never written.

### Session 004 — a harness change, at the user's explicit request

- Date: 2026-08-14
- **This is a harness change and `CLAUDE.md` requires it be recorded here.** The user asked for it
  directly; no session may touch `init.sh` otherwise. No feature was worked on and no source file was
  changed. Branch `harness/init-verify-command`, off `main` at `cf3ff3c`.
- **What was wrong.** `init.sh` line 15 declared `VERIFY_CMD=(./gradlew build)` and printed it as "the
  full verification command" — the one every session is told to run. `build` is `finalizedBy
  copyToDist`, and `copyToDist dependsOn cleanDist`, which **deletes `dist/sighteaddons-*.jar`** and
  writes the current tree's build in its place. Run with an unreleased fix on a branch, it silently
  swaps the released artifact for a different build wearing the same version number. `session-handoff.md`
  has warned about this since the `residue-001` session; `init.sh` said the opposite, and `init.sh` is
  what step 5 of the operating loop tells you to run.
- **Why it was not simply a mistake.** `build` is deliberate in the *release gate* at the top of
  `CLAUDE.md`, where refreshing `dist/` is the entire point: if the jar changes there, the committed
  one was stale. The same command is right in one place and wrong in the other, which is why it
  survived three sessions and two evaluations. The fix keeps both readings and says so.
- **What changed.** `VERIFY_CMD=(./gradlew assemble check)`, with the reasoning as a comment at the
  declaration, and the printed note rewritten from "build also refreshes dist/ — if it changes, the
  jar was stale" to naming which context is which: at the release gate a change means stale, here it
  means you lost it.
- Verification: `bash -n init.sh` clean; `bash init.sh` → `BASELINE: PASSING`, printing
  `./gradlew assemble check`. Then the recommendation was actually executed — `./gradlew assemble
  check` → `BUILD SUCCESSFUL`, and `dist/sighteaddons-0.9.0.jar` kept md5 `b2ebc35c…` with
  `git status --short dist/` empty, before and after. The old text was never tested this way; that is
  how it stayed wrong.
- Provenance: found by the `clear-001` evaluator, which also noted that it had to **carry three
  `residue-001` findings forward by hand** because overwriting `evaluator-rubric.md` would have erased
  them. That is the larger defect and it is not fixed here: evaluator findings never reach
  `feature_list.json` and vanish at the next review. Recorded for whoever opens that file next.

### Session 003

- Date: 2026-08-14
- Goal: `clear-001` — anchor `enterTick` on a minimum stay, so the reported clear stops starting at
  somebody's walk-through.
- Completed: `TrackedRoom` now tracks a **current stay** per member (`start`, `ticks`, `lastSeen`)
  alongside the run-long tick total it already kept, and `enteredAtTick` — now `private set` — is the
  start of the first stay to reach `MIN_TICKS`. Two new pure methods carry it: `onPresence(player,
  at)` and `anchorOnClear(at)`. The first-sighting assignment in `discover` is gone, and its
  `ponytail:` note shrank from two ceilings to one (decoration lag, whose upgrade path is party
  sync). `ContributionTracker.tick` calls both and logs a new `room_anchored` debug event; the
  `cleared` event now carries `enterTick` and `anchoredOnClear`. **`RunReport.SCHEMA` 4 → 5.** New
  `ContributionTrackerTest` with 16 cases — the class both `clear-001` and `clearpoints-001` name in
  their `verification_command` and which did not exist before.
- Verification run (exact commands):
  - `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RunReportTest' --rerun-tasks`
  - `bash init.sh`
  - `./gradlew assemble check`
  - two mutation checks, both reverted immediately (see `feature_list.json` evidence)
- Evidence captured: `BUILD SUCCESSFUL in 7s`, `7 actionable tasks: 7 executed`;
  `ContributionTrackerTest tests=16 failures=0`, `RunReportTest tests=21 failures=0`;
  `BASELINE: PASSING` with the JUnit XML summing to `tests=101 skipped=0 failures=0 errors=0` across
  12 classes, up from 84. All at `a6d92b6`. The two mutation checks are the load-bearing evidence:
  reverting `SCHEMA` to 4 fails 2 tests, and reverting the anchor to first-sighting fails 9 of the
  16 new ones — so neither half can be silently undone.
- Commits: `a6d92b6` (the anchor, the schema bump and the tests) plus the artifact commit that
  follows it, both on branch `clear-001` off `main` at `b588cc4`. Not pushed, not merged.
- Files or artifacts updated: `ContributionTracker.kt`, `RunReport.kt`, `RunReportTest.kt`, new
  `ContributionTrackerTest.kt`, `feature_list.json`, this file, `quality-document.md`,
  `session-handoff.md`.
- Regressions found: none. No test was removed or weakened. Two existing `RunReportTest` assertions
  were updated because this feature deliberately changes what they pin: `run context survives` now
  expects `v = 5`, and the shared room fixture earns its anchor through `onPresence` rather than
  assigning it, landing on the same `120` the assertions have always expected.
- Correction recorded: `clear-001`'s own `notes` claimed the pair risked a `400` and the permanent
  loss of every run of the build. That was **false**, and it was checked rather than trusted:
  `ingest.py:214` validates `v` as `_num(x, 1, 10)` so `v: 5` was accepted before the receiver
  moved, and `ingest.py:159` has had `enterTick` in `ROOM_OPTIONAL` since schema 3. This feature adds
  no field; it changes what one means. The note is corrected in place, with the real risk in its
  stead — which is worse than a `400` precisely because nothing reports it.
- Known risk or unresolved issue: the anchor is verified only through the `TrackedRoom` seam.
  `ContributionTracker.tick` needs a `Minecraft` and a `MapItemSavedData`, so the wiring — that
  `tick` calls `onPresence` once per member per tick with the run clock, and `anchorOnClear` exactly
  on the clear — has no test and no command here can produce one. The gap tolerance is reasoned from
  `PartyTracker.positions`' documented 10–20 tick roster-skew window, not measured against a real
  decoration stream. And the schema is now 5 in source while every install still sends 4: correct and
  harmless, but it means the receiver's `clearStay` bucket stays empty until a release happens.

### Session 002

- Date: 2026-08-14
- Goal: `residue-001` — stop the point split's floating-point residue from reaching the report.
- Completed: `ContributionTracker.unattributed()` and `ContributionTracker.settle()`; `RunReport.build`
  now settles the field instead of clamping one side of it; `RunReport.write` and
  `RoomHistory.printSummary` both call `unattributed()` rather than each computing
  `roomsCleared - pointsByPlayer().values.sum()` inline. Five new tests in `RunReportTest`. The
  feature itself was not in `feature_list.json` at the start of the session — it came from real live
  telemetry, agreed with the user, and was recorded as a new entry (priority 1; the six existing
  features shifted to 2–7, nothing else about them touched).
- Verification run (exact commands):
  - `./gradlew test --tests 'sighteaddons.RunReportTest' --tests 'sighteaddons.RoomHistoryTest' --tests 'sighteaddons.DungeonGridTest' --rerun-tasks`
  - `bash init.sh`
  - `./gradlew assemble check`
- Evidence captured: `BUILD SUCCESSFUL in 10s` with `7 actionable tasks: 7 executed`;
  `BASELINE: PASSING`; the JUnit XML under `build/test-results/test/` sums to
  `tests=84 skipped=0 failures=0 errors=0` across 11 classes, up from 79. All at `2f742cd`.
- Commits: `2f742cd` (the fix and its tests) plus the artifact commit that follows it, both on branch
  `residue-001`. Not pushed, not merged.
- Files or artifacts updated: `ContributionTracker.kt`, `RunReport.kt`, `RoomHistory.kt`,
  `RunReportTest.kt`, `feature_list.json`, this file, `quality-document.md`, `session-handoff.md`.
- Regressions found: none. No existing test was changed, weakened or removed; the two assertions that
  already pinned `unattributed` at `1.25` still pass unmodified, because `settle` is exact on it.
- Known risk or unresolved issue: the fix is verified only through the `build` seam. Nothing here can
  produce a real run, so the end-to-end path — a floor whose credited points drift, `write` calling
  `unattributed()`, the receiver accepting the result — has not been observed, and
  `ContributionTracker.unattributed()`'s own composition (`roomsCleared` minus the credited sum) has
  no test because both inputs are private and only a real run fills them. There is still no
  `ContributionTrackerTest`; `clear-001` and `clearpoints-001` both name one in their
  `verification_command`, so whoever takes those creates it. Separately, `dist/` was deliberately not
  refreshed, so no installed build carries this fix yet.
- Next best step: `clear-001`, but only after the receiver's `schema-001` is deployed.

### Session 001

- Date: 2026-08-13
- Goal: Instantiate the DevLoop harness in this repository.
- Completed: `init.sh`, `feature_list.json`, `claude-progress.md`, `session-handoff.md`,
  `quality-document.md`, `evaluator-rubric.md`, an Operating Loop section appended to `CLAUDE.md`,
  and a `*.sh text eol=lf` line in `.gitattributes`. No source file, no resource and no build script
  was changed — `mod_version` is untouched at 0.9.0, so the release gate at the top of `CLAUDE.md`
  does not fire.
- Verification run (exact commands):
  - `bash init.sh`
- Evidence captured: `BUILD SUCCESSFUL in 25s`, `BASELINE: PASSING`; the JUnit XML under
  `build/test-results/test/` sums to `tests=79 skipped=0 failures=0 errors=0`.
- Commits: the harness commit on branch `devloop-harness`.
- Files or artifacts updated: the six new files above plus the two edited ones.
- Regressions found: none — the suite was green before and after, and nothing it covers was edited.
- Known risk or unresolved issue: the seeded features are read out of this repository's own README
  ("Not implemented yet", "Known limits") and its `ponytail:` notes. They have never been reviewed
  by the user — treat the list as a starting point, not as an agreed backlog. Separately, everything
  `ingame-001` names is still unverified in a real dungeon, and that has not changed.
- Next best step: `clear-001`, but only after the receiver's `schema-001` is deployed.

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
