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
**Branch:** `clearpoints-001` at `96e3a3a`, off `main` at `1e27b42`. Not pushed, not merged. For the
commit count run `git rev-list --count 1e27b42..HEAD` — it reads **9**, and it is deliberately not
transcribed into any artifact any more (see Maintainability).
**Evaluated on:** 2026-08-14, by a session that implemented none of this work and wrote neither
previous pass. This is the **third** grading of this feature, after Revise 12/14 twice.

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `bash init.sh` | `openjdk 25.0.4 OK`, `BUILD SUCCESSFUL`, `==> BASELINE: PASSING`. Its "Full verification command" block prints `./gradlew assemble check` with the `copyToDist`/`cleanDist` reasoning inline |
| `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RoomDatabaseTest' --rerun-tasks` (the feature's own `verification_command`) | `BUILD SUCCESSFUL in 6s`, `7 actionable tasks: 7 executed`; XML `ContributionTrackerTest tests=33 skipped=0 failures=0 errors=0`, `RoomDatabaseTest tests=6 skipped=0 failures=0 errors=0`. **32 → 33 and 6 confirmed** |
| `./gradlew test --rerun-tasks`, summing `build/test-results/test/TEST-*.xml` | `BUILD SUCCESSFUL in 6s`; `classes 12 tests 120 skipped 0 failures 0 errors 0`. **119 → 120 confirmed.** Per-file `@Test` counts at `HEAD` sum to exactly 120 across the 12 files |
| `git diff 1e27b42..HEAD -- src/test/ \| grep -c '^-[^-]'` | **`0`** deletion lines across the entire branch. Only `ContributionTrackerTest.kt` and `RoomDatabaseTest.kt` are touched; every other test file is byte-identical to `main` |
| `git diff 76fad14..HEAD -- src/test/`, deleted lines inspected individually | Six deleted lines, **all six are KDoc prose** carrying the retracted claim. `grep -c '@Test\|assert'` over the deleted side: **`0`**. Nothing was removed, skipped or loosened |
| `./gradlew assemble check`; `md5sum dist/sighteaddons-0.9.0.jar` before and after | `BUILD SUCCESSFUL`; md5 `b2ebc35ccfeb9cc96134eb3b18f0306f` **identical both sides**; `git status --short dist/ gradle.properties` empty; `mod_version=0.9.0` |
| `grep -n 'const val SCHEMA' RunReport.kt`; `git diff --name-only 1e27b42..HEAD` | `66: private const val SCHEMA = 5`. The branch diff is eleven files and **`RunReport.kt` is not among them** — `grep -c RunReport` over the diff returns `0` |
| **Runtime immutability**, `git diff 72e0825..HEAD -- src/main/` filtered to non-comment lines | 25 changed lines, of which non-comment: **`0`**. Every one is inside `weightOf`'s KDoc. The debug build in the user's game is from `72e0825`, and the code it describes is unchanged. The claim holds exactly |
| **Probe A, rebuilt from scratch** — `val floorFactor = 1.0 + (DungeonSession.floorNumber ?: 0) * 0.1`, `BASE_POINTS` made its coefficient; `./gradlew test --rerun-tasks` | `BUILD FAILED`, **`120 tests completed, 1 failed`**, and the one failure is `a room is worth the same on every floor`: `a room got heavier on a deeper floor ==> expected: <3.85> but was: <4.45>`. Matches the recorded numbers exactly |
| **Probe B, rebuilt from scratch** — `val master = if (DungeonSession.floor?.startsWith("M") == true) 1.0 else 0.0`; full suite | `BUILD FAILED`, **`120 tests completed, 1 failed`**: `a room got heavier in master mode ==> expected: <3.75> but was: <4.75>`. The **F1-vs-F7 assertion passed first** and the failure landed on the M7 leg — so the floor-*string* comparison is genuinely load-bearing and is not redundant with `floorNumber` |
| **Probe C, the important one** — dropped `field.set(DungeonSession, value)` from the test's own `setFloor`, leaving `getDeclaredField`/`isAccessible`; `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --rerun-tasks` | `BUILD FAILED`, `33 tests completed, 1 failed`: **`the floor was not actually set — this test would pass either way ==> expected: <1> but was: <null>`**. The guard against the exact degradation session 006 feared fires, by name |
| **Both multiplier forms against the pre-guard tree** — `git checkout 76fad14 -- ContributionTracker.kt ContributionTrackerTest.kt` (32 cases in that file, `grep -c 'on every floor'` = **`0`**, the guard absent), then probe A, then probe B | Probe A: `BUILD SUCCESSFUL`, `classes 12 tests 119 failures 0`. Probe B: `BUILD SUCCESSFUL`, `classes 12 tests 119 failures 0`. **Both live floor multipliers passed all 119 tests.** The exclusion really was guarded by nothing before `0795236` |
| **Mutation 1 re-run at the current head** — `unattributed()` back to `settle(roomsCleared - credited.values.sum())` | `BUILD FAILED`, `120 tests completed, 2 failed`: `weighting cannot silence the unattributed count`, `a pre-cleared room is not an unattributed one`. The silent-forever-zero is still guarded at `96e3a3a` |
| Retraction sweep: `grep -rn "in name only\|pass either way\|closest any test\|impossible"` over `*.kt`, `*.md`, `*.json` | Every surviving hit is a **retraction**, the test's own assertion message, or an append-only session-log entry. No living artifact still asserts the claim |
| Final state after five mutations and one tree-swap | `./gradlew test --rerun-tasks` → `classes 12 tests 120 skipped 0 failures 0 errors 0`; `bash init.sh` → `BASELINE: PASSING`; jar md5 unchanged; `git status --short` **empty**; `git diff --quiet` clean |

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | **None of the harness's Correctness-0 conditions is met, and I checked rather than inherited.** `RunReport.kt` does not appear in `git diff --name-only 1e27b42..HEAD` at all, `SCHEMA` is `5` at `RunReport.kt:66`, `mod_version=0.9.0`, and `RUN_KEYS`/`ROOM_KEYS` carry no per-player points field — no schema change landed, so none could have landed ahead of the receiver. I re-proved the load-bearing half at the current head rather than trusting `72e0825`: reverting `unattributed()` to the old subtraction fails exactly `weighting cannot silence the unattributed count` and `a pre-cleared room is not an unattributed one`. **The hard constraint the session worked under holds exactly.** A debug build from `72e0825` is in the user's game; `git diff 72e0825..HEAD -- src/main/` changes 25 lines and **zero** of them are non-comment. Nothing this session did can have altered what that build does, so the session file the user brings back still describes this code. **The real M7 run corroborates the design rather than contradicting it.** Every anchor stamped 19 ticks after its stay began is precisely `MIN_TICKS = 20` reached on the 20th sighting with a per-tick decoration stream — which is what `clear-001`'s note (1) already says the counter does, and the artifact even predicted that the per-tick stream is the normal case and the sighting/elapsed divergence the blackout case. The weights separating real rooms from **1.00** to **4.50** sit inside the designed range, with the low end landing exactly on `BASE_POINTS = 1.0`, which is what `every room is still worth at least the point it used to be` asserts. `anchorOnClear` firing once in ten rooms is the benign answer to `clear-001`'s open note (3) — the fallback is a genuine fallback, not the main path, so the anchor bought what it looks like it bought. |
| Verification | Did the required checks actually run, with evidence? | 2 | **The single cause of the previous two Revises is genuinely closed, and I established that by construction rather than by reading.** I rebuilt all three discrimination probes from scratch. Probe A (`floorNumber` multiplier) and probe B (master-mode bonus off the floor *string*) each fail exactly one test — the new case — with the recorded messages to the decimal (`3.85`/`4.45`, `3.75`/`4.75`). Probe B failing on the **M7 leg after the F7 leg passed** proves the three-floor shape is reasoned, not decorative: `floorNumber` reads 7 for both, so only the string comparison catches that edit. **Probe C is the one that decides this category, and it fires.** Neutering the test's own `setFloor` while leaving the reflection scaffolding in place fails with `the floor was not actually set — this test would pass either way`. That is the guard against the precise degradation session 006 feared and named, so the test cannot rot into the guard-in-name-only shape that was the whole basis of the impossibility claim. **And the counterfactual is confirmed:** at `76fad14`, with the guard absent (32 cases in that file, 119 total), *both* live floor multipliers passed **all 119 tests**. The exclusion was pinned by nothing while three artifacts explained why it could not be pinned; it is now pinned in both edit forms and the pinning is self-checking. **Nothing was deleted or loosened**: zero deletion lines in `src/test/` across the whole branch, and session 007's only test deletions are six lines of false KDoc prose replaced by accurate prose — zero `@Test`, zero assertions — which the progress log states outright rather than glossing. **Unverified paths are named, not implied.** `session-handoff.md`, `claude-progress.md` and `quality-document.md` each state that the dev client cannot reach Hypixel; the wiring of `tick()` to `onCleared`/`onPresence` is explicitly flagged as read-not-asserted **and distinguished from the floor claim** ("the missing objects are constructor arguments, not private fields") — a distinction I checked and agree with; and the weight constants are labelled judgement rather than measurement. No artifact implies a real run was performed as verification. |
| Regression | Are previously passing features still passing? | 2 | Full suite green from forced cold runs: `./gradlew test --rerun-tasks` → `BUILD SUCCESSFUL`, `classes 12 tests 120 skipped 0 failures 0 errors 0`, run three separate times across this evaluation and green every time. `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 `b2ebc35ccfeb9cc96134eb3b18f0306f` measured before and after and identical, `git status --short dist/ gradle.properties` empty. `bash init.sh` → `BASELINE: PASSING`. `residue-001`'s `verification_command` (`RunReportTest`, 21 cases) and `clear-001`'s are inside that run and green; neither file is modified by this branch. Diffing `feature_list.json` statuses, nothing was downgraded or removed to hide anything — `clearpoints-001` is the only status that moved on this branch and `runend-001` was *added* as discovered work per `CLAUDE.md`. Session 007 changed zero non-comment source lines, so there was nothing here that could regress, and I verified that structurally rather than assuming it. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | Session 007 (`0795236` + `96e3a3a`) touches six files: one source file by KDoc only, one test file, and four artifacts. No version bump, no `dist/` refresh, `rooms.json` untouched, `RunReport.kt` untouched, `build.gradle`/`init.sh`/`CLAUDE.md` untouched — the release gate did not fire and could not have. **Three invitations to overstep were declined correctly.** `evaluator-rubric.md` was again not restructured to append-only, because it is a harness file and `CLAUDE.md` reserves that for the user. `README.md`'s contributor `./gradlew build` line was again left alone rather than "fixed". And the session did not rewrite session 006's log entry to erase the claim it was retracting, which the next row treats in full. It closed an evaluation and did not start work. |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed cold by a session with no prior context. Baseline green on the first attempt, no repair step, no environment fixing beyond what `session-handoff.md` documents — `python` resolved exactly as the handoff says it would, and the handoff's warning that the harness `CLAUDE.md` one level up describes `python3` differently and that one should measure rather than trust either is correct and useful. Five source mutations applied and reverted, plus a two-file checkout of an older tree and back; `git status --short` came back empty after every one and `git diff --quiet` is clean at the end. The handoff's warning not to call `DungeonSession.reset()` for cleanup, and to null the floor in a `finally` instead because the suite runs sequentially in one JVM, is exactly right — the test does that, and it is why my probes left no cross-test contamination. |
| Maintainability | Is the code and documentation clear enough for the next session? | 2 | **Both previously docked items are closed, verified by re-running and sweeping rather than by reading the summary.** (1) The impossibility claim is retracted in all five living artifacts plus the test KDoc, and my own `grep` sweep for `in name only` / `pass either way` / `closest any test` / `impossible` finds no living artifact still asserting it. `feature_list.json:194`'s overstatement — that the run-progress case was "the closest any test in this repository can get to the floor exclusion" — is corrected *in place* in the evidence entry, with the correction labelled as measured rather than argued. (2) The commit count is not merely corrected, it is **removed**: `claude-progress.md` and `session-handoff.md` both now instruct the reader to run `git rev-list --count 1e27b42..HEAD`, and record that three consecutive reviews found a hand-transcribed number wrong. That is the right fix for a recurring transcription error — deriving it rather than getting it right a fourth time. **The reflection trade-off is stated honestly and the mitigation is real, not asserted.** The cost — a rename of `DungeonSession.floor` breaks the test at runtime rather than at compile time — is recorded in the test KDoc, `quality-document.md` and `clearpoints-001`'s notes, together with why the alternative (a production seam on `DungeonSession`) was declined: a shape `src/main` does not otherwise need, added purely for a test. That is a defensible position honestly argued, and it is the *different argument* the previous pass said would be acceptable in place of "impossible". Crucially the mitigation is not just claimed — probe C demonstrates it, and the KDoc points at the probe. The KDocs remain unusually careful about what they do **not** pin: the run-progress case now says outright that a floor multiplier passes it untouched and that the two guards are deliberately separate because they fail on different edits. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | I reconstructed the entire state from the artifacts alone: branch and head, what each commit is for, every command, all three probes precisely enough to rebuild them from scratch and hit the same failure counts and the same assertion messages to the decimal, the pre-guard baseline to check the counterfactual against, the environment quirks, why `assemble check` and never `build`, and which paths are unverified and why. **The durable-findings migration still holds** — I checked each one is still present in `feature_list.json` rather than trusting that it was: `residue-001`'s notes carry the `settle` KDoc finding; `clear-001`'s notes carry all three of the sightings-vs-elapsed-ticks wording, the zero-margin gap tolerance, and `anchorOnClear`'s unknown frequency; `ingame-001` cross-references the two that need a real run plus the weight constants. Nothing was lost in the two rewrites since. **And the handoff prepares a reader for the real run that has now happened.** It anticipates the session file explicitly, says which three findings it unblocks, and says to read it before starting `chat-001` — so the M7 data arrives into artifacts that predicted its shape rather than contradicting it. `session-handoff.md`'s "Next Best Step" asked for exactly this re-grade and named exactly the probes to re-run; all three were reproducible from that description alone. |

**Total: 14 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **ACCEPT** — 14/14, no category 0, and Correctness, Verification and Regression all 2. Not
overridden.

What was holding this feature at Revise for two passes is closed, and closed better than the minimum.
The previous pass's deduction was not that the floor exclusion was unguarded — an argued gap would
have been acceptable — but that it was unguarded *while an artifact declared it unguardable*, a claim
that outlives its session and prevents the fix. Session 007 did three things about that, and I
verified each independently: it wrote the guard; it checked the guard against its own degradation, so
the claim cannot quietly become true again; and it retracted the claim everywhere a living artifact
carried it.

**The `setFloor`-neutering probe is what earns the second Verification point.** Writing a passing
floor test would have closed the letter of the finding. Session 006's actual fear was subtler — that a
floor test here would be a guard in name only, silently passing whether or not the field was ever
written. Probe C answers that fear directly: strip the reflective write and the case fails with `the
floor was not actually set — this test would pass either way`. The test asserts its own setup before
it asserts its invariant, so the degradation session 006 predicted is itself now a failing test.

**The counterfactual is confirmed and it is not small.** At `76fad14`, both a `floorNumber` multiplier
and a master-mode bonus drawn from the floor string passed **all 119 tests**. The exclusion was pinned
by nothing.

**On the retraction's one deliberate exception, I think the session made the right call, not a dodge.**
Session 006's log entries in `claude-progress.md` and `quality-document.md` still contain the false
paragraph. `claude-progress.md` states in its own header that session entries are the audit trail and
must never be edited, and `CLAUDE.md` forbids rewriting artifacts to hide history. Editing them would
have destroyed the record of what was believed and when — which is precisely the evidence that makes
three passes of improvement legible, and the record a future session needs to understand *how* a
plausible-sounding impossibility claim propagated into six places. The line between the two categories
is drawn correctly: every artifact a session *acts on* says the true thing, and the log says what was
believed. The supersession is explicit and adjacent rather than left implicit — session 007's entry
names the superseded paragraph, sits one entry above it, lists which artifacts are the living ones,
and the "Current Verified State" section that `CLAUDE.md` makes every session read first leads with
the correction. That is the honest disposition, and it is stronger than a silent rewrite would have
been.

The real M7 run is corroboration, and I graded it as such. It is not yet recorded in the repository —
correctly, since it arrived after the session ended — and the handoff already tells the next session
to read it first. See follow-up 1.

## Required Follow-Up

Nothing here blocks acceptance. None of it is a defect in shipped behaviour, and no receiver change is
owed — nothing on this branch reaches the wire.

### New this pass

1. **Record the real M7 run — it answers two open findings and refines a third.** The data confirms
   rather than contradicts the artifacts, which is why it is a follow-up and not a deduction:
   - Every anchor stamped exactly **19 ticks** after its stay began — the 20th sighting at
     `MIN_TICKS = 20`. This *measures* `clear-001`'s open note (1), which says `stay.ticks` counts
     sightings rather than elapsed ticks and predicts the two coincide on a per-tick decoration
     stream. They do. The note can move from "reasoned" to "measured", and
     `TrackedRoom.onPresence`'s KDoc wording fix it asks for is still worth making.
   - **`anchorOnClear` fired once in ten rooms** — this answers `clear-001`'s open note (3) outright.
     The fallback is rare, so the anchor is mostly genuine and the feature bought what it looks like.
     Worth noting alongside it that `ContributionTracker.kt:143-144`'s KDoc estimates "the empty 1x1s,
     three of them in one M7 by the count in `award`". The measured rate is lower than that estimate;
     same order, benign direction, but the figure is now checkable and should be reconciled rather
     than left as a prediction.
   - **Weights separated real rooms from 1.00 to 4.50.** The low end is exactly `BASE_POINTS`, which
     is what `every room is still worth at least the point it used to be` pins. This is the first
     real-data evidence for `clearpoints-001`'s weight constants, still labelled judgement rather than
     measurement on both `clearpoints-001` and `ingame-001`. It does not fully close that note —
     whether the *spread* matches what a player would agree with is still a judgement — but it is the
     first measurement of it and belongs in the evidence.

   The run also unblocks `ingame-001`, which is `blocked` solely on a human playing a floor.

2. **The floor guard is the only reflection in the suite, and that is now a standing property of the
   repository rather than a finding.** Recorded honestly in three places with its cost and its
   mitigation, and the mitigation is demonstrated by probe C rather than asserted. Carried forward as
   context, not as work: if `DungeonSession` ever grows a seam for the mod's own reasons, move this
   test onto it — which is what the KDoc already says.

### Carried forward from the previous two passes

Both items the second pass docked are **closed**, verified by re-running rather than by reading:

- ~~The floor-exclusion impossibility claim is false and recorded in six places.~~ **Closed.** Guard
  written, mutation-checked in both edit forms, checked against its own degradation, and the claim
  retracted in every living artifact. The deliberate exception in the append-only session logs is
  correct and explicitly superseded.
- ~~`feature_list.json:194` overstates the run-progress case.~~ **Closed.** Corrected in place, in
  both the notes and the evidence entry, and the run-progress case's own KDoc now states that a floor
  multiplier passes it untouched.
- ~~The hand-transcribed commit count has been wrong three passes running.~~ **Closed, and fixed at
  the right level** — the number is derived now, not corrected. `git rev-list --count 1e27b42..HEAD`
  reads 9.
- ~~`session-handoff.md` claims all three exclusions have a test.~~ **Closed in session 006**, and the
  claim is now actually true rather than merely removed.
- ~~`claude-progress.md:11` names `./gradlew build` as the full verification path.~~ **Closed in
  session 006 and still clean.** `README.md`'s contributor Build section is deliberately left and
  named in "Do Not Touch" — I agree with that call for the third time.

### Still living in `feature_list.json`, not here

The migration the first pass asked for has now survived three rewrites of this file. **I verified each
is still present** rather than trusting that it was. Do not copy them back here — this section records
that the migration holds, and follow-up 1 above is what moves two of them forward.

- `residue-001` notes — `settle`'s KDoc argues rounding against the old clamp rather than against the
  symmetric threshold that was the real alternative. Still open, optional.
- `clear-001` notes — (1) `stay.ticks` counts sightings, not elapsed ticks: **now measured**, see
  follow-up 1; (2) the gap tolerance is calibrated against a documented 10–20 tick blackout with zero
  margin and is **still unmeasured** — the real run does not settle it, since it reports what happened
  and not what the worst case is; (3) `anchorOnClear`'s real-floor frequency: **now answered**, see
  follow-up 1.
- `ingame-001` notes — cross-references the two that need a real run, plus the weight constants.

### Remaining unverified paths, all named by the session, none a deduction

- **The weight constants are judgement, not measurement** — partially addressed by the real run, see
  follow-up 1.
- **`tick()`'s wiring to `onCleared`/`onPresence` is read, not asserted.** Verified as a genuine limit,
  and the artifacts draw the right distinction from the floor claim: the missing objects are
  constructor arguments needing a `Minecraft` and a `MapItemSavedData`, not private fields, so there is
  no reflection trick available here. `onCleared` itself is covered.
- **The cross-repo reading was not executed.** `agent/AGENT-PROMPT.md:62` was read; no receiver test
  was run from here. Nothing on this branch reaches the wire, so nothing is owed.
- **Nothing anywhere implies a real Hypixel run was performed as verification.** Checked across all
  three artifacts, and correct.

### Next review trigger

`chat-001`, per the handoff — but **read the real session file first**, which is what the handoff
already says and what follow-up 1 turns into recorded evidence. Check before starting whether anything
`chat-001` adds reaches `RunReport.kt`; if it does, diff its fields against `RUN_KEYS` in the
receiver's `ingest.py` first, and **the receiver goes first**. `runend-001` is the cheaper alternative
and still needs its open question — which of the two `unattributed` numbers the receiver's analyst
wants — decided before a line is written.

This feature needs no further work. It is accepted at `96e3a3a`, on a branch, unpushed and unmerged;
merging and releasing remain the user's decisions and take the release gate at the top of `CLAUDE.md`
with them.
