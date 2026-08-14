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

**Evaluated:** `runloss-001` — "A run quit straight from the dungeon is not thrown away".
**Branch:** `runloss-001` at `27b9ea8`, code at `1504ace` and `cd26786`, off `main` at `d356ff2`. Not
pushed, not merged. For the commit count run `git rev-list --count d356ff2..27b9ea8` — deliberately
not transcribed, and that discipline has now held across six features.
**Evaluated on:** 2026-08-14, by a session that implemented none of this work, in a detached worktree
at `27b9ea8` (`git worktree add --detach ../runloss-wt 27b9ea8`). Worktree removed after the pass.
This is the **first** grading of `runloss-001`.

**What is being graded is the only defect on either repository's list known to have destroyed real
data** — the user's M7 of 2026-08-14, ten cleared rooms, lost because quitting the client from inside
a dungeon raises no JOIN and no headline. The fix is a third call site for `RunReport.write`. Its
write path is fully testable here and was fully tested; its **trigger has never been observed**,
because the dev client cannot log in to Hypixel. That gap is the whole question this pass exists for
and it is answered explicitly below rather than left to the scores.

**Every mutation in this pass was applied through an anchor that had to match exactly once, and the
`git diff --stat` was read after each one.** Two earlier passes reported "survived" for mutations that
never applied (a heredoc ate a backslash; CRLF broke a patch), and a mutation that failed to apply is
indistinguishable from a mutation no test catches. Every probe below shows its anchor count and a diff
stat proportional to the edit — `1 insertion(+), 1 deletion(-)` for the single-line probes, `1
insertion(+), 3 deletions(-)` for the multi-line one — which is also the proof that CRLF survived,
since a line-ending conversion would have rewritten the whole file.

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `bash init.sh` | `==> BASELINE: PASSING`, first attempt, cold worktree, no environment repair |
| `./gradlew test --tests 'sighteaddons.RunReportTest'` (the feature's own `verification_command`, verbatim) | `BUILD SUCCESSFUL`. **`RunReportTest` 30 tests, 0 failures, 0 errors, 0 skipped**, read from `build/test-results/test/TEST-sighteaddons.RunReportTest.xml`. Matches the recorded 30 |
| `./gradlew test --rerun-tasks`, summing `build/test-results/test/TEST-*.xml`, cold in a fresh worktree | **`classes 14 tests 176 failures 0 errors 0 skipped 0`.** Matches. Per class: ChatEvents 11, ContributionTracker 48, DebugLog 2, DungeonGrid 4, PartyTracker 14, Pseudonym 8, RecordTable 9, RoomDatabase 8, RoomHistory 5, RoomStats 9, **RunReport 30**, SecretRun 6, SecretTracker 8, TelemetryUpload 14. `main` at `d356ff2` is 167 in the same 14; the delta is exactly RunReport 21 → 30 |
| `./gradlew assemble check`; `md5sum dist/*.jar` before and after | `BUILD SUCCESSFUL`; md5 **`b2ebc35ccfeb9cc96134eb3b18f0306f` identical both sides**; `git status --short dist/ gradle.properties` **empty**; `mod_version=0.9.0`; `RunReport.kt:67 SCHEMA = 5` |
| `bash docs/evidence/session-1786719912927/readout.sh` | `==> READOUT: OK`, exit 0 — including the section that is this feature's own headstone, **`run_end events 0`**. The evidence of what the defect cost is intact, as `Do Not Touch` requires |
| **The key diff, re-derived rather than re-run** — `build/keydiff.py` does not exist in a clean checkout (`build/` is gitignored; third pass in a row, see follow-up 8) | Reproduced independently by parsing `addProperty`/`add` out of `RunReport.build()`/`room()` and `frozenset` bodies out of `SighteAddonServerside/ingest.py`: **run 17 vs 17, room 17 vs 17, all four difference sets empty**. Additionally `git diff d356ff2..27b9ea8 -- RunReport.kt \| grep -E '^[+-].*(addProperty\|\.add\()'` is **empty** — not one key line changed anywhere on the branch |
| `git -C SighteAddonServerside status --short` and `git log --oneline -3` | **Empty**, head `018cee5`, untouched. Read and never written |
| **Recorded probe A** — `uploader(live, captured) = live`, the pre-feature behaviour (anchor ×1, `1 insertion(+), 1 deletion(-)`) | **1 of 30 FAILED**: `the report is keyed by the name captured during the run, not by the client`. Matches |
| **Recorded probe B** — delete `if (!reported.compareAndSet(false, true)) return false` from `queue` (anchor ×1, `1 insertion(+), 1 deletion(-)`) | **2 of 30 FAILED**: `a run reported on the way out is not reported again on the way back in`, `the next run may be reported again`. Matches |
| **Recorded probe C** — `queue` returns `publish()`'s result without giving the claim back (anchor ×1, `1 insertion(+), 3 deletions(-)`) | **1 of 30 FAILED**: `a failed write leaves the next call site its chance`. Matches, and it is the exact test written to catch a poisoned flag |
| **Recorded probe D** — `publish` calls `Files.writeString(dir.resolve(name), body)` instead of `replace(...)` (anchor ×1, `1 insertion(+), 1 deletion(-)`) | **Run twice, against both test generations — see below.** Against `1504ace`'s test file: **29 tests, 0 failures.** Against `27b9ea8`'s: **30 tests, 1 failure**, `a torn temporary from an earlier attempt does not survive the next write` |
| `git status --porcelain src/` after each of the four probes, each restored with `git checkout` | **empty**, all four times; final `RunReportTest` re-run after restoration back to **30/0** |

### Probe D: the session's own self-criticism, checked, and it is true

This is the most honest thing in the report and it deserved checking rather than crediting. The claim
is that probe D **passed** when it was first run, and proved nothing, because a successful direct
write and a successful atomic move leave an identical directory — so `cd26786` was written to add the
observable half.

`git diff --stat 1504ace..cd26786` is **`src/test/kotlin/sighteaddons/RunReportTest.kt | 20 ++++`, one
file, test-only**. `RunReport.kt` is byte-identical across the two commits, which is what makes the
comparison clean: the same mutation, the same production code, two test generations.

- With the test file at `1504ace`: **29 tests, 0 failures.** The suite was **genuinely blind**. A
  mutation that removes the entire torn-write guarantee sailed through 29 checks.
- With the test file at `27b9ea8`, mutation unchanged: **30 tests, 1 failure**, and the failure is
  precisely `a torn temporary from an earlier attempt does not survive the next write` — the test
  `cd26786` added.

**The self-report is accurate in both directions.** A session that discovers its own probe was vacuous,
says so in the artifacts, and then commits the test that makes it bite is doing the thing this rubric
exists to reward. Recorded here because the previous rubric's follow-up 3 shows how fast an
unrecorded verification detail evaporates.

### The unresolvable-player claim, re-established from the bytecode

This is the whole design: if the player is resolvable at DISCONNECT, then preferring
`PartyTracker.localName` over the live player loses its justification and the feature is carrying an
unnecessary field. **Nothing was accepted on citation.** All four links were disassembled against the
exact artifacts this module compiles against — `minecraft-merged-043a8b3edf-26.1.2.jar` from
`.gradle/loom-cache`, `fabric-networking-api-v1-6.3.1+554860db4c.jar` (matching
`fabric_api_version=0.155.2+26.1.2`), and `netty-transport-4.2.7.Final.jar`.

1. **`Minecraft.destroy()` disconnects the level before `disconnectWithProgressScreen()` — TRUE, to
   the offset.** `36: invokevirtual ClientLevel.disconnect:(Component)V` then
   `40: invokevirtual disconnectWithProgressScreen:()V`. And `81: invokestatic java/lang/System.exit`
   — "a few statements before `System.exit(0)`" is literally true, which is what makes the torn-write
   concern real rather than decorative.
2. **`Connection.disconnect` is `channel.close().awaitUninterruptibly()` — TRUE.**
   `23: invokeinterface io/netty/channel/Channel.close` →
   `28: invokeinterface io/netty/channel/ChannelFuture.awaitUninterruptibly`.
3. **Fabric raises DISCONNECT from a HEAD inject on `Connection.channelInactive`, with no hop to the
   client thread — TRUE, and this is the load-bearing link.** `ConnectionMixin` carries exactly two
   `@Inject`s that reach the addon: `method=["channelInactive"] at=@At(value="HEAD")` →
   `disconnectAddon(ChannelHandlerContext, CallbackInfo)`, and
   `method=["handleDisconnection"] at=@At(value="INVOKE", target="PacketListener.onDisconnect(DisconnectionDetails)V")`
   → `disconnectAddon(CallbackInfo)`. Both call `AbstractNetworkAddon.handleDisconnect()`, which is
   `disconnected.compareAndSet(false, true)` guarding `invokeDisconnectEvent()` + `endSession()` — so
   the event fires **exactly once per connection**, as recorded. And
   `ClientPlayNetworkAddon.invokeDisconnectEvent` is `DISCONNECT.invoker().onPlayDisconnect(listener,
   client)` and nothing else: **no `client.execute`, no `submit`, no queue**. It runs on whichever
   thread closed the channel, which via the `channelInactive` HEAD inject is the Netty event loop.
4. **Netty completes the close future before firing `channelInactive` — TRUE.** In
   `AbstractChannel$AbstractUnsafe.close(...)`, `doClose0` is called at offset 136 —
   and `doClose0` is `doClose()` → `CloseFuture.setClosed()` → `safeSetSuccess(promise)` — whereas
   `fireChannelInactiveAndDeregister` is reached at 201/210, after it, partly via `invokeLater`. So
   `awaitUninterruptibly()` can return while the handler is still queued.

**The chain holds end to end, so the design choice is justified.** The handler runs off the client
thread while `Minecraft.disconnect(Screen, boolean, boolean)` is nulling `player` — I confirmed the
null itself at `185: aconst_null / 186: putfield player` — and reading that field across threads
without synchronisation is a race whatever the interleaving happens to be in practice. The second
justification the session gives is independent of all four links and also correct: every room's tick
map is keyed by the name that was current *during* the run, so `localName` is the more correct source
rather than a fallback. Two precision defects in how this is written down are follow-ups 1 and 2;
neither touches the conclusion.

### The torn-file guarantee — the pattern really does exclude `.part`

`TelemetryUpload.RUN` is
`^run-\d+-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\.json$`. A temporary is
`run-<millis>-<uuid>.json.part`, which fails on the `$` anchor after `\.json`; Kotlin's `matches` is a
full match on top, so it is excluded twice over. **Both consumers filter through that one pattern** —
`TelemetryUpload.send(...) { RUN.matches(it) }` and `RunReport.restamp`'s
`Files.list(dir).filter { TelemetryUpload.RUN.matches(it.name) }` — so a torn temporary is invisible
to the uploader *and* to the restamper, and is overwritten by the next attempt. "Outside the pattern
by construction" is accurate. The suite pins both halves: `assertFalse(TelemetryUpload.RUN.matches("$name.part"))`
for the exclusion, and the `cd26786` test for the consumption. The move being atomic is a property of
the code that no end-state assertion can reach, and the artifacts say exactly that rather than
implying coverage.

### The double-write guard

`summaryPrinted` was genuinely insufficient and the stated reason is the real one: it is set only at
`SighteAddons.kt:183-186`, behind `RUN_END.matches(text)`, so on the title-screen-then-rejoin pair it
is false on both passes. The guard now lives in `RunReport` as an `AtomicBoolean`, claimed by
`compareAndSet` *before* the write and released on failure, cleared by `DungeonSession.reset()`. Both
properties are asserted and both bite: probe B (drop the claim) fails 2, probe C (drop the rollback)
fails 1. The tests say what they need to say — `a run reported on the way out is not reported again on
the way back in` asserts the second `queue` returns false *and* that the directory holds only the
first file; `a failed write leaves the next call site its chance` blocks the directory with a regular
file, asserts the failure did not claim, then writes successfully for the same run. **A run cannot be
reported twice and a failed write does not poison the flag.** The DISCONNECT site deliberately not
calling `DungeonSession.reset()` is correct and correctly reasoned — that callback can arrive on a
Netty thread while `renderHud` is reading `ContributionTracker`, `PartyTracker` and `ClearPopup` every
frame. One thing the artifacts understate about the *read* side is follow-up 3.

### The corrected `verification_manual` — performable, and the correction is right

The old step 3 asked the operator to "confirm the debug session carries a `run_end` event", which this
path must **not** produce: `run_end` is emitted at exactly one place, `SighteAddons.kt:186`, gated on
the end-of-run headline that by definition never comes when you quit from inside a floor. The old step
was therefore **unpassable** — the sixth recorded statement found false in this repository, and one
that would have made a correct fix look broken.

The replacement is performable, and I checked the mechanism rather than the prose. `run_report` is
emitted from `RunReport.write` itself (`RunReport.kt:190`) with `complete`, `rooms`, `roomsCleared`
and `file`, so all three call sites emit it exactly once. Critically, `DebugLog.write` ends in
`out.flush()` on **every** line — so an event logged a few statements before `System.exit(0)` reaches
disk. Steps 2, 4 and 5 are plain filesystem inspection. One scoping imprecision in step 3 is follow-up
4.

### The judgement this pass was asked for

**Is a bytecode measurement of *where* an event fires enough to call a write path `passing`, when the
event itself has never been observed? Yes — and the reasoning is not "the tests are green".**

Split the claim. *Given DISCONNECT fires*, the report is built from the right name, written whole or
not at all, and written once: that half is fully exercised here — 30 tests, four probes, every one
reproduced, including one the session itself exposed as vacuous and then fixed. *That DISCONNECT
fires* is the unobserved half, and it is not an assumption — it is a call graph I read out of the two
jars this module compiles against, with no weak link: `destroy()` → `ClientLevel.disconnect` →
`channel.close()` → HEAD inject on `channelInactive` → `handleDisconnect` CAS → `invokeDisconnectEvent`
invoked directly. The only step no artifact can close from here is whether a real Hypixel quit reaches
`Minecraft.destroy()` at all, and the entry excludes the case where it does not — hard kill, SIGKILL,
power loss — explicitly and without pretending otherwise.

The alternative status is `in_progress`, and it would be **worse**, because it would misdescribe the
state in the other direction: it would advertise work remaining for an implementer when there is
none. The residual uncertainty cannot be reduced by anything available in this repository; it needs
one dungeon from the user, and `verification_manual` is now the procedure that converts it — which I
verified is actually performable rather than assuming it.

**And this is not a lower standard than `party-001` is held to at `blocked`.** That distinction
matters and the list stays consistent because of it. `party-001` is blocked because its
`user_visible_behavior` — rooms read by *who* a decoration belongs to — is **known to be
unimplemented**; the mod still reads list order, so `passing` would be a false statement about
behaviour. `runloss-001`'s behaviour **is** implemented and pinned; only its trigger is unobserved.
Unimplemented and unobserved are different categories, and `chat-001` is already `passing` on
cited-not-observed strings, so this is the repository's existing standard rather than a new one.

The condition that makes this acceptable is that `passing` is used here with a **disclosed ceiling**,
and the disclosure is everywhere it needs to be: the entry's notes, its `verification_manual`,
`session-handoff.md`'s "Broken Or Unverified", `quality-document.md`'s telemetry row, the
`RunReport.uploader` KDoc, and the progress log. Nothing implies a real run happened. If the
disclosure were thinner, this would be a Verification finding.

**On the grade staying at B: right, for the right reason, and it is the load-bearing half of the
judgement above.** Closing a gap is not evidence, and promoting telemetry on a disassembly would make
"I read the bytecode" equivalent to "I saw it work" — a lower bar than `party` is held to at C for
precisely the cited-not-observed reason, and than `events (chat)` is held to at C. The row also names
independent reasons A is not available anyway (retry schedule is "next game start", no backoff, no
queue, nothing uploads during a run). **Saying why a letter did not move is the honest entry**, and
this document now does it twice in a row.

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | **The Correctness-0 condition — a mod-side schema change landing ahead of the receiver — is not met, and I established that mechanically rather than inheriting it.** `git diff d356ff2..27b9ea8 -- RunReport.kt` contains **not one changed `addProperty`/`add(` line**; `SCHEMA` is 5; `mod_version` 0.9.0; `dist/` and `gradle.properties` absent from the branch; `SighteAddonServerside` clean at `018cee5`, read and never written. I re-derived the key diff independently of the missing script: **17 run keys vs 17, 17 room keys vs 17, all four difference sets empty**. The new `run_report` is a `DebugLog` event into the local JSONL, not a report field, so it cannot reach the validator. **The design's one load-bearing claim holds on every link** — I disassembled `Minecraft.destroy()` (level disconnect at 36 before `disconnectWithProgressScreen` at 40, `System.exit` at 81), `Connection.disconnect` (`close()`/`awaitUninterruptibly` at 23/28), `ConnectionMixin` (both `@Inject`s, `channelInactive`/HEAD and `handleDisconnection`/INVOKE, into a CAS-guarded `handleDisconnect`), `ClientPlayNetworkAddon.invokeDisconnectEvent` (**direct invoker call, no thread hop**) and Netty's `AbstractUnsafe.close` (`doClose0` at 136 before `fireChannelInactiveAndDeregister` at 201/210). So preferring `PartyTracker.localName` is justified, and independently justified again by the tick maps being keyed on the run-time name. The torn-file guarantee is real: `RUN` is `$`-anchored after `\.json`, `matches` is a full match, and **both** consumers filter through that one pattern. The double-write guard does what it claims in both directions. Reservations are follow-ups 1-5, none of which is a behavioural defect. |
| Verification | Did the required checks actually run, with evidence? | 2 | **Every recorded number reproduced, and all four probes reproduced to the exact failing test names** — under an anchor-must-match-once discipline with the diff stat read after each, so no probe was scored on an edit that never landed. `verification_command` verbatim: 30/0/0/0. Full suite cold with `--rerun-tasks`: 14 classes / 176 tests / 0 failures / 0 errors / 0 skipped, delta from 167 exactly RunReport 21→30. `assemble check` green, jar md5 identical either side, `dist/`+`gradle.properties` clean. `init.sh` `BASELINE: PASSING`. `readout.sh` `READOUT: OK` with `run_end events 0` intact. Probe A fails 1, B fails 2, C fails 1, D fails 1 — all rebuilt from the prose descriptions alone. **Nothing was removed, deleted or loosened**: `0` removed `@Test` lines, `0` removed lines in test files *of any kind*, `0` removed assertion lines anywhere under `src/`, 14 test classes before and after — so the Verification-0 condition does not apply. **The vacuous-probe self-report is true and I verified it in both directions** rather than accepting it: probe D against `1504ace`'s tests is 29/0 (genuinely blind), against `27b9ea8`'s is 30/1 on the added test (genuinely bites), with `RunReport.kt` byte-identical between them. **The unverified paths are named, not implied**, in six artifacts, and every one says the dev client cannot reach Hypixel. The corrected manual step is performable — `run_report` is emitted by `write()` and `DebugLog` flushes every line. One structural gap, follow-up 8. |
| Regression | Are previously passing features still passing? | 2 | Full suite green from a forced cold run in a fresh detached worktree: `classes 14 tests 176 skipped 0 failures 0 errors 0`, no class added, removed or renamed. `assemble check` `BUILD SUCCESSFUL` with the jar **byte-identical** either side and no version bump, so the release gate never engaged. Feature statuses diffed `d356ff2` → `27b9ea8`: **exactly one change, `runloss-001` `not_started` → `passing`**; 14 entries before and after; nothing downgraded, removed or quietly moved. **The one behaviour genuinely at risk is the JOIN path, whose guard changed**, and I read both versions rather than trusting the suite: `if (!summaryPrinted) write(...)` became an unconditional `write(...)` fronted by `if (reported.get()) return false`. Where the headline succeeded, `reported` is already true and the new call returns immediately; where there were no rooms, both versions return without writing. The one case that genuinely differs is a headline whose write *failed* — old code skipped at JOIN and lost the run, new code retries and saves it, which is an improvement rather than a regression (follow-up 5 records that the artifacts describe this as "both end in an early return", which is not quite what happens). `PartyTracker.localName` is nulled by `reset()` alongside everything else and only ever assigned a non-null name, so it cannot leak across runs. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | Three commits, nine files, and the split between them is meaningful rather than cosmetic: `1504ace` is the feature, `cd26786` is **test-only** (the 20-line test that makes probe D bite), `27b9ea8` is artifacts. `rooms.json`, `dist/`, `gradle.properties` and `mod_version` are all untouched; `SighteAddonServerside` was read for the key diff and never written; `assemble check` was used throughout rather than `build`. The `PartyTracker` change is the minimum the design needs — one captured field plus a null-guard — rather than a refactor of the tracker. **The session verified the pairing question instead of assuming it**, which is the rule that exists because getting it backwards loses a release's data, and the answer was correctly "not paired". The `ponytail:` note that had stood at `SighteAddons.kt:53-57` was removed because it is no longer a plan, which is the right disposition for a note whose work has landed. No new feature entries were invented and none were closed as a side effect. |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed cold by a session with no prior context, in a detached worktree at a bare sha, baseline green on the **first** attempt with no repair and no environment fixing beyond what `session-handoff.md` documents. Four mutations applied and every one restored with `git checkout`, `git status --porcelain src/` empty after each, and `RunReportTest` back to 30/0 at the end. **The handoff's quirks are load-bearing and were used in anger**: the `git worktree add --detach <path> <sha>` recipe; "commit the feature first, then probe" (which is what makes `git checkout` a safe restore); applying probes from Python with `io.open(..., newline='')` to preserve CRLF — I extended this into an anchor-count assertion plus a diff-stat check, and it caught nothing because nothing was wrong, which is the correct outcome for a guard. `python` resolves and `python3` does not; counts from `build/test-results/test/*.xml` rather than the console; `assemble check` never `build`. All hold exactly as written. The soft spot is `build/keydiff.py`, follow-up 8, now absent for the third consecutive pass. |
| Maintainability | Is the code and documentation clear enough for the next session? | 2 | **`RunReport.uploader`'s KDoc is the pivotal artifact and it survives being rechecked**, which is the only test that matters for a comment pinned as "a measurement, not an opinion": four numbered facts, each with the `javap` that re-establishes it, and all four are true against the jars named. The `Do Not Touch` entry forbidding a future session from softening it to "may not work" or "simplifying" the report back to `Minecraft.getInstance().player` is the right guard on exactly the right line, and the same is true of the entry pinning the `.part` + move, which correctly warns that the suite sees the *consumption* rather than the move and that a "simplification" will therefore look harmless — a warning probe D proves is literally accurate. The DISCONNECT site's comment explains the omitted `reset()` in terms of the render loop rather than asserting a rule. Four precision items (follow-ups 1-4) and one artifact overstatement (follow-up 5); held at 2 rather than 1 because none of them misleads a reader about a risk, the two bytecode nits are self-correcting via the recheck commands sitting next to them, and no artifact contradicts another anywhere on this branch. Follow-up 3 is the one worth acting on, because it is the only one where the artifacts are quieter than the hazard. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | I reconstructed the entire state from the artifacts alone: branch and head, what each of the three commits is for, every command, all four mutation probes precisely enough to rebuild them from prose and hit **identical failure counts and identical failing test names**, the whole four-link disassembly re-derived from scratch against the jars, the probe-D vacuity claim checked in both directions against the parent commit, the key diff re-derived after finding its script absent, why the grade did not move, and every unverified path. The commit count is derived rather than transcribed. **"Next Best Step" asked for exactly this pass, named the one judgement worth a second pair of eyes, and stated the alternative status it would accept** — that is the right way to use an evaluator, and it is now the second consecutive handoff to do it. The environment quirks made the difference between a cold start and a stall. Held at 2 because nothing blocked reconstruction; follow-up 8 is recorded against the one command that could not be re-executed. |

**Total: 14 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **ACCEPT** — 14/14, no category 0, and Correctness, Verification and Regression all 2. Not
overridden.

**The judgement the session asked to have checked — `passing` on a write path whose trigger has never
been observed — is correct, and it is correct for a reason that keeps the list consistent rather than
by exception.** The behaviour is implemented and pinned by 30 tests and four probes; only the trigger
is unobserved, and the trigger is a call graph read out of the exact jars this module compiles
against, with every link verified independently in this pass. That is a different category from
`party-001` at `blocked`, whose promised behaviour is known to be *absent*. `in_progress` would be the
worse description, because no work remains that this repository can do.

**Every recorded number reproduced**: 30 on the `verification_command`, 176 in 14 classes cold, jar md5
`b2ebc35ccfeb9cc96134eb3b18f0306f` unchanged, `SCHEMA` 5, `mod_version` 0.9.0, `readout.sh` green with
`run_end events 0` intact, the key diff clean at 17/17 both directions, and all four probes to the
exact failing test names — including probe D in **both** of its generations, confirming that the
session's admission of a vacuous probe was true and that the test it then wrote genuinely closes it.

**Not a schema change and not paired, confirmed rather than assumed.** No key line changed, `SCHEMA`
stayed 5, and the receiver was read and never written — so the cross-repo ordering rule was never
engaged and no receiver change is owed.

**On telemetry staying B: right.** Closing a gap is not evidence, the fix's one real path is
unobserved, and promoting on a disassembly would set a lower bar than `party` and `events (chat)` are
held to at C. The row also names independent reasons A is unavailable regardless.

## Required Follow-Up

Nothing here blocks acceptance. None of it is a defect in behaviour that reaches a player, and no
receiver change is owed. Items 1-5 are new; 6-18 are carried forward and were **each re-checked at
`27b9ea8` by opening the file, not by trusting the previous rubric**.

### New this pass

1. **A recorded bytecode offset is wrong by two, in a document whose authority is that it can be
   rechecked.** `runloss-001`'s evidence entry says `Minecraft.disconnect(Screen, boolean, boolean)`
   "nulls `player` at offset 184". It is `185: aconst_null` / `186: putfield #2612 // Field
   player`. The substance is untouched — the field *is* nulled there, which is the point — and the
   entry ships the `javap` that corrects it, which is what keeps this cosmetic rather than
   misleading. Worth fixing precisely because this repository's convention is that a named constant
   can be re-established in one command.

2. **`RunReport.uploader`'s KDoc says "the very next thing `Minecraft.disconnect(Screen, boolean,
   boolean)` does is `this.player = null`", and it is not the very next thing.** Before it: `59:
   putfield singleplayerServer`, `71: putfield gameMode`, `102: putfield level`, `181:
   updateLevelInEngines`. The load-bearing claim — that `player` is nulled during a teardown running
   on the client thread while the DISCONNECT handler runs on a Netty thread — is exactly right, and
   the race does not depend on the adverb. But the `Do Not Touch` entry explicitly forbids softening
   this KDoc, so the correction has to be made *precisely* rather than by hedging: name the offset
   and the intervening writes. One clause.

3. **The DISCONNECT write reads run state off the client thread, and the artifacts are quieter about
   this than about the `reset()` hazard they do document.** `SighteAddons.kt` says "the write itself
   only reads", but reading is the hazard here: `ContributionTracker.visitedRooms()` is
   `rooms.values.distinct()` over a plain `HashMap`, and `build()` then walks each room's `ticks`
   `HashMap`, while the client thread can be in `ContributionTracker.tick` calling `discover` and
   `ticks.merge`. A concurrent structural modification can throw `ConcurrentModificationException` or
   yield a partly-updated read. **Materially defused** — the DISCONNECT site wraps the call in
   `try/catch`, so the worst case is a lost report, which is exactly the pre-feature outcome and not
   a crash in the render loop; and on the quit path the level has already disconnected, so ticking has
   very likely stopped. That is why this is a follow-up and not a deduction. But the mitigation is
   load-bearing and currently reads as belt-and-braces ("an exception escaping into either is a worse
   outcome than a missing report") rather than as the thing that makes an unsynchronised cross-thread
   read acceptable. One clause at the site, and it should say *why* the `try/catch` is required rather
   than prudent.

4. **`verification_manual` step 3 asks for `run_report` "as its last line", which is only guaranteed
   for one of the two ways in that step 1 admits.** Quitting to desktop ends the process, so the line
   is last. Dropping to the title screen — which step 1 explicitly says "exercises the same path" —
   leaves the client running and free to log further events before the file is read. The check should
   be "the session's only `run_report` line, with `\"complete\":false`" rather than a positional
   claim, or the positional claim should be scoped to the quit-to-desktop case. Otherwise a correct
   fix can fail the manual check on the very variant the step invites, which is the same shape of
   defect the `run_end` → `run_report` correction just fixed.

