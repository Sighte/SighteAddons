# Progress Log

## Current Verified State

This is the only section that gets edited in place. Keep it accurate — it is the first thing every
new session reads.

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
- Baseline status (last `./init.sh` run): **PASSING** — 140 tests in 13 classes, 0 failures,
  0 skipped, 2026-08-14, on branch `clearpoints-002` (off `main` at `fa075bd`),
  `mod_version=0.9.0`. **Not pushed and not merged.** For how many commits the branch carries, run
  `git rev-list --count fa075bd..HEAD` — three consecutive reviews found a hand-written number here
  wrong (one, then four, then four again, against three, six and seven), so the number is
  deliberately not written down any more. `git log --oneline fa075bd..HEAD` says what each is for.
- Last feature completed: `clearpoints-002` — **a room is now worth what it measures, not what kind
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
- Current highest-priority unfinished feature: `chat-001` — read the events Hypixel puts in chat.
  Its test class `ChatEventsTest` does not exist yet; creating it is part of the feature.
- Current blocker: none for `chat-001`. `records-001` is deferred by the user (a product decision,
  not a technical blocker), `ingame-001` is blocked on a human playing a real floor, and the new
  `scores-fetch-001` is blocked on the receiver serving `roomstats.json` at all — none of which any
  command here can produce.
- **Old and new ClearPoints standings are not comparable.** Under `clearpoints-001` the one real M7
  scored rooms from 1.00 (`Hall`) to 4.50 (`Cathedral`) and `Pipes` was
  `1.0 + 7*0.25 + 3*0.5 = 4.25`; under `clearpoints-002` `Pipes` seeds at `0.75 + 7*0.25 = 2.50`.
  Every room came down, by different amounts. Pinned as an assertion by
  `the seed weight of Pipes is the user's model, not the old one`.
- **The report schema is now 5 in source and 4 in every install.** `dist/sighteaddons-0.9.0.jar` is
  deliberately **not** rebuilt — it is still the released 0.9.0 artifact — so `residue-001` and
  `clear-001` both exist in source only. Neither reaches a player until somebody bumps the version
  and takes the release gate at the top of `CLAUDE.md`, which is the user's decision. Nothing breaks
  in the meantime: the receiver accepts v4 and v5 alike and buckets their clear spans apart.

## Session Log

Rules: insert the newest session at the TOP of this section. Never edit or delete past session
entries — they are the audit trail. Copy the template below for each new session.

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
