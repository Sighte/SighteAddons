# CLAUDE.md — SighteAddons

Two people use this mod. **The process is sized for that** — enough to know what is running and not
to break what already works, and no more.

`ENVIRONMENT.md` holds the toolchain, the traps that make a probe silently do nothing, and the code
invariants a tidy-up would break. Read it when you are about to meet one, not as a ritual.

## Loop

1. Read `TODO.md`.
2. `./init.sh`. A FAILING baseline is the task, ahead of anything else.
3. Do one thing. Commit it on a branch.
4. Update `TODO.md` — what changed, and plainly what is still unverified. That last part is the one
   thing worth spending words on: the dev client cannot reach Hypixel, so a reader will otherwise
   assume a path was tested.

`TODO.md` is the only session artifact. Keep it under ~90 lines; `git log` is the history.

## Rules

- Do not claim something works without having run it.
- Tests pin behaviour that could plausibly break. They are not a score. A handful of cases that would
  catch a real mistake beats twenty that restate the implementation, and nothing needs a test count
  to justify itself. Never weaken a test to make work look finished.
- Find something else broken while working? Write it in `TODO.md` and stay on the thing you are on.

## What this repository is, that a session has to respect

- **Never run `./gradlew runClient`.** It opens a real Minecraft window on the machine the user is
  playing on. Instructed 2026-08-17 after several sessions launched it to check that a mixin applied.
  It is not a fallback and there is no case that justifies it — not a startup check, not "just once".
- **The dev client could not reach Hypixel anyway.** Calibration, decoration mapping, checkmark
  reading and core hashing are not verifiable here. Something that needs a real dungeon run is
  *unverified*, and saying so is the deliverable.
- **What that costs, so it is not a surprise:** a mixin injector that fails to resolve and an
  `@Accessor` naming a field that does not exist are both invisible to the compiler. `javap` on the
  merged jar is the only local check for a field name; for an injector there is none.
- **The receiver moves first for a schema change.** The report schema is compiled in and validated by
  [`Sighte/skyblock-server`](https://github.com/Sighte/skyblock-server) (`ingest.py:282`): a field
  the validator has not learned is a `400`, `TelemetryUpload` never retries it, and the run is gone
  permanently. Before touching `RunReport.kt`, run `python build/keydiff.py`.
- **`rooms.json` is Odin's database verbatim** (`LICENSE-Odin`, BSD-3). Never edited, never
  regenerated; the receiver reads this exact file.
- **`dist/` holds exactly one jar** and `build` refreshes it — a `build` that changes the committed
  jar means it was stale. Mid-feature use `./gradlew assemble check`, never `build`, or you swap the
  released artifact for a different build wearing the same version number.
- **The upload URL and the report schema are compiled in**, so a change here can take previous
  installs off the air. That is a release-note line, not an implementation detail.

## Evidence

**One command, its result, and the commit it ran against.** One line, not a report. "I tested it
manually" without a command anyone can re-run is not evidence. For anything that only shows up in a
real dungeon, the evidence is the debug session file and the line in it that proves the claim.

`--rerun-tasks` is not a default — it defeats the up-to-date check. Use it when the point is that
nothing was cached, and nowhere else, or it fossilizes into every later copy of the command.

## Done

A thing is done when its own check passes and `./gradlew test` is still green (~1.1 s of actual
execution). If something that used to pass now fails, that is the next work.

That is the whole gate. No grading pass, no rubric, no mutation sweep by default. Reach for a
mutation probe when you genuinely doubt a test, not once per feature.

## Releasing — the one heavy thing that stays

A published build reaches other people's game, so **a version bump is never pushed to `main` on your
own.** When the user asks for one:

1. `git status` clean, `main` up to date with `origin/main`.
2. `./gradlew build` passes and `dist/sighteaddons-<version>.jar` is the committed file. If the
   rebuild changes it, the committed jar was stale and the release would ship something nobody
   reviewed. (The build is reproducible here — two `--rerun-tasks` builds gave byte-identical jars.)
3. The jar's own `fabric.mod.json` says the version. The filename is not evidence.
4. **Title ≤ 64 characters** — Modrinth's `version_title` cap, and the workflow sends the GitHub
   title verbatim. 0.15.0's was 65 and the upload died *after* the GitHub release was published.
   Repair: `gh release edit <tag> --title`, then re-run the workflow.
5. `gh release create "v<version>" "dist/sighteaddons-<version>.jar" --target main --title ... --notes-file -`
6. **Check the Modrinth run** — `.github/workflows/modrinth.yml` fires on `release: published`, and a
   failed upload leaves exactly the split this exists to prevent: players on an older build than the
   notes describe. Same version, same jar, same changelog text on both pages.

Notes say four things, short: what changed (behaviour first, one line per PR); **whether the receiver
half is deployed**; the jar's `sha256` plus the Minecraft/loader versions read out of
`gradle.properties`; and what is *not* verified. Operator steps go in the GitHub notes only — name
that section exactly `## Not in the jar` and never place it last, because the workflow's strip is a
lookahead that silently keeps it otherwise.

Do not backfill releases for versions that never had one; the tags would claim a review that never
happened.
