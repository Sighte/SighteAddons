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

One thing colours every grade below, and **it changed on 2026-08-14 — read the new version, not the
remembered one.** It used to read "nothing here has run against real Hypixel data", and that is no
longer true. One real M7 is now committed at `docs/evidence/session-1786719912927/`, so calibration,
room naming, core hashing and checkmark reading have been seen working against live Hypixel data, and
the two domains that rest on them are promoted below.

What has **not** changed is the ceiling on everything else. Loom's dev client still cannot log in, so
for every path that run did not touch, "tests pass" still means the logic matches its own model of a
dungeon rather than that the model matches Hypixel. And that run covered less than it looks like: it
was **solo and deathless**, so the decoration→player heuristic and the roster-skew blackout were never
exercised, no pixel of the `/sa` screen was seen, the `RED` checkmark never occurred, and every room
was scored under `clearpoints-001`'s deleted formula. `ingame-001` is still `blocked` — on a *party*
floor and a human opening the screen — and that is still why no domain resting on unseen live paths
scores above B.

---

## Product Domains

| Domain | Grade | Verification | Agent Legibility | Test Stability | Key Gaps | Last Updated |
|--------|-------|-------------|-----------------|---------------|----------|-------------|
| telemetry (`RunReport`, `TelemetryUpload`, `DebugLog`) | B | `RunReportTest` (32 cases; 30 before `floorloss-001`, 21 before `runloss-001`), **`DungeonSessionTest` (7 cases, new — `DungeonSession` had no test of any kind before 2026-08-15)**, `TelemetryUploadTest`, `DebugLogTest` | High — the ceilings carry `ponytail:` notes, and since `runloss-001` the hardest question in the domain is answered *with the disassembly that answers it*: `RunReport.uploader`'s KDoc names the four bytecode facts that make `Minecraft.player` unreadable at `DISCONNECT`, with the `javap` commands to recheck them | Stable, and mutation-checked four more times at `cd26786`: reverting `uploader` to the live player fails 1, deleting the once-per-run claim fails 2, not giving the claim back after a failed write fails 1, and writing the report straight to its final name instead of through `.part` + move fails 1 | **The quit-from-dungeon gap that cost a real run on 2026-08-14 is closed in source as of `runloss-001` (session 012), and closed is not the same as observed.** `ClientPlayConnectionEvents.DISCONNECT` is now a third call site for `RunReport.write`; the report no longer reads `Minecraft.player`, it lands through an atomic move so a shutdown cannot truncate it, and a once-per-run `AtomicBoolean` in `RunReport` stops the disconnect-then-rejoin pair writing twice. **That Fabric raises the event at all on a real Hypixel quit has never been seen** — the dev client cannot reach Hypixel and the event cannot be raised in a unit test — so the wiring rests on two disassemblies and on the new `run_report` debug event that a real quit would show. **A hard kill still loses the run** and nothing here pretends otherwise. **Stays B and does not go to A**: the same ceiling as before, plus a fix whose one real path is unobserved. Other gaps unchanged: retry schedule is "next game start", no backoff, no queue (`TelemetryUpload.kt:211`); nothing uploads during a run. **A second gap was measured on the box on 2026-08-15 and closed the same day (`floorloss-001`): 20 of 22 uploaded reports carry floor `?`**, because `inDungeon` answered its question by *assigning* the floor and so cleared it on every tick outside a dungeon — and two of the three write paths fire after leaving. Closed in source, and **the 20 are not recoverable**: the floor those runs were played on was never stored. The same ceiling as `runloss-001` applies — no real report has been written under the fix — which is the third reason this domain does not go to A. **Discovered and open: `floorname-001`**, the receiver validates `floor` as `?|E|[FM][1-7]` and this mod can hold `Entrance`; measured unreachable today | 2026-08-15 |
| scoring (`ContributionTracker`, `RoomStats`) | B | `ContributionTrackerTest` (48 cases; 42 before `chat-001` added the death-dedup seam) covers the clear anchor at the `TrackedRoom` seam, and the weighting, the blend and the unattributed accounting at the `onCleared`/`blend` seams; `RoomStatsTest` (9 cases) covers the scores file against the receiver's real document shape; `RoomDatabaseTest` (8) checks the weighting against the bundled `rooms.json` rather than a fixture; `settle` is pinned through `RunReportTest` | High — every number in the model is argued where it is made, including *why* the exponent is 0.5 rather than 1 and why `k` is 10, and the three `clearpoints-001` exclusions (rarity, live secret counts, floor multiplier) are still argued and still pinned. `blend` is pure and takes its sample and median rather than reaching for `RoomStats`, so the model is legible and testable without a file. The three resolution layers are named and numbered in `RoomStats`' KDoc, including the one that does not exist yet and why | Stable, and mutation-checked seven more times at `0d81667` on top of `clearpoints-001`'s seven: a cliff at n=5 fails 2, reading `clear` instead of `clearStay` fails 6, misspelling a seed key fails 3, dropping the clamp fails 2, reintroducing the segment and trap bonuses fails 1 and only 1, defaulting a missing sample instead of falling back to the seed fails 7, inverting the shrinkage fails 4. The suite pins `RoomStats` to the seed layer in `@BeforeEach`, so no weight depends on a file outside the repository | Nothing here has run against real Hypixel data, which is the ceiling on this domain and the reason it is not A; `ContributionTracker.tick`'s wiring is still untestable, since it needs a `Minecraft` and a `MapItemSavedData` — so *that* `onCleared` and `onPresence` are called once per clear and once per member per tick is read, not asserted; **every room is on its seed today and will be until the receiver serves the averages (`scores-fetch-001`)**, so the measured half of the model is exercised only by tests; layer 2 has never been read on a real install, because nothing writes a cache yet; the floor guard depends on reflection, so a rename of `DungeonSession.floor` breaks it at runtime rather than at compile time | 2026-08-14 |

