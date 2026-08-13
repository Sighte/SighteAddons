# SighteAddons

## Every version bump gets a tagged GitHub release

The mod is published, so a build that exists has to be findable and its jar has to be identifiable
after the fact. `dist/` holds exactly one jar and git history holds the rest — that is enough to
rebuild a version but not enough for somebody to download one, which is what a release is for.

**Trigger:** `mod_version` in `gradle.properties` changed and that change is merged to `main`. One
release per version, cut from `main` after the merge, never from a branch.

**Before publishing, three things have to be true.** All of them are cheap and all of them have been
wrong at least once:

1. `git status` is clean and `main` is up to date with `origin/main`.
2. `./gradlew build` passes and `dist/sighteaddons-<version>.jar` is the committed file — if the
   rebuild changes it, the committed jar was stale and the release would ship something nobody
   reviewed.
3. The jar's own metadata says the version: `fabric.mod.json` → `"version"`. The filename alone is
   not evidence.

```bash
gh release create "v$(grep '^mod_version' gradle.properties | cut -d= -f2)" \
  "dist/sighteaddons-$(grep '^mod_version' gradle.properties | cut -d= -f2).jar" \
  --title "..." --notes-file -
```

### What the notes have to say

"Documented" means a reader can decide whether to install it and an operator knows what else to do.
In this project the jar is regularly only half of a change, so the notes carry:

- **What changed**, per merged PR, in one line each — behaviour first, internals only if they are
  visible from outside.
