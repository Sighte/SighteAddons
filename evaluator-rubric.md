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

**Evaluated:** `recordowner-001` — "A record is only yours when the work was yours".
**Branch:** `recordowner-001` at `4e2db23`, code at `9a00325`, off `main` at `8431597`. Not pushed,
not merged. For the commit count run `git rev-list --count 8431597..4e2db23` — deliberately not
transcribed, and that discipline has now held across seven features.
**Evaluated on:** 2026-08-15, by a session that implemented none of this work, in the main checkout
(tree clean, no other session running, per the delegation). This is the **first** grading of
`recordowner-001`.

**Every number the entry records reproduced, and all ten of its mutation probes reproduced to the
exact failing test name.** The implementation matches the user's four decisions of 2026-08-15
literally, including the one that is easiest to violate quietly — neither metric moved, `record()`
is untouched and both callers hand it exactly what they handed it before.

**Two things this pass found that the session did not, and they are why the verdict is not Accept:**

1. **The cost of decision 1 is measurable today, from a file committed in this repository, and it is
   not what the artifacts say it is.** Every artifact states the cost as "party secret records become
   rare". Replayed against `docs/evidence/session-1786719912927/session-excerpt.jsonl` — the one real
   floor in this repository, **solo and deathless** — `ownSecretRun` drops **4 of that floor's 5
   secret-run records**. See finding 1. This does not make the code wrong; it makes the sentence the
   user consented to wrong, and a release is queued behind this verdict.
