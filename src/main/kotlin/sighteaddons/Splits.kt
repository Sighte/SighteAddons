package sighteaddons

import sighteaddons.ui.Format

/**
 * How long each part of the run took — Odin's `SplitsManager`, which is the timing half of its Splits
 * feature. [DungeonSplits] holds the sentences; this holds the clock.
 *
 * ### The chain, and what a row means
 *
 * A run is a list of marks. Each mark is stamped the first time its line arrives, and the *span* between
 * two consecutive marks is credited to the **earlier** one's name — so the row labelled `maxor` is the
 * time from Maxor's opening line to Storm's, not the time it took to reach Maxor. That is Odin's
 * convention, it is the one [BloodClear] already documents, and it is the reason the first mark
 * (`blood open`, Mort's line) is the run's zero point rather than a span of its own.
 *
 * The last mark of every chain is `total` and it is never a row. Its span is the whole run — first mark
 * to last — and it is reported once, as [Readout.totalMs].
 *
 * ### Two clocks, and why neither is the run tick
 *
 * Every span is measured twice: in wall-clock milliseconds (`System.currentTimeMillis`) and in Hypixel's
 * server ticks ([ServerTicks]). Odin shows both, and they answer different questions — the wall clock is
 * what a stopwatch beside the screen would say, the tick count is what the run cost on the server and is
 * therefore what two people can compare. Neither is [DungeonSession.runTicks], which starts at map
 * calibration rather than at Mort's line and is systematically short (`TODO.md`); it is the right clock
 * for a room and the wrong one for a floor.
 *
 * Records are kept in wall-clock seconds, because that is Odin's unit and these are Odin's records —
 * [SplitPbs] carries that argument in full.
 *
 * ### Where the time comes from, and why it is a parameter
 *
 * [onChat] and [readout] are handed the two clock readings rather than reading them. That is
 * [StormTimer]'s shape and it is here for the same reason: it makes the whole of the arithmetic — the
 * spans, the running row, the total, the boss-entry aggregate — reachable from a unit test that has no
 * client, no server and no clock. What is left at the call sites is two field reads.
 *
 * ### Not gated on calibration
 *
 * `Starting in 1 second.` arrives *before* the map is readable, so [SighteAddons.onDungeonEvent]'s
 * `calibrated` gate would drop the one line that starts a run. [onChat] is therefore called from the
 * ungated part of [SighteAddons.onChat], next to the crit and storm readers, which are ungated for their
 * own version of this reason. Nothing here needs the map: a split is a chat line and a clock.
 */
object Splits {

    /**
     * Hypixel's countdown line, which is what arms a run. Odin's trigger, verbatim and whole-line.
     *
     * A literal rather than a pattern because that is what it is — and because the failure it can have
     * is worth naming: if this string is ever wrong, no run is ever armed and every split is silently
     * absent. `splits_armed` in the debug log is what distinguishes that from a chain that armed and then
     * missed its lines.
     */
    const val START_LINE = "Starting in 1 second."

    /**
     * One mark of the chain. [atMs] is `0L` until its line has arrived, which is Odin's own "not yet"
     * (`if (currentSplit.time != 0L) return`).
     */
    private data class Mark(val split: DungeonSplits.Split, val atMs: Long = 0L, val atTicks: Long = 0L) {
        val reached get() = atMs != 0L
    }

    /**
     * The run as it stands.
     *
     * Replaced wholesale on every change rather than mutated in place, and that is deliberate: the
     * renderer reads this from the frame thread while chat writes it from the client thread, so a list of
     * mutable marks could be read with one field of one mark already updated and the other not — a tick
     * span that disagrees with its own time for one frame. A dozen small allocations per run buys a
     * consistent read, which is the same trade [sighteaddons.ui.hud.HudSnapshot] makes for the card.
     */
    @Volatile
    private var marks: List<Mark> = emptyList()

    /** The floor this chain belongs to, in [SoloClear.floorTag]'s spelling. Records key on it. */
    @Volatile
    private var floorTag: String = "?"

    /** What each landing turned out to be against the record, parallel to [marks]. */
    private val results = ArrayList<SplitPbs.Result?>()

    /** Guards the total against a second `Defeated` line — one run files one total. */
    private var closed = false

