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

**Evaluated:** `clearpoints-001` — "ClearPoints proper — weight rooms instead of counting them".
**Branch:** `clearpoints-001` at `72e0825`, off `main` at `1e27b42`. Six commits: `13c9fb5` (code),
`0b6373f` (artifacts), `ddfddc0` (gitignore repair), `073e125` (the previous evaluation), `390399b`
(session 006 code — the hash session 006's evidence is recorded against), `72e0825` (session 006
artifacts). Not pushed, not merged.
**Evaluated on:** 2026-08-14, by a session that neither implemented the work nor wrote the previous
pass. This is a **re-grade** after the previous pass returned Revise 12/14.

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `bash init.sh` | `BUILD SUCCESSFUL`, `==> BASELINE: PASSING`. Its "Full verification command" prints `./gradlew assemble check` with the `copyToDist`/`cleanDist` reasoning inline |
| `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RoomDatabaseTest' --rerun-tasks` (the feature's own `verification_command`) | `BUILD SUCCESSFUL in 6s`, `7 actionable tasks: 7 executed`; XML `ContributionTrackerTest tests=32 skipped=0 failures=0 errors=0`, `RoomDatabaseTest tests=6 skipped=0 failures=0 errors=0`. **29 → 32 and 6 confirmed** |
| `./gradlew test --rerun-tasks`, summing `build/test-results/test/TEST-*.xml` | `BUILD SUCCESSFUL in 6s`; `classes 12 tests 119 skipped 0 failures 0 errors 0`. **116 → 119 confirmed** |
| `@Test` count per file at `1e27b42` vs `HEAD`; `git diff 1e27b42..HEAD -- src/test/ \| grep -c '^-[^-]'` | `101` across 12 files → `119` across 12. `ContributionTrackerTest` 16 → 32, `RoomDatabaseTest` 4 → 6, **every other file byte-identical**. Deletion-line count: **`0`**. Strictly additive over the whole branch — nothing deleted, loosened or skipped |
| `./gradlew assemble check`; `md5sum dist/sighteaddons-0.9.0.jar` before and after | `BUILD SUCCESSFUL`; md5 `b2ebc35ccfeb9cc96134eb3b18f0306f` **identical both sides**; `git status --short dist/ gradle.properties` empty; `mod_version=0.9.0` |
| `grep -n 'const val SCHEMA' RunReport.kt`; `git diff --name-only 1e27b42..HEAD -- src/main/` | `66: private const val SCHEMA = 5`. The `src/main` diff lists **only** `ContributionTracker.kt` and `RoomHistory.kt` — `RunReport.kt` is untouched by this branch |
| **Mutation 4 rebuilt from scratch** — `+ room.secretsFound * SECRET_POINTS` added to `weightOf`, then `./gradlew test --rerun-tasks` | `BUILD FAILED`, **`119 tests completed, 3 failed`**: `the live secret counter is not what a room is worth`, `the credit is the whole room even though the checkmark lands mid-collection`, `a room is worth the same however far the run has got`. **The claim reproduces exactly** — this edit passed all 116 at `13c9fb5`. Restored from a pre-edit copy; `git status --short` empty |
| **Mutation 1 re-run at the new head** — `unattributed()` back to `settle(roomsCleared - credited.values.sum())` | `BUILD FAILED`, `119 tests completed, 2 failed`: `weighting cannot silence the unattributed count`, `a pre-cleared room is not an unattributed one`. The load-bearing guard still holds at `72e0825`. Reverted; tree clean |
| **My own probe, step A** — a throwaway `EvalFloorProbeTest` setting `DungeonSession.floor` by reflection (`getDeclaredField("floor")`, `isAccessible = true`) | `BUILD SUCCESSFUL`. `DungeonSession.floor` reads back `"F7"` and `floorNumber` reads back `7`, **not null**. The private setter is reachable from a test in this repository |
| **My own probe, step B** — a real floor multiplier added to `weightOf` (`val floorFactor = 1.0 + (DungeonSession.floorNumber ?: 0) * 0.1`), full suite plus the probe | `BUILD FAILED`, `121 tests completed, **1 failed**` — and the one failure is **my probe**, `a room is worth the same on every floor`. **All 119 existing tests passed under a live floor multiplier**, including `a room is worth the same however far the run has got`. Probe deleted, source restored, `git status --short` empty |
| `git grep -n 'gradlew build'`; `sed -n '1,20p' claude-progress.md` | Line 11 now reads ``full is **`./gradlew assemble check`**`` / ``**Not `./gradlew build`**`` with the `copyToDist`/`cleanDist` reasoning inline. Remaining hits: `CLAUDE.md:16` (the release gate, where refreshing the jar is the point), `README.md:468` (contributor Build section), `claude-progress.md:86,186` (session-006 and session-004 history), `init.sh:68`, `session-handoff.md` and `feature_list.json` (all warnings *against* it), `quality-document.md:57` (describes the defect) |
| `git diff --stat 073e125..390399b -- src/main/`, filtered to non-comment lines | `1 file changed, 10 insertions(+)`; **zero** non-comment changed lines. Session 006's only `src/main` change really is a comment block. `390399b..HEAD` touches no source or test file at all |
| Feature statuses at `1e27b42` vs `HEAD` | Only `clearpoints-001` moved (`not_started` → `passing`); `runend-001` was **added** as discovered work per `CLAUDE.md`. Nothing downgraded, nothing removed |

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | **The behaviour is right, and I re-established the load-bearing half independently rather than inheriting it.** Mutation 1 at `72e0825` — `unattributed()` back to `settle(roomsCleared - credited.values.sum())` — fails exactly `weighting cannot silence the unattributed count` and `a pre-cleared room is not an unattributed one`. The silent-forever-zero this feature exists to prevent is genuinely guarded at the current head, not merely at the head the original evidence was taken against. `unattributed()` is still `unattributedRooms.toDouble()`, still incremented at the single `award()` site behind the raw-presence fallback, so a brief visitor is attributed and only a room nobody was ever seen in counts. **None of the harness's Correctness-0 conditions are met, and I checked rather than assumed:** `git diff --name-only 1e27b42..HEAD -- src/main/` lists only `ContributionTracker.kt` and `RoomHistory.kt`, so `RunReport.kt` is not in this branch at all; `SCHEMA` is `5` at `RunReport.kt:66`; no field is added, removed or retyped; the per-player breakdown never reaches the wire (`RUN_KEYS`/`ROOM_KEYS` carry no points field), and `unattributed` keeps its `_real(x, 0, MAX_CLEARED)` validation. **No schema change landed ahead of a receiver change, because no schema change landed.** Session 006 changed no behaviour whatsoever: its only `src/main` diff is 10 comment lines, zero non-comment changes, which I verified by filtering the diff. The three new tests are additions to an already-correct implementation. |
| Verification | Did the required checks actually run, with evidence? | 1 | **Every recorded number reproduced exactly**: `32`/`6`, `classes 12 tests 119 skipped 0 failures 0 errors 0`, jar md5 unchanged, `mod_version=0.9.0`, `SCHEMA 5`, `BASELINE: PASSING`. **Mutation 4, rebuilt from scratch, fails exactly 3** — the three named cases — where it passed all 116 at `13c9fb5`. That is a real closure of the previous pass's deduction. **Nothing was deleted or loosened**: zero deletion lines in `src/test/` across the entire branch, no file lost a case. **The three new cases pin reasons, not numbers.** I read them rather than counting them: `the live secret counter is not what a room is worth` asserts in both directions (8 found against a database 0 must equal a plain room; a database 8 must be strictly more), which kills the additive *and* the substituting edit; and `the credit is the whole room even though the checkmark lands mid-collection` genuinely drives `onCleared` with `secretsFound = 2` against `info.secrets = 5` and asserts the credited points equal the whole-room weight — so it really does pin that `award()` fires on the checkmark while the party is still collecting, which is the race the exclusion exists for. **The deduction is that the session's central argument for not writing a floor test is false, and I disproved it by construction.** The claim, recorded in six places and in the source KDoc, is that `DungeonSession.floor` being `private set` behind `inDungeon(Minecraft)` means "a floor factor reads null under test" and "a test claiming to catch it would be a guard in name only" that "would pass either way". I wrote the test. `DungeonSession::class.java.getDeclaredField("floor")` with `isAccessible = true` sets it; `floorNumber` reads back `7`, not null. The resulting invariance test **passes clean and fails under a real floor multiplier** — it discriminates, which is precisely what "a guard in name only" denies. Worse for the session's position: under that same live floor multiplier **all 119 existing tests passed**, my probe alone caught it. So `feature_list.json:194`'s "the closest any test in this repository can get to the floor exclusion" overstates `a room is worth the same however far the run has got` — on the actual exclusion it gets no distance at all. The session had the mutation technique in hand and ran it three times this session; the one probe it did not run is the one that would have tested its own excuse. The gap itself is declared loudly and honestly everywhere, which is why this is 1 and not 0 — but a declared gap defended by a false impossibility claim is not the same as a declared gap. |
| Regression | Are previously passing features still passing? | 2 | Full suite green from a forced cold run: `./gradlew test --rerun-tasks` → `BUILD SUCCESSFUL`, 12 classes, 119 tests, 0 failures, 0 errors, 0 skipped, run three separate times across this evaluation and green every time. `./gradlew assemble check` → `BUILD SUCCESSFUL`, `dist/` and `gradle.properties` clean, jar md5 unchanged. `bash init.sh` → `BASELINE: PASSING`. `residue-001`'s own `verification_command` (`RunReportTest`, 21 cases) and `clear-001`'s (`ContributionTrackerTest` + `RunReportTest`) are inside that run and green; `RunReportTest.kt` is not modified by this branch at all. No status was moved to hide anything — diffing `feature_list.json` at `1e27b42` against `HEAD`, the only change is `clearpoints-001` `not_started` → `passing` plus the addition of `runend-001` as discovered work. `residue-001` and `clear-001` stay `passing`, `records-001` and `ingame-001` stay `blocked` for their unchanged documented reasons, `chat-001` and `party-001` stay `not_started`. Session 006 changed no behaviour, so there was nothing here that could regress. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | Session 006 (`390399b` + `72e0825`) touches one source file by 10 comment lines, one test file, and the artifacts. `390399b..HEAD` touches no source or test at all. Over the whole branch the diff is eleven files: two sources, two test files, `README.md` (a new ClearPoints section — its Build section is **not** in the diff, `grep -c 'gradlew build'` over the README diff returns `0`), `.gitignore`, and five artifacts. `gradle.properties`, `dist/`, `build.gradle`, `init.sh`, `CLAUDE.md`, `rooms.json`, `src/main/resources/` and `RunReport.kt` do not appear anywhere. `mod_version=0.9.0` and the committed jar is byte-identical by md5 — **the release gate did not fire and could not have**. Two invitations to overstep were declined correctly: `evaluator-rubric.md` was **not** restructured to append-only despite the previous pass arguing for it, because it is a harness file and `CLAUDE.md` reserves that for the user — the session said so explicitly and routed the findings the prescribed way instead; and `README.md`'s contributor `./gradlew build` line was left alone rather than "fixed". |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed cold by a session that neither implemented it nor wrote the previous pass. Baseline green on the first attempt, no repair step, no environment fixing beyond what `session-handoff.md` documents — `python` resolved exactly as the handoff says (the harness `CLAUDE.md` one level up describes `python3` differently, and the handoff's warning to measure rather than trust either is correct). Three mutations applied and reverted (mutation 4, mutation 1, and my own floor multiplier) plus one probe test file created and deleted; `git status --short` came back empty after every one, and the final full run is green at `72e0825` with the jar md5 unchanged. `ContributionTracker` is reset in `@BeforeEach` and the new cases deliberately move only its own state, not `DungeonSession`'s, which the handoff explains and which my reflection probe confirms matters — I had to null the floor in a `finally` block for exactly the leak the handoff warns about. |
| Maintainability | Is the code and documentation clear enough for the next session? | 1 | **Both previously docked items are genuinely closed, verified not read.** `claude-progress.md:11` now reads ``full is `./gradlew assemble check``` / ``**Not `./gradlew build`**`` with the `copyToDist`/`cleanDist` reasoning inline and a pointer to `c4c0c56`. **I swept the repository myself and agree with the session's call on what it left.** The remaining `gradlew build` hits are: `CLAUDE.md:16`, inside the release gate where refreshing the jar *is* the point and which is a harness file the session may not edit; `claude-progress.md:86` and `:186`, both history entries recording the repair; `init.sh:68`, explaining that `build` belongs to the release gate; and `README.md:468`, the contributor Build section. Leaving the README is the right call and better than "fixing" it — for a contributor the `dist/` copy is the whole point, rewriting it to `assemble check` would be wrong, and every path a *session* actually reads (`init.sh`, `claude-progress.md`'s Current Verified State, `session-handoff.md`'s Do Not Touch) now says `assemble check` unambiguously. Naming it in "Do Not Touch" with "do not follow it mid-feature, and do not 'fix' it either" is the correct disposition. The "each has a test" overclaim is gone and replaced with something accurate. The documentation is otherwise the strongest thing about this branch: the new test KDocs are unusually careful about what they do *not* pin. **The deduction is one new documentation defect of the same class as the one just fixed, plus a recurrence.** (1) `ContributionTracker.kt:401-409` states in the source itself that a floor guard "would be a guard in name only" and that "a real guard for the floor needs a seam on `DungeonSession`, which is a feature rather than a test" — repeated in `feature_list.json`, `session-handoff.md`, `claude-progress.md` (twice) and `quality-document.md` (twice). It is false, and its consequence is concrete and identical in shape to last pass's "each has a test": it converts roughly twenty lines of test into a phantom feature and will stop the next session from writing a guard that is writable. It is less dangerous than its predecessor — the artifacts correctly say no guard exists, so nobody is falsely reassured about coverage, only misdirected about the fix — which is why the category is 1 rather than lower. (2) `claude-progress.md:20` says the branch carries **four** commits and `session-handoff.md` says four "plus this session's artifact commit"; `git rev-list --count 1e27b42..HEAD` says **six**. The previous pass flagged this exact error as cosmetic finding 3 when the artifacts said "one" and the answer was three. It has now been recorded by hand and been wrong three passes running. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | Reconstructed the entire state from the artifacts alone: branch and head, what each commit is for, every command, all three of session 006's mutation probes precisely enough to rebuild mutation 4 from scratch and hit the same failure count and the same three test names, the environment quirks, why `assemble check` and not `build`, and which paths are unverified and why. **The durable-findings migration the previous pass asked for actually happened, and I checked each one landed rather than taking the summary's word:** `residue-001`'s notes carry `settle`'s KDoc arguing against the old clamp instead of the symmetric threshold; `clear-001`'s notes carry all three of `stay.ticks` counting sightings rather than elapsed ticks, the gap tolerance calibrated against a documented 10–20 tick window with zero margin, and `anchorOnClear`'s unknown real-floor frequency; and `ingame-001`'s notes cross-reference the two that need a real run plus the weight constants. That is four findings moved from a file overwritten every pass onto features that carry a status and a priority — the path `CLAUDE.md` prescribes, and the right answer to a problem two reviewers had been solving by hand. The previous handoff's "one commit" slip is explicitly corrected in the text ("earlier entries saying 'one commit' were wrong"), even though the replacement number is also wrong. `session-handoff.md`'s "Next Best Step" asked for exactly this re-grade and named exactly the two things to re-run, which is what a handoff is for. |