5. **"The two cases where old and new differ both end in an early return" is stated in
   `session-handoff.md` and the progress log, and there is a third case that does not.** If the
   end-of-run headline came but its write *failed*, `reported` was given back and `summaryPrinted` is
   true: the old JOIN site skipped and the run was lost, the new one retries and saves it. That is an
   improvement and worth claiming rather than denying. Worth one clause for a second reason: the
   rescued report is written with `complete = false` for a run that did complete, which is the
   conservative direction and consistent with "the headline remains the only thing allowed to claim
   that it did" — but it is a real property of the rescue path and nothing currently says so.

### Carried forward — still open, verified still open rather than assumed

This file is overwritten every pass and items have repeatedly had to be rescued by hand; **item 6 has
now lost its last durable trace twice over and is the reason this section is not optional.**

6. **Previous follow-up 6 is still open, and has now decayed one step further.** `chat-001`'s
   `verification_command` widening (`ChatEventsTest` → plus `SecretTrackerTest` and
   `ContributionTrackerTest`) has **no durable record anywhere in the repository**. Verified at
   `27b9ea8`: `grep -ci "widen" claude-progress.md` → **0**; `grep -n "SecretTrackerTest"
   claude-progress.md` → **no hits**; `chat-001`'s `notes` field mentions neither `SecretTrackerTest`
   nor `widen` nor `verification_command`. The widened command *is* in the entry and *is* legitimate —
   two consecutive rubrics established that — but the only artifact that ever said so was the previous
   rubric, which this file replaces. **A legitimate scope change with no surviving justification is
   indistinguishable from a quietly loosened check**, which is precisely the thing this repository
   keeps trying to eliminate. One line in `claude-progress.md` or one sentence in `chat-001`'s
   `notes` closes it permanently. Not scored against this session; actioning it is the orchestrator's
   call.

