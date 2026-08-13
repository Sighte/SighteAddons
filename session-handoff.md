# Session Handoff

Overwrite this file at the end of every session — it describes the current state only. The
historical record lives in `claude-progress.md`.

## Verified Now

- What is currently working: the build and the unit suite. 79 tests across 11 classes, 0 failures.
  `mod_version=0.9.0`, `dist/sighteaddons-0.9.0.jar` unchanged.
- What verification actually ran (exact commands):
  - `bash init.sh` → `BUILD SUCCESSFUL in 25s`, `BASELINE: PASSING`

## Changed This Session

- Code or behavior added: none. No `.kt`, no `.java`, no resource, no `build.gradle` line, no
  version bump.
- Infrastructure or harness changes: the DevLoop harness — `init.sh`, `feature_list.json`,
  `claude-progress.md`, this file, `quality-document.md`, `evaluator-rubric.md`, an Operating Loop
  section appended to `CLAUDE.md` (the release gate stays first and unedited), and `*.sh text eol=lf`
  in `.gitattributes`.

## Broken Or Unverified

- Known defect: none introduced.
- Unverified path: everything `ingame-001` lists — calibration, decoration mapping, checkmark
  reading and core hashing have never run against real Hypixel data, and no pixel of the `/sa`
  screen has been on screen. That was true before this session and is still true.
- Regressions found: none.
- Risk for the next session: the six seeded features come from the README's own "Not implemented
  yet" and "Known limits" sections plus the `ponytail:` notes, and have not been agreed with the
  user. Confirm before building `clear-001`.

## Next Best Step

- Highest-priority unfinished feature: `clear-001` — anchor `enterTick` on a minimum stay.
- Why it is next: it is the one open item that makes another number wrong. `clear` is reported as a
  duration but is only an upper bound, so `avgSeconds` on the server cannot be used as a difficulty
  weight until this lands.
- What counts as passing: a `ContributionTrackerTest` that pins the minimum-stay behaviour, the
  field present in `RunReport`, `./gradlew test` green, evidence in `feature_list.json` — **and the
  receiver's `schema-001` deployed first**. A build that sends a field the receiver has not learned
  gets a `400` per report and `TelemetryUpload` never retries it.

## Do Not Touch

- `rooms.json` — Odin's database verbatim under BSD-3 (`LICENSE-Odin`). Never edited, never
  regenerated. The receiver reads this exact file.
- `mod_version` in `gradle.properties`, unless you intend to run the whole release gate at the top
  of `CLAUDE.md` (tagged GitHub release + Modrinth, same jar, same notes).
- `dist/` by hand — `./gradlew build` refreshes it. A `build` that changes the committed jar means
  it was stale, which is a finding, not a fixup.
- The report schema, until the receiver's paired change is deployed.

## Environment Quirks

- The first `./gradlew test` on a cold cache downloads Loom, the Minecraft jar and the Mojang
  mappings — minutes, not seconds. A run that looks stuck is almost always still downloading. Warm,
  it is ~25 s.
- JDK 25+ required (bytecode 25 via `--release`, no pinned toolchain). Gradle uses `JAVA_HOME`, not
  `PATH`, so `init.sh`'s version line can describe a different JDK than the build uses.
- Mappings are official Mojang, not Yarn — class names in this repository are Mojmap.
- `./gradlew runClient` cannot log in to Hypixel. `run/config/sighteaddons/debug/session-<millis>.jsonl`
  from a real install is the only source of real data.
- Git is set to `core.autocrlf=true` on this machine; `/gradlew` and `*.sh` are pinned to LF in
  `.gitattributes` and must stay that way.

## Commands

- Startup: `./gradlew runClient`
- Smoke check: `./init.sh` (wraps `./gradlew test`)
- Full verification: `./gradlew build`
- Focused debug command: `./gradlew test --tests 'sighteaddons.<Class>'`
