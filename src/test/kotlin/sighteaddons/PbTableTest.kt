package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three orderings the two personal-best tables are, and nothing else.
 *
 * Every one of them is invisible when it is wrong. A floor in the wrong place looks like a floor. A
 * chain sorted by name looks like a chain — `blood clear` really does come before `blood open`
 * alphabetically — and reading a run's phases out of order is reading somebody else's run. An
 * own-clock time promoted into a floor's heading looks like a personal best and is a number
 * [RunPbs] spends four paragraphs refusing to compare with the one beside it.
 *
 * Nothing here checks how a line is drawn: [PbTable] is free of Minecraft types precisely so this file
 * can exist, and where a pixel goes is the screen's business.
 */
class PbTableTest {

    /** Every split's record is `1f` unless the case is about a time. Keeps the assertions about order. */
    private fun floor(vararg splits: String): Map<String, Float> =
        splits.associateWith { 1f }

    /** `m:ss.t` is the screen's spelling; here the seconds go through unchanged so they can be read. */
    private val plain: (Float) -> String = { "$it" }

    /** F7's chain, shortened to the four rows that matter for an ordering assertion. */
    private val chain: (String) -> List<String> = {
        listOf("blood open", "blood clear", "portal entry", "maxor", "cleared", DungeonSplits.TOTAL)
    }

    /**
     * Master first, highest first, the entrance last — and a floor nobody recognises after all of them.
     *
     * The unrecognised case is the one worth pinning: a hand-edited `config.json` or a key from a later
     * version has to land *somewhere*, because a record that is silently not on the screen is worse
     * than one in an odd place. [PbTable.order] gives it the back of the list rather than dropping it.
     */
    @Test
    fun `floors read master first and never lose one`() {
        assertEquals(
            listOf("M7", "M6", "M1", "F7", "F1", "E", "DungeonQ7", "Kuudra"),
            PbTable.floors(listOf("F1", "E", "M1", "Kuudra", "F7", "M7", "DungeonQ7", "M6")),
        )
        // Deduped, because the caller is a map's key set today and a list of records tomorrow.
        assertEquals(listOf("M7"), PbTable.floors(listOf("M7", "M7")))
    }

    /**
     * A floor's splits come out in the run's order, and a name the chain has never heard of comes after.
     *
     * `portal entry` is fed in first and `blood open` last on purpose: insertion order is what
     * [SplitPbs] keeps, and it is the order this must *not* reproduce.
     */
    @Test
    fun `splits follow the chain and strays go last`() {
        val lines = PbTable.splits(
            mapOf("F7" to floor("portal entry", "witherlord", "cleared", "blood open", "aardvark")),
            chain,
            plain,
        )
        assertEquals("F7", lines.first().floor)
        assertTrue(lines.first().heading)
        assertEquals(
            // Chain order for the three the chain knows, then the two it does not, alphabetically.
            listOf("blood open", "portal entry", "cleared", "aardvark", "witherlord"),
            lines.drop(1).map { it.label },
        )
    }

    /**
     * The heading carries the floor's `total` and is blank when there is none — never a dash.
     *
     * [sighteaddons.ui.Format.MISSING] is a clock with its digits knocked out, which is the right shape
     * for a time that should be there. A floor mid-way through its first records has no total *yet*,
     * and an empty heading says that where a dash claims a broken one.
     */
    @Test
    fun `a floor's heading is its total, or nothing`() {
        val withTotal = PbTable.splits(
            mapOf("M7" to mapOf("blood open" to 19.6f, DungeonSplits.TOTAL to 272.0f)),
            chain,
            plain,
        )
        assertEquals("272.0", withTotal.first { it.heading }.time)

        val without = PbTable.splits(mapOf("M7" to floor("blood open")), chain, plain)
        assertEquals("", without.first { it.heading }.time)
        // The floor is still on the table: one record is a record.
        assertEquals(listOf("blood open"), without.drop(1).map { it.label })
    }

    /** A floor with no records at all produces no heading, so the table has no empty groups in it. */
    @Test
    fun `an empty floor is not a group`() {
        assertEquals(emptyList<PbTable.Line>(), PbTable.splits(mapOf("M7" to emptyMap()), chain, plain))
    }

    /**
     * Ranked rows first, party ascending inside each clock, and the own-clock rows saying so.
     *
     * The party sizes are fed in descending and the own-clock record first, because that is the shape a
     * `HashMap` of keys hands over and it is the one this must not pass through.
     */
    @Test
    fun `runs put hypixel's rows first and count the party up`() {
        val lines = PbTable.runs(
            listOf(
                RunPbs.Record("M7", 5, RunPbs.Clock.OWN, 351f),
                RunPbs.Record("M7", 4, RunPbs.Clock.HYPIXEL, 348f),
                RunPbs.Record("M7", 1, RunPbs.Clock.HYPIXEL, 392f),
            ),
            plain,
        )
        assertEquals(
            listOf("solo", "4 players", "5 players · own clock"),
            lines.drop(1).map { it.label },
        )
        // The heading is the best *ranked* time and not the fastest number on the floor — 351 is faster
        // than 348 nowhere, but the point is that the own-clock row is not even a candidate.
        assertEquals("348.0", lines.first().time)
    }

    /**
     * A floor with nothing rankable on it keeps its rows and loses only its heading time.
     *
     * This is the whole of [RunPbs]' separation stated as a table: an own-clock record is a record and
     * belongs on the screen, and it is still not a number that may be presented as the floor's best.
     */
    @Test
    fun `an own-clock-only floor has rows but no headline`() {
        val lines = PbTable.runs(listOf(RunPbs.Record("F7", 1, RunPbs.Clock.OWN, 400f)), plain)
        assertEquals("", lines.first().time)
        assertEquals(listOf("solo · own clock"), lines.drop(1).map { it.label })
        assertEquals(1, PbTable.count(lines))
    }
}
