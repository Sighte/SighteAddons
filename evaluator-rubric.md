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


**When it is required:** a change to the report schema, anything that pulls the release gate, a
`priority` <= 3 feature, or a feature that caused a regression. Otherwise the orchestrator decides
and records why.

**Nits do not cost points.** A finding about only wording, documentation, or the precision of an
artifact goes under "Nits" and must not lower a score. Only behaviour, evidence or a regression
moves a number. `clearpoints-001` took three passes without the code's behaviour meaningfully
changing between the second and the third.

**This file holds the current pass only, in <= 120 lines.** Pass 1 on `recordowner-001` (REVISE,
11/14) was dropped on 2026-08-16 and is complete at `0852382`
(`git show 0852382:evaluator-rubric.md`). An open finding must not live only here: this file is
rewritten wholesale every pass, which is how `residue-001`'s finding came to be hand-copied forward
by two reviewers before it was moved into `feature_list.json`. Put findings where the work is.

---
# Pass 2 — `recordowner-001` at `ad3df34` — **ACCEPT, 13 / 14**

**Evaluated:** `recordowner-001` — "A record is only yours when the work was yours".
**Branch:** `recordowner-001` at `ad3df34`, off `main` at `8431597`. Not pushed, not merged. For the
commit count run `git rev-list --count 8431597..ad3df34` — deliberately not transcribed.
**Evaluated on:** 2026-08-15 by the same evaluator session that wrote pass 1 below, which implemented
none of this work. This is the **second** grading of `recordowner-001` and it grades the revision the
implementing session made in answer to pass 1's REVISE.

**Pass 1's two blocking findings are both answered, and answered with measurements rather than
prose.** The `MIN_TICKS` hole is closed as a *property* — my probe K has flipped from `uncaught` to
`caught` and now fails exactly the one case that isolates it. The cost of the strict gate is a
replay script, not an adjective, and the corrected figures are consistent across all five artifacts,
which is the failure mode this repository keeps having and did not have this time.

**What is settled and was not re-argued here:** the user was shown the measurement, was offered the
majority rule `ownSecrets * 2 >= secretsFound`, and reaffirmed `ownSecrets == secretsFound`. It ships
as written. This pass grades whether the claims about that gate are true and whether the code around
it is guarded — not the threshold.

**Two things pass 2 found, neither of which blocks acceptance:**

1. **The verification harness is not crash-safe, and it cost something during this evaluation.**
   `build/runprobes.sh` restores the mutated sources on the success path, so an interruption between
   applying a probe and reaching `git checkout` leaves the repository carrying a deliberate defect.
   That is exactly what happened: this session was killed mid-sweep and the tree sat with
   `return room.presentFromStart(self, at)` rewritten to `return true` until **the coordinator
   restored it by hand** — not the harness. Recorded here rather than left silent, because a release
   was queued behind this pass and a harness whose failure mode is "leaves the code wrong" is one
   crash away from a jar cut off a mutated tree. Follow-up 1.
2. **The cost figure is computed over a live dataset and the number in the five artifacts is already
   stale.** Not a mistake by anyone — one of the fifteen logs is being written to *right now*. See
   the reconciliation section; it is why my 80 and their 87 are both correct and neither should have
   been transcribed as a bare integer.


*The reconstruction this verdict rests on — the commands re-run, the `ownClear` split, the 21-probe
sweep and its three honest exemptions, and the reconciliation of 15/87/12 against 16/80/9 — was
dropped on 2026-08-16 and is complete at `0852382`. So was the release-notes section, which 0.12.0
discharged when it was published.*

