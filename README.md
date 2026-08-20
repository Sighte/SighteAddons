# Sighte Addons

Client-side Fabric mod for Hypixel SkyBlock Catacombs that answers a question Hypixel does not:
**who in the party actually cleared which rooms.**

Hypixel only exposes party totals (secrets, deaths, cleared %) — never per-player values, except on
the end-of-run Extra Stats screen. The dungeon item map, however, shows every party member's position
(even outside render distance) *and* every room's clear checkmark. Sampling both every tick gives full
per-player attribution with no party sync.

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
| Teammate secrets | `SecretApi.kt` | each player's count for the run, as the rise in their lifetime total — looked up through the receiver, so there is no key to enter and no setting for one |
| History | `RoomHistory.kt` | append-only permanent room times, chat announcements, run summary |
| Splits | `Splits.kt`, `DungeonSplits.kt` | the run in spans: blood, portal, every boss phase, and the total |
| Split records | `SplitPbs.kt` | best seconds per split per floor, on Odin's own keys |
| Server ticks | `ServerTicks.kt` | Hypixel's tick beat, counted off its keep-alive packets |
| Telemetry | `DebugLog.kt` | JSONL event log for diagnosing a real run |
| Pseudonyms | `Pseudonym.kt` | replaces party member names on their way into that log |
| Run report | `RunReport.kt` | permanent per-run record: every room and what it cost the party |
| Upload | `TelemetryUpload.kt` | ships session logs and run reports to the analysis server |
| UI | `ui/` | design tokens, components, the HUD, and the `/sa` screens |
| Config | `Config.kt` | the settings those screens write, plus the upload id, as one JSON object |

## In game

The HUD draws the dungeon map with every party member on it, the run clock, and the current room with
how long *you* have been in it. Map, clear popup, storm countdown, splits panel and split clock are
each placed by anchor + offset and dragged in an editor (arrow keys nudge, `r` resets). Run totals —
idle/nav time and the standings — live in a panel behind a keybind that ships **unbound**; the `/sa`
screen says so and links to Vanilla's key screen.

The **splits panel** is the run as spans, ported from Odin: Mort's line opens `blood open`, and every
row after it is the time from the line that names it to the next one — `blood clear`, `portal entry`,
then the floor's own boss phases, with an optional `boss entry` row summing the first three. Each row
carries two times: the wall clock, and the same span in Hypixel's **server ticks**, which is the one
two players can compare because it does not include whatever the server was doing at the time. The
panel keeps drawing through the boss phase, where the map card deliberately fades out.

Best times are kept per split per floor, in `config.json`, **on Odin's own record keys** — so
`/sa` → debug → *import from odin* folds an existing Odin install's records in, taking the faster of
the two, and running it twice does nothing. Master mode keeps its records apart from the F floors even
though the two share their chat lines.

Two numbers that never change meaning: `cleared` is run-relative, the tick the room's checkmark
appeared. `secrets` is the **secret run** — a stopwatch that starts on the room's first secret and
stops on its last, not a timestamp. The Blood Room is measured as Odin's door-to-pass span instead,
and is its own record kind.

`/sa` opens the settings, `/sa pbs` the room history. Every change is written to
`config/sighteaddons/config.json` immediately — there is no save button that could be forgotten.

## Debug telemetry

**Off for an ordinary install**, on in the development environment. Turn it on in `/sa` → **debug** →
*JSONL telemetry*. The log goes into the game directory, so it stays with the instance:

```
<instance>/.minecraft/config/sighteaddons/debug/session-<millis>.jsonl
```

One JSON object per line, flushed immediately, so a crash keeps everything up to the last event. Only
*changes* are written and the file stops at 20 000 events with an explicit `truncated` record. The
events target exactly what cannot be verified by compiling: whether the two calibration anchors
resolved, whether the tab regex still matches, which decoration became which player, which rooms the
chunk scan named, and — for a cell with a room but no database match — the full block column, which
separates "hashed the wrong blocks" from "room not in the database".

