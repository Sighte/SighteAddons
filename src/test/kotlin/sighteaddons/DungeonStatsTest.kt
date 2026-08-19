package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tab rows the computed score is built from. Anchored rather than reasoned about, because every one of
 * them feeds a threshold: a missed `Crypts` row is five points, a missed puzzle mark is ten, and both move
 * the moment a 300 gate fires.
 *
 * **No row here was seen on a real floor** — the formats are the ones the upstream mod matches, and a
 * `score_probe` in a debug session is what turns that into a measurement. Same standing as
 * [DungeonTabTest]'s fixtures.
 */
class DungeonStatsTest {
    private fun tab(vararg extra: String) = listOf(
        "[102] [MVP+] Notch (Berserk VII)", "Dungeon Info", null, "",
    ) + extra.toList()

    @Test
    fun `the rows the formula needs are read, and absence stays absent`() {
        val stats = DungeonStats.read(
            tab("Completed Rooms: 12", "Crypts: 5", "Puzzles: (3)", "Team Deaths: 2"),
        )
        assertEquals(12, stats.completedRooms)
        assertEquals(5, stats.crypts)
        assertEquals(3, stats.puzzleCount)
        assertEquals(2, stats.deaths)
        // Nothing said about either, so nothing is claimed about either.
        assertFalse(stats.mimic)
        assertFalse(stats.prince)
    }

    @Test
    fun `a row Hypixel did not send is null, not zero`() {
        val stats = DungeonStats.read(tab())
        assertNull(stats.completedRooms, "no rooms row is not a floor with no rooms cleared")
        assertNull(stats.crypts, "no crypts row is not a run without crypts")
        assertNull(stats.puzzleCount)
        assertEquals(0, stats.puzzlesSolved, "counted, so zero solved is a true zero")
    }

    /**
     * **The mark, and the leniency this does not copy.** Upstream awards the Quiz its five bonus points if
     * the word "Quiz" appears in the tab list at all — solved or not, because the puzzle is *listed* as soon
     * as the floor has one. Here a puzzle counts only with `✔`, so an unsolved Oruo costs points instead of
     * granting them. Understating cannot announce a run that did not qualify.
     */
    @Test
    fun `only a ticked puzzle counts as solved`() {
        val stats = DungeonStats.read(
            tab(
                "Puzzles: (3)",
                "Teleport Maze: [✔] Notch",
                "Higher Or Lower: [✖] Some_One1",
                "Quiz: [✦] Somebody",
            ),
        )
        assertEquals(3, stats.puzzleCount)
        assertEquals(1, stats.puzzlesSolved, "the failed one and Oruo's own mark are not solved puzzles")
    }

    @Test
    fun `the mimic and the prince are read off their own ticks`() {
        val stats = DungeonStats.read(tab("Mimic: ✔", "Prince: ✔"))
        assertTrue(stats.mimic)
        assertTrue(stats.prince)
        val neither = DungeonStats.read(tab("Mimic: ✖", "Prince: ✖"))
        assertFalse(neither.mimic)
        assertFalse(neither.prince)
    }

    @Test
    fun `a party row is not a stat row`() {
        // `[102] [MVP+] Notch (Berserk VII)` has brackets and a colon-free shape; nothing may take it for
        // a puzzle or a count.
        val stats = DungeonStats.read(tab("Ult Cooldown: 12s", "Revives: 3"))
        assertNull(stats.completedRooms)
        assertEquals(0, stats.puzzlesSolved)
    }

    /** What upstream reads first, and what would make the whole formula unnecessary. */
    @Test
    fun `a published score is taken out of the footer`() {
        assertEquals(287, DungeonStats.footerScore("Blessing of Power VII\nDungeon Score: 287"))
        assertEquals(300, DungeonStats.footerScore("Score: 300 (S+)"))
        assertNull(DungeonStats.footerScore("Blessing of Power VII"))
        assertNull(DungeonStats.footerScore(null))
    }
}
