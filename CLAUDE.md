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
