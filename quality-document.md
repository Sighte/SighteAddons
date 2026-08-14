# Quality Document

A quality snapshot for each product domain and architectural layer. Both agents and humans can use
this document to quickly understand where the codebase is strong and where it needs work.

**Update cadence:** After each significant session, or before starting a new phase of work (this is
part of the "Before You Stop" checklist in CLAUDE.md).

**Grading scale:**

- **A**: All verification passing, clean architecture, agent-legible, stable tests
- **B**: Verification passing, mostly clean, minor gaps in legibility or test coverage
- **C**: Partially working, known gaps, some code areas hard for agents to understand
- **D**: Not working, or major structural issues

One thing colours every grade below: **nothing here has run against real Hypixel data.** Loom's dev
client cannot log in, so "tests pass" means the logic matches its own model of a dungeon, never that
the model matches Hypixel. That is `ingame-001`, and it is why no domain that touches live data
scores above B.

---

## Product Domains

| Domain | Grade | Verification | Agent Legibility | Test Stability | Key Gaps | Last Updated |
|--------|-------|-------------|-----------------|---------------|----------|-------------|
| telemetry (`RunReport`, `TelemetryUpload`, `DebugLog`) | B | `RunReportTest`, `TelemetryUploadTest`, `DebugLogTest` | High — the ceilings carry `ponytail:` notes | Stable | Retry schedule is "next game start", no backoff, no queue (`TelemetryUpload.kt:211`); nothing uploads during a run; quitting straight from a dungeon has no JOIN and loses the session (`SighteAddons.kt:54`) | 2026-08-14 |
| scoring (`ContributionTracker`, `RoomStats`) | B | `ContributionTrackerTest` (42 cases) covers the clear anchor at the `TrackedRoom` seam, and the weighting, the blend and the unattributed accounting at the `onCleared`/`blend` seams; `RoomStatsTest` (9 cases) covers the scores file against the receiver's real document shape; `RoomDatabaseTest` (8) checks the weighting against the bundled `rooms.json` rather than a fixture; `settle` is pinned through `RunReportTest` | High — every number in the model is argued where it is made, including *why* the exponent is 0.5 rather than 1 and why `k` is 10, and the three `clearpoints-001` exclusions (rarity, live secret counts, floor multiplier) are still argued and still pinned. `blend` is pure and takes its sample and median rather than reaching for `RoomStats`, so the model is legible and testable without a file. The three resolution layers are named and numbered in `RoomStats`' KDoc, including the one that does not exist yet and why | Stable, and mutation-checked seven more times at `0d81667` on top of `clearpoints-001`'s seven: a cliff at n=5 fails 2, reading `clear` instead of `clearStay` fails 6, misspelling a seed key fails 3, dropping the clamp fails 2, reintroducing the segment and trap bonuses fails 1 and only 1, defaulting a missing sample instead of falling back to the seed fails 7, inverting the shrinkage fails 4. The suite pins `RoomStats` to the seed layer in `@BeforeEach`, so no weight depends on a file outside the repository | Nothing here has run against real Hypixel data, which is the ceiling on this domain and the reason it is not A; `ContributionTracker.tick`'s wiring is still untestable, since it needs a `Minecraft` and a `MapItemSavedData` — so *that* `onCleared` and `onPresence` are called once per clear and once per member per tick is read, not asserted; **every room is on its seed today and will be until the receiver serves the averages (`scores-fetch-001`)**, so the measured half of the model is exercised only by tests; layer 2 has never been read on a real install, because nothing writes a cache yet; the floor guard depends on reflection, so a rename of `DungeonSession.floor` breaks it at runtime rather than at compile time | 2026-08-14 |
| history and records (`RoomHistory`, `RecordTable`) | B | `RoomHistoryTest`, `RecordTableTest` | High | Stable | Floors are collapsed into one record per room and kind (`records-001`); whole history in memory, ~40 bytes per line (`RoomHistory.kt:59`) | 2026-08-13 |
| party (`PartyTracker`) | C | `PartyTrackerTest` | High — the assumption is stated where it is made | Stable, but it pins the heuristic rather than the truth | Decoration order is assumed to match tab order (`PartyTracker.kt:134`); two players on one decoration are not told apart (`party-001`) | 2026-08-13 |
| room naming (`RoomDatabase`, `DungeonMapReader`, `DungeonGrid`) | C | `RoomDatabaseTest`, `DungeonGridTest` | High | Stable | Version-coupled: core hashes come from `Block.toString()` in a fixed order, so a Hypixel or Mojang change breaks names silently; a room needs a streamed chunk to be named at all | 2026-08-13 |
| events (chat) | D | none — the domain does not exist yet | n/a | n/a | Secret clicks, wither doors, puzzle solvers and deaths are not read; a dead player is only seen via the tab list (`chat-001`) | 2026-08-13 |
| UI (`SettingsScreen`, `ClearPopup`, `/sa`) | C | API use is pinned by the compiler, the record fold is unit tested | Medium | n/a | No pixel has ever been on screen — spacing and hit boxes are unverified | 2026-08-13 |

