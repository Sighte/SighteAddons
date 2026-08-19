package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The score a Discord announcement is gated on, and the rows it is read out of.
 *
 * **What is worth pinning here is not the arithmetic** — that is [DungeonScoreTest]'s, and it is a port of
 * a formula nobody here invented. It is the two decisions around it:
 *
 *  - **which source answered.** A score read off Hypixel's own screen is exact; a computed one carries a
 *    Paul-sized offset it cannot know about. The order must not drift.
 *  - **what a missing input costs.** Every absent row makes the computed score *lower*, so a score built
 *    out of absences would be a gate that never fires — and one built out of defaults would be a gate
 *    that fires on nothing at all. [LiveScore.computed] refuses instead, and that refusal is the property.
 */
class LiveScoreTest {
    @BeforeEach
    fun clean() = LiveScore.reset()

    private fun tab(vararg extra: String) = listOf(
        "[102] [MVP+] Notch (Berserk VII)", "Dungeon Info", "Cleared: 68%",
    ) + extra.toList()

    @Test
    fun `the sidebar answers before the footer, and the footer before the formula`() {
        LiveScore.observe(
            floor = "M7", sidebarScore = 152, footer = { "Score: 999" }, rows = tab("Completed Rooms: 12"),
            clearedFraction = 0.68, secretsPercent = 80.0, inBoss = false, nowMs = 1_000_000, runTicks = 0,
        )
        assertEquals(152, LiveScore.score)
        assertEquals(LiveScore.Source.SIDEBAR, LiveScore.source)

        LiveScore.observe(
            floor = "M7", sidebarScore = null, footer = { "Dungeon Score: 287" }, rows = tab(),
            clearedFraction = 0.68, secretsPercent = 80.0, inBoss = false, nowMs = 1_000_000, runTicks = 0,
        )
        assertEquals(287, LiveScore.score)
        assertEquals(LiveScore.Source.FOOTER, LiveScore.source)
    }

    /**
     * The gate reads [LiveScore.score], and [SoloClear.passes] treats null as "not met". So a run whose
     * rows never parsed is silent rather than announced on a number assembled from defaults.
     */
    @Test
    fun `with nothing readable there is no score at all`() {
        LiveScore.observe(
            floor = "M7", sidebarScore = null, footer = { null }, rows = tab(),
            clearedFraction = null, secretsPercent = null, inBoss = false, nowMs = 1_000_000, runTicks = 0,
        )
        assertNull(LiveScore.score, "no completed-rooms row and no percentage is not a score of 20")
        assertEquals(LiveScore.Source.NONE, LiveScore.source)
        assertTrue(!SoloClear.passes(LiveScore.score, 300))
    }

    @Test
    fun `the formula runs once its two required rows are there`() {
        LiveScore.observe(
            floor = "M7", sidebarScore = null, footer = { null }, rows = tab("Completed Rooms: 20", "Crypts: 5"),
            clearedFraction = 1.0, secretsPercent = 100.0, inBoss = false, nowMs = 60_000, runTicks = 0,
        )
        assertEquals(LiveScore.Source.COMPUTED, LiveScore.source)
        val score = LiveScore.score
        assertTrue(score != null && score in 1..320, "a plausible total, not a sentinel: $score")
    }

    /**
     * `completed / cleared%`, which is how the floor's room count is learned at all — Hypixel never states
     * it. Rounded, and that rounding is one reason the computed score is the last resort.
     */
    @Test
    fun `the floor's room count is derived from the clear percentage`() {
        assertEquals(18, LiveScore.totalRooms(12, 0.68))
        assertEquals(20, LiveScore.totalRooms(20, 1.0))
        // Never zero: the formula divides by it, and a zero would read as a floor with no rooms.
        assertEquals(1, LiveScore.totalRooms(0, 0.5))
        assertNull(LiveScore.totalRooms(12, null))
        assertNull(LiveScore.totalRooms(null, 0.68))
        assertNull(LiveScore.totalRooms(12, 0.0), "a fresh run has cleared nothing and answers nothing")
    }

    /** Upstream's approximation for rooms the tab list has not counted yet, kept verbatim. */
    @Test
    fun `rooms the tab list has not counted yet`() {
        assertEquals(2, LiveScore.extraRooms(bloodDone = false, inBoss = false, isEntrance = false))
        assertEquals(1, LiveScore.extraRooms(bloodDone = false, inBoss = false, isEntrance = true))
        assertEquals(1, LiveScore.extraRooms(bloodDone = true, inBoss = false, isEntrance = false))
        assertEquals(0, LiveScore.extraRooms(bloodDone = true, inBoss = true, isEntrance = false))
    }

    /**
     * **The number that turns "nothing was announced" into a diagnosis.** A solo M7 on 2026-08-19 refused
     * correctly and left no trace of how close it came; the gate at 300 and a run that reached 268 looked
     * exactly like a run whose score was never readable.
     */
    @Test
    fun `the run's high water mark survives a falling score`() {
        LiveScore.observe(
            floor = "M7", sidebarScore = 268, footer = { null }, rows = tab(),
            clearedFraction = 0.9, secretsPercent = 90.0, inBoss = false, nowMs = 1_000, runTicks = 0,
        )
        assertEquals(268, LiveScore.high)

        // The score does fall: the time component decays as the run goes on.
        LiveScore.observe(
            floor = "M7", sidebarScore = 261, footer = { null }, rows = tab(),
            clearedFraction = 0.9, secretsPercent = 90.0, inBoss = false, nowMs = 2_000, runTicks = 0,
        )
        assertEquals(261, LiveScore.score)
        assertEquals(268, LiveScore.high, "how close the run got is not undone by getting slower")
    }

    @Test
    fun `only F6, F7, M6 and M7 have a mimic to infer`() {
        assertTrue(LiveScore.hasMimics("M7"))
        assertTrue(LiveScore.hasMimics("F6"))
        assertTrue(!LiveScore.hasMimics("F5"))
        assertTrue(!LiveScore.hasMimics("Entrance"))
        assertTrue(!LiveScore.hasMimics(null))
    }
}
