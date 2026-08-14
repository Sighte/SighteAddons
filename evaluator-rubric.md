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

**Evaluated:** `artifacts-001` — "Commit the real dungeon run as evidence, and retire the statements
that outlived it".
**Branch:** `artifacts-001` at `d7943eb`, off `main` at `9f71b96`. Not pushed, not merged. For the
commit count run `git rev-list --count 9f71b96..d7943eb` — it reads **2**, and it is deliberately not
transcribed into any artifact. That discipline has now held across three features.
**Evaluated on:** 2026-08-14, by a session that implemented none of this work, in a detached worktree
at `d7943eb` (the main checkout was in use and already held the branch, so `git worktree add
../artifacts-wt artifacts-001` is refused — use `--detach <sha>`). This is the **first** grading of
`artifacts-001`.

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `bash docs/evidence/session-1786719912927/readout.sh` (the feature's own `verification_command`, and `ingame-001`'s, run verbatim) | `==> READOUT: OK`, **exit 0**, `grep -c '^ok'` → **35**, `grep -ci fail` → **0**. Matches the recorded 35/0 exactly |
| `./gradlew test --rerun-tasks`, summing `build/test-results/test/TEST-*.xml` | `classes 13 tests 140 skipped 0 failures 0 errors 0` |
| **The same, at `main` (`9f71b96`) in a second throwaway worktree** | `classes 13 tests 140 skipped 0 failures 0 errors 0`. **"Identical to `main`" is measured on both sides here, not inferred from the diff** |
| `git diff 9f71b96..d7943eb -- src/ \| grep -E '^[+-][^+-]' \| grep -vE '^[+-]\s*(\*\|//\|/\*)'` | **empty** (0 lines) |
| `git diff 9f71b96..d7943eb -- src/test/ \| grep -cE '^[+-]\s*@Test'` | **`0`** |
| **Bytecode comparison, `main` vs branch** — `javap -p -c` over all 9 differing `.class` files | **Instruction streams byte-identical for every one.** The `.class` files do differ; `javap -p -c -l` shows every difference is a `line N: M` entry in the `LineNumberTable`, i.e. debug metadata shifted by added KDoc lines. See Correctness — this is a stronger proof than the grep and it confirms it |
| **Probe 1, rebuilt from scratch** — `Cathedral`'s `award` 4.5 → 4.0 in `session-excerpt.jsonl` | `READOUT: FAILED`, **exit 1, exactly 2 assertions failed**: `sum of award points expected 26.25, got 25.75` and `Cathedral, the dearest expected 4.5, got 4.0`. Matches. Restored with `git checkout`; readout back to exit 0 |
| **Probe 2, rebuilt** — deleted line 106, the single `anchoredOnClear":true` line (Duncan) | `READOUT: FAILED`, **exit 1, exactly 7 assertions failed**: excerpt lines 143→142, cleared 10→9, anchored by the fallback 1→0, the fallback room is Duncan 1→0, cleared 6 ticks after entry 1→0, cleared WHITE 8→7, no other cleared room holds 0 8→7. Matches to the count and to the identity of all seven |
| **The excerpt against the original**, `diff <(grep -v player_room "$ORIG") session-excerpt.jsonl` | **IDENTICAL.** The original is present on this machine at the `%APPDATA%` path the handoff names: 220 lines, 77 `player_room`, 143 not. The committed excerpt is *byte-for-byte* the original minus the `player_room` lines — nothing edited, reordered, added or rounded |
| **The 3 sample lines against the original** | All three found verbatim (`grep -Fqx`). `t=20`, `t=24`, `t=4357` — the first two and the last `player_room` line, exactly as the README claims |
| **The full 77 dropped lines** — distinct `player`, distinct `decoIndex` | **1 and 1** (`p-cad01af7`, `0`) — identical to what the 3-line sample asserts. The sample does not understate the stream |
| **The checkmark claim against `rooms.json` directly** | Of the ten cleared rooms, **exactly `Default` and `Hall` carry no `secrets` key**; the other eight are 4, 8, 6, 3, 5, 1, 2, 7. Those two are exactly the two reading `checkmark:30`. **Ten for ten, verified against the database rather than against the excerpt's own `room_identified` lines** |
| **`ingame-001`'s old `verification_command`** — `git show 9f71b96:feature_list.json` | It was `grep -c '"roomName"' run/config/sighteaddons/debug/session-*.jsonl`. `grep -rn roomName src/main/` → six hits, **all a local `val` in `RoomHistory.kt`**; the property it writes at `RoomHistory.kt:294` is `"room"`, and the `room_identified` key is `"name"`. `git check-ignore -v run/...` → `.gitignore:33:run/`. **Confirmed: it could never have returned non-zero, against any file** |
| `./gradlew assemble check`; `md5sum dist/*.jar` before and after | `BUILD SUCCESSFUL`; md5 `b2ebc35ccfeb9cc96134eb3b18f0306f` **identical both sides**; `git status --short dist/ gradle.properties` empty; `mod_version=0.9.0`; `RunReport.kt:66 SCHEMA = 5` |
| `git diff --name-only 9f71b96..d7943eb` | 13 files: 5 artifacts, 4 evidence, `.gitattributes`, 2 source, 2 test. **`RunReport.kt` absent, `rooms.json` absent, `dist/` absent, `gradle.properties` absent** |
| `bash init.sh` | `BASELINE: PASSING`. Its "Full verification command" block prints `./gradlew assemble check` with the `copyToDist`/`cleanDist` reasoning inline |
| Final state after both evidence mutations | `git status --short` **empty** across the whole tree |

