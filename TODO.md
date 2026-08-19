# TODO — SighteAddons

## Stand — 2026-08-18

`main` = **394 Tests / 35 Klassen / grün**, `mod_version` 0.16.0 released (`v0.16.0`, Modrinth
`kIbj76Z4`, SHA1 `931b1152…` auf beiden Seiten). `dist/sighteaddons-0.16.0.jar` ist die committete
Datei. `RunReport.SCHEMA` 6, der Receiver akzeptiert `idleTicks`/`navTicks` als optional und ist
deployt — **dem Receiver ist nichts geschuldet.**

Seit 18.08. dazu, alles ohne Versionsänderung: `DungeonScore` (die Formel der Upstream-Mod als reine
Funktionen, **ruft noch niemand auf**), `SecretApi.lastCounts` — ein gescheiterter Abschluss-Snapshot
löscht die Teammate-Zahlen nicht mehr — und `docs/features/`, acht annotierte Screenshots.

**Seit 19.08.: `SoloClear`** — jeder solo abgeschlossene Run geht als `POST /v1/solo_clear` an die Box,
die ihn nach Discord weitergibt und nichts speichert. Lokal landet er in `soloclears.jsonl`
(append-only, wie `history.jsonl`); die Bestzeit pro Floor ist das Minimum darüber und `pb` im Body ist
**unsere** Behauptung — die Box hat keine Historie, gegen die sie eine prüfen könnte. Ohne `pb: true`
heißt die Zeile dort `**SOLO CLEAR**`. Die Receiver-Hälfte liegt auf `Sighte/SighteAddons-serverside`
`soloclear-002` und ist **nicht deployt**: bis dahin behauptet jede Nachricht eine PB und Prince/Mimic
stehen als `false` statt `?` da. Schalter ist `Config.soloClears`, **aus by default**, `/sa` → debug.
Zwei Flags statt einem für die Solo-Erkennung: gelatcht wird *Gesellschaft*, nicht *solo* — die
Tab-Liste füllt sich beim Laden, eine Fünfergruppe liest sich in den ersten Ticks als eine Person.

**Der Score fuer das Gate kommt von Hypixel, nicht von `DungeonScore`** (19.08.). `Team Score: 305 (S+)`
steht ein paar Zeilen unter der Run-Ende-Headline, ebenso `Clear Time:`; `A Prince falls.` kommt
mitten im Run. `DungeonScore` bleibt damit ungenutzt — es ist eine Live-Schaetzung fuer einen
Bildschirm, und auf eine Schaetzung wird kein Kanal gegatet. Folge fuer die Mechanik: die Headline
**armiert** nur, die Score-Zeile **loest aus** (`SoloClear.onChatLine`), weil ein Gate auf einer Zahl,
die noch nicht da ist, nicht entscheidbar ist. `Config.soloClearMinScore` = 300 (S+), 270 (S) oder 0
(alles), Zeile in `/sa` → debug. **Ueber 0 faellt ein unbekannter Score durchs Gate** — eine
Schwelle, die nicht ausgewertet werden kann, ist nicht erreicht; andersherum macht ein falsches
Regex einen Kanal, der alles ankuendigt. War die Score-Zeile nie da, steht ein
`solo_clear_unreleased` im Log.

**Das Gate soll im Clear feuern, nicht am Run-Ende** (User, 19.08.): sobald 300 erreicht ist, wird die
Zeit gepostet, der Boss ist irrelevant. Damit ist Hypixels `Team Score:` wertlos — es kommt nach dem
Boss. Es braucht einen **Live**-Score, und dafuer gibt es zwei Wege: den Tab-Footer lesen
(`Score: 287`, wie die Upstream-Mod es zuerst versucht) oder `DungeonScore` rechnen. **Gerechnet wird
nicht blind**, weil die Vorlage zwei bekannte Offsets hat: `isQuizCompleted()` gibt dort `+5`, sobald
das Wort "Quiz" in der Tab-Liste *vorkommt* (Puzzle existiert, nicht geloest), und Mayor Pauls `+10`
fehlt ganz. Beide verschieben den 300-Moment, in entgegengesetzte Richtungen.

**`ScoreProbe` ist die Messung dazu** (dev3): dreimal pro Run (Tick 1200/3600/7200) ein
`score_probe`-Event mit dem Footer-Score, den Stat-Zeilen der Tab-Liste und der Sidebar-Zeile
`Cleared: X%`. Puzzle-Zeilen werden **nach der Klammer abgeschnitten** — dahinter steht der Name des
Loesers. Sagt `published` eine Zahl, ist der Rechner ueberfluessig; sagt es `-1`, muss gerechnet werden
und die zwei Offsets sind die naechste Arbeit. **Die Datei danach loeschen.**

