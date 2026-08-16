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

Short. A reader decides whether to install it; an operator learns what else to do. Four things:

- **What changed**, one line per merged PR, behaviour first.
- **Whether the receiver side is already deployed.** The report schema is compiled in, so a build
  whose receiver half is behind silently `400`s every report it produces. Name the commit it needs,
  or say it is already live.
- **`sha256`** of the attached jar, and the Minecraft/loader versions out of `gradle.properties`
  rather than remembered.
- **What is not verified** — the dev client cannot reach Hypixel, so name the paths nobody has run
  instead of leaving the reader to assume they were tested.

Do not backfill releases for versions that never had one — the tags would claim a review that never
happened.

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
- **The release title must be ≤ 64 characters**, because Modrinth's `version_title` is and the
  workflow sends the GitHub title verbatim. Measured on 2026-08-16: 0.14.0's title was exactly 64 and
  passed, 0.15.0's was 65 and the upload died with
  `Field version_title failed validation with error: length` — a 400 *after* the GitHub release was
  already published, which is precisely the split this section exists to prevent. The repair is
  `gh release edit <tag> --title` and then re-running the workflow; the tag, the jar and the notes
  are untouched by it. Count the title before publishing.
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

**Two people use this mod. The process is sized for that** — enough to know what is running and not
to break what already works, and no more. It was much heavier until 2026-08-16; see "How much process
this is worth" below for what was cut and what deliberately was not.

At the start of a session — read what is named, not the whole file:

1. Read the **"Current Verified State"** section of `claude-progress.md`.
2. Read the entry for your feature in `feature_list.json`. You do not need the others.
3. Run `./init.sh`. If the baseline is FAILING, repairing it is the task and nothing else starts.

`ENVIRONMENT.md` holds the toolchain, the quoting traps that make a probe silently do nothing, and
the code invariants a tidy-up would break. Read it when you are about to meet one of those, not as a
ritual. Do not rewrite it.

Then take one unfinished feature and work on that until it is verified or documented as blocked.

### Rules

- One active feature at a time.
- Do not claim something works without having run it.
- Do not rewrite the feature list to hide unfinished work, and do not weaken a test to make work look
  finished.
- **Tests pin behaviour that could plausibly break. They are not a score.** A handful of cases that
  would actually catch a mistake beats twenty that restate the implementation, and no feature needs a
  test count to justify itself. Mutation probes are a tool for logic that is genuinely easy to get
  wrong — reach for one when you doubt a test, not once per feature out of habit.
- If you discover new required work while implementing, do not fix it inline. Add it to
  `feature_list.json` as a new feature and stay on the active one.
- Do not modify harness files (`CLAUDE.md`, `init.sh`, the schema of `feature_list.json`) unless the
  user asks for it.

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

### Artifact Retention

**`git log` is the audit trail. These files carry current state.** Anything removed stays
recoverable with `git show <hash>:<file>`, so a prune commit names what it removed and the hash it
was last complete at.

| File | Keeps | Ceiling |
|---|---|---|
| `claude-progress.md` | Current Verified State; the **last session** of the log | ~150 lines |
| `feature_list.json` | For `passing`: **one** evidence line — the one that proves it | `notes` ≤ 300 chars |
| `session-handoff.md` | Changeable state only; standing facts live in `ENVIRONMENT.md` | 120 lines |

Open features (`not_started`, `blocked`) keep their `notes` in full — that is the next session's
brief, not history. Code invariants belong in `ENVIRONMENT.md` and in the KDoc at the site, where a
tidy-up will actually meet them.

**The handoff ceiling was 80 for two hours on 2026-08-16 and it was wrong.** Two sessions in a row
pruned honestly, reported the overage rather than hiding it, and both were right about the cause:
the file is long because open features carry standing briefs that this same section protects. 120 is
the honest number while `recordowner-001`, `scores-fetch-001` and `ownsecrets-001` are open. **The
way to shrink it is to close a feature, not to trim around one** — if you are over and every
remaining line belongs to open work, say so and stop, exactly as those sessions did.

`evaluator-rubric.md` and `quality-document.md` are no longer maintained artifacts. They stay in the
repository as the record of the passes that were run; nothing writes to them unless the user asks
for a grading pass.

**Current Verified State means the state, not a stack of corrections.** A session that finds a line
there wrong rewrites that line. Superseding it in a new paragraph is how that section reached 276
lines carrying five different values for `main`, four for `mod_version`, and one bullet that had
lost its opening sentence.

The test that matters: **an artifact too large to change with an ordinary string edit is too large.**
If you are writing a script to edit one of these files, the file has outgrown its ceiling. On
2026-08-16 this set was 448 KB and `build/` held ~110 KB of throwaway Python whose only purpose was
editing it.

### What Counts as Evidence

**One command, its result, and the commit it ran against.** One line in `feature_list.json`, not a
report. "I tested it manually" without a command anyone can re-run is not evidence. For anything that
only shows up in a real dungeon, the evidence is the debug session file and the line in it that
proves the claim.

**`--rerun-tasks` is not a default** — it forces recompilation and defeats the up-to-date check. Use
it when the point is that nothing was cached, and nowhere else, or it fossilizes into every later
copy of the command. It was in 39 recorded commands here before 2026-08-16.

### Completion Gate

A feature moves to `passing` when its own check succeeds and `./gradlew test` is still green. If
something that used to pass now fails, that is the next work, ahead of anything else.

That is the whole gate. **Grading is not routine.** `evaluator-rubric.md` and the `devloop-evaluator`
agent still exist, and a fresh agent — never the one that implemented — can still be asked for a
pass. Ask when something is genuinely dangerous or when the user wants it, not once per feature: a
pass costs a full agent run re-reading work the suite already covers, and it produced 11 passes for
8 features on the receiver and three on one mod feature whose behaviour did not change between the
last two.

### Before You Stop

1. Set the feature's status and its one evidence line in `feature_list.json`.
2. Bring `session-handoff.md` up to date — amend what changed, and say plainly what is still
   unverified. That last part is the one thing worth spending words on, because the dev client cannot
   reach Hypixel and a reader will otherwise assume a path was tested.
3. Commit on a branch. A version bump takes the release gate at the top of this file with it.

`claude-progress.md` gets a session entry only when something happened that the handoff does not
already say. Writing more does not make the handoff better; the next session pays for every line of
it before it can start.

## How much process this is worth

Cut on 2026-08-16, at the user's instruction, after a single feature run cost 224k tokens and the
suite had reached 265 tests for a mod with two users. What went: mandatory grading passes, the
mutation sweep per feature, the four-part evidence entry, the seven-step session close, and roughly
half of every artifact ceiling.

**What did not go, because none of it is ceremony:**

- **The receiver moves first for a schema change.** Not process — a `400` the client never retries,
  and the run is gone. The check is a two-file diff and costs seconds.
- **`./gradlew test` before calling something done.** It is ~1.1 s of actual execution and it is what
  catches breakage; the grading passes never did.
- **Never pushing `main` on your own**, because a version bump pulls the release gate and a release
  reaches other people's game.
- **Saying what is unverified.** The cheapest line in the file and the only defence against a reader
  assuming the dev client tested something it cannot reach.