| telemetry (`RunReport`, `TelemetryUpload`, `DebugLog`) | B | `RunReportTest`, `TelemetryUploadTest`, `DebugLogTest` | High — the ceilings carry `ponytail:` notes, and the one below is now the *measured* cost of one | Stable | **The quit-from-dungeon gap stopped being theoretical on 2026-08-14 and cost a real run**: the M7 at `docs/evidence/session-1786719912927/` wrote no `run_end` and no report, so ten cleared rooms never reached the box. `RunReport.write` is reachable only from the end-of-run headline and from `ClientPlayConnectionEvents.JOIN` (`SighteAddons.kt:53-57`), and quitting to desktop from inside a floor produces neither. Tracked as `runloss-001`. **Stays B rather than dropping to C, and the reasoning is deliberate:** the domain works — seven runs are in `runs/uploaded/` — the failure is confined to one exit path, it was already documented at the site, and `history.jsonl` kept all 14 of that run's lines, so the loss is the report and the receiver's copy rather than the data. A C would say telemetry is partially working; it is fully working on every path but this one. Other gaps unchanged: retry schedule is "next game start", no backoff, no queue (`TelemetryUpload.kt:211`); nothing uploads during a run | 2026-08-14 |
| scoring (`ContributionTracker`, `RoomStats`) | B | `ContributionTrackerTest` (48 cases; 42 before `chat-001` added the death-dedup seam) covers the clear anchor at the `TrackedRoom` seam, and the weighting, the blend and the unattributed accounting at the `onCleared`/`blend` seams; `RoomStatsTest` (17 cases; 9 before `scores-fetch-001` added layer 1) covers the scores file against the receiver's real document shape and now the fetch as well — five of the eight new cases run against a real HTTP server on an ephemeral loopback port, because a truncated read and a refused connection cannot be produced by a pure function, and one nine-shape table drives `503`, `404`, `500`, an HTML 502 page under a `200`, half a document, an empty body, an array, a body that stops halfway and a dead port through the real client; `RoomDatabaseTest` (8) checks the weighting against the bundled `rooms.json` rather than a fixture; `settle` is pinned through `RunReportTest` | High — every number in the model is argued where it is made, including *why* the exponent is 0.5 rather than 1 and why `k` is 10, and the three `clearpoints-001` exclusions (rarity, live secret counts, floor multiplier) are still argued and still pinned. `blend` is pure and takes its sample and median rather than reaching for `RoomStats`, so the model is legible and testable without a file. The three resolution layers are named and numbered in `RoomStats`' KDoc, and all three now exist; the two decisions a reader would otherwise have to reverse-engineer — why a late document is cached but not adopted, and why an `ETag` is never offered for bytes that are not on disk — are argued where they are made and each has its own test and its own mutation probe | Stable, and mutation-checked seven more times at `0d81667` on top of `clearpoints-001`'s seven: a cliff at n=5 fails 2, reading `clear` instead of `clearStay` fails 6, misspelling a seed key fails 3, dropping the clamp fails 2, reintroducing the segment and trap bonuses fails 1 and only 1, defaulting a missing sample instead of falling back to the seed fails 7, inverting the shrinkage fails 4. The suite pins `RoomStats` to the seed layer in `@BeforeEach`, so no weight depends on a file outside the repository — and `RoomStatsTest` now clears the resolution in both `@BeforeEach` and `@AfterEach`, because a leftover would silently turn a fetch case into a no-op. Five more probes at `74e6859`, all caught, each by exactly the one case that names the property: adopting a document that arrives mid-run fails 1, a direct `Files.writeString` instead of the `.part` + rename fails 1, offering an `ETag` for a document that is not on disk fails 1, treating a `200` that does not parse as fresh fails 1, dropping `If-None-Match` fails 1 | Nothing here has run against real Hypixel data, which is the ceiling on this domain and the reason it is not A; `ContributionTracker.tick`'s wiring is still untestable, since it needs a `Minecraft` and a `MapItemSavedData` — so *that* `onCleared` and `onPresence` are called once per clear and once per member per tick is read, not asserted; **every room is on its seed today, and the reason changed on 2026-08-14 rather than went away** — the receiver serves the document and the mod fetches it (`scores-fetch-001`), but every `clearStay` in it is `n=0` because no schema 5 build has been *released*, so the measured half of the model is still exercised only by tests; **`RoomStats.start()` has never run inside a game** — every piece it calls is measured against the live box through the mod's own code, but the dev client cannot log in, so nothing has observed a real launch fetching, caching and adopting; layer 2 has therefore still never been read on a real install; the floor guard depends on reflection, so a rename of `DungeonSession.floor` breaks it at runtime rather than at compile time | 2026-08-14 |
| history and records (`RoomHistory`, `RecordTable`) | B | `RoomHistoryTest`, `RecordTableTest` | High | Stable | Floors are collapsed into one record per room and kind (`records-001`); whole history in memory, ~40 bytes per line (`RoomHistory.kt:59`) | 2026-08-13 |
| party (`PartyTracker`) | C | `PartyTrackerTest` (14 cases; 7 before `party-001` extracted the assignment, and those 7 covered only the tab regex and the living-class carry — the decoration heuristic itself had **zero** coverage and could not get any while it lived inside `positions()`) | High — the assumption is stated where it is made, and since `party-001` the *rejected* alternatives are stated there too, with what was measured rather than with what was assumed | Stable, and mutation-checked three times at `80e5f57`: dropping the `trustOrder` guard fails 2 of 14, counting the local player's own marker in the teammate index (NoammAddons' actual defect) fails 4, assuming the local player is slot 0 fails 1. **Still pins the heuristic rather than the truth**, which is why this is C and not B | Decoration order is assumed to match tab order (`PartyTracker.assign`), and **`party-001` established that no better client-side channel is known to exist**: the wire format carries an unkeyed `List<MapDecoration>` and the client re-keys it `"icon-" + i` from its own loop index, so the accessor-mixin upgrade path the old comment named would have returned list order spelled as a string. The only surviving candidate is `MapDecoration.name()`, unverifiable here — `deconame-001`. **Two failure modes, previously conflated and now separated**: two teammates in one room produce two decorations at nearly the same pixel that both resolve to that room's cell, so swapping them is *harmless* and is asserted to be; the damaging one is the count mismatch that shifts assignments across *different* rooms, which `trustOrder` blacks out and which is now asserted in both directions. **The 2026-08-14 real M7 is not evidence for this domain and must not be read as promoting it** — that run was solo: one player in tab, `decoIndex` 0 for all 77 position events, `roster_skew` fired zero times. A single decoration cannot be mis-ordered, so the heuristic was never put under load. This domain needs a *party* floor, ideally with a death in it, which would also settle `clear-001`'s open gap-tolerance finding | 2026-08-14 |
| room naming (`RoomDatabase`, `DungeonMapReader`, `DungeonGrid`) | **B** (was C) | `RoomDatabaseTest`, `DungeonGridTest`, **plus a real floor**: `bash docs/evidence/session-1786719912927/readout.sh` | High | Stable | **Promoted 2026-08-14 on real data, not on new code.** The one committed M7 shows calibration resolving on the first try (1 `calibrated` event, `mapEntrance Pos(x=25, z=5)`, `mapRoomSize 16`), 272 room cores loaded, and 36 `room_identified` events carrying correct Odin names, types, shapes and secret counts. Checkmark reading is evidenced in both directions: of ten clears exactly two read `GREEN` (all secrets found) and they are exactly the two cleared rooms the database gives 0 secrets. So the version coupling below is *currently* holding rather than merely assumed. Remaining gaps: it is still version-coupled — core hashes come from `Block.toString()` in a fixed order, so a Hypixel or Mojang change breaks names silently and this evidence expires the day either moves; the `RED` checkmark path has still never occurred; a room still needs a streamed chunk to be named at all. Not A because one floor is one sample and the coupling is undiagnosable from inside the mod | 2026-08-14 |
| events (chat) | **C** (was D) | `ChatEventsTest` (11 cases), driven with the raw `§`-coded lines and stripped in the test with the same `ChatFormatting.stripFormatting` the listener uses; the two consumers are covered at their own seams (`SecretTrackerTest`'s `chatAttribution` cases, `ContributionTrackerTest`'s death-dedup cases) | High — every line shape names the published mod it was taken from, and the KDoc states in its own header that no test here can establish Hypixel sends them | Stable, and mutation-checked twice: dropping *both* anchoring mechanisms fails 2 of 11 including the forged-death case, and turning `chatAttribution`'s "chat said nothing" into "chat said no" fails 3 of 8 | **Promoted out of D because the domain now exists, and not further because nothing has ever seen one of these strings arrive.** The patterns are cited to `SkyHanni`, `Cowlection`, `IllegalMap`, `DulkirMod` and `OdinLegacy` rather than observed, so the domain is exactly as correct as those mods and no more; the wiring (that Fabric delivers a line, with `overlay` false, on the tick it was sent) is untestable here, the same way `ContributionTracker.tick`'s is; and it is unknown whether a `chat_secret` reaches the client *before* the action-bar update it attributes, which decides whether the secret half works at all. A wrong pattern fails silently and benignly — it matches nothing and the inference it would have overruled simply stays. `ChatEvents.nearMiss` writes the offending line (redacted) to the debug log, which is what one real floor would need to move this to B. Scope note: chat names a finder for **wither-essence secrets only**, so `SecretTracker.isOwn` still attributes almost every secret | 2026-08-14 |
| UI (`SettingsScreen`, `ClearPopup`, `/sa`) | C | API use is pinned by the compiler, the record fold is unit tested | Medium | n/a | No pixel has ever been on screen — spacing and hit boxes are unverified | 2026-08-13 |