Fuer den Live-Score fehlen dann noch die Parser: `Completed Rooms: N/M`, `Crypts: N`, `Puzzles: (N)`
plus die `[✔]/[✖]`-Zeilen, `Mimic: ✔`, `Prince: ✔` — und die Raumzahl des Floors ist *abgeleitet*
(`completed / (Cleared% / 100)`), nicht gelesen.

**Der Live-Score ist gebaut und auf einem echten Floor gemessen** (`LiveScore`, 12 M7-Runs am 19.08.,
Session `session-1787161530005.jsonl`). Die Antwort ist eindeutig:

- **Hypixel publiziert den Score in der Sidebar.** `Cleared: 92% (191)` — die Klammer ist der Score.
  Zwei Runs beide bei 92 % trugen 191 und 231, das ist keine Funktion der Prozentzahl. Quelle Nr. 1
  liefert, `score_source` sagt `sidebar`.
- **Der Tab-Footer traegt keinen Score** (`published: -1` bei 15 Footer-Zeilen). Upstreams primaere
  Quelle existiert auf dieser Version nicht.
- **`DungeonScore` wird damit nie aufgerufen** und bleibt der Fallback, der auf dieser Version nicht
  gebraucht wird. Mayor Pauls `+10` und die gerundete Raumzahl sind gegenstandslos, solange die
  Sidebar antwortet — beide Kommentare bleiben trotzdem stehen, weil der Pfad noch existiert.
- **`Cleared: X%` und `Completed Rooms:` passen zusammen**: 33 Raeume bei 92 % → 36 total, was ein M7
  auch hat. Die Ableitung war richtig, sie wird nur nicht gebraucht.
- Die Puzzle-Zeilen sehen aus wie angenommen: `Boulder: [✔]`, `Quiz: [✔]`, `???: [✦]` fuer ein noch
  unentdecktes. Upstreams `+5`-auf-das-Wort-"Quiz" ist damit bestaetigt kaputt.

**Zwei Bugs hat derselbe Floor gefunden, beide gefixt:**

1. **`Time Elapsed: 59s`** — unter einer Minute schreibt Hypixel *keinen* Minutenteil. Das Regex verlangte
   ihn, also war genau die erste Minute jedes Runs unlesbar, und das ist das Fenster, in dem ein schneller
   Clear-Score erreicht wird. Jetzt `(\d+h )?(\d+m )?\d+s` plus Doppelpunktform, geteilt zwischen
   Sidebar und Tab-Liste (`DungeonSession.TIME_VALUE`).
2. **Die Tab-Liste hat zwei `Time:`-Zeilen, eine davon `Time: N/A`.** Kein Handling noetig, aber jetzt
   belegt: `N/A` ist keine Dauer und `readElapsed` behaelt die weiteste Lesung.

**Die Solo-Erkennung hat sich dabei live bewiesen:** alle 9 abgeschlossenen Runs waren Fuenfergruppen,
`run_score` sagte 300–307, und **kein einziges `solo_clear`** ist rausgegangen. Ein Party-M7 mit 304
wird nicht angekuendigt, obwohl der Schalter an war (`soloClears: true`, `soloClearMinScore: 300`).

`ScoreProbe` ist geloescht — es war dafuer gebaut. Der laufende Abgleich haengt jetzt an `run_score`,
das Hypixels Endscore neben der letzten Live-Lesung und deren Quelle loggt.

**Was noch aussteht: ein Solo-M7.** Nur der sagt, ob der Trigger im Clear feuert und welche Zeit im Kanal
landet.

**Zum Testen: `build/libs/sighteaddons-0.17.0-dev5.jar`** (19.08., 427 Tests grün). Gebaut mit
`./gradlew assemble check -Pmod_version=0.17.0-dev5` — `mod_version` in `gradle.properties` steht
weiter auf `0.16.0` und `dist/` hält unverändert das released 0.16.0. Der Dev-Jar ist als
`0.17.0-dev5` gestempelt, damit `X-Mod-Version` und `modVersion` in den Reports ihn nicht mit dem
Release verwechseln.

**Der `SecretApi`-Fix liegt auf `main` und bei keinem Spieler.** Er kostet ein Release, und ein
Release ist die Entscheidung des Users; bis dahin läuft draußen 0.16.0 mit dem alten Verhalten.

