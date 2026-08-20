package sighteaddons

/**
 * The run's score while it is still running — as **two different numbers**, and the difference between
 * them is the whole reason the gate in [SoloClear] has the form it does.
 *
 *  - **[score] is what Hypixel is showing right now**, read off the sidebar's `Cleared: 68% (152)` (or
 *    the tab footer's `Score: 287`). Exact by construction, and the calibration this file is judged by.
 *  - **[computedScore] is what the run is on track to score**, [DungeonScore] over the current rows. An
 *    estimate, with the error bar spelled out below.
 *
 * **Both questions were open until 2026-08-20; the session logs of that day closed both.**
 *
 * **The read number is identified.** On six party runs Hypixel's `Team Score:` and the last sidebar
 * reading agree *exactly* — 308/308, 303/303, 302/302, 302/302, 300/300, 292/292 (`run_score`). The
 * bracket on the `Cleared:` line is Hypixel's score, and nothing else. The upstream mod has that regex
 * (`SCOREBOARD_CLEARED_PATTERN`) and never uses it; preferring it is not the mistake, and [score] still
 * does.
 *
 * **It is the wrong number for a clear-phase gate, because of what it measures.** It is the score earned
 * *so far*, and the boss room is part of a floor's score, so it cannot read 300 before the boss is dead.
 * Measured the same day: on the party runs it climbed 92 → 303 **during** the fight; on two solo F7s it
 * stopped at 265 and 242 at the blood door. Five solo runs are on record and not one of them ever read
 * 270. A 270 gate on this number is not a strict gate, it is an unreachable one — which is exactly what
 * it was, with `soloClears` on and nothing ever announced.
 *
 * So the gate reads [computedScore], as every mod carrying this feature does and as upstream does
 * exclusively (`SoloClearsTracker`: `finalScore = DungeonScore.getScore()`, with its `chatScore` allowed
 * only to raise it). The error bar, measured against Hypixel's own final number on those same six runs,
 * is **4 to 8 points low** — and every term of it leans that way on purpose:
 *
 *  - **Mayor Paul is +10 to the real score** and cannot be seen from inside a dungeon.
 *  - `totalRooms` is derived from a percentage Hypixel rounds to whole numbers, so it can be off by one
 *    room, which moves both the explore and the skill halves.
 *  - The upstream `isQuizCompleted()` awards +5 for the word "Quiz" appearing in the tab list at all.
 *    [DungeonStats.PUZZLE_ROW] fixes that here by requiring the solved mark, which trades a five-point
 *    overstatement for a five-point understatement on an unsolved Oruo.
 *
 * Understating is the only tolerable direction: it fires late, never on a run that did not qualify. And
 * [source] travels with every score into the debug log, so the calibration above is a measurement every
 * new run repeats rather than a claim made once.
 */
object LiveScore {
    /** Where the current [score] came from. `none` means no source could answer at all. */
    enum class Source { SIDEBAR, FOOTER, COMPUTED, NONE }

    var score: Int? = null
        private set

    var source = Source.NONE
        private set

    /**
     * When the run's clock started, for the computed time score only.
     *
     * `System.currentTimeMillis()` at the first reading inside a dungeon, the way the upstream mod does
     * it — not [DungeonSession.runTicks], because the formula takes wall-clock milliseconds and turning
     * ticks into them would add a second conversion to a number that is already the weakest input.
     */
    private var startedAtMs = 0L
    private var bloodDone = false

    /**
     * The highest score this run reached, and the reason it exists.
     *
     * A gate that does not fire leaves no trace at all: the score is read every tick, [set] only logs a
     * change of *source*, and a run that got to 268 with the gate at 300 looks exactly like a run whose
     * score could never be read. One number turns "nothing happened" into "you were 32 short", and it is
     * the difference between a feature that can be tuned and one that can only be guessed at.
     */
    var high = 0
        private set