### Category scores

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | Unchanged from pass 1 and re-established rather than inherited. All four of the user's decisions still implemented literally, including decision 4, the one easiest to violate quietly: `record()` is still untouched, a `clear` is still `room.ticks[self]` and a `secretrun` still `room.secretRunTicks`, so no line's meaning moved. `history.jsonl` untouched, no new `kind`, no version field. **The only source change in this pass is the two-line split, and I verified it is logically identical rather than accepting the claim** — `||` short-circuits, the order is preserved, nothing inverted. No schema change: `RunReport.kt` absent from the branch diff, `SCHEMA` 5, `keydiff` CLEAN 17/17 with four empty sets, receiver clean at `018cee5`. `mod_version` 0.11.0, `dist/` byte-identical. No Correctness-0 condition is met. |
| Verification | Did the required checks actually run, with evidence? | 2 | **Raised from 1. Both pass-1 findings are answered with measurements.** Probe K, which passed all 211 tests in pass 1 while a test name and a KDoc claimed the floor was guarded, now **fails exactly the case that isolates it** — and the fix is a property (`anchorOnClear`, six ticks, the shape `Duncan` really has on the one real M7) rather than a fixture tweak, with the unreachability through `onRoomCleared` recorded as its own test. The sweep is now genuinely a sweep: 21 probes, one per condition per gate, complete against my own enumeration, `PROBES` and `ORDER` verified to be the same 21 keys so nothing is silently skipped, expectations checked bidirectionally, and **21/21 met expectation on a clean-to-clean run with a per-probe proof that each mutation landed**. The three `uncaught` exemptions are honest — I re-derived Q from `onSecret`'s source and measured S and T myself in pass 1 before they were written down. Every recorded number reproduced: 11/13/54, 212 in 15, jar md5 identical, `KEYDIFF: CLEAN`, `BASELINE: PASSING`. **Nothing deleted or weakened**: 0 `@Test` and 0 assertions removed against `main`; the single intra-pass assertion change replaces a vacuous assertion with two stronger tests, and probe K's flip is the proof. Held at 2 rather than 3-of-3-with-reservations because the residual issues — a harness that is not crash-safe, a sweep that ignores failure counts, a figure over a live dataset — are follow-ups about the *tooling and the quoting*, not about whether the required checks ran. |
| Regression | Are previously passing features still passing? | 2 | `classes 15 tests 212 failures 0 errors 0 skipped 0`, `--rerun-tasks`. The delta from pass 1's 211 is **`RoomHistoryTest` 12 → 13 and nothing else** — I compared per class, so the new floor case was added without losing anything. Against `main` at `8431597` (193 in the same 15) the branch is still strictly additive: **0** `@Test` and **0** assertion lines removed anywhere under `src/`, one removed line in total and it is a helper. `assemble check` `BUILD SUCCESSFUL` with the jar byte-identical either side and no version bump, so the release gate never engaged. Status diff: `recordowner-001` `passing`, `ownsecrets-001` `not_started`, nothing downgraded or quietly reworded. The 21-probe sweep is itself a regression instrument and it came back clean end to end. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | The source change is one two-line split plus KDoc corrections. `RunReport.kt`, `rooms.json`, `gradle.properties`, `dist/` all absent; no version bump; `./gradlew build` not run; receiver read and never written. **The discovered attribution gap was recorded as `ownsecrets-001` rather than fixed inline**, which is the operating loop's rule and the right call — and the entry explicitly forbids the fix that would have been in scope creep's interest, softening `ownSecretRun` to compensate. The two unprompted corrections (the stale `scoring` quality row, the `onSecretRun` KDoc over-claim) are both repairs of things this session's own earlier pass got wrong, which is inside scope rather than beyond it. |
| Reliability | Does the result survive restart or rerun without repair? | 1 | The *feature* survives cold reruns perfectly: `init.sh` green first attempt, every command reproduced from a clean tree, 21 mutations applied and restored with `git status --porcelain src/` empty before and after. **Held at 1 because the repository's own verification harness required external repair during this evaluation.** `build/runprobes.sh` restores on the success path, not in a trap, so when this session was killed mid-sweep the tree was left carrying probe M — `return room.presentFromStart(self, at)` rewritten to `return true` — and it was **the coordinator, not the harness, that ran `git checkout`**. Any probe results in flight at that moment are void, which is why the entire sweep was re-run from scratch here under `build/evalsweep.sh` with `trap restore EXIT INT TERM` and pre/postcondition assertions. This is the second instance of the species in this project — the receiver's handoff records a mutation harness that silently matched nothing and reported a clean sweep — and with a release queued the exposure is a jar cut from a mutated tree. The fix is one `trap` line. Follow-up 1. |
| Maintainability | Is the code and documentation clear enough for the next session? | 2 | **Raised from 1; all four of pass 1's documentation defects are fixed, and fixed by correction rather than deletion.** `ownSecretRun`'s cost paragraph is now a measurement with its method, its split and its upper-bound caveat, and names attribution rather than sharing as the cause. `ownClear`'s KDoc no longer claims abstract "totality" — it states which shape reaches the floor, what deleting it costs (a 0.3 s record), that the claim was false on 2026-08-15, and which test now holds it. `onSecretRun`'s "the announcement stays either way" is qualified with the `Config.ownPbsOnly` narrowing and why it is defensible. The `quality-document.md` `scoring` row is corrected **and says out loud that an earlier pass promoted the stale copy and dated it**, which is the right way to fix a record. The new test names describe what their fixtures actually test — the specific defect pass 1 raised. `build/ownsecrets.py` and `build/recordprobe.py` both open with why they are correct, not just what they do. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | **Raised from 1.** The fifteen real logs are counted, listed with event counts, and `Next Best Step` is rewritten to mine them before asking the user to play anything — which was pass 1's finding. The trap that would have wasted the next session's time is documented: `config/sighteaddons/debug/` in the repository holds ~145 files written by `./gradlew test` and contains no dungeon, so the glob must point at the PrismLauncher directory. The `barFound: 0` evidence is **qualified by them more carefully than I qualified it** — real, but on a room whose max mismatched, so it proves Hypixel sends `0/N` without proving the trusted path. `ownsecrets-001` is opened with a first task explicitly doable from disk, the wrong fix forbidden, and the cross-repo risk at `RunReport.kt:420` flagged and verified accurate. I reconstructed everything needed for this pass from the artifacts alone. |