**Das UI-Redesign ist durch, alle sieben Phasen** (Tokens/Motion, Komponenten, HUD, Overlays, Stats,
Config, Chat) plus zwei Review-Durchgänge, 18 Befunde. Kein Farbliteral mehr außerhalb `ui/theme/`,
`UiThemeTest` liest den Quellbaum und fällt bei jedem 8-stelligen ARGB darüber hinaus. Details im
`git log`; `runClient` ist damit wieder gesperrt (CLAUDE.md-Regel 2).

## Was das Redesign offen gelassen hat

- **Der Blood-Room hat kein HUD-Split.** Die Live-Uhr dort sind deine Ticks im Raum, der Record ist
  Odins Tür-bis-Pass-Spanne (`BloodClear.kt`, eigener Kind `bloodclear`) — ein Delta zwischen beiden
  wäre eine Lüge. Soll es aufs HUD, muss die laufende Blood-Uhr in den Snapshot.
- **`SecretApi`/`SecretAudit` ist zum ersten Mal überhaupt lauffähig** (seit `Config.hypixelKey` ein
  UI-Feld hat). Ob es läuft, sagt ein `secret_api_baseline` im Debug-Log — bis dahin hat es das nie
  gegeben.
- **`boss_phase` mit `by: "map"` heißt: die Koordinaten haben nicht gegriffen** (Odins Schwellen sind
  das erste Standbein, „Map 2 s weg" das zweite). Dann ist die Sidebar-Zeile `Cleared: X%` der nächste
  Kandidat. `[BOSS] ` ist als Signal verbrannt — The Watcher steht im Blood-Room mit demselben Prefix.
- Allokationsfrei pro Frame ist auf unserer Seite erreicht; offen bleibt der `Origin`, den
  `Config.hudOrigin` pro Frame anlegt.
- Das Modrinth-Projekt antwortet Nicht-Eingeloggten mit 404 (noch in Review). Veröffentlichen ist
  dein Schritt.

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
- **`scores-002` (Mod-Hälfte)** — `weightOf` rechnet das alte geseedete Modell aus kompilierten
  Konstanten, `RoomScores.parse` liest `scores[]` nie. Die Box publiziert seit dem Score-Rewrite
  andere Zahlen als die Mod zahlt. Reihenfolge: serviertes `score`, dann letzte Kopie, dann die
  kompilierten Werte — **nie den Spielstart blockieren.**
- **`chatfields-001`** — blockiert, erster Schritt liegt im Receiver. Nicht mit `RunReport.kt` anfangen.
- **`records-001`** — vom User zurückgestellt (Produktentscheidung). Ein Raum behält einen Record
  über alle Floors; der Receiver faltet absichtlich genauso.
- **`party-001`** — der Mechanismus existiert nicht: kein Dekorations-Key überlebt die Leitung in
  26.1.2, Party-Sync verbietet das eigene Design. Wartet auf `deconame-001`.

## Unverifiziert

**Keine Wiring-Zeile von `ownsecrets-001`, `secretpoints-001`, `idletime-001` und `recordowner-001`
ist je im Spiel gelaufen** — die reine Logik deckt die Suite ab, die Verdrahtung nicht. Ein Floor
klärt alles davon mit dem Auge.

`DungeonTab.ELAPSED` **greift** (11 `tab_time`-Events am 19.08.), und daran hängt die angekündigte
Zeit: Hypixels eigene Uhr (`Time: 06m 32s`) ist die, die zwei Spieler vergleichen können, `runTicks`
beginnt erst bei der Kalibrierung und ist systematisch zu kurz. Greift die Zeile nicht, fällt
`SoloClear` auf unsere Uhr zurück — leise, aber ein `tab_time` im Session-Log ist der Beweis, dass sie
greift, und ein `solo_clear` mit `hypixelTime: "-"` der Beweis, dass sie es nicht tut. Beide Records
werden getrennt gehalten, damit keine Sekunde je gegen einen Tick antritt.

Dazu: `SECRET_ITEMS` sind zehn geratene Namen (exakter Match, nie Präfix); der Mixin nutzt
`require = 0`, ein falscher Injector schweigt also — Erkennung ist das *Fehlen* von `own_pickup`;
eine aus der Distanz getötete Fledermaus bleibt unbelohnt (`AttackEntityCallback` ist ein
Nahkampfschlag), und das ist die Richtung, in die dieser Fehler gehen darf. Die Tick-Zahlen von
Storm/Crit (138, 20, `TIME_WORTH = 2.5`) sind unsichtbar falsch, deshalb stehen sie als `/sa`-Zeilen.
Altlasten: die falschen Bests in `history.jsonl` sind nicht reparabel (akzeptiert), `runTicks` ist
auf dem DISCONNECT-Pfad nicht `@Volatile`, `RoomStats.start()` lief nie im Spiel.