7. **Previous follow-up 1 is still open.** The `assign` KDoc still says "NoammAddons" without naming
   which repository, and there are two (`PartyTracker.kt:183`, `:194`). The previous pass established
   that the current `Noamm9/NoammAddons` — `map/handlers/MapUpdater.kt:55-65`,
   `key.lastOrNull()?.digitToIntOrNull()` into `livingTeammates` — makes the identical choice to
   `NoammAddons-Legacy`'s `DungeonUtils.kt:250-260`. **That finding is recorded only here**, so it is
   restated rather than referenced: the negative is stronger than the source comment claims, and the
   next reader should not have to rediscover that the modern port agrees.

8. **Previous follow-up 3 is still open, third pass running.** `build/keydiff.py` is cited as a
   re-runnable command in `runloss-001`'s evidence and in the handoff's Commands section, and it does
   **not exist in a clean checkout** — `build/` is gitignored. I re-derived its conclusion from
   scratch instead (17/17 both directions, four empty sets) and additionally the cheap way (no
   `addProperty` line changed on the branch). Three sessions have now re-created or re-derived this
   script. **Either move it somewhere tracked or stop recording it as a `command` in `evidence`**: an
   evidence entry naming a command that cannot be executed is the exact failure mode the six corrected
   statements were about.

9. **Previous follow-up 4 is still open.** `deconame-001`'s `verification_command` is still
   `./gradlew test --tests 'sighteaddons.DebugLogTest'` — 2 tests, neither able to touch a field added
   inside `PartyTracker.positions`, which needs a `MapItemSavedData` and is untestable here. It is
   **vacuously green today and would stay vacuously green after the feature is built**. Defused by the
   entry's own notes saying it proves nothing, and by `not_started` meaning nothing rests on it. Fix
   the command before writing the field, not after.

