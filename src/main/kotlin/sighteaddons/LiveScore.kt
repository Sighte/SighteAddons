package sighteaddons

/**
 * The run's score while it is still running, which is what a gate in the clear phase needs.
 *
 * Hypixel's `Team Score:` chat line is no use for that: it arrives after the boss, and by then the
 * question — *when* did this run reach 300 — is already unanswerable.
 *
 * **Three sources, and the two that are read beat the one that is computed.** The upstream mod has the
 * same order of preference (`getExtractedScore()` before `calculateScore()`); it simply never wired the
 * first of them:
 *
 *  1. **The sidebar.** `Cleared: 68% (152)` — the number in brackets is the score, published by Hypixel
 *     every tick. The upstream mod has the regex for it (`SCOREBOARD_CLEARED_PATTERN`) and **never uses
 *     it**, while its own sampler ships that number next to its computed one for comparison. 152 at 68 %
 *     cleared cannot be a room count; floors have twenty to thirty rooms.
 *  2. **The tab footer.** `Score: 287`, which is what the upstream mod does read first.
 *  3. **[DungeonScore].** The ported formula, as the last resort.
 *
 * **Why the order matters more than the formula.** A read score is exact by construction. The computed
 * one carries offsets that no amount of care removes:
 *
 *  - **Mayor Paul is +10 to the real score** and cannot be seen from inside a dungeon. A computed score
 *    is therefore up to ten low in a Paul term, and a 300 gate fires late or never.
 *  - `totalRooms` is derived from a percentage Hypixel rounds to whole numbers, so it can be off by one
 *    room, which moves both the explore and the skill halves.
 *  - The upstream `isQuizCompleted()` awards +5 for the word "Quiz" appearing in the tab list at all.
 *    [DungeonStats.PUZZLE_ROW] fixes that here by requiring the solved mark, which trades a five-point
 *    overstatement for a five-point understatement on an unsolved Oruo — the direction that cannot
 *    announce a run that did not qualify.
 *
 * So [source] travels with every score and reaches the debug log. A `computed` gate firing is a result
 * with an error bar; a `sidebar` one is a measurement.
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

    /** Per run, from [DungeonSession.reset]. */
    fun reset() {
        score = null
        source = Source.NONE
        startedAtMs = 0L
        bloodDone = false
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
    ) {
        if (startedAtMs == 0L) startedAtMs = nowMs

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
        val computed = computed(
            floor, DungeonStats.read(rows), clearedFraction, secretsPercent,
            inBoss, bloodDone, startedAtMs, nowMs,
        )
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
    }
}