## Architectural Layers

| Layer | Grade | Boundary Enforcement | Agent Legibility | Key Gaps | Last Updated |
|-------|-------|---------------------|-----------------|----------|-------------|
| Map reading | C | Reads the map colour and the block column, never a name from chat | High | Only as correct as the real map — untestable here | 2026-08-13 |
| Session and state (`DungeonSession`, `SighteAddons`) | B | One session object owns run state; trackers read it | High | An abrupt quit loses the session | 2026-08-13 |
| Persistence (`RoomHistory`, `Config`) | B | Append-only history, config separate | High | Unbounded in memory | 2026-08-13 |
| Network (`TelemetryUpload`) | B | The URL and the schema are compiled in — deliberate, and the reason a version bump can take old installs off the air | High | A schema change is only safe in one order: receiver first, this build after | 2026-08-13 |
| Vendored data (`rooms.json`, core hash) | A | Odin's database verbatim under BSD-3, never edited or regenerated; the notice ships in the jar | High — the rule is stated in `build.gradle` where it matters | none | 2026-08-13 |

## Change History

Newest entry first.

### 2026-08-14 (session 008)

- Changes: `clearpoints-002`. Deleted five hand-picked scoring constants (`PUZZLE_BONUS`,
  `TRAP_BONUS`, `MINIBOSS_BONUS`, `BLOOD_BONUS`, `SEGMENT_POINTS`) and the `kindBonus` that applied
  them, and replaced them with a measured base blended out of a seed estimate:
  `base = seed + n/(n+10) * (measured - seed)`. New `RoomStats`/`RoomScores` reads the receiver's
  `roomstats.json` verbatim from the mod's own config directory, with the seeds as the fallback and
  no network call. Suite 120 → 140 across 12 → 13 classes.
- Domains promoted: none. **Scoring stays B**, and it should — the ceiling on this domain is that
  nothing has run against real Hypixel data, and this feature does not touch that. What changed is
  the *kind* of thing the weights are: they were judgement dressed as constants and are now a prior
  with a stated mechanism for retiring itself. That is a better shape, not a verified one.
- New gaps identified: two, and both are about the half of the model that cannot run yet. **Every
  room is on its seed and will be until something serves the averages** — recorded as
  `scores-fetch-001`, blocked on the receiver. And **layer 2 has never been read on a real install**:
  the cache path is exercised only by `@TempDir` fixtures, because nothing writes a cache today.
- Gaps closed: the weight constants themselves, partially and honestly. The previous entry recorded
  "the weight constants are judgement, not measurement, and no real run has yet shown whether they
  separate players usefully". They are still not measured — but they are now *structurally able to
  be*, and the blend is what closes the gap once data exists rather than a session editing numbers.
  The one real M7's numbers were used as a sanity check on the shape (0.75 s to 36.5 s of clear time
  against a 2.7x spread of estimates) rather than as validation.
- Legibility note, and it is the reason this change is more than a formula swap: **the shift is
  stated as an assertion, not a comment.** Old and new standings are not comparable — `Pipes` went
  from 4.25 to a 2.50 seed — and `the seed weight of Pipes is the user's model, not the old one`
  pins that number against the real database, so a reader looking for what changed finds a test
  rather than prose that can drift.

### 2026-08-14 (session 007)

- Changes: closed the second `clearpoints-001` evaluation's Revise (12/14), whose whole deduction was
  one thing — the session before it had declared the floor exclusion *impossible* to test, and the
  evaluator disproved that by writing the test. One case added,
  `a room is worth the same on every floor`, and the impossibility claim retracted in the five
  artifacts that carried it. No behaviour changed and none could: the only `src/main` edit is
  `weightOf`'s KDoc.