**Total: 12 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **REVISE** — 12/14 and no category 0, but Verification scored 1, so the Accept bar is not
met. Not overridden.

To be clear about what changed since the previous pass and what did not. **Both docked items were
genuinely closed and I verified both by re-running rather than reading.** Mutation 4 fails 3 where it
passed 116; the three new cases pin reasons rather than numbers, and the mid-collection case really
does drive `award()` on the checkmark with secrets still in hand, which is the race the exclusion
exists for. `claude-progress.md:11` is corrected and the rest of the repository sweeps clean. The
findings migration into `feature_list.json` landed, all four of them. Correctness is unchanged and I
re-proved the load-bearing guard at the new head rather than inheriting it. Nothing here is a blocking
defect, nothing needs reverting, and no receiver change is owed.

What holds it at Revise is narrower than last time but is not a judgement call. The session was asked
for a floor test, declined, and justified the decline with a claim about this repository that is
false: that `private set` puts `DungeonSession.floor` beyond a test, so any floor guard "would pass
either way". I wrote the guard. It passes clean and fails under a floor multiplier, and **all 119
existing tests pass under that same multiplier** — so the exclusion is not merely unpinned, it is
unpinned while an artifact says it is unpinnable and the offered substitute is described as the
closest a test can get when it catches none of it.