    /**
     * Whether the run-end headline has been seen and the summary is waiting for the chain to close.
     *
     * **Measured, and it is the opposite of what this code first assumed.** On the M7 of 2026-08-19
     * 23:22, `run_end` and the `☠ Defeated` line arrived on the same tick with the *headline first* —
     * so a summary printed at the headline reported `cleared` as a span still running and never printed
     * the total at all. Arming at the headline and releasing when the chain closes is `SoloClear`'s
     * pattern for the same discovery (`TODO.md`: the headline arms, the score line releases), and it is
     * right in both orders: a chain already closed prints at the headline instead.
     */
    private var pendingSummary = false

    /**
     * Set once the summary has gone out, so nothing prints a run twice.
     *
     * `internal` because it is the one observable of the arm-and-release decision that a test can hold:
     * [Chat.say] needs a `Minecraft` and [takeSummary] hands its answer to the printer, so "did the
     * summary go out, and had the chain closed when it did" is checkable here and nowhere else.
     */
    internal var summarised = false
        private set

    /**
     * Bumped every time [marks] changes, so a cached readout can tell it is stale.
     *
     * A counter and not a timestamp: [display] holds its answer at the resolution the panel prints,
     * which is a tenth of a second, and a split landing inside one of those tenths has to invalidate the
     * cache *now* rather than up to 100 ms later. Written from the client thread, read from the frame
     * thread, hence volatile — the same shape as [marks] itself.
     */
    @Volatile
    private var generation: Int = 0

    // --- Input ------------------------------------------------------------------------------

    /**
     * One already-stripped chat line, with the two clock readings taken at the call site.
     *
     * Cheap on the overwhelming majority of lines: while nothing is armed this is one string comparison,
     * and while a run is live it is one full-string match per remaining split.
     */
    fun onChat(stripped: String, nowMs: Long, serverTicks: Long) {
        if (stripped == START_LINE) {
            arm()
            return
        }
        val current = marks
        if (current.isEmpty()) return

        // First match in chain order wins, and a mark that already has a time is skipped. Both are
        // Odin's, and the second is the one that matters: the blood door line and the Watcher's greeting
        // are two sightings of one moment, so taking the later of them would shorten every blood clear
        // by the gap between them. BloodClear.onOpen states the same rule for the same pair.
        val index = current.indexOfFirst { !it.reached && it.split.matches(stripped) }
        if (index < 0) return

        marks = current.toMutableList().also { it[index] = current[index].copy(atMs = nowMs, atTicks = serverTicks) }
        generation++
        DebugLog.event("split", "name" to current[index].split.name, "floor" to floorTag, "index" to index)

        if (index == 0) return // The start marker closes no span and beats no record.
        file(index)
    }

    /**
     * Builds a fresh chain for the floor the sidebar is naming.
     *
     * Off means off, including the records: Odin keeps filing personal bests while its module is
     * switched off, so a player who turned the feature away still accumulates times they never asked for
     * and cannot see. Refusing to arm is the one gate that covers the HUD, the chat and the store at
     * once, and `CLAUDE.md`'s rule is to gate *whether* something is written.
     */
    private fun arm() {
        if (!Config.splits) return
        val tag = SoloClear.floorTag(DungeonSession.floor)
        val chain = DungeonSplits.chainFor(tag)
        if (chain == null) {
            // The sidebar had not named a floor yet, or named one this does not know. Nothing to time,
            // and the previous chain is dropped rather than left running under the wrong floor's records
            // — which is what Odin does here, because its own guards `return` before reassigning.
            reset()
            DebugLog.event("splits_unarmed", "floor" to DungeonSession.floor)
            return
        }
        marks = chain.map { Mark(it) }
        generation++
        floorTag = tag
        results.clear()
        repeat(chain.size) { results.add(null) }
        closed = false
        DebugLog.event("splits_armed", "floor" to tag, "splits" to chain.size)
    }