## Architectural Layers

| Layer | Grade | Boundary Enforcement | Agent Legibility | Key Gaps | Last Updated |
|-------|-------|---------------------|-----------------|----------|-------------|
| Map reading | **B** (was C) | Reads the map colour and the block column, never a name from chat | High | **Promoted 2026-08-14 on the same evidence as room naming** — the layer is no longer "untestable here", it is *tested elsewhere*: `docs/evidence/session-1786719912927/` shows the entrance found, the grid calibrated and every checkmark read correctly on a real M7. Still only as correct as the real map, still one sample, and the `RED` checkmark path is still unseen | 2026-08-14 |
| Session and state (`DungeonSession`, `SighteAddons`) | B | One session object owns run state; trackers read it | High | **The report write and the reset are no longer the same event, and that separation is the point of `runloss-001`.** The write now also hangs off `ClientPlayConnectionEvents.DISCONNECT`, so a process that never rejoins still flushes; the *reset* deliberately does not, because that callback can arrive on a Netty thread while the client is still rendering and `DungeonSession.reset()` tears down state `renderHud` reads every frame. Remaining gap: a hard kill flushes nothing, and the only place a run's state lives between events is still memory. **`floorloss-001` (2026-08-15) is the same separation applied to the floor**: `inDungeon` used to answer "are we in a dungeon" by *writing* the floor, so the question destroyed the fact the report needed. The predicate and the record are two calls now — `observeSidebar` answers about the present and only ever writes a floor it saw, and `DungeonSession.reset()` is the single place a floor is forgotten. `floor` is `@Volatile` because the DISCONNECT path reads it off the client thread. **Newly graded gap: `DungeonSession` had zero tests until 2026-08-15**, which is how a one-line defect survived five schema versions with 184 tests around it; `DungeonSessionTest` covers the floor lifecycle and nothing else, and `inDungeon`'s remaining half (`sidebarLines`, which needs a live client) is still untested. **`runTicks` is read on the DISCONNECT path and is not volatile** — pre-existing, out of `floorloss-001`'s scope, unfixed | 2026-08-15 |
| Persistence (`RoomHistory`, `Config`) | B | Append-only history, config separate | High | Unbounded in memory | 2026-08-13 |
| Network (`TelemetryUpload`) | B | The URL and the schema are compiled in — deliberate, and the reason a version bump can take old installs off the air | High | A schema change is only safe in one order: receiver first, this build after | 2026-08-13 |
| Vendored data (`rooms.json`, core hash) | A | Odin's database verbatim under BSD-3, never edited or regenerated; the notice ships in the jar | High — the rule is stated in `build.gradle` where it matters | none | 2026-08-13 |