10. **Previous follow-up 2 is still open.** `party-001`'s "behaviour is unchanged" is stated without
    its one deliberate exception: `positions()` used to read `map.decorations` twice and now snapshots
    it once, because `getDecorations()` is a live view. Verified still absent — `party-001`'s `notes`
    mention neither `snapshot` nor `toList`. Strictly safer, so a documentation gap rather than a
    defect, but a future session diffing the two versions will find a difference the artifacts denied.

11. **Previous follow-up 5 is still open.** `PartyTrackerTest.kt:188`, `two teammates in one room
    resolve to one cell whichever way round they are`, still never calls `assign` — it exercises
    `DungeonGrid` only. The property it pins is the load-bearing half of the harmlessness claim and
    its KDoc says so, but the name implies an ordering case that is not run. Cosmetic.

12. **Previous follow-up 7 is still open.** `ChatEvents.kt:129` still reads that `DungeonChatFilter`
    "has no other `found a` shape". It has two — the `DUNGEON BUFF! (.*) found a Blessing of (.*)`
    pair. The substantive negative survives (a Blessing is not a secret and `SECRET` at `:154` is
    anchored on `found a Wither Essence!`), but the sentence is disproved by a one-line grep of the
    file it cites. Narrow it to "no other shape naming a finder for a *secret*".

