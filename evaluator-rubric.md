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

**Evaluated:** `scores-fetch-001` — "Fetch the measured room scores instead of waiting for somebody
to drop the file in".
**Branch:** `scores-fetch-001` at `1509835`, code at `74e6859`, off `main` at `d356ff2`. Not pushed,
not merged. For the commit count run `git rev-list --count d356ff2..1509835` — deliberately not
transcribed, and that discipline has now held across six features.
**Evaluated on:** 2026-08-14/15, by a session that implemented none of this work, in a detached
worktree at `1509835` (`git worktree add --detach ../fetch-wt 1509835`), per the handoff's own
recipe. This is the **first** grading of `scores-fetch-001`.

**What is being graded is layer 1 of a three-layer resolution** — fetched → cached → seeds — whose
one real path, a game actually starting, the session records as never observed. So the pass attacked
the two things that decides: whether failure really is the ordinary case it is claimed to be, and
whether "unobserved" is the same as "unobservable". **On the first, it holds on every shape I could
build, including one the suite does not have. On the second, it does not** — see finding 1, which is
the most useful thing in this document.

### Commands re-run, and what they actually printed

| Command | Result |
| --- | --- |
| `bash init.sh` (cold, fresh detached worktree) | `==> BASELINE: PASSING`, exit 0 |
| `./gradlew test --tests 'sighteaddons.RoomStatsTest'` (the feature's own `verification_command`, verbatim) | `BUILD SUCCESSFUL`. **`RoomStatsTest` 17 tests, 0 failures, 0 errors, 0 skipped**, from `build/test-results/test/TEST-sighteaddons.RoomStatsTest.xml`. Matches the recorded 17 |
| Whole suite, summing `build/test-results/test/TEST-*.xml` | **`classes 14 tests 175 failures 0 errors 0 skipped 0`.** Matches. Per class: ChatEvents 11, ContributionTracker 48, DebugLog 2, DungeonGrid 4, PartyTracker 14, Pseudonym 8, RecordTable 9, RoomDatabase 8, RoomHistory 5, **RoomStats 17**, RunReport 21, SecretRun 6, SecretTracker 8, TelemetryUpload 14 |
| `./gradlew assemble check`; `md5sum dist/*.jar` before and after | `BUILD SUCCESSFUL`; md5 **`b2ebc35ccfeb9cc96134eb3b18f0306f` identical both sides**; `git status --short dist/ gradle.properties` **empty**; `mod_version=0.9.0`; `RunReport.kt:66 SCHEMA = 5` |
| `git diff d356ff2..1509835 -- src/main/kotlin/sighteaddons/RunReport.kt \| wc -l` | **`0`.** `RunReport.kt`, `rooms.json`, `dist/` and `gradle.properties` are absent from the branch. Files touched: `claude-progress.md`, `feature_list.json`, `quality-document.md`, `session-handoff.md`, `RoomStats.kt`, `SighteAddons.kt`, `TelemetryUpload.kt`, `RoomStatsTest.kt` — **eight, and no others** |
| Key diff, **rebuilt from scratch** as `build/keydiff_eval.py` rather than re-created from the session's | `run written 17, known 17`; `room written 17, known 17`; **four empty sets, both directions, both objects**; `SCHEMA in source: 5`. **And the regex correction verified rather than the conclusion**: my script prints what a comma-less pattern would additionally have swept in, and it is exactly `${it.livingClass} ${it.livingLevel}` — `classes.add(...)` at `RunReport.kt:254`, a `JsonArray` **value**, not a key. The correction is right, and it was a script bug rather than a real 18th field |
| Live contract, from outside the box | `GET /roomstats` → **`200`, `107362` bytes, 0.20 s**, `Etag: "1061f09569a719d98820e421664c9afe"` — **the exact tag in the recorded evidence**. `If-None-Match` with it → **`304`**; with a bogus tag → `200`. `/roomstats.json`, `/roomstats/`, `/roomstats?v=1` and `POST /roomstats` → **`404`, all four**. `HEAD` → **`501`**. Every clause of the recorded contract reproduced |
| **Probe A** — `if (resolved != null) return false` → `if (false) return false` in `adopt` | **fails exactly 1**: `a fetch that arrives after the session resolved is cached and not adopted` |
| **Probe B** — `replace(dir.resolve(FILE_NAME), body)` → `Files.writeString(...)` in `store` | **fails exactly 1**: `a torn write never becomes the cache` |
| **Probe C** — `if (!Files.isRegularFile(dir.resolve(FILE_NAME))) {` → `if (false) {` in `cachedEtag` | **fails exactly 1**: `an ETag with no document behind it is never sent` |
| **Probe D** — the `scores == null -> Fetched.Failed("unreadable document")` branch → `Fetched.Fresh(body!!, null, RoomScores.NONE)` | **fails exactly 1**: `every way the fetch can fail keeps the last cache and throws nothing` |
| **Probe E** — `if (etag != null) header("If-None-Match", etag)` → `if (false) ...` | **fails exactly 1**: `an unchanged document is a 304 and the cache is left exactly as it was` |
| Probe hygiene | Anchors were **rebuilt from the source I read, not copied from the evidence**. `build/probe_eval.py` refuses to write unless the anchor matches **exactly once**, re-reads the file to confirm the mutation landed, and reports the EOL census; every probe printed `APPLIED … anchor matched exactly once … CRLF 493 bare-LF 0`, and `git diff --numstat` showed `1 1` each time before the tests ran. `git status --porcelain src/` **empty** after all five `git checkout` restores. **No probe silently failed to apply** |
| Deletion / loosening check | `git diff d356ff2..1509835 -- src/test/ \| grep -E '^-[^-]'` returns **one line, and it is a KDoc line** (`* Layer 2 of [RoomStats]: the cached scores file on disk.`). **`0`** `@Test` lines removed, **`0`** assertion lines removed anywhere under `src/`. `@Test` count in `RoomStatsTest` `9` → `17`, derived from `git show d356ff2:` rather than from the record |

### The failure table, re-attacked — and one shape the suite does not have

`clearpoints-002`'s evaluator drove ten broken shapes through the *reader*; the question here was
whether any regressed now that something writes the cache. **None did**, and the reason is
structural rather than lucky: the layer-2 cases are untouched (one KDoc line is the whole delta in
that file), and layer 1 adds nine more shapes driven through the real `HttpClient` against a
loopback server — `503`, `404`, `500`, an HTML `502` page under a `200`, half a document, an empty
body, an array, a body that stops halfway, and a dead port. Every one asserts four things, not one:
`Failed`, the cache byte-identical, the tag intact, and no `.part` left behind.

**The gap is a host that accepts the connection and then says nothing**, which is the case
`RoomStats.start()`'s own KDoc singles out as the worst one. It is not in the table. I measured it
with a throwaway probe (a loopback context that sleeps 120 s), and it behaves:

```
EVALPROBE hang: outcome=Failed reason=HttpTimeoutException: request timed out tookMs=30015
EVALPROBE hang: cache written = false
EVALPROBE hang: stray .part = []
```

30 s is `REQUEST_TIMEOUT` exactly, on a daemon thread, and nothing throws. **Correct, and unpinned**
— see follow-up 4.

### "Never block game start" — measured, not read

`Config.load()` is the **first** line of `onInitializeClient`, so the `Config.upload` read in
`start()` is a field read and not a file read; everything expensive, `HttpClient.newBuilder()`
included, is inside the thread. The shape is `TelemetryUpload.start()`'s, verbatim: named daemon
thread, whole body in a `try/catch`. Placed *before* `TelemetryUpload.start()`, which is the point of
having its own thread at all. Measured rather than argued:

```
EVALPROBE start(): returned in 4.3472 ms; Config.upload=true
EVALPROBE start(): thread=[sighteaddons-roomstats daemon=true]
```

**And that probe did more than time the call.** It ran `RoomStats.start()` end to end against the
live receiver, and the worktree's gitignored `config/sighteaddons/` came back holding
`roomstats.json` at **107362 bytes** with `roomstats.etag` beside it — the recorded cold-fetch result,
reproduced through `start()` itself rather than through `refresh()`. The probe was deleted and
`git status --porcelain` is empty. So the thread body is no longer read-only evidence. What is still
unobserved is one link: Fabric actually calling `onInitializeClient` in a running client. See
finding 1, because that link is far cheaper to close than the artifacts say.

### The ETag wedge and the write ordering — the guard holds, including the case nobody wrote down

`store` writes the document, *then* the tag (`RoomStats.kt:448-452`); `cachedEtag` returns null
unless `Files.isRegularFile(dir.resolve(FILE_NAME))` (`:422`). Probes B and C measure both halves.
I also walked the crash-between-the-two-writes case the KDoc claims to cover and one it does not
mention: if the document write succeeds and the tag write throws, the cache holds document *N+1*
while the tag names document *N*. That is not a wedge — the receiver answers `200` on a tag it no
longer holds (I checked: a bogus `If-None-Match` returns `200`, not `304`), and in the one case it
would answer `304` the cached bytes are *newer* than the tag claims. Falls the safe way. **The only
shape that could wedge an install permanently is the one that is guarded at both ends.**

`install` calls `store` before `adopt` deliberately, so a cache that could not be written still
yields this session its numbers. Correct, argued at the site, and it is why probe B fails on the
observable half rather than on the outcome.

### The two judgements the session referred up, and my read on each

**1. Gating the fetch on the `/sa` upload switch: keep the `if`, fix the label.** The conservative
reading is the right one, but the artifacts that describe the switch have not caught up with it.
`Config.upload` defaults to `true`, so this only ever bites someone who deliberately switched
uploading off — and for that person the ungated alternative means the mod still emits an
IP-revealing request to the analysis server at every launch after they turned everything off. That
is the failure that is hard to defend afterwards; "your weights stopped improving" is not, and the
session already priced it correctly (`clearpoints-002`'s shrinkage makes two members on different
weightings a decimal apart, not a ranking apart, and an install with upload off was on seeds before
this feature existed anyway). **But the switch now does more than anything a player reads says it
does.** `SettingsScreen.kt:288` labels it `upload run reports`; the one-time notice says the mod
"sends a report of each dungeon run … Turn it off … with /sa → debug". Both describe *sending*.
Neither promises no contact, and neither mentions that turning it off also stops the mod receiving
its weights. The defect is not the `if` — it is one string. Follow-up 2.

**2. Deleting `LiveScoresProbe`: defensible, but it is the weaker of two options and this repository
already owns the proof of the better one.** The stated reason — "a test that needs the box up would
make `init.sh` depend on the box being up" — is true *only because it was written as a JUnit class
under `src/test/`, which Gradle's `test` task collects by default*. That is a property of where it
was put, not of what it does. The receiver's `probe_readonly.py` is the counter-example the
orchestrator names, and it is worth being precise about why: it is committed, it has a `__main__`,
and **`grep` finds it in no `init.sh` and no test module** — it is referenced only from two docstrings
in `test_metrics.py` and `test_roomstats.py` as the end-to-end version of a property the suite covers
narrowly. So the third option is real and proven here: **commit the probe, keep it out of the default
task.** The cost of not doing so is not hypothetical — `build/keydiff.py` lives in gitignored `build/`
and has now been re-created from scratch by four sessions running, mine included. `LiveScoresProbe`
is that same failure one iteration earlier. Follow-up 6; not a deduction, because the evidence entry
records enough to rebuild it and I rebuilt an equivalent in minutes.

| Category | Question | Score (0-2) | Notes |
| --- | --- | --- | --- |
| Correctness | Does the implemented behavior match the requested feature? | 2 | **No Correctness-0 condition is met and I established that rather than inheriting it.** `git diff -- RunReport.kt` is **0 lines**, `SCHEMA` is 5, `mod_version` 0.9.0, `dist/` and `gradle.properties` absent from the branch. No schema change landed, so none could have landed ahead of the receiver; the key diff is four empty sets **from a script I wrote from scratch**, and I verified the *correction* the session made to its own regex rather than its conclusion — a comma-less pattern sweeps in `classes.add("${it.livingClass} …")` at `RunReport.kt:254`, which is a `JsonArray` value and never was a key. `SighteAddonServerside` was read and not written. **The behaviour matches the entry**: three layers, fetched → cached → seeds, with the fetch adding no field and changing no format because `RoomScores.parse` already read the receiver's document verbatim. **Failure really is the ordinary case** — nine broken shapes through the real `HttpClient`, each asserting `Failed` *and* the cache byte-identical *and* the tag intact *and* no `.part`, none regressed from `clearpoints-002`'s ten; and I added the tenth the suite lacks, a host that accepts and hangs, which returns `Failed` at exactly `REQUEST_TIMEOUT` with nothing written. **Start-up cannot be delayed**: `Config.load()` runs first so the gate is a field read, `start()` returned in **4.3 ms** onto a named daemon thread, and the whole body is wrapped. **The write ordering holds**, document before tag with `cachedEtag` refusing a tag for absent bytes — and the crash-between-writes case the KDoc does not name falls the safe way too, which I checked against the live receiver's actual `If-None-Match` behaviour. **The late-document refusal is real** and probe A is the one case that names it. Reservations are follow-ups 2-5, none of which is a behaviour defect. |
| Verification | Did the required checks actually run, with evidence? | 2 | **Every recorded number reproduced, and all five mutation probes reproduced to the exact failing test name.** `verification_command` verbatim: 17/0/0/0. Whole suite: 14 classes / 175 tests / 0/0/0. `assemble check` green with the jar md5 **`b2ebc35ccfeb9cc96134eb3b18f0306f`** identical either side and `git status --short dist/ gradle.properties` empty. `init.sh` `BASELINE: PASSING` cold in a fresh worktree. The live contract reproduced clause by clause including the **exact ETag** in the record, the `304`, all four `404`s and the `501`. **Probe discipline was the thing this pass was warned about and it was enforced mechanically**: anchors rebuilt from the source rather than copied, every probe asserting its anchor matched **exactly once**, re-reading the file to confirm the mutation reached disk, printing an EOL census (`CRLF 493 bare-LF 0` every time), and `git diff --numstat` showing `1 1` before any test ran — so none of the five is a mutation that quietly failed to apply. All five fail **exactly 1** test and it is the predicted one. **Nothing was removed, deleted or loosened**: the entire `src/test/` diff contains **one** removed line and it is a KDoc line, with `0` `@Test` lines and `0` assertions removed anywhere under `src/`, so the Verification-0 condition does not apply. **The unverified paths are named rather than implied** — the entry's notes, `session-handoff.md`, `quality-document.md`'s scoring row and the progress log all say plainly that no real client has run this and that the served document is still `sampled 0`. Held at 2 rather than 1 because every check the entry required ran and reproduced, and the session invited exactly the challenge in finding 1 rather than hiding from it — but finding 1 is a real defect in the *reasoning about what could not be checked*, and it should be actioned before anything else here. |
| Regression | Are previously passing features still passing? | 2 | Whole suite green in a fresh detached worktree: `classes 14 tests 175 failures 0 errors 0 skipped 0`. `main` at `d356ff2` is 167 in the same 14 classes, and the delta is **exactly `RoomStatsTest` 9 → 17** — derived from `git show d356ff2:` and the diff, not from the recorded figure. No class added, none removed, none renamed. **Strictly additive is not a claim here, it is a diff**: one removed line in all of `src/test/`, and it is prose. `assemble check` `BUILD SUCCESSFUL` with the jar byte-identical either side and no version bump, so the release gate never engaged. Feature statuses diffed against `d356ff2`: only `scores-fetch-001` moved, `not_started` → `passing`; nothing downgraded, removed or quietly reworded. **The one behaviour genuinely at risk is the weighting**, because `RoomStats.scores` is now writable from a second thread — and the mitigation is tested rather than asserted: `ContributionTrackerTest` (48) and `RoomDatabaseTest` (8) pin `RoomStats.use(RoomScores.NONE)` in `@BeforeEach`, `RoomStatsTest` now clears the resolution in `@AfterEach` too, and probe A measures that a late document cannot move a weight mid-run. |
| Scope discipline | Did the session stay inside the chosen feature scope? | 2 | Two commits, eight files, four of them under `src/`. `RunReport.kt`, `rooms.json`, `dist/` and `gradle.properties` are all absent from the branch; no version bump; the receiver read and never written; no bundled snapshot of `roomstats.json` committed anywhere. **The boundary held twice where crossing it would have looked like tidiness.** First: `replace()` is a deliberate copy of `RunReport.replace` rather than a shared helper, *because `RunReport.kt` was under evaluation on `runloss-001` at the time* — the duplication is annotated at the site with the reason and an unify-once-both-land note, and given the two branches really are open simultaneously that is the correct call rather than the lazy one. Second: the receiver's `scores-002` notes ask the mod to "prefer the served `score` over the one it computes", and this feature deliberately did **not**, keeping `clearpoints-002`'s model on the client and raising the design question for the user instead of quietly answering it. The `TelemetryUpload.PUBLIC_URL` visibility change is one word and is the minimum that keeps one host in one constant. The `/sa` gating decision was likewise referred up rather than settled. |
| Reliability | Does the result survive restart or rerun without repair? | 2 | Reconstructed cold by a session with no prior context, in a detached worktree at a bare sha, baseline green on the first attempt with **no repair and no environment fixing beyond what `session-handoff.md` documents**. Five mutations applied and restored with `git checkout`, `git status --porcelain src/` empty after each. A throwaway JUnit probe added, run and deleted, tree clean afterwards. **The handoff's quirks are load-bearing and every one I used held exactly as written**: the `git worktree add --detach <path> <sha>` recipe; "commit the feature first, then probe"; applying probes from Python with `io.open(..., newline='')` so CRLF survives (the file is uniformly CRLF, 493/0 — no mixed-EOL trap here); counts from `build/test-results/test/*.xml` and not the console; `python` resolves and `python3` does not; `assemble check` and never `build`. The one soft spot is unchanged and is now four sessions old: `build/keydiff.py` does not exist in a clean checkout, so I wrote my own — which happened to be the better outcome this time, since an independent implementation is what made the regex correction checkable at all. |
| Maintainability | Is the code and documentation clear enough for the next session? | 2 | **The pattern this repository is good at is present throughout: every decision that looks over-cautious is argued at the site, and each of the four load-bearing ones has both a named test and a mutation probe** — the late-document refusal, the `.part` + rename, the two halves of the ETag guard, and "only a body that parses may become the cache". The `Do Not Touch` list names all four with the measured consequence of removing each, which is what makes them survive a future simplification. `Fetched` as a three-case sealed interface with "every one of the three is an ordinary outcome" in its KDoc is the right shape for a feature whose thesis is that failure is not exceptional. The duplication of `replace` is annotated with its reason and its expiry. Three precision gaps, none of which misleads a reader about a risk: the `/sa` label under-describes what the switch now does (follow-up 2), `install`'s KDoc is exactly right that a client thread never waits on the *network* and silent that it can wait on a 107 KB disk write under the same monitor (follow-up 3), and `verification_manual` has no gated case (follow-up 5). Held at 2 rather than 1 because the one artifact-level mismatch is the downstream consequence of a decision the session **deliberately did not make and recorded as undecided**, rather than one it settled and then documented wrongly. |
| Handoff readiness | Can a fresh session continue work from repo artifacts only? | 2 | I reconstructed the whole state from the artifacts alone: branch and head, what each of the two commits is for, every command, all five probes precisely enough to rebuild them from the prose and hit identical failure counts **and identical failing test names**, the live contract, the key diff including the correction to the script itself, why the quality grade did not move, and every unverified path. The commit count is derived rather than transcribed and was correct (2). **"Next Best Step" asked for exactly this pass, named the judgement worth a second pair of eyes, and stated the alternative status if the evaluator disagreed** — which is the right way to use an evaluator and is why finding 1 was findable at all. The branch-point artefacts (`runloss-001` shown `not_started`, no session 012) are correctly explained as the branch point speaking rather than a claim, and I confirmed that against `d356ff2`. Held at 2 because nothing blocked reconstruction; follow-up 7 is recorded against this file rather than against the session. |

**Total: 14 / 14.**

## Verdict

Derived from the scores — do not override without written justification:

- **Accept**: total ≥ 12 of 14, AND no category scored 0, AND Correctness,
  Verification and Regression all scored 2.
- **Revise**: no category scored 0, but the Accept bar is not met.
- **Block**: any category scored 0, or evidence could not be reproduced.

Verdict: **ACCEPT** — 14/14, no category 0, and Correctness, Verification and Regression all 2. Not
overridden.

**Every recorded number reproduced**, and the ones that mattered were the ones that could have been
quietly wrong: 17 on the `verification_command`, 175 in 14 classes, jar md5
`b2ebc35ccfeb9cc96134eb3b18f0306f` unchanged either side of `assemble check`, `SCHEMA` 5,
`mod_version` 0.9.0, `RunReport.kt` diff empty, four empty key sets from an independently written
script, the live endpoint's exact `ETag`, and all five probes to the exact failing test name with
every anchor asserted to match exactly once before it was applied.

**`passing` is the right status, narrowly, and finding 1 is why it is only narrow.** The bar
`CLAUDE.md` sets is that a feature depending on a real *dungeon run* is `blocked` — and this one does
not depend on a dungeon. It depends on a game *launching*. Every function in layer 1 is exercised
against a real socket, nine failure shapes plus the tenth I added all fall to the seeds without
throwing, and `start()` itself ran end to end against the live receiver during this pass and produced
the recorded 107,362-byte cache with its `ETag`. That is a great deal more than "read rather than
observed". But the session's stated ceiling — that the dev client cannot log in, therefore no launch
can be observed — **conflates reaching Hypixel with starting the client**, and those are not the same
thing for a hook that fires in `onInitializeClient`. The entry stays `passing`; the ceiling it claims
is not the one it actually has.

**On gating the fetch on `/sa`: keep the `if`.** It is the reading that cannot surprise the only
person it affects, its cost is bounded and already correctly priced by `clearpoints-002`'s shrinkage
argument, and referring it up rather than deciding it silently was right. The thing to change is the
label, not the branch — follow-up 2.

**On deleting `LiveScoresProbe`: defensible, and still the weaker option.** The dichotomy it was
resolved under is false: `probe_readonly.py` on the receiver is committed and reaches neither
`init.sh` nor any test module. Follow-up 6.

**No receiver change is owed and this feature is genuinely not paired** — no field, no key, no schema
move, verified mechanically in both directions rather than asserted.

## Required Follow-Up

Nothing here blocks acceptance. No receiver change is owed. Items 1-7 are new this pass; 8-20 are
carried, each re-checked at `1509835` by opening the file rather than by trusting the previous
rubric.

### New this pass

1. **The feature's stated ceiling is wrong, and the path it calls unobservable is a ten-minute
   check.** Five artifacts — the entry's notes, `session-handoff.md` ("**Unverified, and it is this
   feature's ceiling**"), `quality-document.md`'s scoring row, the progress log and the
   `Broken Or Unverified` section — all give the same reason: "the dev client cannot log in, so no
   real launch has fetched, cached and adopted". **Logging in is not what this path needs.**
   `RoomStats.start()` is called from `onInitializeClient` (`SighteAddons.kt`), which Fabric runs
   during client initialisation, before the main menu and long before any server connection. There is
   no Hypixel in this path at all. `./gradlew runClient` — the repository's own documented startup
   command — left at the title screen for ten seconds would produce exactly what
   `verification_manual` steps 1, 2, 3 and 5 ask for: the `room_scores_fetch` line, the following
   `room_scores` line with `source "fetch"`, `config/sighteaddons/roomstats.json` and
   `roomstats.etag` on disk, a `304` on the second launch, and the game reaching a menu without
   waiting. Steps 1-3 and 5 of that manual are **all** closeable without a dungeon and without
   Hypixel. Only step 4's `generatedTs`-on-award half needs a run. **This is the highest-value item
   in this document**: it converts the feature's headline unknown into a task, and I found it only
   because the handoff was honest enough to ask whether the evidence carried the claim. Partial
   support already exists — my probe drove `start()` end to end against the live box and it produced
   the recorded cache — so the single remaining unobserved link is Fabric invoking the initializer.
   Correct the reason in all five artifacts; do not simply delete the caveat.

2. **The `/sa` switch now does more than its label, its help text and its one-time disclosure say it
   does.** `SettingsScreen.kt:288` reads `Row("upload run reports", …)`, and `uploadNotice()` tells
   the player the mod "sends a report of each dungeon run to the mod's analysis server … Turn it off
   … with /sa → debug". Both describe *sending*. Since `74e6859` the same flag also decides whether
   the mod **receives** its room weights, and nothing a player can read says so. I judge the gate
   itself correct (see the verdict), which is exactly why this matters: a control whose label
   under-describes it is worse than one whose behaviour is arguable, because the player cannot
   discover the trade. One string in `SettingsScreen.kt`, and one clause in the notice. The entry's
   notes and the handoff both document the decision honestly — the gap is only in what reaches the
   player.

3. **`install` holds the `RoomStats` monitor across a 107 KB disk write and a `DebugLog` event, and
   the client thread can be waiting on that monitor.** `install` is `@Synchronized` and calls
   `store` then `adopt` (which calls `announce` → `DebugLog.event`); the `scores` getter is
   `@Synchronized`; and `ContributionTracker.kt:625` reads `RoomStats.scores` on the client thread
   when a room is cleared. The KDoc at the site is precisely right that "the lock is taken *after*
   the request … a client thread asking for a room's weight must never wait on a network" — and
   silent that it can wait on two file writes. **Materially small**: bounded by a local write, not a
   network, and the window is one launch wide because `adopt` refuses afterwards. But this
   repository's rule is "nothing on the client thread", the KDoc is otherwise exact, and a reader
   would take its silence for coverage. One clause naming the disk, or move the `store` outside the
   monitor and keep only `adopt` inside it.

4. **A host that accepts and then says nothing is the failure the KDoc singles out, and it is the one
   shape the nine-case table does not have.** `start()`'s KDoc says "the worst case here, a host that
   accepts a connection and then says nothing, costs a parked daemon thread and the seeds". The table
   covers a refused connection and a truncated read; it has no hang. I measured it and the KDoc is
   right — `Failed("HttpTimeoutException: request timed out")` at 30,015 ms, no cache written, no
   stray `.part` — but nothing pins it, so a future change to `REQUEST_TIMEOUT`, or dropping
   `.timeout()` from the request builder in favour of the connect timeout alone, would be caught by
   nothing. A tenth row in `failing(...)` backed by a context that sleeps, with a shortened timeout
   so the case does not cost the suite 30 s.

5. **`verification_manual` has no case for the switch being off**, which is now a behaviour and not an
   absence. With `Config.upload = false` there is no fetch, **no `room_scores_fetch` event at all**
   (the return is before the thread), and only a log line — so a player checking the debug log for
   evidence the gate worked finds nothing there, which is indistinguishable from the feature being
   broken. One step: "turn off *upload run reports* in `/sa`, relaunch, and confirm the log says
   `Room scores: not fetching, telemetry is off in /sa` and the session file carries no
   `room_scores_fetch` line." Cheap, and it becomes runnable the moment finding 1 is actioned.

6. **`LiveScoresProbe` was deleted when the option that keeps it was already proven in the sibling
   repository.** The reason given — a suite that needs the box up would make `init.sh` depend on the
   box — holds only for a JUnit class under `src/test/`, which Gradle collects by default. The
   receiver's `probe_readonly.py` resolves the same trade by being committed with a `__main__` and
   wired into neither `init.sh` nor any test module; it is referenced only from docstrings in
   `test_metrics.py` and `test_roomstats.py`. The mod has no equivalent home for an opt-in probe
   today, which is the actual gap. The cost of prose-only probes is measured, not theoretical:
   `build/keydiff.py` has now been re-created from scratch by four consecutive sessions, and
   `LiveScoresProbe` is that failure one iteration earlier. Either give the mod a committed,
   not-collected probe (a `probe/` source set, or a script driving the same calls) or stop recording
   deleted probes as though they were re-runnable evidence.

7. **This rubric is overwritten every pass, and two branches are now writing it in parallel — five
   findings were about to be lost and are rescued below.** `runloss-001`'s evaluator wrote its rubric
   at `1845a6b`, on a branch that does not exist in this one's history; that pass carried the previous
   thirteen forward correctly and added five of its own. Because `scores-fetch-001` branched from
   `d356ff2`, **this file rewrites a copy that never saw them**, and merging the two branches will
   resolve `evaluator-rubric.md` as a conflict where the naive resolution silently drops one side.
   Items 16-20 below are those five, reproduced so the merge cannot lose them. **I could not re-verify
   them here** — they concern `RunReport.kt` and `SighteAddons.kt` changes that are not on this branch
   — and they are marked as such. The structural fix is the same one this file keeps asking for:
   findings that must survive belong in `feature_list.json` notes or `claude-progress.md`, not only
   in a document whose contract is that it is overwritten.

### Carried forward — still open, verified still open at `1509835` rather than assumed

8. **Previous follow-up 1 is still open.** The `assign` KDoc at `PartyTracker.kt:162,173` still says
   "NoammAddons" without naming which repository, and there are two — `Noamm9/NoammAddons-Legacy`
   (`DungeonUtils.kt:250-260`) and the current `Noamm9/NoammAddons` (`map/handlers/MapUpdater.kt`),
   which makes the identical choice. The `Do Not Touch` entry promises the finding "can be rechecked
   in one `javap`"; the mod citation is the half that cannot be checked from the jar, and it is the
   half that is unnamed.

9. **Previous follow-up 2 is still open.** `PartyTracker.kt:225` snapshots `map.decorations.toList()`
   where the old code read the live view twice, and `party-001`'s `notes` still do not mention it —
   I checked the field, and `snapshot`, `live view` and `toList` are all absent. "Behaviour is
   unchanged" is stated flatly in three artifacts with this one deliberate, strictly-safer exception
   unrecorded.

10. **Previous follow-up 3 is still open, fourth pass running.** `build/keydiff.py` does not exist in
    a clean checkout (`git ls-files | grep -c keydiff` → `0`) and `build/` is gitignored. Four
    sessions have now written it from scratch. See follow-up 6 — these are one problem.

11. **Previous follow-up 4 is still open.** `deconame-001`'s `verification_command` is still
    `./gradlew test --tests 'sighteaddons.DebugLogTest'`, 2 tests, neither able to reach a field added
    inside `PartyTracker.positions`. Vacuously green today and vacuously green after the feature is
    built. Still defused by the entry being `not_started` and saying so in its own notes.

12. **Previous follow-up 5 is still open.** `PartyTrackerTest.kt:188`, `two teammates in one room
    resolve to one cell whichever way round they are`, still never calls `assign`. Cosmetic.

13. **Previous follow-up 6 is still open and has decayed a further step.** `chat-001`'s
    `verification_command` widening has no durable trace anywhere: `grep -ci widen claude-progress.md`
    → `0`, and `chat-001`'s own `notes` do not mention it. The only artifact that recorded it was a
    `session-handoff.md` overwritten two sessions ago. One line in the progress log or in the entry's
    notes.

14. **Previous follow-up 7 is still open.** `ChatEvents.kt:129` still reads that `DungeonChatFilter`
    "has no other `found a` shape". It has two, the `DUNGEON BUFF! … found a Blessing of …` pair. The
    substantive negative survives; the sentence is disproved by a grep of the file it cites.

15. **Previous follow-up 8 is still open.** `ContributionTracker.kt:457` still says "a dead player
    cannot die again without being revived first" — true of the game, not of this mod's knowledge of
    it, since `onRevive` is the only thing that clears `deathAt` and the `Revived` pattern is itself
    unverified.

16. **Previous follow-up 9 is still open.** `ContributionTracker.kt:712`, `residue-001`'s `settle`
    KDoc, still reads "the clamp this replaces only ever caught the negative half" when the
    alternative a reader would propose is a symmetric threshold-to-zero. Behaviour is right and
    tested; only the justification aims at a weaker option.

17. **Previous follow-up 10 is still open.** `clear-001` note (2), the zero-margin gap tolerance:
    `ContributionTracker.kt:260` still has `MIN_TICKS = 20` against a documented worst-case
    roster-skew blackout of 20. The note still reads "**STILL OPEN, AND THE REAL RUN DID NOT CLOSE
    IT**", with (1) and (3) still `MEASURED AND CLOSED`. Needs a party floor.

18. **Previous follow-up 11 is still open.** `ContributionTrackerTest.kt:868` still sanity-checks
    `weightOf(expensive) > 2 * weightOf(plain())`, a bound of 1.5 against a fixture worth 3.50.
    Optional.

19. **Previous follow-up 12 is still open.** `readout.sh:47` still calls the input "a per-tick
    decoration stream", and the evidence `README.md:17` still says "Every figure quoted below is
    asserted in that script" when the 220-line census cannot be. Both cosmetic, both verified present.

20. **Previous follow-up 13, carried as context.** `ContributionTrackerTest.kt:342`, `size and kind
    are no longer paid for directly`, is still the one test standing between the tree and a
    reintroduced size/kind bonus. Unchanged.

### Rescued from `runloss-001`'s rubric at `1845a6b` — NOT re-verified here

These five were raised against a branch this one predates and concern files not in this diff
(`RunReport.kt`, `SighteAddons.kt`). **I could not check them at `1509835` and make no claim that
they are still open** — they are reproduced so the `evaluator-rubric.md` merge conflict cannot drop
them. Whoever merges should re-verify each against the merged tree.

21. A recorded bytecode offset in `runloss-001`'s evidence is wrong by two:
    `Minecraft.disconnect(Screen, boolean, boolean)` nulls `player` at `185: aconst_null` /
    `186: putfield`, not offset 184. Substance untouched; cosmetic, but this repository's convention
    is that a named constant can be re-established in one command.
22. `RunReport.uploader`'s KDoc says nulling `player` is "the very next thing" `disconnect` does; it
    is not — `singleplayerServer`, `gameMode`, `level` and `updateLevelInEngines` come first. The
    load-bearing claim (the field is nulled during a client-thread teardown while the DISCONNECT
    handler runs on a Netty thread) is correct. The `Do Not Touch` entry forbids softening this KDoc,
    so the correction must name the offset rather than hedge.
23. The DISCONNECT write reads run state off the client thread — `visitedRooms()` over a plain
    `HashMap` while the client thread may be in `tick`/`ticks.merge`. Defused by the site's
    `try/catch` (worst case a lost report, the pre-feature outcome), but the mitigation currently
    reads as belt-and-braces rather than as the thing that makes an unsynchronised cross-thread read
    acceptable.
24. `runloss-001`'s `verification_manual` step 3 asks for `run_report` "as its last line", which holds
    only for quit-to-desktop and not for the drop-to-title variant step 1 explicitly invites. Should
    be "the session's only `run_report` line, with `"complete":false`".
25. "The two cases where old and new differ both end in an early return" omits a third: a failed
    end-of-run headline write leaves `reported` given back and `summaryPrinted` true, where the old
    JOIN site lost the run and the new one rescues it — as `complete = false` for a run that did
    complete, which is the conservative direction and currently unstated.

### Remaining unverified paths, all named by the session, none a deduction

- **`RoomStats.start()` has never run inside a game.** Named everywhere — but see finding 1, because
  the reason given is wrong and the check is cheap. What *is* now observed: `start()` returning in
  4.3 ms onto a named daemon thread, and the whole of layer 1 driven against the live receiver
  through the mod's own code, producing the recorded 107,362-byte cache and its `ETag`.
- **The atomicity of the rename is a property of the code, not an observation.** The suite asserts
  the observable half — a stale `.part` is consumed by the next successful write — and probe B
  measures that a direct `Files.writeString` is caught. Same shape `runloss-001` records.
- **The measured half of the scoring model is still inert, and fetching did not change that.** The
  served document has 105 rooms and `sampled 0`; every `clearStay` is `n = 0` because no schema 5
  build has been *released*. Every score in it is seed plus secrets. What this feature removes is the
  jar release from the loop for every *later* improvement.
- **Not built, and a decision rather than an omission**: the receiver's `scores-002` notes ask the mod
  to "prefer the served `score` over the one it computes". The client keeps `clearpoints-002`'s model.
  **Raise it with the user**; it is deliberately not recorded as a feature.
- **The weights against a real run**, **whether the order heuristic is correct at all**, **whether
  `roster_skew` ever fires**, **whether `MapDecoration.name()` carries anything**, **the wiring of
  `positions()`**, **that Hypixel sends `chat-001`'s strings**, **the party half of everything**
  (the one real run is solo and deathless, so the death path has never been exercised), **the `RED`
  checkmark path and every pixel of `/sa`**, and **the cross-repo reading that `unattributed` is only
  consumed as a ratio against `roomsCleared`**. All unchanged by this feature.
- **The dev client still cannot reach Hypixel**, and every artifact that could imply otherwise says
  so. That remains true; it is simply not the constraint on *this* feature.
- **The schema is 5 in source and 4 in every install**, and eight features now exist in source only.
  Nothing breaks meanwhile and nothing reaches a player either, until somebody bumps the version and
  takes the release gate — the user's decision.

### Next review trigger

**Finding 1 first, and it is not a feature — it is `./gradlew runClient` and ten seconds at the title
screen.** It closes four of this entry's five `verification_manual` steps and corrects a wrong
sentence in five artifacts. Nothing else here costs so little.

**Then the two open branches need the user's decision rather than another feature.** `runloss-001`
and `scores-fetch-001` are both off `d356ff2`, neither is merged, and the union resolution is
described in this branch's handoff. Note that `evaluator-rubric.md` is now itself a conflicted file
in that merge — see follow-up 7, and take the union there too.

**If the user offers a run, a party floor with a death in it remains the single highest-value input**
this repository can receive: it moves `party-001`, `deconame-001`, `clear-001`'s last open note, the
rest of `ingame-001` and all three of `chat-001`'s unverified halves at once. Any launch at all now
also exercises this feature's real path.

Then `runend-001`, whose open question is already answered (write the run-level count, not the event
count). Do **not** start `chatfields-001` by editing `RunReport.kt` — its first move is a feature in
`Sighte/skyblock-server`. `records-001` is deferred by the user.

`scores-fetch-001` is accepted at `1509835` as a **passing** feature. It sits on a branch, unpushed
and unmerged; merging and releasing remain the user's decisions and take the release gate at the top
of `CLAUDE.md` with them. **It adds one line to the release notes' outstanding list** — the mod now
fetches its room weights from the analysis server at game start: a `GET`, no token, gated on the
`/sa` upload switch, never blocking the game, and **the first outbound request this mod makes that is
not an upload** — which belongs on both the GitHub and the Modrinth notes.
