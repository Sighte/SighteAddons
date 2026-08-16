# TODO — SighteAddons

State and open work. Amend it; don't rewrite it. `git log` is the history.

## State — 2026-08-17

- **`main` is `a152328`, in sync with origin, 289 tests in 21 classes, 0 failures 0 skipped.**
  `ownsecrets-001` is merged. `mod_version` is 0.15.0, tagged and published; Modrinth version
  `YNlbBvlI` carries the identical jar (`sha1 de94c224…`, 260722 bytes, compared not assumed).
- **`RunReport.SCHEMA` is 6 and the pair is closed.** `skyblock-server`'s `master` `1a7f435` is
  deployed and accepts `idleTicks`/`navTicks` as *optional*, so v5 reports still validate.
  `python build/keydiff.py` is CLEAN. Nothing is owed to the receiver.
- **The release does not reach players, and not for a reason in this repository.** The Modrinth
  project answers 404 to anyone not logged in — still awaiting review — so the build is reachable by
  direct CDN link and nothing else. Publishing the project is the user's step.

## The single highest-value input: one played floor

**Read a real session log from 0.12.0 or later.** It settles more open questions than any amount of
work here, and it is free. Logs:
`%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-*.jsonl`
(read-only; one containing `secret_room_first_bar` is 0.12.0+). It answers: whether
`secret_room_first_bar` ever carries `untouched: true` (`recordowner-001`'s whole remaining ceiling —
without a trusted `0/N` on entering a room, secret records do not get rare, they **stop**); whether
`MapDecoration.name()` carries anything (`deconame-001` — **if not, close `party-001` rather than
carry it**); whether Hypixel sends the storm/crit/chat strings at all. A *released* build carrying
the current `main` is what produces `own_pickup`, `pickup_unmatched`, `crit_unparsed` and
`storm_unparsed` — the only evidence those strings will ever have.

## Open

- **`floorname-001` — cheapest on the board.** The receiver `fullmatch`es `floor` against
  `?|E|[FM][1-7]` (`ingest.py:93`, used `:225`) and `DungeonSession.floor` can hold `Entrance`. A
  400 is never retried, and `keydiff.py` compares key *sets*, so it can never catch this. Map
  `Entrance` → `E` here; no receiver change, no pairing.
- **`runend-001`** — `run_end` (`SighteAddons.kt:149`) writes floor, roomsCleared, points, unnamed,
  newRecords but **not `unattributed`**, which the receiver's own `AGENT-PROMPT.md:62` tells its
  analyst to read against `roomsCleared`. One field on a debug event; not a schema change, not paired.
- **`secretburst-001`** — `SecretTracker.onActionBar` does `room.ownSecrets++` once however large
  `delta` is, so a rise of 2 that was entirely yours counts 1 and `ownSecretRun` fails for the room.
  Pickup credit makes it likelier, not rarer. **The fix is not `ownSecrets += delta`** — that credits
  a teammate's secret whenever one of yours shared the reading. It needs a count of unspent signals.
- **`deconame-001`** — log whether the map's decorations carry a name. One field on the existing
  `player_room` event. The design question that makes it a feature: a populated name is most likely
  the teammate's IGN, and a raw IGN must not reach the log — but a redacted value cannot tell you
  what the name *was*, which is the thing this is trying to find out.
- **`nearmiss-001`** — the near-miss log is unreadable for the diagnosis it exists for. Measured over
  three real M7s: all 32 `chat_unparsed` events are *player chat*, which can never match a dungeon
  pattern, and `Pseudonym.row` over free text turns every whitespace token into a pseudonym. Over-
  redaction, not a leak — and the fix must not become an under-redaction.
- **`chatfields-001` — blocked, and its first move is in the other repository.** `chat-001` already
  reads wither/blood doors, puzzles and per-secret finders into the debug log; putting any of them in
  the run report adds a key to `RunReport.build` and the receiver has to accept it first. Do not
  start by editing `RunReport.kt`.
- **`records-001` — deferred by the user**, a product decision, not a blocker. A room on more than
  one floor keeps ONE record and ONE average; the receiver folds the same way on purpose.
- **`party-001` — the mechanism it was written around does not exist.** No decoration map key
  survives the wire in 26.1.2; `MapDecoration.name()` is the only per-decoration identity channel
  left. Party sync is forbidden by the mod's own design (`README.md:9`, `SighteAddons.kt:24`).
  Two players in one room produce two decorations a few pixels apart and a swap is harmless — both
  resolve to the same `Pos`. Waits on `deconame-001`.

## Unverified — none of this can be checked on this machine

- **Not one line of wiring for `ownsecrets-001`, `secretpoints-001`, `idletime-001`,
  `secrethud-001` or `recordowner-001` has ever run in a game.** The pure halves are covered by the
  suite; the wiring is not, and removing an `IdleTime` wiring line passes the whole suite.
  One floor settles all of them by eye.
- **`ownsecrets-001`'s vocabulary is a guess.** `SECRET_ITEMS` is an exact-match whitelist of ten
  names — never prefix or substring — and `pickup_unmatched` (deduplicated, capped at 32 a run) is
  the only thing that can correct them. The mixin uses `require = 0`, so a wrong injector is silent;
  detection is the *absence* of `own_pickup`. A bat killed at range stays uncredited by construction
  (`AttackEntityCallback` is a melee swing) — the direction this must err in.
- **`stormtimer-001` / `critcalc-001`: two ceilings each, and only one can announce itself.** The
  strings (a `storm_unparsed`/`crit_unparsed` with no readout says they are wrong and quotes what was
  said; *both* absent says the line never reached `onChat`), and the tick counts — 138, 20,
  `TIME_WORTH = 2.5` — which are invisible when wrong. Both are `/sa` rows for that reason.
- **The strict gate is the user's reaffirmed decision.** `RoomHistory.ownSecretRun` stays
  `ownSecrets == secretsFound`, keeping roughly one record in seven. It is shipped and documented in
  0.12.0's public notes. `ownsecrets-001` fixes the number the gate reads, not the gate.
- **Never add back, for both halves of the crit/storm port:** the source mod's `ApiSender` (POSTed
  name/crit/power/ratio to a third party on every hit, no toggle) and its automatic `/msg` + party
  lines. Neither is present in any form behind any flag.
- Carried forward: the bogus bests in `history.jsonl` are not repairable (the user was told and
  accepted it); a `topPlayer` tie resolves in hash order; `runTicks` is read on the DISCONNECT path
  and is not `@Volatile`; `SighteAddons.RUN_END` has no test of any kind; `RoomStats.start()` has
  never run inside a game; the tab-footer mixin was checked to *apply*, not to return anything.
