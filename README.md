# Sighte Addons

Client-side Fabric mod for Hypixel SkyBlock Catacombs that answers a question Hypixel does not:
**who in the party actually cleared which rooms.**

Hypixel only exposes party totals (secrets, deaths, cleared %) — never per-player values, except
on the end-of-run Extra Stats screen. The dungeon item map, however, shows every party member's
position (even outside render distance) *and* every room's clear checkmark. Sampling both every
tick gives full per-player attribution with no party sync.

Minecraft **26.1.2**, Fabric, client only.

## Modules

| Module | File | What it does |
|---|---|---|
| Grid math | `DungeonGrid.kt` | 32-block grid ↔ map pixel conversions, time formatting, point split |
| Map reader | `DungeonMapReader.kt` | map from hotbar slot 9, room types, room segments, clear checkmarks |
| State machine | `DungeonSession.kt` | floor detection, entrance calibration, boss check, run clock |
| Room database | `RoomDatabase.kt` | room name + secret count from a block-column core hash, per loaded chunk |
| Party | `PartyTracker.kt` | tab-list roster, map decoration → player → room |
| Attribution | `ContributionTracker.kt` | per-room presence, clear/secret timeline, point split |
| Secrets | `SecretTracker.kt` | per-room secret count, and which of them were provably yours |
| History | `RoomHistory.kt` | append-only permanent room times, chat announcements, run summary |
| Telemetry | `DebugLog.kt` | JSONL event log for diagnosing a real run |
| HUD | `SighteAddons.kt` | live overlay, run-end hook |

## Live HUD

```
Sighte F7 3:12.5  23 rooms
Water Board  0:41.2
  cleared  02:58.6
  secrets  03:07.1  (0)
8.50  Nordwand
6.00  Tanksalot
```

`cleared` and `secrets` are **independent**: a white checkmark means cleared with secrets still
missing, green means cleared *and* all secrets found. A room usually gets a clear timestamp first
and a secrets timestamp later — or never, if the party leaves secrets behind. Both are run-relative;
the number next to the room name is how long *you* have been in it.

## Chat

Every room reports who did it and how long they were in there. Credit goes to whoever spent the most
time in the room; `(+n)` counts the others who were also there long enough to earn points.

```
bush_on_hide cleared Catwalk in 0:03.6 (+3)
9ast24ray secreted Catwalk in 3:15.2 (+3)
Sighte cleared Water Board in 0:41.2  PB (was 0:52.8)
```

Your own record rides along on the same line rather than producing a second, near-identical message.

