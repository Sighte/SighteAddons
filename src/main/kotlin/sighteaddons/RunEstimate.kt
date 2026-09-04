package sighteaddons

/**
 * What the run's final time is on track to be: every closed split as measured, plus the player's own
 * expected time ([SplitExpected]) for everything still to come.
 *
 * ### The projection, and which way it leans
 *
 * [LiveScore]'s rule applies — an estimate owes the reader its error bar, and this one's is easy to
 * state: **it assumes every remaining split lands exactly on plan.** A run going badly reads too low
 * for as long as the trouble is still ahead, and the number is monotone non-decreasing over a run —
 * closed spans are facts, and the running span contributes `max(elapsed, expected)`, so an overrun
 * already on the clock is never paid back. The moment the estimate can move is when a split *closes*
 * (its expectation is replaced by its measurement) or while the running split is past its plan, in
 * which case it grows a tenth at a time with the clock. That is the honest shape: the panel's other
 * numbers only grow too.
 *
 * ### All or nothing
 *
 * One remaining split without an expected time and there is no estimate at all ([NONE]), rather than a
 * partial sum with a footnote. A figure labelled EST. RUN that silently omitted a boss would be
 * [Splits.Readout.hasLag]'s "most wrong number on the card" — and completeness is one click away, via
 * the prefill action on the `/sa` HUD tab. A finished run is [NONE] too: the panel already shows the
 * measured total, and an estimate row echoing it would be a second total under a different name.
 *
 * Delta against the plan (`estimate − Σ expected`) is deliberately not computed yet: the panel is two
 * fixed columns with fixed meanings (wall clock, server ticks) and a signed third figure has no honest
 * cell on it. If it is ever wanted it is one subtraction on top of this.
 *
 * Pure — no Minecraft, no clock, no store: the rows carry the measurements and [expected] is handed in
 * as a lookup, which is what lets `RunEstimateTest` drive every case with a map.
 */
internal object RunEstimate {

    /** No estimate. [Format.millis] prints it as [Format.MISSING]; [SplitsHud] hides the row instead. */
    const val NONE = -1L

    /**
     * The projected total in milliseconds, or [NONE].
     *
     * Row by row rather than off [Splits.Readout.runningRow], so a run with a hole in it — a missed
     * chat line leaves a mark unreached between two that landed — falls back to that span's expectation
     * instead of mis-indexing everything behind it.
     *
     * [expected] answers in [SplitExpected]'s unit, wall-clock seconds; the rounding to milliseconds is
     * [Format.seconds]' (a `Float` holding 19.6 is 19.600000381469727, and truncation would bleed a
     * millisecond per split).
     */
    fun projectMs(rows: List<Splits.Row>, finished: Boolean, expected: (String) -> Float?): Long {
        if (finished || rows.isEmpty()) return NONE
        var total = 0L
        for (row in rows) {
            if (row.known && !row.running) {
                total += row.ms
                continue
            }
            val plan = expected(row.name) ?: return NONE
            val planMs = Math.round(plan * 1000.0)
            total += if (row.running && row.ms > planMs) row.ms else planMs
        }
        return total
    }
}
