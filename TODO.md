# TODO — SighteAddons

## Stand — 2026-08-20

`main` = **458 Tests / 42 Klassen / grün**, `mod_version` 0.16.0 released (`v0.16.0`, Modrinth
`kIbj76Z4`, SHA1 `931b1152…` auf beiden Seiten). `dist/sighteaddons-0.16.0.jar` ist die committete
Datei. `RunReport.SCHEMA` 6, der Receiver akzeptiert `idleTicks`/`navTicks` als optional und ist
deployt — **dem Receiver ist nichts geschuldet.**

**Seit 20.08.: `/sa import` holt die Split-PBs aus Odin.** Den Import gab es schon, aber nur als
Zeile auf der Debug-Seite — und eine Aktion, die man am Tag der Installation einmal macht, findet
niemand, der nicht schon weiss, dass die Zeile existiert. Beide Wege rufen `SplitImport.run()`, also
gibt es *eine* Formulierung und nicht zwei, die auseinanderlaufen.

`SplitImport` ist ein eigener kleiner Object und keine Methode an einem der Nachbarn, aus zwei
Gruenden, die beide bleiben: **`SplitPbs` speichert absichtlich nicht** (steht in seinem eigenen KDoc
zu `record`, weil eine Chat-Zeile drei Records produziert und der Caller weiss, wann die Datei zu
schreiben ist), und **`SplitPbs` traegt keine Minecraft-Typen**, was `SplitPbsTest` erst erlaubt,
`merge` mit den echten Bytes einer echten Odin-Config zu fahren — `Chat` gibt eine `Component`
zurueck. Auf `SettingsScreen` konnte es auch nicht bleiben: ein Befehl, der durch einen Screen laeuft,
um eine Datei zu lesen, ist ein Befehl, der einen Screen braucht.

**Gegen die echte Datei geprueft** (Odin 0.3.1, `odin-config.json` vom 20.08.), durch den echten
Produktionscode und nicht durch eine Nachbildung: 83 Records auf 13 Floors (F2/F3 haben in Odin keine),
zweiter Lauf aendert **0** — der Merge ist ein Minimum, also idempotent, also gefahrlos zweimal. Die
`/sa splits`-Tabelle rendert daraus 96 Zeilen (83 Records + 13 Ueberschriften), M7 zuerst, in
Kettenreihenfolge. Der Wegwerf-Test dafuer ist geloescht; `SplitPbsTest` deckt den Pfad mit einem
realistischen Fixture ab, und `SplitImport.run()` braucht Chat und ist von hier nicht testbar.

**Offen und als naechstes zu messen: `clear_record`.** Ein Puzzle, das man verlaesst, bevor der
Checkmark kommt, verliert seinen Record — `presentFromStart` verlangt, dass man beim Checkmark
hoechstens `MIN_TICKS + 1` = 21 Ticks (1,05 s) draussen war, und der Checkmark ist das einzige
Clear-Signal. Die Zeit selbst ist nie falsch (`room.ticks[self]` zaehlt nur Anwesenheit), die
ClearPoints auch nicht (`award` fragt die Anwesenheit beim Checkmark nicht), und Hypixels Score erst
recht nicht (`LiveScore` liest die Sidebar). Weg ist der **ganze Attempt** in `history.jsonl`, das
Popup, und mit `own PBs only` auch die Chat-Zeile.

**Bewusst noch nicht gefixt, sondern instrumentiert.** `RoomHistory.logDecision` schreibt eine Zeile
pro Non-Blood-Clear mit `mine`, `named`, `self`/`top`, `ownTicks`, `clearTick`, `enterTick`,
`stayStart` und **`sinceSeen`** — wie lange man beim Checkmark schon draussen war. **Kein
`reason`-Feld, und das ist die Entscheidung:** ein Verdikt neben `ownClear` waere eine zweite Kopie
des Praedikats, und `CLAUDE.md` nennt genau diese fuenf Zeilen als nicht anzufassen, weil
`build/recordprobe.py` sie einzeln loescht. Alle 21 Anker sind geprueft und intakt; `ownClear` und
`presentFromStart` sind unveraendert. Die Rekonstruktion steht in `build/clearrefused.py` und
**prueft sich gegen das `mine` der Mod** — eine `DISAGREEMENT`-Zeile heisst, das Skript ist
abgedriftet, nicht die Mod.