    /**
     * Files the span that just closed, and on the last mark the run's total as well.
     *
     * Two records on the final line is Odin's shape exactly: the interval named by the second-to-last
     * split, then the total named `total`. One save covers both — see [SplitPbs.record].
     */
    private fun file(index: Int) {
        val current = marks
        val previous = current[index - 1]
        if (!previous.reached) return // The span's own start never arrived; there is no duration here.

        var wrote = false
        val spanMs = current[index].atMs - previous.atMs
        SplitPbs.record(floorTag, previous.split.name, spanMs / 1000f)?.let {
            results[index - 1] = it
            wrote = wrote || it !is SplitPbs.Result.Missed
        }

        if (index == current.lastIndex && !closed) {
            closed = true
            val first = current.first()
            if (first.reached) {
                val totalMs = current[index].atMs - first.atMs
                SplitPbs.record(floorTag, DungeonSplits.TOTAL, totalMs / 1000f)?.let {
                    results[index] = it
                    wrote = wrote || it !is SplitPbs.Result.Missed
                }
                // The same span, handed to the other record store rather than measured again there.
                // It keys on the party as well and prefers Hypixel's own clock, so it is a different
                // record from the `total` above and not a copy of it — [RunPbs] argues that in full.
                RunPbs.onTotal(floorTag, totalMs, current[index].atTicks - first.atTicks)
            }
            // The chain is complete. If the headline has already been seen, this is the moment the
            // summary is both due and correct — the total exists now and did not a tick ago.
            flush(current[index].atMs, current[index].atTicks)
        }

        // Once per landing rather than once per record, which is at most two rewrites saved but is also
        // the reason SplitPbs.record does no I/O of its own.
        if (wrote) Config.save()
    }

    /** Called from [DungeonSession.reset], so no floor inherits the previous one's chain. */
    fun reset() {
        // A run that was timed and never summarised is a run whose last line never arrived — the player
        // left, or one of the strings in DungeonSplits is wrong. It is the same fault `split_missing`
        // reports at the end of a complete run, and reporting it here as well is what stops an
        // abandoned run from looking like a chain that held.
        if (marks.isNotEmpty() && !summarised) logMissing(readout(System.currentTimeMillis(), ServerTicks.count))
        marks = emptyList()
        generation++
        floorTag = "?"
        results.clear()
        closed = false
        pendingSummary = false
        summarised = false
    }

    // --- Output -----------------------------------------------------------------------------

    /**
     * One drawn line: a span, its name, and whether it is the one still running.
     *
     * [label] rides along rather than being derived at the draw call, for
     * [DungeonSplits.Split.label]'s reason — the panel draws every row of this every frame.
     */
    data class Row(
        val name: String,
        val label: String,
        val ms: Long,
        val ticks: Long,
        val running: Boolean,
        val timeText: String,
        val tickText: String,
    ) {
        /** Absent, in the spelling the rest of this mod uses for it. */
        val known get() = ms >= 0
    }

    /**
     * One row, with its two cells already written.
     *
     * **The formatting belongs to the snapshot and not to the draw call.** A panel of ten rows formats
     * twenty-two strings, and a frame does that up to twelve times per game tick for numbers that cannot
     * change more than once — the argument `Format.Cached` and `Labels.ASCII` both make, and the reason
     * `HudRoot` allocates nothing per frame. Twenty-two cache slots in the renderer would say the same
     * thing far less directly, so the strings are built where the numbers are, once per [display].
     */
    private fun row(name: String, label: String, ms: Long, ticks: Long, running: Boolean) = Row(
        name = name,
        label = label,
        ms = ms,
        ticks = ticks,
        running = running,
        timeText = Format.millis(ms),
        tickText = if (ticks < 0) Format.MISSING else Format.ticks(ticks.toInt()),
    )