**Total: 13 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **ACCEPT** — 13/14, no category 0, and Correctness, Verification and Regression all 2. Not
overridden.

`recordowner-001` is accepted at `ad3df34` as a **passing** feature. It sits on a branch, unpushed
and unmerged; merging and releasing remain the user's decisions and take the release gate at the top
of `CLAUDE.md` with them. **No receiver change is owed and this feature is not paired** — no field,
no key, no schema move, verified mechanically in both directions.

`passing` remains the right status for the reason pass 1 gave and this pass re-measured: the
decisions are pure predicates, exhaustively probed; the wiring genuinely needs a live client, unlike
`scores-fetch-001` whose ceiling was a conflation; and probes S and T measure that ceiling every run
rather than asserting it.

## Required Follow-Up

Nothing here blocks acceptance. No receiver change is owed.

1. **Make the mutation harness crash-safe — restore in a `trap`, not on the success path.**
   `build/runprobes.sh` runs `git checkout` at the bottom of its loop body, so any interruption
   between applying a probe and reaching that line leaves the working tree carrying a deliberate
   defect. **This is not hypothetical: it happened during this evaluation.** This session was killed
   mid-sweep, the repository was left with `ownClear`'s `presentFromStart` call rewritten to
   `return true`, and it was restored by the coordinator by hand rather than by the harness. Every
   probe result in flight at that moment had to be discarded, because a probe applied on top of
   another probe measures neither. It is the second instance of this species in the project — the
   receiver's handoff records a mutation harness that silently matched nothing and reported a clean
   sweep — and with a release queued the exposure is a jar cut from a mutated tree. `build/evalsweep.sh`,
   written for this pass, is a worked example: `trap restore EXIT INT TERM`, a refusal to start
   unless `git status --porcelain src/` is empty, a `git diff --numstat` proof per probe that the
   mutation landed, and a postcondition check at the end. Fold that into `runprobes.sh`. **This is
   the highest-value item in this document** and it is one line plus two assertions.
