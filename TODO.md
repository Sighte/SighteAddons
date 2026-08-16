# TODO — SighteAddons

## Stand — 2026-08-17

`main` = 314 Tests / 23 Klassen / grün. `mod_version` 0.15.0, released, Modrinth-Version `YNlbBvlI`
mit identischem Jar. `RunReport.SCHEMA` 6, Receiver (`master` `1a7f435`) akzeptiert `idleTicks`/
`navTicks` als optional und ist deployt — **dem Receiver ist nichts geschuldet.**

## Laufend: UI-Redesign (7 Phasen, Review-Gate nach jeder)

Monochromes Design-System — weiß/grau/schwarz, kein Farbton — unter allen vier Oberflächen: HUD,
Stats-Screen, Config, Chat. Die Tracking-Schicht wird **nicht** angefasst; der Renderer bekommt
stattdessen einen Read-only-Snapshot. Plan liegt außerhalb des Repos beim Harness.

**Der User hat `./gradlew runClient` für diese Arbeit ausdrücklich freigegeben** — Ausnahme von
CLAUDE.md-Regel 2, gilt nur hier und fällt danach zurück.

- [x] **Phase 1** — Tokens, Motion, Gallery. `ui/theme/` (`Tokens`, `Palette`, `Contrast`,
  `Density`), `ui/motion/` (`Clock`, `Easing`, `Animatable`, `Spring`, `Motion`),
  `ui/render/DevicePixels`, `ui/screens/GalleryScreen` hinter `/sa gallery`. Nichts sichtbar geändert.
- [ ] Phase 2 Komponenten · 3 HUD+Editor · 4 Stats · 5 Config+Migration · 6 Chat · 7 Politur

Zwei Sachen aus Phase 1, die nicht in der Vorlage standen:

- **`text.tertiary` `#6C7078` verfehlt die eigene 4.5:1-Vorgabe** — 3.57:1 auf `surface.overlay`,
  2.88:1 auf einer gedrückten Overlay-Zeile. Die Vorlage widerspricht sich selbst; die Grenze hat
  gewonnen, `#91959D` (dunkel) / `#5F636B` (hell). Kosten: der Abstand zu `secondary` schrumpft von
  2.0:1 auf 1.27:1. `UiThemeTest` misst das statt es zu glauben.
- **`Density` leitet den Maßstab aus `framebuffer / guiScaled` ab, pro Achse** — nicht aus
  `getGuiScale()`. Bei 1366×768 / Scale 4 ist x = 3.9942 und y = 4.0; mit der nominellen 4 driftet
  eine 420px-Fläche um 0.6 Gerätepixel, und ein Rand verschwindet.

Das Modrinth-Projekt antwortet Nicht-Eingeloggten mit 404 (noch in Review), der Build ist also nur
per direktem CDN-Link erreichbar. Veröffentlichen ist dein Schritt.

## Das Wertvollste zuerst: ein gespielter Floor

Ein echtes Session-Log ab 0.12.0 beantwortet mehr als jede Arbeit hier und kostet nichts. Es klärt:
ob `secret_room_first_bar` je `untouched: true` trägt (ohne vertrauenswürdige `0/N` beim Betreten
werden Secret-Records nicht selten, sie **hören auf**); ob `MapDecoration.name()` etwas trägt (wenn
nicht → `party-001` schließen statt mitschleppen); ob Hypixel die Storm-/Crit-/Chat-Strings so
sendet. `own_pickup`, `pickup_unmatched`, `crit_unparsed` und `storm_unparsed` können überhaupt erst
in einem *released* Build entstehen.

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
- **`chatfields-001`** — blockiert, erster Schritt liegt im Receiver. Nicht mit `RunReport.kt` anfangen.
- **`records-001`** — vom User zurückgestellt (Produktentscheidung). Ein Raum behält einen Record
  über alle Floors; der Receiver faltet absichtlich genauso.
- **`party-001`** — der Mechanismus existiert nicht: kein Dekorations-Key überlebt die Leitung in
  26.1.2, Party-Sync verbietet das eigene Design. Wartet auf `deconame-001`.

## Unverifiziert

**Keine Wiring-Zeile von `ownsecrets-001`, `secretpoints-001`, `idletime-001`, `secrethud-001` und
`recordowner-001` ist je im Spiel gelaufen** — die reine Logik deckt die Suite ab, die Verdrahtung
nicht. Ein Floor klärt alles davon mit dem Auge.

Dazu: `SECRET_ITEMS` sind zehn geratene Namen (exakter Match, nie Präfix); der Mixin nutzt
`require = 0`, ein falscher Injector schweigt also — Erkennung ist das *Fehlen* von `own_pickup`;
eine aus der Distanz getötete Fledermaus bleibt unbelohnt (`AttackEntityCallback` ist ein
Nahkampfschlag), und das ist die Richtung, in die dieser Fehler gehen darf. Die Tick-Zahlen von
Storm/Crit (138, 20, `TIME_WORTH = 2.5`) sind unsichtbar falsch, deshalb stehen sie als `/sa`-Zeilen.
Altlasten: die falschen Bests in `history.jsonl` sind nicht reparabel (akzeptiert), `runTicks` ist
auf dem DISCONNECT-Pfad nicht `@Volatile`, `RoomStats.start()` lief nie im Spiel.
