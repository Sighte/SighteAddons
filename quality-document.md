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
| scoring (`ContributionTracker`) | C | `./gradlew test` compiles and covers the fold; `settle` is pinned through `RunReportTest`, but there is still no `ContributionTrackerTest` | Medium | n/a | Every room is worth 1 point — exactly the flat weighting the metric replaces (`clearpoints-001`); `clear` is an upper bound, not a duration (`clear-001`); `unattributed()`'s own composition is untestable without a real run, since `roomsCleared` and `credited` are private and only a run fills them | 2026-08-14 |
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

### 2026-08-14

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
