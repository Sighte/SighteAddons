# TODO — SighteAddons

## Stand — 2026-08-17

`main` = 336 Tests / 27 Klassen / grün. `mod_version` 0.15.0, released, Modrinth-Version `YNlbBvlI`
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
- [x] **Phase 3a** — HUD. Auf Wunsch des Users vor Phase 2 gezogen; Komponenten werden nachgereicht,
  wie das HUD sie braucht. Sprite-Sheet (`tools/gen_ui_sheet.py` → 7 KB, **die ersten Texturen der
  Mod**), `ui/render/` (`Sheet`, `Surface`, `Effects`), `ui/hud/` (`HudSnapshot`, `HudRoot`,
  `Glyphs`, `HudKeys`), HUD-Vorschau als Gallery-Seite 4 mit gescriptetem Lauf.
  Der alte Corner-Readout ist weg; `idle`/`nav` und die Standings sitzen jetzt in den ausklappbaren
  Run-Totals (Keybind, standardmäßig ungebunden).
  Die Karte blendet sich beim Betreten des Bossraums aus (`inBoss` auf dem Snapshot) — der Run-Clock
  läuft darunter weiter. **Der User testet gerade `0.16.0-dev8`** (nur `build/libs`, `assemble check`
  mit `-Pmod_version=…`, `dist/` bleibt das released 0.15.0).
- [x] **Phase 6 — Chat.** Tag (`SA »`, ein Funnel `Chat.say`, die drei alten Selbstbezeichnungen
  sind weg) **plus** Farben und Wortlaut. Kein `ChatFormatting` mehr in einer Zeile, die die Mod
  schreibt — vier benannte Rollen in `Chat.kt` (`value`/`label`/`meta`/`emphasis`) auf `Palette.DARK`,
  ein Feldtrenner (`Chat.FIELD`, ` · `) statt vier, eine Satzreihenfolge (wer — was — wie lange — wie
  gut), eine Zeitschreibweise (`DungeonGrid.formatTicks`, auch für das PB-Delta). Betonung ohne
  Farbton: Position (PB immer letztes Feld) + Wort (`PB`, `too many`) + erst dann die Rampe.
  Kontrast gegen Vanillas `0x80000000`-Backdrop über schwarzer Welt gemessen; über *weißer* Welt
  schafft **nichts** 4.5:1 (Maximum 4.00:1, reines Weiß) — steht als Begründung in `Chat.kt`.
  `ChatFormatting.stripFormatting` in `DungeonSession.kt`/`SighteAddons.kt` liest fremde Namen und
  bleibt.
- [x] **Blood-Room = Odins `Blood Clear`-Split** (`BloodClear.kt`), eigener Kind `bloodclear`, kein
  `clear` mehr für den Raum. Die 8 alten Blood-Zeilen aus der lokalen `history.jsonl` sind gelöscht
  (Backup `history.jsonl.bak-20260817-190525`), der Server ist **nicht** angefasst — die Blood-Zeilen
  in `profiles/` stehen weiter drin.
  **Offen dazu:** das HUD zeigt für den Blood-Room keinen Split. Die Live-Uhr dort sind deine Ticks
  im Raum, der Record ist die Tür-bis-Pass-Spanne — ein Delta zwischen beiden wäre eine Lüge. Wenn es
  aufs HUD soll, muss die laufende Blood-Uhr in den Snapshot.
- [x] **`SecretAudit`** — der Live-Tracker wird am Runende gegen die API benotet, `secret_audit` ins
  Debug-Log, zwei Richtungen getrennt (`missed` ist Design, `too many` ist ein Defekt).
  **Läuft noch nie:** `Config.hypixelKey` ist leer und hat **keine UI** — nur `config.json`. Kein
  einziges `secret_api_baseline` in irgendeinem Log. Solange der Key fehlt, ist `SecretApi` inert und
  weder die Teammate-Zeile noch das Audit erscheinen. Ein Textfeld im `/sa`-Debug-Tab wäre der
  nächste Schritt, wenn der User es will.

**Boss = Koordinaten pro Tick, wie in Odins `DungeonListener`** — dieselben Schwellen, kein Latch,
kein Chat. `[BOSS] ` ist als Signal verbrannt: The Watcher steht im Blood-Room und trägt denselben
Prefix. Zweites Standbein bleibt „Map 2 s weg" (nicht in Odin). Das Log sagt mit `boss_phase`,
welches von beiden gefeuert hat — **wenn dort `by: "map"` steht, haben die Koordinaten wieder
nicht gegriffen**, und dann ist die Sidebar-Zeile `Cleared: X%` der nächste Kandidat.
- [x] **HUD-Editor** — die Karte steht an ihrer echten Position und wird gezogen (Grab-Offset,
  Clamp gegen die echte Kartengröße), Hintergrund ist ein 48/255-Scrim statt `surfaceBase`, das
  Live-Overlay steht währenddessen still (`HudRoot.editing`, in `removed()` gelöscht).
