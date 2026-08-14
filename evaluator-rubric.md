# Evaluator Rubric

Use this rubric after implementation and before final acceptance.

**Who fills this in:** a fresh session or subagent that did NOT implement the
work, using repository artifacts only (`feature_list.json`,
`claude-progress.md`, `session-handoff.md`, the code, and the test output).
Self-grading by the implementing session does not count. If the evaluator
cannot reconstruct the state from the artifacts alone, that is itself a
finding (score Handoff readiness 0).

**Scoring:** 0 = fails, 1 = partial / with reservations, 2 = fully met.
The evaluator must re-run the relevant `verification_command`s, not just
read the recorded evidence.

---

**Evaluated:** `residue-001` — "Settle the unattributed points instead of clamping one side of them".
**Branch:** `residue-001` at `0724972`, off `main` at `43b425f`. Commits `2f742cd` (fix + tests),
`0724972` (artifacts). Not pushed, not merged.
**Evaluated on:** 2026-08-14, by a session that did not implement the work.

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `./gradlew test --tests 'sighteaddons.RunReportTest' --tests 'sighteaddons.RoomHistoryTest' --tests 'sighteaddons.DungeonGridTest' --rerun-tasks` | `BUILD SUCCESSFUL in 9s`, `7 actionable tasks: 7 executed` |
| `bash init.sh` | `==> BASELINE: PASSING`, `BUILD SUCCESSFUL in 4s` |
| `./gradlew assemble check` | `BUILD SUCCESSFUL in 1s`; `git status --short` empty afterwards, `dist/` untouched |
| JUnit XML sum over `build/test-results/test/TEST-*.xml` | `classes 11, tests 84, failures 0, errors 0, skipped 0` (`RunReportTest` 20, was 15) |
| `@Test` count at `43b425f` (pre-change baseline) | `79` across the same 11 files — so `+5`, and no other test file appears in `git diff 43b425f..HEAD -- src/test/` |
| Independent replay of `DungeonGrid.splitPoints` in IEEE-754 (outside the JVM, outside the repo's tests) | `drift(16) == 3.552713678800501e-15` **exactly the live value**; first negative at `n=33`; sign pattern over 1..48 is `+` in 6–7 and 14–28, `-` from 33 up |
| `sha256sum dist/sighteaddons-0.9.0.jar build/libs/sighteaddons-0.9.0.jar` | `e043d1d2…` vs `8320dcae…` — **different jars** |

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | The residue is stopped at both sinks. `RunReport.build` (the seam `write` goes through, and the seam any direct caller goes through) now applies `ContributionTracker.settle`, replacing a `coerceAtLeast(0.0)` that by construction could only ever catch the negative half. **The not-one-signed claim is independently confirmed**: replaying `splitPoints(A/B/C=100 ticks, 1.0, MIN_TICKS)` accumulated over *n* rooms gives `+3.552713678800501e-15` at 16 rooms — bit-for-bit the value the live report carried — and turns negative at 33 and stays negative. The clamp was not merely incomplete, it was on the wrong side for a whole floor-length range. **The two-decimal choice is defensible and, on inspection of `award()`, loses nothing at all**: every cleared room contributes either a split summing to exactly `POINTS_PER_ROOM` (`splitPoints` normalises by the eligible tick total, and `award` falls back to `minTicks = 1` when nobody clears the bar) or nothing whatever, so `roomsCleared - credited.values.sum()` is *integral by construction* and its only sub-`0.01` content is float noise. The brief's premise that "`splitPoints` produces thirds" therefore does not reach this field — the thirds are credited to players; they never survive into the remainder. 2 dp is also what every display path shows and all the receiver asks of it (`agent/AGENT-PROMPT.md:62` uses it only as "large relative to `roomsCleared`"). **No schema pairing, verified against the receiver directly, not from the claim**: `unattributed` is in `RUN_KEYS` (`ingest.py:138`) and validated `_real(x, 0, MAX_CLEARED)` (`ingest.py:226`), whose docstring calls it "the one field that is legitimately fractional". No field added, removed or retyped; `SCHEMA` is still `4`. One blemish, scored under Maintainability rather than here because it cannot change output for any state this code can reach: the `RoomHistory` chat guard moved from `raw > 0.01` to `round2(raw) > 0.0`, i.e. effectively `raw >= 0.005`. |
| Verification | Did the required checks actually run, with evidence? | 2 | All three recorded commands re-run and reproduced exactly, including the XML totals (11/84/0/0/0). No test was deleted, skipped or loosened: the pre-change tree had 79 `@Test`s, `RunReportTest` went 15 → 20, and no other test file is in the diff — the two pre-existing assertions pinning `unattributed` at `1.25` still pass unmodified, because `settle(1.25) == 1.25`. Evidence entries carry command, output excerpt and commit as `CLAUDE.md` requires. Unverified paths are *named*, not implied: `session-handoff.md` and `claude-progress.md` both state that nothing here can play a floor, so the end-to-end path was never observed. **The `./gradlew build` substitution is correct and I verified it rather than taking it**: `build.gradle:73` is `tasks.named('build') { finalizedBy tasks.named('copyToDist') }`, and `copyToDist` `dependsOn cleanDist`, which deletes `dist/sighteaddons*.jar` before copying `build/libs/`. The branch's `build/libs/sighteaddons-0.9.0.jar` hashes `8320dcae…` against the committed `e043d1d2…` — so `./gradlew build`, which is what `init.sh` prints as the full verification command, would have silently replaced the released 0.9.0 artifact with a *different build wearing the same version number*, and then `CLAUDE.md`'s release gate #2 would read that as "the committed jar was stale". `assemble check` is task-for-task the same coverage (`build` = `assemble` + `check`) minus the finalizer. This belongs in the record and is already in `session-handoff.md` → "Do Not Touch". |
| Regression | Are previously passing features still passing? | 2 | Full suite green at 84/84 from a clean `bash init.sh`, and again from `--rerun-tasks` on the three named classes. No feature's `verification_command` regressed; `ingame-001` stays `blocked` for the same unchanged reason. The `RoomHistory` guard change is the only behavioural delta outside the report and it is unreachable in practice (see Maintainability). |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | `git diff --name-only 43b425f..HEAD` is exactly four source/test files and four artifact files. `gradle.properties`, `dist/`, `build.gradle`, `init.sh`, `CLAUDE.md`, `rooms.json` and `src/main/resources/` do not appear. `clear-001`'s territory is untouched: `enteredAtTick` (`ContributionTracker.kt:405`) and its `ponytail:` note are byte-identical, `MIN_TICKS` is unchanged and appears in the diff only as a constant *read* by a new test, `SCHEMA = 4` is unchanged. `mod_version=0.9.0`, `dist/` holds the one committed jar dated before this session, `git status` clean — **the release gate did not fire and could not have**. `feature_list.json` gained `residue-001` at priority 1 and renumbered the six pre-existing entries 2–7; nothing else about them (status, evidence, notes) changed, and the addition is disclosed in both `claude-progress.md` and `session-handoff.md`. That is the "discover new required work → record it as a new feature" path `CLAUDE.md` prescribes, not a rewrite hiding unfinished work. |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed cold from the artifacts on a different session: baseline green first try, no repair step, no manual environment fixing beyond what `session-handoff.md` already documents (`JAVA_HOME`, warm cache, no working `python` from Bash). `settle` is pure and idempotent — `build` settles a value the call site already settled via `unattributed()`, which is harmless. Working tree clean, both commits present, nothing staged or stashed. |
| Maintainability | Is the code and documentation clear enough for the next session? | 1 | The `settle` KDoc is genuinely good — it names the live value, the mechanism, why a non-zero value must survive, and why `Math.round` over `roundToLong`. Two deductions, both in documentation rather than code. **(a) The comment justifying the `RoomHistory` change is false.** It says the figure "comes back already rounded … and the chat can no longer show a `0.00` row". Under the old `> 0.01` guard a `0.00` row was *already* impossible — anything passing that guard formats to at least `0.01`. The actual effect is the opposite direction: the guard loosened from `raw > 0.01` to `raw >= 0.005`, so raw values in `[0.005, 0.01]` now print a `0.01 rooms unattributed` line where the release build stays silent. That window is unreachable given `award()`'s all-or-nothing crediting, so no player will ever see a difference — but a user-visible guard was moved and the comment explaining it describes a benefit that did not exist, which is precisely how the next session gets misled. **(b) `settle`'s rationale argues rounding against *the clamp* ("only ever caught the negative half"), not against the symmetric `abs < 0.01` threshold the alternative actually proposed** — that threshold would also have removed both signs. The real reason rounding wins (it is the same operation every display already performs, so the permanent stored number equals the shown number) is stated, but as a secondary point rather than as the argument. Minor: `settle` is a pure numeric helper living on `ContributionTracker` while `RunReport.build` is its other caller. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | Reconstructed the entire state — branch, commits, exact commands, environment quirks, why `assemble check` and not `build`, what is unverified and why — from `session-handoff.md`, `claude-progress.md` and `feature_list.json` without needing the implementer's account. The `clear-001` handoff is specific enough to start on (needs `ContributionTrackerTest`, which still does not exist, and needs the receiver's `schema-001` deployed first). `quality-document.md` updated, scoring correctly left at C. |

