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
- **What is not in the jar.** Receiver deploys (`vps/ingest.py`, `vps/roomstats.py` to `/srv/sighte`,
  restart `sighte-ingest`), the `AGENT-PROMPT.md` re-paste out of `vps/SETUP.md`'s heredoc, env
  changes. A release whose server side is not deployed silently rejects every report it produces.
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