Eine Zeile pro Clear und nicht pro Ablehnung, damit die Identitaet gilt: *Zeilen = Non-Blood-Clears*,
und *`mine` und `named` = Attempts in `history.jsonl`*. Naechster Schritt: ein Floor mit
`0.17.0-dev18`, dann `python build/clearrefused.py`. Die Zahl, die die Entscheidung traegt, ist
`worst` pro Raum — eine Toleranz muss den schlimmsten Fall decken, nicht den Median. Danach A
(Toleranz fuer Puzzles) oder B (Record auf die letzte Anwesenheit stempeln, aendert aber, was `clear`
bedeutet — und "keine Metrik wird umdefiniert" steht in `CLAUDE.md`).

**Seit 20.08.: die Settings-Seiten tragen ihre Erklaerungen nicht mehr auf der Seite.** 22 der 28
grauen Saetze sind `Item.notes` und erscheinen als Tooltip, solange der Cursor auf der Zeile liegt —
die Seiten waren doppelt so lang wie die Einstellungen darauf, und der Schalter, fuer den jemand kam,
stand unter der Falz. Der Footer ist die einzige Stelle, die das sagt (`click to change · hover for
the detail`); ein Marker auf jeder Zeile mit Erklaerung waere genau das Rauschen wieder, das
Weglassen beseitigt hat.

**Sechs bleiben Zeilen, und die Grenze ist nicht Geschmack.** `note()` erklaert, *was* eine Zeile ist,
und ist derselbe Satz fuer immer — das kann hinter einem Cursor warten. `state()` sagt, was die Zeile
*gerade tut*: `<name> rides on every report`, `unbound and closed: nothing below can show`, was ein
Scrim unter der gemessenen Kontrastschwelle kostet. Die drei Consent-Zeilen (`uploadName`,
`soloClears`, `runPbs`) muessen **vor** dem Klick lesbar sein — das steht seit ihrer Einfuehrung so im
Code —, und eine Warnung, die man erst mit dem Cursor findet, findet man zu spaet.

Nebenbei zwei Fehlzuordnungen repariert, die als Zeile nicht auffielen: `138 and 20 are inherited and
unverified` sass ueber den zwei Steppern und haengt jetzt an jedem, und `kept in soloclears.jsonl`
sass unter der Score-Zeile statt am Schalter. `note()` sucht die letzte Zeile, die *keine* `NOTE` ist,
damit die Reihenfolge am Call-Site frei bleibt. Und der Footer wird jetzt auf die Content-Spalte
gekuerzt — er war der eine String ohne Scissor und ohne `fit`, also lief ein langer Satz bei 320x240
quer ueber den restlichen Bildschirm.

**Seit 20.08.: jeder Record ist in `/sa` sichtbar, und die Seite hat dafür eine Ebene bekommen.** Die
zwei PB-Stores waren vorher nur zwei Zahlen in `debug` → `data` — `split records: 27` und
`run records: 4`, und kein Weg zu dem, was dahinter steht. Jetzt gibt es drei Tabellen hinter *einem*
Rail-Eintrag (`records`), umgeschaltet über die `Segmented`-Komponente: `rooms` (die alte History),
`splits` (`SplitPbs`, inklusive aller Bossphasen — maxor, storm, terminals, goldor, necron) und `runs`
(`RunPbs`, pro Floor **und** Teamgröße, beide Uhren). `/sa splits` und `/sa runs` gehen direkt hin,
`/sa pbs` bleibt die Zimmer-Tabelle.

**Drei Rail-Einträge statt eines wären nicht gegangen, und das ist Arithmetik.** Das Rail hat keinen
Scroll und `Nav.ROW` pro Eintrag; bei der Vanilla-Mindestgröße 320×240 bleiben 140 px, also fünf
Einträge und nicht sieben. Deshalb bleibt das Rail bei fünf und die Wahl steckt in einem
Segmented-Control über der Tabelle. **Der Preis ist eine Zeile der History-Tabelle**: das Control
kostet `SPACE_24`, die die `rooms`-Ansicht vorher nicht ausgab — sechs sichtbare Zeilen werden fünf bei
1080p, fünf werden drei bei 320×240. `splits` und `runs` haben keine Chip-Reihe, zahlen also nichts.
Beide Zahlen stehen jetzt in `SettingsPageTest`, damit der Preis eine fehlschlagende Assertion ist und
kein Screenshot. Die Bänder selbst (`chooserTop`, `chipsTop`, `columnsTop`, `rowsTop`, `rows`) sind aus
dem Screen nach `Frame` gewandert, aus genau dem Grund, aus dem `RecordColumns` existiert.