13. **Previous follow-up 8 is still open.** `ContributionTracker.kt:457` still says "a dead player
    cannot die again without being revived first". True of the game, not of this mod's knowledge of
    it: `onRevive` is the only thing that clears `deathAt` and the `Revived` pattern is itself
    unverified, so a missed revive line plus a second death inside `DEATH_DEDUP_TICKS` loses the
    death. No test can catch it; worth one clause, and worth knowing the revive pattern is
    load-bearing for death counts.

14. **Previous follow-up 9 is still open.** `residue-001`'s `settle` KDoc at
    `ContributionTracker.kt:712` still reads "the clamp this replaces only ever caught the negative
    half" when the alternative a reader would actually propose is a symmetric threshold-to-zero.
    Behaviour is right and tested; only the justification aims at a weaker option. The migration into
    `residue-001`'s `notes` still holds.

15. **Previous follow-up 10 is still open.** `clear-001` note (2), the zero-margin gap tolerance:
    `MIN_TICKS` is 20 against a documented worst-case roster-skew blackout of 20, so the two features
    are provably the same window, and the duration is still an estimate from other mods —
    `roster_skew` fired **zero** times on the one committed run. The migration into `clear-001`'s
    `notes` holds (`MIN_TICKS` and the gap tolerance are both present there), with (1) and (3) still
    marked MEASURED AND CLOSED.