    /**
     * The run as it should be read out, or null when there is nothing to read.
     *
     * Null covers two cases that look different and are not: nothing armed, and armed but not started.
     * Odin renders the second as a full card of `0s` rows for the few seconds between the countdown and
     * Mort's line; this shows nothing at all, because a column of zeroes is a reading and there is no
     * reading yet. `-:--.-` is this mod's word for that, and it is used below for the rows a live run has
     * not reached — the card is full height from the first mark, so it does not grow under the player's
     * eye as splits land.
     */
    fun readout(nowMs: Long, serverTicks: Long): Readout? {
        val current = marks
        if (current.isEmpty()) return null
        val first = current.first()
        if (!first.reached) return null

        // The run's far end: the total's own mark once it exists, otherwise now. Odin's `latestTime`.
        val last = current.last()
        val latestMs = if (last.reached) last.atMs else nowMs
        val latestTicks = if (last.reached) last.atTicks else serverTicks

        var running = -1
        val rows = ArrayList<Row>(current.size - 1)
        for (i in 0 until current.size - 1) {
            val start = current[i]
            val end = current[i + 1]
            val name = start.split.name
            val label = start.split.label
            when {
                // Closed: the span between two marks that both arrived.
                end.reached ->
                    rows.add(row(name, label, end.atMs - start.atMs, end.atTicks - start.atTicks, false))
                // Running: this span started and has not ended, so it is measured against now. Exactly
                // one row can be in this state, and everything after it is simply unknown.
                start.reached && running < 0 -> {
                    running = i
                    rows.add(row(name, label, latestMs - start.atMs, latestTicks - start.atTicks, true))
                }
                else -> rows.add(row(name, label, -1L, -1L, false))
            }
        }

        // Mort's line to the boss's first line, which is the three shared splits summed — Odin's
        // `times.take(3).sum()`. An unreached span contributes nothing, so before the portal this reads
        // as "time since Mort" and grows, which is what it is.
        //
        // **The condition is not Odin's literal one.** Odin asks `splits.size > 3`, and the entrance
        // chain is four (three shared splits and a total), so it draws a `Boss Entry` row on the one
        // floor that has no boss — where the row is the whole run under a second name. Four rather than
        // three is the test Odin's own comment describes, and it is the one that excludes the entrance.
        val hasBossEntry = current.size > 4
        val boss = rows.take(3).filter { it.known }
        val bossMs = if (hasBossEntry) boss.sumOf { it.ms } else -1L
        val bossTicks = if (hasBossEntry) boss.sumOf { it.ticks } else -1L
        return Readout(
            rows = rows,
            totalMs = latestMs - first.atMs,
            totalTicks = latestTicks - first.atTicks,
            bossEntryMs = bossMs,
            bossEntryTicks = bossTicks,
            bossEntryText = Format.millis(bossMs),
            bossEntryTickText = if (bossTicks < 0) Format.MISSING else Format.ticks(bossTicks.toInt()),
            hasBossEntry = hasBossEntry,
            runningRow = running,
            floorTag = floorTag,
        )
    }

    /**
     * [readout], held for as long as its answer would read the same — the function both HUD elements
     * call, and the reason they cannot disagree about what "now" is.
     *
     * **Keyed at the resolution the panel prints, not at the frame rate.** Every number on screen is
     * `m:ss.t`, so a readout recomputed more often than ten times a second produces a string that is
     * character for character the one already on screen. The tick key is halved for the same reason: two
     * server ticks are one tenth of a second. [generation] is in the key so a split landing inside a
     * tenth still lands on the next frame rather than up to 100 ms later.
     *
     * Read from the frame thread only, which is why the key fields are plain — [generation] and [marks]
     * are the two things written elsewhere and both are volatile.
     */
    fun display(nowMs: Long, serverTicks: Long): Readout? {
        val tenth = nowMs / 100L
        val tickTenth = serverTicks / 2L
        val gen = generation
        if (tenth != keyTenth || tickTenth != keyTickTenth || gen != keyGeneration) {
            keyTenth = tenth
            keyTickTenth = tickTenth
            keyGeneration = gen
            cached = readout(nowMs, serverTicks)
        }
        return cached
    }

    private var keyTenth = Long.MIN_VALUE
    private var keyTickTenth = Long.MIN_VALUE
    private var keyGeneration = -1
    private var cached: Readout? = null

    /** Everything a frame or a chat line needs, computed once. */
    data class Readout(
        val rows: List<Row>,
        val totalMs: Long,
        val totalTicks: Long,
        val bossEntryMs: Long,
        val bossEntryTicks: Long,
        val bossEntryText: String,
        val bossEntryTickText: String,
        val hasBossEntry: Boolean,
        val runningRow: Int,
        val floorTag: String,
        /**
         * Seconds of this run that were the server being behind — [lostToLag] over the two totals.
         *
         * Defaulted from the two fields above rather than passed in, which is the property that matters:
         * there is no way to build a readout whose lag disagrees with its own columns, and the two
         * places that construct one — [readout] and [sample] — get it without having to remember it.
         * Formatted here for [row]'s reason: the panel would otherwise build this string every frame for
         * a number that changes ten times a second at most.
         */
        val lagMs: Long = lostToLag(totalMs, totalTicks),
        val lagText: String = Format.millis(lagMs),
    ) {
        /** Whether the last mark has landed. A finished run has no running row. */
        val finished get() = runningRow < 0

        /**
         * Whether there is a lag figure to show at all.
         *
         * A run with no tick reading has a wall clock and nothing to subtract from it, and printing the
         * whole run length as "lost to lag" would be the most wrong number on the card. That happens
         * when no keep-alive ping was seen for the span — [ServerTicks] counts Hypixel's, so a
         * disconnect is the case — and it is the one state where this row has to be absent rather than
         * zero.
         */
        val hasLag get() = totalTicks > 0L && totalMs > 0L
    }