    /**
     * The score this run is on track for, [DungeonScore] over the current rows — **and the number the
     * gate is judged on.** The header says why it beats the read one there and nowhere else.
     *
     * The two disagreeing is not a fault to be fixed: on a solo run this sits 30-odd points *above*
     * [score] for the whole clear phase, because it counts the run the player is completing while the
     * sidebar counts the rooms already done. On a finished run they converge, this side low — 296
     * against 303 is the measured shape.
     *
     * **Sampled every ten ticks**, as upstream samples it, which is below the resolution of the thing it
     * dates: the announced time comes off Hypixel's own clock, and that clock counts whole seconds.
     */
    var computedScore: Int? = null
        private set

    /**
     * The highest [computedScore] this run reached — [high]'s twin, for the number that actually decides.
     *
     * Kept separately rather than folded into [high] because the two are different quantities, and the
     * one thing the diagnosis must not do is merge them: `solo_clear_missed` carries both, and a run
     * where they read 275 and 242 is the run that moved the gate onto this one.
     */
    var projectedHigh = 0
        private set

    /** The step [high] is logged at. Twelve lines over a full run, which is the price of seeing the curve. */
    private const val STEP = 25

    private var loggedStep = 0

    /** Per run, from [DungeonSession.reset]. */
    fun reset() {
        // Logged here rather than by a reader, so no call site has to run before [DungeonSession.reset]
        // clears this. A run that never had a score says so with a zero.
        if (startedAtMs != 0L) {
            DebugLog.event(
                "score_high", "high" to high, "source" to source.name.lowercase(),
                // The gate's own high water mark, next to Hypixel's. The gap between them on a solo run
                // is the measurement this file's header rests on, so every run re-measures it.
                "projectedHigh" to projectedHigh, "computed" to (computedScore ?: -1),
            )
        }
        score = null
        source = Source.NONE
        startedAtMs = 0L
        bloodDone = false
        high = 0
        computedScore = null
        projectedHigh = 0
        loggedStep = 0
    }

    /** The Watcher's `You may pass.`, routed from [SighteAddons]. Feeds [extraRooms] and nothing else. */
    fun onBloodDone() {
        bloodDone = true
    }

    /**
     * Rooms the tab list has not counted yet, verbatim from the upstream mod.
     *
     * An approximation and openly one: before the blood room there are two such rooms (one on the
     * Entrance), after it one, and none once the boss has started. It exists because `Completed Rooms`
     * lags the run, and it is why the computed score is a fallback rather than a source.
     */
    internal fun extraRooms(bloodDone: Boolean, inBoss: Boolean, isEntrance: Boolean): Int = when {
        !bloodDone -> if (isEntrance) 1 else 2
        !inBoss && !isEntrance -> 1
        else -> 0
    }

    /**
     * The floor's room count, derived rather than read: `completed / cleared%`.
     *
     * Null when either input is missing, and never zero — the formula divides by this, and a zero would
     * turn a missing reading into a score of 20 rather than into no score at all.
     */
    internal fun totalRooms(completedRooms: Int?, clearedFraction: Double?): Int? {
        if (completedRooms == null || clearedFraction == null || clearedFraction <= 0.0) return null
        return Math.round(completedRooms / clearedFraction).toInt().coerceAtLeast(1)
    }