2. **A second guard-in-name-only survived, in the same predicate as probe H.** Deleting the
   `MIN_TICKS` floor from `ownClear` fails **zero** of the 211 tests, and the test whose name claims
   to cover it (`the presence floor is enforced by the gate itself`) is refused by a different half
   of the gate. See finding 2. The session found this species once, wrote it up as the measurement
   that changed the work, and did not sweep for the rest of it.

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `bash init.sh` | `==> BASELINE: PASSING`, exit 0 |
| `./gradlew test --tests 'sighteaddons.SecretRunTest' --tests 'sighteaddons.RoomHistoryTest' --tests 'sighteaddons.ContributionTrackerTest' --rerun-tasks` (the feature's own `verification_command`, verbatim) | `BUILD SUCCESSFUL in 8s`. **SecretRunTest 11 / RoomHistoryTest 12 / ContributionTrackerTest 54, 0 failures, 0 errors, 0 skipped**, read from `build/test-results/test/TEST-*.xml`. Matches the record exactly |
| `./gradlew test --rerun-tasks`, summing `build/test-results/test/TEST-*.xml` | **`classes 15 tests 211 failures 0 errors 0 skipped 0`.** Per class: ChatEvents 11, ContributionTracker 54, DebugLog 2, DungeonGrid 4, DungeonSession 7, PartyTracker 14, Pseudonym 8, RecordTable 9, RoomDatabase 8, RoomHistory 12, RoomStats 17, RunReport 32, SecretRun 11, SecretTracker 8, TelemetryUpload 14 |
| Branch-point count, **derived rather than transcribed**: `git show main:<file> \| grep -c '@Test'` over all 15 test files | `main` at `8431597` sums to **193** in the same 15 classes. The delta is **exactly** `SecretRunTest` 6→11, `RoomHistoryTest` 5→12, `ContributionTrackerTest` 48→54. No class added, none removed, none renamed |
| `md5sum dist/*.jar; ./gradlew assemble check; md5sum dist/*.jar` | `BUILD SUCCESSFUL in 5s`; md5 **`e8cd7099034dd3475dbc8069be3c433e` identical both sides**; `git status --short dist/ gradle.properties` **empty**; `mod_version=0.11.0`; `RunReport.kt:67 SCHEMA = 5`. `./gradlew build` was **not** run |
| `python build/keydiff.py` | `SCHEMA 5`, `run keys written 17`, `room keys written 17`, **four empty sets, both directions, both objects**, `KEYDIFF: CLEAN`. I read the script: it parses `RUN_KEYS`/`RUN_OPTIONAL`/`ROOM_KEYS`/`ROOM_OPTIONAL` out of `SighteAddonServerside/ingest.py` and `obj.add(Property)?("…"` out of `RunReport.kt`, and writes nothing |
| The stronger form of the same check | `git diff main..HEAD --name-only` is **11 files** and `RunReport.kt` is **not among them**. `rooms.json`, `gradle.properties`, `dist/`, `evaluator-rubric.md` likewise absent. `SighteAddonServerside` is on `master` at `018cee5` with `git status --short` **empty** — read, not written |
| `bash build/runprobes.sh` — the session's ten probes | **All ten caught, every failure count as recorded**, and the failing test names are the ones that name the property. A 3, B 2, C 5, D 2, E 1, F 1, G 2, H 1, I 1, J 1. `git status --porcelain src/` **empty** after the ten `git checkout` restores |
| **Probe H specifically**, the one the session reports as having passed all 211 tests at `5b670ca` | Now **caught**, failing exactly `RoomHistoryTest > being there from the start is not enough if somebody else did the room`. The rebuild at `9a00325` is real |
| **Ten more probes I wrote myself** (`build/evalprobe.py`, anchors rebuilt from the source at `4e2db23`, each asserting its anchor matched exactly once **and** re-reading the file to confirm the mutation landed) | Eight caught, two not, and one of the two is a real hole — see the next section |
| Deletion / loosening check | `git diff main..HEAD -- src/test/ \| grep -E '^-[^-]'` returns **one line**, the one-line `room()` helper, replaced by a documented `room(firstBar: Int = 0)`. **`0`** `@Test` lines removed and **`0`** assertion lines removed anywhere under `src/`. The six pre-existing `SecretRunTest` cases keep their bodies verbatim |
| Feature-status diff, `main` vs `HEAD` | **Only `recordowner-001` moved**, and it moved by being added (absent → `passing`). Nothing downgraded, removed or quietly reworded |

### The ten probes I added, and the one hole they found

The orchestrator's instruction was the right one: *a gate whose two conditions are never tested
independently is a gate with one condition*. The session probed ten mutations; probes G and H
between them do not isolate every half of `ownClear`, and I and J do not isolate every half of
`presentFromStart`. So I deleted each remaining half separately.

| Probe | Mutation | Result |
| --- | --- | --- |
| **K** | `ownClear`'s `MIN_TICKS` floor deleted | **NOT CAUGHT — 211 pass.** See finding 2 |
| L | `room.clearedAtTick ?: return false` → `?: 0` (the half probe G conflates with M) | caught, 1 |
| M | `return room.presentFromStart(self, at)` → `return true`, **alone** | caught, 1 |
| N | `stays[player] ?: return false` → `?: return true` | caught, 1 |
| O | `if (stay.start > anchor) return false` deleted (the arrival half, alone) | caught, 3 |
| P | the pre-existing `previous != 0` half of `onSecret`'s guard deleted | caught, 1 |
| Q | the `max < 2` half deleted | **NOT CAUGHT** — benign: `if (found >= max)` three lines below catches the single-secret room, so the clause is redundant. Pre-existing, not this feature's |
| R | `readBar`'s rise test deleted | caught, 2 |
| **S** | `onRoomCleared` ignores its own gate (`val mine = true`) | **NOT CAUGHT — as declared.** Corroborates the ceiling |
| **T** | `onSecretRun` ignores its own gate (`val mine = true`) | **NOT CAUGHT — as declared.** Corroborates the ceiling |

S and T are not deductions — they are the session's own stated ceiling, **measured**. The wiring
really is unguarded and the entry says so in as many words rather than implying otherwise. That is
the honest shape, and it is worth more than a claim.

### The four decisions, checked one at a time against the source

1. **`ownSecrets == secretsFound`.** `RoomHistory.ownSecretRun` is
   `room.secretsFound > 0 && room.ownSecrets == room.secretsFound`. Both halves probed (E, F), each
   failing exactly one named case. Implemented at the user's strict end, not softened.
2. **Present from the anchor AND top by ticks, both.** `ownClear` requires `self == topPlayer`,
   `ticks >= MIN_TICKS`, a non-null `clearedAtTick`, and `presentFromStart`. Probes H, G, and my M
   and L take the halves apart; the `MIN_TICKS` half is the one nothing guards (finding 2).
   `presentFromStart`'s staleness tolerance really is `onPresence`'s: `onPresence` starts a new stay
   at `at - stay.lastSeen - 1 > MIN_TICKS` and `presentFromStart` accepts at `<= MIN_TICKS`. Exactly
   complementary — I read both, they are not two notions of "still here".
3. **`history.jsonl` untouched, nothing reinterpreted.** No new `kind` (`CLEAR = "clear"`,
   `SECRETS = "secretrun"`, both unchanged), no version field, no rewrite path, no migration. The
   file's line schema is not in the diff at all.
4. **Neither metric redefined — this is the one I attacked hardest and it holds.** `record()` is
   byte-identical to `main`. `onRoomCleared` still passes `ownTicks`, which is still
   `room.ticks[self]`; `onSecretRun` still passes `ticks`, which is still `room.secretRunTicks`.
   Every line the branch adds is a `Boolean` in front of a call, never inside one. Old and new lines
   of one `kind` stay comparable.

Also checked and clean: `RunReport.SCHEMA` still 5, no report field added, renamed or removed,
`rooms.json` untouched, `mod_version` untouched, `dist/` untouched, receiver read and never written.
**No Correctness-0 condition is met**, and I established that mechanically rather than inheriting it.

### `readBar`, and whether the ordering is guarded by a test rather than by argument

It is. `SecretRunTest > an untouched room is observed even though nothing rose` asserts
`reading.first == true` **and** `reading.rose == false` on a `0/10`, which is precisely the assertion
a rise-first ordering cannot satisfy — and probe C, the reordering written the way a tidy-up would
write it, fails **5** tests, three of which predate this feature. The design change from two
statements to one function is therefore not merely argued, it is the reason a test can hold the
ordering at all.

**A genuine run starting at `0/10` still records**, checked at both levels: the predicate seam
(`readBar(0)` → `readBar(1)` → `onSecret(previous = 0, found = 1, …)` → `STARTED`, asserted in that
same case), and by reading `SecretTracker.onActionBar`, where the `0/10` returns at `if (!reading.rose)`
after the observation has already landed. `readBar` is also the **only** writer of `secretsFound` in
`src/main/` — I grepped; the other writes are all in tests.

### Category scores

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | **All four of the user's decisions are implemented literally, including the one most likely to be violated quietly.** Decision 4 is the sharp one and it holds mechanically: `record()` is unchanged, `ownTicks` is still `room.ticks[self]`, `ticks` is still `room.secretRunTicks`, and every line added is a `Boolean` in front of a call rather than inside one — so no `clear` and no `secretrun` line changes what its number means, and old lines stay comparable with new ones. Decision 3 holds: no new `kind`, no version field, no rewrite, no migration; the file's schema is not in the diff. Decision 1 is `secretsFound > 0 && ownSecrets == secretsFound`, both halves probed. Decision 2 is all four conditions, and `presentFromStart`'s tolerance is provably `onPresence`'s complement rather than a second quieter notion. **No Correctness-0 condition is met and I established it rather than inheriting it**: `RunReport.kt` is absent from `git diff --name-only main..HEAD`, `SCHEMA` is 5, `keydiff` is CLEAN at 17/17 with four empty sets, and the receiver is on `master` at `018cee5` with an empty `git status`. The two remaining unknowns are consequences of the spec rather than deviations from it, and both are conservative in direction — a missing record, never a wrong one — but finding 1 puts a measured number on one of them that the user has not seen. |
| Verification | Did the required checks actually run, with evidence? | 1 | **Every recorded number reproduced** — 11/12/54 on the `verification_command`, 211 in 15 classes with 0/0/0, jar md5 `e8cd7099034dd3475dbc8069be3c433e` identical either side of `assemble check`, `KEYDIFF: CLEAN` 17/17, `BASELINE: PASSING`, and **all ten probes to the exact failing test name and the exact failure count**, including probe H, which the record says passed at `5b670ca` and which is now caught. **Nothing was deleted or loosened**: one removed line in all of `src/test/`, a helper, and `0` `@Test` and `0` assertion lines removed anywhere — so the Verification-0 condition does not apply. **Held at 1 for two reasons, both of the same species.** (a) Probe K: the `MIN_TICKS` half of `ownClear` is guarded by **nothing** — deleting it passes 211 — while a test named `the presence floor is enforced by the gate itself` and a KDoc claiming the predicate is "total" both say otherwise. The session identified exactly this failure as the measurement that changed its work, and then did not sweep the same predicate for more of it. (b) The headline cost the entry states — "party secret records become rare" — was checkable against a **committed repository artifact** and is wrong in scope by that artifact's own numbers (finding 1). The unverified *paths* are named honestly and probes S and T confirm the ceiling is real; what was not verified is a claim the session made about its own consequence, in the one place a release note will repeat it. |
| Regression | Are previously passing features still passing? | 2 | Whole suite green: `classes 15 tests 211 failures 0 errors 0 skipped 0`, `--rerun-tasks`. `main` at `8431597` is **193 in the same 15**, derived by counting `@Test` out of `git show main:<file>` rather than read from the record, and the delta is exactly the three named classes. No class added, removed or renamed. **Strictly additive is a diff here, not a claim**: one removed line in `src/test/`, and it is the `room()` helper whose replacement `room(firstBar: Int = 0)` supplies `readBar(0)` — the state all six pre-existing cases already meant, with their bodies unchanged, which I confirmed by reading them. `assemble check` `BUILD SUCCESSFUL` with the jar byte-identical either side and no version bump, so the release gate never engaged. Feature statuses diffed against `main`: **only `recordowner-001` moved**, by being added; nothing downgraded, removed or reworded. `SecretTracker.onActionBar`'s rewrite is the one place a regression could hide, and the mismatch guard, the trusted-max check and the attribution block below it are untouched — the three replaced statements are `readBar`'s body verbatim, in the one order the tests pin. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | Eleven files, four under `src/main/`, three under `src/test/`, four artifacts. `RunReport.kt`, `rooms.json`, `gradle.properties`, `dist/` and `evaluator-rubric.md` are all absent from the branch; no version bump; `./gradlew build` never run; the receiver read and never written. **The boundary held where crossing it would have looked like completeness.** The tie in `topPlayer` is left arbitrary and *recorded* as a decision the user has not been asked for, rather than pinned as a fifth decision. The bogus records already in `history.jsonl` are left alone, which is decision 3 taken at its word. The informational chat line and `Config.ownPbsOnly` are untouched. `ClearPopup.show` was in scope by the delegation and the change is two `if`s at the call sites plus a KDoc correction, not a rewrite. Discovered-but-not-fixed work (`floorname-001`) is recorded as an entry rather than fixed inline, which is the operating loop's rule. |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed by a session with no prior context: `init.sh` green first attempt, no environment repair beyond what `session-handoff.md` documents. Twenty mutations applied and restored — the session's ten and my ten — with `git status --porcelain src/` **empty** after every one, and no probe silently failed to apply (the session's script asserts its anchor matches exactly once; mine asserts that **and** re-reads the file). **The handoff's quirks are load-bearing and every one I used held as written**: `python` resolves and `python3` does not; CRLF anchors need `\r\n` translation and `encoding="utf-8"` or the read throws on the em dashes; counts come from `build/test-results/test/*.xml` and not the console; `assemble check` and never `build`; commit the feature before probing so `git checkout` restores from the index. `build/` is gitignored and this time the scripts had survived — that is luck, not design, and it is follow-up 6, now five sessions old. |
| Maintainability | Is the code and documentation clear enough for the next session? | 1 | **The code and its KDocs are the best in this repository.** Every decision that looks over-cautious is argued where it is made, with the user's words quoted at the two gates that answer them; `readBar`'s KDoc states the ordering trap and why the function exists; `record`'s new paragraph states the append-only constraint at the exact place a future session would violate it; and the `Do Not Touch` list names each load-bearing property with the measured consequence of removing it. **Held at 1 for four documentation inaccuracies, one of them the species this feature was written to eliminate.** (a) `RoomHistoryTest > the presence floor is enforced by the gate itself` does not test the presence floor — the fixture is refused by `presentFromStart`'s staleness half, and deleting the floor passes 211 tests. A name that claims a guard is worse than no test. (b) The cost of decision 1 is stated as "party secret records become rare" in the entry, the KDoc, the handoff, the progress log and the quality table; the committed solo floor says otherwise (finding 1). (c) `onSecretRun`'s KDoc says "The announcement stays either way" — true on the default, false when `Config.ownPbsOnly` is on, where a party run that used to be announced because it beat the record is now silent. (d) `quality-document.md`'s **current** `scoring` row was updated from the superseded copy rather than the current one, so it now reads `RoomStatsTest` (9 cases) against an actual 17 and "layer 2 has never been read on a real install, because nothing writes a cache yet" — both untrue since `scores-fetch-001` merged, and the accurate row is sitting in the superseded block below it. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 1 | I reconstructed the whole state from the artifacts: branch and head, what each of the three commits is for, every command, all ten probes precisely enough to rebuild them, the key diff, why the quality grade did not move, and every unverified path. The commit count is derived rather than transcribed. The failed probe-H run is kept in the evidence rather than tidied away, which is the single best thing in this handover — it is what let me check that the rebuild is real rather than asserted. **Held at 1 because the handoff misdescribes the one resource that would close this feature's ceiling.** `Environment Quirks` says "A `session-<millis>.jsonl` from a real install is the only source of real data, and there is exactly one in the repository" and names `session-1786719912927.jsonl` as "the original"; the directory it names holds **sixteen**, several of them party floors carrying `death`, `revive`, `roster_skew` and `chat_secret` events. `Broken Or Unverified` reasserts "the one real run is solo and deathless… the death path has never been exercised" — which `main`'s own `dc8d504`, *"Record what three real party floors said about chat"*, already contradicts. Nothing here blocked my reconstruction, so this is not a 0; but a next session reading it will not go looking for the fifteen other floors, and those floors are how three of this pass's findings were settled without playing anything. |

**Total: 11 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **REVISE** — 11/14, no category 0, but Verification is 1, so the Accept bar is not met. Not
overridden.

**Nothing here is broken and no evidence failed to reproduce.** Every recorded number came back
exactly, all ten probes came back to the failing test name, and the four decisions are implemented
literally including the one that is easiest to violate quietly. On the code alone this is Accept
work. It is Revise because a release is queued behind the verdict and two things should be in front
of the user before the jar is cut, both of them cheap:

**On `passing` versus `in_progress` or `blocked`: `passing` is the right status, and I want to be
precise about why, because the reasoning is not the one the entry gives.** `CLAUDE.md`'s bar is that
a feature depending on a real *dungeon run* is `blocked`. This feature's *decisions* do not — they
are pure predicates on `TrackedRoom` and `RoomHistory`, driven directly, with twenty mutations
between the session's ten and mine measuring that they can fail. Its *wiring* does, genuinely and
unlike `scores-fetch-001`, whose ceiling the previous evaluator correctly demolished: `onRoomCleared`,
`onSecretRun` and `onActionBar` need a calibrated dungeon, a resolved room and a live `Minecraft`,
and `./gradlew runClient` at a title screen reaches none of them. Probes S and T confirm that wiring
is guarded by nothing, which is what the entry already says. That matches the `runloss-001` and
`floorloss-001` precedent, both `passing`, and the entry names the unverified lines individually
rather than implying they were tested. **Keep `passing`. Do not move it to `blocked`** — the status is
not what is wrong here.

**What is wrong is that the ceiling is smaller than the entry believes, and the cost is larger.**
Both are settled below without playing a floor.

**On the release: this feature does not owe the receiver anything** — no field, no key, no schema
move, verified mechanically in both directions. It owes the release notes the lines the handoff
already lists, **plus the number in finding 1**.

## Required Follow-Up

Findings 1-4 are new this pass. 5-12 are carried and were re-checked at `4e2db23` by opening the file
rather than by trusting the previous rubric; 13-22 are carried without re-verification and are marked
as such. **Turning any of these into work is the orchestrator's call, not mine.**

### New this pass

1. **The cost of decision 1 is 4 records in 5 on a solo floor, not "party records become rare" — and
   the file that says so is committed in this repository.** `ownSecretRun` is
   `ownSecrets == secretsFound`, and `secret_run_done` in the debug log carries exactly those two
   numbers (`"secrets" to bar.max`, `"own" to room.ownSecrets`, and at `DONE` `secretsFound == max`),
   so the gate can be replayed against real data with one command. Against
   `docs/evidence/session-1786719912927/session-excerpt.jsonl` — **solo, deathless, the floor this
   repository already treats as its ground truth** — the five completed runs are `Atlas 4/6`,
   `New Trap 2/3`, `Slime 2/5`, `Chains 2/2`, `Pipes 5/7`. **Only `Chains` survives the gate.** Four
   of five records disappear on a floor where, by construction, every secret *was* the local
   player's; what fails is not ownership but attribution — `ownSecrets` counts a click inside
   `OWN_WINDOW` or a wither-essence chat line and nothing else. Corroborated, and worse, on the
   sixteen real session logs in the directory `session-handoff.md` names: **9 of 80 completed secret
   runs have `own == secrets`**, and split by roster it is **2 of 23 on single-member sessions** and
   7 of 57 on party sessions. (Both figures are *upper* bounds on what survives — some of those runs
   would not start at all under the new `firstBarFound` gate.) The user was shown a trade-off scoped
   to parties and priced as "rare"; the measured effect is roughly nine in ten records gone, solo
   included. **The code is not wrong — decision 1 is the user's and this implements it exactly.** The
   fix is to put the number in front of them before the jar is cut, and to correct the sentence in
   the entry's notes, `ownSecretRun`'s KDoc, `session-handoff.md`, `claude-progress.md` and the
   `history and records` quality row. If the user then wants the softer gate they refused, that is a
   new decision made on real numbers rather than a re-litigation of the old one. **This is the
   highest-value item in this document and it costs one `python` one-liner.**

2. **A second guard in name only, in the same predicate as probe H.** Delete
   `if ((room.ticks[self] ?: 0) < ContributionTracker.MIN_TICKS) return false` from
   `RoomHistory.ownClear` and **all 211 tests pass** (my probe K). The case that carries the name,
   `RoomHistoryTest > the presence floor is enforced by the gate itself`, gives the local player
   `min - 1` ticks starting at tick 1000 and asks about tick 1080 — so `presentFromStart`'s staleness
   half refuses it 61 ticks out and the floor is never the thing under test. `ownClear`'s KDoc claims
   the opposite in as many words: *"The `MIN_TICKS` floor stays as well, so the predicate is total
   rather than relying on the caller having filtered `topPlayer` out of an already-eligible map."*
   **No behaviour is wrong today** — `onRoomCleared` filters `eligible` before computing `topPlayer`,
   so `self == topPlayer` already implies eligibility, which is exactly why the floor is invisible to
   every fixture. But the claim of totality is the reason the floor is there, and nothing holds it.
   Either build a fixture that isolates it (self is `topPlayer`, `presentFromStart` true, ticks below
   the floor — reachable via `anchorOnClear`), or delete the line and say in the KDoc that the caller
   owns the floor. Do not leave a test whose name asserts a guard it does not apply; that is the
   failure this whole feature was written to remove, and the session's own probe-H write-up is the
   argument for fixing it.

3. **The feature's ceiling is real but smaller than stated, and sixteen real floors are sitting on
   this machine.** `session-handoff.md`'s `Environment Quirks` says *"A `session-<millis>.jsonl` from
   a real install is the only source of real data, and there is exactly one in the repository"* and
   names `session-1786719912927.jsonl` as "the original". The directory it gives holds **sixteen**
   session files — including party floors with `death`, `revive`, `roster_skew`, `chat_secret`,
   `puzzle_solved` and `run_report` events — and `main`'s own `dc8d504` already analysed three of
   them for `chat-001`. Meanwhile `Broken Or Unverified` still says *"the one real run is solo and
   deathless… the death path has never been exercised"*, and `Next Best Step` asks the user for a
   party floor with a death as the highest-value input on the board. **Some of what is being asked
   for has already been played.** Concretely, one thing this feature calls unobservable is partly
   answered there already: `session-1786567867893.jsonl` at `t=137` carries
   `secret_room_mismatch … barFound: 0`, which is a real Hypixel action bar reporting **zero**
   secrets found — evidence that the `firstBarFound == 0` path is reachable at all, which is the
   assumption the entire secret-run half rests on and which the entry lists as its falsification
   criterion. Correct the two handoff paragraphs, and mine the existing logs before asking for
   another floor. What genuinely still needs a live client is narrower: `secret_room_first_bar` on a
   *trusted* bar, and the four wiring lines (probes S and T).

4. **`onSecretRun`'s KDoc over-claims on one path.** *"The announcement stays either way, and it
   carries both counts, so a run somebody else did most of is still shown — it is simply not filed."*
   With `Config.ownPbsOnly` on, `pb` is now `null` for every run the gate refuses, so
   `if (Config.ownPbsOnly && pb == null) return` suppresses the announcement that previously appeared
   whenever the party's run beat the stored record. The default is `false`, so most installs are
   unaffected and the narrowing is arguably what "own PBs only" should have meant — but the sentence
   is unqualified and a reader will take it for coverage. One clause. The same applies, more mildly,
   to `onRoomCleared`'s "`Config.ownPbsOnly` is what governs how much of it you see", which is
   accurate but no longer complete.

### Carried forward — re-verified still open at `4e2db23`

5. **`quality-document.md`'s current `scoring` row was updated from the stale copy.** The top block is
   the current block by the handoff's own convention, and its `scoring` row now reads
   `RoomStatsTest` (9 cases) against an actual 17, and *"layer 2 has never been read on a real
   install, because nothing writes a cache yet"* — untrue since `scores-fetch-001` merged. The
   accurate row, with 17 cases and the layer-1 description, is in the **superseded** block below it.
   This session inherited the mis-ordering and then dated the stale row 2026-08-14 while bumping
   `ContributionTrackerTest` 48 → 54 in it, which makes it look current. Copy the right row up.

6. **Previous follow-ups 3/6/10 are one problem and it is now five sessions old.**
   `git ls-files | grep -c keydiff` → **`0`**; `build/` is gitignored. `build/keydiff.py`,
   `build/recordprobe.py` and `build/runprobes.sh` all happened to survive into this pass, which is
   luck — the previous evaluator had to rewrite `keydiff.py` from scratch, as did four sessions
   before it. Either give the repository a committed, not-collected home for probe scripts (a
   `probe/` source set, or `build/`-equivalent scripts tracked under `tools/`) or stop recording
   probe scripts as re-runnable evidence. The receiver's `probe_readonly.py` is the proven shape.

7. **Previous follow-up 2 is still open.** The `/sa` switch labelled `upload run reports`
   (`SettingsScreen.kt:288`) also decides whether the mod *receives* its room weights, and nothing a
   player can read says so. Unchanged by this branch.

8. **Previous follow-up 8/1 is still open.** `PartyTracker.kt:183,194` still cites "NoammAddons"
   without naming which of the two repositories, and the mod citation is the half that cannot be
   rechecked from a jar.

9. **Previous follow-up 9/2 is still open.** `party-001`'s `notes` still do not mention the
   `map.decorations.toList()` snapshot — `snapshot`, `live view` and `toList` are all absent from the
   field, checked.

10. **Previous follow-up 11/4 is still open.** `deconame-001`'s `verification_command` is still
    `./gradlew test --tests 'sighteaddons.DebugLogTest'`, vacuously green. Defused only by the entry
    being `not_started`.

11. **Previous follow-up 13/6 is still open.** `chat-001`'s `verification_command` widening still has
    no durable trace: `grep -ni widen claude-progress.md` returns two hits and **neither is about
    `chat-001`** (one is this session's `keydiff` regex note, one is `floorname-001`'s).

12. **Previous follow-ups 14/7 and 17/10 are still open.** `ChatEvents.kt:129` still says
    `DungeonChatFilter` "has no other `found a` shape". `ContributionTracker.kt:370` still has
    `MIN_TICKS = 20` against a documented worst-case roster-skew blackout of 20 — `clear-001`'s
    zero-margin note, which **the sixteen real logs may now be able to close**: several carry
    `roster_skew` events (up to 26 in one session). See finding 3.

### Carried without re-verification this pass

13-22. Previous follow-ups 5, 12, 15, 16, 18, 19, 20 and the five rescued from `runloss-001`'s rubric
(the `disconnect` bytecode offset off by two; `RunReport.uploader`'s "very next thing"; the DISCONNECT
cross-thread read of `visitedRooms()`; `runloss-001`'s `verification_manual` step 3; the third case
where old and new differ). `runloss-001` has since merged into `main`, so these are now checkable
against this tree — I did not check them, because they are outside this feature's diff and this pass
spent its budget on the two findings above. **Reproduced here so the `evaluator-rubric.md` overwrite
cannot drop them**, which is the structural problem the previous pass raised as its follow-up 7 and
which this file has now demonstrated twice: findings that must survive belong in `feature_list.json`
notes or `claude-progress.md`, not only in a document whose contract is that it is overwritten.

### Remaining unverified paths — all named by the session, none a deduction

- **Neither gate has run in a game.** The four wiring lines (`onRoomCleared` → `ownClear`,
  `onSecretRun` → `ownSecretRun`, `ClearPopup.show` under the same answer, `onActionBar` → `readBar`)
  need a live `Minecraft`. **Measured, not assumed**: my probes S and T mutate two of those lines and
  no test notices. The entry names all four.
- **Whether a *trusted* `0/N` bar is ever read.** The strict form of the secret-run gate rests on it,
  and a real `barFound: 0` exists in the logs but on a *mismatched* room (finding 3). If the answer
  is no, every secret run in the game is discarded — the same catastrophe the reordering would cause,
  reached by another route. The entry names this as its falsification criterion and added
  `secret_room_first_bar` with `untouched` so one floor answers it. Direction of failure is
  conservative.
- **A tie in `topPlayer`** is arbitrary and left that way, recorded rather than inherited silently.
- **The bogus records already in `history.jsonl`** are not repaired, by decision 3.
- Everything carried unchanged: `RoomStats.start()` inside a game; the atomic rename; the weights
  against a real run; `deconame-001`; the wiring of `positions()`; the `RED` checkmark path and every
  pixel of `/sa`; `floorname-001`'s value-domain mismatch, which `keydiff.py` compares key *sets* and
  cannot see.

### Next review trigger

**Finding 1 first, and it is not a feature — it is one `python` one-liner over a committed file and a
sentence in front of the user.** It changes what the release note says and possibly what the user
wants the gate to be. Nothing else here costs so little or matters so much, and a release is queued.

**Then finding 3, before asking the user for another floor.** Sixteen real sessions are on this
machine and the handoff says there is one. Mining them may close `clear-001`'s zero-margin note,
`deconame-001`, the death path and part of `party-001` without anybody playing anything.

**Then finding 2**, which is fifteen minutes and removes a test that lies.

`recordowner-001` is graded at `4e2db23` as a **passing** feature whose acceptance is held at
**Revise** — not because the work is wrong, but because two facts about it are cheaply knowable and
are not yet known. It sits on a branch, unpushed and unmerged; merging and releasing remain the
user's decisions and take the release gate at the top of `CLAUDE.md` with them.