    /**
     * How much of a run was the server running behind, in milliseconds, and never below zero.
     *
     * **The whole of the feature is this subtraction.** The panel's left column is wall-clock time and
     * its right one is the same span in Hypixel's own ticks ([ServerTicks] argues why that is the
     * comparable number), so what separates them is exactly the time the run took and the server did
     * not account for. Summing it per split would give the same answer — the spans telescope, and the
     * two ends of the run are the only readings needed — so it is taken from the totals rather than
     * accumulated, which is also what makes it right for a run whose middle rows are missing.
     *
     * **Clamped at zero, and that is not defensiveness.** Ticks arrive as keep-alive packets and a
     * server that has been behind can send a burst of them, so a span can briefly show more ticks than
     * wall-clock time allows. A negative reading would print as time the player *gained* to lag, which
     * is not a thing; zero is the honest floor.
     *
     * What it measures is "time that was not the run being played", which includes this machine's own
     * connection stalling — those delay the pings identically. From the seat of somebody who watched a
     * run take longer than it should have, that is the same fact, and pretending to separate them would
     * need a clock nobody here has.
     */
    internal fun lostToLag(totalMs: Long, totalTicks: Long): Long =
        (totalMs - totalTicks * Format.MS_PER_TICK).coerceAtLeast(0L)

    /**
     * A scripted mid-run F7, for the placement editor to drag.
     *
     * Odin's own preview mode renders the F7 group, and for the same two reasons: it is the longest
     * chain there is, so the rectangle the editor hands the player is the tallest the panel will ever
     * be, and it is the floor anybody positioning this is thinking about.
     *
     * Mid-run rather than finished, because the panel has three tones and a finished run shows one of
     * them. Six spans closed, one running, two not started yet is every state the real thing can be in,
     * on one screen, with no session — the same argument `HudPreview` makes for scripting a run.
     *
     * Built once: the editor asks for this three times a frame (preview, width, height) and none of it
     * ever changes.
     */
    internal fun sample(): Readout = SAMPLE

    private val SAMPLE: Readout by lazy {
        val chain = DungeonSplits.chainFor("F7")
            ?: error("F7 is one of the fifteen floors DungeonSplits.chainFor knows")
        // Wall-clock milliseconds and the tick span beside it, a little short of it as a real one is.
        val spans = listOf(
            19_000L to 18_900L,
            51_800L to 51_600L,
            4_000L to 4_000L,
            26_300L to 26_100L,
            48_600L to 48_400L,
            80_300L to 80_000L,
            7_800L to 7_700L,
        )
        val rows = chain.dropLast(1).mapIndexed { i, split ->
            val span = spans.getOrNull(i)
            row(split.name, split.label, span?.first ?: -1L, span?.second ?: -1L, i == spans.lastIndex)
        }
        val closed = rows.take(3)
        Readout(
            rows = rows,
            totalMs = rows.filter { it.known }.sumOf { it.ms },
            totalTicks = rows.filter { it.known }.sumOf { it.ticks },
            bossEntryMs = closed.sumOf { it.ms },
            bossEntryTicks = closed.sumOf { it.ticks },
            bossEntryText = Format.millis(closed.sumOf { it.ms }),
            bossEntryTickText = Format.ticks(closed.sumOf { it.ticks }.toInt()),
            hasBossEntry = true,
            runningRow = spans.lastIndex,
            floorTag = "F7",
        )
    }

    // --- Chat -------------------------------------------------------------------------------

    /**
     * Hypixel printed the run-end headline: the summary is now due, but not necessarily ready.
     *
     * **The headline arms, the chain releases** — `SoloClear`'s pattern, adopted here for the same
     * reason it was adopted there. Odin defers its dump ten client ticks so it lands after Hypixel's
     * end-of-run block, and this first tried to get the same ordering for free by printing at the
     * headline. A real M7 said otherwise: the headline and the `☠ Defeated` line arrive on the same
     * tick, headline first, so at that moment `cleared` is still a running span and there is no total to
     * print at all. A countdown of this mod's own is not the fix either — it would have to be ticked
     * from `onTick`, which returns early on several paths ([StormTimer] is where that costs).
     *
     * So the release is the event that actually makes the summary correct: the last mark landing.
     */
    fun onRunEnd(nowMs: Long, serverTicks: Long) {
        pendingSummary = true
        flush(nowMs, serverTicks)
    }