- Domains promoted: none. **Scoring stays B**, and the ceiling is unchanged — nothing here has run
  against real Hypixel data. What moved is that the third of three exclusions is now guarded instead
  of asserted-to-be-unguardable.
- New gaps identified: one, and it is the cost of the fix. The floor guard reaches a `private set`
  field by reflection, so renaming `DungeonSession.floor` breaks it at runtime rather than at compile
  time. Mitigated inside the test — it asserts `floorNumber` reads the value back before it asserts
  anything about weights, so the failure is loud and names itself. Recorded rather than hidden: this
  is the only reflection in the suite, and a production seam on `DungeonSession` would still be the
  cleaner answer if the mod ever needs one for its own reasons.
- Gaps closed: **the floor exclusion, which was guarded by nothing at all.** Measured, not argued: a
  floor multiplier added to `weightOf` passed all 119 existing tests, including the run-progress case
  that `feature_list.json` had described as the closest a test could get to it. Both edit forms now
  fail the new case and nothing else. The second gap closed is the claim itself — a declared
  impossibility outlives the session that wrote it and stops the next one from trying, which is a
  worse defect than the missing test was.

### 2026-08-14 (session 006)

- Changes: closed the `clearpoints-001` evaluation's Revise (12/14) — the two items it docked, and
  nothing else. Three test cases where there were none: the live secret counter is not what a room is
  worth (pinning both the additive and the substituting edit), the credit is the whole room even
  though the checkmark lands mid-collection (pinning the *reason* — `award()` fires while the party
  is still collecting, so the live counter at that instant is a race), and a room is worth the same
  however far the run has got. `claude-progress.md`'s "Current Verified State" no longer names
  `./gradlew build` as the full verification path, which was the released jar's rewrite instruction
  sitting in the first section every session reads.
- Domains promoted: none, and deliberately not. **Scoring stays B.** The two exclusions that were
  argued-but-unguarded are now guarded, which is what the evaluation asked for, but it removes a
  documentation defect rather than adding capability — and the ceiling is unchanged: nothing here has
  run against real Hypixel data.
- Domains demoted: none.
- New gaps identified: none new. What changed is where the *old* ones live. Four findings that had
  survived two evaluations only because each reviewer hand-copied them into a file that is
  overwritten wholesale every pass are now notes on the features they belong to in
  `feature_list.json` — `settle`'s KDoc arguing against the wrong alternative (`residue-001`), and on
  `clear-001`: `stay.ticks` counting sightings rather than elapsed ticks, the gap tolerance
  calibrated against a documented worst case with zero margin, and `anchorOnClear`'s unknown
  real-floor frequency. The last of those, plus the weight constants, are cross-referenced from
  `ingame-001` as what to read out of the first real session file.
- Gaps closed: the coverage overclaim itself. The exact edit the `SECRET_POINTS` KDoc argues against
  passed all 116 tests at `13c9fb5`; it now fails 3. A run-progress factor was caught by nothing and
  is now caught by one case. The floor exclusion is recorded as argument-only, in the KDoc and in
  `feature_list.json`, rather than given a test that would have passed whether or not the factor was
  there.

### 2026-08-14 (session 005)

- Changes: `clearpoints-001`. `ContributionTracker.weightOf` pays a room for what it took — kind,
  secrets from the room database, segments from the map — instead of one flat point, and the split
  over the members in it is unchanged. The load-bearing half is `unattributed`: it was
  `roomsCleared - pointsByPlayer().values.sum()`, correct only while a count and a score were
  numerically interchangeable at 1.0 a room, and weighted it would have clamped to `0.0` on every run
  forever with nothing anywhere saying so. It is now counted, in `award()`, and stays in **rooms**,
  which is the unit the receiver reads it in. No schema bump is owed: the values are unchanged,
  because under flat weighting every award credited either the full point or nothing.
- Domains promoted: **scoring C → B.** The single reason it was held at C — every room worth exactly
  1 point, the flat weighting the metric exists to replace — is gone, and the domain is
  mutation-checked in both directions. B and not A for the standing reason: nothing here has run
  against real Hypixel data.
- Domains demoted: none.
- New gaps identified: the weight constants are judgement rather than measurement — no real run has
  shown whether 1.5 for a puzzle and 0.25 a secret separate players usefully, and the first debug
  session from a real floor is what would say. `runend-001` was found and recorded: the receiver's
  `agent/AGENT-PROMPT.md:62` tells its analyst to read `unattributed` against `roomsCleared` in the
  `run_end` event, and `run_end` has never carried `unattributed`.