This is not the same as "argued, not pinned", which would have been an acceptable outcome and which
the artifacts otherwise record with real consistency. Declaring a gap is honest. Declaring it
*impossible to close*, in the source KDoc, when closing it is twenty lines, is a claim that outlives
the session and prevents the fix. The session ran three mutation probes this cycle; it did not run
the one that would have checked its own excuse, and that is the whole of the deduction.

## Required Follow-Up

### Findings from this pass

1. **The floor-exclusion impossibility claim is false, and it is recorded in six places.** The claim
   is that `DungeonSession.floor` being `private set` behind `inDungeon(Minecraft)` makes a floor
   guard "a guard in name only" that "would pass either way". Disproved by construction:

   ```kotlin
   val f = DungeonSession::class.java.getDeclaredField("floor")
   f.isAccessible = true
   f.set(DungeonSession, "F7")     // DungeonSession.floorNumber then reads 7, not null
   ```

   A `weightOf` invariance test across `F1`/`F7`/`M7` built on that passed clean and **failed under a
   real floor multiplier**, while all 119 existing tests passed under the same multiplier. Fix the
   claim in all six places — `ContributionTracker.kt:401-409`, `ContributionTrackerTest.kt:381-387`,
   `feature_list.json` (`clearpoints-001` notes and the run-progress evidence entry),
   `session-handoff.md` "Broken Or Unverified", `claude-progress.md:29` and `:78-79`,
   `quality-document.md:28` and `:74`. Then either write the guard or, if reflection on a private
   Kotlin backing field is judged too fragile to live in this suite, **say that** — "possible but
   undesirable, and here is the seam we want instead" is a defensible position; "impossible" is not.
   Reflection has no precedent in this test suite, so that is a real argument, but it is a different
   argument from the one currently recorded.