At the end of the run (triggered by Hypixel's results headline):

```
Sighte F7 — 6:42.1, 31 rooms
   8.50  Nordwand (12 rooms)
   6.00  Tanksalot (9 rooms)
   0.00  AFKler (0 rooms)
   2.25 rooms unattributed
  3 new records
```

## History

Your own room times are appended permanently to `config/sighteaddons/history.jsonl`, one line per
completed room. **Nothing is ever overwritten or removed** — a personal best is just the minimum over
the history, rebuilt in memory at startup. The full progression stays available instead of collapsing
to a single number, and there is no second file that could disagree with it.

```json
{"ts":1786530882102,"floor":"M5","room":"Catwalk","kind":"clear","ticks":824,"seconds":41.2,"secretsInRoom":4,"ownSecrets":3,"maxSecrets":6,"pb":true}
{"ts":1786531014883,"floor":"M5","room":"Catwalk","kind":"secrets","ticks":1440,"seconds":72.0,"secretsInRoom":6,"ownSecrets":4,"maxSecrets":6,"pb":true}
```

`secretsInRoom` is the room total and says nothing about who found them; `ownSecrets` is the subset
that coincided with your own interaction. Two separate numbers on purpose — there is no estimated
third one in between. See [Secrets](#secrets).

Teammates are deliberately not stored: in a party-finder group they are strangers you never see
again, so every run would add dozens of useless entries. Contribution *points* are still computed for
everyone — only the history is personal.

The metric is **time spent in the room** until the event, not run-relative time — that is what a
player can influence, and it stays comparable across runs and floors. At least 1 second in the room
is required, so running through earns nothing. Time accumulates across re-entries: leaving and coming
back continues the same counter rather than starting a new one.

`rooms unattributed` is the gap between rooms cleared and points handed out. A large gap means the
decoration → player mapping is losing members, so it doubles as a diagnostic.

## Secrets

Hypixel exposes no per-player secret count to the client, so two independent signals are combined:

1. The action bar carries the **current room's** progress (`6/10 Secrets`). That is a per-room number
   — a rise only says *somebody* in that room found one.
2. The client sees its **own** interactions. Right-clicking a chest, a lever or one of the two secret
   skull types is visible locally; a teammate's interaction never is.

A rise that coincides with your own interaction (within 2 seconds) is credited to you. A rise without
one belongs to somebody else, regardless of who is standing where — stronger than guessing from
presence, and it holds up with the whole party digging through the same room.

Before a number is accepted, the total the action bar reports must match the room's secret count from
the room database. Without that guard a stale action bar gets attributed to whichever room you walked
into next.

Secret *coordinates* are not needed for any of this, which is why no waypoint data is bundled. What
this cannot do: attribute secrets to a *specific teammate*, or see secrets in rooms you are not in.
Item and bat secrets are also not detected as "yours" — only block interactions are.

## Debug telemetry

On by default in 0.1.x — the point of this version is producing a diagnostic log. Silence it with
`-Dsighteaddons.debug=false`; gate it behind the development environment again once anyone but the
author installs the mod.

The log goes into the game directory, so it stays with the instance rather than in a shared
`.minecraft`:

```
<prism instance>/.minecraft/config/sighteaddons/debug/session-<millis>.jsonl
run/config/sighteaddons/debug/session-<millis>.jsonl         # gradlew runClient
```

It is created on the first event, which only fires inside a dungeon — an absent folder before that
is not a failure. `gradlew runClient` cannot reach Hypixel (Loom's dev client has no valid session),
so real runs need a normal launcher.

One JSON object per line, flushed immediately, so a crash keeps everything up to the last event.
Events target exactly the parts that cannot be verified by compiling:

| Event | Answers |
|---|---|
| `mort_found`, `calibrated`, `calibration_waiting` | Did the two anchors resolve, and to what? |
| `tab_slot` | Does the tab regex still match Hypixel's format? Logs the raw row. |
| `player_room` | Which decoration became which player, in which cell — with the raw decoration. |
| `room_identified` | Which rooms the chunk scan named. |
| `room_unmatched` | A cell has a room but no database match — **logs the full block column**, which separates "hashed the wrong blocks" from "room not in the database". |
| `room_discovered`, `cleared`, `all_secrets` | Segment grouping and the checkmark timeline. |
| `award`, `unattributed` | Where points went, and which rooms nobody earned. |
| `run_start`, `run_end` | Totals, plus how many rooms stayed unnamed. |

Volume is bounded: only *changes* are written, and the file stops at 20 000 events with an explicit
`truncated` record rather than silently.

## Build

Needs JDK 25 or newer. Bytecode targets 25 (what Minecraft 26.1.2 requires) via `--release`, so a
JDK 26 works too — no toolchain is pinned. If Gradle 9.5.1 refuses your JDK as a daemon JVM, use 25.

```bash
./gradlew build
```

```bash
./gradlew test
```

The installable jar is `build/libs/sighteaddons-<version>.jar`. The `-sources.jar` next to it is
source code for IDE navigation — it contains a `fabric.mod.json` but no classes, so putting it in
`mods/` produces a duplicate mod id and the game will not start.

Runtime dependencies are not bundled: Fabric API `0.155.2+26.1.2` and fabric-language-kotlin
`1.13.13+kotlin.2.4.10`.

## Mappings

Loom 1.17 defaults to **official Mojang mappings** for 26.x — Yarn stops at 1.21.11, and there is
no `mappings` line in `build.gradle` on purpose. Class names are Mojmap: `MapItemSavedData` (not
`MapState`), `Minecraft` (not `MinecraftClient`), `DataComponents` (not `DataComponentTypes`).

## Not implemented yet

- **ClearPoints proper.** Every room is still worth 1 point, which reproduces exactly the flat
  weighting the metric is meant to replace. `rooms.json` already carries the secret and crypt counts
  needed to weight rooms, and `DungeonSession.floorNumber` is available for floor multipliers.
- **Chat events.** Secret clicks, wither doors, puzzle solvers, deaths/ghosts. Currently a dead
  player is only detected via the tab list.
- **Config.** HUD position is fixed at (4, 4); no YACL screen.
- **Party sync.** Would remove the decoration-order heuristic (see the `ponytail:` comment in
  `PartyTracker.kt`) and add per-player secret attribution. Room names no longer need it — chunk
  streaming covers most of the map.

## Known limits

- **Room names need a loaded chunk.** The core hash reads a block column, so a room is only named
  once Hypixel has sent its chunk. Every dungeon chunk is hashed as it streams in, and a dungeon is
  only 12x12 chunks, so in practice most of the map gets named without walking there — but whatever
  Hypixel does not send stays unnamed, and rooms cleared there are scored without a record.
- **The room database is version-coupled.** Core hashes are built from `Block.toString()` output in
  a specific order. If Hypixel changes a room or Mojang changes that representation, names break —
  `RoomDatabase.coreAt` must then be re-verified against the data source.
- **Unverified in-game.** The build compiles and the grid, timing and tab-parsing logic are unit
  tested, but calibration, decoration mapping, checkmark reading and core hashing have never run
  against real Hypixel data. That is what the telemetry log is for.

## Credits

Room-detection maths, map colour IDs and API shapes were verified against three reference mods.

- [Skyblocker](https://github.com/SkyblockerMod/Skyblocker) (LGPL-3.0) — grid maths, map colour IDs,
  checkmark scan, current Mojmap/Fabric API shapes for 26.1.2. No code copied.
- [Odin](https://github.com/odtheking/Odin) (BSD-3-Clause, © 2025 odtheking) — **`RoomDatabase.kt`
  is a derivative work**: the core-hash algorithm is ported from Odin's `WorldScan.getRoomCore` and
  `assets/sighteaddons/rooms.json` is Odin's room database verbatim. The hashes only match that data
  if the algorithm matches exactly. Also: per-floor boss-room bounds, the "centre pixel equals room
  colour means no checkmark" rule, tab-list regex. See `LICENSE-Odin`.
- [NoammAddons](https://github.com/Noamm9/NoammAddons) (CC0-1.0) — decoration map keys carry a
  player-slot digit (noted as the upgrade path in `PartyTracker.kt`), run-end chat regex.