    /**
     * [DungeonScore] over one reading, or null when an input the formula cannot do without is missing.
     *
     * Refusing beats guessing here: every missing input makes the score *lower*, and a gate that fires
     * on a score built out of absences is a gate that fires on nothing.
     */
    internal fun computed(
        floor: String?,
        stats: DungeonStats.Stats,
        clearedFraction: Double?,
        secretsPercent: Double?,
        inBoss: Boolean,
        bloodDone: Boolean,
        startedAtMs: Long,
        nowMs: Long,
    ): Int? {
        val requirement = DungeonScore.requirementFor(floor)
        if (requirement == DungeonScore.FloorRequirement.NONE) return null
        val total = totalRooms(stats.completedRooms, clearedFraction) ?: return null
        val completed = stats.completedRooms ?: return null
        if (startedAtMs == 0L) return null
        val isEntrance = floor == "Entrance"
        val extra = extraRooms(bloodDone, inBoss, isEntrance)

        val time = DungeonScore.calculateTimeScore(requirement, startedAtMs, nowMs)
        val explore = DungeonScore.calculateExploreScore(
            total, completed, extra, secretsPercent ?: 0.0, requirement.percentage,
        )
        val skill = DungeonScore.calculateSkillScore(
            total, completed, extra, stats.puzzleCount ?: 0, stats.puzzlesSolved,
        )
        // The mimic is inferred as well as read: the mimic chest is a secret, so a full secret count on a
        // floor that has one means it died. Upstream does the same, and it is the one inference here that
        // cannot be wrong in the direction that matters — 100 % secrets is 100 % secrets.
        val mimic = stats.mimic || ((secretsPercent ?: 0.0) >= 100.0 && hasMimics(floor))
        val bonus = DungeonScore.calculateBonusScore(
            stats.crypts ?: 0, mimic, stats.prince, quizCompleted = false,
        )
        return DungeonScore.calculateTotal(time, explore, skill, bonus, isEntrance)
    }

    /** Floors that spawn a mimic, as upstream hardcodes them. Hoisted: [computed] runs every tick. */
    private val MIMIC_FLOORS = Regex("""[FM][67]""")

    internal fun hasMimics(floor: String?): Boolean = floor != null && MIMIC_FLOORS.matches(floor)

    /**
     * One reading, from the client tick. Sets [score] and [source] and nothing else — the decision about
     * what a score means belongs to [SoloClear].
     *
     * Called every tick rather than every ten like upstream, because both read sources come off screens
     * this mod already parses on that cadence, and the announced time is only as precise as the reading
     * that triggered it.
     */
    fun observe(
        floor: String?,
        sidebarScore: Int?,
        footer: () -> String?,
        rows: List<String?>,
        clearedFraction: Double?,
        secretsPercent: Double?,
        inBoss: Boolean,
        nowMs: Long,
        runTicks: Int,
    ) {
        if (startedAtMs == 0L) startedAtMs = nowMs

        // Computed every tick would mean matching eighty tab rows against seven patterns twenty times a
        // second for a number nothing reads yet. Upstream samples at ten ticks; so does this.
        if (runTicks % 10 == 0) {
            computedScore = computed(
                floor, DungeonStats.read(rows), clearedFraction, secretsPercent,
                inBoss, bloodDone, startedAtMs, nowMs,
            )
            // Here and not in [set], which only ever sees the read score. The projection needs its own
            // maximum because it is the number a refusal is measured against, and a refusal has to be
            // able to say how close *that* one came.
            computedScore?.let { if (it > projectedHigh) projectedHigh = it }
        }

        sidebarScore?.let {
            set(it, Source.SIDEBAR)
            return
        }
        // A lambda, and only called on the path that needs it: reading the footer means turning a
        // Component into a String, and the sidebar has usually already answered. Same reason
        // [CritMeter.onChat] takes it as one.
        DungeonStats.footerScore(footer())?.let {
            set(it, Source.FOOTER)
            return
        }
        val computed = computedScore
        if (computed == null) {
            score = null
            source = Source.NONE
            return
        }
        set(computed, Source.COMPUTED)
    }

    /**
     * Keeps the score and logs the first reading of each source.
     *
     * **The source is logged once per run per source**, because which one answered is the whole question
     * a played floor settles: a `sidebar` line means the calculator can be deleted, a `computed` line
     * means it is load-bearing and its offsets matter.
     */
    private fun set(value: Int, from: Source) {
        if (source != from) DebugLog.event("score_source", "source" to from.name.lowercase(), "score" to value)
        score = value
        source = from
        if (value <= high) return
        high = value
        // Every [STEP] points of new maximum, not every tick: the curve is what is worth seeing, and the
        // score moves in ones for the whole clear phase.
        val step = value / STEP
        if (step > loggedStep) {
            loggedStep = step
            DebugLog.event(
                "score_step", "score" to value, "source" to from.name.lowercase(),
                "computed" to (computedScore ?: -1),
            )
        }
    }
}
