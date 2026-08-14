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

**Evaluated:** `party-001` — "Identify a decoration's player by something other than list order".
**Branch:** `party-001` at `64cba74`, code at `80e5f57`, off `main` at `9cb71ee`. Not pushed, not
merged. For the commit count run `git rev-list --count 9cb71ee..64cba74` — deliberately not
transcribed, and that discipline has now held across five features.
**Evaluated on:** 2026-08-14, by a session that implemented none of this work, in a detached worktree
at `64cba74` (`git worktree add --detach ../party-wt 64cba74`). Worktree removed after the pass. This
is the **first** grading of `party-001`.

**What is being graded is a negative result.** The feature set out to build the accessor-mixin upgrade
path the codebase had recorded at `PartyTracker.kt:134-138`, concluded that both halves of that claim
are false, built no mixin, and moved the entry to `blocked`. A fabricated negative closes a door, so
the negative was re-established from the primary sources rather than read. **It holds, on every
claim, and on one the session did not make.**

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `./gradlew test --tests 'sighteaddons.PartyTrackerTest'` (the feature's own `verification_command`, verbatim) | `BUILD SUCCESSFUL`. **`PartyTrackerTest` 14 tests, 0 failures, 0 errors, 0 skipped**, read from `build/test-results/test/TEST-sighteaddons.PartyTrackerTest.xml`. Matches the recorded 14 |
| `./gradlew test --rerun-tasks`, summing `build/test-results/test/TEST-*.xml`, cold in a fresh worktree | **`classes 14 tests 167 failures 0 errors 0 skipped 0`.** Matches. Per class: ChatEvents 11, ContributionTracker 48, DebugLog 2, DungeonGrid 4, **PartyTracker 14**, Pseudonym 8, RecordTable 9, RoomDatabase 8, RoomHistory 5, RoomStats 9, RunReport 21, SecretRun 6, SecretTracker 8, TelemetryUpload 14 |
| `./gradlew assemble check`; `md5sum dist/*.jar` before and after | `BUILD SUCCESSFUL`; md5 **`b2ebc35ccfeb9cc96134eb3b18f0306f` identical both sides**; `git status --short dist/ gradle.properties` **empty**; `mod_version=0.9.0`; `RunReport.kt:66 SCHEMA = 5` |
| `bash init.sh` | `==> BASELINE: PASSING` |
| `git diff 9cb71ee..64cba74 -- src/main/kotlin/sighteaddons/RunReport.kt \| wc -l` | **`0`.** `RunReport.kt` is absent from the branch entirely, as are `rooms.json`, `dist/` and `gradle.properties`. Files touched: `claude-progress.md`, `feature_list.json`, `quality-document.md`, `session-handoff.md`, `PartyTracker.kt`, `PartyTrackerTest.kt` — **six, and no others** |
| `bash docs/evidence/session-1786719912927/readout.sh` | `==> READOUT: OK`, exit 0. The party section asserts and passes: **`roster_skew` events 0, distinct players 1, distinct `decoIndex` 1.** The "the one real run was solo" claim is not prose — it is a green assertion over committed evidence |
| **Recorded probe A** — `if (trustOrder) teammates.getOrNull(next++) else null` → `teammates.getOrNull(next++)` | **2 of 14 FAILED**, and they are the two blackout cases: `one missing marker blacks out every teammate and keeps only the frame`, `an extra marker blacks out the teammates as well`. Matches |
| **Recorded probe B** — `.also { next++ }` appended to `assign`'s frame branch (NoammAddons' actual defect) | **4 of 14 FAILED**: `dead and empty slots are neither counted nor assigned`, `the frame takes the local slot and the rest follow map order`, `the frame is found by type wherever it sits...`, `the local player being dead drops only the frame`. Matches |
| **Recorded probe C** — `localSlot` shadowed with `0` inside `assign` | **1 of 14 FAILED**: `the frame is found by type wherever it sits, and the local slot is not assumed to be zero`. Matches, and it is the one case written to catch exactly this |
| `git status --porcelain src/` after each of the three probes, each restored with `git checkout` | **empty**, all three times |
| `git diff 9cb71ee..64cba74 -- src/test/ \| grep -cE '^-\s*@Test'` | **`0`**. Added: **7**. Deleted lines in test files of any kind: **none**. Assertion lines removed anywhere under `src/`: **0**. Test classes 14 → 14 |
| `git show 9cb71ee:src/test/kotlin/sighteaddons/PartyTrackerTest.kt \| grep 'fun \`'` | **The vacuity self-report is true.** The prior 7 are `parses rank prefixes...`, `parses emblems and dead players`, `rejects rows that are not party members`, `dead and empty classes count as not alive`, `a death keeps the class the player had while alive`, `a living row carries its own class`, `a different player in the slot does not inherit a class` — **all tab-regex or living-class carry, not one touching the decoration heuristic** |
| Feature-status diff `9cb71ee` → `64cba74` | Only `party-001` `not_started` → `blocked`, title and `user_visible_behavior` corrected in place, and `deconame-001` **added** per `CLAUDE.md`'s discovered-work rule. **Nothing downgraded, removed or quietly moved**; `verification_command` text unchanged, confirmed by diffing the field |
| `python build/keydiff.py` | **Could not be re-run — the script does not exist in a clean checkout** (`build/` is gitignored; the handoff says so). Its conclusion was verified a shorter way instead: `RunReport.kt` is 0 diff lines, so the mod writes no new key and no receiver change can be owed. See follow-up 3 |