## Change History

Newest entry first.

### 2026-08-15 (session 014)

- Changes: `floorloss-001`. The floor is kept for the life of a run instead of being cleared by the
  in-dungeon check, so a report knows which floor it was. Suite 184 -> 193; new class
  `DungeonSessionTest` (7), `RunReportTest` 30 -> 32. **No schema change and no receiver change** —
  `python build/keydiff.py` reports four empty sets both directions, `SCHEMA` stays 5, jar md5
  `5e0b1cd2d3b97cfaa6cd5e86061cbdbe` unchanged, `mod_version` not touched, `SighteAddonServerside`
  read and not written.
- **Domains promoted: none, and telemetry staying B is again the deliberate call.** The defect was
  found *on the box*, in data, not in the suite — which is the strongest available evidence that
  184 passing tests were not measuring the thing that mattered. Five mutation probes now measure
  that the new cases can fail, but the fix's three real paths are as unobserved as `runloss-001`'s
  were, for the same reason: the dev client cannot log in. Promoting on that would apply a lower
  standard than `party` is held to at C.
- **Session and state keeps B and gains a named gap**: `DungeonSession` had no test of any kind
  before this session.

### 2026-08-14 (session 012)

- Changes: `runloss-001`. `ClientPlayConnectionEvents.DISCONNECT` is a third call site for
  `RunReport.write`, so a run quit straight from a dungeon is no longer thrown away. Suite
  167 → 176 in the same 14 classes; `RunReportTest` 21 → 30. **No schema change and no receiver
  change** — `build/keydiff.py` reports four empty sets both directions, `SCHEMA` stays 5, jar
  md5 unchanged, `SighteAddonServerside` read and not written.