- **What is not in the jar.** The receiver lives in
  [`Sighte/skyblock-server`](https://github.com/Sighte/skyblock-server) and a push to its `master`
  deploys, so the question is whether the matching change is *pushed there* rather than whether
  somebody copied a file. Name the commit or PR it needs. A build whose receiver side is behind
  silently rejects every report it produces — the report schema is compiled in, and a field the
  validator has not learned yet is a `400` the client never retries.
- **What breaks for older installs.** The upload URL and the report schema are compiled in, so a
  bump can take previous builds off the air — say so plainly, and say whether their queued data
  survives.
- **Requirements**: Minecraft, loader, Fabric API and fabric-language-kotlin versions, read out of
  `gradle.properties` rather than remembered.
- **`sha256`** of the attached jar.
- **What is not verified.** The dev client cannot reach Hypixel, so anything needing a real run is
  unverified until somebody plays one. Name those paths instead of leaving the reader to assume they
  were tested.

Do not backfill releases for versions that never had one — the tags would claim a review that never
happened. Start where the habit starts.

## The same build goes to Modrinth, in the same step

A jar on GitHub and not on Modrinth means the players who actually install this mod are on an older
build than the one whose notes describe the current server behaviour. The report schema and the upload
URL are compiled in, so "older build" here is not cosmetic: it decides whether their runs arrive at
all. A GitHub release is therefore not finished until the same file is on Modrinth.

`.github/workflows/modrinth.yml` does it on `release: published`, so this is a trigger rather than
something to remember. It needs `MODRINTH_TOKEN` as a repo secret and `MODRINTH_PROJECT` as a repo
variable. **Check the run after publishing a release** — a failed upload leaves exactly the split this
section exists to prevent, and nothing else will tell you.

- **Same version number, same jar** — the file attached to the release, not a rebuild. A second build
  of the same source is a different `sha256`, and then neither number identifies anything.
- **Same `version_type`** and the same changelog text, so the two pages cannot tell different stories
  about one build.
- **`loaders`, `game_versions` and dependencies** come out of `fabric.mod.json` in the jar being
  uploaded rather than from memory — that file is the only place that cannot be out of date with the
  artifact.

One deliberate difference: the operator steps — anything waiting in `Sighte/skyblock-server`, env
changes on the box — stay in the GitHub notes only. Nobody installing from Modrinth runs that box, so
on their page those lines are noise that reads like a setup requirement for the mod. Everything a
player can act on (what changed, requirements, what is unverified) belongs on both.

Publishing there is the point at which the public token in the jar is genuinely public and strangers
can post to `/runs`. That is by design and documented, but it happens on the first Modrinth upload and
not on the first GitHub release — so it is worth saying out loud when it is about to happen.

## Operating Loop

This repository is worked on in long-running sessions. Prioritize reliable completion, continuity
across sessions, and explicit verification over speed. Everything above stays in force — the release
gate is what happens when a version bump lands, this is what happens on every ordinary day.

At the start of every session:

1. Confirm you are in this repository root (`build.gradle` and `gradlew` are here).
2. Read `claude-progress.md`, the "Current Verified State" section first.
3. Read `feature_list.json`.
4. `git log --oneline -5`.
5. Run `./init.sh` and note the reported baseline status.
6. If the baseline is FAILING, repairing it is the active task. Do not start or continue any feature
   until it is green again. Record the repair as evidence in the progress log.

Then select exactly one unfinished feature — respecting `depends_on` order — and work only on that
feature until you either verify it or document why it is blocked.

### Rules

- One active feature at a time.
- Do not claim completion without runnable evidence.
- Do not rewrite the feature list to hide unfinished work.
- Do not remove or weaken tests just to make the task look complete.
- Use repository artifacts as the system of record.
- If you discover new required work while implementing, do not fix it inline. Add it to
  `feature_list.json` as a new feature (or record it as a blocker) and stay on the active feature.
- Do not modify or delete harness files (`CLAUDE.md`, `init.sh`, the schema of `feature_list.json`,
  this operating loop) unless the user explicitly asks for it. Record any harness change in the
  progress log.

### What this repository is, that a session has to respect

- **The dev client cannot reach Hypixel.** `./gradlew runClient` has no valid session, so calibration,
  decoration mapping, checkmark reading and core hashing cannot be verified here at all. A feature
  that depends on a real dungeon run is `blocked`, not `passing` — say which paths are unverified
  rather than letting the reader assume they were tested.
- **`rooms.json` is Odin's database verbatim** (`LICENSE-Odin`, BSD-3). Never edited, never
  regenerated. The server side reads this exact file rather than a copy.
- **`dist/` holds exactly one jar** and `build` refreshes it. A `build` that changes the committed jar
  means it was stale.
- **The upload URL and the report schema are compiled in**, so a change here can take previous installs
  off the air. That is a release-note line, not an implementation detail.

### Cross-repo order — the receiver moves first

The report schema is compiled into this mod and validated by the receiver in
[`Sighte/skyblock-server`](https://github.com/Sighte/skyblock-server) (see the `ponytail:` note at
`ingest.py:282`): a report carrying a field the validator has not learned is a `400`, and
`TelemetryUpload` never retries it. So for any schema change:

**the receiver accepts the new field and is deployed first — the build that sends it comes after.**
The reverse order loses every run of that build, permanently.

Before touching `RunReport.kt`, diff the fields it writes against `RUN_KEYS` in the receiver's
`ingest.py`. Note the paired feature id in the `notes` field of `feature_list.json` on both sides.

### What Counts as Evidence

"Runnable evidence" means something the next session can re-execute or inspect. Every evidence entry
in `feature_list.json` must contain:

- the exact command that was run,
- the relevant output excerpt (or test name and result),
- the commit hash the verification ran against,
- optionally an artifact path (a `run/config/sighteaddons/debug/session-<millis>.jsonl`, a log).

"I tested it manually" without a reproducible command is not evidence. For anything that only shows
up in a real dungeon, the evidence is the debug session file and which line in it proves the claim.

### Regression Policy

Before moving any feature to `passing`:

1. Run the feature's own `verification_command`.
2. Re-run `./gradlew test` over previously passing features.
3. If a previously passing feature broke, set its status to `regressed` and treat the regression as
   the highest-priority work after the current feature is recorded.

### Required Files

- `feature_list.json`
- `claude-progress.md`
- `init.sh`
- `session-handoff.md` — overwrite it at the end of every session
- `quality-document.md` — update after each significant session

### Completion Gate

A feature moves to `passing` only after its `verification_command` succeeds, the regression check
ran, and the evidence is recorded in `feature_list.json`.

Final acceptance of a phase of work is done with `evaluator-rubric.md`, filled in by a fresh session
or subagent using repository artifacts only — never by the session that implemented the work.

### Before You Stop

1. Update `claude-progress.md` (Current Verified State + a new session entry at the top of the log).
2. Update the feature states in `feature_list.json`.
3. Record what is still broken or unverified.
4. Update `quality-document.md` if grades changed.
5. Overwrite `session-handoff.md`.
6. Commit once the repository is safe to resume — on a branch, and a version bump takes the release
   gate at the top of this file with it.
7. Leave a clean restart path for the next session.