- [x] **Anchor+Offset statt `hudX`/`hudY`** (`HudPlacement`) — neun Anker, Versatz von der Kante nach
  innen, der Editor zieht weiter frei und leitet den Anker beim Loslassen ab. Dazu `version` in
  `config.json` und ein geordneter, idempotenter Migrationspfad (`ConfigMigration`, rein, ohne
  `FabricLoader`). **Ein v0-File wird erst umgerechnet, wenn eine echte `guiScaled`-Größe vorliegt** —
  aus zwei absoluten Pixeln allein ist der Anker nicht ableitbar; bis dahin bleibt die Datei v0 und die
  Karte steht auf demselben Pixel wie vorher.
- [x] **Phase 3b Rest** — StormHud/ClearPopup auf dem Design-System. Beide sind jetzt Chips aus
  `Surface`/`Tokens` statt roher Literale; das Gold für einen PB ist weg und der PB reist auf drei
  Kanälen ohne Luminanz (Chevron, das Wort `PB`, stärkerer Rahmen). `StormTimer.readout` gibt eine
  `Urgency`-Stufe statt ARGB zurück — vier Zustände sind gefüllte Marken plus Rahmengewicht, und
  `SHOOT NOW` invertiert den Chip. Chrome zeichnet in GUI-Space, nur der Text betritt `scale(2)`:
  `DevicePixels.push` verweigert jede Pose, die keine reine Translation ist.
- [x] **Phase 2** — Komponenten. `ui/components/`: `Labels` (die drei kopierten Tracking-Loops sind
  jetzt einer), `Button` (3 Varianten), `TextField` + `Edit`, `Stepper`/`Slider`, `Nav`/`Segmented`,
  `Popover`/`Tooltip`, `Badge`, `EmptyState`, `Table`, `ProgressBar`. Gallery-Seiten 5–8 (`controls`,
  `input`, `nav`, `data`) zeigen jede in jedem Zustand. **`SettingsScreen` ist absichtlich noch nicht
  umgestellt** — das ist Phase 4/5, und die Screens werden dort ohnehin neu gebaut.
  Das Textfeld existiert für `Config.hypixelKey`, der bis heute keine UI hat: maskiert per Default,
  Reveal ist eine Handlung und kein gespeicherter Schalter — der Einwand in `Config` gilt einem Feld,
  das den Key *anzeigt*, nicht einem Feld.
  Nebenbefund: `SettingsScreen` reicht lange Raumnamen an `setTooltipForNextFrame` weiter, und
  Vanillas Box ist mit ihrem lila Rand das einzige Farbige auf dem ganzen Bildschirm. `Tooltip` ersetzt
  sie, sobald Phase 4 den Screen anfasst.
- [ ] 4 Stats · 5 Config-**Bildschirm** (Unterbau steht) · 7 Politur

Zwei Sachen aus Phase 1, die nicht in der Vorlage standen:

- **`text.tertiary` `#6C7078` verfehlt die eigene 4.5:1-Vorgabe** — 3.57:1 auf `surface.overlay`,
  2.88:1 auf einer gedrückten Overlay-Zeile. Die Vorlage widerspricht sich selbst; die Grenze hat
  gewonnen, `#91959D` (dunkel) / `#5F636B` (hell). Kosten: der Abstand zu `secondary` schrumpft von
  2.0:1 auf 1.27:1. `UiThemeTest` misst das statt es zu glauben.
- **`Density` leitet den Maßstab aus `framebuffer / guiScaled` ab, pro Achse** — nicht aus
  `getGuiScale()`. Bei 1366×768 / Scale 4 ist x = 3.9942 und y = 4.0; mit der nominellen 4 driftet
  eine 420px-Fläche um 0.6 Gerätepixel, und ein Rand verschwindet.

Drei Sachen aus Phase 3a, die die Vorlage anders wollte:

- **„Allokationsfrei pro Frame" geht nicht.** `GuiGraphicsExtractor.innerFill` legt pro Aufruf eine
  `ColoredRectangleRenderState` **und** eine defensive `Matrix3x2f`-Kopie an; `fillGradient` boxt
  zusätzlich die zweite Farbe. Zwei bis drei Objekte pro Draw, in Vanilla, ohne Ausweg. Das erreichbare
  Ziel ist **null Allokation auf unserer Seite, Draw-Calls als Budget** — daher `Format.Cached`.
- **Kein Blur hinter dem HUD.** `blurBeforeThisStratum()` blurrt alles bereits Eingereichte,
  bildschirmfüllend, ohne Form — bei `attachElementAfter(OVERLAY_MESSAGE)` also Welt *plus* Hotbar,
  Leben, Hunger und XP-Leiste, während der Chat scharf bleibt. Der Scrim ist nicht der Default,
  sondern die einzige Option.
- **Ein 1-Gerätepixel-Rand um eine *Rundung* geht aus einem festaufgelösten Sprite nicht.** Gerade
  Hairlines sind exakt; die Bögen sind 1 *GUI*-Pixel. `Surface.roundedBorder` besitzt die
  Entscheidung, ein Upgrade auf pro-Scale gebackene Ringe fasst keine Aufrufstelle an.

`fabric-key-binding-api-v1` heißt in 26.1.2 **`fabric-key-mapping-api-v1`**, der Helper
`KeyMappingHelper.registerKeyMapping`, und die Kategorie ist ein `KeyMapping.Category`-Record um eine
`Identifier` — Sprachschlüssel `key.category.<namespace>.<path>`.

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
