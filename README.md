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
| Pseudonyms | `Pseudonym.kt` | replaces party member names on their way into that log |
| Run report | `RunReport.kt` | permanent per-run record: every room and what it cost the party |
| Upload | `TelemetryUpload.kt` | ships session logs and run reports to the analysis server |
| Settings | `SettingsScreen.kt` | the `/sa` screen: settings tabs and the room history |
| History table | `RecordTable.kt` | the rows behind that history: the room join, type filter, search, sort |
| Config | `Config.kt` | the settings that screen writes, plus the upload id, as one JSON object |
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

`/sa` opens the settings screen, `/sa pbs` jumps straight to the history. Four tabs, one row grid,
click a row to toggle it — every change is written to `config/sighteaddons/config.json` immediately,
so there is no save button that could be forgotten.

```
  SIGHTE ADDONS                                            0.9.0
  ────────────────────────────────────────────────────────────────
  hud    chat    history    debug
  ────────────────────────────────────────────────────────────────
  show HUD                                                     on
  position                                         4, 4 · place
  current room                                                 on
  standings                                                   off
```

The **debug** tab holds the upload switches: run reports on or off, *send my name* (off by default,
see [Sending your name](#sending-your-name--off-by-default)), and the upload id itself.

`position · place` switches to placement mode: the HUD is drawn at the cursor, the next left click
places it, right click cancels. The screen is drawn from filled rectangles and font calls only — no
textures, no widget library, and no config-screen dependency, which is why the mod still ships with
nothing but Fabric API and fabric-language-kotlin. That also rules out glyphs the 9px bitmap font
does not carry: the sort arrow and the progression bars below are rectangles, not `▲` and `▁`.

## History — `/sa pbs`

The history read back: one row per room with both records side by side — time in the room until it
cleared, and the secret run — how many runs the room was completed in, and how long ago that last
happened.

```
  SIGHTE ADDONS                                 45 rooms · 347 attempts
  ─────────────────────────────────────────────────────────────────────
  hud    chat    history    debug
  ─────────────────────────────────────────────────────────────────────
  all 45   puzzle 7   trap 2   rare 5   normal 26   other 5
  ─────────────────────────────────────────────────────────────────────
  room            type        clear      secrets     runs        last ▾
  Water Board     puzzle      0:41.2      0:12.4        7        today
      clear   ▁▃▂▅▁▂▁   best 0:41.2 · median 0:52.0 · 7 attempts
      floors  F7 0:41.2 · M7 0:44.0 · F6 0:48.1
      room    1x1 · 4 secrets · 2 crypts
  Tic Tac Toe     puzzle      0:22.8           –       10       3d ago
  Catwalk         normal      0:18.4      0:09.1       12       3d ago
```

The table has exactly one arrangement at any time: **a chip says which rooms, a column says in which
order, and neither changes the other.** The type chips filter — `other` is the leftovers, so no room
can fall out of every chip — and every column sorts, with a second click on the same one reversing
it. Rooms with no time for that column stay at the bottom in both directions; reversing `secrets`
should not open the table on a screen of dashes. The `type` column only appears under `all`, where it
is the only place that word is not already on the screen.

**Type to filter.** There is no input box: any character starts a search on the room name, backspace
deletes, and the first escape clears it before the second one closes the screen. While a search is
active it replaces the counts in the top right, and the chip counts follow it — so a chip always
advertises the number of rows a click on it produces.

**Click a room** for its detail: the progression of its clear times as one bar per attempt (oldest
left, personal bests in gold, capped at twice the median so one wipe does not flatten the rest), its
best time per floor, and the shape, secrets and crypts from the room database. All of that already
sat in `history.jsonl` — every line has carried its floor and a PB flag since the first version, and
the table simply never read them back.

Records are derived from `history.jsonl` at startup, not stored — floors are collapsed for the record
itself on purpose, because the metric is time spent in the room and stays comparable across floors.
The chat settings hide announcements only: history is always written, so silencing chat never costs a
record.

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

**Off for an ordinary install**, on in the development environment. The public upload tier never
ships sessions — only the private one does — so a session written on somebody else's machine is a few
MB per launch that nobody can ever read. Turn it on in `/sa` → **debug** → *JSONL telemetry*, or with
`-Dsighteaddons.debug=true`.

The property only seeds the *first* launch: from then on `config.json` holds the value and the `/sa`
switch owns it. An install that ran a version before 0.5.2 therefore already has it persisted as on,
and turning it off is one click in that screen.

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
| `tab_slot` | Does the tab regex still match Hypixel's format? Logs the row, name replaced. |
| `player_room` | Which decoration became which player, in which cell — with the raw decoration. |
| `room_identified` | Which rooms the chunk scan named. |
| `room_unmatched` | A cell has a room but no database match — **logs the full block column**, which separates "hashed the wrong blocks" from "room not in the database". |
| `room_discovered`, `cleared`, `all_secrets` | Segment grouping and the checkmark timeline. |
| `award`, `unattributed` | Where points went, and which rooms nobody earned. |
| `run_start`, `run_end` | Totals, plus how many rooms stayed unnamed. |

Volume is bounded: only *changes* are written, and the file stops at 20 000 events with an explicit
`truncated` record rather than silently.

### Upload

> **This mod uploads by default.** When a dungeon run ends — finished or left early — a report of it
> is sent to the mod's analysis server: rooms, times, party size and classes, filed under a random id
> that is generated on
> your machine. **Your Minecraft name and UUID are not part of it** — not unless you switch the name
> on yourself, see [Sending your name](#sending-your-name--off-by-default) — **and nobody else's ever
> is.**
> Switch it off in `/sa` → **debug** → *upload run reports*. The mod says this once in chat the first
> time it runs. The transport is HTTPS.

Two tiers, and what separates them is what may leave the machine:

| | who | what goes up |
|---|---|---|
| **Public** | every install, on by default | run reports only |
| **Private** | whoever has `upload.properties` | run reports **and** debug sessions |

The public tier uses a token compiled into the jar. That token is readable by anyone who unzips the
mod, so it is a filter against drive-by noise and **not** a secret — the server treats everything on
that tier as untrusted and validates it field by field rather than believing the bearer.

Debug sessions are deliberately not in the public tier. They name every party member — pseudonymously
(see below), but still people who installed nothing and agreed to nothing. A run report is about its
own uploader and nobody else, which is what makes it fair to send by default.

The private tier is opt-in by existing: `config/sighteaddons/upload.properties`, next to the debug
folder.

```properties
url=https://<host>
token=<shared secret>
```

Base URL only; the mod appends `/ingest` for sessions and `/runs` for the run reports below. The
file is never written by the mod and never committed — the token stays on the machine that plays.
A file that is present but missing a key switches uploading **off** rather than quietly falling back
to the public tier, so a typo cannot look like it worked.

### Your upload id

`/sa` → **debug** shows the id every one of your run reports is filed under. It is random, generated
once on first launch, and stored in `config.json`. Nothing on the server can turn it back into a
player — which is the point: until you say otherwise there is nothing in there that points at you.

Deleting `config.json` starts a new id and orphans the history under the old one.

### Sending your name — off by default

`/sa` → **debug** → *send my name* adds your Minecraft name to every run report from then on, as a
`player` field next to the id. **Off unless you switch it on.** A leaderboard needs a name to put on
a row, and that name is yours to give — not a default anybody can be opted into.

The row shows the name itself instead of the word *on*, so what would leave the machine is legible
before the click rather than after it. If run reports are switched off it says so too: a name with
nothing being sent is a reason to wonder why no leaderboard ever knows you.

The id stays the identity either way. The name annotates it rather than replacing it, so switching
back off keeps the profile and merely stops naming it.

**The switch reaches back as far as the queue, and no further.** A report is written when the run ends
but only handed over at the *next* game start, so there is a window where a report sits in
`config/sighteaddons/runs/` waiting. Flipping the switch rewrites what is still in there — in both
directions, so turning it off un-names them again. The boundary is `runs/uploaded/`: once a report has
left the machine it stays exactly as it left, and so does everything already on the server. Three runs
played and *then* opted into therefore go up named; three runs already uploaded stay anonymous.

**It names you and nobody else.** Teammates never appear by name in a run report at all, and this
changes nothing about that: the four strangers from party finder cannot consent through somebody
else's settings screen. Same reason debug sessions stay off the public tier even pseudonymised.

`player` needs no schema bump of its own — it has been the run report's one optional key since the
receiver was written (`check_run` in the server's `ingest.py`), precisely so this could be switched on without
invalidating a single stored report. An anonymous report omits the key rather than sending null:
`check_run` rejects `"player": null` outright, so absent is the only way to say nothing. A report the
switch reached carries the key at the end of the object instead of after `uuid`, which the validator
does not care about — it walks its own field list.

The receiver decides what to *keep* by schema version, not by the field. Below v3 a name is dropped
before the profile line is written: v1 sent the Minecraft name unconditionally, before any of this was
a choice, and such a report can still be sitting in somebody's backlog. From v3 the mod writes the
field only when this switch is on, so there its presence is the consent and it is kept — see
`to_store` and `NAMED_FROM_SCHEMA` in the server's `ingest.py`. Without that half the switch would validate,
upload, and change nothing.

Uploads happen **at game start, for what previous sessions left behind** — not at `run_end`, which
never fires when the game crashes or the run is left early, losing exactly the material worth
looking at. It also keeps networking out of the tick loop. Whatever was handed over moves to an
`uploaded/` folder rather than being deleted, so a server-side mistake cannot destroy the only copy
of a run. A file over the receiver's size cap is left alone and named in the log instead of being
sent — a finished session is a few MB, so that only happens when something else has already gone
wrong.

A refusal the server will repeat — `400`, `413` — moves the file to `rejected/` rather than leaving
it. Files go oldest first, so one report from an older schema left in the queue would sit in front of
every newer one and block them all, at every launch, for good. It is moved rather than deleted,
because a receiver-side bug is also a reason for a `400`. Everything else stays put and is retried at
the next start; only a refused token stops the run, since that one fails every file identically.

### What is in an uploaded session

**No party member names.** Everyone in the log — teammates and you — appears as a pseudonym like
`p-3f8a1c04`, derived from a random salt that is generated fresh every launch and never written
down. Within one session the pseudonym is stable, so "who was in which room" still reads normally;
across sessions the same player gets a different one, so the server never accumulates a history of
somebody who never agreed to any of this. The raw tab row is logged with only its name replaced, and
a row the regex *fails* on — the case that event exists for — is redacted conservatively instead,
keeping the format and dropping anything name-shaped.

`RunReport` goes further and names nobody at all — not teammates, and since 0.5.0 not you either.
Party size, classes and player-ticks, filed under your upload id. No Minecraft UUID anywhere in it,
and no name unless you switch *send my name* on, which adds yours and only ever yours.

The transport is HTTPS: Caddy terminates TLS on the box and forwards to the receiver on loopback, so
nothing travels readable. That protects what is on the wire and nothing else — the public token is
compiled into the jar and stays a spam filter rather than a secret, which is why the server validates
every field instead of trusting the bearer. Nobody else's name travels either way.

## Run reports

The debug log is a diagnostic: bounded at 20 000 events and switchable off in `/sa`. What has to
survive instead goes into `config/sighteaddons/runs/run-<millis>-<uuid>.json`, one closed file per
run, which the server files under that player's permanent profile. Room difficulty is
derived from that history on the server by `roomstats.py`, which averages how long each room
takes to clear and how long its secrets take **separately** — the mod does no scoring itself.

Per run, not per *finished* run. Leaving a floor early is the most ordinary thing a party does, and
the rooms they cleared on the way are as valid as anybody's — reporting only completed runs threw all
of them away. A run that was left carries `complete: false`, which is the only thing separating it
from a whole one: `runTicks`, `roomsCleared` and `deaths` then describe the part that was played,
while every room entry means exactly what it always did. Since schema 4; in an older line the field is
absent and reads as complete, because back then a report only existed once the run had ended.

Per run: floor, whether it completed, duration, party size, the classes present, own class and level,
rooms cleared, unattributed remainder, deaths, mod and Minecraft version, and a `v` schema number.
Per room: name, type, shape, secret and crypt counts, segment count, entry, clear and all-secrets
tick, whether it was already green on arrival, secrets found, own secrets, deaths, and the effort
numbers —
`playerTicks` (the whole party's time in the room), `playersInRoom`, `ownTicks`. One player for 60
seconds and four players for 15 are the same clear time and very different rooms, which is exactly
what a difficulty estimate needs to see.

Those three ticks are run timestamps, not durations, and they only say anything in pairs. `enterTick`
to `clearTick` is how long the room took to clear; `clearTick` to `secretsTick` is how long the
secrets took after that. Keeping the two apart is the point — a room can be a long fight with
trivial secrets or the other way round, and one combined number hides exactly that. `enterTick`
arrived with schema 3, so runs uploaded before it have no clear duration and never will.

Teammates appear in aggregate only — party size, classes without names, player-ticks. Installing
the mod is consent for your own data; the four strangers from party finder never gave any.

The server side is its own repository now,
[Sighte/skyblock-server](https://github.com/Sighte/skyblock-server): a stdlib receiver, the per-room
averages folded back out of the profiles, the master prompt for the Claude agent that reads the
sessions and opens pull requests here, and the whole box's setup. It deploys itself when its `master`
moves, which is why it no longer lives in this repository — nothing about a jar players install has to
move at the speed of a server.

## Build

Needs JDK 25 or newer. Bytecode targets 25 (what Minecraft 26.1.2 requires) via `--release`, so a
JDK 26 works too — no toolchain is pinned. If Gradle 9.5.1 refuses your JDK as a daemon JVM, use 25.

```bash
./gradlew build
```

```bash
./gradlew test
```

The latest build is committed at [`dist/sighteaddons-0.9.0.jar`](dist/sighteaddons-0.9.0.jar) —
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