### Upload

> **This mod uploads by default.** When a dungeon run ends — finished or left early — a report of it is
> sent to the mod's analysis server: rooms, times, party size and classes, filed under a random id
> generated on your machine. **Your Minecraft name and UUID are not part of it** — not unless you switch
> the name on yourself — **and nobody else's ever is.** Switch it off in `/sa` → **debug** → *upload run
> reports*. The mod says this once in chat the first time it runs. The transport is HTTPS.

Two tiers, and what separates them is what may leave the machine:

| | who | what goes up |
|---|---|---|
| **Public** | every install, on by default | run reports only |
| **Private** | whoever has `upload.properties` | run reports **and** debug sessions |

The public token is compiled into the jar and readable by anyone who unzips the mod, so it is a filter
against drive-by noise and **not** a secret — the server validates every field rather than believing
the bearer. Debug sessions are deliberately not in the public tier: they name every party member,
pseudonymously but still, people who installed nothing and agreed to nothing. A run report is about
its own uploader and nobody else, which is what makes it fair to send by default.

The private tier is opt-in by existing — `config/sighteaddons/upload.properties`, base URL only, the
mod appends `/ingest` and `/runs`:

```properties
url=https://<host>
token=<shared secret>
```

A file that is present but missing a key switches uploading **off** rather than quietly falling back to
the public tier, so a typo cannot look like it worked.

**Your upload id** is shown in `/sa` → **debug**: random, generated once, stored in `config.json`.
Nothing on the server can turn it back into a player. Deleting `config.json` starts a new id and
orphans the history under the old one.

**Sending your name is off unless you switch it on** (`/sa` → **debug** → *send my name*), and it names
you and nobody else — teammates never appear by name in a run report at all. The row shows the name
itself instead of the word *on*, so what would leave the machine is legible before the click. The
switch reaches back as far as the queue and no further: reports still waiting in
`config/sighteaddons/runs/` are rewritten in both directions, but once a report has moved to
`runs/uploaded/` it stays exactly as it left.

Uploads happen **at game start, for what previous sessions left behind** — not at `run_end`, which
never fires when the game crashes or the run is left early, losing exactly the material worth looking
at. Handed-over files move to `uploaded/` rather than being deleted, so a server-side mistake cannot
destroy the only copy. A refusal the server will repeat (`400`, `413`) moves the file to `rejected/`,
because otherwise one bad report sits in front of every newer one at every launch, for good.

**No party member names in a session log.** Everyone — teammates and you — appears as a pseudonym like
`p-3f8a1c04`, derived from a salt generated fresh every launch and never written down. Stable within a
session so "who was in which room" reads normally; different across sessions, so the server never
accumulates a history of somebody who never agreed to any of this.

## Run reports

What has to survive the 20 000-event bound goes into `config/sighteaddons/runs/run-<millis>-<uuid>.json`,
one closed file per run, which the server files under that player's permanent profile. Room difficulty
is derived there by `roomstats.py`, which averages clear time and secret time **separately** — the mod
does no scoring of its own.

Per run, not per *finished* run: leaving a floor early is the most ordinary thing a party does, and the
rooms cleared on the way are as valid as anybody's. A run that was left carries `complete: false`.

Per run: floor, completion, duration, party size, classes present, own class and level, rooms cleared,
unattributed remainder, deaths, mod and Minecraft version, and a `v` schema number. Per room: name,
type, shape, secret and crypt counts, segment count, entry/clear/all-secrets tick, whether it was
already green on arrival, secrets found, own secrets, deaths, and the effort numbers — `playerTicks`,
`playersInRoom`, `ownTicks`. One player for 60 seconds and four players for 15 are the same clear time
and very different rooms, which is what a difficulty estimate needs to see.