### The negative, re-established from the primary sources

Every claim was checked against `javap` over the merged jar this module compiles against
(`.gradle/loom-cache/.../minecraft-merged-043a8b3edf-26.1.2.jar`) and against the cited mod's source
fetched with `gh api`. **Nothing was accepted on citation.**

- **`ClientboundMapItemDataPacket` carries an unkeyed list — TRUE.** `javap -p` gives the record
  component `private final java.util.Optional<java.util.List<MapDecoration>> decorations;` and the
  accessor `public java.util.Optional<java.util.List<MapDecoration>> decorations()`. There is no
  `Map`, no `String`, and no key anywhere in the packet. Nothing to survive the wire.
- **`addClientSideDecorations` clears the map and re-keys from its own loop index — TRUE, and the
  bytecode is unambiguous.** `Map.clear()` at offset 4; `istore_2` initialising `i` to 0 at 14; then
  per iteration `getfield decorations` → `iload_2` → `invokedynamic #5:makeConcatWithConstants:(I)`
  → `Map.put`. **`BootstrapMethods #5` is `StringConcatFactory` with the recipe constant
  `icon-`; `#6` is `frame-`.** The constants are exactly as recorded. The field is
  initialised `Maps.newLinkedHashMap()` (constructor offset 35, `putfield #73`), and
  `getDecorations()` is a one-line `return decorations.values()`. **So an accessor mixin over the
  private map returns keys this client invented from the packet list's own order** — the same
  heuristic, spelled as a string. And past nine entries the last character is `0` of `icon-10`,
  which is not the index, exactly as the finding says.
- **NoammAddons does the same thing, with the extra defect — TRUE, verbatim.**
  `Noamm9/NoammAddons-Legacy`, `DungeonUtils.kt:250-260`:
  `val livingTeammates = dungeonTeammatesNoSelf.filter { ! it.isDead }`, then
  `if (type == 1) thePlayer?.mapIcon?.icon = key else if (type == 3) { val index =
  key[key.lastIndex].digitToInt(); if (index >= 0 && index < livingTeammates.size)
  livingTeammates[index].mapIcon.icon = key }`. The digit is a position in the key space, which
  includes the local player's own `type == 1` icon; `dungeonTeammatesNoSelf` excludes them
  (`:246`). **The defect is real and this mod does not have it.**
- **And one the session did not check, which I did because a single-repo citation is a single point
  of failure.** There is a *current* `Noamm9/NoammAddons` alongside the Legacy repo. Its
  `map/handlers/MapUpdater.kt:55-65` does `val index = key.lastOrNull()?.digitToIntOrNull()` →
  `livingTeammates[index]`, with the frame separated by `decoration.type.value() ==
  MapDecorationTypes.FRAME.value()`. **The modern port made the same choice.** The negative is
  therefore stronger than recorded, not weaker.