- Gaps closed: the flat weighting itself. Also the gap session 002 recorded — `unattributed()`'s
  composition had no test, because `roomsCleared` and `credited` are private and only a real run
  filled them; the new `internal onCleared(room)` seam needs a cleared room and nothing else, and six
  cases now drive it. `README.md` no longer describes `rooms unattributed` as "the gap between rooms
  cleared and points handed out", which this change would have made false.

### 2026-08-14 (session 003)

- Changes: `clear-001`. `enterTick` is anchored on a minimum stay rather than on the first sighting
  of anybody, so a room a member merely walked through no longer reports that walk-through as the
  start of its clear. `TrackedRoom` keeps a current stay per member beside the run-long tick total it
  already had; `enteredAtTick` is `private set` and stamped only by `onPresence` (a stay reaching
  `MIN_TICKS`, anchored at that stay's own start) or by `anchorOnClear` (a room that clears sooner,
  anchored inside a one-second window that bounds what the fallback can invent). `RunReport.SCHEMA`
  4 → 5, which is the half that carries the risk: no field changed shape, so nothing anywhere would
  have rejected or logged a stay-anchored report sent as v4 — `roomstats.py` routes on `v` alone and
  would have folded it into the average it was built to replace, permanently.
- Domains promoted: none. Scoring stays **C**: the anchor is fixed and the domain finally has its own
  test class, but `clearpoints-001` is untouched and every room is still worth exactly 1 point, which
  is the domain's headline purpose still missing. Telemetry stays **B** for the standing reason —
  nothing here has run against real Hypixel data.
- Domains demoted: none.
- New gaps identified: the wiring between `ContributionTracker.tick` and the anchor has no test —
  `tick` needs a `Minecraft` and a `MapItemSavedData`, so that it calls `onPresence` once per member
  per tick with the run clock is asserted by reading only. The gap tolerance that keeps a stay
  together across a sighting blackout is reasoned from `PartyTracker.positions`' documented 10–20
  tick roster-skew window rather than measured against a real decoration stream.
- Gaps closed: `clear` is no longer an upper bound masquerading as a duration. `ContributionTracker`
  had no test class of its own at all — the gap the previous two sessions both recorded and the one
  `clearpoints-001` also needs — and now has 16 cases. A false rationale in `clear-001`'s `notes`,
  claiming this pair risked a `400` and the loss of every run of the build, was checked against
  `ingest.py` and corrected: `v` was already accepted up to 10 and `enterTick` has been optional
  since schema 3.

### 2026-08-14 (session 002)

- Changes: `residue-001`. The point split's floating-point residue no longer reaches the report.
  `ContributionTracker.settle` rounds an unattributed figure to the two decimals every display path
  already truncates to and then clamps it, replacing a `coerceAtLeast(0.0)` that caught only the
  negative half of a residue that demonstrably occurs with both signs. The expression
  `roomsCleared - pointsByPlayer().values.sum()`, previously written out in both `RunReport.write`
  and `RoomHistory.printSummary` with two different ideas of what counted as zero, is now
  `ContributionTracker.unattributed()` in one place.
- Domains promoted: none. Telemetry stays **B** and scoring stays **C** — the grade ceiling in both
  is that nothing here has run against real Hypixel data, and this session did not change that.
- Domains demoted: none.
- New gaps identified: `ContributionTracker.unattributed()`'s composition has no test, because
  `roomsCleared` and `credited` are private and only a real run fills them. The residue *treatment*
  is fully covered through the `RunReport.build` seam; the subtraction feeding it is not.
- Gaps closed: the clamp in `RunReport.build` had no test at all despite its comment naming a real
  failure mode. It now has four, including one that derives the exact value the live report carried
  (`3.552713678800501e-15`) from `DungeonGrid.splitPoints` itself rather than from a typed constant,
  and one that pins the drift going negative as well — the property the old clamp assumed away.

### 2026-08-13

- Changes: DevLoop harness added. No source file, resource or build script touched.
- Domains promoted: none — these are first grades, not movements.
- Domains demoted: none.
- New gaps identified: none new. What is written down here was already documented in the README's
  "Not implemented yet" and "Known limits" sections and in the `ponytail:` notes; this is the first
  time it is graded in one place.
- Gaps closed: none.
