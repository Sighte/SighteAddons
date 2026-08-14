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
| scoring (`ContributionTracker`) | B | `ContributionTrackerTest` (32 cases) covers the clear anchor at the `TrackedRoom` seam and the weighting and unattributed accounting at the new `onCleared` seam; `RoomDatabaseTest` checks the weighting against the bundled `rooms.json` rather than a fixture; `settle` is pinned through `RunReportTest` | High — the weighting states what each term is for, and the three exclusions (rarity, live secret counts, floor multiplier) are argued where they are made rather than merely absent. **Two of the three are now also pinned by tests; the floor is argued only, and the KDoc says so and says why** — `weightOf` takes no floor and `DungeonSession.floor` is `private set` behind `inDungeon(Minecraft)`, so no test here can reach it | Stable, and mutation-checked five times: restoring the old `unattributed` subtraction fails 2, flattening every weight to 0.0 fails 7, adding `secretsFound` to the weight fails 3, substituting it for the database count fails 6, and a run-progress factor fails 1 | Nothing here has run against real Hypixel data, which is the ceiling on this domain and the reason it is not A; `ContributionTracker.tick`'s wiring is still untestable, since it needs a `Minecraft` and a `MapItemSavedData` — so *that* `onCleared` and `onPresence` are called once per clear and once per member per tick is read, not asserted; the weight constants are judgement, not measurement, and no real run has yet shown whether they separate players usefully; the floor exclusion has no guard and cannot have one without a seam on `DungeonSession` | 2026-08-14 |
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
