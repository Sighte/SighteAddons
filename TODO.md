# TODO — SighteAddons

## Stand — 2026-08-19

`main` = **458 Tests / 40 Klassen / grün**, `mod_version` 0.16.0 released (`v0.16.0`, Modrinth
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

`SecretApi` fragt jetzt **die Box** statt Hypixel: `GET /v1/secrets/<uuid>` mit dem Upload-Token, das
jede Installation schon hat. `Config.hypixelKey` bleibt als Override für jemanden, dessen Party-UUIDs
nicht über die Box laufen sollen — `SecretApi.Source` ist die Entscheidung, `Box` gewinnt.

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
