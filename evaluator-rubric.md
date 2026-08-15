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

### Commands re-run at `ad3df34`, and what they actually printed

| Command | Result |
| --- | --- |
| `bash init.sh` | `==> BASELINE: PASSING` |
| `./gradlew test --tests 'sighteaddons.SecretRunTest' --tests 'sighteaddons.RoomHistoryTest' --tests 'sighteaddons.ContributionTrackerTest' --rerun-tasks` (the `verification_command`, verbatim) | `BUILD SUCCESSFUL in 8s`. **SecretRun 11 / RoomHistory 13 / ContributionTracker 54, 0 failures, 0 errors, 0 skipped.** Matches the claimed 11 / 13 / 54 |
| `./gradlew test --rerun-tasks`, summed from `build/test-results/test/TEST-*.xml` | **`classes 15 tests 212 failures 0 errors 0 skipped 0`.** The delta from pass 1's 211 is **`RoomHistoryTest` 12 → 13 and nothing else** — every other class identical. Nothing was lost to gain the new case |
| `md5sum dist/*.jar; ./gradlew assemble check; md5sum dist/*.jar` | `BUILD SUCCESSFUL in 5s`; md5 **`e8cd7099034dd3475dbc8069be3c433e` identical both sides**; `git status --short dist/ gradle.properties` **empty**; `mod_version=0.11.0`. `./gradlew build` was not run |
| `python build/keydiff.py` | `SCHEMA 5`, 17 run keys, 17 room keys, **four empty sets both directions**, `KEYDIFF: CLEAN` |
| `git diff main..HEAD --name-only` | 12 files. **`RunReport.kt` absent**, as are `rooms.json`, `gradle.properties` and `dist/`. `RunReport.kt:67` still `SCHEMA = 5` |
| Receiver | `SighteAddonServerside` on `master` at `018cee5`, `git status --porcelain` **empty**. Read, never written |
| **`bash build/evalsweep.sh`** — my own crash-safe driver over their 21 probes | **21 / 21 met their declared expectation**, `EVAL SWEEP OK`, `git status --porcelain src/` empty **before the first probe and after the last**, and every probe carries a `git diff --numstat` proof that the mutation reached the tree before the tests ran |
| **Probe K specifically** — pass 1's finding | **`caught`**, failing exactly `RoomHistoryTest > the presence floor is the one thing wrong with a room that cleared in six ticks`. It passed all 211 tests in pass 1. The hole is closed and the closure is measured |
| Deletion / weakening, **whole branch** | vs `main`: **0** `@Test` lines removed, **0** assertion lines removed, and the only removed line in all of `src/test/` is the one-line `room()` helper. Intra-pass (`4e2db23..ad3df34`): 0 `@Test` removed, **1** assertion changed — see below, it strengthens |
| Feature-status diff vs `main` | `recordowner-001` added as `passing`; `ownsecrets-001` added as `not_started`. **Nothing downgraded, removed or reworded** |
| `python build/ownsecrets.py` | Read-only, writes nothing. Printed **90** completed runs / 12 kept / 13.3% / 2 of 23 solo / 10 of 67 party — **not** the recorded 87 / 13.8% / 10 of 64. Explained below, and it is not an error |

### The `ownClear` two-line split — the claim I was told to be hostile about

The claim is that the only behaviour change in this pass was `ownClear`'s conditions moving onto
separate lines, "identical logic". **Verified against the diff, and it is true.**

```
-        if (self == null || self != topPlayer) return false
+        if (self == null) return false
+        if (self != topPlayer) return false
```

`||` short-circuits, so the old form returns `false` on a null `self` without evaluating the second
operand, and otherwise returns `false` iff `self != topPlayer`. The new form does the same in the
same order. No condition is inverted, none is dropped, none is added, and the remaining three lines
of the function are untouched. **The split is not cosmetic — it is what makes probes U and H able to
delete each half alone**, which is the discipline the whole sweep now rests on, and both come back
`caught`.

### Test 1 assertion changed intra-pass — checked, and it strengthens

`git diff 4e2db23..ad3df34 -- src/test/` removes exactly three lines: a KDoc line, the old test name
`the presence floor is enforced by the gate itself`, and
`assertFalse(RoomHistory.ownClear(room, self = "Me", topPlayer = "Me"))`.

That old assertion was the one pass 1 showed to be vacuous — `presentFromStart`'s staleness half
refused the fixture 61 ticks out, so the floor was never under test. It is replaced by **two** tests:
`the presence floor is the one thing wrong with a room that cleared in six ticks`, which reaches the
floor through `anchorOnClear` and asserts every *other* condition says yes before asserting the gate
refuses; and `the caller cannot produce a top player below the floor`, which keeps the old fixture
and records why `onRoomCleared` can never reach the floor. **Probe K flipping from `uncaught` to
`caught` is the proof that this is a strengthening and not a rename.** Justified in the KDoc, the
progress log and the entry.

