# Sighte Addons

Client-side Fabric mod for Hypixel SkyBlock Catacombs that answers a question Hypixel does not:
**who in the party actually cleared which rooms.**

Hypixel exposes party totals only — secrets, deaths, cleared %, never per-player values. The dungeon
item map, however, shows every party member's position (even outside render distance) *and* every
room's clear checkmark. Sampling both every tick gives full per-player attribution with no party sync.

Minecraft **26.1.2** · Fabric · client only

## Install

Fabric Loader `>=0.19.3`, Fabric API, fabric-language-kotlin `>=1.13.13`, Java 25 or newer. Drop the
jar into `mods/`. Nothing is bundled, so both dependencies have to be there too.

## What it does

- **Per-player room attribution** — who was in the room, for how long, and who cleared it. A record is
  yours only when the work was: you were there from the start and you found every secret in it.
- **Room times and personal bests**, appended permanently and announced in chat. `/sa pbs` shows them.
- **Splits** — the run as spans (blood, portal, every boss phase, total), each with a wall clock and
  the same span in Hypixel's server ticks, which is the number two players can compare. Records per
  split per floor, on Odin's own keys, so `/sa import` folds an existing Odin install's in.
- **Run records** — best time for a whole run, per floor *and* per party size.
- **Solo clears, announced** — one line per solo run, or the moment a run reaches a score you set.
- **Teammate secrets** — each player's count for the run, as the rise in their lifetime total. Looked
  up through this project's own receiver, so there is no key to enter and no setting for one.
- **A HUD you place** — map, clear popup, storm countdown, splits panel and split clock, each by anchor
  and offset. Drag to move, arrow keys to nudge, scroll wheel to scale, `r` to reset.

`/sa` opens the settings. Every change is written to `config/sighteaddons/config.json` at once — there
is no save button that could be forgotten.

## What leaves your machine

**Run reports do, by default** — one anonymous JSON per run, uploaded at the *next* game start, so a
crash or an early leave cannot lose it. They carry the floor, the rooms and what they cost, and an
install id that nothing on the server can turn back into a player. Teammates appear in aggregate only:
party size, classes, player-ticks, never a name. `/sa` → **debug** turns it off.

**Three switches are off until you turn them on**, and each is its own decision:

| Switch | What it sends |
|---|---|
| *send my name* | your name on your own run reports, and nobody else's |
| *announce in discord* | one line per solo clear into the team's channel |
| *send new bests* | a standing leaderboard row for a run record |

The JSONL debug log is off for an ordinary install. When it is on, everyone in it — teammates and you
— appears as a pseudonym derived from a salt made fresh every launch and never written down.

The server side is its own repository:
[Sighte/skyblock-server](https://github.com/Sighte/skyblock-server).

## Build

```bash
./gradlew build     # jar + copy to dist/
./gradlew test
```

`dist/sighteaddons-<version>.jar` is the committed build, and `dist/` holds exactly one — git history
holds the rest. Loom 1.17 defaults to official Mojang mappings for 26.x, so class names are Mojmap
(`MapItemSavedData`, not `MapState`); there is no `mappings` line in `build.gradle` on purpose.

## Known limits

Room names need a loaded chunk: the core hash reads a block column, so whatever Hypixel has not sent
stays unnamed, and rooms cleared there are scored without a record. And the room database is
version-coupled — core hashes come from `Block.toString()` in a fixed order, so if Hypixel changes a
room or Mojang changes that representation, `RoomDatabase.coreAt` has to be re-verified against the
data source.

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

MIT, except where the above says otherwise. See `LICENSE`.