- **`MapDecoration.name()` really is the only survivor — TRUE.** `javap -p` gives the record
  components `type, x, y, rot, Optional<Component> name` and nothing else. There is no fifth channel
  the session overlooked.

**Verdict on the negative: correct. No real feature was skipped, and the door it closes deserved to
close.**

### "Behaviour is unchanged" — checked by reading both sides, not by trusting the suite

Room *discovery* runs through this loop (`ContributionTracker.tick` only ever creates a room some
decoration resolved into), so a silent change here changes which rooms exist, not merely who is
credited. I compared the old `positions()` against `assign()` + the new `positions()` clause by
clause: the teammate filter (`indices.filter { it != localSlot }.filter { alive }`) preserves the same
membership *and the same order*; `markers` is the same count expressed over the boolean projection;
the frame is still identified by type and still guarded on `alive`; `!trustOrder` still skips without
advancing `next`; and `next++` still advances even when `getOrNull` returns null. `players` is
`arrayOfNulls<DungeonPlayer>(5)` and `localSlot` is only ever assigned `0` or a validated `slot >= 0`,
so the old unguarded `players[localSlot]` and the new `roster.getOrNull(it)` cannot differ. **One
deliberate difference exists and is annotated at the site** — `map.decorations` was read twice (once
to count, once to iterate) and is now snapshotted once, because `getDecorations()` is a live view.
That is strictly safer, not neutral; see follow-up 2.

### The two corrected descriptions

