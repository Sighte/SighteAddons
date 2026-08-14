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

**Evaluated:** `clearpoints-002` — "ClearPoints, the user's model: a room is worth what it measures".
**Branch:** `clearpoints-002` at `8482388`, off `main` at `fa075bd`. Not pushed, not merged. For the
commit count run `git rev-list --count fa075bd..HEAD` — it reads **2**, and it is deliberately not
transcribed into any artifact (see Maintainability on the previous feature's history here).
**Evaluated on:** 2026-08-14, by a session that implemented none of this work. This is the **first**
grading of `clearpoints-002`.

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `bash init.sh` | `openjdk 25.0.4 OK`, `BUILD SUCCESSFUL`, `==> BASELINE: PASSING`. Its "Full verification command" block prints `./gradlew assemble check` with the `copyToDist`/`cleanDist` reasoning inline |
| `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --rerun-tasks` (the feature's own recorded `verification_command`, run verbatim) | `BUILD SUCCESSFUL in 7s`, `7 actionable tasks: 7 executed` |
| `./gradlew test --rerun-tasks`, summing `build/test-results/test/TEST-*.xml` | `classes 13 tests 140 skipped 0 failures 0 errors 0`. **120 → 140 and 12 → 13 confirmed.** Per class: `ContributionTrackerTest` **42**, `RoomDatabaseTest` 8, `RoomStatsTest` 9 (new file), the other ten unchanged |
| `@Test` counts either side of `fa075bd` | `ContributionTrackerTest` 33 → **42**, `RoomDatabaseTest` 6 → **8**, `RoomStatsTest` 0 → **9**. `+20` exactly, and the other ten test files are byte-identical to `main` |
| `git diff fa075bd..HEAD -- src/test/ \| grep -c '^-[^-]'` and the same filtered to `@Test` | **30** deletion lines, of which **`0`** are `@Test`. Every deleted line is a KDoc line, a renamed `fun` signature, a moved helper, or one of the three assertion bodies treated individually below |
| `./gradlew assemble check`; `md5sum dist/sighteaddons-0.9.0.jar` before and after | `BUILD SUCCESSFUL`; md5 `b2ebc35ccfeb9cc96134eb3b18f0306f` **identical both sides**; `git status --short dist/ gradle.properties` empty; `mod_version=0.9.0` |
| `git diff --name-only fa075bd..HEAD \| grep -c RunReport`; `grep -n 'const val SCHEMA' RunReport.kt` | **`0`** — `RunReport.kt` is not in the branch diff at all. `66: private const val SCHEMA = 5` |
| `grep -rn 'scoresTs\|generatedTs' src/main/kotlin/` | Three write sites, **none of them the report**: `ContributionTracker.kt:698` inside a `DebugLog.event("award", …)`, `RoomHistory.kt:310` on the `history.jsonl` line, and `RoomStats.kt:182` on the `room_scores` debug event. `RunReport.kt` contains no reference to `RoomStats`, `RoomHistory` or `scoresTs` |
| **Probe 1, rebuilt from scratch** — `"Ice Fill" to 2.0` → `"IceFill" to 2.0` | `BUILD FAILED`, **`140 tests completed, 3 failed`**. Matches the recorded count exactly |
| **Probe 2, rebuilt** — `RoomScores.METRIC` `"clearStay"` → `"clear"` | `BUILD FAILED`, **`140 tests completed, 6 failed`**. Matches |
| **Probe 3, rebuilt** — `confidence` → `if (sample.n < 5) 0.0 else 1.0`, i.e. a cliff at n=5 | `BUILD FAILED`, **`140 tests completed, 2 failed`**. Matches |
| **Probe 4, rebuilt** — dropped `.coerceIn(MIN_BASE, MAX_BASE)` | `BUILD FAILED`, **`2 failed`**: `no measurement however extreme can run away with a room`, `no room is ever worthless`. Matches |
| **Probe 5, rebuilt** — absent sample returns `MEDIAN_BASE` instead of `seed` | `BUILD FAILED`, **`7 failed`**, across both `ContributionTrackerTest` (5) and `RoomDatabaseTest` (2). Matches |
| **Probe 6, rebuilt** — shrinkage inverted to `k / (n + k)` | `BUILD FAILED`, **`4 failed`**: the two convergence cases, the no-cliff case and `a single observation barely moves a room`. Matches |
| **Probe 7, rebuilt** — segment (`0.5`/segment) and `TRAP` (`0.5`) bonuses reintroduced | `BUILD FAILED`, **`1 failed`** and only 1: `size and kind are no longer paid for directly`. Matches the recorded count, *including* the session's own "and only 1" |
| **`clearpoints-001`'s load-bearing probe, re-run under the new weighting** — `unattributed()` back to `settle(roomsCleared - credited.values.sum())` | `BUILD FAILED`, **3 failed**: `weighting cannot silence the unattributed count`, `a pre-cleared room is not an unattributed one`, `a room somebody only passed through is still attributed`. The silent-forever-zero is still guarded, and now by one case more than at `96e3a3a` |
| **My own throwaway `EvalProbeTest`** (4 cases, written from the brief rather than from the session's fixtures; deleted afterwards) | `BUILD SUCCESSFUL`, `tests=4 failures=0 errors=0`. Details in Correctness |
| Final state after 8 mutations | `./gradlew test --rerun-tasks` → `classes 13 tests 140 skipped 0 failures 0 errors 0`; `bash init.sh` → `BASELINE: PASSING`; jar md5 unchanged; `git status --short` **empty**; `git diff --quiet` clean |

### The two replaced tests, judged individually

The brief flagged this as the likeliest place for a weakened suite. It is not one — but the judgement
is per case, so here is each.

| Old case | New case | Verdict |
| --- | --- | --- |
| `a four-segment room is worth more than a 1x1 of the same kind` — one `assertTrue` inequality, pinning `SEGMENT_POINTS` | `size and kind are no longer paid for directly` — one exact `assertEquals` for the four-segment room **plus six more** across `TRAP`, `CHAMPION`, `BLOOD`, `RARE`, `ENTRANCE`, `FAIRY` | **Genuinely stronger.** An inequality became seven exact equalities. It asserts the opposite of the old case on purpose, because the constant it pinned is deleted; keeping it would have pinned behaviour the user replaced. Probe 7 shows it is the *only* thing standing between the tree and a reintroduced size/kind bonus — thin, but real and named by the session |
| `every room is still worth at least the point it used to be` — `>= 1.0` over `RoomType.entries` and eight database types, pinning `BASE_POINTS = 1.0` | `no room is ever worthless` — same two loops at `>= 0.75`, **plus** a `MIN_BASE` check driving `blend` with `n = 1_000_000, avgTicks = 1.0` against a median of `10_000.0` | **Honest consequence, and net stronger.** The `1.0` bound is the constant the user's model removes, so it could not survive; what mattered about it (nothing is ever worth nothing) is kept and *extended* to the clamped-measurement case, which the old one never reached. Probe 4 shows the added half is load-bearing |

A third case was renamed rather than replaced — `the database and the map agree on what a miniboss is`
→ `…on what a room's kind is` — and it **gained** an assertion (the `PUZZLE` leg, which is now the one
word the two vocabularies have to agree on). Strictly stronger.

**The one genuine loosening**, and I agree it is not a violation: inside `an expensive room nobody was
in is one unattributed room, not its weight in points`, the fixture sanity line moved from
`weightOf(expensive) >= 4.0` to `> 2 * weightOf(plain())`. That room is now worth 3.50 and the old
line would fail. `CLAUDE.md` forbids weakening tests *to make work look complete*; this is a fixture
precondition inside a case about something else entirely (that `unattributed()` returns `1.0` room and
not `3.5` points), that assertion is untouched, and the change is **written out by name in
`claude-progress.md`** with its reasoning. It is nonetheless slack — the new bound is `1.5` against an
actual `3.50` — and it is carried below as follow-up 1.

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | **None of the harness's Correctness-0 conditions is met, and I checked rather than inherited.** `RunReport.kt` is absent from `git diff --name-only fa075bd..HEAD` (`grep -c RunReport` → `0`), `SCHEMA` is `5` at `RunReport.kt:66`, `mod_version=0.9.0`. `generatedTs` rides on exactly the three places it is supposed to — the `award` and `room_scores` debug events and the `history.jsonl` line — and `RunReport.kt` contains no reference to `RoomStats`, `RoomHistory` or `scoresTs` at all. No schema change landed, so none could have landed ahead of the receiver. **The seeds are the user's numbers, exactly, and I checked them against the bundled database rather than against the seed map.** My own test resolves `Ice Fill` → 2.0, `Water Board` → 1.5, `Quiz` → 1.0, `Boulder` (an unnamed puzzle) → 1.0, `Admin` → 0.75, `Old Trap` (TRAP) → 0.75, `Pipes` → `0.75 + 7×0.25` = 2.50, all to `0.0` tolerance; and `RoomDatabase.infoByName` returns non-null for the three seeded names and **null for `IceFill` and `Waterboard`**, which is the failure mode the brief named. Probe 1 confirms a misspelling is caught, by 3 cases. **The blend is the specified formula.** I recomputed `0.75·(avgTicks/median)^0.5` clamped `[0.25, 2.5]`, blended by `n/(n+10)`, independently of the implementation across 35 `(n, ticks)` combinations and it agrees to `1e-12`. `n = 0` returns the seed **exactly** (`0.0` tolerance) for six seeds × three medians, as do a null sample and a null median; `n = 10_000_000` converges seeds of `0.25` and `9.0` onto the same `1.5` (= `0.75·√4`). **No cliff**, checked as a monotone-decreasing-step property over `n = 1…5000` at three speeds, which catches a cliff wherever it is put — probe 3 confirms. **The three layers behave as the brief requires.** I drove ten cache shapes through `RoomStats.read` — missing file, empty, whitespace-only, truncated mid-object, right shape with `n` absent, `n: null`, `n: 0` with `avgTicks: null` (the box today), no `rooms` array, `rooms` not an array, and an HTML 502 body — and **every one yields exactly the seeds, a null median, zero samples, and none of them throws.** **The judgement calls are argued from data, not asserted.** `TIME_EXPONENT = 0.5` is reasoned from a measured 49× spread of clear times against the user's 2.7× spread of estimates, with the explicit consequence that a linear map would leave the clamp deciding nearly every room; `CONFIDENCE_SAMPLES = 10` is reasoned from the box's own rate (157 visits over 9 runs across 83 rooms → ten clears of one room is roughly forty runs). Both are the right kind of argument. One caveat on the first is follow-up 4. |
| Verification | Did the required checks actually run, with evidence? | 2 | **All seven mutation probes rebuilt from scratch, and all seven reproduce the recorded failure counts exactly** — 3, 6, 2, 2, 7, 4, 1 — including the two the handoff singles out as the feature's only silent failures. Probe 2 (`clearStay` → `clear`) fails 6 cases; that is the one edit that would produce entirely plausible numbers off the wrong measurement, and it is caught. Probe 1 (`Ice Fill` → `IceFill`) fails 3; the guard checks against the bundled `rooms.json` rather than against the seed map, which is the only way it is a guard at all. I also re-ran `clearpoints-001`'s load-bearing probe under the new weighting and it still fires, now on 3 cases rather than 2. **Nothing was removed.** Zero deleted `@Test` lines across the whole branch; ten of thirteen test files byte-identical to `main`; `+20` cases. The two replacements are judged case by case in the table above and both genuinely pin more — an inequality became seven exact equalities, and the old floor's meaningful half was kept and extended to the clamp. The third apparent deletion is a rename that gained an assertion. **The one loosening is real and it is justified in the progress log by name**, with the number that forced it (3.50 against a `>= 4.0` line) and the reason for relativising rather than retuning — so it does not meet the Verification-0 condition, and I would have scored it 0 had the log been silent. **Unverified paths are named, not implied.** `session-handoff.md` gives the measured half its own bullet ("no room in any real install has ever had a non-zero `n`"), layer 2 its own ("exercised only by `@TempDir` fixtures"), and repeats that `runClient` cannot reach Hypixel; `claude-progress.md` says every install is on layer 3; `clearpoints-002`'s own notes close on what is unverified. **Nothing anywhere implies a real run was performed as verification of this feature** — checked across all four artifacts. |
| Regression | Are previously passing features still passing? | 2 | Full suite green from forced cold runs: `./gradlew test --rerun-tasks` → `classes 13 tests 140 skipped 0 failures 0 errors 0`, run four separate times across this evaluation and green every time. `./gradlew assemble check` → `BUILD SUCCESSFUL`, jar md5 `b2ebc35ccfeb9cc96134eb3b18f0306f` measured before and after and identical, `git status --short dist/ gradle.properties` empty. `bash init.sh` → `BASELINE: PASSING`. `residue-001`'s `RunReportTest` (21) and `clear-001`'s cases are inside that run and green; neither file is touched by this branch. **`clearpoints-001`'s five named guards all survive at `HEAD`** — `a room is worth the same on every floor`, `a rare room is not paid for being rare`, `the live secret counter is not what a room is worth`, `weighting cannot silence the unattributed count`, `a pre-cleared room is not an unattributed one` — and I proved the last two still bite rather than merely still existing. Diffing statuses in `feature_list.json`: nothing was downgraded or removed to hide anything; `clearpoints-002` is the only status that moved (`blocked` → `passing`, with the blocker's resolution explained rather than quietly dropped) and `scores-fetch-001` was *added* as discovered work per `CLAUDE.md`. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | Two commits, ten files: three source, three test, four artifacts. No version bump, no `dist/` refresh, `rooms.json` untouched, `RunReport.kt` untouched, `build.gradle`/`init.sh`/`CLAUDE.md`/`evaluator-rubric.md` untouched — the release gate did not fire and could not have. Layer 1 was **recorded rather than built** (`scores-fetch-001`, `blocked`), which is exactly what `CLAUDE.md` asks for when new required work is discovered mid-feature. The bundled-snapshot design was written and then dropped on the user's push-back, and the reversal is recorded with its reason rather than buried. `SighteAddonServerside` was read (`ingest.py`, `roomstats.py`) and never written, and the session explicitly did **not** add the scores endpoint to the receiver's feature list from here. The live box was touched read-only. `README.md`'s contributor `./gradlew build` line was again left alone rather than "fixed" — the fourth session to make that call correctly. |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed cold by a session with no prior context. Baseline green on the first attempt, no repair step, no environment fixing beyond what `session-handoff.md` documents. Eight source mutations applied and reverted; `git status --short` came back empty after every one and `git diff --quiet` is clean at the end. The handoff's environment quirks are correct and load-bearing rather than decorative: `python` resolves and `python3` does not, exactly as stated; **restoring a mutated file with `git checkout` rather than by writing it back from Python is not optional here** — I hit the `core.autocrlf` trap the handoff predicts and its advice is what got me out; and the warning that any test touching `weightOf` must pin `RoomStats.use(RoomScores.NONE)` is real — my own throwaway test would have been machine-dependent without it, since `config/sighteaddons/` on this machine is populated by every `./gradlew test` run. |
| Maintainability | Is the code and documentation clear enough for the next session? | 1 | **The new code is excellent and the deduction is not about it.** Every constant is argued where it is made, `blend` is pure and takes its inputs so the model is testable without a file on disk, `RoomScores`' class KDoc explains all four receiver metrics and why three of them are wrong, and the seed map lists `Quiz` even though its value is redundant so the table is the user's table one-for-one. The deduction is that **four statements in living artifacts are now stale or unsourced**, and this repository's own history is why that costs a point: `clearpoints-001` sat at Revise for two passes over exactly this — an artifact asserting something that had stopped being true. (1) `clearpoints-001`'s `notes` still open "WHAT THE WEIGHTING IS" with the deleted formula — `BASE_POINTS 1.0`, the four kind bonuses, `0.5 per segment beyond the first` — stated in the present tense with **no supersession marker**, while its status is `passing`. A reader working down the list in order meets the wrong description of live behaviour before they reach `clearpoints-002`'s correction. (2) `ingame-001`'s notes still cross-reference "`clearpoints-001`'s weight constants, which are judgement rather than measurement"; those constants no longer exist and the concern has transferred to the seeds. (3) The real-M7 figures — rooms from 1.00 (`Hall`) to 4.50 (`Cathedral`) — are now asserted as measurement in `claude-progress.md:49`, in `ContributionTracker.kt:462` and in a `RoomDatabaseTest` KDoc, but **the session file is not in the repository and no command reproduces them**; `CLAUDE.md` is explicit that for anything only a real dungeon shows, the evidence is the debug session file and the line in it. Their only provenance was the previous `evaluator-rubric.md`, which this file overwrites. (4) `TIME_EXPONENT`'s 49× calibration is drawn from the box's `clear` averages, because `clearStay` has zero samples anywhere — and the KDoc that argues it does not say so, in a file that elsewhere spends a paragraph insisting the two must never be confused. None of these is a defect in behaviour and none is load-bearing on an assertion, which is why this is 1 and not 0. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | I reconstructed the entire state from the artifacts alone: branch and head, what each commit is for, every command, all seven mutation probes precisely enough to rebuild them from scratch and hit identical failure counts, which metric is the right one and why the other three are not, the environment quirks, why `assemble check` and never `build`, and which paths are unverified and why. The handoff's "Next Best Step" asked for exactly this evaluation and named exactly the two probes that matter most; both were reproducible from that description alone. **The durable-findings migration still holds** — I checked each is still present in `feature_list.json` rather than trusting that it was: `residue-001`'s notes carry the `settle` KDoc finding; `clear-001`'s notes carry all three of the sightings-vs-elapsed-ticks wording, the zero-margin gap tolerance and `anchorOnClear`'s frequency; `ingame-001` cross-references the ones needing a real run. Nothing was lost across the rewrites. Two of those items are, however, **less advanced than the previous rubric recorded** — see follow-up 3, which is the one place a hand-rescue is genuinely owed. |

**Total: 13 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **ACCEPT** — 13/14, no category 0, and Correctness, Verification and Regression all 2. Not
overridden.

**Every number the session recorded reproduced, to the count.** Seven mutation probes rebuilt from
their descriptions rather than copied, and each one failed exactly the number of cases recorded —
including probe 7's "and *only* 1", which is the kind of claim a session has no incentive to state and
every incentive to round up. 140 tests, 13 classes, `ContributionTrackerTest` 42, jar md5 unchanged,
`mod_version=0.9.0`, `SCHEMA` 5, `RunReport.kt` absent from the diff.

**The suite is not weakened, and I checked that per case rather than in aggregate.** Zero `@Test`
lines were deleted. Both replaced cases pinned constants the user's model removes, so keeping either
would have pinned the behaviour that was replaced — and both replacements assert strictly more than
the originals, one turning a single inequality into seven exact equalities and the other keeping the
old floor's meaningful half while extending it to the clamped case the original never reached. A third
case was renamed and gained an assertion. The single genuine loosening is a fixture precondition
inside a test about something else, it is written out by name in `claude-progress.md` with the number
that forced it, and the assertion the case actually exists for is untouched. Had the log been silent
about it, this would have been a Verification 0.

**The seeds and the blend are the user's specification, verified independently.** I recomputed the
formula from the brief across 35 combinations and matched the implementation to `1e-12`; `n = 0`
returns the seed to `0.0` tolerance, not approximately; two seeds four orders of magnitude apart
converge on the same measured value at large `n`; and the no-cliff property holds as a
monotone-decreasing-step invariant over `n = 1…5000` at three speeds, which is a strictly stronger
statement than bounding steps by a threshold and catches a cliff wherever somebody puts one. Ten
malformed or absent cache documents — including the exact `n: 0, avgTicks: null` shape the box writes
today — all yield exactly the seeds and none of them throws.

**The schema boundary is clean and was the first thing I checked.** `generatedTs` reaches the two
debug events and the `history.jsonl` line and stops there. `RunReport.kt` is not in the branch diff at
all, so the ordering rule the harness enforces was never in play — there is nothing here for the
receiver to learn and nothing owed.

**The deduction is documentation drift, and it is the one thing this repository has proven it is prone
to.** `clearpoints-001`'s notes still describe its own deleted formula in the present tense with no
supersession marker; that entry is `passing`, so a reader has no signal that it is history. This is
the same shape as the impossibility claim that held `clearpoints-001` at Revise twice — a true-when-
written statement outliving its session inside a living artifact — caught early this time, and cheap
to fix.

**On the real-M7 figures I want to be precise, because I am about to destroy their provenance.** The
numbers 1.00/`Hall` and 4.50/`Cathedral` are sound and I have no reason to doubt them; they entered
the repository through the previous `evaluator-rubric.md`, which recorded them as corroboration. They
have since been promoted into a source KDoc, a test KDoc and the progress log as plain measurement,
while the session file `CLAUDE.md` names as their required evidence has never arrived — the handoff
says so itself. That is a gap between two living artifacts, and this file was the bridge. Follow-up 2
is what keeps it from vanishing when this file is next overwritten.

## Required Follow-Up

Nothing here blocks acceptance. None of it is a defect in shipped behaviour, and no receiver change is
owed — nothing on this branch reaches the wire.

### New this pass

1. **The one loosened assertion is slack, and could be tightened for free.** `an expensive room
   nobody was in…` now sanity-checks `weightOf(expensive) > 2 * weightOf(plain())`, i.e. `> 1.5`,
   against a fixture actually worth `3.50`. Relativising it was the right instinct and the retuning
   argument is correct, but the multiplier could be `4` and still hold with margin. Optional, and the
   case's real assertion is unaffected either way.

2. **The real-M7 figures are cited as measurement in three living artifacts with no reproducible
   evidence, and this file was their only provenance.** `claude-progress.md:49`,
   `ContributionTracker.kt:462` and `RoomDatabaseTest`'s `the seed weight of Pipes…` KDoc all state
   that the one real M7 scored rooms from 1.00 (`Hall`) to 4.50 (`Cathedral`). `session-handoff.md`
   says the session file has not come back. `CLAUDE.md` → "What Counts as Evidence" requires the
   debug session file and the line in it for anything only a real dungeon shows. Either obtain the
   file and record it, or mark those three citations as second-hand. **Provenance, recorded here so
   the next overwrite of this file cannot lose it: the figures were first recorded by the
   `clearpoints-001` evaluator (rubric pass three, at `96e3a3a`/`b90d2b7`) as corroboration relayed
   by the user, not read out of a committed artifact.** Nothing asserts on them, so no test is at
   risk.

3. **Two of `clear-001`'s open notes are recorded as less advanced than the previous rubric measured,
   and the measurements lived only in this file.** Carried forward by hand, which is what the brief
   for this pass asked for:
   - `clear-001` note (1) still reads "`TrackedRoom.onPresence`'s KDoc **should** say ticks are
     sightings". The previous pass measured this: every anchor in the real M7 was stamped 19 ticks
     after its stay began, i.e. the 20th sighting at `MIN_TICKS = 20`, so sightings and elapsed ticks
     do coincide on a per-tick decoration stream. The note can move from *reasoned* to *measured*;
     the KDoc wording fix it asks for is still unmade.
   - `clear-001` note (3) still reads "`anchorOnClear`'s **REAL-FLOOR FREQUENCY IS UNKNOWN**". The
     previous pass recorded it as **fired once in ten rooms** — so the fallback is rare and the
     anchor is mostly genuine. Worth reconciling alongside it that `ContributionTracker.kt`'s KDoc
     for `anchorOnClear` still estimates "the empty 1x1s, three of them in one M7"; the measured
     rate is lower. Same order, benign direction, now checkable.
   - `clear-001` note (2), the zero-margin gap tolerance, is **still genuinely unmeasured** and
     stays open unchanged — the real run reports what happened, not what the worst case is.

4. **`TIME_EXPONENT`'s calibration data comes from the metric the model does not use, and the KDoc
   does not say so.** The 0.75 s – 36.5 s / 49× spread is drawn from the box's `clear` averages,
   because `clearStay` has `n = 0` for every room (the session measured this itself over SSH). The
   same file argues at length that `clear` is a walk-through-inflated upper bound and must never be
   confused with `clearStay` — and walk-through inflation is not uniform, so the true `clearStay`
   spread is plausibly narrower, which would weaken rather than strengthen the case against a linear
   map. The exponent is still argued from data and I graded it as such; it needs one sentence naming
   the proxy. Revisit once `clearStay` has real samples.

5. **`feature_list.json`'s `clearpoints-001` entry describes deleted behaviour in the present
   tense.** "WHAT THE WEIGHTING IS: … `BASE_POINTS` 1.0, plus a kind bonus (PUZZLE 1.5; TRAP,
   CHAMPION/MINIBOSS and BLOOD 1.0 each) … plus 0.5 per segment beyond the first" — none of which
   exists at `HEAD`. Status is `passing`, so nothing signals it is history. One superseded-by line at
   the top of that note is the whole fix; do **not** delete the paragraph, it is the record of what
   was built. Same for `ingame-001`'s cross-reference to "`clearpoints-001`'s weight constants",
   which should now point at the seeds.

6. **Probe 7 shows exactly one test stands between the tree and a reintroduced size/kind bonus.**
   The session recorded this honestly ("fails 1 and *only* 1") and I confirmed it. Not a defect — one
   case with seven exact equalities is a real guard — but it is a single point of failure for the
   deletion that is the whole feature. Carried as context.

### Carried forward from the `clearpoints-001` passes — all still closed

Re-checked at `8482388` rather than assumed, since the branch rewrote both files involved:

- ~~The floor-exclusion impossibility claim.~~ **Still closed.** `a room is worth the same on every
  floor` is present at `HEAD` and `clearpoints-001`'s notes still carry the full retraction.
- ~~`feature_list.json:194` overstates the run-progress case.~~ **Still closed.**
- ~~The hand-transcribed commit count.~~ **Still closed, and the fix held through a second feature** —
  `session-handoff.md` again tells the reader to derive it and again refuses to state it.
- ~~`session-handoff.md` claims all three exclusions have a test.~~ **Still closed.**
- ~~`claude-progress.md:11` names `./gradlew build` as the full verification path.~~ **Still closed.**
  `README.md`'s contributor Build section is deliberately left and named in "Do Not Touch" — I agree
  with that call for the fourth time.
- **The floor guard is still the only reflection in the suite**, with its cost and its mitigation
  recorded. Standing property, not a finding.

### Still living in `feature_list.json`, not here

The migration has now survived four rewrites of this file. **I verified each is still present** rather
than trusting that it was. Do not copy them back here — this section records that the migration holds,
and follow-up 3 above is what moves two of them forward.

- `residue-001` notes — `settle`'s KDoc argues rounding against the old clamp rather than against the
  symmetric threshold that was the real alternative. Still open, optional.
- `clear-001` notes — (1) sightings vs elapsed ticks: **measured, see follow-up 3**; (2) the zero-margin
  gap tolerance: **still unmeasured**; (3) `anchorOnClear`'s real-floor frequency: **answered, see
  follow-up 3**.
- `ingame-001` notes — cross-references the ones that need a real run, plus the weight constants
  (pointer now stale, see follow-up 5).

### Remaining unverified paths, all named by the session, none a deduction

- **The measured half of the model has never run on real data.** Every install is on layer 3, no room
  in any real install has ever had a non-zero `n`, and the box confirms it — 83 rooms, 9 with a
  `clear` sample, sum of every `clearStay.n` = **0**. `blend` is tested at every property that matters
  and mutation-probed seven ways, but the thing being blended toward does not exist yet anywhere.
  This is what `scores-fetch-001` is for, and the session states it in `session-handoff.md`,
  `claude-progress.md` and `clearpoints-002`'s notes rather than leaving it to silence.
- **Layer 2 has never been read from a real game directory.** Exercised only by `@TempDir` fixtures
  and by my own ten-shape probe, because nothing writes a cache until layer 1 exists.
- **Whether the weights separate players in a way a player would agree with.** Same ceiling
  `clearpoints-001` had; needs a floor played with the mod installed. Rides with `ingame-001`.
- **`tick()`'s wiring to `onCleared`/`onPresence` is read, not asserted.** A genuine limit, correctly
  distinguished from the floor claim: the missing objects are constructor arguments needing a
  `Minecraft` and a `MapItemSavedData`, not private fields, so no reflection trick is available.
  `onCleared` itself is covered.
- **The cross-repo reading was not executed as a test.** `ingest.py` and `roomstats.py` were read for
  `RUN_KEYS`/`ROOM_KEYS` and the document shape; no receiver test was run from here. Nothing on this
  branch reaches the wire, so nothing is owed.
- **Nothing anywhere implies a real Hypixel run was performed as verification of this feature.**
  Checked across all four artifacts, and correct — with the M7 citation caveat at follow-up 2, which
  is about provenance rather than about a claimed test.

### Next review trigger

`chat-001`, per the handoff. Check before starting whether anything it adds reaches `RunReport.kt`; if
it does, diff its fields against `RUN_KEYS` in the receiver's `ingest.py` first, and **the receiver
goes first**. Do **not** start `scores-fetch-001` — it is blocked on the receiver serving
`roomstats.json`, which `do_GET` does not. `runend-001` is the cheaper alternative and still needs its
open question — which of the two `unattributed` numbers the receiver's analyst wants — decided before
a line is written.

This feature needs no further work. It is accepted at `8482388`, on a branch, unpushed and unmerged;
merging and releasing remain the user's decisions and take the release gate at the top of `CLAUDE.md`
with them — and the release notes now owe the line the handoff names: **room points changed meaning
and old standings are not comparable to new ones.**
