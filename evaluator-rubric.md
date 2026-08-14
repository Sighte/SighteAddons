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

**Evaluated:** `clear-001` — "Anchor `enterTick` on a minimum stay".
**Branch:** `clear-001` at `6211c7e`, off `main` at `b588cc4`. Commits `a6d92b6` (anchor, schema bump,
tests), `6211c7e` (artifacts). Not pushed, not merged.
**Evaluated on:** 2026-08-14, by a session that did not implement the work.

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `./gradlew test --tests 'sighteaddons.ContributionTrackerTest' --tests 'sighteaddons.RunReportTest' --rerun-tasks` | `BUILD SUCCESSFUL in 7s`, `7 actionable tasks: 7 executed`; XML `ContributionTrackerTest tests=16 failures=0 errors=0`, `RunReportTest tests=21 failures=0 errors=0` |
| `bash init.sh` | `BUILD SUCCESSFUL in 4s`, `==> BASELINE: PASSING` |
| JUnit XML sum over `build/test-results/test/TEST-*.xml` | 12 files; `tests=101 skipped=0 failures=0 errors=0` |
| `./gradlew assemble check` | `BUILD SUCCESSFUL in 4s`; `git status --short dist/ gradle.properties` empty; `dist/sighteaddons-0.9.0.jar` md5 `b2ebc35c…` **identical before and after** |
| `@Test` count at `b588cc4` vs `HEAD` | `84` across 11 files → `101` across 12. No file lost a test |
| **Mutation 1** — `SCHEMA = 5` → `4`, then `./gradlew test --tests 'sighteaddons.RunReportTest' --rerun-tasks` | `BUILD FAILED`, `21 tests completed, 2 failed`: `the stay anchor only ships under a schema that says so`, `run context survives`. Reverted; `git status --short` empty |
| **Mutation 2** — drop `stay.ticks < MIN_TICKS` from `onPresence`, then the feature's own command | `BUILD FAILED`, `37 tests completed, 9 failed` — all 9 in `ContributionTrackerTest`, including `a walk-through does not anchor the room`, `a clear long after a walk-through reports no anchor at all`, `a member who passes through and comes back is anchored on the return`. Reverted; `git status --short` empty |
| `grep -n "const val SCHEMA" src/main/kotlin/sighteaddons/RunReport.kt` | `66:    private const val SCHEMA = 5` |
| `ssh root@217.160.51.229 'grep -n STAY_ANCHOR_SCHEMA /srv/sighte/server/roomstats.py'` | `68:STAY_ANCHOR_SCHEMA = 5`, `86: return "clearStay" if schema >= STAY_ANCHOR_SCHEMA else "clear"` |
| `cmp /srv/sighte/server/roomstats.py /srv/sighte/roomstats.py` (checkout vs live, the check `deploy.sh`'s `changed()` makes) | identical — the receiver half is **deployed**, not merely merged. `sudo -u sighte git -C /srv/sighte/server log -1` → `f1085a7` |
| Live `/srv/sighte/roomstats.json` | `stayAnchorSchema: 5`, `rooms: 83`, `rooms with clearStay.n>0: 0`, `rooms with clear.n>0: 9` |

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | **The load-bearing constant is right and I checked it three independent ways**: the diff (`b588cc4..a6d92b6` on `RunReport.kt` is exactly two hunks — a KDoc paragraph and `-private const val SCHEMA = 4` / `+private const val SCHEMA = 5`), the working tree (`RunReport.kt:66`), and mutation 1, which fails 2 tests the moment it goes back to 4. **Both halves of the pair are real and neither can be undone silently.** The receiver half is not just on `master` — `cmp` between `/srv/sighte/server/roomstats.py` and the live `/srv/sighte/roomstats.py` is identical, so it is deployed, and the live `roomstats.json` reports its own `stayAnchorSchema: 5`. **Receiver-first ordering verified from timestamps, not from the claim**: `STAY_ANCHOR_SCHEMA` landed at `3f1cdfd` `2026-08-14 02:34:46`, merged to `master` at `f1085a7` `14:51:42`; the mod commit `a6d92b6` is `15:07:39`. Receiver reached `passing` and deployed *before* the mod feature's commit — the sequence `CLAUDE.md` fixes was respected, so this is not a schema-change-landed-early Correctness 0. **No `400` is possible and the session's in-place correction of its own earlier rationale is accurate**: `ingest.py:214` validates `v` as `_num(x, 1, 10)` (5 was always in range), `ingest.py:159` has had `enterTick` in `ROOM_OPTIONAL` since schema 3, and the RunReport diff adds, removes and retypes no field at all. I also checked the *other* schema threshold on the receiver, which nothing in the artifacts mentions: `ingest.py:151` `NAMED_FROM_SCHEMA = 3` — already below 4, so 4→5 crosses it in neither direction and the `player`-consent path is unaffected. `STAY_ANCHOR_SCHEMA` is the only boundary the bump crosses, which is the one it was made to cross. **The anchor itself is what it says.** `discover`'s first-sighting assignment is gone from the diff (`-        room.enteredAtTick = DungeonSession.runTicks`); `enteredAtTick` is `var … private set` (`ContributionTracker.kt:49-50`), so the only two writers are `onPresence:134` (the qualifying stay's own `start`, not the tick it qualified) and `anchorOnClear:161`. The fallback's bound is real: `filter { at - it in 0..MIN_TICKS }`, so no span it produces exceeds 20 ticks. The `enterTick <= clearTick` invariant holds by construction — `tick()` calls `anchorOnClear` at line 372, `clearedAtTick` is set at 373, and `onPresence` short-circuits on `cleared` — and the `preCleared` null-anchor claim checks out against the receiver: `roomstats.py:102` returns `dict.fromkeys(SPANS)` for a `preCleared` visit. |
| Verification | Did the required checks actually run, with evidence? | 2 | Every recorded command re-run and reproduced **exactly**, down to the strings: `7 actionable tasks: 7 executed`, `BASELINE: PASSING`, `tests=101 skipped=0 failures=0 errors=0` across 12 classes, and both mutation checks at the recorded counts (2 of 21; 9 of 37, with the named tests among the 9). **No test was deleted, skipped or loosened**: `@Test` goes 84 → 101 and no file drops one. The two touched `RunReportTest` assertions are tightenings, not weakenings, and both are disclosed in the evidence and the progress log — `run context survives` moved `assertEquals(4, …)` → `assertEquals(5, …)`, and the shared fixture replaced `enteredAtTick = 120` with `repeat(MIN_TICKS) { onPresence("Sighte", 120 + it) }`, landing on the same 120 because the setter is now private. **The three self-flagged unverified paths are honestly stated and I tested the honesty rather than accepting it.** (a) *The wiring* — genuinely unreachable, not an excuse: `grep -rln "net.minecraft" src/test/` returns **nothing at all**, so no test in this repository touches a Minecraft type and the `tick(client, map)` seam has no way in. What *can* be established by reading — that `anchorOnClear` precedes `clearedAtTick` — I confirmed, and the artifacts claim no more than reading. (b) *The gap tolerance* — the artifacts draw exactly the right line: the **mechanism** is tested (`a gap shorter than the threshold does not split a stay`, `a gap longer than the threshold starts a new stay`, both among mutation 2's failures), only the **calibration** is unmeasured. Its premise is real — `PartyTracker.kt:144-151` documents the 10-20 tick window and `if (!trustOrder) continue` does drop teammate positions entirely — and `at - stay.lastSeen - 1 > MIN_TICKS` covers a 20-tick blackout with zero margin, which is what "if real blackouts are longer, some rooms will anchor about a second late" says. (c) *Fallback frequency* — unmeasurable here, and the session did the actionable thing instead of only confessing: the `room_anchored` event and `anchoredOnClear` on the `cleared` event exist in `tick()` and make it answerable from the first real session file. The "dev client cannot reach Hypixel" limit is stated in both `session-handoff.md` and `claude-progress.md`; nothing implies a real run. `./gradlew build` correctly avoided — I confirmed `dist/sighteaddons-0.9.0.jar` md5 is byte-identical before and after `assemble check`. |
| Regression | Are previously passing features still passing? | 2 | Full suite green from a cold `bash init.sh`: `BASELINE: PASSING`, 12 classes, 101 tests, 0 failures, 0 errors, 0 skipped. `./gradlew assemble check` `BUILD SUCCESSFUL`. `residue-001`'s own `verification_command` is a subset of the run above and is green; `ingame-001` stays `blocked` for the same unchanged reason. No status was moved to hide anything — `clearpoints-001`, `chat-001`, `records-001`, `party-001` are all still `not_started` at their prior priorities. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | `git diff --name-only b588cc4..HEAD` is exactly four source/test files (`ContributionTracker.kt`, `RunReport.kt`, `ContributionTrackerTest.kt`, `RunReportTest.kt`) and four artifacts. `gradle.properties`, `dist/`, `build.gradle`, `init.sh`, `CLAUDE.md`, `rooms.json` and `src/main/resources/` do not appear. `mod_version=0.9.0` unchanged, the committed jar unchanged by md5, working tree clean — **the release gate did not fire and could not have**. `SighteAddonServerside` has a clean tree on `master` at `f1085a7` with no commit from this session: it was read, as the cross-repo schema diff requires, and not written. The temptation this feature offered — fixing `clearpoints-001`'s flat weighting while inside `award()` — was not taken. |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed cold in a different session from the artifacts alone. Baseline green first try, no repair step, no environment fixing beyond what `session-handoff.md` already documents. Both mutation probes reverted with `git checkout --` and `git status --short` came back empty each time, so the recorded mutations left nothing behind. `onPresence` and `anchorOnClear` are pure over `TrackedRoom` state and both are idempotent once `enteredAtTick` is set (each returns early), so a repeated tick cannot re-anchor. Working tree clean, both commits present, nothing staged or stashed. |
| Maintainability | Is the code and documentation clear enough for the next session? | 2 | Unusually strong, and — unlike the previous feature's — every load-bearing comment I checked against the code was **true**. The `SCHEMA` KDoc names the exact failure mode ("no 400, no log line, just a permanently contaminated mean in an append-only store") and cites the receiver constant that causes it. `ContributionTracker.kt:124-126` explains the `- 1` in the gap comparison in terms of what it measures (ticks missed, not distance between sightings) and that is precisely what the expression does. `tick():370-371` explains why `anchorOnClear` precedes `clearedAtTick`, and it does. `enteredAtTick`'s KDoc states the `enteredAtTick <= clearedAtTick` invariant and `private set` on line 50 is what enforces it. The 16 test names are behavioural, not structural — which is why mutation 2 fails 9 of them instead of failing to compile. Two small imprecisions, neither misleading: `anchorOnClear`'s KDoc says it "can only ever record a clear of under a second" when `0..MIN_TICKS` is inclusive and the true bound is *at most* one second; and neither KDoc notes that `stay.ticks` counts sightings rather than elapsed ticks, so under repeated tolerated gaps a stay's `start` can precede its qualification by much more than `MIN_TICKS` (see Required Follow-Up — an edge case, not a defect). |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | Reconstructed the whole state — branch, both commits, exact commands, the two mutation probes, the environment quirks, why `assemble check` and not `build`, what is unverified and why — without the implementer's account. **The `clearStay` = 0 expectation is stated clearly enough that a next session cannot misread the empty column as a failure**: it appears three times, in three registers — `claude-progress.md` "Current Verified State" ("the report schema is now 5 in source and 4 in every install … neither reaches a player until somebody bumps the version"), `session-handoff.md` "Broken Or Unverified" ("the receiver's `clearStay` metric stays at `n = 0` for all 83 rooms until a release happens … **Do not read an empty `clearStay` as this feature failing**"), and the feature's own `notes` ("NOT SHIPPED"). The live box agrees with the prediction exactly: 83 rooms, 0 with `clearStay.n > 0`, 9 with `clear.n > 0`. `session-handoff.md` → "Do Not Touch" leads with the one irreversible mistake and names the test that guards it. `quality-document.md` updated; `clearpoints-001` is set up to start, with the right question pre-asked (whether a weighted total breaks `_real(x, 0, MAX_CLEARED)` and therefore needs the receiver to move first). |

**Total: 14 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **ACCEPT** — 14/14, no category 0, Correctness / Verification / Regression all 2.

The thing this review was pointed at hardest — `SCHEMA` being 5 rather than 4 — is correct, is
guarded by a test written as `>= 5` so a later bump passes and a revert does not, and the guard was
proven by reverting the constant and watching two tests fail. The receiver half is not merely merged
but deployed and answering with `stayAnchorSchema: 5`. There is no silent-contamination path left
open on this pair.

## Required Follow-Up

- Missing evidence (all three named by the session, none a deduction):
  - **The wiring has no test and cannot have one here.** `ContributionTracker.tick` needs a
    `Minecraft` and a `MapItemSavedData`; no test file in this repository imports `net.minecraft` at
    all. That `tick` calls `onPresence` once per member per tick with the run clock is established by
    reading `ContributionTracker.kt:344-356` and by nothing else. Closing it needs either a mixin-free
    seam or the debug session file.
  - **The gap tolerance is calibrated, not measured.** `MIN_TICKS = 20` covers `PartyTracker`'s
    documented 10-20 tick roster-skew window with exactly zero margin. A real blackout of 21+ ticks
    splits a stay and anchors that room about a second late. First real session file settles it.
  - **`anchorOnClear`'s real-floor frequency is unknown.** `room_anchored` and the `cleared` event's
    `anchoredOnClear` flag were added to answer it; that is the first thing to read in an arriving
    session file.
- Worth recording, for whoever next touches `TrackedRoom` (not blocking, not a defect):
  1. `stay.ticks` counts **sightings**, and the gap test is per-gap rather than cumulative. A member
     sighted once every 20 ticks therefore accumulates a qualifying stay whose `start` is far older
     than `MIN_TICKS`, so the anchor could reach back further than the KDoc's reasoning implies. It
     needs sustained repeated blackouts to happen at all, so it is not reachable in the one-death case
     the tolerance exists for — but the KDoc should say ticks are sightings.
  2. `anchorOnClear`'s "under a second" is really "at most one second" — `0..MIN_TICKS` is inclusive.
- **Carried forward from the `residue-001` review, which this file overwrote.** Those findings now
  live only in git history at `b30496f`, and neither reached `feature_list.json`:
  1. The comment at `RoomHistory.kt:260-261` is still false. It claims the figure "comes back already
     rounded … and the chat can no longer show a `0.00` row"; under the old `> 0.01` guard a `0.00`
     row was already impossible, and the actual effect is that the print threshold *loosened* from
     `0.01` to `0.005`. Unreachable in practice, wrong as documentation. Untouched by this session.
  2. Optional: `ContributionTracker.settle`'s KDoc argues rounding against the old clamp rather than
     against the symmetric threshold that was the real alternative.
  3. `init.sh` still prints `./gradlew build` as the full verification command, which contradicts
     `session-handoff.md` → "Do Not Touch" and would overwrite the released `dist/` jar. A session
     reading only `init.sh` will trip it. Worth making the two agree.
- Process note: this file is overwritten per evaluation, so an evaluator's non-blocking findings
  vanish from the working tree at the next review. Either carry them into `feature_list.json` as
  `CLAUDE.md`'s "record it as a new feature" path prescribes, or append rather than overwrite.
- Next review trigger: `clearpoints-001`. Check first whether a weighted point total can exceed
  `roomsCleared` — the receiver validates `unattributed` as `_real(x, 0, MAX_CLEARED)` — because if it
  can, that is a schema change, one feature per repository, and **the receiver goes first**.