**Total: 13 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **ACCEPT** — 13/14, no category 0, Correctness / Verification / Regression all 2.

Why the one deduction did not land on Correctness: the `RoomHistory` guard genuinely moved, and a
silent change to a user-visible chat line is normally a Correctness finding. It is scored under
Maintainability because `award()` credits each cleared room either a full `POINTS_PER_ROOM` or
nothing at all, so `unattributed` is integral to within ~1e-14 and the `[0.005, 0.01]` window in
which old and new differ cannot be produced by any run. The defect is in the comment that explains
the change, not in what the change does.

## Required Follow-Up

- Missing evidence:
  - **The end-to-end path is unobserved and correctly said to be**: no command here can play a floor,
    so `write` → `unattributed()` → the receiver accepting the result has never run. Named in
    `session-handoff.md`; not a deduction.
  - **`ContributionTracker.unattributed()`'s own subtraction has no test.** Honestly stated in three
    artifacts. The stated reason ("`roomsCleared` and `credited` are private") is loose about
    `roomsCleared`, which is `var … private set` — public to read, not to write — but the operative
    fact holds: no public seam fills that state, `award()` is private, and the only path into
    `credited` needs a live `Minecraft` tick. Closing it would have meant widening visibility for a
    test, on a one-line expression whose two halves are each covered. Acceptable as left.