### The excerpt, judged as the handoff asked

The handoff singles this out as "the judgement most worth a second pair of eyes". It is an honest
subset, and I could check that harder than the session could record it, because the original file is
still on this machine.

- **It is a subset, not a rewrite.** The committed 143 lines are byte-identical to the original's 143
  non-`player_room` lines, in order. There is no editorial hand in it at all.
- **The dropped lines carry nothing an assertion needs.** Their only keys are `t`, `e`, `player`,
  `cell`, `decoIndex`, `decoType`, `decoX`, `decoY`, `worldX`, `worldZ`. Every assertion in
  `readout.sh` reads `session-excerpt.jsonl` except the two that read the sample, and those two are
  accurate for the whole stream — I checked all 77, not the 3.
- **No identity survives.** The only player token anywhere is `p-cad01af7`. No UUIDs. The place a real
  name would leak is `tab_slot`'s raw `row` field, and it reads `[391] p-cad01af7 ☄ (Berserk L)` —
  pseudonymised by `Pseudonym` before `DebugLog` saw it, exactly as the README claims, so nothing was
  scrubbed after the fact and nothing needed to be.
- **The dropped stream is not the sighting stream**, which is what makes dropping it cheap:
  `player_room` fires on room change (77 lines over 4357 ticks, gaps 4–291), not per tick. The
  `MIN_TICKS` reading rests on `room_anchored`'s own `at` and `t`, both committed and both asserted.
