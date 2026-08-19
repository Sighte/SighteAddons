package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A probe writes into a debug log that gets uploaded, so the only property worth pinning here is the one
 * that cannot be taken back once it is wrong: **no party member's name may reach the log.**
 *
 * The stat rows are safe by construction — they begin with a word Hypixel chose, and a Minecraft name
 * has no spaces and no colon. The puzzle row is the exception and the reason this file exists:
 * `Teleport Maze: [✔] Somebody` carries the solver, and that solver is usually a stranger from party
 * finder who agreed to nothing.
 */
class ScoreProbeTest {
    @Test
    fun `a puzzle row is cut after its mark, so the solver never reaches the log`() {
        val out = ScoreProbe.readable(listOf("Teleport Maze: [✔] Notch", " Higher Or Lower: [✖] Some_One1"))
        assertEquals(listOf("Teleport Maze: [✔]", "Higher Or Lower: [✖]"), out)
        assertTrue(out.none { it.contains("Notch") || it.contains("Some_One1") })
    }

    @Test
    fun `the rows a live scorer would read are kept whole`() {
        val out = ScoreProbe.readable(
            listOf(
                "Completed Rooms: 12/20", "Crypts: 5", "Puzzles: (3)", "Mimic: ✔", "Prince: ✔",
                "Secrets Found: 80.6%", "Time: 06m 32s", "Cleared: 68%", "Score: 287",
            ),
        )
        assertEquals(9, out.size)
        assertTrue(out.contains("Crypts: 5"))
        assertTrue(out.contains("Cleared: 68%"))
    }

    @Test
    fun `a party row is not a stat row`() {
        // The rows this probe is pointed at cannot be names; the ones that are names must be dropped,
        // and the tab list is mostly the second kind.
        val out = ScoreProbe.readable(listOf("[102] [MVP+] Notch (Berserk VII)", null, "", "Ult Cooldown: 12s"))
        assertTrue(out.isEmpty(), "kept: $out")
    }

    /**
     * The whole question: if this matches on a real floor, the gate reads Hypixel's own score and
     * [DungeonScore] stays unused. If it never does, the score has to be computed — with the two known
     * offsets spelled out in [ScoreProbe].
     */
    @Test
    fun `a published score is taken out of the footer line it sits in`() {
        assertEquals("287", ScoreProbe.FOOTER_SCORE.matchEntire("Dungeon Score: 287")!!.groupValues[1])
        assertEquals("300", ScoreProbe.FOOTER_SCORE.matchEntire("Score: 300 (S+)")!!.groupValues[1])
        assertNull(ScoreProbe.FOOTER_SCORE.matchEntire("Blessing of Power VII"))
    }
}