**Zwei Ordnungen, die falsch unsichtbar sind, und deshalb `PbTable` + `PbTableTest`.** Floors lesen
Master zuerst und den höchsten zuerst (`M7 … M1, F7 … F1, E`) — nicht die Reihenfolge, in der
`SplitPbs` einfügt, denn stabile Insertion-Order ist die richtige Eigenschaft für eine *Datei* und
keine für eine Liste, die jemand liest. Innerhalb eines Floors gilt **die Reihenfolge des Runs**
(`DungeonSplits.chainFor`) und keine Spalte: `blood clear` kommt alphabetisch vor `blood open`, und
eine nach Namen sortierte Kette beschreibt keinen Run mehr. Namen, die die Kette nicht kennt — ein
Import aus einem Odin, das diese Mod nie gesehen hat — kommen dahinter, alphabetisch, statt zu
verschwinden.

**Die zwei Uhren treffen sich auch auf der Tabelle nicht.** Die Überschrift eines Floors auf `runs`
trägt dessen beste **gerankte** Zeit oder gar nichts; eine Own-Clock-Zeile steht in ihrem eigenen
Label (`5 players · own clock`) und nie in einer eigenen Spalte, weil eine Spalte das ist, was bei
schmalem Fenster wegfällt — und die Zeile, die dann übrig bliebe, sähe aus wie derselbe Record zweimal.
Ein Floor, auf dem nur eine Own-Clock-Zeit liegt, hat Zeilen und keine Headline. Das ist `RunPbs`'
Argument als Tabelle.

Dazu: `Format.seconds` (die dritte Eingabeeinheit, weiter *ein* Dialekt — `Math.round` und nicht
Truncation, sonst liest `19.6f` als `0:19.5`), `SplitPbs.revision`/`RunPbs.revision` als Cache-Key
(ein *geschlagener* Record lässt `count` stehen und ändert die Zahl daneben, und `/sa` pausiert das
Spiel nicht), `RunPbs.Record` + `RunPbs.records()`, `SplitPbs.tagOf`/`records()`. Aus `debug` → `data`
sind die zwei Zählungen weg — sie stehen jetzt im Header der Tabelle, die sie zählt —, der
Odin-Import bleibt dort, weil er eine Aktion ist und keine Zahl.

**Nicht auf der Seite: `SoloClear`s `bestSeconds`/`bestTicks`.** Das ist die Zeit *bis zu einem Score*
pro Floor und Metrik, kein Ganz-Run-Record, und die Solo-Bestzeit steht als `solo`-Zeile schon auf
`runs`. Ein vierter Tab wäre auch nicht gegangen: vier Segmente wollen ~204 px in einer
Content-Spalte, die bei 320×240 168 breit ist.

**Nie im Spiel gesehen.** Die zwei neuen Tabellen sind Arithmetik und Tests; `runClient` ist verboten,
also ist der Dev-Jar (`0.17.0-dev16`) der erste Blick darauf. Was ein Auge klären muss: ob die
Segmented-Reihe bei GUI-Scale 4 lesbar über der Chip-Reihe sitzt, und ob drei Zeilen History bei
320×240 noch benutzbar sind oder ob die Chip-Reihe dort besser wegfällt.

Seit 18.08. dazu, alles ohne Versionsänderung: `DungeonScore` (die Formel der Upstream-Mod als reine
Funktionen, **ruft noch niemand auf**), `SecretApi.lastCounts` — ein gescheiterter Abschluss-Snapshot
löscht die Teammate-Zahlen nicht mehr — und `docs/features/`, acht annotierte Screenshots.

**Seit 20.08.: jedes HUD-Element hat eine Größe, und das Scrollrad ist sie.** Im Platzierungs-Editor
(`/sa` → die `position`-Zeilen) ändert das Rad die Größe des Elements, das gerade unter der Hand liegt —
auch mitten im Ziehen, weil das der Moment ist, in dem man merkt, dass es da nicht hinpasst. Gespeichert
wird `<key>Scale` als **ganze Prozent** (50–300, zehn pro Rasterschritt) neben Anchor und Offsets in
`OverlayPlacement`; ein Float in `config.json` liest sich als `1.2000000476837158` zurück und wiederholtes
Multiplizieren mit 1.1 kommt nie wieder bei 100 an. `r` setzt jetzt Position **und** Größe zurück, Escape
nimmt beide zurück (`OverlayPlacement.Saved`, nicht `HudPlacement.Placement` — vier Werte, nicht drei).