- **Domains promoted: none, and telemetry staying B is the deliberate call this time.** Session
  009 held it at B while the gap was open, on the argument that the domain worked on every path
  but one. Closing that path does not earn the promotion either, and for a reason worth stating:
  the fix's one real path — Fabric raising `DISCONNECT` when somebody closes the game on Hypixel
  — has never been observed. It is read off `Minecraft.destroy()`'s bytecode and off
  `ConnectionMixin`'s inject targets. That is a stronger basis than prose, and it is still not a
  dungeon. Promoting on it would apply a lower standard than `party` is held to at C.
- **Domains demoted: none.** No previously passing test moved; the regression check ran over the
  whole suite at `--rerun-tasks`.
- **A gap this document should record about itself: one of the four mutation probes did not
  fail.** Replacing the atomic move in `RunReport.publish` with a direct `Files.writeString` left
  every one of the 29 checks green, because a *successful* direct write and a *successful* move
  leave an identical directory. The observable half is what a crash leaves behind: a `.part` from
  an interrupted attempt must be consumed by the next successful write. A test for that was added
  and the probe now fails. The atomicity of the move itself stays a property of the code rather
  than of anything a unit test on a working filesystem can inspect, and is recorded as such
  rather than counted as covered.
- **A correction to the feature list, not to this document.** `runloss-001`'s
  `verification_manual` told the reader to "confirm the debug session carries a `run_end`
  event" — which is exactly what this path does not and must not produce, since `run_end` means
  the end-of-run headline came. It now names the new `run_report` event, which is the observable
  the fix actually added.

