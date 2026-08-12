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
| Secrets | `SecretTracker.kt` | per-room secret count, which of them were provably yours, secret-run timer |
| History | `RoomHistory.kt` | append-only permanent room times, chat announcements, run summary |
| Telemetry | `DebugLog.kt` | JSONL event log for diagnosing a real run |
| Run report | `RunReport.kt` | permanent per-run record: every room and what it cost the party |
| Upload | `TelemetryUpload.kt` | ships session logs and run reports to the analysis server |
| Settings | `SettingsScreen.kt` | the `/sa` screen: settings tabs and the personal-record table |
| Config | `Config.kt` | the settings that screen writes, as one JSON object |
| HUD | `SighteAddons.kt` | live overlay, `/sa` command, run-end hook |

## Live HUD

```
Sighte F7 3:12.5  23 rooms
Water Board  0:41.2
  cleared  02:58.6
  secrets  0:08.6  4/6 (2 you)
8.50  Nordwand
6.00  Tanksalot
```

`cleared` is run-relative: the tick the room's checkmark appeared. The number next to the room name
is how long *you* have been in it.

`secrets` is the **secret run** — a stopwatch, not a timestamp. It starts the moment the room's first
secret is taken (chest, lever, wither essence, item pickup) and stops on its last one. Nothing taken
yet means dashes, and a run that dies goes back to dashes rather than freezing at a number.

## Settings — `/sa`

`/sa` opens the settings screen, `/sa pbs` jumps straight to the records table. Four tabs, one row
grid, click a row to toggle it — every change is written to `config/sighteaddons/config.json`
immediately, so there is no save button that could be forgotten.

```
  SIGHTE ADDONS                                            0.4.0
  ────────────────────────────────────────────────────────────────
  HUD    CHAT    RECORDS    DEBUG
  ────────────────────────────────────────────────────────────────
  show HUD                                                     on
  position                                         4, 4 · place
  current room                                                 on
  standings                                                   off
```

`position · place` switches to placement mode: the HUD is drawn at the cursor, the next left click
places it, right click cancels. The screen is drawn from filled rectangles and font calls only — no
textures, no widget library, and no config-screen dependency, which is why the mod still ships with
nothing but Fabric API and fabric-language-kotlin.

**RECORDS** is the history read back: one row per room with both records side by side — time in the
room until it cleared, and the secret run — how many runs the room was completed in, and how long ago
that last happened. Rows are grouped by room type; clicking `room`, `type`, `clear` or `runs` sorts
by that column, and the list scrolls.

```
  RECORDS                                    128 lines · 43 rooms
  ────────────────────────────────────────────────────────────────
  room       type            clear     secrets    runs      last
  puzzles  9 ─────────────────────────────────────────────────────
  Water Board               0:41.2      0:12.4       7      today
  Tic Tac Toe               0:22.8      --:--.-      10     3d ago
  normal rooms  56 ────────────────────────────────────────────────
  Catwalk                   0:03.6      0:14.8      12     2d ago
```

Records are derived from `history.jsonl` at startup, not stored — floors are collapsed on purpose,
because the metric is time spent in the room and stays comparable across floors. The chat settings
hide announcements only: history is always written, so silencing chat never costs a record.

## Chat

Every clear reports who did it and how long they were in there. Credit goes to whoever spent the most
time in the room; `(+n)` counts the others who were also there long enough to earn points.

```
bush_on_hide cleared Catwalk in 0:03.6 (+3)
Catwalk secrets in 0:14.8 (6, 4 yours)  PB (was 0:19.2)
Sighte cleared Water Board in 0:41.2  PB (was 0:52.8)
```

A secret run names the **room**, not a player: the clock runs from the room's first secret to its
last no matter whose hands took them, so crediting one player would be a claim this client cannot
back. What it does know is how many of them were yours, and that rides along.

Your own record rides along on the same line rather than producing a second, near-identical message.