Both now say the same true thing, and both assert it rather than describing it. `feature_list.json`'s
notes and `quality-document.md`'s party row agree: two teammates in one room produce two decorations
that resolve to one cell, so a swap is **harmless**; the damaging failure is the **count mismatch
across different rooms**, which `trustOrder` blacks out, and which is now pinned in both directions.
`PartyTrackerTest` carries the assertion in both halves, including `assertNotEquals(bPixel,
cell(-28, -88))` so the equality is a property of sharing a room rather than of a coarse grid. The
old row said "two players on one decoration are not told apart", which was never possible —
decorations are one per player. The correction is right. The two design citations behind the title
correction are verbatim: `README.md:9` "full per-player attribution with no party sync" and
`SighteAddons.kt:24` "No packets are sent and nothing is automated".

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | **No Correctness-0 condition is met and I checked rather than inherited.** `git diff -- RunReport.kt` is **0 lines**, `SCHEMA` is 5, `mod_version` 0.9.0, `dist/` and `gradle.properties` absent from the branch. No schema change landed, so none could have landed ahead of the receiver, and `SighteAddonServerside` was read and not written. **The deliverable is a negative and the negative is correct** — I re-established all four claims from `javap` over the 26.1.2 merged jar and from `gh api` over the cited source, and every one holds to the constant: `Optional<List<MapDecoration>>` with no key in the packet; `Map.clear()` then `put(makeConcatWithConstants(i), d)` with `BootstrapMethods #5 = icon-` and `#6 = frame-`; the field initialised `Maps.newLinkedHashMap()` and `getDecorations()` returning `decorations.values()`; NoammAddons-Legacy's `key[key.lastIndex].digitToInt()` into a `livingTeammates` that excludes self. I additionally checked the **current** `Noamm9/NoammAddons`, which the session did not cite: `MapUpdater.kt` makes the identical choice, so the finding is stronger than recorded. `MapDecoration`'s components are `type, x, y, rot, Optional<Component> name` and nothing else, so `deconame-001` really is the last candidate. **Refusing to build the mixin was the correct call, and the correct call was the expensive one.** What did land is behaviour-preserving: I compared `positions()` before and after clause by clause rather than trusting the green suite, because room discovery is inside the blast radius — teammate membership and order, the marker count, frame-by-type, the `!trustOrder` skip and `next++` advancement are all identical, and `players[localSlot]` cannot go out of bounds either way. Reservations are follow-ups 1-2, neither of which is a defect in behaviour. |
| Verification | Did the required checks actually run, with evidence? | 2 | **Every recorded number reproduced, and all three mutation probes reproduced to the exact failing test names.** The `verification_command` verbatim: 14/0/0/0. Full suite cold with `--rerun-tasks` in a fresh worktree: 14 classes / 167 tests / 0 failures / 0 errors / 0 skipped. `assemble check` green with the jar md5 identical either side and `git status --short dist/ gradle.properties` empty. `init.sh` `BASELINE: PASSING`. `readout.sh` `READOUT: OK` — including the three party assertions that make the solo-run claim checkable rather than stated. Probe A fails 2 (both blackout cases), B fails 4, C fails 1, rebuilt from the prose descriptions alone. **Nothing was removed, deleted or loosened**: `0` `@Test` lines removed, `0` deleted lines in test files of any kind, `0` assertion lines removed anywhere under `src/`, so the Verification-0 condition does not apply. **The vacuity self-report is true and I verified it against `9cb71ee` rather than accepting it** — all 7 prior cases are tab-regex or living-class carry, none touching the heuristic, and the entry was `not_started` at the time, so the vacuous command was never the basis of a passing claim. Keeping the command text unchanged and making the class non-vacuous instead is the honest direction; padding it with class names would have raised the number without raising the coverage. **The unverified paths are named, not implied** — in the entry's `blocked_reason`, its `verification_manual`, its notes, `session-handoff.md`, `quality-document.md`'s row and the `assign` KDoc — and every artifact that could mislead says the dev client cannot reach Hypixel and that the one real run was solo. One structural gap, follow-up 3. |
| Regression | Are previously passing features still passing? | 2 | Full suite green from a forced cold run in a fresh detached worktree: `classes 14 tests 167 skipped 0 failures 0 errors 0`. `main` at `9cb71ee` is 160 in the same 14, and the delta is exactly the 7 added `@Test` lines in `PartyTrackerTest` — no class added, none removed, and I derived that from the diff rather than from the recorded figure. `assemble check` `BUILD SUCCESSFUL` with the jar byte-identical either side, no version bump, so the release gate never engaged. **The one behaviour at risk is room discovery, and I attacked it directly rather than trusting the suite**: `ContributionTracker.tick` iterates `positions()`, so a subtle reordering in the extraction would change which rooms exist, get named, get cleared and get scored — silently, and in a way `PartyTrackerTest` alone could not see. `ContributionTrackerTest`'s 48 cases are untouched by this branch (`src/test/` diff names only `PartyTrackerTest.kt`) and green, and the clause-by-clause reading above finds no semantic difference beyond the deliberate snapshot. Feature statuses diffed against `9cb71ee`: nothing downgraded, nothing removed, nothing quietly moved — `party-001` `not_started`→`blocked` and `deconame-001` added, which is the discovered-work rule being followed rather than scope creep. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | Two commits, six files, and the boundary held at the point where it was expensive to hold. **The temptation here was the whole feature**: the session was sent to build a mixin, found the mixin pointless, and delivered a measurement instead of a substitute — which is the harder and the correct outcome. `RunReport.kt`, `rooms.json`, `dist/` and `gradle.properties` are all absent from the branch; no version bump; `SighteAddonServerside` read and never written. `deconame-001` was **recorded rather than built**, per `CLAUDE.md`'s rule, and it carries the design question that makes it a feature rather than a commit — a populated name is probably an IGN, `Pseudonym` exists so the log never carries one, and a redacted value cannot report what it redacted. The title and `user_visible_behavior` corrections are scope *honesty*, not creep: the old title named party sync, which `README.md:9` and `SighteAddons.kt:24` forbid in as many words, and leaving it would have read as a sanctioned plan. The testability seam is the minimum change that makes the heuristic gradeable at all, and it changed no behaviour. |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed cold by a session with no prior context, in a detached worktree at a bare sha, baseline green on the first attempt with no repair and no environment fixing beyond what `session-handoff.md` documents. Three mutations applied and every one restored with `git checkout`, with `git status --porcelain src/` empty after each. **The handoff's quirks are load-bearing and correct rather than decorative**, and two of them were used in anger: the `git worktree add --detach <path> <sha>` recipe (added this session, closing the previous rubric's follow-up 8 — it is what made this pass possible without being told by hand), and "commit the feature first, then probe", which is the trap that cost the implementing session an edit. `python` resolves and `python3` does not; `assemble check` and never `build`; counts from `build/test-results/test/*.xml` and not the console — all three hold exactly as written. The one soft spot is `build/keydiff.py`, follow-up 3, which does not survive a clean; the handoff discloses that and states in prose what the script does, so it is recoverable rather than lost. |
| Maintainability | Is the code and documentation clear enough for the next session? | 2 | **The `assign` KDoc is the best artifact in this pass, and it is good in a specific and rare way: it records a rejected alternative as a measurement, with the constants named so it can be rechecked in one `javap` — and I rechecked it, and every constant was there.** `icon-` and `frame-` are `BootstrapMethods #5` and `#6`; the field really is a `LinkedHashMap`; the NoammAddons line is verbatim. That is the difference between a comment that ages into folklore and one that stays falsifiable, and the `Do Not Touch` entry pinning it in place — with the instruction to correct it against a fresh `javap` and name the version rather than soften it to "may not work" — is the right guard. The two corrected descriptions now agree with each other and with the tests, the harmless and damaging failure modes are separated and both asserted, and `quality-document.md`'s row states the mutation results and the reason the letter did not move. **The grade staying at C is right and right for the stated reason**: the row's own criterion is "pins the heuristic rather than the truth", and nothing here observed a second decoration — compare `room naming`, which was promoted C→B on a real floor, and `events (chat)`, held at C for the same cited-not-observed reason. Promoting on mutation coverage would have made "tested against its own model" equivalent to "seen once". Four small precision items, follow-ups 1, 2, 4 and 5, none of which misleads a reader about a risk; held at 2 rather than 1 because no artifact contradicts another anywhere in this branch. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | I reconstructed the entire state from the artifacts alone: branch and head, what each of the two commits is for, every command, all three mutation probes precisely enough to rebuild them from prose and hit identical failure counts **and identical failing test names**, the whole finding re-derived from scratch against the class files and both NoammAddons repositories, the vacuity claim checked against the parent commit, why the grade did not move, and every unverified path. The commit count is derived rather than transcribed and was correct. **"Next Best Step" asked for exactly this pass and named the one judgement worth a second pair of eyes, which is the right way to use an evaluator.** On that judgement — see below; the session's reasoning is correct. Held at 2 because nothing blocked reconstruction, with follow-up 3 recorded against this file. |