2. **`feature_list.json:194` overstates the run-progress case.** It calls `a room is worth the same
   however far the run has got` "the closest any test in this repository can get to the floor
   exclusion". Measured: on a live floor multiplier it catches nothing. It is a good guard against a
   run-progress factor and should be described as only that.
3. **Cosmetic, non-blocking, and now recurring:** `claude-progress.md:20` says the branch carries four
   commits and `session-handoff.md` says four plus one; `git rev-list --count 1e27b42..HEAD` says six
   (`073e125`, the previous evaluation, and `72e0825` are both uncounted). The previous pass flagged
   the same error when the artifacts said "one" and the answer was three. Three passes, three wrong
   counts — the number is transcribed by hand each time. Consider deriving it or dropping it.

### Still open from the previous pass, re-checked against the working tree this pass

- ~~`session-handoff.md` claims all three exclusions have a test.~~ **Resolved.** The sentence is gone
  and replaced with an accurate one; secrets-from-the-database is now genuinely pinned in both
  directions, confirmed by rebuilding mutation 4 (fails 3).
- ~~`claude-progress.md:11` names `./gradlew build` as the full verification path.~~ **Resolved and
  swept.** Corrected with reasoning inline; the remaining repository hits are history, the release
  gate, or warnings against it. `README.md`'s contributor Build section was deliberately left and
  named in "Do Not Touch" — **I agree with that call.**