- Required fixes (none blocking; for whoever next touches these files):
  1. Correct the comment at `RoomHistory.kt:258-261`. Either restore the `> 0.01` guard on the
     settled value — which is the behaviour-preserving option — or keep `> 0.0` and say what it
     actually does: the print threshold moved from `0.01` to `0.005`, deliberately, and is
     unreachable because credited points are whole-room.
  2. Optional, in `ContributionTracker.settle`'s KDoc: state the real reason rounding beats a
     symmetric threshold (stored number == shown number) rather than arguing against the clamp,
     which is not the alternative that was on the table.
- Worth keeping in the record (verified here, useful to every future session): **`./gradlew build` is
  not a neutral verification command in this repository while an unreleased fix is on the branch.**
  `build` is `finalizedBy copyToDist`, `copyToDist dependsOn cleanDist`, and the branch's jar hashes
  differently from the committed one — so `build` overwrites `dist/sighteaddons-0.9.0.jar` with a jar
  that is no longer the released 0.9.0. Use `./gradlew assemble check`, which is the same task
  coverage without the finalizer. `init.sh` still prints `./gradlew build` as the full verification
  command; that line and this constraint disagree, and a session reading only `init.sh` will trip it.
- Next review trigger: the next feature to reach `passing` — `clear-001`, which must not start until
  the receiver's `schema-001` is deployed. That one *is* a schema change, so its review has to check
  the receiver-first ordering, not just the tests.