At the end of the run (triggered by Hypixel's results headline):

```
Sighte F7 — 6:42.1, 31 rooms
   8.50  Nordwand (12 rooms · – secrets)
   6.00  Sighte (9 rooms · 14 secrets)
   0.00  AFKler (0 rooms · – secrets)
   2.25 rooms unattributed
  3 new records
```

The number in front is the contribution score, the breakdown behind it is what produced it: rooms the
player was in for at least a second, and the secrets they found. **Secrets are only ever shown for
you.** Hypixel reports secrets as a per-room counter and only for the room you are standing in, so a
teammate's count would be a guess — they get a dash instead of a number this client cannot know. A
teammate's `0` would be a claim, a dash is the truth. See [Secrets](#secrets).

## History

Your own room times are appended permanently to `config/sighteaddons/history.jsonl`, one line per
completed room. **Nothing is ever overwritten or removed** — a personal best is just the minimum over
the history, rebuilt in memory at startup. The full progression stays available instead of collapsing
to a single number, and there is no second file that could disagree with it.

```json
{"ts":1786530882102,"floor":"M5","room":"Catwalk","kind":"clear","ticks":824,"seconds":41.2,"secretsInRoom":4,"ownSecrets":3,"maxSecrets":6,"pb":true}
{"ts":1786531014883,"floor":"M5","room":"Catwalk","kind":"secretrun","ticks":296,"seconds":14.8,"secretsInRoom":6,"ownSecrets":4,"maxSecrets":6,"pb":true}
```

`kind` says what `ticks` measures. `clear` is time spent in the room; `secretrun` is the secret run,
first secret to last. Lines written before the secret run existed carry `"kind":"secrets"` and a
third meaning — how long you had been in the room when it turned green. They are still in the file,
because nothing here is ever rewritten, and nothing reads them any more: a shorter measurement under
the old name would beat every old entry and announce it as a personal best.

`secretsInRoom` is the room total and says nothing about who found them; `ownSecrets` is the subset
that coincided with your own interaction. Two separate numbers on purpose — there is no estimated
third one in between. See [Secrets](#secrets).

Teammates are deliberately not stored: in a party-finder group they are strangers you never see
again, so every run would add dozens of useless entries. Contribution *points* are still computed for
everyone — only the history is personal.

A clear is measured as **time spent in the room**, not run-relative time — that is what a player can
influence, and it stays comparable across runs and floors. At least 1 second in the room is required,
so running through earns nothing. Time accumulates across re-entries: leaving and coming back
continues the same counter rather than starting a new one.

A **secret run** is measured from the room's first secret to its last, and is recorded only when it
is a whole run. Three cases produce no record at all rather than a fast one:

- the room was already part-way done when its first secret reached this client — that is somebody
  else's run, and timing the leftovers would beat every honest attempt;
- there is no span to measure: a single-secret room, or a counter that jumps from empty to full in
  one update;
- **10 seconds pass without another secret** — the party moved on, and the run is discarded instead
  of being closed at whatever the room reaches later. Coming back does not resume it.

A missing record costs nothing; a wrong one is permanent, which is why every uncertain case drops.

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

The same two signals drive the **secret run** timer. It starts at the moment the room's first secret
was taken — your own click when the secret is yours, since the action bar can lag up to two seconds
behind it, and the bar update itself otherwise (which is what an item pickup looks like from here).
It stops on the room's last secret. See [History](#history) for the cases that are discarded.
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

### Upload

The logs are only useful where they can be read, so finished sessions are shipped to an analysis
server. Off unless `config/sighteaddons/upload.properties` exists next to the debug folder:

```properties
url=http://<server>:8420
token=<shared secret>
```

Base URL only; the mod appends `/ingest` for sessions and `/runs` for the run reports below. The
file is never written by the mod and never committed — the token stays on the machine that plays.

Uploads happen **at game start, for what previous sessions left behind** — not at `run_end`, which
never fires when the game crashes or the run is left early, losing exactly the material worth
looking at. It also keeps networking out of the tick loop. Whatever was handed over moves to an
`uploaded/` folder rather than being deleted, so a server-side mistake cannot destroy the only copy
of a run; anything the server did not accept stays put and is retried at the next start.

## Run reports

The debug log is a diagnostic: bounded at 20 000 events and switchable off in `/sa`. What has to
survive instead goes into `config/sighteaddons/runs/run-<millis>-<uuid>.json`, one closed file per
finished run, which the server files under that player's permanent profile. Room difficulty and
per-room clear scores are derived from that history later — the mod does no scoring itself.

Per run: floor, duration, party size, the classes present, own class and level, rooms cleared,
unattributed remainder, deaths, mod and Minecraft version, and a `v` schema number. Per room: name,
type, shape, secret and crypt counts, segment count, clear and all-secrets tick, whether it was
already green on arrival, secrets found, own secrets, deaths, and the effort numbers —
`playerTicks` (the whole party's time in the room), `playersInRoom`, `ownTicks`. One player for 60
seconds and four players for 15 are the same clear time and very different rooms, which is exactly
what a difficulty estimate needs to see.

Teammates appear in aggregate only — party size, classes without names, player-ticks. Installing
the mod is consent for your own data; the four strangers from party finder never gave any.

The server side is one file, [`vps/SETUP.md`](vps/SETUP.md): a stdlib receiver, the master prompt
for the Claude agent that reads the sessions and opens pull requests, and the setup to paste.

## Build

Needs JDK 25 or newer. Bytecode targets 25 (what Minecraft 26.1.2 requires) via `--release`, so a
JDK 26 works too — no toolchain is pinned. If Gradle 9.5.1 refuses your JDK as a daemon JVM, use 25.

```bash
./gradlew build
```

```bash
./gradlew test
```

The latest build is committed at [`dist/sighteaddons-0.4.0.jar`](dist/sighteaddons-0.4.0.jar) —
`build` copies it there automatically, so the checked-in jar can never go stale relative to the
source. The filename carries `mod_version`, so a jar already sitting in `mods/` still says which
build it is; the copy deletes the previous version, so `dist/` holds exactly one jar and git history
holds the older ones.

`build/libs/sighteaddons-<version>.jar` is the same file. The
`-sources.jar` next to it is source code for IDE navigation — it contains a `fabric.mod.json` but no
classes, so putting it in `mods/` produces a duplicate mod id and the game will not start.

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
- **Per-floor records.** `/sa` collapses floors into one record per room and kind. The history lines
  carry `floor`, so a filter is available whenever a room turns out to differ per floor.
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
  against real Hypixel data. That is what the telemetry log is for. The `/sa` screen has the same
  status one step down: its API use is pinned by the compiler and its record fold is unit tested, but
  no pixel of it has ever been on screen, so spacing and hit boxes are unverified.

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