- **Floor exclusion unguarded** — carried forward and **escalated**: see finding 1. The previous pass
  offered "either accept it as a KDoc-only argument and say so, or make it assertable". The session
  chose the first; it is now established that the second was available, which changes the choice.

### Missing evidence, all named by the session, none a deduction

- **The weight constants are judgement, not measurement.** That they *separate* rooms is tested in
  both directions and against the real `rooms.json`. That 1.5 for a puzzle and 0.25 a secret separate
  rooms the way a player would agree with needs a real dungeon, and this repository cannot reach one.
  Recorded on `clearpoints-001` and cross-referenced from `ingame-001`.
- **`tick()`'s wiring to `onCleared` and `onPresence` is read, not asserted.** That seam needs a
  `Minecraft` and a `MapItemSavedData`; no test file imports `net.minecraft`. `onCleared` itself is
  covered. Verified as a genuine limit, unlike the floor claim — there is no reflection trick here,
  because the missing objects are constructor arguments and not private fields.
- **The cross-repo reading was not executed.** `agent/AGENT-PROMPT.md:62` was read and is exact and
  `roomstats.py` does not consume the field, but no receiver test was run from here. Nothing on this
  branch reaches the wire, so nothing is owed.
- **Nothing anywhere implies a real Hypixel run.** The dev client's inability to reach Hypixel is
  stated in `session-handoff.md`, `claude-progress.md` and `quality-document.md` alike, and every
  unverified path is named rather than left for the reader to assume. Checked, and correct.