Zwei Dinge, die dabei nicht offensichtlich sind. **Die Größe steckt in `origin()`, nicht in den fünf
Draw-Sites:** ein Offset zählt von einer Kante nach innen, also hängt bei acht der neun Anchor die Ecke
an der Größe, und ein Element, das ungeskaliert gemessen und 150% gezeichnet wird, hängt genau um die
Differenz aus dem Bild. Der Editor greift und clampt deshalb über `placedWidth`/`placedHeight`.
**Und der Preis steht in `ui/render/Zoom.kt`:** `DevicePixels.push` verweigert jede Pose, die keine reine
Translation ist, also fallen Borders in einer skalierten Pose auf die 1-GUI-Pixel-Variante zurück und Text
bei nicht-ganzzahligem Faktor wird weich. **Bei 100% gilt davon nichts** — die Pose ist dann eine reine
Translation und die Pixel sind die, die released sind. Das ist der Grund, warum der Bereich bei 50%
aufhört und nicht bei 25%.

**Seit 20.08.: `RunPbs` — Run-Bestzeiten pro Floor und Teamgröße, und jede neue geht raus.** Nicht
`SplitPbs`, und das ist der ganze Punkt: dessen `total` läuft auf **unserer** Uhr (Mort → `☠ Defeated`,
damit es neben `blood clear` in derselben Spalte stehen und mit Odins Datei vergleichbar sein kann) und
hat **keine Teamgröße im Key**. Eine Run-Zeit ohne Teamgröße ist die Zeit der größten Gruppe und sonst
nichts — die Solo-Bestzeit wäre an dem Tag unsichtbar, an dem sie ein Fünfer schlägt.

Also ein zweiter, kleinerer Store: `runpbs.jsonl`, append-only, nur PBs, Record = Minimum darüber
(`RoomHistory`/`SoloClear`-Form). Key ist `Floor|Spieler|Uhr`, **gerankt wird Hypixels `Clear Time:`** —
die einzige Zahl, die zwei Spieler vergleichen können. Fehlt die Zeile (Run verlassen, Hypixel formuliert
um), fällt es beim Reset auf unsere Mort→Defeated-Spanne zurück, **in eigenem Key-Space und als `own`
markiert**; die zwei Uhren treffen sich nirgends, genau wie SoloClears seconds/ticks.

**Die gerankte Hälfte hängt nicht an `Config.splits`** — sie braucht nur die Chat-Zeile und den
Floornamen von der Sidebar, den `onChatLine` selbst greift, solange `DungeonSession.reset` ihn noch nicht
genullt hat. Nur der own-clock-Fallback braucht die armierte Kette, weil dort die Spanne gemessen wird.

**Das Leaderboard gibt es noch nicht, deshalb gibt es eine Outbox.** Jede fällige PB liegt als
`pbs/pb-<millis>.json`, wird sofort einmal gepostet (`POST /v1/run_pb`) und erst nach 2xx gelöscht. Ein
`404` von einer Route, die niemand geschrieben hat, ist über `TelemetryUpload.outcome` ein `RETRY` — die
Zeilen warten, `RunPbs.flush()` versucht es beim nächsten Start wieder, und das erste Leaderboard, das
antwortet, bekommt alle PBs, die vor ihm gesetzt wurden. Eigener Pass und nicht Teil von
`TelemetryUpload.start()`, weil der bei `upload = false` gar nicht anläuft und die Schalter unabhängig
sind.

