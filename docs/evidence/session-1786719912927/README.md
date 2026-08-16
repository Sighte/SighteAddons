# The one real dungeon run this repository has evidence of

**M7, solo, 2026-08-14 17:05:12 local time.** Session id `1786719912927`, which is the epoch
millisecond `DebugLog` names its file after.

Until this directory existed, everything the artifacts said about a real floor was relayed prose. The
`clearpoints-002` evaluation put it plainly: three living artifacts cited real-M7 figures as
measurement while "the session file `CLAUDE.md` names as their required evidence has never arrived".
It has now, and this is it.

## Read the numbers by running them, not by reading this file

```bash
bash docs/evidence/session-1786719912927/readout.sh
```

Every figure quoted below is asserted in that script. It exits non-zero if any of them moves, so a
number transcribed out of here by hand and then drifting is a build failure rather than a
disagreement between two documents. That is the whole design: this repository has spent four
evaluations on statements that were true when written and stopped being true quietly.

## Provenance, and why you should believe the build it came from

- **The mod build was a debug build of `72e0825`** — `clearpoints-001`'s formula, *before*
  `clearpoints-002` (`0d81667`) replaced it. It is not a build of `HEAD` and never was.
- **The file corroborates its own provenance.** No `award` event carries a `scoresTs` key and there is
  no `room_scores` event anywhere. Both were added by `clearpoints-002`, so the build that wrote this
  file predates it — that is a property of the bytes rather than a claim about somebody's memory.
- **Names are already pseudonymised at the source.** `Pseudonym` maps the player to `p-cad01af7`
  before `DebugLog` ever sees it. Nothing here was scrubbed after the fact.

## What is in this directory, and what was left out

| File | Contents |
| --- | --- |
| `session-excerpt.jsonl` | All 143 lines of the original 220 whose event is **not** `player_room`, verbatim and in order. Every line here is byte-identical to a line in the file the game wrote, so any of them can be found in the original with a plain `grep`. |
| `player_room-sample.jsonl` | 3 of the 77 `player_room` lines — the first two and the last. |
| `readout.sh` | The assertions. |

**Why the 77 `player_room` lines are not all here.** They are the run's position stream: a world
coordinate and a map-decoration index every few ticks. They are the user's own telemetry, and the only
claim they support is a negative one — that this run was solo. Three of them show the shape; the
census below shows the count. Keeping the other 74 would be copying position data to prove something
a count already proves.

## Event census of the original 220-line file

```
  8  all_secrets            10  cleared              36  room_identified       1  secret_run_discarded
  9  award                  17  own_interaction       1  run_start             5  secret_run_done
  1  calibrated             77  player_room          24  secret                5  secret_run_start
  1  mort_found             10  room_anchored         2  tab_slot              1  unattributed
                            12  room_discovered
```

`run_end`: **0**. `roster_skew`: **0**. Both absences are load-bearing and both are asserted.

---

# What this run settles

## 1. `MIN_TICKS` — `stay.ticks` counts sightings, and it now says so

`clear-001`'s open note (1) reasoned that `TrackedRoom.onPresence`'s `stay.ticks` counts *sightings*
rather than elapsed ticks, and asked for the KDoc to say so. This measures it.

`MIN_TICKS` is 20 and the anchor is the stay's **start**, so on a decoration stream that reports every
tick the 20th sighting lands exactly 19 ticks after the first. Nine of the ten `room_anchored` events
are stamped at delta 19, to the tick:

| Room | stay began (`at`) | anchored (`t`) | delta |
| --- | ---: | ---: | ---: |
| Withermancer | 24 | 43 | 19 |
| Cathedral | 261 | 280 | 19 |
| Atlas | 403 | 422 | 19 |
| Default | 644 | 663 | 19 |
| Hall | 800 | 819 | 19 |
| **New Trap** | **1657** | **1681** | **24** |
| Slime | 2203 | 2222 | 19 |
| Chains | 3029 | 3048 | 19 |
| Pipes | 3267 | 3286 | 19 |
| Teleport Maze | 4373 | 4392 | 19 |