### 2026-08-14 (session 013)

- Changes: `scores-fetch-001`. Layer 1 of `RoomStats`: a daemon-thread fetch of the receiver's
  `GET /roomstats` at game start, the document cached verbatim through `.part` + rename with its
  `ETag` beside it, `If-None-Match` on the next launch, and every failure falling through to the last
  cache and then to the seeds. Suite 167 → 175 in the same 14 classes, strictly additive —
  `RoomStatsTest` 9 → 17 and nothing else. **No schema change and no receiver change**: no report
  field was added, `RunReport.kt` is not in the branch's diff, `SCHEMA` stays 5, and the key diff is
  four empty sets. Jar md5 unchanged.
- **Domains promoted: none. Scoring stays B**, on the standard session 012 applied to telemetry: the
  gap is closed in source and its one real path has never been observed. `RoomStats.start()` has
  never run inside a game, because the dev client cannot log in — what *is* measured is every piece
  it calls, against the live box, through the mod's own code. And the served document is still
  `sampled 0`: every `clearStay` is `n=0` until a schema 5 build is released, so no weight actually
  moves yet. Promoting on a loopback server and a `curl` would be a lower standard than the one that
  held this domain at B while the fetch did not exist at all.
- **Domains demoted: none.**
- **A note on where the tests run.** Five of the eight new cases open a real HTTP server on an
  ephemeral loopback port. That is a deliberate departure from this repository's habit of testing the
  decision and leaving the wire alone (`TelemetryUpload.post` has no test), and the reason is that
  here the wire *is* the feature: the whole entry is failure handling, and a truncated read and a
  refused connection have no pure-function equivalent. Nothing in the suite reaches a network, and
  the live box is exercised only by a probe that was deleted rather than committed — a test that
  needs the box up would make `init.sh` depend on the box being up.

