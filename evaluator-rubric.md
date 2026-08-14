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
**Branch:** `clearpoints-001` at `ddfddc0`, off `main` at `1e27b42`. Commits `13c9fb5` (code — the
hash all evidence is recorded against), `0b6373f` (artifacts), `ddfddc0` (gitignore repair). Not
pushed, not merged.
**Evaluated on:** 2026-08-14, by a session that did not implement the work.

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `bash init.sh` | `BUILD SUCCESSFUL`, `==> BASELINE: PASSING`. Its `VERIFY_CMD` now prints `./gradlew assemble check` — the `residue-001` finding about `init.sh` is fixed (on `main`, `c4c0c56`) |
| `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RoomDatabaseTest' --rerun-tasks` | `BUILD SUCCESSFUL in 7s`, `7 actionable tasks: 7 executed`; XML `ContributionTrackerTest tests=29 skipped=0 failures=0 errors=0`, `RoomDatabaseTest tests=6 skipped=0 failures=0 errors=0` |
| `./gradlew test --rerun-tasks`, then summing `build/test-results/test/TEST-*.xml` | `BUILD SUCCESSFUL in 6s`; `classes 12 tests 116 skipped 0 failures 0 errors 0` |
| `@Test` count per file at `1e27b42` vs `HEAD` | `101` across 12 files → `116` across 12. `ContributionTrackerTest` 16 → 29, `RoomDatabaseTest` 4 → 6, **every other file unchanged**. `git diff 1e27b42..HEAD -- src/test/` contains **zero deletion lines** — the test diff is strictly additive |
| `./gradlew assemble check` | `BUILD SUCCESSFUL in 4s`; `dist/sighteaddons-0.9.0.jar` md5 `b2ebc35ccfeb9cc96134eb3b18f0306f` **identical before and after**; `git status --short dist/ gradle.properties` empty; `mod_version=0.9.0` |
| **Mutation 1** — `unattributed()` back to `settle(roomsCleared - credited.values.sum())`, then the feature's own command | `BUILD FAILED`, `35 tests completed, 2 failed`: `weighting cannot silence the unattributed count`, `a pre-cleared room is not an unattributed one`. Reverted; `git status --short` empty |
| **Mutation 2** — `SECRET_POINTS`, `SEGMENT_POINTS`, `PUZZLE_BONUS`, `TRAP_BONUS`, `MINIBOSS_BONUS`, `BLOOD_BONUS` all `0.0`, then the feature's own command | `BUILD FAILED`, `35 tests completed, 7 failed` — exactly the seven recorded, including `a puzzle is worth more than an empty 1x1` and `RoomDatabaseTest > a real puzzle outscores a real empty room`. Reverted; `git status --short` empty |
| **Mutation 3, mine, not the session's** — flat weights **and** the old subtraction together, i.e. the pre-feature behaviour reconstructed, then the feature's own command | `BUILD FAILED`, `35 tests completed, 8 failed` = mutation 2's seven **plus** `a pre-cleared room is not an unattributed one`. The three pure value tests (`a run where every room was attributed reports nothing unattributed`, `a room somebody only passed through is still attributed`, `the unattributed count does not survive the run`) **all still pass** — which is the identity claim, proved from a direction the session did not run |
| **Mutation 4, mine** — `weightOf` gains `+ room.secretsFound * SECRET_POINTS`, i.e. the exact edit the KDoc argues against, then `./gradlew test --rerun-tasks` over everything | `BUILD SUCCESSFUL` — **all 116 tests pass**. That exclusion has no guard. Reverted; `git status --short` empty |
| `grep -n "unattributed" agent/AGENT-PROMPT.md` (receiver repo) | `62:| \`unattributed\` large relative to \`roomsCleared\` in \`run_end\` | Same decoration→player mapping as above; the gap is the built-in diagnostic for it. |` — the citation is exact, at exactly that line |
| `grep -n "RUN_KEYS\|ROOM_KEYS\|unattributed\|MAX_CLEARED" ingest.py` | `137 RUN_KEYS`, `152 ROOM_KEYS` — neither carries a points field; `167 MAX_CLEARED = 200`; `226 ("unattributed", lambda x: _real(x, 0, MAX_CLEARED))` |
| `grep -n "const val SCHEMA" RunReport.kt`; `git diff --name-only 1e27b42..HEAD -- RunReport.kt` | `66: private const val SCHEMA = 5`; the diff lists **nothing** — `RunReport.kt` is untouched by this branch |

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | **The unattributed decision is right and it is right for the stated reason.** `unattributed()` returns `unattributedRooms.toDouble()`; the counter is incremented at exactly one place, `award()`'s `if (fallback.isEmpty()) unattributedRooms++`, after the raw-presence fallback has had its chance — so a brief visitor is attributed and only a room nobody was ever seen in is counted. **I reproduced the guard test's premise by hand rather than trusting it**: puzzle(1 seg, PUZZLE, 3 secrets) = `1.0 + 1.5 + 0.75` = 3.25; big(4 seg, NORMAL, 6 secrets) = `1.0 + 1.5 + 1.5` = 4.0; total 7.25 against 3 rooms cleared, so `settle(3 - 7.25)` = `settle(-4.25)` = `0.0`. The clamp really would have gone silent on that run, and mutation 1 makes it do so. **The claim that no schema bump is owed holds, and I verified it rather than accepting it.** Mutation 3 reconstructs the pre-feature code exactly — flat weights *and* the old subtraction — and every reachable unattributed-value assertion still passes, so old and new agree case for case: `splitPoints` (`DungeonGrid.kt:76-81`) distributes exactly `points` proportionally, so under flat weighting an attributed room credits 1.0 and an unattributed one credits 0, and `roomsCleared - sum` *is* the count. The single divergence mutation 3 exposes is the `pointsAwarded`-already-true path, and it is unreachable through the real wiring: `discover` sets `preCleared`, `pointsAwarded` **and** `clearedAtTick` together (`ContributionTracker.kt:654-656`), `cleared get() = clearedAtTick != null`, and `tick()` guards `onCleared` behind `if (!room.cleared)`. So a pre-cleared room never reaches the seam, and where old and new could disagree it is the old code that over-reported. **No paired change is owed and none of the harness's Correctness-0 conditions are met**: `RunReport.kt` is not in this branch's diff at all, `SCHEMA` is still 5, no field is added, removed or retyped, `RUN_KEYS`/`ROOM_KEYS` carry no points field so the weighting never crosses the wire, and `unattributed` is validated as `_real(x, 0, MAX_CLEARED)` exactly as before — a `400` is not reachable from either definition. **The citation the design rests on is real.** `agent/AGENT-PROMPT.md:62` reads `unattributed` large relative to `roomsCleared` in `run_end` → the decoration→player mapping, "the gap is the built-in diagnostic for it" — which is precisely what the design says it says, at precisely the cited line. The weights themselves are coherent: `BASE_POINTS = 1.0` guarantees no room falls below the point it used to be worth (pinned across every `RoomType` and every database kind), and `kindBonus` runs both vocabularies through one `when` so `CHAMPION`/`MINIBOSS` cannot be worth different amounts depending on whether the chunk had streamed. |
| Verification | Did the required checks actually run, with evidence? | 1 | **Every recorded command reproduced exactly** — `29`/`6`, `classes 12 tests 116 skipped 0 failures 0 errors 0`, `2 failed` and `7 failed` at the recorded counts with the recorded test names, jar md5 unchanged, `BASELINE: PASSING`. **Nothing was deleted, skipped or loosened**: the test diff has zero deletion lines and no file lost a case, so the "do not weaken tests" rule is satisfied outright. `./gradlew build` was correctly avoided. **The deduction is a specific, reproducible overclaim about test coverage.** `session-handoff.md` states: "Three exclusions are deliberate, argued in the KDoc, and each has a test: rarity unpaid, secrets from the database rather than `secretsFound` …, and the floor explicitly not a multiplier." Only the first is true. (a) *Rarity* — genuinely pinned, `a rare room is not paid for being rare` asserts `RARE` with 4 secrets equals `NORMAL` with 4 secrets, which pins the reason and not just a number. (b) *Secrets from the database* — **not pinned.** Mutation 4 added `+ room.secretsFound * SECRET_POINTS` to `weightOf`, the exact edit the KDoc spends a paragraph arguing against, and the **whole 116-test suite passed**. (Substituting `secretsFound` for `info.secrets` would be caught incidentally, because the fixtures set `info.secrets` and leave `secretsFound` at 0 — but that is the fixtures' accident, not a guard, and it does not catch the additive form.) (c) *Floor not a multiplier* — **not pinned and not pinnable as written**: `floor` appears nowhere in `ContributionTrackerTest`, and `weightOf` does not take a floor, so the exclusion rests entirely on the KDoc. Two of the three "obvious next edits" the session identified are therefore undefended, while an artifact a next session reads first says they are defended — which is the failure shape this project keeps removing, applied to its own record. Note the rest of the record is honest: `feature_list.json`'s notes and `quality-document.md` claim only that the exclusions are *argued*, never that they are tested, and `quality-document.md` names the weight constants as judgement rather than measurement. **The three self-flagged unverified paths are named honestly and I tested the honesty**: the `tick()` wiring is genuinely unreachable here (no test file imports `net.minecraft`, and `tick` needs a `Minecraft` and a `MapItemSavedData`), the weight constants are correctly described as a first calibration, and nothing anywhere implies a real Hypixel run — the dev client's inability to reach Hypixel is stated in `session-handoff.md`, `claude-progress.md` and `quality-document.md` alike. |
| Regression | Are previously passing features still passing? | 2 | Full suite green from a forced cold run: `./gradlew test --rerun-tasks` → `BUILD SUCCESSFUL`, 12 classes, 116 tests, 0 failures, 0 errors, 0 skipped. `./gradlew assemble check` → `BUILD SUCCESSFUL`. `residue-001`'s own `verification_command` (`RunReportTest`) is inside that run and green, and its 21 cases are untouched — the branch does not modify `RunReportTest.kt` at all. `clear-001`'s command is likewise green at 29 + 21. No status was moved to hide anything: `residue-001` and `clear-001` stay `passing`, `ingame-001` and `records-001` stay `blocked` for their unchanged documented reasons, `chat-001` and `party-001` stay `not_started` at their prior priorities. The one behavioural change outside `ContributionTracker` is a comment in `RoomHistory.kt`; the `if (unattributed > 0.0)` guard beside it is unchanged and is now exactly right, since the figure is an integer count. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | `git diff --stat 1e27b42..HEAD` is ten files: two sources (`ContributionTracker.kt`, and a comment-only hunk in `RoomHistory.kt`), two test files, `README.md`, `.gitignore`, and four artifacts. `gradle.properties`, `dist/`, `build.gradle`, `init.sh`, `CLAUDE.md`, `rooms.json`, `src/main/resources/` and `RunReport.kt` do not appear. `mod_version=0.9.0` and the committed jar is byte-identical by md5 — **the release gate did not fire and could not have**. `SighteAddonServerside` has a clean working tree with no commit from this session: it was read, as the cross-repo check requires, and not written. The two temptations this feature offered were both declined: the `run_end` gap was recorded as a new feature `runend-001` rather than fixed inline, which is exactly `CLAUDE.md`'s prescribed path, and the `RunReport` schema was left alone once the diff showed no field moved. |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed cold in a session that did not implement it. Baseline green on the first try with no repair step and no environment fixing beyond what `session-handoff.md` documents; `python` resolved as the handoff says it now does, and I used it to sum the result XMLs. All **four** mutations reverted with `git checkout --` and `git status --short` came back empty every time, so nothing I did left residue and nothing the session recorded did either. `unattributedRooms` is reset in `reset()` alongside the rest of the run state and there is a test for that; `award()` is idempotent per room via `pointsAwarded`, so a repeated tick cannot double-count a room or double-increment the counter. Working tree clean, all three commits present, nothing staged or stashed, `dist/` untouched across a full `assemble check`. |
| Maintainability | Is the code and documentation clear enough for the next session? | 1 | **The code documentation is excellent and, where I checked it against the code, true.** `unattributed`'s KDoc names the exact failure mode and the exact reason the unit matters; `weightOf`'s KDoc argues each term and each exclusion where it is made; the comment at the `unattributedRooms++` site explains why it is counted there and not elsewhere; `onCleared`'s KDoc says plainly that it exists to be testable and why `tick` cannot be. **A carried-forward defect is genuinely fixed**: `RoomHistory.kt:256-265`'s comment was false in the `residue-001` review and has been rewritten to something accurate — the figure really is a count now, so "worth printing" really is "not zero". The deduction is two documentation defects with consequences. **(1) `claude-progress.md:11`, inside "Current Verified State", still reads "Standard verification path: `./init.sh` → `./gradlew test`; full is `./gradlew build`."** That is the command `session-handoff.md`'s "Do Not Touch" forbids and that `init.sh` was corrected on `main` to stop printing — and line 97 of the *same file* records that correction, so `claude-progress.md` now contradicts itself with the dangerous version in the section the file's own header calls "the first thing every new session reads". This session edited that section (the baseline bullet directly below) and left the stale line. **(2) The `session-handoff.md` "each has a test" sentence** will actively stop a future session from writing the two missing guards, because it says they are already there. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | Reconstructed the whole state from the artifacts alone — branch, all three commits and what each is for, the exact commands, both mutation probes precisely enough to rebuild them from scratch and hit the same failure counts and the same test names, the environment quirks, why `assemble check` and not `build`, and which paths are unverified and why. The `.gitignore` episode is disclosed rather than tidied away: the seven `config/sighteaddons/debug/session-*.jsonl` files swept into `13c9fb5` are named, `ddfddc0` untracks them (confirmed: `git ls-tree HEAD config/` is empty while at `13c9fb5` it lists all seven), and the reason `13c9fb5` was deliberately *not* amended — it is the hash the evidence is recorded against — is stated and is the right call. `runend-001` is left with the open question pre-asked (which of the two `unattributed` numbers the analyst wants) rather than a decision smuggled in. One factual slip, self-contradicted within the same file and not blocking: both `session-handoff.md` → "Verified Now" and `claude-progress.md` → session 005 say "One commit, `13c9fb5`", while the branch carries three — the handoff's own "Environment Quirks" then discusses "the branch's last commit". |

**Total: 12 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **REVISE** — 12/14 and no category 0, but Verification scored 1, so the Accept bar is not
met. Not overridden.

To be clear about what this verdict is and is not. The thing this review was pointed at hardest —
that weighted points must never be subtracted from a room count — is **correct, is guarded, and the
guard was proved by restoring the old expression and watching two tests fail**. The claim that no
schema bump is owed is **true**, and I established it independently by reconstructing the pre-feature
code and showing it agrees with the new code on every reachable case. The citation the design rests
on says what the design says it says, at the cited line. No receiver change is owed and no `400` is
reachable. Nothing here is a blocking defect and nothing needs reverting.

What holds it at Revise is narrower and entirely fixable: two of the three exclusions the session
called deliberate have no test, and an artifact a next session reads first says they do. Mutation 4
is the proof — the precise edit `SECRET_POINTS`' KDoc argues against passes all 116 tests. Closing
that is two test cases and one corrected sentence.

## Required Follow-Up

### Findings from this pass

1. **`session-handoff.md` claims all three exclusions have a test; only one does.** Fix the sentence,
   and preferably close the gap rather than only the claim:
   - **Secrets from the database, not `secretsFound`** — unguarded. `weightOf` + `room.secretsFound *
     SECRET_POINTS` passes all 116 tests. A test that sets `info(secrets = 0)` and
     `room.secretsFound = 8` and asserts the weight is unchanged pins the reason directly.
   - **Floor is not a multiplier** — unguarded and not reachable as `weightOf` is written, since it
     takes no floor. Either accept it as a KDoc-only argument and say so, or make it assertable.
   - **Rarity** — genuinely pinned; no action.
2. **`claude-progress.md:11` still names `./gradlew build` as the full verification path**, inside
   "Current Verified State". It contradicts `init.sh` (corrected at `c4c0c56`), contradicts
   `session-handoff.md` → "Do Not Touch", contradicts line 97 of its own file, and a session that
   follows it overwrites the released `dist/sighteaddons-0.9.0.jar`. This is the `residue-001`
   finding below, relocated rather than resolved — fixed in `init.sh`, still live here.
3. **Cosmetic, non-blocking:** `session-handoff.md` → "Verified Now" and `claude-progress.md` →
   session 005 both say the branch has one commit; it has three.

### Missing evidence, all named by the session, none a deduction

- **The weight constants are judgement, not measurement.** That they *separate* rooms is tested, in
  both directions and against the real `rooms.json` rather than a fixture. That 1.5 for a puzzle and
  0.25 a secret separate rooms in a way a player would agree with needs a real dungeon, and this
  repository cannot reach one. Treat them as a first calibration, as the artifacts already say.
- **`tick()`'s wiring to `onCleared` and `onPresence` is read, not asserted.** That seam needs a
  `Minecraft` and a `MapItemSavedData`, and no test file in this repository imports `net.minecraft`.
  `onCleared` itself is now covered, which is a real narrowing of the gap `clear-001` left; that it
  is *called* once per clear, and `onPresence` once per member per tick, still rests on reading
  `ContributionTracker.kt:466-479`. The unreachability of the pre-cleared divergence in the
  Correctness note rests on reading the same seam.
- **The cross-repo reading was not executed.** `agent/AGENT-PROMPT.md:62` was read and is exact, and
  `roomstats.py` does not consume the field at all — but no receiver test was run from here.

### Carried forward from the `clear-001` review, which this file overwrote

Status re-checked against the working tree this pass rather than copied:

1. ~~`RoomHistory.kt:260-261`'s comment is false.~~ **Resolved this session.** Rewritten and now
   accurate: the figure is a count, so `> 0.0` really is "not zero once rounded".
2. `ContributionTracker.settle`'s KDoc still argues rounding against the old clamp
   (`ContributionTracker.kt:448`, "the clamp this replaces only ever caught the negative half")
   rather than against the symmetric threshold that was the real alternative. **Still open**,
   optional.
3. ~~`init.sh` prints `./gradlew build` as the full verification command.~~ **Resolved in `init.sh`**
   (`c4c0c56`, on `main`) — `VERIFY_CMD=(./gradlew assemble check)` with the reasoning inline. **But
   see finding 2 above: the same instruction is still live in `claude-progress.md`.**
4. `anchorOnClear`'s "under a second" versus "at most one second". **Resolved** — the phrasing is
   gone from the source.
5. `stay.ticks` counts **sightings**, not elapsed ticks, and the gap test is per-gap rather than
   cumulative, so a member sighted once every 20 ticks accumulates a qualifying stay whose `start` is
   far older than `MIN_TICKS`. **Still open**: the KDoc should say ticks are sightings. Not a defect.
6. `clear-001`'s gap tolerance is calibrated against `PartyTracker`'s documented 10-20 tick window
   with zero margin, not measured. **Still open**, settled by the first real session file.
7. `anchorOnClear`'s real-floor frequency is unknown; `room_anchored` and the `cleared` event's
   `anchoredOnClear` flag exist to answer it. **Still open**, same trigger.

### Process — and yes, the overwrite pattern is itself a defect

The previous evaluator flagged that this file is overwritten per evaluation and carried three
`residue-001` findings forward by hand to stop them vanishing. I have now done the same thing a
second time, for seven items. That is the tell: a record that only survives because each reviewer
remembers to copy it is not a record, and the two items that *did* get fixed this cycle (`init.sh`,
the `RoomHistory` comment) were fixed only because they happened to be re-copied into the file the
next session read.

The mechanism, not the diligence, is what should change. `CLAUDE.md` already prescribes the path —
"if you discover new required work while implementing … add it to `feature_list.json` as a new
feature (or record it as a blocker)" — and it applies to an evaluator's findings as much as an
implementer's. Either route non-blocking findings into `feature_list.json` where they have a status
and a priority, or make this file append-only with a section per pass. Until one of those happens,
every review's findings have a half-life of exactly one review, and finding 2 above — a hazard fixed
in one file and left live in another — is what that costs in practice.

### Next review trigger

`chat-001`. Check before starting whether anything it adds reaches `RunReport.kt`; if it does, diff
its fields against `RUN_KEYS` in the receiver's `ingest.py` first, and **the receiver goes first**.
`runend-001` is the cheaper alternative and needs its open question — which of the two `unattributed`
numbers the receiver's analyst wants — decided before a line is written, not after.