`New Trap`'s 24 is the same mechanism seen from the other side: 20 sightings spread over 24 ticks
means five were missed, and the gap tolerance absorbed them without splitting the stay. Sightings and
elapsed ticks coincide when the stream is clean and diverge exactly as predicted when it is not.

**What this does *not* settle: `clear-001`'s note (2), the zero-margin gap tolerance.** That note is
about a gap longer than `MIN_TICKS` splitting a stay, and its documented cause is the 10–20 tick
roster-skew blackout `PartyTracker.positions` reports around a **death in a party**. This run was solo
and nobody died — `roster_skew` fired zero times — so the failure mode was never in the building. The
largest divergence observed was 5 missed sightings against a tolerance of 20, but a solo run cannot
bound a party-and-death case. Note (2) stays open.

## 2. `anchorOnClear` fires once in ten rooms

`clear-001`'s open note (3) recorded the fallback's real-floor frequency as unknown. It is **1 of 10**:

- `Duncan`, a 1x1 holding one secret, entered at tick 2990 and cleared at 2996 — **six ticks**, so no
  stay could reach 20 and the fallback anchored it. `"anchoredOnClear":true`, the only one.
- The other nine were anchored by a genuine qualifying stay, `"anchoredOnClear":false`.

So the anchor is mostly real and the fallback is the exception it was designed to be. Two consequences
worth carrying:

- `ContributionTracker.anchorOnClear`'s KDoc estimated "the empty 1x1s, three of them in one M7". The
  measured rate is **one**, not three. Same order, benign direction, and now checkable.
- **`Duncan` is also the room missing from `history.jsonl`.** `RoomHistory.kt:144` only records a
  clear when the local player's own presence reached `MIN_TICKS`, and Duncan's was 7. So this run
  wrote 9 clear lines locally for 10 cleared rooms. That is existing, deliberate behaviour rather than
  a defect — but it means the fallback-anchored room is precisely the one the local history drops, and
  a reader comparing the two files will find the off-by-one.

## 3. Calibration, room naming and core hashing worked

The first evidence `ingame-001` has ever had:

- One `calibrated` event, floor `M7`, `mapEntrance Pos(x=25, z=5)`, `mapRoomSize 16`, resolved against
  a physical entrance found by `mort_found`.
- `run_start` reports `roomCores: 272` loaded from the bundled `rooms.json`.
- **36 `room_identified` events carrying real Odin names** — Withermancer, Cathedral, Atlas, Teleport
  Maze, Duncan, Ice Fill, Tic Tac Toe, Pipes, Slime, Big Red Flag, Chains, Gold, Well, Mushroom, Blood
  and more, each with its type, shape and secret count. Core hashing against the database hits.

**Checkmark reading is evidenced too, and more than expected.** `DungeonMapReader` reads `WHITE = 34`
(cleared) and `GREEN = 30` (cleared, all secrets found). Of the ten clears, exactly two read GREEN —
`Default` and `Hall` — and those are exactly the two cleared rooms the database gives **0** secrets,
so all of them were trivially found. The other eight read WHITE and their `all_secrets` events land
later, after the party went back for them. Ten for ten, in both directions.

**What is still unevidenced on `ingame-001`, and this run cannot supply it:**

- **The decoration→player mapping under a party.** One player, one decoration, `decoIndex` 0 for all
  77 `player_room` lines. The heuristic `party-001` exists to replace was never put under load.
- **The RED checkmark path.** Never occurred here.
- **Every pixel of the `/sa` screen.** Nothing in a session file can show it.

## 4. The party heuristic was NOT exercised — say so out loud