### The 21-probe sweep, and whether the three exemptions are honest

The sweep covers **every condition of every gate this feature added** — I enumerated them
independently against the source: `onSecret`'s start guard (A, B, P, Q), `readBar`'s ordering and
both its own conditions (C, D, R), `ownSecretRun`'s two (E, F), `ownClear`'s five (U, H, K, L, M,
plus G for the pair), and `presentFromStart`'s three (J, N, O, I). Nothing is missing.

I also checked the failure mode a sweep of this shape actually has: **a probe defined but absent from
the `ORDER` list would never run and nothing would say so.** `PROBES` and `ORDER` are both exactly
the same 21 keys — checked by parsing the file, not by reading it.

**The three declared-uncaught exemptions are honest.** I verified each independently rather than
accepting its stated reason:

- **Q** (`max < 2` deleted) — genuinely redundant, not unguarded. `if (found >= max)` three lines
  below reaches the same `DISCARDED` for a one-secret room; I read `onSecret` to confirm it. The
  clause is pre-existing and states the intent where the intent is formed.
- **S** and **T** (the two wiring lines ignore their own gates) — the feature's declared ceiling,
  and I measured both `uncaught` myself in pass 1 before they were ever written down as expected.
  They are recorded as "if this ever starts being caught, somebody has found a way to test the
  wiring", which is the right framing: the exemption is a tripwire, not an excuse.

**Where the mechanism is still weaker than the ten probes it replaces:** `SWEEP OK` compares only
`caught` / `uncaught`, never the failure *count* or the failing test *name*. A probe that starts
being caught by an unrelated test would still read "as expected". My driver captured counts and
names for all 21 and they match pass 1 throughout — but nothing in the repository's own harness would
notice the drift. Follow-up 3.

### Reconciling 15 / 87 / 12 against my 16 / 80 / 9 — settled

The coordinator asked me to decide which of us counted wrong. **Three separate things, and I was
wrong about one of them:**

1. **File count: they are right, 15. My "sixteen" in pass 1 was my own miscount** — I listed fifteen
   files and wrote sixteen. Corrected here rather than left in the superseded section to be
   inherited.
2. **The party split: their method is better and supersedes mine.** They take the roster from
   `tab_slot.parsed`, the actual roster readout; I took it from player names in `cleared` ticks maps,
   which only ever contains members who were in a room that cleared, so sessions where the local
   player was the only such member were misfiled as solo. Their 10 of 64 supersedes my 7 of 57.
   **The single-member figure `2 of 23` is identical in both replays**, which is why it was the
   figure worth agreeing on.
3. **The run total is neither a file-set nor a method difference, and the entry attributes it to the
   wrong cause.** Both replays scanned all fifteen logs. I re-counted per file: **fourteen of the
   fifteen are byte-stable and agree exactly between my pass-1 scan and now. Exactly one grew** —
   `session-1786750213806.jsonl`, from 4 completed runs to 15, whose mtime is **two minutes before I
   measured it**. The user is playing right now. That is the whole of 80 → 87 → 90 → 91: my 80 was
   true when I measured it, their 87 was true when they measured it, the script printed 90 for me
   minutes ago and my own recount gives 91 now.

**Consequence, and it is the one that matters for the release:** the bare integers now written into
`feature_list.json`, `claude-progress.md`, `session-handoff.md`, `quality-document.md` and the KDoc
are consistent with each other — which is the pass-1 defect fixed — but they are computed over a
directory that is still being appended to, so they were stale before the ink dried. The figures that
*cannot* drift are the committed floor's **four of five** and the ratio-with-command. Those are the
ones that belong in an artifact. Follow-up 2.

**Their two headline claims verified independently, both hold exactly.**
`session-1786572786745.jsonl`: roster `['p-44e1f7eb']` — a single member — **8 completed runs, 0 kept**,
`Stairs 3/4, Grand Library 3/4, Pirate 4/6, Pit 3/5, Overgrown 2/3, Big Red Flag 0/2, New Trap 2/3,
Redstone Warrior 2/3`. It is the strongest single statement of the finding, and unlike the aggregate
it is frozen — that file has not changed since 2026-08-13. And `RunReport.kt:420` does ship
`obj.addProperty("ownSecrets", room.ownSecrets)`, so `ownsecrets-001`'s cross-repo flag is accurate:
changing what `ownSecrets` *means* moves a shipped field's meaning without moving its key, which is
the `clear-001` shape and needs the receiver diffed first.

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