    /** Prints the summary if it is due and the chain is complete. Both call sites go through here. */
    private fun flush(nowMs: Long, serverTicks: Long) {
        val readout = takeSummary(nowMs, serverTicks) ?: return
        if (!Config.splitsSendToChat) return

        readout.rows.forEachIndexed { index, row ->
            if (!row.known) return@forEachIndexed
            Chat.say(line(row.name, row.timeText, row.ms, results.getOrNull(index)))
        }
        if (readout.hasBossEntry && readout.bossEntryMs >= 0) {
            // No record of its own: it is an aggregate of three spans that each have one, so a fourth
            // number claiming to be a best would be beatable by a run that was slower at every split.
            Chat.say(line(DungeonSplits.BOSS_ENTRY, readout.bossEntryText, readout.bossEntryMs, null))
        }
        Chat.say(line(DungeonSplits.TOTAL, Format.millis(readout.totalMs), readout.totalMs, results.lastOrNull()))
        // Last, under the total, because it is a statement *about* the total and not another split. No
        // record clause: a personal best for having been lagged is not an achievement, and the number
        // is not the player's to beat.
        if (readout.hasLag) Chat.say(line(DungeonSplits.LAG, readout.lagText, readout.lagMs, null))
    }

    /**
     * The readout to summarise, once, or null — the whole of the arm-and-release decision.
     *
     * Separated from the printing because [Chat.say] needs a `Minecraft` and this does not: the decision
     * is what `SplitsTest` can drive, and the decision is the part that was wrong. Null covers a run
     * that has not been armed, one whose chain is still open, one already summarised, and one that never
     * started.
     *
     * The missing-split log fires here rather than in [flush], so it is written whether or not the chat
     * lines are switched on. It is the feature's own instrumentation and the only thing that will ever
     * say which of [DungeonSplits]' twenty-odd Hypixel strings is wrong.
     */
    internal fun takeSummary(nowMs: Long, serverTicks: Long): Readout? {
        if (!pendingSummary || summarised) return null
        val readout = readout(nowMs, serverTicks)
        if (readout == null) {
            if (marks.isNotEmpty()) DebugLog.event("splits_never_started", "floor" to floorTag)
            return null
        }
        // Armed but the last mark has not landed. Held rather than printed — `reset` is what reports it
        // if it never does.
        if (!readout.finished) return null

        summarised = true
        pendingSummary = false
        logMissing(readout)
        return readout
    }

    /**
     * Two different faults, kept apart.
     *
     * `unclosed` is the span that was still running when the run ended: it started, so its own line is
     * right and the *next* split's line never arrived. `unstarted` are the spans behind it, which never
     * began at all. One name in either field, on a floor that certainly reached that boss, is one
     * pattern in [DungeonSplits] to correct; both empty is a chain that held.
     */
    private fun logMissing(readout: Readout?) {
        if (readout == null) return
        DebugLog.event(
            "split_missing",
            "floor" to floorTag,
            "unclosed" to if (readout.finished) null else readout.rows.getOrNull(readout.runningRow)?.name,
            "unstarted" to readout.rows.filter { !it.known }.joinToString(",") { it.name },
            // Beside the two faults because it explains a third symptom: a run whose wall-clock total
            // looks wrong against the records was often not a slow run but a lagged one.
            "lagMs" to readout.lagMs,
        )
    }

    /**
     * `blood clear · 0:51.8 · PB −0:02.1`, or with the standing best where none fell.
     *
     * The record clause is [RoomHistory.pbSuffix]'s wording and shape, deliberately: a player reads one
     * of these lines a few dozen times a run and two spellings of "you beat it" would be two things to
     * learn. It is not reused as a function because that one is in run ticks and this is in
     * milliseconds — see [SplitPbs].
     */
    private fun line(name: String, text: String, ms: Long, result: SplitPbs.Result?) = Chat.label(name)
        .append(Chat.meta(Chat.FIELD))
        .append(Chat.value(text))
        .apply {
            when (result) {
                null -> Unit
                is SplitPbs.Result.First -> append(Chat.meta(Chat.FIELD))
                    .append(Chat.emphasis("PB"))
                    .append(Chat.meta(" first"))

                is SplitPbs.Result.Beat -> append(Chat.meta(Chat.FIELD))
                    .append(Chat.emphasis("PB"))
                    .append(Chat.meta(" " + Format.deltaMillis(ms - (result.previous * 1000f).toLong())))

                is SplitPbs.Result.Missed ->
                    append(Chat.meta(Chat.FIELD + Format.millis((result.best * 1000f).toLong())))
            }
        }
}
