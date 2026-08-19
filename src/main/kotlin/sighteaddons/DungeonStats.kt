package sighteaddons

/**
 * What the dungeon tab list says about the run, beyond the secrets [DungeonTab] already takes out of it.
 *
 * Split from [DungeonTab] rather than added to it because these rows exist for one consumer — the live
 * score [LiveScore] computes when Hypixel does not publish one — while `DungeonTab`'s two readings feed
 * the summary and the announcement whatever the score does.
 *
 * Every field is nullable or false-by-absence, and that is the contract: a row Hypixel did not send is
 * unknown, never zero. The score built on top of it decides what an unknown costs, and it decides that
 * in one place ([LiveScore.computed]) instead of nine.
 *
 * **No row read here can carry a player's name**, with one exception that is handled: a puzzle row is
 * `Teleport Maze: [✔] Somebody`, and only the mark is read off it — the tail is never touched.
 */
object DungeonStats {
    /** `Completed Rooms: 12` — how many rooms the tab list counts as done. */
    internal val COMPLETED = Regex("""(?i)Completed Rooms: (\d+).*""")

    /** `Crypts: 5` — worth one bonus point each, capped at five. */
    internal val CRYPTS = Regex("""(?i)Crypts: (\d+)""")

    /** `Puzzles: (3)` — how many the floor has, not how many are solved. */
    internal val PUZZLES = Regex("""(?i)Puzzles: \((\d+)\)""")

    /** `Team Deaths: 0`, or `Deaths: 0` — the tab's own count, kept for the announcement. */
    internal val DEATHS = Regex("""(?i)(?:Team )?Deaths: (\d+).*""")

    /** `Mimic: ✔` — Hypixel ticks it once the mimic is dead. */
    internal val MIMIC = Regex("""(?i)Mimic: .*[✔✓].*""")

    /** `Prince: ✔`, the tab's half of the signal whose other half is a chat line. */
    internal val PRINCE = Regex("""(?i)Prince: .*[✔✓].*""")

    /**
     * A puzzle row and its mark: `Teleport Maze: [✔] Somebody`.
     *
     * Solved is `✔`. `✦` is Oruo's own mark and is **not** counted as solved — the upstream mod treats
     * it as one for the Quiz, and that is the same leniency that makes its `isQuizCompleted()` award five
     * points for a puzzle merely existing. A wrong `+5` moves the moment a 300 gate fires.
     */
    internal val PUZZLE_ROW = Regex("""^[^:]{1,32}: [\[(]([✔✖✦])[\])].*""")

    /** `Score: 287` anywhere in the tab list footer — the number the upstream mod tries first. */
    internal val FOOTER_SCORE = Regex("""(?i).*\bScore:\s*(\d+).*""")

    /** One reading. Absent means Hypixel did not say, which is not the same as zero. */
    data class Stats(
        val completedRooms: Int? = null,
        val crypts: Int? = null,
        val puzzleCount: Int? = null,
        val puzzlesSolved: Int = 0,
        val deaths: Int? = null,
        val mimic: Boolean = false,
        val prince: Boolean = false,
    )

    /** Everything above, out of one screen's worth of rows. Pure — the rows come from [PartyTracker]. */
    fun read(rows: List<String?>): Stats {
        var stats = Stats()
        var solved = 0
        for (raw in rows) {
            val row = raw?.trim() ?: continue
            if (row.isEmpty()) continue
            COMPLETED.matchEntire(row)?.let { stats = stats.copy(completedRooms = it.groupValues[1].toIntOrNull()) }
            CRYPTS.matchEntire(row)?.let { stats = stats.copy(crypts = it.groupValues[1].toIntOrNull()) }
            PUZZLES.matchEntire(row)?.let { stats = stats.copy(puzzleCount = it.groupValues[1].toIntOrNull()) }
            DEATHS.matchEntire(row)?.let { stats = stats.copy(deaths = it.groupValues[1].toIntOrNull()) }
            if (MIMIC.matchEntire(row) != null) stats = stats.copy(mimic = true)
            if (PRINCE.matchEntire(row) != null) stats = stats.copy(prince = true)
            PUZZLE_ROW.matchEntire(row)?.let { if (it.groupValues[1] == "✔") solved++ }
        }
        return stats.copy(puzzlesSolved = solved)
    }

    /** The published score out of the footer, or null when the footer does not carry one. */
    fun footerScore(footer: String?): Int? = footer?.lines()?.firstNotNullOfOrNull { line ->
        FOOTER_SCORE.matchEntire(line.trim())?.groupValues?.get(1)?.toIntOrNull()
    }
}