16. **Previous follow-up 11 is still open.** `ContributionTrackerTest.kt:868` still sanity-checks
    `weightOf(expensive) > 2 * weightOf(plain())`, i.e. a bound of 1.5 against a fixture worth 3.50.
    Optional.

17. **Previous follow-up 12 is still open.** `readout.sh:47` still calls the input "a per-tick
    decoration stream" and the evidence `README.md:17` still says "Every figure quoted below is
    asserted in that script" when the 220-line census cannot be. Both cosmetic, both verified still
    present.

18. **Previous follow-up 13, carried as context.** `ContributionTrackerTest.kt:342`, `size and kind
    are no longer paid for directly`, is still the one test standing between the tree and a
    reintroduced size/kind bonus. Unchanged.

### Closed since the previous pass — verified closed, not assumed

- **Nothing from the previous pass's 13 items closed this session.** All thirteen were re-checked
  individually against the files at `27b9ea8` and all thirteen are still open; they are carried above
  as 6-18. This is stated explicitly because an empty "closed" section is otherwise
  indistinguishable from a section nobody filled in.
- **The `run_end` → `run_report` correction in `runloss-001`'s `verification_manual` is closed by this
  session and verified performable**, not merely reworded: `run_report` is emitted from
  `RunReport.write` at `RunReport.kt:190`, `run_end` is emitted only at `SighteAddons.kt:186` behind
  the headline regex, and `DebugLog.write` flushes every line so the event survives the imminent
  `System.exit(0)`. This is the sixth recorded statement in this repository found false and corrected.