**Total: 14 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **ACCEPT** — 14/14, no category 0, and Correctness, Verification and Regression all 2. Not
overridden.

**The negative is real, and that was the thing most worth checking.** A fabricated negative closes a
door permanently, so I went to the primary sources rather than to the citations. All four claims hold
to the constant — the unkeyed `Optional<List<MapDecoration>>` in the packet, the `Map.clear()` and
`put(makeConcatWithConstants(i), …)` in `addClientSideDecorations` with `icon-` and
`frame-` as `BootstrapMethods #5` and `#6`, the `Maps.newLinkedHashMap()` field behind a
`values()` accessor, and NoammAddons' `key[key.lastIndex].digitToInt()` into a self-excluding
`livingTeammates`. **The finding is also stronger than the session recorded**: it cited only
`NoammAddons-Legacy`, and the current `Noamm9/NoammAddons` makes the identical choice in
`MapUpdater.kt`. `MapDecoration` has exactly five components and `name()` is the only identity channel
among them, so `deconame-001` genuinely is the last candidate rather than the first one thought of.

**Every recorded number reproduced**: 14 on the `verification_command`, 167 in 14 classes cold, jar md5
unchanged, `SCHEMA` 5, `mod_version` 0.9.0, `RunReport.kt` diff empty, `readout.sh` green including its
three party assertions, and all three probes to the exact failing test names.

**On `blocked` rather than `passing` — the judgement the session asked to have checked: `blocked` is
correct, and closing the entry would have been wrong.** The `user_visible_behavior` promises rooms read
by *who* a decoration belongs to; the mod still reads list order, so `passing` would be false whatever
the suite says. And `blocked` is not a parking space here, because the entry carries an explicit,
falsifiable exit condition — if `MapDecoration.name()` comes back empty on a real party floor, close it;
the channel does not exist and party sync is forbidden rather than a fallback. That is a better-specified
block than most entries manage, and it costs one field on an existing debug event to resolve. Closing now
would discard a question that is one dungeon away from an answer. This is also the same condition
`ingame-001` is already `blocked` under, so the list stays internally consistent.

