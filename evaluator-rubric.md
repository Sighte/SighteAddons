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

**Evaluated:** `chat-001` — "Read the events Hypixel puts in chat".
**Branch:** `chat-001` at `936e233`, code at `382c2c2`, off `main` at `a6dc629`. Not pushed, not
merged. For the commit count run `git rev-list --count a6dc629..936e233` — it is deliberately not
transcribed here, and that discipline has now held across four features.
**Evaluated on:** 2026-08-14, by a session that implemented none of this work, in a detached worktree
at `936e233` (`git worktree add --detach ../chat-wt 936e233`; the plain `add <path> <branch>` form is
still refused while the main checkout holds the branch — see follow-up 8). This is the **first**
grading of `chat-001`.

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `./gradlew test --tests 'sighteaddons.ChatEventsTest' --tests 'sighteaddons.SecretTrackerTest' --tests 'sighteaddons.ContributionTrackerTest'` (the feature's own `verification_command`, verbatim) | `BUILD SUCCESSFUL`. **ChatEventsTest 11, SecretTrackerTest 8, ContributionTrackerTest 48, 0 failures, 0 errors.** Matches the recorded 11/8/48 exactly |
| `./gradlew test --rerun-tasks`, summing `build/test-results/test/TEST-*.xml`, cold in a fresh worktree | **`classes 14 tests 160 skipped 0 failures 0 errors 0`.** Matches. `main` at `a6dc629` is 13/140, and the delta is exactly the 20 added tests |
| `./gradlew assemble check`; `md5sum dist/*.jar` before and after | `BUILD SUCCESSFUL`; md5 **`b2ebc35ccfeb9cc96134eb3b18f0306f` identical both sides**; `git status --short dist/ gradle.properties` empty; `mod_version=0.9.0`; `RunReport.kt:66 SCHEMA = 5` |
| `git diff a6dc629..936e233 -- src/main/kotlin/sighteaddons/RunReport.kt \| wc -l` | **`0`.** `RunReport.kt` is absent from the branch entirely, as are `rooms.json`, `dist/` and `gradle.properties` |
| `bash init.sh` | `==> BASELINE: PASSING` |
| **The paired check, rebuilt mechanically from scratch** — my own script extracting every `addProperty`/`add` key from `RunReport.build()` and `room()` and diffing against `RUN_KEYS`/`RUN_OPTIONAL`/`ROOM_KEYS`/`ROOM_OPTIONAL` parsed out of `SighteAddonServerside/ingest.py:137-159` | **Four empty sets, both directions.** The one apparent mismatch my first pass threw up, `${it.livingClass} ${it.livingLevel}`, is `RunReport.kt:254` `classes.add(...)` — a **JsonArray element, not an object key** — and my regex over-matched. Excluding it: nothing the receiver would reject, nothing required that the mod omits. **`chat-001` writes no new field and is genuinely not paired** |
| **`/ingest` validates the filename and never the body** — `ingest.py:538-549` | Confirmed. `store_session` does `if not SESSION.fullmatch(name): return self.reply(400)` and then `tmp.write_bytes(body)`. The body is never parsed. `store_run` by contrast `json.loads`es and validates. A grep for any event-type reader across `ingest.py` returns **nothing**. So a new debug event kind cannot be rejected, exactly as claimed |
| **Recorded probe 1** — remove `^` from `ChatEvents.LEAD` **and** `matchEntire`→`find` | **2 of 11 FAILED**, and they are the two named: `a party member typing a death message does not kill anybody`, `reads the blood door, which names nobody` |
| **Probe 1's sub-claim, run as two separate mutations** | `^` removed **alone** → `BUILD SUCCESSFUL`, fails nothing. `find` **alone** → `BUILD SUCCESSFUL`, fails nothing. **The redundancy claim is exactly right** |
| **Recorded probe 2** — `chatAttribution` returns `false` instead of `null` | **3 of 8 FAILED**: `the sentinel cannot be subtracted from here either`, `a named finder settles it either way`, `chat says nothing about the secrets it does not name a finder for`. Matches, and those three are precisely the three-state cases |
| **My probe A** — idempotency keyed globally (`deathAt["*"]`) instead of per player | **2 FAILED**: `two members dying at once are two deaths`, `a revive makes the next death a new death`. Mis-keying is pinned |
| **My probe B** — `onRevive` reads instead of removing (`deathAt[player] != null`) | **1 FAILED**: `a revive makes the next death a new death` |
| **My probe C** — dedup window unbounded (`at - previous >= 0`), i.e. the lost-death direction | **1 FAILED**: `the duplicate window covers the lag between the two reports and nothing else` |
| `git diff a6dc629..936e233 -- src/test/ \| grep -cE '^-\s*@Test'` | **`0`**. Added: **20**. Deleted lines in test files of any kind: **none**. Assertions removed anywhere under `src/`: **0**. Test files 13 → 14 |
| `git status --porcelain src/` after all six mutations, each restored with `git checkout` | **empty** |

### The citations, checked against the cited sources rather than read

This is the whole feature and the session sourced it by `gh api .../contents/...` rather than from
memory. I fetched five of the five cited mods myself. **Not one citation is fabricated, and the two
I expected to be loose are verbatim.**

- **Cowlection** (`cow-mc/Cowlection`, `DungeonsListener.java`) — `DUNGEON_DEATH_PATTERN` at `:102` is
  `"^ ☠ (\\w+) .+ and became a ghost\\.$"` and `DUNGEON_REVIVED_PATTERN` at `:103` is
  `"^ ❣ (\\w+) was revived.*?$"`. The KDoc's claim that the file "documents them as a list and matches
  them with one pattern" is literally true: `:95-99` is a javadoc `<li>` block carrying **exactly the
  five middles the KDoc names** — `disconnected from the Dungeon`, `died`, `fell to their death with
  help from`, `was killed by`, and second-person `You were killed by`. The `SELF` KDoc's claim that
  Cowlection "maps the literal `You` to the client's own name and stops there" is `:578-580`, verbatim.
  `text.startsWith("PUZZLE FAIL!")` at `:589` backs the second `PuzzleFailed` citation.
- **IllegalMap** (`UnclaimedBloom6/IllegalMap`, `components/DmapDungeon.js:295-296`) —
  `/^(?:\[[^\]]+\] )*\w+ opened a WITHER door!/` is **character-for-character** the regex quoted in the
  `WitherDoor` KDoc, and `:296` carries the blood door as the whole-line literal
  `/^The BLOOD DOOR has been opened!$/`.
- **OdinLegacy** (`odtheking/OdinLegacy`, `AutoGFS.kt:38`) — the alternation is verbatim, including
  `(\w{1,16})` twice, which also substantiates the separate claim that `NAME` is "bounded the way
  `OdinLegacy` bounds it". The KDoc elides the tail with `...`; the real tail is `I shall never forget
  this moment of misrememberance.` and the mod's own `QUIZ_WRONG` uses `.*$` there instead, which is
  the tolerant direction and is consistent with its stated design.
- **SkyHanni** (`hannibal002/SkyHanni`, `DungeonChatFilter.kt`) — all three named lists exist under
  exactly those names: `puzzlePatterns:108`, `pickupPatterns:133`, `pickupMessages:148`. The two
  `PUZZLE SOLVED!` examples the KDoc quotes are `:109-110` verbatim. Both Wither Essence forms are
  verbatim: third-person in `pickupPatterns` at `:138`, second-person in `pickupMessages` at `:149`.
  The `(.*) §r§ffound a §r§dWither Essence§r§f!` string quoted in `parse`'s KDoc as "three codes in one
  sentence" is `:138` exactly. **The one overstatement in the pass is here — see follow-up 2.**
- **DulkirMod** (`inglettronald/DulkirMod`, `DungeonKeyDisplay.kt:9-10`) — `([a-zA-Z]+) opened a WITHER
  door!` and `The BLOOD DOOR has been opened!`. **Harry282/Skyblock-Client** (`FastLeap.kt:32`) —
  `message == "The BLOOD DOOR has been opened!"`, a whole-line literal as claimed. With OdinLegacy's
  `SplitsManager.kt` that is the fourth independent blood-door source the KDoc says it has.

### The three unverified paths, and whether a reader will find them

The session names all three — the strings, the wiring, the ordering — in `feature_list.json`'s notes,
in `verification_manual`, in `session-handoff.md`, in `quality-document.md`'s row, and in
`ChatEvents`' own header. **Nothing anywhere implies the dev client reached Hypixel**; every artifact
that could says the opposite. The strings and the wiring are named and correctly fenced, and the
benign-failure argument for a wrong pattern is sound: an anchored pattern that does not match returns
null and the pre-existing inference runs unchanged.

**The ordering is the sharp one, and the artifacts are half right about it — see follow-up 1.** The
risk itself is stated prominently and correctly in the two artifacts a reader meets first. But two of
the four places it is written say the opposite, and they are the operational two.

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | **No Correctness-0 condition is met and I checked rather than inherited.** `git diff -- RunReport.kt` is **0 lines**, `SCHEMA` is 5, `mod_version` 0.9.0. No schema change landed, so none could have landed ahead of the receiver. I rebuilt the paired check mechanically from its description rather than re-reading the session's: **four empty sets, both directions** (the lone apparent mismatch is a `JsonArray` element, not a key). `SighteAddonServerside` was read and not written. **The citations are the feature and they hold.** Five of five cited mods fetched; the Cowlection death and revive patterns, IllegalMap's wither-door regex and OdinLegacy's puzzle alternation are **verbatim**, and the two claims most likely to be loose — Cowlection's five death shapes and the `You`→client-name mapping — are in the cited file as described. A fabricated citation would have been worse than an uncited guess; there are none. **The three-state `chatAttribution` genuinely distinguishes all three cases**: `null` when `chatAt == NO_INTERACTION` or the window has passed, `chatMine` otherwise, and `val mine = chat ?: clicked` leaves the silent case falling through to the *unmodified* `isOwn`. Probe 2 reproduces at 3 of 8 and the three failures are exactly the three-state cases. The `in 0..CHAT_WINDOW` range check (rather than `<=`) makes the `Int.MIN_VALUE` overflow that bit `isOwn` in a real M7 unrepresentable here. **The death change is correct where it touches working code.** Both sources key on the same string — `PartyTracker.TAB` captures `(?<name>[A-Za-z0-9_]+)` with ranks stripped, `ChatEvents` captures `(\w{1,16})`, the same alphabet, and `resolve()` maps `SELF` to the local name at the only site that knows it — so the idempotency key cannot diverge between the two paths. **Doors and puzzles really do stop at the debug log**: all four reach `DebugLog.event` and nothing else, and `/ingest` validates only the filename. Reservations recorded as follow-ups 1-3, none of which is a defect in behaviour that ships. |
| Verification | Did the required checks actually run, with evidence? | 2 | **Every recorded number reproduced, and both mutation probes reproduced to the failing test name.** 11/8/48 on the `verification_command` verbatim; 14 classes / 160 tests / 0 failures cold with `--rerun-tasks`; `assemble check` green with the jar md5 identical either side; `init.sh` `BASELINE: PASSING`. Probe 1 fails 2 of 11 and they are the two named; **its sub-claim is the part worth crediting** — I ran the two mutations separately and each alone fails nothing, so the redundancy is real rather than one guard sitting on a dead one, exactly as the comment on `parse` says. Probe 2 fails 3 of 8, the three named. **Nothing was removed, deleted or loosened**: `0` `@Test` lines removed, `0` deleted lines in test files of any kind, `0` assertions removed anywhere under `src/`, 13 → 14 test files. So the Verification-0 condition does not apply. **The `verification_command` change is honest scope-widening, not a bigger green number.** `git show a6dc629:feature_list.json` shows the old entry was `--tests 'sighteaddons.ChatEventsTest'` **while the feature was `not_started`** — a planning placeholder that was never the basis of any passing claim, which materially defuses the concern. The widening pulls in exactly the two classes carrying the feature's other nine tests, which are the ones both mutation probes bite on; running only `ChatEventsTest` would have missed every death-dedup and `chatAttribution` case. And the evidence entry **states the before-counts per class** (`SecretTrackerTest 8 (was 5)`, `ContributionTrackerTest 48 (was 42)`), so a reader can see that 20 of the 67 are new and 140 are inherited — the opposite of inflating. It is disclosed in `session-handoff.md` rather than swapped quietly. One durability gap on it, follow-up 4. **All three unverified paths are named** in five artifacts, and nothing implies a real run. |
| Regression | Are previously passing features still passing? | 2 | Full suite green from a forced cold run in a fresh detached worktree: `classes 14 tests 160 skipped 0 failures 0 errors 0`; `main` is 13/140 and the delta is exactly the 20 added tests, with zero removed. `assemble check` → `BUILD SUCCESSFUL`, jar md5 `b2ebc35ccfeb9cc96134eb3b18f0306f` before and after, `dist/` and `gradle.properties` clean, no version bump, `RunReport.kt` and `rooms.json` untouched. **The death path is the one behaviour change to code that already worked, so I attacked it directly rather than trusting the suite.** All three failure modes are pinned by a test that actually goes red: mis-keying the idempotency (global key) fails **2** — `two members dying at once are two deaths` and `a revive makes the next death a new death`; `onRevive` not clearing fails **1**; and the lost-death direction, an unbounded dedup window, fails **1** — `the duplicate window covers the lag between the two reports and nothing else`. So double-counting, wrong-keying and swallowing a genuine second death are each guarded, and the tab path is kept rather than replaced, which is the conservative choice. Diffing statuses against `a6dc629`: nothing downgraded, removed or quietly moved — `chat-001` `not_started`→`passing`, `chatfields-001` *added* per `CLAUDE.md`'s discovered-work rule. One residual risk found that no test can catch, recorded as follow-up 5; it is a consequence of the design, not a regression. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | Two commits, 12 files, and the boundary held where it mattered. `RunReport.kt` absent, `rooms.json` absent, `dist/` absent, `gradle.properties` absent, no version bump, so the release gate never engaged. The temptation here was real and was refused twice: the door and puzzle events **are** parsed and **do** stop at the debug log, with `chatfields-001` recorded rather than built — and its `blocked_reason` correctly puts the receiver first and carries the teammate-names design question that makes it a feature rather than a follow-up commit. `SighteAddons.RUN_END` was left untested on purpose with the reason written down (private, in a companion needing Fabric, and the sole trigger of the permanent report — `runloss-001`'s territory). `SighteAddonServerside` read and not written. The `user_visible_behavior` correction is scope *honesty* rather than scope creep: it narrows three of four promises to what the code actually does. |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed cold by a session with no prior context, in a detached worktree, baseline green on the first attempt with no repair and no environment fixing beyond what `session-handoff.md` documents. Six mutations applied and every one restored with `git checkout`; `git status --porcelain src/` came back empty. The handoff's quirks are load-bearing and correct rather than decorative: `python` resolves and `python3` does not, `assemble check` and never `build`, restore with `git checkout` rather than a Python round-trip (`core.autocrlf=true` plus the `*.jsonl` LF pin), `RoomStats.use(RoomScores.NONE)` in `@BeforeEach`, and `DungeonSession.runTicks` being `private set` — which is exactly why `onDeath`, `onChatSecret` and `onPresence` all take `at` as a parameter and are therefore testable at all. That seam is the reason this feature could be graded rather than described. |
| Maintainability | Is the code and documentation clear enough for the next session? | 1 | **The deduction, and it is the sharpest thing in this pass: the artifacts contradict themselves on the one risk that decides whether half the feature works.** `feature_list.json` note (3) says the ordering failure means "`CHAT_WINDOW` cannot fix it" and `session-handoff.md` says the same. But `verification_manual` step 5 says "`click` next to a preceding `chat_secret` proves it does not and **CHAT_WINDOW is the fix**", and the comment inside `SecretTracker.onChatSecret` says "**the window is the fix**". Two say cannot, two say is. **The two that are wrong are the operational two** — the procedure somebody follows after a real floor, and the comment at the logging site they will be staring at. Reading the code settles it: `chatAttribution` is a *forward-only* window from chat arrival, so if the line lands after the bar there is nothing in `chatSecretAt` to widen toward; the fix is deferring the credit, a different mechanism. A reader who follows the manual would widen a constant, see nothing change, and have to rediscover the design risk the notes already knew. Second, smaller: the SkyHanni negative claim is overstated (follow-up 2). Everything else is genuinely good — per-pattern citations with the source named at each shape, the `DeathSource` KDoc arguing why the tab path is *kept*, `CHAT_WINDOW`'s asymmetry-against-`OWN_WINDOW` reasoning, `nearMiss`'s privacy argument and the explicit statement of what it therefore cannot cover, and the `user_visible_behavior` correction done in place with the grep that disproved it. This is a 1 for two fixable sentences and one overstated one, not for the state of the code. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | I reconstructed the entire state from the artifacts alone: branch and head, what each commit is for, every command, both mutation probes precisely enough to rebuild them from their descriptions and hit identical failure counts **and identical failing test names**, the paired check rebuilt from scratch from its one-line description, why the grade moved to C and not B, and all three unverified paths. The "Next Best Step" asked for exactly this evaluation, named exactly the probes and counts to expect, and named the judgement most worth a second pair of eyes. **On that judgement — whether `passing` is right for a feature whose strings are unverified — the session's argument is correct.** `blocked` would misstate it: nothing is waiting on anything, the `verification_command` covers the layer this repository can reach exhaustively, the unreachable layer is named in five places, and a wrong pattern degrades to the pre-existing inference rather than to a wrong number. It is the same condition every other `passing` feature's wiring is under. Held at 2 rather than 1 because nothing blocked reconstruction — but see follow-ups 4 and 8, both of which are about this file. |

**Total: 13 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **ACCEPT** — 13/14, no category 0, and Correctness, Verification and Regression all 2. Not
overridden.

**Every number the session recorded reproduced**: 11/8/48 on the `verification_command`, 160 tests in
14 classes cold, jar md5 unchanged, `SCHEMA` 5, `mod_version` 0.9.0, `RunReport.kt` diff empty, four
empty sets on a paired check I rebuilt myself, and both mutation probes to the exact failing test
names.

**The citations are real, and that was the thing most worth checking.** A cited pattern reads as
evidence, so a fabricated one would have been worse than an honest guess. I fetched all five mods:
Cowlection's death and revive patterns, IllegalMap's wither-door regex and OdinLegacy's puzzle
alternation are verbatim; SkyHanni's three lists exist under exactly those names with both essence
forms verbatim; the five death shapes and the `You`→client-name mapping are in Cowlection's file as
described. The one flaw is an overstated *negative* (follow-up 2), not an invented positive.

**The `verification_command` change is honest.** The old entry was a placeholder on a `not_started`
feature, never the basis of a passing claim; the widening pulls in precisely the classes carrying the
tests both mutation probes bite on; and the evidence states the before-counts per class, so the
inherited 140 are visible rather than folded into a bigger number.

**The death change does not lose, double-count or misplace a death, and I made it fail three ways to
find out.** Mis-keying, a non-clearing `onRevive` and an unbounded window each go red. The two sources
key on the same alphabet with ranks stripped on both sides, so the dedup key cannot diverge.

**On the ordering question, which I was asked to opine on directly:** the design risk is real and the
session identified it correctly — if `chat_secret` arrives after the action-bar update it attributes,
no window size fixes it, because the window only ever looks forward from a line that has already
landed. The artifacts state that correctly where a reader meets it first. They then state the opposite
in the two places a reader would act on it. **My answer to "is it clear enough that nobody ships it
believing it works" is: yes for the reader, no for the diagnoser** — and since nothing ships without
the release gate, and a wrong ordering degrades to the inference rather than to a wrong number, that is
a documentation defect rather than a shipping hazard. Follow-up 1 is two sentences.

**The grade move `events (chat) D → C` is right, and right for the stated reason.** The domain went
from not existing to existing and tested at its one testable seam; it is held below B because the
patterns are cited rather than observed, the wiring is untestable here, and the ordering is unknown.
`quality-document.md:42` says all three in the row itself, and `:71` explicitly names this as the same
standard `party` is held to at C — which is accurate: `party`'s C is also "the heuristic is stated
where it is made, and nothing has put it under load". Promoting to B on citations would have made
"cited to five mods" equivalent to "seen once", and the `Do Not Touch` entry pinning the citations in
place is the right guard around that.

## Required Follow-Up

Nothing here blocks acceptance. None of it is a defect in behaviour that reaches a player, and no
receiver change is owed — `chat-001` writes no new field and is genuinely not paired.

### New this pass

1. **The ordering risk is described two contradictory ways, and the wrong version is in the two
   operational places.** `feature_list.json` note (3) and `session-handoff.md` say `CHAT_WINDOW`
   *cannot* fix a late `chat_secret`; `verification_manual` step 5 and the comment in
   `SecretTracker.onChatSecret` say the window *is* the fix. The notes are right —
   `chatAttribution`'s window runs forward from a line that has already arrived, so if chat lands
   after the bar there is nothing to widen toward and the fix would be deferring the credit. Correct
   the two that are wrong; they are the ones somebody reads while diagnosing a real floor.

2. **The SkyHanni negative claim is overstated and the cited file contradicts it.**
   `Event.SecretFound`'s KDoc says `DungeonChatFilter` "has no other `found a` shape". It has two:
   `:92-93`, `DUNGEON BUFF! (.*) found a Blessing of (.*)` and its second-person form. The
   *substantive* negative survives — a Blessing is not a secret, and no false match is possible
   because `SECRET` requires `found a Wither Essence!` after an anchored name — but the sentence as
   written is disproved by a one-line grep of the file it cites, which is the standard this feature's
   own `Do Not Touch` entry sets. Narrow it to "no other shape naming a finder for a *secret*". While
   there: `pickupPatterns` also carries named-actor lines for Superboom TNT, Revive Stone, Premium
   Flesh and Beating Heart — items that come *out* of chests, so they are not a secret signal and were
   right to skip, but the reasoning for skipping them is not written down anywhere.

3. **A late `chat_secret` may mis-attribute the *next* secret, which no artifact says.** Every artifact
   describes the ordering failure as the attribution being "always too late", implying a benign
   fallback to `isOwn`. It is slightly worse than that: a line arriving after its own bar update stays
   in `chatSecretAt` for `CHAT_WINDOW`, so a second secret rising within 20 ticks consumes it and is
   credited or denied on the *previous* secret's finder. Narrow (needs two secrets inside 20 ticks) and
   unverified in either direction, but the risk paragraph should say it, because "benign" is currently
   doing work it has not earned.

4. **The `verification_command` change is recorded only in `session-handoff.md`, which is overwritten
   every session.** The operating loop mandates that overwrite, so next session the only permanent
   trace of the scope widening will be gone — and the entry's own `notes` do not mention it. The
   precedent the handoff cites, `ingame-001` in session 009, *is* in `claude-progress.md` at `:277`.
   One line in the progress log, or one sentence in the entry's `notes`, closes it. Recorded because
   the change is legitimate and should not become suspicious later for want of a durable record.

5. **A death can still be lost, in one narrow case the KDoc does not name.** `onRevive` is the only
   thing that clears `deathAt`, and the `Revived` pattern is itself unverified. So: revive line missed
   or unmatched, player revived in game, player dies again within `DEATH_DEDUP_TICKS` (60) — both
   sources are suppressed and the death is gone. The KDoc's "a dead player cannot die again without
   being revived first" is true of the *game* but not of *this mod's knowledge of it*. Not a defect
   today and no test can catch it; worth one clause, and worth knowing that the revive pattern is
   load-bearing for death counts rather than merely informational.

### Carried forward — still open, verified still open rather than assumed

The rubric is overwritten every pass and items have repeatedly had to be rescued by hand. Each of
these was re-checked at `936e233`.

6. **`residue-001`'s `settle` KDoc still argues against the wrong alternative.** Now at
   `ContributionTracker.kt:712` (was `:625`; shifted by this feature's additions), still reading "the
   clamp this replaces only ever caught the negative half" when the real alternative was a symmetric
   threshold-to-zero. Behaviour is right and tested; only the justification aims at a weaker option
   than a reader would propose. **The migration into `residue-001`'s `notes` still holds** — I checked
   the field rather than trusting the previous rubric, and the "OPEN REVIEW FINDING, carried here in
   session 006" paragraph is present.

7. **`clear-001` note (2), the zero-margin gap tolerance, is still genuinely unmeasured.** `MIN_TICKS`
   as the gap that may not split a stay is 20 against a documented worst-case roster-skew blackout of
   20. **The migration holds and is in good shape**: `clear-001`'s `notes` carry all three findings,
   with (1) and (3) marked MEASURED AND CLOSED by `artifacts-001` with the measurement and the artifact
   path in place, and (2) marked STILL OPEN with the reason the real M7 cannot close it. Nothing was
   lost in this pass's rewrite. `chat-001` adds a fourth thing waiting on the same party floor.

8. **The worktree recipe is still missing from `session-handoff.md`'s Environment Quirks, and it has
   now cost two evaluators.** `grep -in worktree session-handoff.md` returns nothing. The previous
   rubric filed this and it was not actioned; the workaround had to be carried by hand in this
   evaluator's delegation prompt instead, which is exactly the failure being masked. `git worktree add
   --detach ../<name> <sha>` is the form that works while the main checkout holds the branch. One line.
   Not scored against this session — actioning a previous rubric's findings is the orchestrator's call
   — but it belongs in the file this session is required to overwrite.

9. **The loosened fixture assertion from `clearpoints-002` is still slack.** `ContributionTrackerTest.kt:868`
   sanity-checks `weightOf(expensive) > 2 * weightOf(plain())`; `weightOf(plain())` is `0.75` (`:656`),
   so the bound is `1.5` against a fixture actually worth `3.50`. Unchanged this pass. Optional.

10. **`readout.sh:47` still calls the input "a per-tick decoration stream"** and the evidence README
    still says "Every figure quoted below is asserted in that script" (`README.md:17`) when the
    220-line census cannot be. Both cosmetic, both unchanged, both verified still present.

11. **One test still stands between the tree and a reintroduced size/kind bonus** —
    `size and kind are no longer paid for directly`, `ContributionTrackerTest.kt:342`. Carried as
    context; unchanged.

### Remaining unverified paths, all named by the session, none a deduction

- **That Hypixel sends these strings.** Cited to five published mods and verified to be cited
  accurately, which is the standard `SECRET_SKULLS` is already held to and is not the same as having
  seen one. A wrong pattern matches nothing and the inference continues unchanged; `ChatEvents.nearMiss`
  writes the offending line, redacted through `Pseudonym.row`, as `chat_unparsed`.
- **The wiring, entirely** — that Fabric hands `SighteAddons.onDungeonEvent` a given line with
  `overlay` false on the tick Hypixel sent it. The same condition `PartyTracker.positions` and
  `ContributionTracker.tick` are under.
- **The ordering**, per follow-ups 1 and 3. One real floor answers it: read `attributedBy` on each
  `secret` event.
- **The death path has never been exercised at all, by either source.** New this feature and correctly
  flagged: the one real run is solo and deathless.
- **Everything party-shaped**, the `RED` checkmark path, every pixel of the `/sa` screen, and the
  measured half of the scoring model. All unchanged.
- **The dev client still cannot reach Hypixel**, and the session says so in five artifacts.
- **`runloss-001` remains a measured, permanent data loss and is unfixed.** Unchanged by this feature.

### Next review trigger

`runloss-001`, per the handoff, and I agree it still outranks everything else on the list: it is the
only entry known to have destroyed real data, it needs no receiver change and no real dungeon to
verify the write path, and every `clearStay` sample the box is waiting for has to survive it to arrive
at all. Read its notes first — the fix is *when* `RunReport.write` is called, and whether the player is
still resolvable at `DISCONNECT` is something to **measure** rather than assume. If it touches
`RunReport.kt`, re-run the mechanical key diff first and **the receiver goes first**. Do **not** start
`chatfields-001` by editing `RunReport.kt`; its first move is a feature in `Sighte/skyblock-server`. Do
**not** start `scores-fetch-001`.

**If the user offers another run, ask for a party floor with a death in it.** That single file now
moves `party-001`, `clear-001`'s last open note, the rest of `ingame-001`, and all three of
`chat-001`'s unverified halves — including the ordering question, which is the one thing here that is
a design risk rather than a coverage gap.

This feature needs no further work to be accepted. It is accepted at `936e233`, on a branch, unpushed
and unmerged; merging and releasing remain the user's decisions and take the release gate at the top of
`CLAUDE.md` with them. The release notes now owe six things, per the handoff's list: the schema moved to
5, older installs are unaffected because the receiver still accepts 4, room points are no longer flat,
room points changed meaning so old and new standings are not comparable, that the mod now reads chat
events **and that the strings behind them are sourced from other mods rather than observed**, and — if
`runloss-001` lands first — that runs ended by quitting from inside a floor used to be discarded.