### 2026-08-14 (session 010)

- Changes: `chat-001`. A pure `String -> Event?` parser (`ChatEvents`) plus wiring, reading deaths,
  revives, wither doors, the blood door, puzzle solves and failures, and the one secret Hypixel names
  a finder for. Suite 140 → 160 in 13 → 14 classes. **No schema change and no receiver change**:
  every improvement lands in `deaths`, room `deaths` and `ownSecrets`, which the receiver already
  accepts — checked by extracting every `addProperty` key from `RunReport` and diffing it against
  `RUN_KEYS`/`ROOM_KEYS` parsed out of `ingest.py`, four empty sets. `RunReport.kt` untouched,
  `SCHEMA` 5, jar md5 unchanged. New feature recorded rather than built: `chatfields-001`.
- **Domain promoted: events (chat) D → C.** It went from not existing to existing, tested at its one
  testable seam and mutation-checked twice. **It does not go to B, and the reason is the ceiling this
  document's preamble already names**: the patterns are cited to published mods rather than observed,
  and no string has ever been seen arriving here. That is the same standard `party` is held to at C —
  a heuristic pinned by tests is not a heuristic confirmed by a dungeon.
- **Domains demoted: none.** Scoring stays B; its test count moved 42 → 48 but the addition is a new
  seam (death deduplication), not a change to the weighting model.
- **A correction to the feature list, not to this document.** `chat-001`'s stated behaviour was
  factually wrong on three of its four claims — deaths were read rather than inferred, and wither
  doors and puzzle solvers did not exist in `src/main/` at all. Corrected in place. The reason it
  belongs in this history is that a domain graded `D` for "does not exist yet" was, in one of those
  four respects, already at `B` — a grade table can only be as accurate as the feature entries it is
  read alongside.

### 2026-08-14 (session 009)

- Changes: `artifacts-001`, an artifact pass. **No runtime behaviour changed** — every `src/` edit is
  comment text and the diff proves it mechanically
  (`git diff -- src/ | grep -E '^[+-][^+-]' | grep -vE '^[+-]\s*(\*|//|/\*)'` is empty; `@Test` lines
  touched: 0; suite unchanged at 140 in 13 classes). Committed the first real dungeon run this
  repository has ever held, at `docs/evidence/session-1786719912927/` — an excerpt, a README, and a
  `readout.sh` that **asserts** all 35 figures the README quotes and fails if one drifts. Retired six
  statements that had outlived what they described. New feature recorded rather than built:
  `runloss-001`.
- **Domains promoted: room naming C → B, and the Map reading layer C → B.** Both on real data rather
  than on new code: calibration, 272 core hashes, 36 correctly named rooms and ten correctly read
  checkmarks on one live M7. Neither goes to A — one floor is one sample, and the core-hash coupling
  to `Block.toString()` means this evidence expires the day Hypixel or Mojang moves.
- **Domains demoted: none, and one deliberate non-demotion.** Telemetry stays B even though the
  quit-from-dungeon gap has now destroyed a real run: the domain works on every path but that one,
  the failure was already documented at the site, and the local history survived. Recorded with its
  reasoning in the table so a later reader does not have to guess whether it was considered.
- **The preamble of this document was itself one of the stale statements.** It opened "nothing here
  has run against real Hypixel data", which stopped being true the moment the run was committed.
  Rewritten to say what changed *and* what did not — the run was solo and deathless, so party
  attribution, the roster-skew blackout, the `RED` checkmark and every pixel of `/sa` are exactly as
  unverified as before.
- Follow-up: `runloss-001` is unblocked and arguably the highest-value work on the list; `party-001`
  and the rest of `ingame-001` both need one party floor.

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