**On recording `deconame-001` rather than building it: correct, and mandated.** `CLAUDE.md`'s discovered-work
rule says to record and stay on the active feature. Beyond compliance, the entry earns its existence: the
redaction problem it names is genuine — a populated name is probably an IGN, `Pseudonym` exists so the log
never carries one, and a redacted value cannot report what it redacted — and deciding that before writing
the field is the difference between a debug field and a privacy defect. Its own notes say it proves nothing
here and is only worth doing if somebody is about to play a party floor, which is the right fence. See
follow-up 4 for the one thing wrong with it.

**On the grade staying at C: right, and saying why a grade did not move is the honest entry.** The party
row's stated criterion for C is that the tests pin the heuristic rather than the truth. Three mutation
probes raise confidence that the *guard* works; nothing raises confidence that decoration order is tab
order, because no run has ever contained two decorations. `room naming` moved C→B on a real floor and
`events (chat)` is held at C for the same cited-not-observed reason, so the letter is consistent with how
this document grades everything else.

## Required Follow-Up

Nothing here blocks acceptance. None of it is a defect in behaviour that reaches a player, and no
receiver change is owed — `party-001` writes no new field, changes no key, and is genuinely not paired.

### New this pass

1. **The `assign` KDoc says "NoammAddons" without naming which repository or version, and there are
   two.** The `Do Not Touch` entry promises the finding "can be rechecked in one `javap`" and the same
   standard should apply to the mod citation, which is the half that cannot be checked from the jar.
   The evidence entry names `Noamm9/NoammAddons-Legacy` correctly; the KDoc, which is what a reader
   meets, does not. Worth naming both — I checked the current `Noamm9/NoammAddons` too and
   `map/handlers/MapUpdater.kt:55-65` does `key.lastOrNull()?.digitToIntOrNull()` into
   `livingTeammates`, i.e. the same heuristic and the same self-counting defect. **Recording that here
   because it strengthens the finding and because the next reader should not have to rediscover that
   the modern port agrees.** One clause, and it makes the citation falsifiable by path rather than by
   name.

2. **"Behaviour is unchanged" is stated flatly in three artifacts, and there is one deliberate
   exception.** `positions()` used to read `map.decorations` twice — once to count markers, once to
   iterate — and now snapshots it once with `.toList()`, because `getDecorations()` is a live view over
   the `LinkedHashMap`. The comment at the site says so and the change is strictly safer, so this is a
   documentation gap rather than a defect: the entry, the progress log and the handoff all say
   "behaviour unchanged" without the exception, and a future session diffing the two versions will find
   a difference the artifacts told them was not there. One clause in the entry's notes.

3. **`build/keydiff.py` is cited as a re-runnable command and does not exist in a clean checkout.**
   `build/` is gitignored, so the script the evidence names was gone in my worktree and the recorded
   `python build/keydiff.py` could not be re-executed. The handoff discloses this and says to re-create
   it "from its docstring" — but the docstring is inside the script that does not survive, which is
   circular. What saves it is that the handoff *also* states in prose what the script parses, and that
   the conclusion is checkable a cheaper way (`RunReport.kt` diff is 0 lines, so no key moved), which is
   what I did. Two sessions have now re-created this script from scratch. Either move it somewhere
   tracked or stop recording it as a command; a `verification`-shaped line that cannot be run is the
   thing this repository keeps trying to eliminate.

4. **`deconame-001` ships with the exact defect this session was praised for diagnosing.** Its
   `verification_command` is `./gradlew test --tests 'sighteaddons.DebugLogTest'` — 2 tests, neither of
   which can touch a field added inside `PartyTracker.positions`, which needs a `MapItemSavedData` and
   is untestable here by the handoff's own note. That command is **vacuously green today and will stay
   vacuously green after the feature is built**, which is precisely the trap `party-001` just spent a
   session escaping. Materially defused by the entry saying in its own notes that it "proves nothing on
   its own and cannot be verified here", and by the entry being `not_started` so nothing rests on it —
   which is why this is a follow-up and not a deduction. But whoever picks it up should fix the command
   before writing the field, not after.