This matters because "a real run happened" reads as covering everything. It does not cover
`party-001`. One player in tab (`p-cad01af7`), one decoration index, every split went to that one
player, and `roster_skew` fired **zero** times. Nothing here is evidence for or against the
decoration-order assumption at `PartyTracker.kt:134`.

## 5. A documented gap fired for real: the run was lost

There is **no `run_end` event and no run report was written.** `runs/uploaded/`'s newest file predates
the run by a day and the receiver got nothing.

The cause is the `ponytail:` note at `SighteAddons.kt:53-57`. `RunReport.write` is reached from
exactly two places: the end-of-run chat headline, and `ClientPlayConnectionEvents.JOIN`. The user quit
the game straight from the dungeon, so the headline never came and there was no subsequent JOIN. The
note called this a known gap; this is the first time it is a **measured loss** rather than a
theoretical one.

Recorded as `runloss-001`, since fixed (`git log --grep runloss-001`). What survived is the local `history.jsonl` — 14
lines, 9 clears and 5 secret runs, appended per line as they happened — so the records are intact
locally and only the report, and therefore the receiver's copy, is gone.

## 6. The ClearPoints weights — under the formula that no longer exists

**These are `clearpoints-001` numbers. `HEAD` does not produce them.** Anywhere they are quoted, that
sentence has to travel with them.

| Room | shape | db secrets | old weight | how the old formula made it |
| --- | --- | ---: | ---: | --- |
| Hall | 1x1 | 0 | **1.00** | `1.0` |
| Chains | 1x1 | 2 | 1.50 | `1.0 + 2×0.25` |
| Default | 1x1 miniboss | 0 | 2.00 | `1.0 + 1.0` |
| New Trap | 1x1 trap | 3 | 2.75 | `1.0 + 1.0 + 3×0.25` |
| Withermancer | L (3 seg) | 4 | 3.00 | `1.0 + 4×0.25 + 2×0.5` |
| Slime | 1x3 | 5 | 3.25 | `1.0 + 5×0.25 + 2×0.5` |
| Atlas | 2x2 | 6 | 4.00 | `1.0 + 6×0.25 + 3×0.5` |
| Pipes | 1x4 | 7 | 4.25 | `1.0 + 7×0.25 + 3×0.5` |
| Cathedral | 2x2 | 8 | **4.50** | `1.0 + 8×0.25 + 3×0.5` |
| | | | **26.25** | sum of the nine `award` events |
| Duncan | 1x1 | 1 | 1.25 | `1.0 + 0.25`, credited through the fallback split |

**Under `clearpoints-002`, every one of these comes down and they come down by different amounts.**
`Pipes` seeds at `0.75 + 7×0.25 = 2.50` — its three extra segments stop being paid for — and no room
in this run had a measured `clearStay` average to blend toward, because `clearStay` is `n = 0`
everywhere. A standing from this run cannot be held next to one from a `HEAD` build.

**One subtlety, and it is `runend-001`'s open question answered.** `Duncan` emitted an `unattributed`
event, and then the raw-presence fallback credited the same 1.25 to `p-cad01af7` anyway. So this run
has **one `unattributed` event and zero genuinely unattributed rooms**. `runend-001`'s note warns that
counting those events is not the same number as the run's unattributed room count; here the two differ
by one, on a ten-room floor, which is the whole diagnostic range the receiver's analyst reads. Whoever
picks up `runend-001` should write the run-level count, not the event count.

## 7. `TIME_EXPONENT`'s calibration still rests on a proxy

Unchanged by this run and worth stating here so it is not read as settled. The 0.75 s – 36.5 s / 49×
spread that argues `TIME_EXPONENT = 0.5` comes from the box's **`clear`** averages, because
`clearStay` has `n = 0` for every room on the box. `clear` is the walk-through-inflated upper bound
the same file insists must never be confused with `clearStay`. This run produced no `clearStay`
samples on the box either — its report was never uploaded (§5) — so the proxy stands. The KDoc now
names it.
