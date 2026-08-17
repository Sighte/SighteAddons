# TODO — SighteAddons

## Stand — 2026-08-17

`main` = 388 Tests / 33 Klassen / grün. `mod_version` 0.15.0, released, Modrinth-Version `YNlbBvlI`
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
  läuft darunter weiter. **Der User testet `0.16.0-dev18`** (nur `build/libs`, `assemble check`
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
  **Lief bis Phase 5b nie:** `Config.hypixelKey` war leer und hatte keine UI. Seit dem Debug-Tab-Feld
  ist der Key ohne Handeditieren setzbar — **damit ist `SecretApi`/`SecretAudit` das erste Mal
  überhaupt lauffähig.** Ob es wirklich läuft, sagt ein `secret_api_baseline` im Debug-Log; bis dahin
  hat es das noch nie gegeben.

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
- [x] **Alle drei Overlays sind platzierbar** (`OverlayPlacement`) — ein Editor für Karte, Clear-Popup
  und Storm-Countdown statt einem für die Karte. Jedes Element hat eigene drei Keys
  (`<key>Anchor`/`OffsetX`/`OffsetY`), einen eigenen Default und wird **durch seine echte
  Zeichenfunktion** gezogen; das Trefferrechteck kommt aus derselben Breitenfunktion, die das Chip
  zeichnet, sonst rastet das Element ein paar Pixel neben dem Loslassen ein. Beide Chips starten exakt
  auf dem alten Pixel — `OverlayPlacementTest` hält die alte `screenHeight / 2 ± n`-Formel gegen die
  neue Ankerrechnung, über sechs GUI-Größen und drei Chipbreiten, dazu Datei-Roundtrip und
  Drop-Roundtrip. Neu im Editor: Pfeiltasten schieben um 1 px, mit Shift um 8, `r` setzt auf den
  Default zurück — Offset `0` ("genau mittig") ist mit der Maus nicht zu treffen, und *das* ist der
  Unterschied zwischen verschiebbar und einstellbar. Kein Versionssprung in `config.json`: sechs
  **neue** Keys sind genau der Fall, den der Explicit-Fallback pro Key schon abdeckt.
  **`StormHud`s KDoc hat Positions-Settings ausdrücklich abgelehnt** — das Argument galt absoluten
  Pixeln, nicht Anker+Offset, und steht jetzt umgeschrieben in der Datei statt gelöscht.
  **Der User testet `0.16.0-dev19`** (nur `build/libs`, `assemble check` mit `-Pmod_version=…`,
  `dist/` bleibt das released 0.15.0).
- [x] **Phase 3b Rest** — StormHud/ClearPopup auf dem Design-System. Beide sind jetzt Chips aus
  `Surface`/`Tokens` statt roher Literale; das Gold für einen PB ist weg und der PB reist auf drei
  Kanälen ohne Luminanz (Chevron, das Wort `PB`, stärkerer Rahmen). `StormTimer.readout` gibt eine
  `Urgency`-Stufe statt ARGB zurück — vier Zustände sind gefüllte Marken plus Rahmengewicht, und
  `SHOOT NOW` invertiert den Chip. Chrome zeichnet in GUI-Space, nur der Text betritt `scale(2)`:
  `DevicePixels.push` verweigert jede Pose, die keine reine Translation ist.
- [x] **Phase 2** — Komponenten. `ui/components/`: `Labels` (die drei kopierten Tracking-Loops sind
  jetzt einer), `Button` (3 Varianten), `TextField` + `Edit`, `Stepper`/`Slider`, `Nav`/`Segmented`,
  `Popover`/`Tooltip`, `Badge`, `EmptyState`, `Table`, `ProgressBar`. Gallery-Seiten 5–8 (`controls`,
  `input`, `nav`, `data`) zeigen jede in jedem Zustand.
- [x] **Phase 4 — Stats.** `ui/screens/StatsOverview.kt` (rein, testbar) + eigener Rail-Eintrag
  `stats`: Coverage gegen `RoomDatabase.roomCount` (neu — `size` sind Cores, nicht Räume), die drei
  Kinds getrennt gezählt und getimt, PBs total/letzte Woche, meistgespielter Raum, Floors. **Unter
  fünf Versuchen gibt es keinen Median** (`sorted[n/2]` ist der obere Mittelwert, bei n=2 also der
  langsamere von zweien) — stattdessen `fastest of 3`; das Akkordeon hält denselben Boden. Die
  PB-Zahl ist ausdrücklich eine Untergrenze: eine Zeile von vor dem `pb`-Feld faltet als `false`.
  Tabelle auf der Komponentenschicht: `Table`, `Tooltip` (Vanillas lila Box ist weg), `EmptyState`,
  `Badge`, `Nav`.
- [x] **Phase 5b — Config-Bildschirm.** Abschnitte + Erklärzeilen, `Stepper` für die zwei
  Storm-Ticks, **`TextField` für `Config.hypixelKey`** (maskiert, Reveal ist eine Handlung und wird
  nie persistiert, Copy/Cut verweigert, Commit erst beim Verlassen) — der Einwand im `Config`-KDoc
  galt einem Feld, das den Key *anzeigt*. `settingRowHeight` ist weg, alle Seiten scrollen über
  `Scroll`. Shift dreht den Stepper nicht mehr um — dafür gibt es den Minus-Arm; `StormTimer.step`
  behält `back` und den Wrap.
- [x] **Der Bildschirm war an genau einer Fenstergröße gemessen** — die Wurzel von vier Befunden des
  zweiten Prüfdurchgangs. `content == guiScaledWidth - 152`, die 2-px-Reserve war bei 478 aufgebraucht,
  und darunter zeichnete der `SECRETS`-Caret **in** das `CLEAR`-Label; weil die Trefferzonen um
  `SPACE_4` gepolstert waren und `firstOrNull` gewann, überlappten sie um 16 px: **ein Klick auf
  `secrets` sortierte nach `clear`** — bei 1280×720 und 1366×768, also bei Minecrafts eigenem
  Auto-Scale. `ui/screens/RecordColumns.kt` setzt die Spalten jetzt von rechts nach links aus
  *gemessenen* Breiten, die Zonen **partitionieren** die Kopfzeile, und Spalten fallen in fester
  Reihenfolge weg (`type` → `runs` → `secrets`); `room`/`clear`/`last` nie. `RecordColumnsTest` geht
  480×270, 456×256, 427×240 und das Vanilla-Minimum 320×240 ab, dazu eine anderthalbfach breite
  Schrift — die Zusicherung gilt dem Algorithmus, nicht den Schriftmaßen.
  **Eine Layoutrechnung im Kommentar hat genau diesen Fehler nicht verhindert: sie war richtig, aber
  nur für eine Größe.**
- [x] **Phase 7 (Teil 1)** — kein Farbliteral mehr außerhalb von `ui/theme/`. Die drei Konstanten in
  `SighteAddons.kt` waren seit Phase 3a Waisen (keine war eine Warnung); `MID` ist jetzt
  `Tokens.PREVIEW_STAGE`, `FAIL` ist weg — ein Kontrast-Fehlschlag sagt das **Wort** `FAIL`.
  `UiThemeTest` liest den Quellbaum und fällt bei jedem 8-stelligen ARGB-Literal außerhalb
  `ui/theme/` (sechsstellige Masken bleiben erlaubt, Kommentarzeilen zählen nicht).
  **`SecretHud` gelöscht** — seit Phase 3a von nichts aufgerufen. Die CLAUDE.md-Zusicherung wurde
  vorher geprüft und hält: kein Fallback von `ownSecrets` auf `secretsFound`. Sie ist jetzt
  `HudSnapshot.roomOwnSecrets`/`runOwnSecrets` und wird von `UiHudSecretsTest` bewacht.
  **Gallery-Seite 9 `overlay`** — `ClearPopup` und `StormHud` mit 13-s-Skript durch die echten
  Dateien: alle vier Dringlichkeitsstufen inkl. Inversion, ein Popup überlappt den Countdown, dann
  ein PB-Popup, dazu Timeline mit Playhead.
- [x] **Review-Durchgang nach dem Merge** — acht Befunde, alle behoben. `ConfigMigration` liest jeden
  Wert defensiv (`intOr`/`stringOr`/`boolOr`), `Config` liest `installId` als Allererstes: ein
  handverkorkstes `config.json` kostete bisher die **Upload-Identität** und verwaiste die ganze
  Historie auf dem Receiver. `Config.showRoom` wird jetzt gelesen (vorher: Schalter ohne Wirkung).
  `Palette.scrim` ist ein eigenes Token — `shadow` war im hellen Ramp die Rückwand für Text und maß
  **1.32:1**; jetzt hält der schlechteste Wert über beiden Ramps und über hellem wie dunklem
  Weltuntergrund **4.57:1** bei 88 %, 87 % fällt durch. `Config.hudScrim` (Prozent, 88–100, Default
  90) trägt die Deckkraft. `ClearPopup` klemmt Breite und linke Kante (Scale 4 auf 1366×768 schnitt
  Raumname und PB-Badge ab). HUD wieder allokationsfrei auf unserer Seite. Raumuhr auf `ScaledText`.
  Eine Schreibweise für „gegen den Record": `−0:02.8` auf HUD *und* im Chat.
- [x] **Zweiter Review-Durchgang, nur über die Bildschirme** — zehn Befunde, alle behoben. Neben der
  Spaltenwurzel oben: ein Klick in den toten Streifen unter der letzten Tabellenzeile klappte einen
  Raum auf, den man nicht sieht; die Scrim-Notiz sagte das Gegenteil der Zahl (90 % ist Deckkraft, es
  zeigen sich 10 %); der Leer-Zustand lief bei `guiScaledHeight 240` durch die Fußzeile — der erste
  Blick einer frischen Installation; `show` beim Key fokussierte das Feld nicht, wodurch die
  dokumentierte Endbedingung des Reveals nie griff (**kein Leck** — alle Ausstiegspfade wurden
  geprüft); ein Scrim-Wert ging verloren, wenn der Bildschirm bei gehaltenem Regler zuging.
  Geprüft und für gut befunden: Metriken getrennt, Median-Boden an jeder Stelle, der Key taucht
  außerhalb von `Config`/`SecretApi` nirgends auf, „Nicht anfassen" unberührt.

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
  Ziel ist **null Allokation auf unserer Seite, Draw-Calls als Budget** — daher `Format.Cached`,
  `Format.Cached2` und `Labels`' Tabelle aus Ein-Zeichen-Strings. Offen bleibt der `Origin`, den
  `Config.hudOrigin` pro Frame anlegt.
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