### Findings now living in `feature_list.json` rather than here

The previous pass argued that a file overwritten every review cannot hold a finding, and asked for the
findings to be routed into `feature_list.json` where they carry a status and a priority. **That
happened, and I verified each one landed** rather than trusting the summary:

- `residue-001` notes — `settle`'s KDoc argues rounding against the old clamp rather than against the
  symmetric threshold that was the real alternative. Still open, optional.
- `clear-001` notes — (1) `stay.ticks` counts sightings, not elapsed ticks, and the gap test is
  per-gap rather than cumulative; (2) the gap tolerance is calibrated against a documented 10–20 tick
  blackout with zero margin, never measured; (3) `anchorOnClear`'s real-floor frequency is unknown.
- `ingame-001` notes — cross-references the two of those that need a real run, plus the weight
  constants.

Do not copy them back here. This section exists to record that the migration was checked, not to
re-home the findings. `evaluator-rubric.md` was correctly **not** restructured to append-only: it is a
harness file and `CLAUDE.md` reserves that change for the user.

### Next review trigger

`chat-001`, per the handoff. Check before starting whether anything it adds reaches `RunReport.kt`; if
it does, diff its fields against `RUN_KEYS` in the receiver's `ingest.py` first, and **the receiver
goes first**. `runend-001` is the cheaper alternative and still needs its open question — which of the
two `unattributed` numbers the receiver's analyst wants — decided before a line is written.

Finding 1 above is a documentation correction plus at most one test, and is smaller than either. If it
is taken, it does not need a fresh feature: it is closing this evaluation, the same way session 006
closed the last one.