5. **One test's name promises more than its body delivers.** `two teammates in one room resolve to one
   cell whichever way round they are` never calls `assign` — it exercises `DungeonGrid` only, asserting
   that two nearby pixels give one `Pos` and a third gives another. The property it pins is the
   load-bearing half of the harmlessness claim and its KDoc explains exactly that, so nothing is
   overstated where it counts; but the name implies an ordering case that is not run. Rename, or add the
   two-line `assign` call that makes the "whichever way round" literal. Cosmetic.

### Carried forward — still open, verified still open rather than assumed

The rubric is overwritten every pass and items have repeatedly had to be rescued by hand. Each of these
was re-checked at `64cba74` by opening the file, not by trusting the previous rubric.

6. **Previous follow-up 4 is still open and has now materialised exactly as predicted.**
   `chat-001`'s `verification_command` widening (`ChatEventsTest` → plus `SecretTrackerTest` and
   `ContributionTrackerTest`) was recorded only in `session-handoff.md`. That file has since been
   overwritten by this session, as the operating loop requires. `grep -n "verification_command"
   claude-progress.md` returns five hits and **none of them is the chat-001 widening**; `grep` for
   `widened|widening|scope-widen` returns nothing; and `chat-001`'s own `notes` field does not mention
   it. **The only durable trace is now gone.** The change was legitimate — the previous pass established
   that — but there is no longer any artifact that says so, which is how a legitimate change becomes
   suspicious later. One line in `claude-progress.md` or one sentence in `chat-001`'s `notes`.
   Not scored against this session; actioning a previous rubric is the orchestrator's call.

7. **Previous follow-up 2 is still open.** `ChatEvents.kt:129` still reads that `DungeonChatFilter`
   "has no other `found a` shape". It has two — the `DUNGEON BUFF! (.*) found a Blessing of (.*)` pair.
   The substantive negative survives (a Blessing is not a secret and the anchored pattern cannot match
   it), but the sentence is disproved by a one-line grep of the file it cites. Narrow it to "no other
   shape naming a finder for a *secret*". Unchanged this pass.

8. **Previous follow-up 5 is still open.** `ContributionTracker.kt:457` still says "a dead player cannot
   die again without being revived first". True of the game, not of this mod's knowledge of it: `onRevive`
   is the only thing that clears `deathAt` and the `Revived` pattern is itself unverified, so a missed
   revive line plus a second death inside `DEATH_DEDUP_TICKS` loses the death. No test can catch it;
   worth one clause, and worth knowing the revive pattern is load-bearing for death counts.

9. **Previous follow-up 6 is still open.** `residue-001`'s `settle` KDoc at
   `ContributionTracker.kt:712` still reads "the clamp this replaces only ever caught the negative half"
   when the real alternative a reader would propose is a symmetric threshold-to-zero. Behaviour is right
   and tested; only the justification aims at a weaker option. **The migration into `residue-001`'s
   `notes` still holds** — I checked the field rather than the previous rubric.

10. **Previous follow-up 7 is still open, and `party-001` adds a fifth thing waiting on the same input.**
    `clear-001` note (2), the zero-margin gap tolerance: `MIN_TICKS` is 20 against a documented worst-case
    roster-skew blackout of 20. That blackout is `assign`'s `trustOrder` guard, so the two features are
    now provably the same window — and this pass confirms the duration is still an estimate from other
    mods, because `roster_skew` fired **zero** times on the one committed run (`readout.sh`, green). The
    migration into `clear-001`'s `notes` holds, with (1) and (3) still marked MEASURED AND CLOSED.

11. **Previous follow-up 9 is still open.** `ContributionTrackerTest.kt:868` still sanity-checks
    `weightOf(expensive) > 2 * weightOf(plain())`, i.e. a bound of 1.5 against a fixture worth 3.50. The
    comment above it now argues for a relative rather than absolute bound, which is a fair argument, but
    the slack is unchanged. Optional.

12. **Previous follow-up 10 is still open.** `readout.sh:47` still calls the input "a per-tick decoration
    stream" and the evidence `README.md:17` still says "Every figure quoted below is asserted in that
    script" when the 220-line census cannot be. Both cosmetic, both verified still present.

13. **Previous follow-up 11, carried as context.** `ContributionTrackerTest.kt:342`, `size and kind are no
    longer paid for directly`, is still the one test standing between the tree and a reintroduced
    size/kind bonus. Unchanged.

### Closed since the previous pass — verified closed, not assumed

- **Previous follow-up 8 is CLOSED.** The worktree recipe is now in `session-handoff.md`'s Environment
  Quirks at `:211-214`, with the `--detach <path> <sha>` form and the reason the plain form is refused.
  It is why this pass did not need to be told by hand. Closed by this session.
- **Previous follow-up 1 is CLOSED, and closed in both operational places.** `chat-001`'s
  `verification_manual` step 5 now reads "**CHAT_WINDOW is NOT the fix for that**… a late line needs the
  credit DEFERRED", and `SecretTracker.kt:131` now reads "And the window is NOT the fix for that, however
  much it looks like one." All four statements now agree. Closed before `9cb71ee`, not by this session.
- **Previous follow-up 3 is CLOSED.** The mis-attribution of the *next* secret is now stated explicitly at
  the end of `chat-001`'s `verification_manual` step 5. Closed before `9cb71ee`, not by this session.

### Remaining unverified paths, all named by the session, none a deduction

- **Whether the order heuristic is correct at all** — the ceiling on this entire domain. `assign` is
  pinned against its own model of a dungeon. That Hypixel emits decorations in tab order has never been
  observed, and the one real run was **solo**: `readout.sh` asserts `roster_skew` 0, one distinct player,
  one distinct `decoIndex`. A single decoration cannot be mis-ordered, so that run is evidence neither
  for nor against.
- **Whether `MapDecoration.name()` carries anything.** `party-001`'s blocker and `deconame-001`'s whole
  subject. If no, close `party-001`; there is no client-side channel left and party sync is forbidden.
- **Whether `roster_skew` ever fires**, and the real duration of the blackout window (documented 10-20
  ticks, estimated from other mods).
- **The wiring of `positions()` itself** — that a real Hypixel dungeon map carries exactly one `FRAME`
  and one marker per living teammate is read from other mods, not observed here.
- **All three of `chat-001`'s halves**, the death path in either source, the `RED` checkmark path, every
  pixel of `/sa`, and the measured half of the scoring model. All unchanged.
- **The dev client still cannot reach Hypixel**, and every artifact that could imply otherwise says so.
- **`runloss-001` remains a measured, permanent data loss and is unfixed.** Unchanged by this feature.
- **The schema is 5 in source and 4 in every install**, and six features now exist in source only.
  Nothing breaks meanwhile and nothing reaches a player either, until somebody bumps the version and
  takes the release gate — the user's decision.

### Next review trigger

`runloss-001`, per the handoff, and I agree it now outranks everything: `records-001` is deferred by the
user, `party-001` is blocked on an input this machine cannot produce, `ingame-001` needs a party floor,
and `runloss-001` is the only entry known to have destroyed real data — ten cleared rooms on 2026-08-14,
and `readout.sh` asserts `run_end` events **0** on that file. It needs no receiver change and no real
dungeon to verify the write path. Read its notes first: the fix is *when* `RunReport.write` is called, and
whether the player is still resolvable at `DISCONNECT` is something to **measure**, not assume. If it
touches `RunReport.kt`, re-run the key diff first and **the receiver goes first**. Do **not** start
`chatfields-001` by editing `RunReport.kt` — its first move is a feature in `Sighte/skyblock-server`. Do
**not** start `scores-fetch-001`. Do **not** start `deconame-001` unless somebody is about to play a party
floor, and fix its `verification_command` (follow-up 4) before writing the field.

**If the user offers another run, ask for a party floor with a death in it.** That one file now moves
`party-001`, `deconame-001`, `clear-001`'s last open note, the rest of `ingame-001` and all three of
`chat-001`'s unverified halves at once. It is the single highest-value input this repository can receive
and it costs the user one dungeon.

`party-001` is accepted at `64cba74` as a **blocked** feature with a landed testability seam — nothing here
is `passing` and nothing here claims to be. It sits on a branch, unpushed and unmerged; merging and
releasing remain the user's decisions and take the release gate at the top of `CLAUDE.md` with them. This
feature adds nothing to the release notes' outstanding list, because it changed no behaviour.
