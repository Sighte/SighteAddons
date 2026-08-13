# Progress Log

## Current Verified State

This is the only section that gets edited in place. Keep it accurate — it is the first thing every
new session reads.

- Repository root: the directory holding `build.gradle` and `gradlew` (clone of `Sighte/SighteAddons`)
- Standard startup path: `./gradlew runClient` — Loom's dev client, which has no valid session and
  cannot reach Hypixel
- Standard verification path: `./init.sh` → `./gradlew test`; full is `./gradlew build`
- Baseline status (last `./init.sh` run): **PASSING** — 79 tests in 11 classes, 0 failures,
  0 skipped, `BUILD SUCCESSFUL in 25s`, 2026-08-13, at the commit that added this harness
  (branch `devloop-harness`, off `main`), `mod_version=0.9.0`
- Current highest-priority unfinished feature: `clear-001` — anchor `enterTick` on a minimum stay.
  It is paired with the receiver's `schema-001` and **the receiver ships first**.
- Current blocker: none for `clear-001`. `ingame-001` is blocked on a human playing a real floor,
  which no command here can produce.

## Session Log

Rules: insert the newest session at the TOP of this section. Never edit or delete past session
entries — they are the audit trail. Copy the template below for each new session.

### Session 001

- Date: 2026-08-13
- Goal: Instantiate the DevLoop harness in this repository.
- Completed: `init.sh`, `feature_list.json`, `claude-progress.md`, `session-handoff.md`,
  `quality-document.md`, `evaluator-rubric.md`, an Operating Loop section appended to `CLAUDE.md`,
  and a `*.sh text eol=lf` line in `.gitattributes`. No source file, no resource and no build script
  was changed — `mod_version` is untouched at 0.9.0, so the release gate at the top of `CLAUDE.md`
  does not fire.
- Verification run (exact commands):
  - `bash init.sh`
- Evidence captured: `BUILD SUCCESSFUL in 25s`, `BASELINE: PASSING`; the JUnit XML under
  `build/test-results/test/` sums to `tests=79 skipped=0 failures=0 errors=0`.
- Commits: the harness commit on branch `devloop-harness`.
- Files or artifacts updated: the six new files above plus the two edited ones.
- Regressions found: none — the suite was green before and after, and nothing it covers was edited.
- Known risk or unresolved issue: the seeded features are read out of this repository's own README
  ("Not implemented yet", "Known limits") and its `ponytail:` notes. They have never been reviewed
  by the user — treat the list as a starting point, not as an agreed backlog. Separately, everything
  `ingame-001` names is still unverified in a real dungeon, and that has not changed.
- Next best step: `clear-001`, but only after the receiver's `schema-001` is deployed.

<!-- SESSION TEMPLATE — copy, do not fill in here
### Session NNN

- Date:
- Goal:
- Completed:
- Verification run (exact commands):
- Evidence captured:
- Commits:
- Files or artifacts updated:
- Regressions found:
- Known risk or unresolved issue:
- Next best step:
-->