- **The README's provenance claim is self-verifying and I verified it.** It says the writing build
  predates `clearpoints-002` because no `award` event carries `scoresTs`. At `HEAD`,
  `ContributionTracker.kt:727` puts `scoresTs` on every `award`; the run's nine carry none. That is a
  property of the bytes, as claimed.

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | **None of the harness's Correctness-0 conditions is met, and I checked rather than inherited.** `RunReport.kt` is absent from `git diff --name-only 9f71b96..d7943eb` entirely, `SCHEMA` is 5, `mod_version` is 0.9.0. No schema change landed, so none could have landed ahead of the receiver; `SighteAddonServerside` was neither read nor written and nothing here reaches the wire. **The hard constraint — change no runtime behaviour — holds, and I proved it a second way rather than re-running the session's grep.** I compiled `main` and the branch and compared with `javap -p -c`: nine `.class` files differ, and **every one has a byte-identical instruction stream**; `javap -c -l` shows the whole delta is `LineNumberTable` entries shifted by added KDoc lines. That is the mechanical proof the pass claims, obtained independently of the diff filter the session used, and it closes the one hole a comment-grep leaves (a filter that hides a real line because it happens to start with `*`). **The evidence is real and it is a faithful subset**, verified against the original file rather than against its own README — byte-identical minus `player_room`, samples verbatim, census correct in all 17 event kinds. **The checkmark claim holds and it is the substantive new result.** Two of ten clears read GREEN(30); `Default` and `Hall` are exactly the two cleared rooms `rooms.json` gives no secrets; the other eight hold 1–8 and all read WHITE(34). Ten for ten, checked against the database directly — which matters, because `readout.sh`'s own version of that check reads the excerpt's `room_identified` lines and is therefore self-referential. It is the first real evidence `ingame-001` has ever had, and the session correctly limits it: the RED path never occurred. **The old weights reproduce.** All nine `award` figures match the README's arithmetic under `clearpoints-001`'s deleted formula against the real database secret counts, and sum to 26.25. |
| Verification | Did the required checks actually run, with evidence? | 2 | **The `verification_command` re-runs green and the readout is a real verifier, not a description.** 35 `ok`, 0 fail, exit 0; `check()` sets `fail=1` and the script `exit "$fail"`s, so it can and does return non-zero. **Both mutation probes rebuilt from their descriptions and both reproduce exactly** — Cathedral 4.5→4.0 fails 2 (and the session's claim that it is caught "twice, once directly and once through the checksum-like sum" is correct), deleting the one `anchoredOnClear` line fails 7, and all seven are the ones recorded. So the excerpt cannot be quietly trimmed, which is the failure mode a committed evidence file has that no compiler or test would catch. That answers the standing concern in this repository's history: **a verifier that cannot fail is the defect two grading passes were lost to, and this one bites.** **Nothing was removed or loosened.** Zero `@Test` lines touched; every changed line in both test files is KDoc, read individually; `@Test` counts per file identical to `main` (42 and 8); the other eleven test files untouched. So the Verification-0 condition does not apply. **The suite is 140 at `main` and 140 here, both measured cold in separate worktrees** — for a comment-only branch an unchanged count is the correct reading and is itself part of the evidence, since a KDoc edit that moved a fixture would show up there. **Unverified paths are named, not implied.** The party half, the RED checkmark, every `/sa` pixel, the measured half of the scoring model, layer 2, and `tick()`'s wiring are each named — in `session-handoff.md`, in `ingame-001`'s `blocked_reason`, in `quality-document.md`'s promoted rows, and at equal length in the evidence README's own §4. **Nothing implies the dev client reached Hypixel**; the README states the run came from a real install on a debug build of `72e0825` and is emphatic that `HEAD` does not produce its numbers. |
| Regression | Are previously passing features still passing? | 2 | Full suite green from a forced cold run in a fresh worktree: `classes 13 tests 140 skipped 0 failures 0 errors 0`, and **identical to `main` measured independently at `9f71b96`**. `./gradlew assemble check` → `BUILD SUCCESSFUL`; jar md5 `b2ebc35ccfeb9cc96134eb3b18f0306f` before and after and identical; `git status --short dist/ gradle.properties` empty; `bash init.sh` → `BASELINE: PASSING`. Bytecode instruction-identical to `main`, so no previously passing behaviour *can* have regressed. Diffing statuses in `feature_list.json` against `9f71b96`: nothing was downgraded, removed or quietly moved — `artifacts-001` went `in_progress`→`passing`, `runloss-001` was *added* per `CLAUDE.md`'s discovered-work rule, and `ingame-001` stayed `blocked` (see below). `clearpoints-001`'s five named guards and `clear-001`'s cases are inside the green run and untouched. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | Two commits, 13 files, and the boundary held under a real temptation: the pass found a live data-loss bug and **recorded it as `runloss-001` instead of fixing it**, which is both `CLAUDE.md`'s discovered-work rule and the only way the no-runtime-change constraint survives. No version bump, no `dist/` touch, `rooms.json` untouched, `RunReport.kt` untouched, `SighteAddonServerside` neither read nor written. `README.md`'s contributor `./gradlew build` line left alone again — the fifth session to make that call correctly. The one harness-adjacent edit is `.gitattributes` (`*.jsonl text eol=lf`); it is not in `CLAUDE.md`'s protected list, it is necessary — the committed evidence is asserted by line and its bytes must not depend on the checkout — and it is justified in place and in the progress log. |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed cold by a session with no prior context, in a detached worktree. Baseline green first attempt, no repair, no environment fixing beyond what `session-handoff.md` documents. Both evidence mutations applied and reverted; `git status --short` came back empty over the whole tree. The handoff's quirks are correct and load-bearing rather than decorative: **restoring the mutated `.jsonl` with `git checkout` rather than rewriting it from Python is not optional** — `core.autocrlf=true` here and the `*.jsonl` LF pin makes a Python round-trip show as modified; `python` resolves and `python3` does not; `assemble check` and not `build` is right and `init.sh` prints the reasoning itself. One quirk the handoff should gain: the worktree recipe in the delegation brief cannot work while the main checkout holds the branch. |
| Maintainability | Is the code and documentation clear enough for the next session? | 2 | **All four stale statements this pass set out to close are genuinely closed, checked one at a time rather than as a group.** (1) `clearpoints-001`'s note now opens `SUPERSEDED BY clearpoints-002 AT 0d81667 — READ THIS BEFORE THE REST OF THIS NOTE`, and the formula paragraph is retitled `WHAT THE WEIGHTING WAS — SUPERSEDED, PAST TENSE`; the paragraph is kept, which is right, since its load-bearing half (`unattributed` is a count of rooms) is still live. (2) `ingame-001`'s dead cross-reference is not merely deleted — it names what it used to say, names the five deleted constants, and says where the concern moved (the seed table and `TIME_EXPONENT`). That is the better fix. (3) The real-M7 figures now cite committed, asserted evidence in all three places the previous rubric listed, and `readout.sh` turns a future hand-copy drift into a failure rather than a disagreement. (4) `TIME_EXPONENT`'s proxy is named in **both** KDocs that carried the calibration (`ContributionTracker` and `RoomScores`), including the concession that a narrower true `clearStay` spread would *weaken* the argument — an unusually honest way to close that one. Two further statements were retired beyond the four: the impossible `verification_command` and `quality-document.md`'s "nothing here has run against real Hypixel data" preamble. **The two grade promotions are argued from the data and fenced**: room naming and Map reading C→B on real evidence with the version-coupling expiry stated, party held at C with an explicit "this run is not evidence for this domain and must not be read as promoting it", and telemetry deliberately held at B despite the measured loss with the reasoning written out. Nothing new is asserted without a source that I could find. The deductions I considered and did not take are follow-ups 1 and 2 below — both are precision, not drift. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | I reconstructed the entire state from the artifacts alone: branch and head, what each of the two commits is for, every command, both mutation probes precisely enough to rebuild them from their descriptions and hit identical failure counts and identical failing assertions, which figures are asserted and which are descriptive, why the run does not cover the party half, and why `assemble check` and never `build`. The handoff's "Next Best Step" asked for exactly this evaluation, named exactly the probes that matter, and named the excerpt as the judgement most worth a second pair of eyes — which was the right call and is where the most work went. The durable-findings migration **still holds and I checked each rather than trusting it**: `residue-001`'s `settle` KDoc finding, `clear-001`'s three notes (two now closed *in `feature_list.json` itself*, with the measurement, rather than in this file), and `ingame-001`'s cross-references are all present. Nothing was lost across this rewrite, and two items that previously survived only here have now been promoted into the feature list where they belong. |

**Total: 14 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **ACCEPT** — 14/14, no category 0, and Correctness, Verification and Regression all 2. Not
overridden.

**Every number the session recorded reproduced.** 35 assertions ok and 0 failed; the Cathedral probe
fails exactly 2 and the trimmed-excerpt probe exactly 7, with all seven being the seven recorded; 140
tests in 13 classes at both `main` and `HEAD`; jar md5 unchanged; `SCHEMA` 5; `mod_version` 0.9.0.

**`readout.sh` is a verifier and not a description, which is the thing this repository has twice been
burned on.** It fails, it exits non-zero, and it fails *loudly and redundantly* — one edited number
trips two assertions, one deleted line out of 143 trips seven. That redundancy is what makes a
committed evidence file trustworthy, because unlike source it has no compiler and no test reading it.

**The comment-only claim is true, and I proved it a second way.** Rather than re-run the session's own
grep and believe it, I compiled both revisions and compared bytecode: nine class files differ and every
one is instruction-identical, with the entire delta in `LineNumberTable` entries pushed down by added
KDoc lines. A comment-grep can in principle hide a code line; this cannot. For a comment-only branch an
unchanged test count is the correct regression reading, and I confirmed `main`'s 140 by running it
rather than by inferring it from the diff.

**The excerpt is the most honest thing in this pass.** The original session file is still on this
machine, so I did not have to take the subset on trust: the committed 143 lines are byte-identical to
the original's 143 non-`player_room` lines, the three samples are verbatim, the event census is right
in all 17 kinds, and the two assertions computed over the 3-line sample are accurate for all 77. The
only identity anywhere is `p-cad01af7`, including inside the raw tab-list row where a real name would
have leaked if `Pseudonym` had not run first.

**The checkmark result is genuine and it is new.** `Default` and `Hall` are exactly the two cleared
rooms `rooms.json` gives no secrets, and they are exactly the two that read GREEN(30); the other eight
hold 1 to 8 secrets and all read WHITE(34). I checked that against the database itself, not against the
excerpt's own `room_identified` lines, because `readout.sh`'s version of that check is self-referential.
Ten for ten. It is the first evidence `ingame-001` has ever had, and it is correctly fenced — the RED
path never occurred and the session says so.

**On `ingame-001`'s replaced `verification_command`, the session is right and the old one was worse
than useless.** `grep -c '"roomName"'` — `roomName` is a local variable in `RoomHistory.kt` and nothing
else; the JSON property written there is `"room"` and the event key is `"name"`, so the pattern matches
no `DebugLog` output that has ever existed. It ran against `run/`, which `.gitignore:33` excludes and
which only a dev client that cannot log in would populate. A `passing` claim could never have rested on
it. The replacement is a script that asserts 35 figures against a committed file and exits non-zero —
the largest single improvement in this pass.

**Both judgement calls the session flagged rather than made are correct.** Leaving `ingame-001`
`blocked` is what `CLAUDE.md` requires ("a feature that depends on a real dungeon run is `blocked`, not
`passing`"), and the three residual blockers — a party floor, the RED path, the `/sa` screen — are
genuinely unreachable from this repository; `in_progress` would have falsely implied work can continue
here. The narrowed `blocked_reason` is the right shape: it says what was resolved, what is not, and why
none of the remainder is session-work. And recording `runloss-001` rather than fixing it is doubly
right — it is `CLAUDE.md`'s discovered-work rule, and fixing it would have destroyed the one constraint
that makes this pass verifiable at all.

## Required Follow-Up

Nothing here blocks acceptance. None of it is a defect in shipped behaviour, and no receiver change is
owed — nothing on this branch reaches the wire.

### New this pass

1. **The README slightly overstates its own assertion coverage.** It says "Every figure quoted below is
   asserted in that script", but the event census of the *original* 220-line file — 220, 77
   `player_room`, and the per-kind counts — cannot be asserted, because the original is not committed
   and must not be. I verified the census independently against the original and **it is correct in all
   17 event kinds**; the issue is only that a reader is invited to believe `readout.sh` covers it. One
   sentence marking the census as descriptive would close it. Recorded here because the same sentence
   is the pass's central design claim.

2. **The "solo" conclusion is asserted over the 3-line sample, not the 77-line stream.** `distinct
   players in the player_room sample` and `distinct decoIndex in the player_room sample` are honestly
   labelled, and I confirmed the full 77 carry exactly one player and one `decoIndex` — so the
   conclusion is sound. But the assertion cannot catch a stream that disagreed with its own sample. The
   load-bearing guard for "solo" is `roster_skew == 0`, which *is* asserted over the whole excerpt;
   worth saying so in the README so the weight rests where it actually holds.

3. **`readout.sh`'s comment at lines 46–47 calls the input "a per-tick decoration stream".** That is
   true of `PartyTracker`'s internal sampling but not of the committed `player_room-sample.jsonl`,
   whose three lines are 4 and 4333 ticks apart — `player_room` logs on room change (77 lines over
   4357 ticks). A reader checking the comment against the sample will think one of them is wrong.
   Cosmetic, one clause.

4. **The delegation recipe for a worktree does not work on this branch and cost time.** `git worktree
   add ../artifacts-wt artifacts-001` fails with `'artifacts-001' is already used by worktree at
   .../SighteAddonMOD` whenever the main checkout holds the branch. `git worktree add --detach
   ../artifacts-wt <sha>` is the form that works for an evaluator, who wants a fixed commit anyway.
   Worth a line in `session-handoff.md`'s Environment Quirks.

### Carried forward — still open, verified still open rather than assumed

The rubric is overwritten every pass and items have repeatedly had to be rescued by hand. Each of these
was re-checked at `d7943eb`:

5. **The loosened fixture assertion from `clearpoints-002` is still slack.** `ContributionTrackerTest.kt:868`
   sanity-checks `weightOf(expensive) > 2 * weightOf(plain())`. `weightOf(plain())` is `0.75`
   (line 656), so the bound is `1.5` against a fixture actually worth `3.50`. The multiplier could be
   `4` and still hold with margin. Optional; the case's real assertion is unaffected. **Unchanged this
   pass** — and note the comment above it now explains the relativising, which is an improvement.

6. **`residue-001`'s `settle` KDoc still argues against the wrong alternative.** `ContributionTracker.kt:625`
   still reads "the clamp this replaces only ever caught the negative half", when the real alternative
   was a symmetric threshold-to-zero, which would also have caught both signs. Behaviour is right and
   tested; only the justification is aimed at a weaker option than a reader would propose. Still open,
   still one paragraph, still living in `residue-001`'s `notes`.

7. **`clear-001` note (2), the zero-margin gap tolerance, is still genuinely unmeasured** — and the
   session **re-checked this rather than letting a real run be read as closing it**, which is exactly
   right and is worth crediting. `MIN_TICKS` as the gap that may not split a stay is 20 against a
   documented worst-case roster-skew blackout of 20: zero margin. The M7 was solo and deathless, so
   `PartyTracker`'s blackout path was never entered. Needs a party floor with a death.

8. **Notes (1) and (3) of `clear-001` are now closed, and closed in the right place.** Sightings vs
   elapsed ticks is measured (9 of 10 anchors at delta exactly 19, one at 24) and `anchorOnClear`'s
   frequency is measured (1 in 10, Duncan). Both now live in `feature_list.json` with the measurement
   and the artifact path, not in this file. Recorded so a future pass does not re-open them.

9. **One test still stands between the tree and a reintroduced size/kind bonus** (`size and kind are no
   longer paid for directly`, `clearpoints-002`'s probe 7 — "fails 1 and *only* 1"). Not a defect; one
   case with seven exact equalities is a real guard. Carried as context, unchanged by this pass.

10. **The M7 provenance gap from the previous rubric is CLOSED and can stop being carried.** The
    figures that were once relayed prose are now a committed file with an asserting script, and all
    three citations point at it. This item is retired.

11. **`TIME_EXPONENT`'s proxy finding from the previous rubric is CLOSED** — named in both KDocs and in
    the evidence README §7. Retired. It remains true that the exponent rests on `clear` and should be
    revisited once `clearStay` has real samples, which is `scores-fetch-001`.

### Remaining unverified paths, all named by the session, none a deduction

- **Everything party-shaped.** The run was solo and deathless: one player in tab, `decoIndex` 0 across
  all 77 position lines, `roster_skew` zero. `PartyTracker.kt:134`'s decoration-order assumption was
  never put under load. `party-001`, `clear-001` note (2) and half of `ingame-001` all still wait on
  one party floor with a death in it — and `quality-document.md` explicitly refuses to promote the
  party row on this evidence, which is the right instinct.
- **The `RED` checkmark path.** Never occurred in the run. GREEN and WHITE are both now evidenced.
- **Every pixel of the `/sa` screen.** No session file can show it; needs a human to open it.
- **The measured half of the scoring model, entirely.** Every room is on its seed. The M7 does not
  help — it was scored under the *old* formula and its report never reached the box, so it added no
  `clearStay` samples anywhere. Blocked on `scores-fetch-001`, blocked in turn on the receiver.
- **`tick()`'s wiring to `onCleared`/`onPresence` is read, not asserted.** Unchanged and correctly
  distinguished: the missing objects are constructor arguments needing a `Minecraft` and a
  `MapItemSavedData`, so no reflection trick is available. `onCleared` itself is covered.
- **Layer 2 on a real install.** Unchanged; nothing writes a cache until layer 1 exists.
- **The cross-repo reading.** `SighteAddonServerside` was neither read nor written this session.
  Nothing here reaches the wire, so nothing is owed.
- **The dev client still cannot reach Hypixel**, and the session says so in four artifacts. The one
  real file came from a real install on a debug build of `72e0825`, and every artifact quoting its
  numbers now says that `HEAD` does not produce them.

### Next review trigger

`runloss-001`, per the handoff, and I agree it outranks `chat-001`: it is the only entry known to have
destroyed real data, it needs no receiver change and no real dungeon to verify the write path, and every
`clearStay` sample the box is waiting for has to survive it to arrive at all — so it is upstream of
`clearpoints-002`'s measured half being worth anything. Read its notes first: the fix is *when*
`RunReport.write` is called, and whether the player is still resolvable at `DISCONNECT` is something to
**measure** rather than assume. If it touches `RunReport.kt`, diff its fields against `RUN_KEYS` in the
receiver's `ingest.py` first and **the receiver goes first**. Do **not** start `scores-fetch-001`.

This feature needs no further work. It is accepted at `d7943eb`, on a branch, unpushed and unmerged;
merging and releasing remain the user's decisions and take the release gate at the top of `CLAUDE.md`
with them. The release notes now owe six things, per the handoff's list plus this pass: the schema moved
to 5, older installs are unaffected because the receiver still accepts 4, room points are no longer
flat, room points changed meaning so old and new standings are not comparable, and — if `runloss-001`
lands first — that runs ended by quitting from inside a floor used to be discarded.