**`Config.runPbs`, aus by default** (`/sa` → debug, „send new bests"). Das ist `uploadName`s Argument an
der Stelle, um die es dabei ging: ein Leaderboard braucht einen Namen, und auf einem zu stehen ist keine
Voreinstellung. Der Record wird trotzdem geführt — eine Zahl in einer Datei auf der eigenen Platte, wie
ein Split-Record —, und solange der Schalter aus ist, wird **nichts** in die Outbox geschrieben, damit
ein späteres Einschalten keine alten Runs nachschickt.

**Die Receiver-Hälfte fehlt.** `POST /v1/run_pb` existiert auf der Box nicht; bis dahin sammeln sich die
Zeilen in `pbs/`. Der Payload ist der Vertrag und steht in `RunPbsTest` Feld für Feld: `player`,
`installId`, `floor`, `players`, `time`, `seconds`, `timeSource`, `totalMs`, `totalTicks`, `previous`,
`ts`, `modVersion` — `player`/`time`/`previous`/`totalTicks` fehlen, wenn es sie nicht gibt, statt eine
erfundene Null zu tragen.

**Seit 20.08. auch: die Lag-Zeile.** Das Panel zeigt seit dem Splits-Port zwei Spalten — links
Wall-Clock, rechts dieselbe Spanne in Hypixels Server-Ticks —, und die Differenz ist die Zeit, die der
Run gedauert hat und der Server nicht verbucht hat. `Splits.lostToLag(totalMs, totalTicks)` ist die ganze
Rechnung, `Readout.lagMs`/`lagText` tragen sie (als **Default-Parameter** aus den beiden Totals, damit
kein Readout gebaut werden kann, dessen Lag seinen eigenen Spalten widerspricht), Zeile auf dem Panel
unter `boss entry` und eine Zeile in der Chat-Zusammenfassung unter `total`.

Zwei Dinge daran sind nicht offensichtlich. **Aus den Totals gerechnet, nicht pro Split summiert** — die
Spannen teleskopieren, das Ergebnis ist identisch, und aus den zwei Enden ist es auch für einen Run
richtig, dessen mittlere Zeilen fehlen. **`hasLag` ist der gefährliche Teil:** ein Run ohne jede
Tick-Lesung (kein Keep-Alive-Ping gesehen, also Disconnect) würde sonst die *ganze* Runlänge als
Lag-Verlust melden — die größtmögliche falsche Zahl, auf genau der Zeile, die erklären soll, warum ein Run
lang wirkte. Deshalb ist die Zeile dann abwesend statt null, und der Testfall dazu ist der, den kein Blick
auf einen guten Run findet. Geklammert bei 0, weil Ticks nach einem Hänger im Bündel kommen und eine
negative Lesung als *gewonnene* Zeit lesen würde.

`Config.splitsLag`, an by default; die Panel-Zeile hängt zusätzlich an `splitsTickTime`, weil die Zahl die
Differenz der zwei Spalten ist und auf einem Panel mit nur einer davon nichts hätte, woran man sie prüfen
kann. `Format.MS_PER_TICK` ist von `private` auf `internal` — eine Definition von „ein Tick sind 50 ms".
Im Leaderboard-Payload muss dafür nichts dazu: `totalMs` und `totalTicks` sind beide drin, die Box kann
die Differenz selbst bilden.

**Zum Testen: `build/libs/sighteaddons-0.17.0-dev15.jar`** (20.08., 450 Tests grün), gebaut mit
`./gradlew assemble check -Pmod_version=0.17.0-dev15`. `gradle.properties` steht weiter auf `0.16.0`,
`dist/` hält unverändert das released 0.16.0.

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

**Der Solo-M7 ist gelaufen** (dev5, Session `session-1787167317404.jsonl`) und **es ging nichts raus —
korrekt.** Die Verweigerung war die richtige: Solo stimmte (`tab_slot` nur Slot 0, `classes:
['Archer XLVII']`), Floor M7, Schalter an, Gate 300, nicht im Boss. Uebrig bleibt nur, dass der Score im
Clear nie 300 erreichte; der Run endete `complete: false` nach 7160 Ticks mit 17 Raeumen.

**Nur war das aus dem Log nicht ablesbar, und das war der eigentliche Fehler.** `score_source` feuert
nur bei Quellenwechsel, also einmal mit `score: 0` — ein Run, der bei 268 stand, sah aus wie einer,
dessen Score nie lesbar war. Dagegen jetzt zwei Zeilen (dev6):

- **`score_step`** alle 25 Punkte neuen Maximums, plus `score_high` am Run-Ende: die Kurve, zwoelf
  Zeilen pro Run.
- **`solo_clear_missed`** einmal pro Run, wenn der Schalter an war und nichts rausging: `why` (welche
  der sieben Verweigerungen zuletzt stand), `gate`, `high`, `short` und `solo`.

`LiveScore.high` ist ein Hoechststand und faellt nicht mit dem Score zurueck — der Time-Score sinkt im
Laufe des Runs, und die Frage ist, wie nah der Run *je* war.

**Der Nachbau war an einer Stelle nicht der Nachbau, und die hat den Run gekostet** (dev7, 19.08.):

- **Upstreams `SoloClearsTracker.tick()` laeuft auf *jedem* Client-Tick** und fragt nur `inDungeons()` —
  Tab-Liste enthaelt "Dungeon: Catacombs". Kein Map-Check, keine Clear-Phase-Bedingung, kein `complete`.
  Unsere Pruefung sass **hinter** dem Boss-Return in `SighteAddons.tick` (Hypixel nimmt die Map im Boss aus
  dem Hotbar), lief also im Boss und danach nie. Jetzt sitzt sie **vor** dem Return.
- **Upstream vergleicht `max(live, chatScore)` gegen 300**, nicht nur den Live-Score
  (`if (chatScore >= 300 && chatScore > finalScore) finalScore = chatScore`). Auf den meisten Runs kreuzt
  der Live-Score die Schwelle spaet oder nie, und **`Team Score:` ist die Zahl, die den S+ bestaetigt** —
  sie kommt nach dem Boss. Ein Gate nur auf dem Live-Score verweigert Runs, die qualifiziert waren.
  `SoloClear.best(live, chat)` ist diese Regel, benannt statt inline.
- **Solo-Erkennung wie Upstream:** `Solo` / `Party (1)` auf Sidebar **und** Tab-Liste
  (`DungeonSession.SOLO`), gelatcht. Das Roster bleibt als Veto — Text sagt solo, Roster zeigt fuenf
  Leute, dann wird nicht angekuendigt.
- Zeitquellen in der Reihenfolge, die zu jedem Trigger-Zeitpunkt eine Antwort hat: Sidebar
  `Time Elapsed:` → Tab `Time:` → Chat `Clear Time:` (die einzige, die nach dem Run noch existiert).

Damit ist der `inBoss`-Refusal weg. Ein Run, der 300 erst im Boss oder erst laut `Team Score:` erreicht,
wird jetzt angekuendigt — genau wie in der Vorlage.

**Die Kette ist bewiesen** (dev7, Session `session-1787168867208.jsonl`): ein Solo-M7, in dem der Spieler
nach einem Raum starb, ist rausgegangen — `solo_clear` mit `pb: true`, `time: "10s"`, und die Zeile liegt
in `soloclears.jsonl`. Mehr Beweis braucht der Pfad nicht.

**Zwei Runs, zwei verschiedene Gruende fuer Stille, keiner davon ein Fehler im Gate:**

- Der abgebrochene: `gate: 0`, Run zu Ende (Tod) → **angekuendigt**.
- Der bis 300 gespielte: `gate: 0` und `complete: false` nach 6895 Ticks / 18 Raeumen. Bei Gate 0 besitzt
  die **Run-Ende-Headline** die Ankuendigung, und die druckt Hypixel nur fuer einen beendeten Floor. Ein
  verlassener Run kann in diesem Modus nichts ausloesen. **Fuer "im Clear bei 300 posten" muss das Gate
  auf `300 · S+` stehen, nicht auf 0.**

**Und die Sidebar-Zahl ist NICHT identifiziert.** Sie lief 25 → 266 ueber den Solo-M7, den der Spieler bei
300 sah, und auf dem Zwei-Raum-Run stand sie auf 35, waehrend Hypixels `Team Score:` **24** sagte. Die
Deutung "die Klammer ist der Score" aus der Party-Session war zu schnell: dort passte sie zu zwei
Messpunkten, hier passt sie zu keinem.

Deshalb rechnet `LiveScore` seit dev8 die Formel **daneben** mit (alle 10 Ticks, wie Upstream) und beide
Zahlen stehen in `score_step`, `score_high` und vor allem in `run_score` neben Hypixels Endzahl. **Ein
gewoehnlicher Party-Run genuegt**, um zu sehen, welche der beiden mit `Team Score:` uebereinstimmt — danach
ist die andere ein Feld zum Loeschen. Solange nichts identifiziert ist, kann die Sidebar-Zahl nur zu
*niedrig* sein und damit nur zu spaet feuern, nie falsch: `best()` nimmt das Maximum aus ihr und der
Chat-Zahl.

Zwei Warzen im Diagnose-Log dazu gefixt: `solo_clear_missed` trug immer `floor: "?"` (gelesen, nachdem
`DungeonSession.reset` den Floor genullt hat — jetzt beim Refusal gemerkt), und es feuerte bei jedem
Lobby-Hop mit `why: "not in a run"`, was die eine Zeile ertraenkt hat, die etwas bedeutete.

**Zum Testen: `build/libs/sighteaddons-0.17.0-dev8.jar`** (19.08., 429 Tests grün). Gebaut mit
`./gradlew assemble check -Pmod_version=0.17.0-dev8` — `mod_version` in `gradle.properties` steht
weiter auf `0.16.0` und `dist/` hält unverändert das released 0.16.0. Der Dev-Jar ist als
`0.17.0-dev8` gestempelt, damit `X-Mod-Version` und `modVersion` in den Reports ihn nicht mit dem
Release verwechseln.

**Der `SecretApi`-Fix liegt auf `main` und bei keinem Spieler.** Er kostet ein Release, und ein
Release ist die Entscheidung des Users; bis dahin läuft draußen 0.16.0 mit dem alten Verhalten.

**Das UI-Redesign ist durch, alle sieben Phasen** (Tokens/Motion, Komponenten, HUD, Overlays, Stats,
Config, Chat) plus zwei Review-Durchgänge, 18 Befunde. Kein Farbliteral mehr außerhalb `ui/theme/`,
`UiThemeTest` liest den Quellbaum und fällt bei jedem 8-stelligen ARGB darüber hinaus. Details im
`git log`; `runClient` ist damit wieder gesperrt (CLAUDE.md-Regel 2).

## Was das Redesign offen gelassen hat

- ~~**Der Blood-Room hat kein HUD-Split.**~~ **Erledigt am 19.08. durch den Splits-Port**, aber nicht
  so, wie es hier stand: die Blood-Spanne steht jetzt als Zeile `blood clear` auf dem Splits-Panel,
  gemessen von Tür/Watcher bis zur Pass-Zeile — **dieselbe Spanne, die `BloodClear.kt` als Kind
  `bloodclear` ins `history.jsonl` schreibt**, nur in Millisekunden und Server-Ticks statt in Run-Ticks.
  Kein `HudSnapshot`-Feld dafür, und das war die eigentliche Antwort auf die Warnung oben: das Panel
  liest eine reine Funktion (`Splits.readout`) direkt, wie `StormHud` es tut, statt einen Raum-Timer
  gegen einen Run-Split zu deltaieren. Die Karte hat nach wie vor kein Blood-Delta, und soll keins.
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

## secrets-001 — Team-Secrets brauchen keinen Key mehr (20.08., ohne Versionsänderung)

`SecretApi` fragt **die Box** statt Hypixel: `GET /v1/secrets/<uuid>` mit dem Upload-Token, das jede
Installation schon hat. **Es gibt keinen Key mehr in der Mod und keinen Weg, einen einzutragen** —
`Config.hypixelKey`, das `/sa`-Feld, `Kind.FIELD` und der direkte Hypixel-Pfad sind alle weg.

Die erste Fassung behielt das Feld als „Override". Das war ein halber Schritt: wer noch einen Wert
drinstehen hatte, blieb genau auf dem ablaufenden Key, dessen Unsichtbarkeit der Anlass war. Und ein
Config-Key, den niemand mehr setzen kann, ist toter Code hinter einer toten Einstellung.

Mitgegangen sind damit `SecretApi.parse` (Hypixels Dokumentform — das parst jetzt `secret_from_body`
auf der Box, wo `SecretBody` es hält), `HOST`, und in `SettingsScreen` die ganze Feld-Mechanik:
`keyEdit`/`keyFocused`/`keyRevealed`, `clickField`, `editKey`, `pasted`, `blurKey`, `commitKey`,
`FIELD_MAX`, `KEY_MAX`. `TextField` bleibt — fertige, getestete Komponente mit Gallery-Seite, und das
Argument darin (ein Feld für ein Geheimnis **echot** nicht) überlebt seinen ersten Verbraucher.

Ein `config.json` von vorher trägt sein `hypixelKey` noch; nichts liest es und der nächste Save wirft
es raus. Das ist die richtige Richtung: ein Credential, das die Mod nicht mehr benutzt, soll nicht in
einer Config liegen bleiben.

**Genau so machen es die anderen Dungeon-Mods, nachgesehen statt vermutet.** Odins `SecretsCounter`
rechnet dieselbe Baseline-minus-Delta und holt den Wert von `api.odtheking.com/hypixel/secrets/<uuid>`
— dem Proxy des Autors, mit dessen Key. Odin cached den Secret-Wert dabei **nicht** (nur UUID und
Profil, 5 min): ein Fenster in Runlänge würde die zweite Lesung aus der ersten beantworten und jedes
Delta still auf null setzen. Dieselbe Grenze steht auf der Box als `SECRET_TTL = 30`.

Anlass war der 19.08.: der Key im Client war ein Development Key und lief nach etwa einem Tag ab. Von
innen ist das ein `403`, das identisch aussieht wie „kein Key", „privates Profil" und „Timeout" — drei
Runden Suche, und die Mod konnte nichts davon sagen. Deshalb trägt `secret_api_baseline` jetzt `via`
(`box`/`key`), und die Box schreibt die Absage im Klartext ins Journal.

Ein `404` von der Box latcht `boxRouteMissing` für die Sitzung (ein Receiver ohne die Route würde sonst
fünf Requests pro Run damit verbringen, fünfmal dasselbe zu lernen); `502`/`503` latchen **nicht** —
die Route ist da und die Antwort ist heute nein.

Dabei mitgefixt: beide Parser nahmen `"812"` als `812`, weil Gson einen String zu Int macht. Der
Receiver lehnt das ab, die Mod tat es nicht — eine Feature-Hälfte war strenger als die andere.

**Die Receiver-Hälfte ist nicht deployt** (`skyblock-server`, Branch `secrets-001`). Ohne
`SIGHTE_HYPIXEL_KEY` antwortet die Route `503`, die Mod fällt auf den Override zurück und ohne den auf
das alte Verhalten — Strich für Mitspieler. Regel 1 bleibt: Receiver zuerst.

## Splits (19.08., ohne Versionsänderung)

**Odins Splits sind portiert, Dungeons only** — E, F1–F7, M1–M7. `DungeonSplits.kt` ist die
Transliteration seiner Tabellen (jedes Chat-Pattern, die Floor-Gruppen, die Reihenfolge),
`Splits.kt` die Kette: eine Spanne wird der *früheren* von zwei Marken zugeschrieben, das erste Signal
gewinnt, Master Mode nimmt die F-Zeilen und eigene Records, `Starting in 1 second.` armiert. Zwei
Uhren pro Zeile — Wanduhr und Hypixels Server-Ticks (`ServerTicks.kt` + `ConnectionMixin.java`, dritter
Mixin, zählt `ClientboundPingPacket` mit `id != 0` wie Odins gleichnamiger).

Bewusst *nicht* 1:1: monochrom und `m:ss.t` durch `Format` statt `§`-Farben und `59m 59s (59.9)`; die
Tick-Zeit ist eine zweite rechtsausgerichtete Spalte statt einer Klammer; das `boss entry`-Kriterium ist
`size > 4` statt Odins `> 3`, das dem Entrance eine Boss-Zeile gibt; und `Config.splits` **aus** schaltet
auch die Records ab, wo Odin sie weiterschreibt.

**Die Zusammenfassung armiert an der Headline und wird von `☠ Defeated` ausgelöst** — gemessen am M7 vom
19.08. 23:22, und die erste Fassung war falsch. Beide Zeilen kommen auf **demselben** Tick, die Headline
zuerst (`run_end` → `split_missing` → `split total` im Session-Log). An der Headline gedruckt war
`cleared` eine noch laufende Spanne und `total` gab es überhaupt nicht — die Zeile fehlte still. Das ist
`SoloClear`s Arm/Release, aus demselben Grund. Odins 10-Tick-Defer bleibt trotzdem draußen: er müsste an
`onTick` hängen, das an mehreren Stellen früh zurückkehrt.

**Records: `SplitPbs.kt`, in `config.json`, auf Odins eigenen Keys** (`DungeonM7` → `blood open` →
Sekunden). Nicht in `history.jsonl` — andere Einheit, anderes Subjekt, keine `kind` umdefiniert.
`/sa` → debug → *import from odin* liest `config/odin/odin-config.json`, strippt die Farbcodes,
nimmt das Minimum und ist idempotent. **Keine eingecheckte Tabelle mit Zeiten**: zwei Leute benutzen
die Mod, ein Seed im Jar hätte dem zweiten die Rekorde des ersten als eigene untergeschoben.

Panel und Uhr liegen **über** dem `calibrated`-Gate in `renderHud`, wie `StormHud` — die Hälfte der
Spannen läuft in der Bossphase, genau wo die Karte ausblendet. `/sa gallery` Seite `0` zeigt beide
gefroren auf einem scripted Mid-Run-F7 (`Splits.sample`), derselbe Zustand, den der Platzierungsmodus
zieht.

**Unverifiziert und nur im Spiel klärbar:** ob die ~20 Hypixel-Strings stimmen. `splits_armed`,
`split` und `split_missing` im Session-Log sind die Instrumentierung dafür — ein Name unter `unclosed`
oder `unstarted` auf einem Floor, der den Boss sicher erreicht hat, ist ein Pattern zum Korrigieren.
**Odin 0.3.0 läuft parallel im Modpack**: ein echter Run zeigt beide Panels gleichzeitig, und jede
Zeile, die abweicht, benennt ihren Regex.

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
