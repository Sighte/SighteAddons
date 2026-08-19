package sighteaddons

/**
 * A measurement, not a feature: does Hypixel publish the dungeon score, and do the rows a live scorer
 * would need actually look the way the ported calculator assumes?
 *
 * **Why this exists instead of the calculator.** `SoloClear`'s gate wants the score *during the clear
 * phase* — the end-of-run `Team Score:` line arrives after the boss and is useless for that. Two ways
 * to get a live one:
 *
 *  1. **Read it.** The upstream mod checks the tab list footer for `Score: 287` first and only computes
 *     when that is absent. If this floor's footer carries it, there is nothing to compute and the gate
 *     is exactly right by construction.
 *  2. **Compute it** from [DungeonScore] — which needs `Completed Rooms`, the sidebar's `Cleared: X%`,
 *     `Crypts`, the puzzle rows, `Mimic`, `Prince` and the secrets percentage, and which carries two
 *     known offsets: the upstream `isQuizCompleted()` awards its +5 for the word "Quiz" merely
 *     *appearing* in the tab list, and Mayor Paul's +10 is not in the formula at all. Both shift the
 *     moment a 300 gate fires, in opposite directions.
 *
 * Building (2) before knowing whether (1) is available would be a confidently wrong number in a Discord
 * channel — and this repository's own rule is that a played floor answers more than any work here. So
 * this logs what a floor actually shows, three times per run, and nothing else.
 *
 * **Names never reach the log.** Every row is matched on a literal prefix that cannot be a player name,
 * with one exception: a puzzle row is `Teleport Maze: [✔] Somebody`, so it is cut after the bracket.
 * That keeps the only thing worth reading — which mark the puzzle carries — and drops the solver.
 *
 * Delete this file once the answer is in `TODO.md`.
 */
object ScoreProbe {
    /**
     * The run ticks a probe is taken at: one minute in, three, and six.
     *
     * Fixed points rather than an interval, because the rows fill in over the run — at ten seconds the
     * tab list has no crypts and no puzzles, and a probe of an empty list would answer "the format is
     * wrong" when it only means "nothing has happened yet".
     */
    internal val AT = setOf(1200, 3600, 7200)

    private var taken = 0

    /** Per run, like everything [DungeonSession.reset] clears. */
    fun reset() {
        taken = 0
    }

    /** `Score: 287` in the tab footer — the whole question this probe exists to answer. */
    internal val FOOTER_SCORE = Regex("""(?i).*\bScore:\s*(\d+).*""")

    /**
     * The rows a live scorer would read, matched on their literal prefixes. `Cleared` is on the sidebar
     * rather than the tab list and is passed in from there; the rest are tab rows.
     */
    internal val CANDIDATE = Regex(
        """(?i)^(?:Score|Completed Rooms|Crypts|Puzzles|Mimic|Prince|Deaths|Time|Time Elapsed|Secrets Found|Cleared)\b.*""",
    )

    /** A puzzle row's mark. Hypixel uses three: solved, failed, and Oruo's own. */
    private val MARKED = Regex("""^[^:]{1,32}: .*[\[(][✔✖✦][\])].*""")

    /**
     * The lines worth logging out of one screen's worth of rows.
     *
     * A puzzle row is truncated after its mark. Everything else matched here begins with a word Hypixel
     * chose, so it carries no name to begin with — the same argument [DungeonTab.unparsed] makes.
     */
    internal fun readable(lines: List<String?>): List<String> = lines.filterNotNull()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { row ->
            when {
                CANDIDATE.matches(row) -> row
                // Cut after the bracket: what is behind it is a party member's name.
                MARKED.matches(row) -> row.substringBefore(']').plus(']')
                else -> null
            }
        }

    /**
     * Takes one probe if this run tick is one of [AT] and the cap is not spent.
     *
     * Reads nothing itself — the caller already holds the tab rows, the footer and the sidebar, and
     * fetching them a second time here would be a second chance to disagree about what they said.
     */
    fun observe(runTicks: Int, rows: List<String?>, footer: String?, sidebar: List<String>) {
        if (taken >= AT.size || runTicks !in AT) return
        taken++
        val published = footer?.lines()?.firstNotNullOfOrNull { line ->
            FOOTER_SCORE.matchEntire(line.trim())?.groupValues?.get(1)?.toIntOrNull()
        }
        DebugLog.event(
            "score_probe",
            "at" to runTicks,
            // The answer. -1 means the footer exists and does not carry a score; a probe with no
            // `score_probe` line at all means there was no footer to read.
            "published" to (published ?: -1),
            "footerLines" to (footer?.lines()?.size ?: -1),
            "tab" to readable(rows).toString(),
            "sidebar" to readable(sidebar).toString(),
        )
    }
}
