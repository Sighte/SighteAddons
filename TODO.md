# TODO — SighteAddons

## Stand — 2026-08-20

`main` = **461 Tests / 42 Klassen / grün**, `mod_version` **1.0.0**.
`dist/sighteaddons-1.0.0.jar` ist die committete Datei. `RunReport.SCHEMA` 6, der Receiver akzeptiert
`idleTicks`/`navTicks` als optional und ist deployt — **für die Run-Reports ist dem Receiver nichts
geschuldet.**

Alles, was zwischen 0.16.0 und hier passiert ist, steht in `git log`: Splits-Port, Run- und
Split-PBs, die Solo-Clear-Ankündigung, `/sa import`, HUD-Scaling, das Icon, und der Key, der aus der
Mod verschwunden ist. Diese Datei trägt nur noch, was **nicht** erledigt ist.

## Der eine offene Deploy — `secrets-001`

**Die Receiver-Hälfte ist nicht deployt** (`skyblock-server`, in `master`, aber `SIGHTE_HYPIXEL_KEY`
fehlt in `/etc/sighte-ingest.env`, `chmod 600`, dann Restart). Ohne die Variable antwortet
`GET /v1/secrets/<uuid>` mit `503`.

Die Mod hat **keinen Key mehr und keinen Weg, einen einzutragen** — es gibt also keinen Rückfall auf
einen Override, den es früher gab. Bis der Key auf der Box liegt, stehen Mitspieler-Secrets in der
Zusammenfassung als Strich, genau wie vor dem Feature. Das ist leise, aber nicht kaputt: `404` latcht
`boxRouteMissing` für die Sitzung, `502`/`503` latchen nicht, und `secret_api_baseline` trägt `via`.

Regel 1 bleibt: Receiver zuerst. Ein Release der Mod-Hälfte ohne den Key liefert das Feature dunkel
aus, und das ist eine Entscheidung, keine Panne.

## Nie im Spiel gesehen

Das kostet nichts außer einem gespielten Floor und beantwortet mehr als jede Arbeit hier.

- **Der Solo-Gate.** `LiveScore.computedScore` gegen 270/300 ist Arithmetik gegen aufgezeichnete Runs.
  Offen: ob eine echte Solo-M7-Projektion 300 überhaupt kreuzt, oder ob nur die 270-Stufe benutzbar
  ist. Instrumentierung: `solo_clear_missed` (`projected`, `high`, `short`, `cleared`) und
  `solo_clear_unreleased`.
- **Die ~20 Split-Strings.** `splits_armed`, `split`, `split_missing` sind der Beleg; ein Name unter
  `unclosed`/`unstarted` auf einem Floor, der den Boss sicher erreicht hat, ist ein Pattern zum
  Korrigieren. **Odin 0.3.0 läuft parallel im Modpack** — ein Run zeigt beide Panels gleichzeitig.
- **Die Verdrahtung von `ownsecrets-001`, `secretpoints-001`, `idletime-001`, `recordowner-001`.** Die
  reine Logik deckt die Suite ab, die Wiring-Zeile nicht.
- **`secret_room_first_bar` mit `untouched: true`.** Ohne vertrauenswürdige `0/N` beim Betreten werden
  Secret-Records nicht selten — sie **hören auf**.
- **`MapDecoration.name()`.** Trägt es nichts, wird `party-001` geschlossen statt mitgeschleppt.
- `own_pickup`, `pickup_unmatched`, `crit_unparsed`, `storm_unparsed` können erst in einem *released*
  Build entstehen.

## Offen

- **`floorname-001`** — billigstes auf dem Board. Receiver macht `fullmatch` gegen `?|E|[FM][1-7]`
  (`ingest.py:93`), `DungeonSession.floor` kann `Entrance` halten → 400, nie wiederholt. Hier
  `Entrance` → `E` mappen. Keine Receiver-Änderung.