2. **Stop transcribing a figure computed over a live directory.** Fourteen of the fifteen logs are
   byte-stable; `session-1786750213806.jsonl` is being appended to as this is written, and it alone
   moved the total 80 → 87 → 90 → 91. The entry's reconciliation attributes my 80 against their 87 to
   "file set and roster method" — the roster method explains the *split* and my miscount explains the
   *file count*, but the total is neither, and saying so matters because it means the recorded 87 is
   not a number that can be maintained. Quote `python build/ownsecrets.py` and its ratio, or the
   committed floor's four-of-five, which is frozen in git. Fix in all five places at once, since they
   currently agree and that is worth preserving.
3. **The sweep compares `caught`/`uncaught` and ignores the failure count and the failing test name.**
   A probe that stops being caught by the case that names the property and starts being caught by an
   unrelated one still reads "as expected". All 21 match pass 1's counts today — I captured them —
   but nothing in the repository's harness would notice the drift. Record the expected count, or at
   least the expected failing test, alongside the expectation.
4. **`onSecret`'s post-start `if (found >= max)` is not in the sweep.** It is the condition probe Q's
   exemption leans on: Q is declared benign *because* that check reaches the same `DISCARDED`. So the
   exemption's honesty depends on a line nothing probes. Pre-existing and out of this feature's
   scope, but one more probe closes the loop the exemption opens.
5. **My own pass-1 error, corrected here so it is not inherited:** I wrote "sixteen real session
   logs" where I had listed fifteen. The count is **15**. The implementing session was right and said
   so; I am recording it in my own voice rather than leaving the correction only in theirs.

### Carried forward, unchanged and still open

6. The `/sa` switch labelled `upload run reports` also decides whether the mod *receives* its room
   weights, and nothing a player can read says so.
7. `build/` is gitignored, so no probe or replay script survives a clean — `git ls-files | grep -c keydiff`
   → `0`. `keydiff.py` has been rewritten from scratch by five sessions now, and `ownsecrets.py`,
   `recordprobe.py`, `runprobes.sh` and my `evalsweep.sh` are all one `git clean` from gone. Give the
   repository a committed, not-collected home for them; the receiver's `probe_readonly.py` is the
   proven shape.
8. `PartyTracker.kt:183,194` still cites "NoammAddons" without naming which of the two repositories.
9. `party-001`'s notes still do not mention the `map.decorations.toList()` snapshot.
10. `deconame-001`'s `verification_command` is still vacuously green; defused by `not_started`.
11. `chat-001`'s `verification_command` widening still has no durable trace — `grep -ni widen
    claude-progress.md` returns two hits and neither is about `chat-001`.
12. `ChatEvents.kt:129` still claims `DungeonChatFilter` "has no other `found a` shape"; it has two.
13. `ContributionTracker.kt:370` still has `MIN_TICKS = 20` against a documented worst-case
    roster-skew blackout of 20 — `clear-001`'s zero-margin note. **The fifteen logs may now close
    this from disk**: several carry `roster_skew` events, up to 26 in one session.
14-22. The remaining carried items from pass 1 (previous follow-ups 5, 12, 15, 16, 18, 19, 20 and the
    five rescued from `runloss-001`'s rubric) are unchanged and were not re-verified this pass. They
    are preserved in pass 1 below and in git at `e5cf586`.

### Next review trigger

**Follow-up 1 before anything else, and before the release is cut.** A harness that leaves the tree
mutated on a crash is the one defect on this list that can put wrong code in a jar.

**Then the first real floor**, which is now the only thing that can move this feature's remaining
unknowns: `secret_room_first_bar` on a trusted bar, the four wiring lines, and whether the strict
gate has closed the door on every record rather than on the wrong ones. The user is playing as this
is written, so the log to read may already exist.

**Then `ownsecrets-001`**, whose first task needs no dungeon — the `attributedBy` and `own_interaction`
fields in the fifteen logs already on disk say what fraction of unattributed rises have no interaction
near them, which is the measurement that decides what the feature actually is.

---

