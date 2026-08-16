# SighteAddons

Zwei Leute benutzen diese Mod. Der Prozess ist dafür dimensioniert.

**Loop:** `TODO.md` lesen → `./init.sh` → eine Sache machen → committen → `TODO.md` nachziehen.
Direkt auf `main`, keine Branches — hier deployt nichts. Push ist frei, **solange `mod_version`
gleich bleibt**; eine Versionsänderung ist ein Release und gehört dem User.

Keine Grading-Pässe, keine Rubrics, keine Mutation-Sweeps, keine Evidence-Formulare.
`TODO.md` ist das einzige Session-Artefakt, ~50 Zeilen. `git log` ist die Historie.

## Tests

**Nur schreiben, wenn du sonst raten müsstest.** 289 Tests für eine Mod mit zwei Nutzern sind
bereits mehr als genug — ein neues Feature braucht null bis zwei, und meistens null. Kein Test darf
abgeschwächt oder gelöscht werden, um Arbeit fertig aussehen zu lassen; aber ein Test, der nur die
Implementierung nacherzählt, kostet nur Zeit.

`./gradlew test` muss grün sein, bevor etwas fertig heißt (3 s warm). Was vorher lief und jetzt
fällt, ist die nächste Arbeit.

## Die vier Regeln, die kein Prozess sind

1. **Der Receiver ist zuerst dran, wenn sich das Report-Schema ändert.** Ein Feld, das `ingest.py`
   nicht kennt, ist ein `400`, `TelemetryUpload` wiederholt nie, der Run ist endgültig weg. Vor jeder
   Änderung an `RunReport.kt`: die Felder gegen `RUN_KEYS` in `ingest.py` diffen (auf `obj.add` **und**
   `obj.addProperty` ankern — `addProperty` allein verfehlt `rooms` und `classes`).
2. **Niemals `./gradlew runClient`.** Öffnet ein Minecraft-Fenster auf dem Rechner, auf dem der User
   gerade spielt. Kein Startup-Check, kein "nur einmal".
3. **`rooms.json` wird nie angefasst** — Odins Datenbank, BSD-3, der Receiver liest genau diese Datei.
4. **Kein Push mit geänderter `mod_version` ohne den User.**

## Release

Modrinth zeigt das Projekt öffentlich als 404, die Zielgruppe sind zwei Leute. Also kurz:

1. `./gradlew build` grün, `dist/sighteaddons-<version>.jar` ist die committete Datei (ändert der
   Rebuild sie, war die committete alt).
2. `gh release create "v<version>" "dist/…jar" --target main --title "…" --notes-file -`
   — **Titel ≤ 64 Zeichen**, sonst stirbt der Modrinth-Upload *nach* dem GitHub-Release (0.15.0).
3. Notes: was sich geändert hat, und ob die Receiver-Hälfte deployt ist. Mehr nicht.
4. `.github/workflows/modrinth.yml` läuft auf `release: published` — Run einmal anschauen.

Nie eine Version rausgeben, ohne vorher `mod_version` zu erhöhen; sonst weiß niemand, was läuft.

## Diese Maschine

- `python`, nicht `python3` (Windows-Alias-Stub). JDK 25+, Gradle nimmt `JAVA_HOME`, nicht `PATH`.
- **Testzahlen aus `build/test-results/test/*.xml`**, nicht aus der Konsole.
- Mid-Feature `./gradlew assemble check`, nie `build` — `build` überschreibt das released Jar.
  `--rerun-tasks` nur, wenn der Punkt ist, dass nichts gecacht war.
- Windows-Python kann `./gradlew` nicht starten (`WinError 193`) — Gradle aus bash.
- **Ein Python-Replace über eine Kotlin-Datei braucht `\r\n` im Anker** (Working Tree ist CRLF).
  `assert source.count(old) == 1` vor jedem Apply — ein Probe, der nicht greift, sieht aus wie einer,
  der bestanden hat.
- `PYTHONIOENCODING=utf-8` vor dem Printen dieser Quellen, sonst stirbt `print` am Gedankenstrich.
- Echte Session-Logs (read-only):
  `%APPDATA%\PrismLauncher\instances\Skyblock 26.1.2 Modpack\minecraft\config\sighteaddons\debug\session-*.jsonl`.
  Event-Key ist `e`, nicht `event`. Eines mit `secret_room_first_bar` ist 0.12.0+.
  **Nicht** `config/sighteaddons/debug/` im Repo greppen — da liegt nur Testrauschen.
- `net.minecraft.ChatFormatting` lädt im Unit-Test, `MapItemSavedData`/`MapDecoration` nicht.
  `ContributionTracker`, `RunReport` und `RoomStats` sind `object`s mit Prozess-State — im
  `@BeforeEach` zurücksetzen. `DungeonSession.reset()` **nicht** zum Aufräumen aufrufen, das setzt
  die halbe Mod zurück.
- `gh pr merge` wird vom Permission-Classifier abgelehnt; `git merge --no-ff` + Push macht denselben
  Commit. **Eine Ablehnung ist kein fehlgeschlagenes Release.**

## Nicht anfassen — jedes davon ist gemessen

- **`TrackedRoom.readBar` liest den Bar, BEVOR es auf einen Anstieg testet.** Ein `0/10` ist kein
  Anstieg und die einzige Lesung, die sagen kann, dass der Raum unberührt war. Dreht man das um —
  genau das, was ein Aufräumen tut — ist die erste Lesung die `1/10` danach, kein Raum sieht je
  sauber aus, und **jeder Secret-Run im Spiel wird still verworfen.**
- **`RoomHistory.ownClear` bleibt fünf einzelne Zeilen**, inklusive `MIN_TICKS`-Boden. Eine
  zusammengezogene Bedingung ist nicht einzeln prüfbar, und dieses Prädikat hat schon zwei Guards
  produziert, die nur dem Namen nach welche waren.
- **`ownSecrets == secretsFound` wird nicht aufgeweicht**, nicht konfigurierbar gemacht, kein
  Schlupfloch. Der User hat die gemessenen Kosten gesehen und zweimal bestätigt; steht so in den
  öffentlichen 0.12.0-Notes.
- **`SecretHud` zeigt Attribution an und repariert sie nie.** Fallback auf `secretsFound`, wenn
  `ownSecrets` 0 ist, schreibt dem lokalen Spieler die Secrets der Party auf den Bildschirm.
- **Keine Metrik wird umdefiniert.** `clear` = `room.ticks[self]`, `secretrun` =
  `room.secretRunTicks`. `history.jsonl` ist append-only und 0.12.0 ist draußen — gate *ob* eine
  Zeile geschrieben wird, nie was drinsteht.
- **`RunReport.SCHEMA` ist 6 und geht nicht zurück.** `idleTicks`/`navTicks` werden zusammen oder
  gar nicht geschrieben — der Receiver liest einen fehlenden Key als "dieser Build kann das nicht
  messen", eines allein behauptet, das andere sei null.
- **`DungeonSession.floor` bleibt `@Volatile`** (DISCONNECT liest es aus einem Netty-Thread), und nur
  `reset()` löscht es, nach `RunReport.write`.
- **Nie wieder einzubauen** (aus der portierten Crit-Mod): der `ApiSender`, der bei jedem Treffer
  Name/Crit/Power an einen Dritten POSTete, und die automatischen `/msg`- und Party-Zeilen.