- **`runend-001`** — `run_end` schreibt kein `unattributed`, obwohl `AGENT-PROMPT.md:62` dem Analysten
  genau das gegen `roomsCleared` zu lesen sagt. Ein Feld auf einem Debug-Event.
- **`secretburst-001`** — `SecretTracker.onActionBar` macht `ownSecrets++` einmal, egal wie groß
  `delta` ist. Ein Anstieg um 2, der ganz deiner war, zählt 1. **Nicht `ownSecrets += delta`** — das
  schreibt dir fremde Secrets gut. Braucht einen Zähler unverbrauchter Signale.
- **`deconame-001`** — loggen, ob Dekorationen einen Namen tragen. Haken: ein gesetzter Name ist
  wahrscheinlich der IGN des Teamkollegen, der nicht ins Log darf — und ein redigierter Wert
  beantwortet die Frage nicht mehr.
- **`nearmiss-001`** — das Near-Miss-Log ist unlesbar: alle 32 `chat_unparsed` aus drei echten M7s
  sind Spielerchat, und `Pseudonym.row` macht jedes Wort zum Pseudonym. Überredigierung, kein Leak —
  der Fix darf nicht ins Gegenteil kippen.
- **`scores-002` (Mod-Hälfte)** — `weightOf` rechnet das alte geseedete Modell aus kompilierten
  Konstanten, `RoomScores.parse` liest `scores[]` nie. Die Box publiziert seit dem Score-Rewrite
  andere Zahlen als die Mod zahlt. Reihenfolge: serviertes `score`, dann letzte Kopie, dann die
  kompilierten Werte — **nie den Spielstart blockieren.**
- **`chatfields-001`** — blockiert, erster Schritt liegt im Receiver. Nicht mit `RunReport.kt` anfangen.
- **`records-001`** — vom User zurückgestellt (Produktentscheidung). Ein Raum behält einen Record
  über alle Floors; der Receiver faltet absichtlich genauso.
- **`party-001`** — der Mechanismus existiert nicht: kein Dekorations-Key überlebt die Leitung in
  26.1.2, Party-Sync verbietet das eigene Design. Wartet auf `deconame-001`.
- **`boss_phase` mit `by: "map"`** heißt: die Koordinaten haben nicht gegriffen. Dann ist die
  Sidebar-Zeile `Cleared: X%` der nächste Kandidat. `[BOSS] ` ist als Signal verbrannt — The Watcher
  steht im Blood-Room mit demselben Prefix.
- Allokationsfrei pro Frame ist erreicht; offen bleibt der `Origin`, den `Config.hudOrigin` pro Frame
  anlegt.
- Das Modrinth-Projekt antwortet Nicht-Eingeloggten mit 404 (noch in Review). Veröffentlichen ist
  dein Schritt.

## Altlasten, akzeptiert

`DungeonTab.ELAPSED` **greift** (11 `tab_time`-Events am 19.08.), und daran hängt die angekündigte
Zeit: Hypixels eigene Uhr ist die, die zwei Spieler vergleichen können, `runTicks` beginnt erst bei
der Kalibrierung und ist systematisch zu kurz. Beide Records werden getrennt gehalten, damit keine
Sekunde je gegen einen Tick antritt.

`SECRET_ITEMS` sind zehn geratene Namen (exakter Match, nie Präfix); der Mixin nutzt `require = 0`,
ein falscher Injector schweigt also — Erkennung ist das *Fehlen* von `own_pickup`. Eine aus der
Distanz getötete Fledermaus bleibt unbelohnt (`AttackEntityCallback` ist ein Nahkampfschlag), und das
ist die Richtung, in die dieser Fehler gehen darf. Die Tick-Zahlen von Storm/Crit (138, 20,
`TIME_WORTH = 2.5`) sind unsichtbar falsch, deshalb stehen sie als `/sa`-Zeilen.

Die falschen Bests in `history.jsonl` sind nicht reparabel (akzeptiert), `runTicks` ist auf dem
DISCONNECT-Pfad nicht `@Volatile`, `RoomStats.start()` lief nie im Spiel.