Those ticks are run timestamps, not durations, and only say anything in pairs: `enterTick` to
`clearTick` is how long the room took, `clearTick` to `secretsTick` how long the secrets took after
that. Keeping them apart is the point — one combined number hides whether a room was a long fight with
trivial secrets or the other way round.

Teammates appear in aggregate only — party size, classes without names, player-ticks. Installing the
mod is consent for your own data; the four strangers from party finder never gave any.

The server side is its own repository: [Sighte/skyblock-server](https://github.com/Sighte/skyblock-server)
— the receiver, the per-room fold, the analysis agent's prompt, and the whole box's setup.

## Build

Needs JDK 25 or newer. Bytecode targets 25 (what Minecraft 26.1.2 requires) via `--release`, so a JDK
26 works too — no toolchain is pinned.

```bash
./gradlew build     # jar + copy to dist/
./gradlew test
```

`dist/sighteaddons-<version>.jar` is the committed build; `build` copies it there and deletes the
previous one, so `dist/` holds exactly one jar and git history holds the older ones.
`build/libs/…-sources.jar` is for IDE navigation only — it carries a `fabric.mod.json` but no classes,
so putting it in `mods/` produces a duplicate mod id and the game will not start.

Runtime dependencies are not bundled: Fabric API `0.155.2+26.1.2` and fabric-language-kotlin
`1.13.13+kotlin.2.4.10`.

**Mappings:** Loom 1.17 defaults to official Mojang mappings for 26.x — Yarn stops at 1.21.11, and
there is no `mappings` line in `build.gradle` on purpose. Class names are Mojmap: `MapItemSavedData`
(not `MapState`), `Minecraft` (not `MinecraftClient`), `DataComponents` (not `DataComponentTypes`).

## Known limits

- **Room names need a loaded chunk.** The core hash reads a block column, so a room is only named once
  Hypixel has sent its chunk. Every dungeon chunk is hashed as it streams in and a dungeon is only
  12x12 chunks, so most of the map gets named without walking there — but whatever Hypixel does not
  send stays unnamed, and rooms cleared there are scored without a record.
- **The room database is version-coupled.** Core hashes are built from `Block.toString()` output in a
  specific order. If Hypixel changes a room or Mojang changes that representation, names break and
  `RoomDatabase.coreAt` must be re-verified against the data source.

## Credits

Room-detection maths, map colour IDs and API shapes were verified against three reference mods.

- [Skyblocker](https://github.com/SkyblockerMod/Skyblocker) (LGPL-3.0) — grid maths, map colour IDs,
  checkmark scan, current Mojmap/Fabric API shapes for 26.1.2. No code copied.
- [Odin](https://github.com/odtheking/Odin) (BSD-3-Clause, © 2025 odtheking) — **`RoomDatabase.kt` and
  the run splits are derivative works.** `RoomDatabase.kt` ports the core-hash algorithm from Odin's
  `WorldScan.getRoomCore` and `assets/sighteaddons/rooms.json` is Odin's room database verbatim; the
  hashes only match that data if the algorithm matches exactly. `DungeonSplits.kt` is a transliteration
  of the split tables in Odin's `SplitsManager.kt` — every chat pattern, the per-floor grouping and the
  order — and `Splits.kt` follows its chain semantics: a span credited to the earlier of two marks, the
  first signal winning, master mode reusing the F-floor lines, the boss-entry aggregate, and the
  `Starting in 1 second.` trigger. `SplitPbs.kt` keeps Odin's record keys so the two stores are the same
  data, and `ConnectionMixin.java` counts Hypixel's tick beat off `ClientboundPingPacket` the way Odin's
  mixin of the same name does. Also: per-floor boss-room bounds, the "centre pixel equals room colour
  means no checkmark" rule, tab-list regex, and the Blood Room clear split. See `LICENSE-Odin`.
- [NoammAddons](https://github.com/Noamm9/NoammAddons) (CC0-1.0) — decoration map keys carry a
  player-slot digit (noted as the upgrade path in `PartyTracker.kt`), run-end chat regex.