### Remaining unverified paths, all named by the session, none a deduction

- **That Fabric raises `DISCONNECT` at all on a real Hypixel quit** — this feature's ceiling. The
  four-link disassembly is verified and the chain holds, but no client has ever done it here. What a
  real quit would show: a `run_report` line with `"complete":false` and a
  `run-<millis>-<installId>.json` in `config/sighteaddons/runs/`. Nobody has seen either.
- **A hard kill still loses the run** — task manager, `SIGKILL`, power loss. Explicitly uncovered,
  explicitly not implied to be covered, and the rejected JVM-shutdown-hook alternative is reasoned
  rather than waved away.
- **That the atomic move is atomic.** The suite sees a stale `.part` being consumed; the move itself
  is a property of the code. Probe D proves this distinction is real rather than pedantic.
- **Whether the order heuristic is correct at all** — the ceiling on the whole party domain. `assign`
  is pinned against its own model of a dungeon and the one real run was solo.
- **Whether `MapDecoration.name()` carries anything** (`party-001`'s blocker, `deconame-001`'s
  subject). If no, **`party-001` should be closed rather than carried**.
- **Whether `roster_skew` ever fires**, and **the wiring of `positions()` itself**.
- **That Hypixel actually sends `chat-001`'s strings**, and all three of its halves.
- **The party half of everything else** — the one real run is solo and deathless, so `clear-001`'s
  zero-margin gap tolerance is open and **the death path has never been exercised at all**.