## What the 0.12.0 release notes must tell a player

The notes are generated out of `feature_list.json`, `claude-progress.md` and `session-handoff.md`, so
this is the part of the verdict with the shortest path to a player.

**How to describe the record change — behaviour first:**

- Room records are now only written when the work was yours. A **secret-run** personal best is
  recorded only when the mod counted *every* secret in that room as yours. A **room-clear** personal
  best is recorded only when you were in the room from the moment its clock started *and* spent more
  time in it than anyone else. Walking into a room somebody else is already clearing no longer
  credits you with it, and neither does arriving as the checkmark lands.
- **Say plainly that far fewer records will be written, and that this hits solo floors too.** On the
  real logs measured for this build, roughly one completed secret run in seven still qualifies, and
  the rate on single-player floors is no better. On a solo floor every secret was yours by
  construction, so what is failing there is not the rule but the mod's *attribution*: a secret is
  credited to you only if you right-clicked a chest, lever or secret skull within about two seconds,
  or Hypixel named you in a wither-essence line. A secret picked up by walking over it counts as
  somebody else's and sinks that room's whole run. Tracked as `ownsecrets-001` and **not fixed in
  this build**. A player who sees their PBs stop appearing should read that sentence, not guess.
- **Records already in `history.jsonl` are not repaired**, deliberately. Rooms whose best was set by
  walking into somebody else's work keep that record and will simply never report a PB again.
  Nothing is rewritten, reinterpreted or migrated.
- The chat lines are unchanged: the room-clear line still names whoever actually cleared the room and
  the secret-run line still carries `(N, M yours)`. One narrowing worth a clause: with **own PBs
  only** switched on, a run the gate refuses no longer prints at all.

**What is not verified — `CLAUDE.md` requires these be named rather than left to assumption:**

- **Neither gate has ever run in a game.** The predicates are unit-tested and swept with 21
  mutations, but the four lines that call them need a live client, and two probes (S, T) confirm
  nothing in the test suite guards them.
- **The secret-run gate now requires the mod to have read a `0/N` action bar on entering the room.**
  If Hypixel does not deliver one on a bar the mod trusts, secret-run records stop entirely rather
  than becoming rare. Two new debug fields — `secret_room_first_bar` and `firstBar` on
  `secret_run_discarded` — exist so the first real floor answers this from the log instead of by
  inference.

**Do not put a bare "87 completed runs / 13.8%" in the notes.** That figure is computed over a log
directory that is still being written to and was already 90, then 91, within minutes of being
recorded. Quote the committed floor — *four of five records on the one real solo floor in the
repository* — or the ratio with the command that reproduces it. Both are stable; the integer is not.

**Nothing here is owed to `Sighte/skyblock-server`.** This is a client-side change: no report field,
no schema move, `SCHEMA` stays 5. Nothing has to be deployed before this jar. That belongs in the
GitHub notes' operator section and, per `CLAUDE.md`, stays out of the Modrinth copy.

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

# Pass 1 — `recordowner-001` at `4e2db23` — **REVISE, 11 / 14** (superseded, retained)

Full text in git at `e5cf586`. Retained here rather than overwritten, because this file's contract is
that it is rewritten each pass and that has already cost this repository findings once.

**Verdict:** REVISE. Correctness 2, **Verification 1**, Regression 2, Scope 2, Reliability 2,
**Maintainability 1**, **Handoff readiness 1**.

Every recorded number reproduced and all ten of that pass's probes reproduced to the exact failing
test name, including probe H, which the session honestly recorded as having passed all 211 tests
before it rebuilt the fixture. The two findings that held it below Accept:

1. **The stated cost of the strict gate was wrong in scope** — "party secret records become rare",
   against a committed repository artifact showing four of five records lost on a *solo* floor.
   **Answered at `ad3df34`**: replayed with `build/ownsecrets.py`, cause correctly re-diagnosed as
   attribution rather than shared work, corrected consistently in five artifacts, and opened as
   `ownsecrets-001`.
2. **A second guard in name only** — deleting `ownClear`'s `MIN_TICKS` floor failed zero of 211
   tests while a test name and a KDoc claimed otherwise (probe K). **Answered at `ad3df34`**: fixed
   as a property via the fast-clear shape, probe K now `caught`, and the unreachability through
   `onRoomCleared` recorded as its own test.

Also raised and now fixed: the `onSecretRun` "announcement stays either way" over-claim; the
`quality-document.md` `scoring` row promoted from the superseded copy; the handoff's claim that one
real session log existed when fifteen do.

**One error of my own in that pass, corrected in pass 2 follow-up 5:** I wrote "sixteen real session
logs" where I had listed fifteen. The count is 15.