- **The `RED` checkmark path, every pixel of `/sa`, and the measured half of the scoring model.**
- **The dev client still cannot reach Hypixel**, and every artifact that could imply otherwise says so.
- **The schema is 5 in source and 4 in every install**, and **seven** features now exist in source
  only. Nothing breaks meanwhile and nothing reaches a player either, until somebody bumps the version
  and takes the release gate — the user's decision.

### Next review trigger

**If the user offers a run, the highest-value single act has changed and is now cheap to state: play a
floor and quit the game from inside it.** That verifies `runloss-001` end to end — the one thing this
repository cannot do for itself — and costs one dungeon. **A party floor with a death in it that is
then quit from does both**, moving `party-001`, `deconame-001`, `clear-001`'s last open note, the rest
of `ingame-001`, all three of `chat-001`'s unverified halves *and* this feature's ceiling at once. It
remains the single highest-value input this repository can receive.

Otherwise `runend-001` is next: the only `not_started` entry workable here, cheap, and its open
question already answered (write the run-level count, not the event count). Do **not** start
`chatfields-001` by editing `RunReport.kt` — its first move is a feature in `Sighte/skyblock-server`.
Do **not** start `scores-fetch-001`, still blocked on the receiver serving `roomstats.json`. Do **not**
start `deconame-001` unless somebody is about to play a party floor, and fix its `verification_command`
(follow-up 9) before writing the field. `records-001` is deferred by the user.

`runloss-001` is accepted at `27b9ea8` as `passing` **with a disclosed and correctly disclosed
ceiling**: the write path is implemented, pinned and mutation-checked; the event that drives it is
measured but unobserved; and no artifact anywhere on this branch implies otherwise. It sits on a
branch, unpushed and unmerged; merging and releasing remain the user's decisions and take the release
gate at the top of `CLAUDE.md` with them. **It adds one line to the release notes' outstanding list** —
that a run ended by quitting from inside a floor used to be discarded and now is not, and that this
path is unverified against a real client — bringing that list to seven.
